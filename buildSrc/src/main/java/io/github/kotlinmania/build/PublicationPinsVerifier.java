package io.github.kotlinmania.build;

import io.github.kotlinmania.build.AndroidPublicationVerifier.PublicationSupport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.w3c.dom.Element;

/** Checks actual root and Android publication files against declared exact pins. */
public final class PublicationPinsVerifier {
    private PublicationPinsVerifier() {}

    public record Dependency(String group, String module) {
        @Override
        public String toString() {
            return group + ":" + module;
        }
    }

    public static Map<String, Object> verify(
        Path repository,
        String group,
        String module,
        String version,
        Map<Dependency, String> expected,
        Map<Dependency, String> pomModules
    ) throws IOException {
        for (Map.Entry<Dependency, String> edge : expected.entrySet()) {
            String pin = edge.getValue();
            PublicationSupport.require(
                edge.getKey().group() != null && !edge.getKey().group().isEmpty()
                    && edge.getKey().module() != null && !edge.getKey().module().isEmpty()
                    && pin != null && !pin.isEmpty() && pin.chars().noneMatch(c -> "+[](),".indexOf(c) >= 0) && !pin.startsWith("latest."),
                "Expected exact declared dependency pins");
        }
        PublicationSupport.require(expected.keySet().containsAll(pomModules.keySet()), "POM mapping has no declared dependency");
        Set<Dependency> mapped = new LinkedHashSet<>();
        for (Dependency dependency : expected.keySet()) {
            mapped.add(new Dependency(dependency.group(), pomModules.getOrDefault(dependency, dependency.module())));
        }
        PublicationSupport.require(mapped.size() == expected.size(), "Ambiguous Android POM dependency mapping");
        Path directory = repository.resolve(group.replace('.', '/'));
        Path root = directory.resolve(module).resolve(version).resolve(module + "-" + version);
        Path target = directory.resolve(module + "-android").resolve(version).resolve(module + "-android-" + version);
        Map<String, Path> paths = new LinkedHashMap<>();
        paths.put("root_module", Path.of(root + ".module"));
        paths.put("root_pom", Path.of(root + ".pom"));
        paths.put("android_module", Path.of(target + ".module"));
        paths.put("android_pom", Path.of(target + ".pom"));
        for (Path path : paths.values()) {
            PublicationSupport.require(Files.isRegularFile(path) && !Files.isSymbolicLink(path), "Missing real publication: " + path);
        }
        Map<String, Object> rootMetadata = PublicationSupport.object(Json.read(paths.get("root_module")));
        Map<String, Object> androidMetadata = PublicationSupport.object(Json.read(paths.get("android_module")));
        for (Map<String, Object> metadata : List.of(rootMetadata, androidMetadata)) {
            PublicationSupport.require("1.1".equals(metadata.get("formatVersion")), "Unsupported Gradle module metadata");
            Map<String, Object> component = PublicationSupport.object(metadata.get("component"));
            PublicationSupport.require(
                group.equals(component.get("group")) && module.equals(component.get("module")) && version.equals(component.get("version")),
                "Wrong owning component");
        }
        String rootUrl = paths.get("android_module").getParent().relativize(paths.get("root_module")).toString().replace('\\', '/');
        PublicationSupport.require(
            rootUrl.equals(PublicationSupport.object(androidMetadata.get("component")).get("url")),
            "Android metadata points to a different root");
        List<Map<String, Object>> rootVariants = objects(rootMetadata.get("variants"));
        List<Map<String, Object>> common = new ArrayList<>();
        for (Map<String, Object> variant : rootVariants) {
            Map<String, Object> attributes = PublicationSupport.object(variant.get("attributes"));
            if ("kotlin-metadata".equals(attributes.get("org.gradle.usage")) && !variant.containsKey("available-at")) {
                common.add(variant);
            }
        }
        PublicationSupport.require(common.size() == 1, "Expected one root metadata dependency variant");
        Map<String, String> rootPins = dependencies(common.get(0), true, expected);
        List<String> redirects = new ArrayList<>();
        for (Map<String, Object> variant : rootVariants) {
            if (variant.containsKey("available-at")) {
                Map<String, Object> edge = PublicationSupport.object(variant.get("available-at"));
                PublicationSupport.require(
                    group.equals(edge.get("group")) && version.equals(edge.get("version")),
                    "Stale target redirect: " + PublicationSupport.string(variant, "name"));
                Map<String, Object> attributes = PublicationSupport.object(variant.get("attributes"));
                if ("androidJvm".equals(attributes.get("org.jetbrains.kotlin.platform.type"))
                    && "library".equals(attributes.get("org.gradle.category"))) {
                    PublicationSupport.require((module + "-android").equals(edge.get("module")), "Wrong Android target redirect");
                    Path actual = paths.get("root_module").getParent().resolve(PublicationSupport.string(edge, "url")).normalize();
                    PublicationSupport.require(actual.equals(paths.get("android_module").normalize()), "Wrong Android target metadata URL");
                    redirects.add(PublicationSupport.string(attributes, "org.gradle.usage"));
                }
            }
        }
        redirects.sort(String::compareTo);
        PublicationSupport.require(redirects.equals(List.of("java-api", "java-runtime")), "Missing Android API/runtime redirects");
        List<Map<String, Object>> variants = new ArrayList<>();
        Set<String> usages = new LinkedHashSet<>();
        for (Map<String, Object> variant : objects(androidMetadata.get("variants"))) {
            Map<String, Object> attributes = PublicationSupport.object(variant.get("attributes"));
            if ("library".equals(attributes.get("org.gradle.category"))) {
                variants.add(variant);
                usages.add(PublicationSupport.string(attributes, "org.gradle.usage"));
            }
        }
        PublicationSupport.require(
            variants.size() == 2 && usages.equals(Set.of("java-api", "java-runtime")),
            "Missing Android API/runtime variants");
        Map<String, Object> androidPins = new LinkedHashMap<>();
        for (Map<String, Object> variant : variants) {
            String usage = PublicationSupport.string(PublicationSupport.object(variant.get("attributes")), "org.gradle.usage");
            androidPins.put(usage, dependencies(variant, usage.equals("java-runtime"), expected));
            List<Object> files = PublicationSupport.array(variant.getOrDefault("files", List.of()));
            PublicationSupport.require(files.size() == 1, "Expected the real Android AAR");
            Map<String, Object> descriptor = PublicationSupport.object(files.get(0));
            Path aar = target.getParent().resolve(PublicationSupport.string(descriptor, "url"));
            PublicationSupport.require(
                aar.equals(Path.of(target + ".aar")) && Files.isRegularFile(aar) && !Files.isSymbolicLink(aar),
                "Wrong or missing Android AAR");
            PublicationSupport.require(
                PublicationSupport.sameSize(descriptor.get("size"), Files.size(aar))
                    && PublicationSupport.fingerprint(aar).equals(descriptor.get("sha256")),
                "Android AAR fingerprint mismatch");
        }
        Map<String, Object> pomPins = new LinkedHashMap<>();
        for (String name : List.of("root_pom", "android_pom")) {
            Element pom = PublicationSupport.xml(Files.readAllBytes(paths.get(name)));
            String expectedModule = name.equals("root_pom") ? module : module + "-android";
            for (Map.Entry<String, String> field : Map.of("groupId", group, "artifactId", expectedModule, "version", version).entrySet()) {
                PublicationSupport.require(
                    field.getValue().equals(PublicationSupport.childText(pom, field.getKey(), null)),
                    "Wrong POM identity: " + name);
            }
            Map<String, Object> found = new LinkedHashMap<>();
            for (Element edge : PublicationSupport.pomDependencies(pom)) {
                String edgeGroup = PublicationSupport.childText(edge, "groupId", null);
                String edgeModule = PublicationSupport.childText(edge, "artifactId", null);
                Dependency key = null;
                for (Dependency candidate : expected.keySet()) {
                    String selectedModule = name.equals("android_pom") ? pomModules.getOrDefault(candidate, candidate.module()) : candidate.module();
                    if (candidate.group().equals(edgeGroup) && selectedModule.equals(edgeModule)) {
                        key = candidate;
                        break;
                    }
                }
                if (key == null) {
                    continue;
                }
                PublicationSupport.require(!found.containsKey(key.toString()), "Duplicate POM dependency");
                String actual = PublicationSupport.childText(edge, "version", null);
                PublicationSupport.require(
                    expected.get(key).equals(actual),
                    "Wrong POM dependency pin: " + name + " " + key + " " + actual);
                found.put(key.toString(), Map.of(
                    "module", edgeModule, "version", actual, "scope", PublicationSupport.childText(edge, "scope", "compile")));
            }
            PublicationSupport.require(
                found.size() == expected.size(),
                "Missing POM dependencies: " + name + "; declare actual target mapping with --pom-dependency when Android selects a target artifact");
            pomPins.put(name, found);
        }
        Map<String, Object> fileReceipts = new LinkedHashMap<>();
        for (Map.Entry<String, Path> path : paths.entrySet()) {
            fileReceipts.put(path.getKey(), Map.of("path", path.getValue().toString(), "sha256", PublicationSupport.fingerprint(path.getValue())));
        }
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("status", "passed");
        receipt.put("coordinates", group + ":" + module + ":" + version);
        receipt.put("files", fileReceipts);
        receipt.put("root_dependency_pins", rootPins);
        receipt.put("android_dependency_pins", androidPins);
        receipt.put("pom_dependency_pins", pomPins);
        receipt.put("coverage", "Declared common API/implementation pins; target-specific dependencies require separate reviewed guards.");
        receipt.put("api_scope_note", "Implementation dependencies may be absent from Android API metadata; runtime pins must match.");
        receipt.put("independent_consumer", "Separate compilation/resolution required");
        return receipt;
    }

