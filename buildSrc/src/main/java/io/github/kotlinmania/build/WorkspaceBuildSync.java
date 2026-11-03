package io.github.kotlinmania.build;

import static io.github.kotlinmania.build.AndroidPublicationVerifier.PublicationSupport.require;
import io.github.kotlinmania.build.AndroidPublicationVerifier.PublicationSupport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlTable;

/** Reviewed local shared-build synchronization. Plans contain hashes and metadata, never source copies. */
public final class WorkspaceBuildSync {
    private WorkspaceBuildSync() {}
    public static final Map<String, String> EXCEPTIONS = exceptions();
    public static final List<String> PRIMARY_FILES = List.of("build.gradle.kts", "settings.gradle.kts", "gradle.properties", "maven.properties",
        "gradle/libs.versions.toml", "gradle/wrapper/gradle-wrapper.properties", "kotlin-js-store/package.json", "kotlin-js-store/wasm/package.json", "gradle/npm/karma-webpack/package.json");
    private static final List<String> JSON_FILES = List.of("kotlin-js-store/package.json", "kotlin-js-store/wasm/package.json", "gradle/npm/karma-webpack/package.json");
    public static final List<String> FFI_GUIDES = List.of("src/nativeInterop/cinterop/README.md", "src/jvmMain/c/README.md", "native/node-ffi/README.md");
    private static final Set<String> HELD = Set.of("thiserror-kotlin", "serderive-kotlin");
    private static final Set<String> TOOL_PINS = Set.of("versions.kotlin", "versions.android.plugin", "versions.detekt", "versions.ktlint", "versions.ktlintEngine", "node.version", "wasm.node.version",
        "yarn.version", "wasm.yarn.version", "node.webpackCli.version", "node.karma.version", "node.mocha.version", "node.kotlinWebHelpers.version", "kotlin.languageVersion", "kotlin.apiVersion");
    private static final Set<String> REMOVED_PROPERTIES = Set.of("yarn.resolution.minimatch", "yarn.resolution.webpack", "node.webpack.version", "project.repositories.mavenLocal");
    private static final Set<String> PROJECT_METADATA = Set.of("project.group", "project.version", "project.name", "project.frameworkName", "project.namespace", "project.pom.description");
    public record BuildResult(String text, List<String> preserved) {}
    public record MergeResult(String text, Map<String, Object> details) {}
    private record Proposal(Map<String, Object> plan, Map<String, Map<String, byte[]>> contents) {}
    @FunctionalInterface interface GitReader { String read(Path repo, String... args) throws IOException; }

    private static Map<String, String> exceptions() {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("thiserror-kotlin", "Explicitly excluded by Sydney: active work.");
        result.put("serderive-kotlin", "Explicitly excluded: obsolete standalone directory; canonical derive is serde-kotlin/serde_derive.");
        result.put("async-trait-kotlin", "Multi-module build: preserve root delegation and adapt :library separately.");
        result.put("serde-kotlin", "Included build logic and :serde-core/:serde-derive conventions; preserve project dependency edges.");
        result.put("ratatui-kotlin", "Multi-module build with included convention logic and per-module publication identities.");
        result.put("byteorder-kotlin", "Custom patched Swift exporter, SDK preparation, isolated caches, and behavioral acceptance tasks.");
        result.put("candle-kotlin", "Custom JNI packaging, Android ABI/page-size acceptance, Accelerate linking, and native runtime tasks.");
        result.put("km-io", "Older shared build structure, serialization plugin, and specialized source-set wiring.");
        result.put("JWT-Kotlin", "Different build structure and dependency bundles; review serialization usage before adapting.");
        result.put("android-system-properties-kotlin", "Distinct Android artifact and device acceptance build without shared Swift Export wiring.");
        result.put("codex-kotlin", "Custom application build; preserve source sets and publishing model.");
        result.put("gradle-kotlin", "Custom Gradle tooling build; adapt applicable shared sections individually.");
        result.put("klang", "Compiler build with :swift-common-test-export; preserve module and export wiring.");
        result.put("kotlin-swift-export-tooling", "Artifact producer with distribution verification and signing, not a KMP library template.");
        result.put("llama.kotlin", "Specialized native/application build with Vanniktech publishing.");
        result.put("rust-kotlin", "Minimal KMP build and canonical serde-derive dependency; do not graft the full library template.");
        result.put("rust-kotlin-mayhem", "Older Vanniktech build with distinct publishing and serialization choices.");
        result.put("rust-lang-kotlin", "Root JVM build with :runtime; retain module and source-set dependencies.");
        return java.util.Collections.unmodifiableMap(result);
    }

