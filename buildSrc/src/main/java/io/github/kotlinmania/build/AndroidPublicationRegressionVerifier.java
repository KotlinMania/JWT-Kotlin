package io.github.kotlinmania.build;

import io.github.kotlinmania.build.AndroidPublicationVerifier.PublicationSupport;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Negative acceptance checks against the real, already built async-trait publication bundle. */
public final class AndroidPublicationRegressionVerifier {
    private AndroidPublicationRegressionVerifier() {}

    private record Reference(Path bundle, String group, String module, String version, Path scratch,
                             Map<String, byte[]> entries, String root, String metadata, String aar, String pom) {}

    private static final class Mutation {
        private final Reference reference;
        private final Map<String, byte[]> entries;

        Mutation(Reference reference) {
            this.reference = reference;
            entries = new LinkedHashMap<>(reference.entries());
        }

        void replaceAar(byte[] payload) {
            entries.put(reference.aar(), payload);
            Map<String, Object> metadata = PublicationSupport.jsonObject(entries.get(reference.metadata()));
            List<Object> variants = new ArrayList<>();
            for (Object raw : PublicationSupport.array(metadata.get("variants"))) {
                Map<String, Object> variant = PublicationSupport.object(raw);
                if ("aar".equals(PublicationSupport.object(variant.getOrDefault("attributes", Map.of())).get("org.gradle.libraryelements"))) {
                    List<Object> descriptors = new ArrayList<>();
                    for (Object descriptor : PublicationSupport.array(variant.get("files"))) {
                        Map<String, Object> file = PublicationSupport.object(descriptor);
                        file.put("size", payload.length);
                        for (String algorithm : List.of("sha256", "sha512", "sha1", "md5")) file.put(algorithm, PublicationSupport.hash(algorithm, payload));
                        descriptors.add(file);
                    }
                    variant.put("files", descriptors);
                }
                variants.add(variant);
            }
            metadata.put("variants", variants);
            entries.put(reference.metadata(), Json.stringify(metadata).getBytes(StandardCharsets.UTF_8));
        }

        void reject(String reason) throws IOException {
            Path invalid = reference.scratch().resolve("invalid.zip");
            Files.write(invalid, packed(entries));
            try {
                AndroidPublicationVerifier.verifyAsyncTrait(invalid, reference.group(), reference.module(), reference.version());
            } catch (IllegalArgumentException | IOException expected) {
                if (expected.getMessage() != null && expected.getMessage().contains(reason)) return;
                throw new IllegalStateException("Expected rejection containing '" + reason + "', received " + expected, expected);
            } finally {
                Files.deleteIfExists(invalid);
            }
            throw new IllegalStateException("Invalid actual publication was accepted; expected " + reason);
        }
    }