    private static List<Map<String, Object>> objects(Object value) {
        return PublicationSupport.array(value).stream().map(PublicationSupport::object).toList();
    }

    private static Map<String, String> dependencies(
        Map<String, Object> variant, boolean required, Map<Dependency, String> expected
    ) {
        Map<String, String> selected = new LinkedHashMap<>();
        for (Object raw : PublicationSupport.array(variant.getOrDefault("dependencies", List.of()))) {
            Map<String, Object> edge = PublicationSupport.object(raw);
            Dependency key = new Dependency(PublicationSupport.string(edge, "group"), PublicationSupport.string(edge, "module"));
            if (!expected.containsKey(key)) {
                continue;
            }
            PublicationSupport.require(!selected.containsKey(key.toString()), "Duplicate dependency: " + key);
            Object actual = PublicationSupport.object(edge.getOrDefault("version", Map.of())).get("requires");
            PublicationSupport.require(
                expected.get(key).equals(actual),
                "Wrong dependency pin in " + PublicationSupport.string(variant, "name") + ": " + key + " " + actual + " != " + expected.get(key));
            selected.put(key.toString(), (String) actual);
        }
        PublicationSupport.require(
            !required || selected.size() == expected.size(),
            "Missing required dependency pins in " + PublicationSupport.string(variant, "name"));
        return selected;
    }