    private static String sha(byte[] bytes) { return bytes == null ? null : PublicationSupport.hash("sha256", bytes); }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] bytes) { require(bytes != null, "Missing required source file"); return new String(bytes, StandardCharsets.UTF_8); }
    private static List<String> lines(String text) { return text.isEmpty() ? List.of() : List.of(text.split("(?<=\\n)")); }
    private static String relative(Path root, Path file) { return root.relativize(file).toString().replace('\\', '/'); }
    private static void safePath(Path root, Path path) {
        Path normalized = path.toAbsolutePath().normalize(); Path base = root.toAbsolutePath().normalize();
        require(normalized.startsWith(base), "Path escapes workspace: " + path);
        for (Path cursor = normalized; cursor != null && cursor.startsWith(base); cursor = cursor.getParent()) require(!Files.isSymbolicLink(cursor), "Refusing a symbolic link: " + cursor);
    }
    private static byte[] read(Path root, Path path) throws IOException {
        safePath(root, path); require(!Files.exists(path) || Files.isRegularFile(path), "Expected a regular file: " + path);
        return Files.isRegularFile(path) ? Files.readAllBytes(path) : null;
    }
    private static Path repository(Path workspace, String name, boolean allowHeld) {
        require(!name.isBlank() && !name.equals(".") && !name.equals("..") && !name.contains("/") && !name.contains("\\"), "Name one immediate workspace checkout");
        require(allowHeld || !HELD.contains(name), "Project is explicitly excluded: " + name);
        Path repo = workspace.resolve(name); safePath(workspace, repo);
        require(Files.isDirectory(repo.resolve(".git")) && Files.isRegularFile(repo.resolve("build.gradle.kts")), "Use an existing primary checkout: " + name);
        return repo;
    }
    private static String git(Path repo, String... args) throws IOException {
        List<String> command = new ArrayList<>(List.of("git")); command.addAll(List.of(args));
        return AndroidRuntimeResultsVerifier.command(repo, command);
    }

    public static Map<String, String> properties(String text) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String line : text.split("\\R")) {
            if (line.isBlank() || line.stripLeading().startsWith("#") || line.stripLeading().startsWith("!")) continue;
            int equals = line.indexOf('='); if (equals < 0) continue;
            String key = line.substring(0, equals).strip(); String value = line.substring(equals + 1).strip();
            require(!result.containsKey(key) || result.get(key).equals(value), "Duplicate property: " + key); result.put(key, value);
        }
        return result;
    }
    private static String section(String text, String start, String end) {
        require(text.indexOf(start) >= 0 && text.indexOf(start) == text.lastIndexOf(start), "Unsupported/ambiguous build section: " + start);
        int begin = text.indexOf(start); int finish = text.indexOf(end, begin);
        require(finish >= begin, "Missing end of build section: " + end); return text.substring(begin, finish);
    }
    private static String replace(String text, String before, String after) {
        int start = text.indexOf(before); require(start >= 0, "Missing shared build anchor: " + before);
        return text.substring(0, start) + after + text.substring(start + before.length());
    }
    private static String changeSection(String text, String start, String end, String replacement) { return replace(text, section(text, start, end), replacement); }

    private record ProjectBlock(String marker, String anchor, String description) {}
    private static final Map<String, List<ProjectBlock>> PROJECT_BLOCKS = Map.of(
        "libc-kotlin", List.of(
            new ProjectBlock("libc:macro-layout-fixtures", "val publicationCompilationTasks =", "Compiler-derived browser/WASI C fixture, runtime imports, environment, and preopened-directory test gates."),
            new ProjectBlock("libc:jni-distribution", "// ============================================================================\n// N-API", "JNI resource packaging, Android ABI payloads, packaged-library tests, and release distribution verification.")),
        "deno-core-icudata-kotlin", List.of(new ProjectBlock("kotlinmania:icu-data-generation:v1", "kotlin {\n", "Vendored ICU data generation and compile/lint task dependencies.")),
        "tree-sitter-bash-kotlin", List.of(new ProjectBlock("kotlinmania:tree-sitter-bash-resources:v1", "kotlin {\n", "Bundled grammar-resource generation and compile/lint task dependencies.")),
        "sha1-kotlin", List.of(new ProjectBlock("sha1:asm-native-bindings", "    // Web\n", "Per-target SHA-1 wrapper/assembly compilation, static archives, and sha1Asm cinterop bindings.")),
        "starlark-syntax-kotlin", List.of(new ProjectBlock("kotlinmania:project-specific:starlark-golden-tests:v1", "// ============================================================================\n// JNI — JVM C bindings. Gated by project.ffi.jvm=true.", "Starlark golden-test fixture roots and runtime-specific preopens.")),
        "tree-sitter-kotlin", List.of(
            new ProjectBlock("kotlinmania:project-specific:treesitter-native-support:v1", "kotlin {\n", "Tree-sitter native fixture/toolchain support."),
            new ProjectBlock("kotlinmania:project-specific:treesitter-native-bindings:v1", "    sourceSets {\n", "Tree-sitter native cinterop and compiler task bindings."),
            new ProjectBlock("kotlinmania:project-specific:treesitter-wasi-source-set:v1", "        if (benchmarkEnabled) {\n", "Tree-sitter WASI source-set dependency edge."),
            new ProjectBlock("kotlinmania:project-specific:treesitter-jni-fixture:v1", "// ============================================================================\n// JNI — JVM C bindings. Gated by project.ffi.jvm=true.", "Tree-sitter JVM fixture compiler and runtime library path."),
            new ProjectBlock("kotlinmania:project-specific:treesitter-native-archive:v1", null, "Tree-sitter native static archive checks.")));

    static String markedBlock(String source, String marker) {
        var begin = Pattern.compile("(?m)^[ \\t]*// " + Pattern.quote(marker) + " begin[ \\t]*(?:\\r?\\n|$)").matcher(source);
        require(begin.find(), "Missing project-specific begin marker: " + marker);
        int start = begin.start(), content = begin.end(); require(!begin.find(), "Duplicate project-specific begin marker: " + marker);
        var end = Pattern.compile("(?m)^[ \\t]*// " + Pattern.quote(marker) + " end[ \\t]*(?:\\r?\\n|$)").matcher(source);
        require(end.find(), "Missing project-specific end marker: " + marker);
        int finish = end.end(); require(end.start() >= content, "Reversed project-specific markers: " + marker);
        require(!source.substring(content, end.start()).isBlank(), "Empty project-specific block: " + marker);
        require(!end.find(), "Duplicate project-specific end marker: " + marker);
        return source.substring(start, finish);
    }

    private static String insertBefore(String source, String anchor, String addition) {
        require(source.indexOf(anchor) >= 0 && source.indexOf(anchor) == source.lastIndexOf(anchor), "Unsupported/ambiguous project preservation anchor: " + anchor);
        return replace(source, anchor, addition + (addition.endsWith("\n") ? "\n" : "\n\n") + anchor);
    }

    private static String preserveImport(String before, String result, String imported) {
        String statement = "import " + imported;
        var original = Pattern.compile("(?m)^" + Pattern.quote(statement) + "[ \\t]*(?:\\r?\\n|$)").matcher(before);
        require(original.find() && !original.find(), "Missing/ambiguous project-specific import: " + imported);
        var current = Pattern.compile("(?m)^" + Pattern.quote(statement) + "[ \\t]*(?:\\r?\\n|$)").matcher(result);
        if (current.find()) { require(!current.find(), "Duplicate template import: " + imported); return result; }
        var imports = Pattern.compile("(?m)^import ([^\\r\\n]+)(?:\\r?\\n|$)").matcher(result);
        int end = -1;
        while (imports.find()) {
            if (imports.group(1).compareTo(imported) > 0) return result.substring(0, imports.start()) + statement + "\n" + result.substring(imports.start());
            end = imports.end();
        }
        require(end >= 0, "Template has no import anchor for " + imported);
        return result.substring(0, end) + statement + "\n" + result.substring(end);
    }

    private static String preserveGeneratedSource(String before, String result, String generator) {
        var sourceSet = Pattern.compile("(?m)^[ \\t]*commonMain[ \\t]*\\{[ \\t]*(?:\\r?\\n[ \\t]*)?kotlin\\.srcDir\\(" + Pattern.quote(generator)
            + "\\)[ \\t]*(?:\\r?\\n[ \\t]*)?}[ \\t]*(?:\\r?\\n|$)").matcher(before);
        require(sourceSet.find(), "Missing generated commonMain source edge: " + generator);
        String source = sourceSet.group(); require(!sourceSet.find(), "Duplicate generated commonMain source edge: " + generator);
        require(!result.contains("kotlin.srcDir(" + generator + ")"), "Template contains a project-specific generator source edge: " + generator);
        return insertBefore(result, "        commonMain.dependencies {", source);
    }

    private static Set<String> regionMarkers(String source) {
        Set<String> result = new LinkedHashSet<>();
        Pattern.compile("(?m)^[ \\t]*// ([A-Za-z0-9_.:-]+) (?:begin|end)[ \\t]*$").matcher(source).results().forEach(match -> result.add(match.group(1)));
        return result;
    }

    private static Set<String> helperImports(String source) {
        Set<String> result = new LinkedHashSet<>();
        Pattern.compile("(?m)^import (io\\.github\\.kotlinmania\\.build\\.[^\\r\\n]+)").matcher(source).results().forEach(match -> result.add(match.group(1).strip()));
        return result;
    }

    private static Set<String> sourceDirectoryEdges(String source) {
        List<AndroidPublicationSync.Token> tokens = AndroidPublicationSync.tokens(source); Set<String> result = new LinkedHashSet<>();
        for (int i = 2; i + 1 < tokens.size(); i++) {
            if (!Set.of("srcDir", "srcDirs").contains(tokens.get(i).value()) || !tokens.get(i - 1).value().equals(".")
                    || !Set.of("kotlin", "resources").contains(tokens.get(i - 2).value()) || !tokens.get(i + 1).value().equals("(")) continue;
            int start = i - 2;
            while (start >= 2 && tokens.get(start - 1).value().equals(".") && tokens.get(start - 2).value().matches("[A-Za-z_][A-Za-z0-9_]*")) start -= 2;
            int depth = 1, end = i + 2;
            while (end < tokens.size() && depth > 0) {
                if (tokens.get(end).value().equals("(")) depth++;
                if (tokens.get(end).value().equals(")")) depth--;
                end++;
            }
            require(depth == 0, "Unclosed project source directory edge");
            result.add(source.substring(tokens.get(start).start(), tokens.get(end - 1).end()));
        }
        return result;
    }

    private static String preserveProjectBlocks(String name, String before, String template, String result, List<String> preserved) {
        Set<String> recognized = regionMarkers(template);
        PROJECT_BLOCKS.values().forEach(blocks -> blocks.forEach(block -> recognized.add(block.marker())));
        for (String marker : regionMarkers(before)) require(recognized.contains(marker), "Unrecognized custom build region: " + marker);
        for (var entry : PROJECT_BLOCKS.entrySet()) for (ProjectBlock block : entry.getValue()) {
            require(!template.contains("// " + block.marker() + " begin") && !template.contains("// " + block.marker() + " end"), "Shared template contains project-specific block: " + block.marker());
            if (!entry.getKey().equals(name)) require(!before.contains("// " + block.marker() + " begin") && !before.contains("// " + block.marker() + " end"), "Project-specific block belongs to " + entry.getKey() + ": " + block.marker());
        }
        for (ProjectBlock block : PROJECT_BLOCKS.getOrDefault(name, List.of())) {
            String source = markedBlock(before, block.marker());
            require(!result.contains("// " + block.marker() + " begin"), "Project-specific block already inserted: " + block.marker());
            result = block.anchor() == null ? result.stripTrailing() + "\n\n" + source : insertBefore(result, block.anchor(), source);
            require(markedBlock(result, block.marker()).equals(source), "Project-specific block changed during preservation: " + block.marker());
            preserved.add(block.description());
        }
        if (name.equals("deno-core-icudata-kotlin")) {
            result = preserveImport(before, result, "io.github.kotlinmania.build.IcuDataGenerator");
            result = preserveGeneratedSource(before, result, "generateIcuData");
            preserved.add("Project-owned IcuDataGenerator import and generated commonMain source directory.");
        }
        if (name.equals("tree-sitter-bash-kotlin")) {
            result = preserveImport(before, result, "io.github.kotlinmania.build.TreeSitterBashResourcesGenerator");
            result = preserveGeneratedSource(before, result, "generateBundledTreeSitterBashResources");
            preserved.add("Project-owned TreeSitterBashResourcesGenerator import and generated commonMain source directory.");
        }
        if (name.equals("libc-kotlin")) for (String imported : List.of("io.github.kotlinmania.build.LibcAndroidJniTask", "java.util.Base64", "org.gradle.process.ExecOperations", "org.jetbrains.kotlin.gradle.ExperimentalWasmDsl", "org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsEnvSpec")) result = preserveImport(before, result, imported);
        if (name.equals("sha1-kotlin")) for (String imported : List.of("org.gradle.process.ExecOperations", "org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget")) result = preserveImport(before, result, imported);
        Set<String> requiredImports = helperImports(before); requiredImports.removeAll(helperImports(template));
        require(helperImports(result).containsAll(requiredImports), "Unrecognized project-owned Java helper import would be lost: " + requiredImports);
        Set<String> requiredSources = sourceDirectoryEdges(before); requiredSources.removeAll(sourceDirectoryEdges(template));
        require(sourceDirectoryEdges(result).containsAll(requiredSources), "Unrecognized project source directory edge would be lost: " + requiredSources);
        return result;
    }

    public static BuildResult buildFor(String name, String before, String template) {
        String result = template; List<String> preserved = new ArrayList<>();
        if (name.equals("syn-kotlin") && before.contains("project.swift.isolatedBuild")) {
            String extra = section(before, "if (providers.gradleProperty(\"project.swift.isolatedBuild\")", "\ngroup = providers.gradleProperty");
            result = replace(result, "group = providers.gradleProperty", extra + "\ngroup = providers.gradleProperty");
            preserved.add("Opt-in isolated Swift build directory.");
        }
        if (Set.of("base64ct-kotlin", "bincode-kotlin").contains(name)) {
            String extra = section(before, "        jvmTest.dependencies {", "        if (benchmarkEnabled) {");
            result = replace(result, "        if (benchmarkEnabled) {", extra + "        if (benchmarkEnabled) {");
            preserved.add("Explicit Kotlin JUnit dependencies for JVM and Android host/device tests.");
        }
        if (name.equals("bincode-kotlin")) {
            String start = "            commonMainApiDependencyBundle?."; String end = "\n        }\n        commonTest.dependencies";
            result = changeSection(result, start, end, section(before, start, end)); preserved.add("Public API dependency exclusions for kotlinx-serialization-core/json.");
        }
        if (name.equals("either-kotlin")) {
            String end = "// ============================================================================\n// Maven Central publishing";
            result = changeSection(result, "// Make kotlinUpgradeYarnLock", end, section(before, "// Refresh Yarn locks for test execution", end));
            preserved.add("Test-only Yarn-lock wiring that avoids development-dependency publication cycles.");
        }
        if (name.equals("threadlocal-kotlin")) {
            result = changeSection(result, "val intellijCoroutinesVersion =", "\nval projectKonanDataDirectory =", "");
            String start = "    if (name == \"swiftExportClasspath\") {"; String end = "\n}\n\n// Test name patterns";
            result = changeSection(result, start, end, section(before, start, end));
            start = "    swiftExport {"; end = "\n\n    // Android KMP library.";
            result = changeSection(result, start, end, section(before, start, end));
            preserved.add("Patched Swift exporter and disabled coroutine export support.");
        }
        if (name.equals("starlark-syntax-kotlin")) {
            String end = "        commonMain.dependencies {";
            result = replace(result, end, section(before, "        // Local source bridge", end) + end);
            List<String> apiLines = lines(before).stream().filter(line -> line.contains("api(libs.anyhow)")).toList();
            require(apiLines.size() == 1, "Ambiguous Starlark public Anyhow edge");
            String anchor = "            implementation(commonMainDependencyBundle)\n";
            result = replace(result, anchor, anchor + apiLines.getFirst());
            String marker = "// kotlinmania:android-publication:v1 end\n";
            require(before.indexOf(marker) >= 0 && before.indexOf(marker) == before.lastIndexOf(marker), "Unsupported Starlark publication boundary");
            result += before.substring(before.indexOf(marker) + marker.length());
            preserved.add("Exact-file Rust typed Hash source bridge, public Anyhow dependency, and compiler file filter.");
        }
        if (name.equals("aws-sigv4-kotlin")) {
            for (String task : List.of("copyIncludedNode$ibFfiName", "copyIncludedWasmNode$ibFfiName")) {
                String anchor = "tasks.register<Copy>(\"" + task + "\") {\n";
                require(result.indexOf(anchor) >= 0 && result.indexOf(anchor) == result.lastIndexOf(anchor), "Ambiguous included-build copy task");
                result = replace(result, anchor, anchor + "                    description = \"\"\n");
            }
            preserved.add("Existing included-build copy-task descriptions.");
        }
        result = preserveProjectBlocks(name, before, template, result, preserved);
        return new BuildResult(result, preserved);
    }

    public static MergeResult syncProperties(String before, String build, String template) {
        Map<String, String> existing = properties(before); Map<String, String> shared = properties(template);
        Set<String> pins = new LinkedHashSet<>(); for (String key : shared.keySet()) if (key.startsWith("yarn.resolution.") || TOOL_PINS.contains(key)) pins.add(key);
        StringBuilder output = new StringBuilder();
        for (String line : lines(before)) {
            if (!line.isBlank() && !line.stripLeading().startsWith("#") && !line.stripLeading().startsWith("!") && line.contains("=")) {
                String key = line.substring(0, line.indexOf('=')).strip();
                if (REMOVED_PROPERTIES.contains(key)) continue;
                if (pins.contains(key)) line = key + "=" + shared.get(key) + "\n";
            }
            output.append(line);
        }
        Map<String, String> defaults = new LinkedHashMap<>(shared);
        for (String sourceSet : List.of("commonMain", "commonTest")) {
            String legacy = "project.dependencies." + sourceSet; String current = legacy + "Bundle";
            if (!existing.containsKey(current) && existing.containsKey(legacy)) defaults.put(current, existing.get(legacy));
        }
        for (String channel : List.of("napi", "jvm")) {
            String block = section(build, "        if (!" + channel + "FfiEnabled) {", channel.equals("napi") ? "        if (!jvmFfiEnabled) {" : "        commonMain.dependencies {");
            Set<String> literals = new LinkedHashSet<>(); Pattern.compile("kotlin\\.exclude\\(\"([^\"$]+)\"\\)").matcher(block).results().forEach(match -> literals.add(match.group(1)));
            defaults.put("project.ffi." + channel + ".kotlinExcludes", String.join(",", literals));
        }
        require(existing.keySet().containsAll(PROJECT_METADATA), "Missing project metadata");
        require(existing.containsKey("project.dependencies.commonMainBundle") || existing.containsKey("project.dependencies.commonMain"), "Missing project dependency bundle selection");
        List<String> missing = defaults.keySet().stream().filter(key -> !existing.containsKey(key) && !REMOVED_PROPERTIES.contains(key)).toList();
        if (!missing.isEmpty()) {
            output.append("\n# Shared template defaults; existing project-specific values above are retained.\n# Maven resolution mode and repository path are configured in maven.properties.\n");
            for (String key : missing) output.append(key).append('=').append(defaults.get(key)).append('\n');
        }
        Map<String, String> result = properties(output.toString()); Map<String, Object> updated = new TreeMap<>(); Map<String, String> added = new TreeMap<>(); Map<String, String> removed = new TreeMap<>();
        for (var entry : existing.entrySet()) {
            String key = entry.getKey(); if (!pins.contains(key) && !REMOVED_PROPERTIES.contains(key)) require(entry.getValue().equals(result.get(key)), "Project property changed: " + key);
            if (!result.containsKey(key)) removed.put(key, entry.getValue());
            else if (!entry.getValue().equals(result.get(key))) updated.put(key, Map.of("before", entry.getValue(), "after", result.get(key)));
        }
        result.forEach((key, value) -> { if (!existing.containsKey(key)) added.put(key, value); });
        return new MergeResult(output.toString(), Map.of("updated", updated, "added", added, "removed", removed));
    }

    static Map<String, Object> catalog(String text) {
        var parsed = Toml.parse(text); require(!parsed.hasErrors(), "Invalid version catalog: " + parsed.errors()); return PublicationSupport.object(tomlValue(parsed));
    }
    private static Object tomlValue(Object value) {
        if (value instanceof TomlTable table) { Map<String, Object> result = new LinkedHashMap<>(); table.toMap().forEach((key, item) -> result.put(key, tomlValue(item))); return result; }
        if (value instanceof TomlArray array) { List<Object> result = new ArrayList<>(); for (int i = 0; i < array.size(); i++) result.add(tomlValue(array.get(i))); return result; }
        return value;
    }
    private static Map<String, Object> objectAt(Map<String, Object> map, String key) { return PublicationSupport.object(map.getOrDefault(key, Map.of())); }
    public static MergeResult syncCatalog(String before, String template) {
        Map<String, Object> original = catalog(before); Map<String, Object> defaults = catalog(template); String result = before; Map<String, Object> additions = new LinkedHashMap<>();
        boolean detekt = Map.of("id", "io.gitlab.arturbosch.detekt", "version", Map.of("ref", "detekt")).equals(objectAt(original, "plugins").get("detekt"))
            && Objects.equals(objectAt(original, "versions").get("detekt"), objectAt(defaults, "versions").get("detekt"));
        if (detekt) { result = result.replace("id = \"io.gitlab.arturbosch.detekt\"", "id = \"dev.detekt\""); additions.put("plugin_id_alignment", Map.of("detekt", "io.gitlab.arturbosch.detekt -> dev.detekt")); }
        for (String section : List.of("versions", "plugins", "libraries")) {
            Map<String, Object> current = objectAt(original, section); List<String> missing = objectAt(defaults, section).keySet().stream().filter(key -> !current.containsKey(key)).toList();
            if (missing.isEmpty()) continue;
            var templateSection = Pattern.compile("^\\[" + Pattern.quote(section) + "\\]\\r?\\n(.*?)(?=^\\[|\\z)", Pattern.MULTILINE | Pattern.DOTALL).matcher(template);
            require(templateSection.find(), "Missing catalog section: " + section); StringBuilder added = new StringBuilder();
            for (String key : missing) { var line = Pattern.compile("^" + Pattern.quote(key) + "\\s*=.*$", Pattern.MULTILINE).matcher(templateSection.group(1)); require(line.find(), "Unsupported catalog entry layout: " + key); added.append(line.group()).append('\n'); }
            var header = Pattern.compile("^\\[" + Pattern.quote(section) + "\\]\\r?\\n", Pattern.MULTILINE).matcher(result);
            require(header.find(), "Missing existing catalog section: " + section); int end = header.end(); require(!header.find(), "Duplicate catalog section: " + section);
            result = result.substring(0, end) + added + result.substring(end); additions.put(section, missing);
        }
        Map<String, Object> after = catalog(result);
        for (var section : original.entrySet()) for (var entry : PublicationSupport.object(section.getValue()).entrySet()) {
            Object expected = detekt && section.getKey().equals("plugins") && entry.getKey().equals("detekt") ? objectAt(defaults, "plugins").get("detekt") : entry.getValue();
            require(Objects.equals(objectAt(after, section.getKey()).get(entry.getKey()), expected), "Changed project catalog value: " + section.getKey() + "/" + entry.getKey());
        }
        require(Objects.equals(after.get("bundles"), original.get("bundles")), "Project dependency bundles changed");
        return new MergeResult(result, additions);
    }

    public static List<Map<String, Object>> inventory(Path workspace) throws IOException { return inventory(workspace, WorkspaceBuildSync::git); }
    static List<Map<String, Object>> inventory(Path workspace, GitReader git) throws IOException {
        List<Map<String, Object>> records = new ArrayList<>();
        try (var directories = Files.list(workspace)) {
            for (Path repo : directories.sorted().toList()) {
                String name = repo.getFileName().toString();
                if (name.equals("template") || Files.isSymbolicLink(repo) || !Files.isDirectory(repo)) continue;
                if (HELD.contains(name)) { records.add(Map.of("name", name, "exception", EXCEPTIONS.get(name), "hashes", Map.of(), "workflows", Map.of())); continue; }
                if (!Files.exists(repo.resolve(".git")) || !Files.isRegularFile(repo.resolve("build.gradle.kts"))) continue;
                Map<String, Object> record = new LinkedHashMap<>(); record.put("name", name); record.put("head", git.read(repo, "rev-parse", "HEAD").strip());
                record.put("status", git.read(repo, "status", "--short", "--branch", "--untracked-files=normal")); record.put("exception", EXCEPTIONS.get(name));
                Map<String, Object> hashes = new LinkedHashMap<>(); for (String nameInRepo : PRIMARY_FILES) hashes.put(nameInRepo, sha(read(workspace, repo.resolve(nameInRepo))));
                record.put("hashes", hashes); record.put("workflows", workflows(workspace, repo)); records.add(record);
            }
        }
        return records;
    }
    private static Map<String, Object> workflows(Path workspace, Path repo) throws IOException {
        Map<String, Object> result = new TreeMap<>(); Path directory = repo.resolve(".github/workflows");
        if (Files.isDirectory(directory)) try (var files = Files.list(directory)) {
            for (Path file : files.sorted().toList()) if (file.getFileName().toString().matches(".*\\.ya?ml")) {
                byte[] bytes = read(workspace, file); List<String> automatic = Pattern.compile("^\\s*(push|pull_request|pull_request_target)\\s*:", Pattern.MULTILINE).matcher(text(bytes)).results().map(m -> m.group(1)).toList();
                result.put(file.getFileName().toString(), Map.of("sha256", sha(bytes), "automatic", automatic, "codeql", file.getFileName().toString().toLowerCase(java.util.Locale.ROOT).startsWith("codeql")));
            }
        }
        return result;
    }
    private static List<String> helpers(Path workspace) throws IOException {
        Path source = workspace.resolve("template"); List<String> result = new ArrayList<>(List.of("verification/strict-swift.xcconfig", "verification/MAVEN_MODE.md", "buildSrc/build.gradle.kts", "buildSrc/settings.gradle.kts"));
        Path javaSources = source.resolve("buildSrc/src/main/java");
        if (Files.isDirectory(javaSources)) try (var paths = Files.walk(javaSources)) {
            for (Path path : paths.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".java")).sorted().toList()) result.add(relative(source, path));
        }
        require(result.stream().anyMatch(path -> path.endsWith("/Json.java")), "Template Java build support is missing"); return result;
    }
    private static boolean reviewed(Map<String, Object> reviews, String name, String relative, byte[] before) {
        Object values = objectAt(reviews, name).get(relative); return values instanceof List<?> list && list.contains(sha(before));
    }
    public static Map<String, Object> plan(Path workspace, List<Map<String, Object>> inventory, Map<String, Object> reviewed) throws IOException {
        return proposal(workspace.toRealPath(), inventory, reviewed, WorkspaceBuildSync::git).plan();
    }
    static Map<String, Object> plan(Path workspace, List<Map<String, Object>> inventory, Map<String, Object> reviewed, GitReader git) throws IOException {
        return proposal(workspace.toRealPath(), inventory, reviewed, git).plan();
    }
    private static Proposal proposal(Path workspace, List<Map<String, Object>> inventory, Map<String, Object> reviewed, GitReader git) throws IOException {
        Path sourceRoot = workspace.resolve("template"); List<String> helperFiles = helpers(workspace);
        List<String> sourceFiles = new ArrayList<>(List.of("build.gradle.kts", "settings.gradle.kts", "gradle.properties", "maven.properties", "gradle/libs.versions.toml"));
        sourceFiles.addAll(JSON_FILES); sourceFiles.addAll(helperFiles); sourceFiles.addAll(FFI_GUIDES);
        Map<String, byte[]> source = new LinkedHashMap<>(); Map<String, Object> sourceHashes = new LinkedHashMap<>();
        for (String name : sourceFiles) { byte[] value = read(workspace, sourceRoot.resolve(name)); require(value != null, "Missing template source: " + name); source.put(name, value); sourceHashes.put(name, sha(value)); }
        List<Map<String, Object>> projects = new ArrayList<>(); List<Map<String, Object>> changes = new ArrayList<>(); Map<String, Map<String, byte[]>> contents = new LinkedHashMap<>(); Set<String> seen = new LinkedHashSet<>();
        for (Map<String, Object> record : inventory) {
            String name = PublicationSupport.string(record, "name"); require(seen.add(name), "Duplicate inventory project: " + name);
            if (EXCEPTIONS.containsKey(name) || record.get("exception") != null) continue;
            Path repo = repository(workspace, name, false);
            require(git.read(repo, "rev-parse", "HEAD").strip().equals(record.get("head")), "Concurrent HEAD change: " + name);
            for (String primary : List.of("build.gradle.kts", "settings.gradle.kts")) require(reviewed(reviewed, name, primary, read(workspace, repo.resolve(primary))), "Unreviewed build/settings variant: " + name + "/" + primary);
            Map<String, Object> hashes = objectAt(record, "hashes"); require(hashes.keySet().containsAll(PRIMARY_FILES), "Incomplete primary input inventory: " + name);
            for (var hash : hashes.entrySet()) require(Objects.equals(sha(read(workspace, repo.resolve(hash.getKey()))), hash.getValue()), "Concurrent change: " + name + "/" + hash.getKey());
            require(Objects.equals(workflows(workspace, repo), record.get("workflows")), "Concurrent workflow change: " + name);
            String beforeBuild = text(read(workspace, repo.resolve("build.gradle.kts")));
            BuildResult build = buildFor(name, beforeBuild, text(source.get("build.gradle.kts")));
            MergeResult props = syncProperties(text(read(workspace, repo.resolve("gradle.properties"))), beforeBuild, text(source.get("gradle.properties")));
            MergeResult catalog = syncCatalog(text(read(workspace, repo.resolve("gradle/libs.versions.toml"))), text(source.get("gradle/libs.versions.toml")));
            Map<String, byte[]> proposed = new LinkedHashMap<>(); proposed.put("build.gradle.kts", bytes(build.text())); proposed.put("settings.gradle.kts", source.get("settings.gradle.kts"));
            proposed.put("gradle.properties", bytes(props.text())); proposed.put("gradle/libs.versions.toml", bytes(catalog.text()));
            if (name.equals("socket2-kotlin")) proposed.put("settings.gradle.kts", bytes(text(proposed.get("settings.gradle.kts")).replace("rootProject.name =", "// socket2-kotlin consumes libc-kotlin from the catalog; no composite build.\nrootProject.name =")));
            byte[] maven = read(workspace, repo.resolve("maven.properties")); proposed.put("maven.properties", maven == null ? source.get("maven.properties") : maven);
            for (String file : JSON_FILES) {
                Map<String, Object> existing = PublicationSupport.jsonObject(read(workspace, repo.resolve(file))); Map<String, Object> defaults = PublicationSupport.jsonObject(source.get(file));
                Map<String, Object> dependencies = objectAt(existing, "dependencies"); dependencies.putAll(objectAt(defaults, "dependencies")); existing.put("dependencies", dependencies); proposed.put(file, bytes(Json.stringify(existing) + "\n"));
            }
            for (String file : helperFiles) {
                byte[] existing = read(workspace, repo.resolve(file));
                if (file.equals("verification/strict-swift.xcconfig") && existing != null) proposed.put(file, bytes(Pattern.compile("^SWIFT_VERSION\\s*=.*$", Pattern.MULTILINE).matcher(text(existing)).replaceAll("SWIFT_VERSION = 5")));
                else {
                    require(existing == null || java.util.Arrays.equals(existing, source.get(file)) || file.equals("verification/MAVEN_MODE.md") || reviewed(reviewed, name, file, existing), "Unreviewed helper difference: " + name + "/" + file);
                    proposed.put(file, source.get(file));
                }
            }
            for (String file : FFI_GUIDES) if (read(workspace, repo.resolve(file)) == null) proposed.put(file, source.get(file));
            List<String> changed = new ArrayList<>();
            for (var change : proposed.entrySet()) {
                String file = change.getKey(); require((!file.startsWith("src/") && !file.startsWith("native/")) || FFI_GUIDES.contains(file), "FFI scaffold is not shared build configuration: " + file);
                require(!file.matches(".*\\.(lock|kt|def|c|cpp|h|py)$"), "Refusing to copy project source or retired tooling: " + file);
                byte[] before = read(workspace, repo.resolve(file)); if (java.util.Arrays.equals(before, change.getValue())) continue;
                Map<String, Object> item = new LinkedHashMap<>(); item.put("repo", name); item.put("path", file); item.put("before_sha256", sha(before)); item.put("after_sha256", sha(change.getValue())); changes.add(item); changed.add(file);
            }
            contents.put(name, proposed); Map<String, Object> project = new LinkedHashMap<>(); project.put("name", name); project.put("head", record.get("head"));
            String status = PublicationSupport.string(record, "status"); project.put("branch_status", status.lines().findFirst().orElse("")); project.put("files", changed);
            project.put("preserved_build_specializations", build.preserved()); project.put("properties_delta", props.details()); project.put("catalog_additions", catalog.details());
            Map<String, String> projectProps = new TreeMap<>(); properties(props.text()).forEach((key, value) -> { if (key.startsWith("project.")) projectProps.put(key, value); }); project.put("project_properties", projectProps);
            projects.add(project);
        }
        Map<String, Object> plan = new LinkedHashMap<>(); plan.put("schema", 1); plan.put("workspace", workspace.toString()); plan.put("template_hashes", sourceHashes);
        plan.put("inventory", inventory); plan.put("reviewed_hashes", reviewed); plan.put("projects", projects); plan.put("changes", changes);
        plan.put("storage", "Hashes and compact metadata only; proposals are recomputed from current in-repository sources at apply time.");
        return new Proposal(plan, contents);
    }

    public static void apply(Path workspace, Map<String, Object> saved) throws IOException { apply(workspace, saved, WorkspaceBuildSync::git); }
    static void apply(Path workspace, Map<String, Object> saved, GitReader git) throws IOException {
        workspace = workspace.toRealPath(); require(workspace.toString().equals(saved.get("workspace")), "Plan belongs to another workspace");
        List<Map<String, Object>> inventory = PublicationSupport.array(saved.get("inventory")).stream().map(PublicationSupport::object).toList();
        Proposal fresh = proposal(workspace, inventory, objectAt(saved, "reviewed_hashes"), git);
        // Compare JSON trees to normalize integer types after a plan has been saved and reloaded.
        require(Json.parse(Json.stringify(fresh.plan())).equals(Json.parse(Json.stringify(saved))), "Stale template or proposal hash; review a fresh plan");
        List<Object> changes = PublicationSupport.array(saved.get("changes"));
        for (Object raw : changes) {
            Map<String, Object> change = PublicationSupport.object(raw); String name = PublicationSupport.string(change, "repo");
            require(!EXCEPTIONS.containsKey(name), "Excluded project in sync plan: " + name); Path repo = repository(workspace, name, false);
            Path target = repo.resolve(PublicationSupport.string(change, "path")); require(Objects.equals(sha(read(workspace, target)), change.get("before_sha256")), "Concurrent change: " + target);
            byte[] after = fresh.contents().get(name).get(PublicationSupport.string(change, "path")); require(sha(after).equals(change.get("after_sha256")), "Recomputed proposal hash mismatch");
        }
        for (Object raw : changes) {
            Map<String, Object> change = PublicationSupport.object(raw); String name = PublicationSupport.string(change, "repo"); String file = PublicationSupport.string(change, "path");
            Path target = workspace.resolve(name).resolve(file); require(Objects.equals(sha(read(workspace, target)), change.get("before_sha256")), "Concurrent change: " + target);
            Files.createDirectories(target.getParent()); safePath(workspace, target);
            if (change.get("before_sha256") == null) Files.write(target, fresh.contents().get(name).get(file), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            else Files.write(target, fresh.contents().get(name).get(file), StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS);
        }
    }

    public static String rewriteActionPins(String content) {
        Map<String, String> reviewed = Map.of("actions/setup-java@v6.0.1", "v6.1.0", "gradle/actions/setup-gradle@v6.3.0", "v6.4.0", "actions/setup-node@v7.0.0", "v7.1.0");
        var pattern = Pattern.compile("(^[ \\t]*(?:-[ \\t]+)?uses:[ \\t]*['\"]?)(actions/setup-java|gradle/actions/setup-gradle|actions/setup-node)@(v[0-9.]+)(?=[ \\t\\r'\"#]|$)", Pattern.MULTILINE);
        return pattern.matcher(content).replaceAll(match -> {
            String update = reviewed.get(match.group(2) + "@" + match.group(3));
            return java.util.regex.Matcher.quoteReplacement(update == null ? match.group() : match.group(1) + match.group(2) + "@" + update);
        });
    }
    public static List<String> actionPins(Path workspace, String name, boolean apply) throws IOException {
        Path repo = repository(workspace, name, false); Path github = repo.resolve(".github"); List<String> changed = new ArrayList<>();
        Map<Path, byte[]> before = new LinkedHashMap<>(); Map<Path, byte[]> after = new LinkedHashMap<>();
        if (Files.isDirectory(github)) try (var paths = Files.walk(github)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                Path rel = github.relativize(path); boolean ignored = false;
                for (Path part : rel) if (Set.of(".git", "node_modules", "tmp", "vendor", "upstream").contains(part.toString())) ignored = true;
                if (ignored || !path.toString().matches(".*\\.ya?ml")) continue;
                byte[] value = read(workspace, path); byte[] replacement = bytes(rewriteActionPins(text(value)));
                if (!java.util.Arrays.equals(value, replacement)) { changed.add(relative(repo, path)); before.put(path, value); after.put(path, replacement); }
            }
        }
        if (apply) {
            for (var entry : before.entrySet()) require(java.util.Arrays.equals(read(workspace, entry.getKey()), entry.getValue()), "Concurrent workflow change: " + entry.getKey());
            for (var entry : after.entrySet()) { require(java.util.Arrays.equals(read(workspace, entry.getKey()), before.get(entry.getKey())), "Concurrent workflow change: " + entry.getKey()); Files.write(entry.getKey(), entry.getValue(), StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS); }
        }
        return changed;
    }

    private static Map<String, Object> catalogDifferences(Map<String, Object> project, Map<String, Object> template) {
        Map<String, Object> result = new TreeMap<>();
        for (var section : project.entrySet()) { Map<String, Object> differences = new TreeMap<>(); for (var entry : PublicationSupport.object(section.getValue()).entrySet()) if (!Objects.equals(entry.getValue(), objectAt(template, section.getKey()).get(entry.getKey()))) differences.put(entry.getKey(), entry.getValue()); if (!differences.isEmpty()) result.put(section.getKey(), differences); }
        return result;
    }
    public static List<Map<String, String>> catalogFindings(String name, Map<String, Object> catalog, Map<String, String> properties) {
        List<Map<String, String>> findings = new ArrayList<>(); Map<String, Object> libraries = objectAt(catalog, "libraries"); Map<String, Object> versions = objectAt(catalog, "versions"); Map<String, Object> bundles = objectAt(catalog, "bundles");
        for (var bundle : bundles.entrySet()) for (Object alias : PublicationSupport.array(bundle.getValue())) if (!libraries.containsKey(alias)) findings.add(Map.of("repo", name, "kind", "missing_library", "value", alias.toString(), "bundle", bundle.getKey()));
        for (String section : List.of("libraries", "plugins")) for (var entry : objectAt(catalog, section).entrySet()) if (entry.getValue() instanceof Map<?, ?> definition) {
            Object version = definition.get("version"); if (version instanceof Map<?, ?> reference && reference.get("ref") instanceof String key && !versions.containsKey(key)) findings.add(Map.of("repo", name, "kind", "missing_version", "value", key, "alias", entry.getKey()));
        }
        for (var property : properties.entrySet()) if (property.getKey().startsWith("project.dependencies.") && property.getKey().endsWith("Bundle") && !property.getValue().isBlank() && !bundles.containsKey(property.getValue())) findings.add(Map.of("repo", name, "kind", "missing_bundle", "value", property.getValue(), "property", property.getKey()));
        return findings;
    }
    public static Map<String, Object> differenceCatalog(Path workspace, Map<String, Object> plan) throws IOException {
        Map<String, Object> template = catalog(text(read(workspace, workspace.resolve("template/gradle/libs.versions.toml")))); List<Map<String, Object>> projects = new ArrayList<>();
        for (var exception : EXCEPTIONS.entrySet()) projects.add(Map.of("project", exception.getKey(), "status", "excluded", "difference", exception.getValue(), "targeted_action", HELD.contains(exception.getKey()) ? "Keep excluded until explicit user direction changes." : "Adapt the applicable shared blocks individually; preserve the documented specialization."));
        List<Map<String, String>> allFindings = new ArrayList<>();
        for (Object raw : PublicationSupport.array(plan.get("projects"))) {
            Map<String, Object> row = PublicationSupport.object(raw); String name = PublicationSupport.string(row, "name"); Path repo = repository(workspace, name, false);
            require(!EXCEPTIONS.containsKey(name), "Excluded project in difference catalog"); Map<String, Object> actual = catalog(text(read(workspace, repo.resolve("gradle/libs.versions.toml")))); Map<String, String> props = properties(text(read(workspace, repo.resolve("gradle.properties"))));
            List<Map<String, String>> findings = catalogFindings(name, actual, props); allFindings.addAll(findings);
            Map<String, Object> entry = new LinkedHashMap<>(); entry.put("project", name); entry.put("status", "reviewed shared settings scope"); entry.put("build_specializations", row.get("preserved_build_specializations"));
            Map<String, String> projectProps = new TreeMap<>(); props.forEach((key, value) -> { if (key.startsWith("project.")) projectProps.put(key, value); }); entry.put("project_properties", projectProps);
            entry.put("catalog_differences", catalogDifferences(actual, template)); entry.put("catalog_default_additions", row.get("catalog_additions")); entry.put("existing_catalog_findings", findings); projects.add(entry);
        }
        return Map.of("scope", Map.of("selected", PublicationSupport.array(plan.get("projects")).size(), "excluded", EXCEPTIONS.size()), "projects", projects,
            "catalog_reference_findings", allFindings, "storage", "Compact reports under tools/; temporary build outputs inside the owning project. No source snapshots.",
            "serde_duplicate", Map.of("canonical_module", workspace.resolve("serde-kotlin/serde_derive").toString(), "canonical_coordinate", "io.github.kotlinmania:serde-derive", "standalone", "serderive-kotlin remains excluded"));
    }

    private static void writeInventory(Path workspace, Path output) throws IOException {
        List<Map<String, Object>> records = inventory(workspace); Json.write(output.resolve("inventory.json"), records); Json.write(output.resolve("exceptions.json"), EXCEPTIONS);
        StringBuilder selected = new StringBuilder(); Map<String, List<String>> grouped = new LinkedHashMap<>(); Map<String, List<String>> settings = new LinkedHashMap<>();
        for (Map<String, Object> record : records) {
            String name = PublicationSupport.string(record, "name"); if (record.get("exception") == null) selected.append(name).append('\n');
            Object hash = objectAt(record, "hashes").get("build.gradle.kts"); if (hash instanceof String digest) grouped.computeIfAbsent(digest, key -> new ArrayList<>()).add(name);
            if (!HELD.contains(name) && objectAt(record, "hashes").get("settings.gradle.kts") instanceof String) {
                String normalized = text(read(workspace, workspace.resolve(name).resolve("settings.gradle.kts"))).replaceAll("rootProject\\.name\\s*=.*", "rootProject.name = NAME"); settings.computeIfAbsent(sha(bytes(normalized)), key -> new ArrayList<>()).add(name);
            }
        }
        List<Map<String, Object>> groups = new ArrayList<>(); List<String> templateLines = lines(text(read(workspace, workspace.resolve("template/build.gradle.kts"))));
        for (var entry : grouped.entrySet()) { List<String> old = lines(text(read(workspace, workspace.resolve(entry.getValue().getFirst()).resolve("build.gradle.kts")))); Map<String, Integer> counts = new LinkedHashMap<>(); for (String line : templateLines) counts.merge(line, 1, Integer::sum); int common = 0; for (String line : old) if (counts.getOrDefault(line, 0) > 0) { common++; counts.merge(line, -1, Integer::sum); }
            groups.add(Map.of("hash", entry.getKey(), "repos", entry.getValue(), "similarity", 2.0 * common / Math.max(1, templateLines.size() + old.size()), "similarity_method", "line-multiset-overlap; review hashes control application", "lines", old.size())); }
        groups.sort(Comparator.comparingDouble(value -> -((Number) value.get("similarity")).doubleValue()));
        Json.write(output.resolve("build-groups.json"), groups); Json.write(output.resolve("settings-groups.json"), settings); Files.writeString(output.resolve("selected-repos.txt"), selected);
    }
    private static void outputPath(Path workspace, Path output) {
        safePath(workspace, output); Path path = output.toAbsolutePath().normalize();
        require(path.startsWith(workspace.resolve("tools").toAbsolutePath().normalize()) || path.startsWith(workspace.resolve("template/build").toAbsolutePath().normalize()), "Reports must stay in workspace tools/ or template/build/");
    }
    private static String option(Map<String, List<String>> options, String name, String fallback) { return options.containsKey(name) ? options.get(name).getFirst() : fallback; }
    public static int run(String... args) throws IOException {
        require(args.length > 0, "Choose inventory, plan, apply, catalog, diagnose, or actions"); String action = args[0]; Map<String, List<String>> options = new LinkedHashMap<>(); boolean apply = false;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--apply")) { apply = true; continue; }
            require(args[i].startsWith("--") && i + 1 < args.length, "Incomplete workspace sync option: " + args[i]); String key = args[i].substring(2);
            require(Set.of("workspace", "output", "inventory", "reviewed", "plan", "repo").contains(key) && !options.containsKey(key), "Unknown or duplicate option: " + args[i]); options.put(key, List.of(args[++i]));
        }
        require(options.containsKey("workspace"), "--workspace is required"); Path workspace = Path.of(option(options, "workspace", "")).toRealPath();
        Path output = Path.of(option(options, "output", workspace.resolve("tools/shared-build-sync").toString())); outputPath(workspace, output);
        Path planPath = Path.of(option(options, "plan", output.resolve("plan.json").toString())); outputPath(workspace, planPath);
        require(!apply || action.equals("actions"), "Use the reviewed apply action for build plans; generic --fix is disabled");
        switch (action) {
            case "inventory" -> { Files.createDirectories(output); writeInventory(workspace, output); }
            case "plan" -> {
                Path inventoryPath = Path.of(option(options, "inventory", output.resolve("inventory.json").toString())); Path reviewedPath = Path.of(option(options, "reviewed", output.resolve("reviewed-builds.json").toString())); safePath(workspace, inventoryPath); safePath(workspace, reviewedPath);
                Map<String, Object> result = plan(workspace, PublicationSupport.array(Json.read(inventoryPath)).stream().map(PublicationSupport::object).toList(), PublicationSupport.object(Json.read(reviewedPath)));
                Json.write(planPath, result); System.out.println("Planned " + PublicationSupport.array(result.get("changes")).size() + " shared-build changes; source is unchanged.");
            }
            case "apply" -> { Map<String, Object> saved = PublicationSupport.object(Json.read(planPath)); apply(workspace, saved); Json.write(output.resolve("applied.json"), Map.of("projects", PublicationSupport.array(saved.get("projects")).size(), "files", PublicationSupport.array(saved.get("changes")).size())); }
            case "catalog" -> {
                Map<String, Object> result = differenceCatalog(workspace, PublicationSupport.object(Json.read(planPath))); Json.write(output.resolve("difference-catalog.json"), result); Json.write(output.resolve("catalog-reference-findings.json"), result.get("catalog_reference_findings"));
                StringBuilder summary = new StringBuilder("Shared build sync difference catalog\n\n"); StringBuilder tsv = new StringBuilder("project\tstatus\tbuild_specialization\tffi_channels\tcatalog_findings\n");
                for (Object raw : PublicationSupport.array(result.get("projects"))) { Map<String, Object> entry = PublicationSupport.object(raw); String name = PublicationSupport.string(entry, "project"); String detail = String.valueOf(entry.getOrDefault("difference", entry.getOrDefault("build_specializations", List.of()))); Map<String, Object> props = objectAt(entry, "project_properties"); List<String> channels = List.of("native", "jvm", "napi").stream().filter(c -> "true".equals(props.get("project.ffi." + c))).toList(); summary.append(name).append("\n  ").append(detail).append('\n'); tsv.append(name).append('\t').append(entry.get("status")).append('\t').append(detail.replace('\t', ' ').replace('\n', ' ')).append('\t').append(String.join(",", channels)).append('\t').append(PublicationSupport.array(entry.getOrDefault("existing_catalog_findings", List.of())).size()).append('\n'); }
                Files.writeString(output.resolve("difference-catalog.txt"), summary); Files.writeString(output.resolve("project-index.tsv"), tsv);
            }
            case "actions" -> { require(options.containsKey("repo"), "Actions require one explicit --repo"); System.out.println(Json.stringify(Map.of("changed", actionPins(workspace, option(options, "repo", ""), apply), "applied", apply))); }
            case "diagnose" -> {
                Path repo = repository(workspace, option(options, "repo", ""), false); Map<String, Object> findings = new LinkedHashMap<>(); for (String file : PRIMARY_FILES) { byte[] actual = read(workspace, repo.resolve(file)); byte[] template = read(workspace, workspace.resolve("template").resolve(file)); if (!java.util.Arrays.equals(actual, template)) findings.put(file, actual == null ? "missing" : "different; preserve project-specific settings"); }
                findings.put("catalog_differences", catalogDifferences(catalog(text(read(workspace, repo.resolve("gradle/libs.versions.toml")))), catalog(text(read(workspace, workspace.resolve("template/gradle/libs.versions.toml")))))); findings.put("reviewed_action_pin_proposals", actionPins(workspace, repo.getFileName().toString(), false)); System.out.println(Json.stringify(findings));
            }
            default -> throw new IllegalArgumentException("Unknown workspace sync action; generic fixes and source snapshots are disabled: " + action);
        }
        return 0;
    }
    public static void main(String[] args) throws IOException { System.exit(run(args)); }
}
