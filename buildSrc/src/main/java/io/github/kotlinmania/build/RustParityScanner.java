package io.github.kotlinmania.build;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/** Read-only Cargo declaration audit. A declaration inventory never grants port acceptance. */
public final class RustParityScanner {
    private RustParityScanner() {}

    private static final List<String> KINDS = List.of("dependencies", "build-dependencies", "dev-dependencies");

    private static Map<String, Object> map(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value == null) return result;
        if (!(value instanceof Map<?, ?> source)) throw new IllegalArgumentException("Expected an object");
        source.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static List<Object> list(Object value) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> source)) throw new IllegalArgumentException("Expected an array");
        return new ArrayList<>(source);
    }

    private static Map<String, Object> object(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }

    private static boolean truth(Object value) {
        if (value == null || Boolean.FALSE.equals(value)) return false;
        if (value instanceof String string) return !string.isEmpty();
        if (value instanceof Map<?, ?> mapping) return !mapping.isEmpty();
        if (value instanceof List<?> values) return !values.isEmpty();
        if (value instanceof Number number) return number.doubleValue() != 0;
        return true;
    }

    private static String string(Object value) {
        if (!(value instanceof String string)) throw new IllegalArgumentException("Expected a string: " + value);
        return string;
    }

    private static Map<String, Object> specification(Object value) {
        return value instanceof String ? object("version", value) : map(value);
    }

    private static String digest(String algorithm, byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public static String sha256(byte[] bytes) { return digest("SHA-256", bytes); }

    public static String gitBlobSha1(byte[] bytes) {
        byte[] header = ("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII);
        byte[] blob = Arrays.copyOf(header, header.length + bytes.length);
        System.arraycopy(bytes, 0, blob, header.length, bytes.length);
        return digest("SHA-1", blob);
    }

    private static Object tomlValue(Object value) {
        if (value instanceof TomlTable table) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (String key : table.keySet()) result.put(key, tomlValue(table.get(List.of(key))));
            return result;
        }
        if (value instanceof TomlArray array) {
            List<Object> result = new ArrayList<>();
            for (int i = 0; i < array.size(); i++) result.add(tomlValue(array.get(i)));
            return result;
        }
        return value;
    }

    private static Map<String, Object> parseToml(byte[] bytes, String source) {
        TomlParseResult result = Toml.parse(new String(bytes, StandardCharsets.UTF_8));
        if (result.hasErrors()) throw new IllegalArgumentException("Invalid TOML in " + source + ": " + result.errors());
        return map(tomlValue(result));
    }

    /** Normalize repository paths with POSIX semantics, independent of the build host. */
    public static String normalized(String path) {
        boolean absolute = path.startsWith("/");
        List<String> parts = new ArrayList<>();
        for (String part : path.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..") && !parts.isEmpty() && !parts.getLast().equals("..")) parts.removeLast();
            else if (!part.equals("..") || !absolute) parts.add(part);
        }
        String result = (absolute ? "/" : "") + String.join("/", parts);
        if (result.isEmpty()) result = ".";
        if (absolute || result.equals("..") || result.startsWith("../") || result.contains("\\")) {
            throw new IllegalArgumentException("Path outside indexed upstream repository: " + result);
        }
        return result;
    }

    private static String join(String... parts) {
        String result = "";
        for (String part : parts) {
            if (part.startsWith("/") || result.isEmpty()) result = part;
            else result += (result.endsWith("/") ? "" : "/") + part;
        }
        return result;
    }

    private static String dirname(String name) {
        int slash = name.lastIndexOf('/');
        return slash < 0 ? "" : name.substring(0, slash);
    }

    private static String directory(String name) {
        String value = dirname(name);
        return value.isEmpty() ? "." : value;
    }

    /** Cargo-style component glob: a single star cannot consume a slash. */
    public static boolean matches(String pattern, String path) {
        String[] patterns = pattern.split("/", -1), parts = path.split("/", -1);
        return components(patterns, parts, 0, 0, new Boolean[patterns.length + 1][parts.length + 1]);
    }

    private static boolean components(String[] patterns, String[] parts, int i, int j, Boolean[][] memo) {
        if (memo[i][j] != null) return memo[i][j];
        boolean result;
        if (i == patterns.length) result = j == parts.length;
        else if (patterns[i].equals("**")) {
            result = components(patterns, parts, i + 1, j, memo)
                    || j < parts.length && components(patterns, parts, i, j + 1, memo);
        } else {
            result = j < parts.length && component(patterns[i], parts[j], 0, 0,
                    new Boolean[patterns[i].length() + 1][parts[j].length() + 1])
                    && components(patterns, parts, i + 1, j + 1, memo);
        }
        return memo[i][j] = result;
    }

    private static boolean component(String pattern, String value, int i, int j, Boolean[][] memo) {
        if (memo[i][j] != null) return memo[i][j];
        boolean result;
        if (i == pattern.length()) result = j == value.length();
        else if (pattern.charAt(i) == '*') {
            result = component(pattern, value, i + 1, j, memo)
                    || j < value.length() && component(pattern, value, i, j + 1, memo);
        } else if (j == value.length()) result = false;
        else if (pattern.charAt(i) == '?') result = component(pattern, value, i + 1, j + 1, memo);
        else if (pattern.charAt(i) == '[') {
            int start = i + 1;
            boolean negative = start < pattern.length() && pattern.charAt(start) == '!';
            if (negative) start++;
            int end = start;
            if (end < pattern.length() && pattern.charAt(end) == ']') end++;
            while (end < pattern.length() && pattern.charAt(end) != ']') end++;
            if (end == pattern.length()) result = value.charAt(j) == '[' && component(pattern, value, i + 1, j + 1, memo);
            else {
                char character = value.charAt(j);
                boolean found = false;
                for (int at = start; at < end; at++) {
                    if (at + 2 < end && pattern.charAt(at + 1) == '-') {
                        found |= character >= pattern.charAt(at) && character <= pattern.charAt(at + 2);
                        at += 2;
                    } else found |= character == pattern.charAt(at);
                }
                result = found != negative && component(pattern, value, end + 1, j + 1, memo);
            }
        } else result = pattern.charAt(i) == value.charAt(j) && component(pattern, value, i + 1, j + 1, memo);
        return memo[i][j] = result;
    }

    private record Dependency(String kind, String target, String alias, Object value) {}

    private static List<Dependency> tables(Map<String, Object> data) {
        List<Dependency> result = new ArrayList<>();
        for (String kind : KINDS) map(data.get(kind)).forEach((alias, value) -> result.add(new Dependency(kind, "*", alias, value)));
        map(data.get("target")).forEach((target, sections) -> {
            for (String kind : KINDS) map(map(sections).get(kind)).forEach((alias, value) -> result.add(new Dependency(kind, target, alias, value)));
        });
        return result;
    }

    private static void gap(List<Map<String, Object>> findings, String code, String key, String detail) {
        findings.add(object("code", code, "key", key, "detail", detail));
    }

    public static Map<String, Object> audit(Path indexPath, Path kotlinRoot, Map<String, Object> mapping) throws IOException {
        Map<String, Object> index = map(Json.read(indexPath));
        if (!(index.get("revision") instanceof String revision) || !revision.matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException("Pin the exact upstream revision");
        }
        List<Map<String, Object>> findings = new ArrayList<>();
        Map<String, Map<String, Object>> manifests = new LinkedHashMap<>(), proofs = new LinkedHashMap<>();
        for (Object entry : list(index.get("manifests"))) {
            Map<String, Object> row = map(entry);
            String name = normalized(string(row.get("upstream_path")));
            if (manifests.containsKey(name)) throw new IllegalArgumentException("Duplicate manifest: " + name);
            byte[] content = Files.readAllBytes(Path.of(string(row.get("local_path"))));
            if (!sha256(content).equals(row.get("sha256"))) throw new IllegalArgumentException("Stale manifest hash: " + name);
            if (!gitBlobSha1(content).equals(row.get("git_blob_sha1"))) throw new IllegalArgumentException("Unverified Git blob: " + name);
            manifests.put(name, parseToml(content, name));
            proofs.put(name, row);
        }
        String rootName = normalized(string(index.getOrDefault("root_manifest", "Cargo.toml")));
        Map<String, Object> root = manifests.get(rootName);
        if (root == null) throw new IllegalArgumentException("Missing root manifest: " + rootName);
        String base = dirname(rootName);
        Map<String, Object> workspace = map(root.get("workspace"));
        Set<String> treePaths = new TreeSet<>();
        if (truth(index.get("tree"))) {
            Map<String, Object> treeRow = map(index.get("tree"));
            byte[] content = Files.readAllBytes(Path.of(string(treeRow.get("local_path"))));
            if (!sha256(content).equals(treeRow.get("sha256")) || !index.get("revision").equals(treeRow.get("revision"))) throw new IllegalArgumentException("Stale upstream tree inventory");
            Map<String, Object> tree = map(Json.parse(new String(content, StandardCharsets.UTF_8)));
            if (!Boolean.FALSE.equals(tree.get("truncated"))) throw new IllegalArgumentException("Truncated tree cannot establish workspace coverage");
            Map<String, Map<String, Object>> blobs = new LinkedHashMap<>();
            for (Object item : list(tree.get("tree"))) {
                Map<String, Object> blob = map(item);
                if ("blob".equals(blob.get("type"))) blobs.put(string(blob.get("path")), blob);
            }
            for (Map.Entry<String, Map<String, Object>> proof : proofs.entrySet()) {
                if (!proof.getValue().get("git_blob_sha1").equals(blobs.getOrDefault(proof.getKey(), Map.of()).get("sha"))) throw new IllegalArgumentException("Manifest/tree blob mismatch: " + proof.getKey());
            }
            treePaths.addAll(blobs.keySet());
        } else gap(findings, "SOURCE_TREE_UNVERIFIED", rootName, "Automatic targets and omitted workspace manifests are not inventoried");
        List<String> excludedPatterns = new ArrayList<>();
        for (Object value : list(workspace.get("exclude"))) excludedPatterns.add(normalized(join(base, string(value))));
        Predicate<String> excluded = name -> excludedPatterns.stream().anyMatch(pattern -> matches(pattern, directory(name)));
        Set<String> members = new TreeSet<>(), processed = new TreeSet<>();
        if (root.containsKey("package")) members.add(rootName);
        Set<String> knownPaths = new TreeSet<>(treePaths);
        knownPaths.addAll(manifests.keySet());
        for (Object value : list(workspace.get("members"))) {
            String full = normalized(join(base, string(value)));
            List<String> selected = knownPaths.stream().filter(path -> path.endsWith("Cargo.toml") && matches(full, directory(path)) && !excluded.test(path)).toList();
            if (selected.isEmpty()) gap(findings, "MISSING_MEMBER", full, "Declared workspace member has no indexed Cargo.toml");
            members.addAll(selected);
        }
        List<Map<String, Object>> dependencies = new ArrayList<>();
        while (!processed.containsAll(members)) {
            String name = members.stream().filter(member -> !processed.contains(member)).findFirst().orElseThrow();
            processed.add(name);
            if (!manifests.containsKey(name)) {
                gap(findings, "MISSING_MANIFEST_BYTES", name, "Retrieve this exact revision manifest; tree presence alone is insufficient");
                continue;
            }
            for (Dependency dependency : tables(manifests.get(name))) {
                Map<String, Object> spec = specification(dependency.value());
                String origin = dirname(name);
                boolean uncertainDefault = false;
                if (truth(spec.remove("workspace"))) {
                    Object inheritedValue = map(workspace.get("dependencies")).get(dependency.alias());
                    if (inheritedValue == null) {
                        gap(findings, "UNRESOLVED_INHERITANCE", name + ":" + dependency.alias(), "Missing workspace.dependencies entry");
                        continue;
                    }
                    Map<String, Object> inherited = specification(inheritedValue);
                    Set<String> features = new TreeSet<>();
                    for (Object feature : list(inherited.get("features"))) features.add(string(feature));
                    for (Object feature : list(spec.get("features"))) features.add(string(feature));
                    if (truth(inherited.get("optional"))) gap(findings, "UNSUPPORTED_INHERITANCE", name + ":" + dependency.alias(), "Workspace dependencies cannot be optional");
                    if (Boolean.FALSE.equals(spec.get("default-features")) && truth(inherited.getOrDefault("default-features", true))) {
                        gap(findings, "DEFAULT_FEATURE_OVERRIDE", name + ":" + dependency.alias(), "Cargo/toolchain/edition evidence required; do not assume default features disabled");
                        uncertainDefault = true;
                    }
                    for (Map.Entry<String, Object> entry : spec.entrySet()) if (!entry.getKey().equals("features")) inherited.put(entry.getKey(), entry.getValue());
                    inherited.put("features", new ArrayList<>(features));
                    spec = inherited;
                    origin = base;
                }
                String key = name + "::" + dependency.kind() + "::" + dependency.target() + "::" + dependency.alias();
                Map<String, Object> edge = object("id", key, "manifest", name, "kind", dependency.kind(), "target_condition", dependency.target(),
                        "alias", dependency.alias(), "package", spec.getOrDefault("package", dependency.alias()), "specification", spec,
                        "features", spec.getOrDefault("features", List.of()), "default_features", uncertainDefault ? null : spec.getOrDefault("default-features", true),
                        "optional", spec.getOrDefault("optional", false));
                if (spec.containsKey("path")) {
                    String path = normalized(join(origin, string(spec.get("path")), "Cargo.toml"));
                    edge.put("path_manifest", path);
                    if (!knownPaths.contains(path)) gap(findings, "MISSING_PATH_DEPENDENCY", key, path);
                    else if (!excluded.test(path) && (base.isEmpty() || path.startsWith(base + "/"))) {
                        if (root.containsKey("workspace")) members.add(path);
                        else gap(findings, "PATH_CRATE_GRAPH_UNVERIFIED", key, "No explicit workspace: path dependency is not assumed to be a workspace member");
                    }
                }
                dependencies.add(edge);
            }
        }
        List<Map<String, Object>> features = new ArrayList<>(), targets = new ArrayList<>(), memberRows = new ArrayList<>();
        for (String name : processed) {
            if (!manifests.containsKey(name)) continue;
            Map<String, Object> data = manifests.get(name), pkg = map(data.get("package"));
            for (Map.Entry<String, Object> entry : pkg.entrySet()) {
                if (entry.getValue() instanceof Map<?, ?> && truth(map(entry.getValue()).get("workspace"))) {
                    Map<String, Object> inherited = map(workspace.get("package"));
                    if (!inherited.containsKey(entry.getKey())) gap(findings, "UNRESOLVED_PACKAGE_INHERITANCE", name + ":" + entry.getKey(), "Missing workspace.package value");
                    else entry.setValue(inherited.get(entry.getKey()));
                }
            }
            memberRows.add(object("manifest", name, "package", pkg, "sha256", proofs.get(name).get("sha256")));
            map(data.get("features")).forEach((feature, value) -> features.add(object("id", name + "::" + feature, "manifest", name, "name", feature, "enables", value)));
            String directory = dirname(name);
            for (List<String> configuration : List.of(List.of("test", "tests", "autotests"), List.of("bench", "benches", "autobenches"), List.of("example", "examples", "autoexamples"), List.of("bin", "src/bin", "autobins"))) {
                String kind = configuration.get(0), folder = configuration.get(1), auto = configuration.get(2);
                Map<String, Map<String, Object>> selected = new LinkedHashMap<>();
                for (Object targetValue : list(data.get(kind))) {
                    Map<String, Object> target = map(targetValue);
                    target.put("discovered", "explicit");
                    selected.put(string(target.get("name")), target);
                }
                String prefix = join(directory, folder) + "/", mainSource = join(directory, "src/main.rs");
                if (kind.equals("bin") && truth(pkg.getOrDefault(auto, true)) && treePaths.contains(mainSource)) {
                    String packageName = string(pkg.get("name"));
                    selected.putIfAbsent(packageName, object("name", packageName, "path", "src/main.rs", "discovered", "automatic"));
                }
                if (truth(pkg.getOrDefault(auto, true))) {
                    for (String source : treePaths) {
                        if (!source.startsWith(prefix)) continue;
                        String relative = source.substring(prefix.length());
                        String stem = null;
                        if (!relative.contains("/") && relative.endsWith(".rs")) stem = relative.substring(0, relative.length() - 3);
                        else if (relative.endsWith("/main.rs") && relative.chars().filter(c -> c == '/').count() == 1) stem = relative.substring(0, relative.indexOf('/'));
                        if (stem != null) selected.putIfAbsent(stem, object("name", stem, "path", join(folder, relative), "discovered", "automatic"));
                    }
                }
                for (Map.Entry<String, Map<String, Object>> entry : selected.entrySet()) {
                    String targetName = entry.getKey();
                    Map<String, Object> target = entry.getValue();
                    String source = normalized(join(directory, string(target.getOrDefault("path", folder + "/" + targetName + ".rs"))));
                    target.putAll(object("id", name + "::" + kind + "::" + targetName, "manifest", name, "kind", kind, "source", source, "source_in_tree", treePaths.contains(source)));
                    targets.add(target);
                    if (!treePaths.isEmpty() && !treePaths.contains(source)) gap(findings, "TARGET_SOURCE_MISSING", name + ":" + targetName, source);
                }
            }
            for (String kind : List.of("lib", "build")) {
                boolean lib = kind.equals("lib");
                String defaultPath = lib ? "src/lib.rs" : "build.rs";
                boolean enabled = lib ? truth(pkg.getOrDefault("autolib", true)) : !Boolean.FALSE.equals(pkg.get("build"));
                Map<String, Object> config = lib ? map(data.get("lib")) : Map.of();
                if (lib && !config.isEmpty()) enabled = true;
                if (!enabled) continue;
                String source = normalized(join(directory, string(lib ? config.getOrDefault("path", defaultPath) : pkg.getOrDefault("build", defaultPath))));
                if (treePaths.contains(source) || lib && !config.isEmpty()) targets.add(object("id", name + "::" + kind, "manifest", name, "kind", kind, "source", source, "source_in_tree", treePaths.contains(source), "test", lib ? config.getOrDefault("test", true) : false));
            }
        }
        for (Map<String, Object> edge : dependencies) {
            String key = edge.get("manifest") + "::" + edge.get("alias");
            List<Map<String, Object>> existing = features.stream().filter(row -> row.get("manifest").equals(edge.get("manifest"))).toList();
            boolean explicitDependency = existing.stream().anyMatch(row -> list(row.get("enables")).contains("dep:" + edge.get("alias")));
            if (truth(edge.get("optional")) && !explicitDependency && existing.stream().noneMatch(row -> row.get("id").equals(key))) {
                features.add(object("id", key, "manifest", edge.get("manifest"), "name", edge.get("alias"), "enables", List.of("dep:" + edge.get("alias")), "implicit_optional_dependency", true));
            }
        }
        Map<String, Object> overrides = new LinkedHashMap<>();
        for (String key : List.of("patch", "replace")) if (root.containsKey(key)) overrides.put(key, root.get(key));
        if (!overrides.isEmpty()) gap(findings, "CARGO_OVERRIDE_GRAPH_UNVERIFIED", rootName, "Patch/replace affects actual selection; retain Cargo resolver evidence");
        List<Map<String, Object>> excludedRows = new ArrayList<>();
        for (String path : knownPaths) {
            if (path.endsWith("Cargo.toml") && !processed.contains(path) && !path.equals(rootName)) excludedRows.add(object("manifest", path, "reason", excluded.test(path) ? "workspace.exclude" : "outside selected member/path closure; scope review required"));
        }
        Map<String, Object> reviews = mapping == null ? Map.of() : mapping;
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        groups.put("members", memberRows); groups.put("dependencies", dependencies); groups.put("features", features); groups.put("targets", targets);
        for (Map.Entry<String, List<Map<String, Object>>> group : groups.entrySet()) {
            for (Map<String, Object> row : group.getValue()) {
                String key = string(row.getOrDefault("id", row.get("manifest")));
                Map<String, Object> review = map(map(reviews.get(group.getKey())).get(key));
                row.put("review", review.isEmpty() ? object("status", "unmapped") : review);
                if (review.isEmpty() || !Set.of("implemented", "language-specific", "upstream-release-tooling").contains(String.valueOf(review.get("status"))) || !truth(review.get("rationale"))) {
                    gap(findings, "UNMAPPED_" + group.getKey().toUpperCase(java.util.Locale.ROOT), key, "Explicit behavior/tooling/feature mapping and justified differences required");
                } else if (!truth(review.get("evidence"))) gap(findings, "REVIEW_EVIDENCE_MISSING", key, "Classification alone does not establish the mapping");
                else for (Object value : list(review.get("evidence"))) {
                    Map<String, Object> evidence = map(value);
                    Path path = Path.of(string(evidence.get("path")));
                    if (!Files.isRegularFile(path) || !sha256(Files.readAllBytes(path)).equals(evidence.get("sha256"))) gap(findings, "STALE_REVIEW_EVIDENCE", key, path.toString());
                }
            }
        }
        for (Map<String, Object> row : excludedRows) {
            if (!row.get("reason").equals("workspace.exclude") && !truth(map(map(reviews.get("excluded")).get(row.get("manifest"))).get("rationale"))) gap(findings, "UNREVIEWED_EXCLUDED_MANIFEST", string(row.get("manifest")), string(row.get("reason")));
        }
        Path catalog = kotlinRoot.resolve("gradle/libs.versions.toml");
        byte[] catalogBytes = Files.exists(catalog) ? Files.readAllBytes(catalog) : null;
        return object("schema_version", 1, "upstream", object("repository", index.get("repository"), "revision", index.get("revision"), "root_manifest", rootName),
                "workspace_context", workspace, "manifest_proofs", new ArrayList<>(proofs.values()), "members", memberRows, "excluded_manifests", excludedRows,
                "dependencies", dependencies, "features", features, "targets", targets, "dependency_overrides", overrides, "findings", findings,
                "kotlin_catalog", object("path", catalog.toString(), "sha256", catalogBytes == null ? null : sha256(catalogBytes), "declarations", catalogBytes == null ? null : parseToml(catalogBytes, catalog.toString())),
                "declaration_mapping_status", findings.isEmpty() ? "classified" : "incomplete",
                "actual_runtime_test_tooling_graph", "unverified; catalog declarations are not resolved dependency evidence",
                "published_sibling_capabilities", "unverified; require actual variant/API and consumer evidence",
                "ast_function_mapping", "unverified; require established ast_distance binary/command/input hashes and reviewed missing/divergent functions; score alone is insufficient",
                "resolved_feature_graph", "unverified; these are declarations, not Cargo resolver output or transitive feature unification",
                "port_acceptance", "unverified; source classification and counts never establish semantic parity or runtime acceptance");
    }

    public static int run(String... args) throws IOException {
        Path index = null, root = null, mapping = null, output = null;
        boolean strict = false;
        for (int i = 0; i < args.length; i++) {
            String option = args[i];
            if (option.equals("--strict")) { strict = true; continue; }
            if (i + 1 >= args.length) throw new IllegalArgumentException("Missing value for " + option);
            String value = args[++i];
            switch (option) {
                case "--manifest-index" -> index = Path.of(value);
                case "--kotlin-root" -> root = Path.of(value);
                case "--mapping" -> mapping = Path.of(value);
                case "--output" -> output = Path.of(value);
                default -> throw new IllegalArgumentException("Unknown argument: " + option);
            }
        }
        if (index == null || root == null || output == null) throw new IllegalArgumentException("--manifest-index, --kotlin-root and --output are required");
        Map<String, Object> result = audit(index, root, mapping == null ? null : map(Json.read(mapping)));
        Json.write(output, result);
        int findings = list(result.get("findings")).size();
        System.out.println(Json.stringify(object("mapping", result.get("declaration_mapping_status"), "findings", findings,
                "members", list(result.get("members")).size(), "targets", list(result.get("targets")).size(), "port_acceptance", result.get("port_acceptance"))));
        return strict && findings != 0 ? 1 : 0;
    }

    public static void main(String[] args) throws IOException { System.exit(run(args)); }
}