    public static int run(String... args) throws IOException {
        PublicationSupport.Options options = PublicationSupport.Options.parse(
            args, Set.of("group", "module", "version", "dependency", "pom-dependency", "receipt", "source-head"), Set.of());
        Path receiptPath = Path.of(options.one("receipt"));
        Map<String, Object> receipt;
        try {
            String head = options.one("source-head");
            PublicationSupport.require(head.matches("[0-9a-f]{40}"), "Expected an exact source revision");
            Map<Dependency, String> expected = new LinkedHashMap<>();
            PublicationSupport.require(!options.many("dependency").isEmpty(), "--dependency is required");
            for (String value : options.many("dependency")) {
                String[] parts = value.split(":", -1);
                PublicationSupport.require(parts.length == 3, "Expected group:module:version");
                Dependency key = new Dependency(parts[0], parts[1]);
                PublicationSupport.require(!expected.containsKey(key), "Duplicate expected pin");
                expected.put(key, parts[2]);
            }
            Map<Dependency, String> mappings = new LinkedHashMap<>();
            for (String value : options.many("pom-dependency")) {
                String[] parts = value.split(":", -1);
                PublicationSupport.require(parts.length == 3 && !parts[0].isEmpty() && !parts[1].isEmpty() && !parts[2].isEmpty(),
                    "Invalid or duplicate Android POM mapping");
                Dependency key = new Dependency(parts[0], parts[1]);
                PublicationSupport.require(!mappings.containsKey(key), "Invalid or duplicate Android POM mapping");
                mappings.put(key, parts[2]);
            }
            receipt = verify(options.path(), options.one("group"), options.one("module"), options.one("version"), expected, mappings);
            receipt.put("source_head", head);
        } catch (Exception error) {
            receipt = PublicationSupport.failure(error);
        }
        Json.write(receiptPath, receipt);
        System.out.println(Json.stringify(receipt));
        return "passed".equals(receipt.get("status")) ? 0 : 1;
    }

    public static void main(String[] args) throws IOException {
        System.exit(run(args));
    }
}
