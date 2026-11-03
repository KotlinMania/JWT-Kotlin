package io.github.kotlinmania.build;

import io.github.kotlinmania.build.AndroidPublicationVerifier.ApiDependency;
import io.github.kotlinmania.build.AndroidPublicationVerifier.PublicationSupport;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Verifies Ratatui's actual staged facade/components and independently executed consumers. */
public final class RatatuiConsumerVerifier {
    private static final String GROUP = "io.github.kotlinmania";
    private static final String MODULE = "ratatui-kotlin";
    public static final Set<String> TESTS = Set.of("publishedTextUsesUnicodeWidth", "publishedModifierExposesBitflags",
            "publishedErrorsRetainRustDiagnostics", "publishedColorRetainsItsDeserializeContract",
            "publishedModifierRetainsSerdeContracts", "publishedStyleRetainsSerdeContracts");
    private static final Map<String, Component> COMPONENTS = components();
    private static final Map<String, String> SERDE = Map.of(
            "ratatui/style/Modifier", "io/github/kotlinmania/serdecore/ser/Serialize",
            "ratatui/style/Style", "io/github/kotlinmania/serdecore/ser/Serialize",
            "ratatui/style/Modifier$Companion", "io/github/kotlinmania/serdecore/de/Deserialize",
            "ratatui/style/Style$Companion", "io/github/kotlinmania/serdecore/de/Deserialize",
            "ratatui/style/Color$Companion", "io/github/kotlinmania/serdecore/de/Deserialize");

    private RatatuiConsumerVerifier() {}

    private record Component(Set<String> classes, List<ApiDependency> edges) {}

    private static Map<String, Component> components() {
        Map<String, Component> values = new LinkedHashMap<>();
        values.put(MODULE, new Component(Set.of(), edges("ratatui-core", "ratatui-widgets", "ratatui-macros")));
        values.put("ratatui-core", new Component(Set.of("ratatui/text/Text.class", "ratatui/style/Modifier.class",
                "ratatui/style/ParseColorError.class", "ratatui/style/Color$Companion.class", "ratatui/style/Style.class",
                "ratatui/style/Style$Companion.class", "ratatui/style/Modifier$Companion.class", "ratatui/style/TryFromColorError.class",
                "ratatui/style/ColorParseResult.class", "ratatui/style/Ansi256ColorResult.class", "ratatui/style/AnsiColorResult.class",
                "ratatui/style/RgbColorResult.class"), edges("bitflags-kotlin", "anstyle-kotlin", "unicode-width-kotlin", "serde-core")));
        values.put("ratatui-widgets", new Component(Set.of("ratatui/widgets/list/ListState.class", "ratatui/widgets/table/TableState.class",
                "ratatui/widgets/scrollbar/ScrollbarState.class"), edges("ratatui-core")));
        values.put("ratatui-macros", new Component(Set.of("ratatui_macros/SpanKt.class", "ratatui_macros/RowKt.class"),
                edges("ratatui-core", "ratatui-widgets")));
        return values;
    }

    private static List<ApiDependency> edges(String... modules) {
        return Arrays.stream(modules).map(module -> new ApiDependency(GROUP, module, module + "-android")).toList();
    }

