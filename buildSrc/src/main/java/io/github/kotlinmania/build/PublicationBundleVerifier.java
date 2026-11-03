package io.github.kotlinmania.build;

import static io.github.kotlinmania.build.AndroidPublicationVerifier.PublicationSupport.require;
import io.github.kotlinmania.build.AndroidPublicationVerifier.PublicationSupport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Validates all 17 KMP target publications and the root in an actual Central deployment ZIP. */
public final class PublicationBundleVerifier {
    private PublicationBundleVerifier() {}
    public static final String PLATFORM = "org.jetbrains.kotlin.platform.type";
    public static final String NATIVE = "org.jetbrains.kotlin.native.target";
    public static final String WASM = "org.jetbrains.kotlin.wasm.target";
    public static final String USAGE = "org.gradle.usage";
    public static final Map<String, Map<String, String>> TARGETS = targets();

    private static Map<String, Map<String, String>> targets() {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        result.put("android", Map.of(PLATFORM, "androidJvm", "org.gradle.libraryelements", "aar"));
        result.put("jvm", Map.of(PLATFORM, "jvm", "org.gradle.libraryelements", "jar"));
        result.put("js", Map.of(PLATFORM, "js"));
        result.put("wasmjs", Map.of(PLATFORM, "wasm", WASM, "js"));
        result.put("wasmwasi", Map.of(PLATFORM, "wasm", WASM, "wasi"));
        for (String target : List.of("linux_x64", "linux_arm64", "mingw_x64", "android_arm64", "android_x64", "macos_arm64",
            "ios_arm64", "ios_simulator_arm64", "tvos_arm64", "tvos_simulator_arm64", "watchos_device_arm64", "watchos_simulator_arm64")) {
            String suffix = target.startsWith("android_") ? target.replace("android_", "androidnative") : target.replace("_", "");
            result.put(suffix, Map.of(PLATFORM, "native", NATIVE, target));
        }
        return java.util.Collections.unmodifiableMap(result);
    }

    private static byte[] entry(Map<String, byte[]> archive, String name) {
        byte[] data = archive.get(name); require(data != null, "Missing publication archive entry: " + name); return data;
    }
    private static Map<String, byte[]> archive(byte[] data) throws IOException { return PublicationSupport.readZip(data, "Archive contains duplicate ZIP entries"); }
    private static int kotlinClasses(byte[] data, String namespace) throws IOException {
        Map<String, byte[]> jar = archive(data);
        require(jar.containsKey(namespace.replace('.', '/') + "/Args.class"), "Compiled Kotlin API Args is missing");
        require(jar.keySet().stream().anyMatch(name -> name.endsWith(".kotlin_module")), "Kotlin module descriptor is missing");
        return (int) jar.keySet().stream().filter(name -> name.endsWith(".class")).count();
    }