    public static int run(String... arguments) throws IOException {
        PublicationSupport.Options options = PublicationSupport.Options.parse(arguments, Set.of("group", "module", "version", "scratch"), Set.of());
        Path scratchRoot = Path.of(options.one("scratch"));
        Files.createDirectories(scratchRoot);
        Path scratch = Files.createTempDirectory(scratchRoot, "android-publication-regressions-");
        try {
            String group = options.one("group");
            String module = options.one("module");
            String version = options.one("version");
            String prefix = group.replace('.', '/');
            String root = prefix + "/" + module + "/" + version + "/" + module + "-" + version + ".module";
            String stem = prefix + "/" + module + "-android/" + version + "/" + module + "-android-" + version;
            Reference reference = new Reference(options.path(), group, module, version, scratch,
                    PublicationSupport.readZip(options.path(), "Duplicate deployment ZIP entries"), root, stem + ".module", stem + ".aar", stem + ".pom");
            PublicationSupport.require("passed".equals(AndroidPublicationVerifier.verifyAsyncTrait(reference.bundle(), group, module, version).get("status")),
                    "The actual reference publication must pass first");

            Mutation missing = new Mutation(reference);
            missing.entries.remove(reference.aar());
            missing.reject("Missing Android publication entry");

            Mutation renamed = new Mutation(reference);
            renamed.replaceAar(PublicationSupport.readZip(renamed.entries.get(reference.aar()), "Duplicate AAR entry").get("classes.jar"));
            renamed.reject("AAR has no Android manifest");

            Mutation api = new Mutation(reference);
            Map<String, byte[]> aar = PublicationSupport.readZip(api.entries.get(reference.aar()), "Duplicate AAR entry");
            Map<String, byte[]> classes = PublicationSupport.readZip(aar.get("classes.jar"), "Duplicate classes.jar entry");
            PublicationSupport.require(classes.remove("io/github/kotlinmania/asynctrait/AsyncTraitKt.class") != null, "The reference expansion API is missing");
            aar.put("classes.jar", packed(classes));
            api.replaceAar(packed(aar));
            api.reject("missing public API class");

            Mutation redirect = new Mutation(reference);
            Map<String, Object> rootMetadata = PublicationSupport.jsonObject(redirect.entries.get(root));
            List<Object> rootVariants = new ArrayList<>();
            for (Object raw : PublicationSupport.array(rootMetadata.get("variants"))) {
                Map<String, Object> variant = PublicationSupport.object(raw);
                if ("aar".equals(PublicationSupport.object(variant.getOrDefault("attributes", Map.of())).get("org.gradle.libraryelements"))) {
                    Map<String, Object> available = PublicationSupport.object(variant.get("available-at"));
                    available.put("version", "0.0.0");
                    variant.put("available-at", available);
                }
                rootVariants.add(variant);
            }
            rootMetadata.put("variants", rootVariants);
            redirect.entries.put(root, Json.stringify(rootMetadata).getBytes(StandardCharsets.UTF_8));
            redirect.reject("redirects to the wrong publication");

            Mutation hash = new Mutation(reference);
            byte[] original = hash.entries.get(reference.aar());
            byte[] changed = Arrays.copyOf(original, original.length + 7);
            System.arraycopy("changed".getBytes(StandardCharsets.US_ASCII), 0, changed, original.length, 7);
            hash.entries.put(reference.aar(), changed);
            hash.reject("size or SHA-256 disagrees");

            Mutation dependency = new Mutation(reference);
            Map<String, Object> metadata = PublicationSupport.jsonObject(dependency.entries.get(reference.metadata()));
            List<Object> variants = new ArrayList<>();
            for (Object raw : PublicationSupport.array(metadata.get("variants"))) {
                Map<String, Object> variant = PublicationSupport.object(raw);
                if ("java-api".equals(PublicationSupport.object(variant.getOrDefault("attributes", Map.of())).get("org.gradle.usage"))) {
                    variant.put("dependencies", PublicationSupport.array(variant.getOrDefault("dependencies", List.of())).stream()
                            .filter(edge -> !"syn-kotlin".equals(PublicationSupport.object(edge).get("module"))).toList());
                }
                variants.add(variant);
            }
            metadata.put("variants", variants);
            dependency.entries.put(reference.metadata(), Json.stringify(metadata).getBytes(StandardCharsets.UTF_8));
            dependency.reject("Public signature dependencies missing");

            Mutation packaging = new Mutation(reference);
            String pom = new String(packaging.entries.get(reference.pom()), StandardCharsets.UTF_8);
            PublicationSupport.require(pom.contains("<packaging>aar</packaging>"), "Reference POM does not declare AAR packaging");
            packaging.entries.put(reference.pom(), pom.replace("<packaging>aar</packaging>", "<packaging>jar</packaging>").getBytes(StandardCharsets.UTF_8));
            packaging.reject("Incorrect Android POM packaging");
            System.out.println("AndroidPublicationRegressionVerifier: 8 real-bundle acceptance checks passed");
            return 0;
        } finally {
            try (var entries = Files.walk(scratch)) {
                for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) Files.delete(entry);
            }
        }
    }

    private static byte[] packed(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream archive = new ZipOutputStream(output)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                archive.putNextEntry(new ZipEntry(entry.getKey()));
                archive.write(entry.getValue());
                archive.closeEntry();
            }
        }
        return output.toByteArray();
    }

    public static void main(String[] arguments) throws IOException {
        System.exit(run(arguments));
    }
}