    public static Map<String, Object> classHierarchy(byte[] payload) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            require(input.readInt() == 0xcafebabe, "Invalid class payload");
            input.readUnsignedShort();
            input.readUnsignedShort();
            Object[] pool = new Object[input.readUnsignedShort()];
            for (int index = 1; index < pool.length; index++) {
                int tag = input.readUnsignedByte();
                switch (tag) {
                    case 1 -> pool[index] = input.readUTF();
                    case 7 -> pool[index] = input.readUnsignedShort();
                    case 8, 16, 19, 20 -> input.skipNBytes(2);
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> input.skipNBytes(4);
                    case 5, 6 -> { input.skipNBytes(8); index++; }
                    case 15 -> input.skipNBytes(3);
                    default -> throw new IllegalArgumentException("Unknown class constant tag: " + tag);
                }
            }
            input.readUnsignedShort();
            String own = className(pool, input.readUnsignedShort());
            int parent = input.readUnsignedShort();
            String superclass = parent == 0 ? "" : className(pool, parent);
            int count = input.readUnsignedShort();
            List<String> interfaces = new ArrayList<>();
            for (int index = 0; index < count; index++) interfaces.add(className(pool, input.readUnsignedShort()));
            return Map.of("class", own, "superclass", superclass, "interfaces", interfaces);
        }
    }

    private static String className(Object[] pool, int index) {
        require(index > 0 && index < pool.length && pool[index] instanceof Integer, "Invalid class constant reference");
        int name = (Integer) pool[index];
        require(name > 0 && name < pool.length && pool[name] instanceof String, "Invalid class name reference");
        return (String) pool[name];
    }

    private static Map<String, Object> verifyDiagnosticPayloads(Map<String, byte[]> archive, String version) throws IOException {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> platform : coreJars(archive, version).entrySet()) {
            Map<String, byte[]> jar = PublicationSupport.readZip(platform.getValue(), "Duplicate core JAR entry");
            Map<String, Object> shapes = new LinkedHashMap<>();
            for (Map.Entry<String, String> expected : Map.of("ParseColorError", "java/lang/Object", "TryFromColorError", "java/lang/Enum").entrySet()) {
                Map<String, Object> shape = classHierarchy(member(jar, "ratatui/style/" + expected.getKey() + ".class"));
                require(shape.get("class").equals("ratatui/style/" + expected.getKey()) && shape.get("superclass").equals(expected.getValue())
                        && ((List<?>) shape.get("interfaces")).isEmpty(), "Leaked diagnostic inheritance: " + expected.getKey());
                shapes.put(expected.getKey(), shape);
            }
            result.put(platform.getKey(), shapes);
        }
        return result;
    }

    private static Map<String, Object> verifySerdePayloads(Map<String, byte[]> archive, String version) throws IOException {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> platform : coreJars(archive, version).entrySet()) {
            Map<String, byte[]> jar = PublicationSupport.readZip(platform.getValue(), "Duplicate core JAR entry");
            Map<String, Object> shapes = new LinkedHashMap<>();
            for (Map.Entry<String, String> expected : SERDE.entrySet()) {
                Map<String, Object> shape = classHierarchy(member(jar, expected.getKey() + ".class"));
                require(shape.get("class").equals(expected.getKey()) && ((List<?>) shape.get("interfaces")).contains(expected.getValue()),
                        "Missing real Serde contract: " + platform.getKey() + "/" + expected.getKey());
                shapes.put(expected.getKey(), shape);
            }
            result.put(platform.getKey(), shapes);
        }
        return result;
    }

    private static Map<String, byte[]> coreJars(Map<String, byte[]> archive, String version) throws IOException {
        Map<String, byte[]> aar = PublicationSupport.readZip(member(archive, metadataPath("ratatui-core", version, "-android") + ".aar"), "Duplicate core AAR entry");
        return Map.of("android", member(aar, "classes.jar"), "jvm", member(archive, metadataPath("ratatui-core", version, "-jvm") + ".jar"));
    }

    public static String metadataPath(String module, String version, String suffix) {
        String child = module + suffix;
        return GROUP.replace('.', '/') + "/" + child + "/" + version + "/" + child + "-" + version;
    }

    private static List<Map<String, Object>> selectedVariants(Map<String, Object> metadata, String platform) {
        List<Map<String, Object>> variants = new ArrayList<>();
        Set<String> usages = new LinkedHashSet<>();
        for (Object raw : PublicationSupport.array(metadata.get("variants"))) {
            Map<String, Object> variant = object(raw);
            Map<String, Object> attributes = object(variant.getOrDefault("attributes", Map.of()));
            if (platform.equals(attributes.get("org.jetbrains.kotlin.platform.type")) && "library".equals(attributes.get("org.gradle.category"))) {
                variants.add(variant);
                usages.add(string(attributes, "org.gradle.usage"));
            }
        }
        require(variants.size() == 2 && usages.equals(Set.of("java-api", "java-runtime")), "Missing " + platform + " API/runtime variants");
        return variants;
    }

    private static void verifyProjectEdges(Map<String, byte[]> archive, String module, String version, Collection<ApiDependency> edges) throws IOException {
        Set<String> internal = new LinkedHashSet<>();
        for (ApiDependency edge : edges) {
            if (edge.group().equals(GROUP) && COMPONENTS.containsKey(edge.rootModule())) internal.add(edge.rootModule());
        }
        for (String suffix : List.of("-android", "-jvm")) {
            String base = metadataPath(module, version, suffix);
            for (Map<String, Object> variant : selectedVariants(PublicationSupport.jsonObject(member(archive, base + ".module")), suffix.equals("-jvm") ? "jvm" : "androidJvm")) {
                Map<String, Map<String, Object>> dependencies = dependencies(variant);
                if (module.equals("ratatui-core") && "java-api".equals(object(variant.get("attributes")).get("org.gradle.usage"))) {
                    require(!dependencies.containsKey(GROUP + ":thiserror-kotlin"), "Derive tooling must not be a direct public core dependency");
                }
                for (String child : internal) {
                    Map<String, Object> edge = dependencies.get(GROUP + ":" + child);
                    require(edge != null && version.equals(object(edge.getOrDefault("version", Map.of())).get("requires")),
                            "Missing/wrong current-version API edge: " + module + " -> " + child);
                }
            }
            Element pom = PublicationSupport.xml(member(archive, base + ".pom"));
            Map<String, Element> dependencies = new LinkedHashMap<>();
            for (Element edge : PublicationSupport.pomDependencies(pom)) {
                dependencies.put(PublicationSupport.childText(edge, "groupId", "") + ":" + PublicationSupport.childText(edge, "artifactId", ""), edge);
            }
            for (String child : internal) {
                Element edge = dependencies.get(GROUP + ":" + child + suffix);
                require(edge != null && "compile".equals(PublicationSupport.childText(edge, "scope", null))
                        && version.equals(PublicationSupport.childText(edge, "version", null)),
                        "Missing/wrong compile-scope POM edge: " + module + " -> " + child);
            }
        }
    }

    private static Map<String, Object> verifyJvm(Map<String, byte[]> archive, String module, String version, Component component) throws IOException {
        String base = metadataPath(module, version, "-jvm");
        String rootPath = metadataPath(module, version, "") + ".module";
        String path = base + ".module";
        Map<String, Object> metadata = PublicationSupport.jsonObject(member(archive, path));
        require("1.1".equals(metadata.get("formatVersion")), "Unsupported JVM metadata format");
        Map<String, Object> owner = object(metadata.get("component"));
        require(GROUP.equals(owner.get("group")) && module.equals(owner.get("module")) && version.equals(owner.get("version")), "Incorrect JVM owner identity");
        require(PublicationSupport.relatedPath(path, string(owner, "url")).equals(rootPath), "Wrong JVM owning component URL");
        for (Map<String, Object> variant : selectedVariants(PublicationSupport.jsonObject(member(archive, rootPath)), "jvm")) {
            Map<String, Object> redirect = object(variant.get("available-at"));
            require(GROUP.equals(redirect.get("group")) && (module + "-jvm").equals(redirect.get("module")) && version.equals(redirect.get("version"))
                    && PublicationSupport.relatedPath(rootPath, string(redirect, "url")).equals(path), "Wrong JVM root redirect");
        }
        byte[] payload = member(archive, base + ".jar");
        String digest = PublicationSupport.hash("SHA-256", payload);
        for (Map<String, Object> variant : selectedVariants(metadata, "jvm")) {
            Map<String, Object> attributes = object(variant.get("attributes"));
            require("jar".equals(attributes.get("org.gradle.libraryelements")) && "standard-jvm".equals(attributes.get("org.gradle.jvm.environment")), "Wrong JVM variant");
            List<Object> files = PublicationSupport.array(variant.getOrDefault("files", List.of()));
            require(files.size() == 1, "JVM payload hash/path/size mismatch");
            Map<String, Object> file = object(files.getFirst());
            require(PublicationSupport.relatedPath(path, string(file, "url")).equals(base + ".jar")
                    && PublicationSupport.sameSize(file.get("size"), payload.length) && digest.equals(file.get("sha256")), "JVM payload hash/path/size mismatch");
            Map<String, Map<String, Object>> dependencies = dependencies(variant);
            for (ApiDependency edge : component.edges()) {
                require(dependencies.containsKey(edge.group() + ":" + edge.rootModule()), "Missing JVM public API dependency");
            }
        }
        Element pom = PublicationSupport.xml(member(archive, base + ".pom"));
        for (Map.Entry<String, String> expected : Map.of("groupId", GROUP, "artifactId", module + "-jvm", "version", version).entrySet()) {
            require(expected.getValue().equals(PublicationSupport.childText(pom, expected.getKey(), null)), "Incorrect JVM POM identity");
        }
        require("jar".equals(PublicationSupport.childText(pom, "packaging", "jar")), "Incorrect JVM packaging");
        Map<String, byte[]> jar = PublicationSupport.readZip(payload, "Duplicate JVM JAR entry");
        require(!module.equals(MODULE) || jar.keySet().stream().noneMatch(name -> name.endsWith(".class")), "API facade unexpectedly contains JVM bytecode");
        for (String name : component.classes()) {
            require(jar.containsKey(name), "Missing real JVM public API bytecode: " + name);
            byte[] bytecode = member(jar, name);
            require(bytecode.length >= 4 && bytecode[0] == (byte) 0xca && bytecode[1] == (byte) 0xfe
                    && bytecode[2] == (byte) 0xba && bytecode[3] == (byte) 0xbe, "Missing real JVM public API bytecode: " + name);
        }
        return new LinkedHashMap<>(Map.of("jar_sha256", digest, "required_classes", new TreeSet<>(component.classes())));
    }

    public static Map<String, Object> verifyComponents(Path bundle, String version) throws IOException {
        Map<String, Object> receipts = new LinkedHashMap<>();
        for (Map.Entry<String, Component> entry : COMPONENTS.entrySet()) {
            Component component = entry.getValue();
            receipts.put(entry.getKey(), AndroidPublicationVerifier.verify(bundle, GROUP, entry.getKey(), version,
                    component.classes(), component.edges(), entry.getKey().equals(MODULE)));
        }
        Map<String, byte[]> archive = PublicationSupport.readZip(bundle, "Duplicate deployment ZIP entries");
        for (Map.Entry<String, Component> entry : COMPONENTS.entrySet()) {
            Map<String, Object> receipt = object(receipts.get(entry.getKey()));
            receipt.put("jvm", verifyJvm(archive, entry.getKey(), version, entry.getValue()));
            receipts.put(entry.getKey(), receipt);
            verifyProjectEdges(archive, entry.getKey(), version, entry.getValue().edges());
        }
        Map<String, Object> result = new LinkedHashMap<>(object(receipts.get(MODULE)));
        result.put("components", receipts);
        result.put("diagnostics", verifyDiagnosticPayloads(archive, version));
        result.put("serde", verifySerdePayloads(archive, version));
        return result;
    }

    public static Map<String, Object> stage(Path repository, String version, Path bundle, Path sourceRoot) throws IOException {
        require(version.matches("[A-Za-z0-9_.+\\-]+"), "Invalid version");
        Path absoluteBundle = bundle.toAbsolutePath().normalize();
        Files.createDirectories(absoluteBundle.getParent());
        try (ZipOutputStream archive = new ZipOutputStream(Files.newOutputStream(absoluteBundle))) {
            for (String module : COMPONENTS.keySet()) {
                for (String suffix : List.of("", "-android", "-jvm")) {
                    Path root = repository.resolve(GROUP.replace('.', '/')).resolve(module + suffix).resolve(version);
                    require(Files.isDirectory(root), "Missing current publication: " + root);
                    try (var paths = Files.walk(root)) {
                        for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
                            archive.putNextEntry(new ZipEntry(repository.relativize(file).toString().replace('\\', '/')));
                            Files.copy(file, archive);
                            archive.closeEntry();
                        }
                    }
                }
            }
        }
        Map<String, Object> result = verifyComponents(absoluteBundle, version);
        Map<String, Object> components = object(result.get("components"));
        Map<String, byte[]> archive = PublicationSupport.readZip(absoluteBundle, "Duplicate deployment ZIP entries");
        for (String module : COMPONENTS.keySet()) {
            Path owner = module.equals(MODULE) ? sourceRoot : sourceRoot.resolve(module);
            Path sourcesRoot = owner.resolve("src/common/commonMain/kotlin");
            byte[] payload = member(archive, metadataPath(module, version, "-jvm") + "-sources.jar");
            Map<String, byte[]> sources = PublicationSupport.readZip(payload, "Duplicate source JAR entry");
            Map<String, Path> expected = new LinkedHashMap<>();
            if (Files.isDirectory(sourcesRoot)) {
                try (var paths = Files.walk(sourcesRoot)) {
                    for (Path file : paths.filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".kt")).toList()) {
                        expected.put(sourcesRoot.relativize(file).toString().replace('\\', '/'), file);
                    }
                }
            }
            Map<String, String> actual = new LinkedHashMap<>();
            for (String name : sources.keySet()) {
                if (name.startsWith("commonMain/") && name.endsWith(".kt")) actual.put(name.substring("commonMain/".length()), name);
            }
            require(actual.keySet().equals(expected.keySet()), "Stale/missing owned sources: " + module);
            for (Map.Entry<String, Path> file : expected.entrySet()) {
                require(Arrays.equals(sources.get(actual.get(file.getKey())), Files.readAllBytes(file.getValue())), "Source hash mismatch: " + module + "/" + file.getKey());
            }
            Map<String, Object> component = object(components.get(module));
            Map<String, Object> jvm = object(component.get("jvm"));
            jvm.put("sources_sha256", PublicationSupport.hash("SHA-256", payload));
            jvm.put("current_source_files", expected.size());
            component.put("jvm", jvm);
            components.put(module, component);
        }
        result.put("components", components);
        result.put("jvm", object(components.get(MODULE)).get("jvm"));
        return result;
    }

    public static Map<String, Object> executedTests(Path results) throws IOException {
        Map<String, Object> receipt = new LinkedHashMap<>();
        for (String task : List.of("testAndroidHostTest", "jvmTest")) {
            Path directory = results.resolve(task);
            List<Path> files;
            if (Files.isDirectory(directory)) {
                try (var paths = Files.list(directory)) {
                    files = paths.filter(path -> path.toString().endsWith(".xml") && Files.isRegularFile(path)).sorted().toList();
                }
            } else files = List.of();
            require(!files.isEmpty(), "No execution XML for " + task);
            List<Element> cases = new ArrayList<>();
            for (Path file : files) {
                Element root = PublicationSupport.xml(Files.readAllBytes(file));
                if (root.getTagName().equals("testcase")) cases.add(root);
                var descendants = root.getElementsByTagName("testcase");
                for (int index = 0; index < descendants.getLength(); index++) cases.add((Element) descendants.item(index));
            }
            require(cases.size() == TESTS.size(), "Expected " + TESTS.size() + " actual consumer tests in " + task);
            List<String> names = new ArrayList<>();
            for (Element test : cases) {
                require(test.getAttribute("classname").endsWith("PublicApiConsumerTest"), "Unexpected test class");
                for (Node child = test.getFirstChild(); child != null; child = child.getNextSibling()) {
                    require(!(child instanceof Element element) || !Set.of("failure", "error", "skipped").contains(element.getTagName()),
                            "Failed or skipped consumer test: " + test.getAttribute("name"));
                }
                names.add(test.getAttribute("name").split("\\[", 2)[0]);
            }
            require(new LinkedHashSet<>(names).equals(TESTS) && names.size() == TESTS.size(), "Missing or duplicate tests: " + task);
            receipt.put(task, Map.of("executed", cases.size(), "names", names));
        }
        return receipt;
    }

    public static Map<String, Object> consumer(Path build, Map<String, Object> publication) throws IOException {
        require("passed".equals(publication.get("status")), "Publication proof failed");
        String[] coordinates = string(publication, "coordinates").split(":", -1);
        require(coordinates.length == 3, "Invalid publication coordinates");
        Path reports = build.resolve("reports/publication");
        Map<String, Object> variant = object(Json.read(reports.resolve("selected-variant.json")));
        require(coordinates[0].equals(variant.get("group")) && (coordinates[1] + "-android").equals(variant.get("module"))
                && coordinates[2].equals(variant.get("version")), "Wrong selected Android coordinate");
        Map<String, Object> duplicates = object(Json.read(reports.resolve("duplicates.json")));
        require("passed".equals(duplicates.get("status")) && Map.of("compile", Map.of(), "runtime", Map.of()).equals(duplicates.get("duplicates_by_configuration")),
                "Compile/runtime duplicate check did not pass independently");
        Map<String, Object> components = object(publication.get("components"));
        require(components.keySet().equals(COMPONENTS.keySet()), "Missing publication component evidence");
        verifySelectedArtifacts(object(Json.read(reports.resolve("raw-classpaths.json"))), components, false);
        verifySelectedArtifacts(object(Json.read(reports.resolve("jvm-classpaths.json"))), components, true);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "passed");
        result.put("coordinates", publication.get("coordinates"));
        result.put("aar_sha256", publication.get("aar_sha256"));
        result.put("tests", executedTests(build.resolve("test-results")));
        result.put("scope", "Android host and JVM consumer only; other platform runtime gates remain separate");
        return result;
    }

    private static void verifySelectedArtifacts(Map<String, Object> paths, Map<String, Object> components, boolean jvm) throws IOException {
        Map<String, String> fingerprints = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : components.entrySet()) {
            Map<String, Object> component = object(entry.getValue());
            String expected = jvm ? string(object(component.get("jvm")), "jar_sha256") : string(component, "aar_sha256");
            for (String scope : List.of("compile", "runtime")) {
                int count = 0;
                for (Object raw : PublicationSupport.array(paths.get(scope))) {
                    require(raw instanceof String, "Invalid consumer artifact path");
                    String value = (String) raw;
                    if (!value.endsWith(jvm ? ".jar" : ".aar")) continue;
                    String digest = fingerprints.get(value);
                    if (digest == null) {
                        digest = PublicationSupport.fingerprint(Path.of(value));
                        fingerprints.put(value, digest);
                    }
                    if (expected.equals(digest)) count++;
                }
                require(count == 1, "Selected " + scope + " " + entry.getKey() + (jvm ? " JVM JAR" : " AAR") + " differs from staged producer");
            }
        }
    }

    public static String projectVersion(Path properties) throws IOException {
        List<String> versions = Files.readAllLines(properties).stream().filter(line -> line.startsWith("project.version="))
                .map(line -> line.substring("project.version=".length())).toList();
        require(versions.size() == 1 && versions.getFirst().matches("[A-Za-z0-9_.+\\-]+"), "Expected one exact producer version");
        return versions.getFirst();
    }

    public static int run(String... arguments) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        String mode = null;
        Set<String> options = Set.of("--receipt", "--repository", "--version", "--bundle", "--source-root", "--build", "--publication", "--properties", "--environment-file");
        for (int index = 0; index < arguments.length; index++) {
            String argument = arguments[index];
            if (argument.startsWith("--")) {
                require(options.contains(argument) && index + 1 < arguments.length, "Unknown or incomplete option: " + argument);
                require(values.put(argument, arguments[++index]) == null, "Repeated option: " + argument);
            } else {
                require(mode == null, "One verification mode is required");
                mode = argument;
            }
        }
        require(mode != null, "Expected version, stage or consumer mode");
        if (mode.equals("version")) {
            String version = projectVersion(Path.of(required(values, "--properties")));
            Files.writeString(Path.of(required(values, "--environment-file")), "PRODUCER_VERSION=" + version + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            System.out.println("Producer version: " + version);
            return 0;
        }
        Path receipt = Path.of(required(values, "--receipt"));
        Map<String, Object> result;
        try {
            result = switch (mode) {
                case "stage" -> stage(Path.of(required(values, "--repository")), required(values, "--version"),
                        Path.of(required(values, "--bundle")), Path.of(values.getOrDefault("--source-root", ".")));
                case "consumer" -> consumer(Path.of(required(values, "--build")), object(Json.read(Path.of(required(values, "--publication")))));
                default -> throw new IllegalArgumentException("Unknown Ratatui verification mode: " + mode);
            };
        } catch (IOException | IllegalArgumentException exception) {
            Json.write(receipt, PublicationSupport.failure(exception));
            throw exception;
        }
        Json.write(receipt, result);
        System.out.println(Json.stringify(result));
        return 0;
    }

    public static void main(String[] arguments) throws IOException {
        System.exit(run(arguments));
    }

    private static Map<String, Map<String, Object>> dependencies(Map<String, Object> variant) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Object raw : PublicationSupport.array(variant.getOrDefault("dependencies", List.of()))) {
            Map<String, Object> dependency = object(raw);
            result.put(string(dependency, "group") + ":" + string(dependency, "module"), dependency);
        }
        return result;
    }

    private static byte[] member(Map<String, byte[]> archive, String name) {
        require(archive.containsKey(name), "Missing publication payload: " + name);
        return archive.get(name);
    }

    private static String required(Map<String, String> values, String name) {
        require(values.containsKey(name), "Missing " + name);
        return values.get(name);
    }

    private static Map<String, Object> object(Object value) { return PublicationSupport.object(value); }
    private static String string(Map<String, Object> value, String key) { return PublicationSupport.string(value, key); }
    private static void require(boolean condition, String message) { PublicationSupport.require(condition, message); }
}