    public static Map<String, Object> inspectPayload(byte[] data, String filename, Map<String, Object> attributes, String namespace) throws IOException {
        String platform = PublicationSupport.string(attributes, PLATFORM);
        String usage = PublicationSupport.string(attributes, USAGE);
        Map<String, byte[]> payload = archive(data);
        Set<String> names = payload.keySet();
        if (usage.equals("kotlin-metadata")) {
            require(filename.endsWith(".jar"), "Metadata must use its declared JAR container");
            require(names.stream().noneMatch(n -> n.endsWith(".class")), "Common/native metadata contains target bytecode");
            if (platform.equals("common")) {
                require(names.contains("META-INF/kotlin-project-structure-metadata.json"), "Root Kotlin project structure metadata is missing");
                require(names.stream().anyMatch(n -> n.contains("/linkdata/") && n.endsWith(".knm")), "Root common Kotlin metadata is missing");
            }
            return Map.of("kind", "kotlin-metadata", "entry_count", names.size());
        }
        if (platform.equals("androidJvm")) {
            require(filename.endsWith(".aar"), "Android variant must publish an AAR");
            require(names.containsAll(Set.of("AndroidManifest.xml", "classes.jar")), "Android AAR lacks its manifest or compiled Kotlin classes");
            require(namespace.equals(PublicationSupport.xml(payload.get("AndroidManifest.xml")).getAttribute("package")), "Android manifest namespace differs");
            require(names.contains("META-INF/com/android/build/gradle/aar-metadata.properties"), "Android AAR metadata is missing");
            require(names.stream().noneMatch(n -> n.endsWith(".dex")), "A device APK is not a library AAR");
            return Map.of("kind", "android-aar", "kotlin_classes", kotlinClasses(payload.get("classes.jar"), namespace));
        }
        if (platform.equals("jvm")) {
            require(filename.endsWith(".jar"), "Kotlin/JVM variant must publish a JAR");
            require(!names.contains("AndroidManifest.xml"), "Kotlin/JVM artifact contains an Android manifest");
            return Map.of("kind", "kotlin-jvm-jar", "kotlin_classes", kotlinClasses(data, namespace));
        }
        require(filename.endsWith(".klib"), platform + " variant must publish a KLIB");
        require(names.stream().noneMatch(n -> n.endsWith(".class")), "KLIB contains JVM bytecode");
        require(names.contains("default/manifest"), "KLIB manifest is missing");
        require(names.stream().anyMatch(n -> n.startsWith("default/ir/") && payload.get(n).length > 0), "KLIB contains no compiled IR");
        require(names.stream().anyMatch(n -> n.startsWith("default/linkdata/") && payload.get(n).length > 0), "KLIB contains no link metadata");
        Map<String, String> manifest = new LinkedHashMap<>();
        for (String line : new String(payload.get("default/manifest"), StandardCharsets.UTF_8).split("\\R")) {
            int equals = line.indexOf('='); if (equals >= 0) manifest.put(line.substring(0, equals), line.substring(equals + 1));
        }
        require(platform.toUpperCase(java.util.Locale.ROOT).equals(manifest.get("builtins_platform")), "KLIB platform does not match variant");
        if (platform.equals("native")) require(attributes.get(NATIVE).equals(manifest.get("native_targets")), "KLIB native target does not match variant");
        if (platform.equals("wasm")) require(("wasm-" + attributes.get(WASM)).equals(manifest.get("wasm_targets")), "Wasm KLIB environment does not match variant");
        return Map.of("kind", "kotlin-klib", "manifest", manifest);
    }

