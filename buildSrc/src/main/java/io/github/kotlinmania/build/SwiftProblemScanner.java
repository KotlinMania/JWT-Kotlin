package io.github.kotlinmania.build;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Read-only versioned Swift source and evidence audit; findings are not runtime acceptance. */
public final class SwiftProblemScanner {
    private SwiftProblemScanner() {}

    private static Map<String, Object> map(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value == null) return result;
        if (!(value instanceof Map<?, ?> source)) throw new IllegalArgumentException("Expected JSON object");
        source.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static List<Object> list(Object value) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> source)) throw new IllegalArgumentException("Expected JSON array");
        return new ArrayList<>(source);
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private static boolean zero(Object value) { return value instanceof Number number && number.doubleValue() == 0; }
    public static String sha(Path path) throws IOException { return SwiftManifestEnforcer.sha256(Files.readAllBytes(path)); }
    private static String read(Path path) throws IOException { return new String(Files.readAllBytes(path), StandardCharsets.UTF_8); }
    private static String suffix(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot);
    }
    private static String relative(Path root, Path file) { return root.relativize(file).toString().replace('\\', '/'); }

    private static List<Path> walk(Path root, Set<String> extensions, Set<String> excluded) throws IOException {
        if (root == null || !Files.isDirectory(root)) return List.of();
        List<Path> result = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                return !directory.equals(root) && excluded.contains(directory.getFileName().toString())
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (Files.isRegularFile(file) && extensions.contains(suffix(file))) result.add(file);
                return FileVisitResult.CONTINUE;
            }
        });
        result.sort(Path::compareTo);
        return result;
    }

    public static List<Path> sourceFiles(Path root) throws IOException {
        Set<Path> result = new TreeSet<>();
        for (String file : List.of("build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradle/libs.versions.toml")) {
            Path path = root.resolve(file);
            if (Files.isRegularFile(path)) result.add(path);
        }
        Set<String> extensions = Set.of(".kt", ".kts", ".java", ".swift", ".c", ".cpp", ".h", ".def", ".yml", ".yaml", ".gyp", ".json", ".xcconfig");
        for (String directory : List.of("src", "src/swift/swift-test-harness", "native", ".github", "verification", "buildSrc")) {
            result.addAll(walk(root.resolve(directory), extensions, Set.of(".build", "node_modules", "build", ".git")));
        }
        return new ArrayList<>(result);
    }

    public static List<Path> generatedFiles(Path root) throws IOException {
        return walk(root, Set.of(".swift", ".kt", ".h", ".c", ".modulemap"), Set.of(".build", ".git"));
    }

    private static Map<String, String> inventory(Path root, List<Path> files) throws IOException {
        Map<String, String> result = new TreeMap<>();
        for (Path file : files) result.put(relative(root, file), sha(file));
        return result;
    }

    private static boolean matches(String pattern, String input) { return Pattern.compile(pattern).matcher(input).find(); }

    private static void add(List<Map<String, Object>> findings, Map<String, Object> rules, String rule, Path path, String source, int offset) {
        Map<String, Object> record = map(rules.get(rule));
        record.put("rule", rule);
        record.put("path", path.toString());
        record.put("line", 1 + source.substring(0, offset).chars().filter(c -> c == '\n').count());
        findings.add(record);
    }

    public static Map<String, Object> scan(Path root, Path generated, Path bridge, Path evidence,
                                          List<String> requiredTests, Path registryPath) throws IOException {
        Map<String, Object> registry = map(Json.read(registryPath));
        Map<String, Object> rules = map(registry.get("rules"));
        List<Map<String, Object>> findings = new ArrayList<>();
        List<Path> source = sourceFiles(root);
        List<Path> emitted = new ArrayList<>(generatedFiles(generated));
        emitted.addAll(generatedFiles(bridge));
        Set<Path> emittedSet = new HashSet<>(emitted);
        List<Path> all = new ArrayList<>(source);
        all.addAll(emitted);
        for (Path file : all) {
            String content = read(file);
            String extension = suffix(file);
            boolean isGenerated = emittedSet.contains(file);
            if (file.getFileName().toString().equals("build.gradle.kts")) {
                try { SwiftManifestEnforcer.validateWarningPolicy(content); }
                catch (IllegalArgumentException ignored) { add(findings, rules, "SWIFT001", file, content, 0); }
                if (matches("(?i)(?:patched[-_].*export|swiftExportClasspath\\s*\\.\\s*setFrom)", content)) add(findings, rules, "SWIFT007", file, content, 0);
                if (content.contains("writeText") && content.contains("replace")
                        && matches("(?:Serialization|removeRange|filterNot).*", content)
                        && matches("(?:spmDir|\\.swift[\"'])", content)) add(findings, rules, "SWIFT003", file, content, 0);
                if (matches("replace[^\\n]*swift-tools-version[^\\n]*5\\.9", content)) add(findings, rules, "SWIFT002", file, content, 0);
            }
            if (Set.of(".yml", ".yaml", ".xcconfig").contains(extension)) {
                if (matches("SWIFT_VERSION\\s*[:=]\\s*[\"']?5|swift-version[\"']?\\s*[, ]+5", content)) add(findings, rules, "SWIFT002", file, content, 0);
                if (matches("(?i)SWIFT_TREAT_WARNINGS_AS_ERRORS\\s*[:=]\\s*(?:NO|false)", content)) add(findings, rules, "SWIFT001", file, content, 0);
            }
            if (extension.equals(".swift")) {
                if (!isGenerated) {
                    Matcher placeholders = Pattern.compile("(?:#expect\\(\\s*(?:Bool\\()?true|XCTAssertTrue\\(\\s*true)").matcher(content);
                    while (placeholders.find()) add(findings, rules, "SWIFT008", file, content, placeholders.start());
                } else {
                    Map<String, String> patterns = new LinkedHashMap<>();
                    patterns.put("@_implementationOnly\\s+import", "SWIFT004");
                    patterns.put("struct\\s+KotlinError\\s*:[\\s\\S]{0,500}wrapped\\s*:\\s*KotlinRuntime\\.KotlinBase", "SWIFT005");
                    patterns.put("@preconcurrency|@unchecked\\s+Sendable", "SWIFT007");
                    for (Map.Entry<String, String> entry : patterns.entrySet()) {
                        Matcher match = Pattern.compile(entry.getKey()).matcher(content);
                        if (match.find()) add(findings, rules, entry.getValue(), file, content, match.start());
                    }
                }
            }
            if (isGenerated && extension.equals(".kt")) {
                Matcher match = Pattern.compile("as\\s+kotlin\\.Function\\d+<|as\\s+io\\.github\\.kotlinmania\\.[^<\\n]+<kotlin\\.Any|_result\\s*;\\s*true").matcher(content);
                if (match.find()) add(findings, rules, "SWIFT006", file, content, match.start());
            }
        }
        for (Path file : all) {
            if (!file.getFileName().toString().equals("Package.swift")) continue;
            String content = Files.readString(file);
            List<String> values = SwiftManifestEnforcer.tokens(content).stream().map(SwiftManifestEnforcer.Token::value).toList();
            Matcher header = Pattern.compile("^// swift-tools-version:\\s*(\\d+)\\.(\\d+)").matcher(content);
            List<String> strict = List.of("swiftLanguageModes", ":", "[", ".", "v6", "]");
            boolean packageMode = false;
            boolean weaker = false;
            for (int i = 0; i < values.size(); i++) {
                if (i + strict.size() <= values.size() && values.subList(i, i + strict.size()).equals(strict)) packageMode = true;
                if (values.get(i).equals("swiftLanguageMode")) {
                    List<String> target = List.of("swiftLanguageMode", "(", ".", "v6", ")");
                    weaker |= i + target.size() > values.size() || !values.subList(i, Math.min(i + target.size(), values.size())).equals(target);
                }
            }
            if (!header.find() || Integer.parseInt(header.group(1)) < 6 || !packageMode || weaker || values.contains("swiftLanguageVersions")) {
                add(findings, rules, "SWIFT002", file, content, 0);
            }
        }
        Map<String, String> sourceHashes = inventory(root, source);
        Map<String, String> emittedHashes = new TreeMap<>();
        Set<String> modules = new TreeSet<>();
        Path[] bases = {generated, bridge};
        String[] prefixes = {"spm", "bridge"};
        for (int i = 0; i < bases.length; i++) {
            for (Path file : generatedFiles(bases[i])) {
                Path rel = bases[i].relativize(file);
                emittedHashes.put(prefixes[i] + "/" + rel.toString().replace('\\', '/'), sha(file));
                if (suffix(file).equals(".swift")) {
                    for (int part = 0; part + 1 < rel.getNameCount(); part++) {
                        if (rel.getName(part).toString().equals("Sources")) { modules.add(rel.getName(part + 1).toString()); break; }
                    }
                }
            }
        }
        List<String> errors = new ArrayList<>();
        if (evidence == null) errors.add("No compiler/runtime evidence supplied");
        else {
            try {
                Map<String, Object> data = map(Json.read(evidence));
                if (!(data.get("schema_version") instanceof Number version) || version.doubleValue() != 1 || !sourceHashes.equals(data.get("source_sha256"))) errors.add("Missing/stale exact source inventory");
                if (emittedHashes.isEmpty() || generated == null || bridge == null || !emittedHashes.equals(data.get("generated_sha256"))) errors.add("Missing/stale generated Swift AND Kotlin bridge inventory");
                Map<String, Object> generation = map(data.get("generation_receipt"));
                String runId = text(generation.get("run_id"));
                if (runId.isEmpty() || !zero(generation.get("exit_code"))
                        || !sourceHashes.equals(generation.get("source_sha256")) || !emittedHashes.equals(generation.get("generated_sha256"))
                        || list(generation.get("argv")).stream().map(SwiftProblemScanner::text).noneMatch(v -> v.contains("SwiftExport") || v.contains("swiftExport"))) {
                    errors.add("Fresh exact generation receipt missing or failed");
                }
                String generationLog = text(generation.get("log_path"));
                if (generationLog.isEmpty() || !sha(Path.of(generationLog)).equals(generation.get("log_sha256"))) errors.add("Missing/stale generation command log");
                List<Object> tools = list(data.get("toolchain_files"));
                boolean badTools = tools.isEmpty();
                for (Object item : tools) {
                    Map<String, Object> tool = map(item);
                    badTools |= !sha(Path.of(text(tool.get("path")))).equals(tool.get("sha256"));
                }
                if (badTools) errors.add("Missing/stale exact toolchain files");
                Set<String> strictModules = new HashSet<>();
                for (Object item : list(data.get("frontend_logs"))) {
                    Map<String, Object> record = map(item);
                    Path file = Path.of(text(record.get("path")));
                    validateRun(record, runId, file, errors);
                    if (!sha(file).equals(record.get("sha256"))) { errors.add("Stale frontend log: " + file); continue; }
                    for (String line : read(file).split("\\R")) {
                        if (!line.contains("swift-frontend") && !line.contains("/swiftc ")) continue;
                        List<String> command = shellWords(line);
                        String mode = option(command, "-swift-version");
                        String module = option(command, "-module-name");
                        if (mode.equals("6") && command.contains("-warnings-as-errors") && !module.isEmpty()) strictModules.add(module);
                    }
                }
                String consumer = text(data.get("consumer_module"));
                Set<String> requiredModules = new HashSet<>(modules);
                requiredModules.add(consumer);
                if (modules.isEmpty() || consumer.isEmpty() || !strictModules.containsAll(requiredModules)) errors.add("Actual strict frontend commands missing for generated dependencies or consumer");
                Set<String> passed = new HashSet<>();
                for (Object item : list(data.get("test_results_xml"))) {
                    Map<String, Object> record = map(item);
                    Path file = Path.of(text(record.get("path")));
                    validateRun(record, runId, file, errors);
                    if (!sha(file).equals(record.get("sha256"))) { errors.add("Stale runtime XML: " + file); continue; }
                    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
                    NodeList cases = factory.newDocumentBuilder().parse(file.toFile()).getElementsByTagName("testcase");
                    if (cases.getLength() == 0) errors.add("No actual named consumer tests: " + file);
                    for (int c = 0; c < cases.getLength(); c++) {
                        Element test = (Element) cases.item(c);
                        boolean failed = false;
                        for (String tag : List.of("failure", "error", "skipped")) {
                            for (int child = 0; child < test.getChildNodes().getLength(); child++) failed |= test.getChildNodes().item(child).getNodeName().equals(tag);
                        }
                        if (failed) errors.add("Failed/errored/skipped consumer test: " + (test.hasAttribute("name") ? test.getAttribute("name") : "?"));
                        else passed.add(test.getAttribute("classname") + "." + test.getAttribute("name"));
                    }
                }
                if (requiredTests.isEmpty() || !passed.containsAll(requiredTests)) errors.add("Caller-required actual named behavior tests missing");
            } catch (Exception failure) {
                errors.add("Unsupported/incomplete acceptance receipt: " + failure.getMessage());
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema_version", 1);
        result.put("recipe_version", registry.get("recipe_version"));
        result.put("findings", findings);
        result.put("source_sha256", sourceHashes);
        result.put("generated_sha256", emittedHashes);
        result.put("generated_swift_modules", new ArrayList<>(modules));
        result.put("acceptance", Map.of("status", errors.isEmpty() ? "evidence-consistent-scoped-consumer" : "unverified", "errors", errors,
                "limit", "No all-target, full-library, publication, or toolchain adoption acceptance follows from this scan."));
        result.put("required_actions", registry.get("acceptance_contract"));
        return result;
    }

    private static void validateRun(Map<String, Object> record, String runId, Path path, List<String> errors) {
        if (!runId.equals(record.get("run_id")) || !zero(record.get("exit_code"))) errors.add("Failed/stale compiler or runtime run receipt: " + path);
    }

    private static String option(List<String> arguments, String name) {
        int at = arguments.indexOf(name);
        if (at < 0) return "";
        if (at + 1 >= arguments.size()) throw new IllegalArgumentException("Missing compiler option value: " + name);
        return arguments.get(at + 1);
    }

    static List<String> shellWords(String text) {
        List<String> words = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        char quote = 0;
        boolean started = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote == '\'') {
                if (c == quote) quote = 0; else word.append(c);
                continue;
            }
            if (c == '\\') {
                if (++i >= text.length()) throw new IllegalArgumentException("Trailing shell escape");
                char next = text.charAt(i);
                if (quote == '\"' && next != '\"' && next != '\\') word.append('\\');
                word.append(next);
                started = true;
            } else if (quote == '\"') {
                if (c == quote) quote = 0; else word.append(c);
            } else if (c == '\'' || c == '\"') {
                quote = c;
                started = true;
            } else if (Character.isWhitespace(c)) {
                if (started) { words.add(word.toString()); word.setLength(0); started = false; }
            } else {
                word.append(c);
                started = true;
            }
        }
        if (quote != 0) throw new IllegalArgumentException("Unclosed shell quote");
        if (started) words.add(word.toString());
        return words;
    }

    public static int run(String... args) throws IOException {
        Path root = null, generated = null, bridge = null, evidence = null, output = null, registry = null;
        List<String> required = new ArrayList<>();
        boolean requireAcceptance = false;
        for (int i = 0; i < args.length; i++) {
            String option = args[i];
            if (option.equals("--require-acceptance")) { requireAcceptance = true; continue; }
            if (i + 1 >= args.length) throw new IllegalArgumentException("Missing value for " + option);
            String value = args[++i];
            switch (option) {
                case "--root" -> root = Path.of(value).toAbsolutePath().normalize();
                case "--generated-root" -> generated = Path.of(value);
                case "--bridge-root" -> bridge = Path.of(value);
                case "--evidence" -> evidence = Path.of(value);
                case "--output" -> output = Path.of(value);
                case "--registry" -> registry = Path.of(value);
                case "--required-test" -> required.add(value);
                default -> throw new IllegalArgumentException("Unknown argument: " + option);
            }
        }
        if (root == null) throw new IllegalArgumentException("--root is required");
        if (registry == null) {
            registry = root.resolve("verification/swift-known-problems.json");
            if (!Files.isRegularFile(registry)) registry = Path.of("verification/swift-known-problems.json");
        }
        Map<String, Object> result = scan(root, generated, bridge, evidence, required, registry);
        if (output != null) Json.write(output, result); else System.out.println(Json.stringify(result));
        boolean bad = list(result.get("findings")).stream().map(SwiftProblemScanner::map).anyMatch(v -> "error".equals(v.get("severity")));
        return bad || requireAcceptance && "unverified".equals(map(result.get("acceptance")).get("status")) ? 1 : 0;
    }

    public static void main(String[] args) throws IOException { System.exit(run(args)); }
}