    public static Map<String, Object> verify(Path bundlePath, String group, String module, String version, String namespace, boolean requireSignatures) throws IOException {
        String groupPath = group.replace('.', '/');
        Map<String, Map<String, String>> modules = new LinkedHashMap<>();
        modules.put(module, null); TARGETS.forEach((target, attrs) -> modules.put(module + "-" + target, attrs));
        List<Map<String, Object>> receipts = new ArrayList<>();
        Map<String, byte[]> bundle = PublicationSupport.readZip(bundlePath, "Bundle contains duplicate ZIP entries");
        Set<String> files = new LinkedHashSet<>();
        for (String name : bundle.keySet()) {
            require(!name.startsWith("/") && !List.of(name.split("/", -1)).contains(".."), "Unsafe deployment ZIP entry");
            if (!name.endsWith("/")) files.add(name);
        }
        Set<String> directories = new LinkedHashSet<>();
        modules.keySet().forEach(name -> directories.add(groupPath + "/" + name + "/" + version + "/"));
        require(files.stream().allMatch(name -> directories.stream().anyMatch(name::startsWith)), "Bundle includes another coordinate/version or repository-level files");
        require(files.stream().noneMatch(name -> name.endsWith(".apk")), "Device APK must not be published as a library");
        Map<String, Map<String, Object>> metadata = new LinkedHashMap<>();
        String rootPath = groupPath + "/" + module + "/" + version + "/" + module + "-" + version + ".module";
        for (Map.Entry<String, Map<String, String>> target : modules.entrySet()) {
            String name = target.getKey(); Map<String, String> expected = target.getValue();
            String base = groupPath + "/" + name + "/" + version + "/" + name + "-" + version;
            Map<String, Object> data = PublicationSupport.jsonObject(entry(bundle, base + ".module"));
            Map<String, Object> component = PublicationSupport.object(data.get("component"));
            String owner = component.containsKey("url") ? module : name;
            require(group.equals(component.get("group")) && owner.equals(component.get("module")) && version.equals(component.get("version")), "Wrong module coordinates: " + name);
            if (component.containsKey("url")) require(PublicationSupport.relatedPath(base, PublicationSupport.string(component, "url")).equals(rootPath), "Target component points to the wrong root publication: " + name);
            metadata.put(name, data);
            Element pom = PublicationSupport.xml(entry(bundle, base + ".pom"));
            String packaging = expected == null || expected.get(PLATFORM).equals("jvm") ? "jar" : expected.get(PLATFORM).equals("androidJvm") ? "aar" : "klib";
            require(packaging.equals(childText(pom, "packaging", "jar")), "Wrong POM packaging: " + name);
            for (Map.Entry<String, String> field : Map.of("groupId", group, "artifactId", name, "version", version).entrySet()) require(field.getValue().equals(childText(pom, field.getKey(), null)), "Wrong POM " + field.getKey() + ": " + name);
            for (String classifier : List.of("sources", "javadoc")) {
                Map<String, byte[]> documentation = archive(entry(bundle, base + "-" + classifier + ".jar"));
                require(documentation.keySet().stream().anyMatch(n -> !n.endsWith("/") && !n.startsWith("META-INF/")), "Empty " + classifier + " archive: " + name);
            }
            Set<String> usages = new LinkedHashSet<>();
            for (Object raw : PublicationSupport.array(data.get("variants"))) {
                Map<String, Object> variant = PublicationSupport.object(raw);
                Map<String, Object> attributes = PublicationSupport.object(variant.get("attributes"));
                List<Map<String, Object>> artifacts = new ArrayList<>();
                Map<String, Object> variantReceipt = new LinkedHashMap<>();
                variantReceipt.put("module", name); variantReceipt.put("variant", variant.get("name")); variantReceipt.put("attributes", attributes); variantReceipt.put("files", artifacts);
                for (Object descriptor : PublicationSupport.array(variant.getOrDefault("files", List.of()))) {
                    Map<String, Object> artifact = PublicationSupport.object(descriptor);
                    String path = PublicationSupport.relatedPath(base, PublicationSupport.string(artifact, "url"));
                    require(files.contains(path), "Metadata references a missing artifact: " + path);
                    byte[] payload = bundle.get(path);
                    require(PublicationSupport.sameSize(artifact.get("size"), payload.length), "Artifact size mismatch: " + path);
                    for (String algorithm : List.of("sha512", "sha256", "sha1", "md5")) require(PublicationSupport.hash(algorithm, payload).equals(artifact.get(algorithm)), "Artifact " + algorithm + " mismatch: " + path);
                    Map<String, Object> receipt = new LinkedHashMap<>();
                    receipt.put("path", path); receipt.put("sha256", PublicationSupport.hash("sha256", payload));
                    if ("library".equals(attributes.get("org.gradle.category"))) {
                        if (expected != null) require(expected.entrySet().stream().allMatch(e -> e.getValue().equals(attributes.get(e.getKey()))), "Wrong target attributes: " + name + "/" + variant.get("name"));
                        receipt.putAll(inspectPayload(payload, path, attributes, namespace));
                        usages.add(PublicationSupport.string(attributes, USAGE));
                    }
                    artifacts.add(receipt);
                }
                receipts.add(variantReceipt);
            }
            Set<String> required = expected == null ? Set.of("kotlin-metadata") : switch (expected.get(PLATFORM)) {
                case "androidJvm", "jvm" -> Set.of("java-api", "java-runtime");
                case "native" -> Set.of("kotlin-api");
                default -> Set.of("kotlin-api", "kotlin-runtime");
            };
            require(usages.containsAll(required), "Missing library usage variants for " + name + ": " + required);
        }
        Set<String> referenced = new LinkedHashSet<>();
        for (Object raw : PublicationSupport.array(metadata.get(module).get("variants"))) {
            Map<String, Object> variant = PublicationSupport.object(raw);
            if (!variant.containsKey("available-at")) continue;
            Map<String, Object> reference = PublicationSupport.object(variant.get("available-at"));
            String name = PublicationSupport.string(reference, "module");
            require(modules.containsKey(name) && !name.equals(module), "Root points to an unexpected target");
            require(group.equals(reference.get("group")) && version.equals(reference.get("version")), "Root target reference uses the wrong coordinates");
            String referencePath = PublicationSupport.relatedPath(rootPath, PublicationSupport.string(reference, "url"));
            require(PublicationSupport.jsonObject(entry(bundle, referencePath)).equals(metadata.get(name)), "Root target metadata URL is incorrect");
            require(PublicationSupport.array(metadata.get(name).get("variants")).stream().map(PublicationSupport::object)
                .anyMatch(v -> v.get("name").equals(variant.get("name")) && v.get("attributes").equals(variant.get("attributes"))), "Root and target attributes disagree");
            referenced.add(name);
        }
        Set<String> targets = new LinkedHashSet<>(modules.keySet()); targets.remove(module);
        require(referenced.equals(targets), "Root metadata does not expose every supported target");
        int checksumCount = 0; int signatures = 0;
        for (String path : new TreeSet<>(files)) {
            String extension = path.substring(path.lastIndexOf('.') + 1);
            if (Set.of("sha512", "sha256", "sha1", "md5").contains(extension)) {
                String target = path.substring(0, path.lastIndexOf('.'));
                require(new String(bundle.get(path), StandardCharsets.UTF_8).strip().equals(PublicationSupport.hash(extension, entry(bundle, target))), "Checksum sidecar mismatch: " + path);
                checksumCount++;
            } else if (extension.equals("asc")) {
                require(JniArtifactVerifier.Binary.contains(bundle.get(path), "BEGIN PGP SIGNATURE"), "Invalid signature container: " + path); signatures++;
            } else if (requireSignatures) require(files.contains(path + ".asc"), "Unsigned publication file: " + path);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("bundle", bundlePath.toAbsolutePath().normalize().toString()); result.put("sha256", PublicationSupport.fingerprint(bundlePath));
        result.put("group", group); result.put("module", module); result.put("version", version); result.put("publication_count", modules.size());
        result.put("target_count", TARGETS.size()); result.put("checksum_count", checksumCount); result.put("signature_containers", signatures);
        result.put("signatures_required", requireSignatures); result.put("variants", receipts);
        return result;
    }
    private static String childText(Element parent, String name, String fallback) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element child && name.equals(child.getLocalName() == null ? child.getTagName() : child.getLocalName())) return child.getTextContent();
        }
        return fallback;
    }
    public static int run(String... args) throws IOException {
        PublicationSupport.Options options = PublicationSupport.Options.parse(args, Set.of("group", "module", "version", "namespace", "receipt"), Set.of("require-signatures"));
        Map<String, Object> result = verify(options.path(), options.one("group"), options.one("module"), options.one("version"), options.one("namespace"), options.flag("require-signatures"));
        Json.write(Path.of(options.one("receipt")), result);
        System.out.println("Verified bundle payloads and metadata: " + result.get("target_count") + " KMP targets, " + result.get("publication_count") + " publications. Receipt: " + options.one("receipt"));
        return 0;
    }
    public static void main(String[] args) throws IOException { System.exit(run(args)); }
}
