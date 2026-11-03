package io.github.kotlinmania.build;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Structural report for the Kotlin-to-C++ codebase layout; it never rewrites sources. */
public final class CodebaseLint {
    private CodebaseLint() {}
    public record Analysis(Map<String, List<Object>> details, Map<String, Integer> counts) {}
    static String read(Path path) throws IOException { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.IGNORE).onUnmappableCharacter(CodingErrorAction.IGNORE).decode(ByteBuffer.wrap(Files.readAllBytes(path))).toString(); }
    static List<Path> files(Path root, String extension) throws IOException { if (!Files.isDirectory(root)) return List.of(); try (var paths = Files.walk(root)) { return paths.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(extension)).sorted().toList(); } }
    static List<Map<String, Object>> todos(Path path, String text) {
        List<Map<String, Object>> result = new ArrayList<>(); List<String> lines = text.lines().toList();
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).contains("TODO")) { var match = Pattern.compile("//\\s*TODO[:(]?\\s*(.+)").matcher(lines.get(i)); if (match.find()) { String message = match.group(1).strip(); result.add(Map.of("file", path.toString(), "line", i + 1, "text", message.substring(0, Math.min(message.length(), 100)))); } }
        return result;
    }
    static Map<String, Object> namespace(Path cppRoot, Path path, String text) {
        List<String> segments = new ArrayList<>(); Path parent = cppRoot.relativize(path).getParent(); if (parent != null) for (Path part : parent) if (!Set.of("common", "native", "concurrent", "src").contains(part.toString())) segments.add(part.toString()); String expected = String.join(".", segments);
        if (!expected.startsWith("kotlinx")) return null; String declared = null; List<String> stack = new ArrayList<>();
        for (String line : text.split("\n", -1)) { var match = Pattern.compile("^\\s*namespace\\s+([\\w:]+)\\s*\\{").matcher(line); if (match.find() && match.group(1).contains("::")) { declared = match.group(1).replace("::", "."); break; }
            match = Pattern.compile("^\\s*namespace\\s+(\\w+)\\s*\\{").matcher(line); if (match.find() && !Set.of("std", "detail", "anonymous", "").contains(match.group(1))) { stack.add(match.group(1)); if (stack.getFirst().equals("kotlinx")) declared = String.join(".", stack); }
        }
        return declared != null && !declared.equals(expected) ? Map.of("file", path.toString(), "expected", expected, "declared", declared) : null;
    }
    static List<Map<String, Object>> includes(Path path, String cpp, String kotlin) {
        Set<String> imports = new TreeSet<>(), includes = new TreeSet<>(); Pattern.compile("^import\\s+(kotlinx\\.coroutines[\\w.]*)", Pattern.MULTILINE).matcher(kotlin).results().forEach(m -> imports.add(m.group(1)));
        Pattern.compile("#include\\s*\"(kotlinx/coroutines[^\"]*)\"").matcher(cpp).results().forEach(m -> includes.add(m.group(1).replace('/', '.').replace(".hpp", "").replace(".h", "")));
        List<Map<String, Object>> result = new ArrayList<>(); for (String imported : imports) { String base = imported.replaceFirst("\\.\\*$", "").replaceFirst("\\.[A-Z][a-zA-Z]*$", ""); if (includes.stream().noneMatch(value -> value.startsWith(base))) result.add(Map.of("cpp_file", path.toString(), "missing_import", imported)); } return result;
    }
    static boolean emptyStub(String text) { return text.replaceAll("(?m)//.*$", "").replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("#include\\s*[<\"][^>\"]*[>\"]", "").replaceAll("namespace\\s+\\w+\\s*\\{", "").replaceAll("\\}\\s*//\\s*namespace", "").replaceAll("#pragma\\s+\\w+", "").strip().length() < 50; }
    public static Analysis analyze(Path base, boolean summary) throws IOException {
        Path cpp = base.resolve("src"), kotlin = base.resolve("tmp/kotlinx.coroutines"); Map<String, List<Object>> details = new LinkedHashMap<>(); for (String key : List.of("todos", "namespace_issues", "include_issues", "missing_files", "extra_files", "empty_stubs")) details.put(key, new ArrayList<>());
        Map<String, Integer> counts = new LinkedHashMap<>(Map.of("todos", 0, "namespace_issues", 0, "missing_files", 0, "empty_stubs", 0));
        for (String extension : List.of(".cpp", ".hpp")) for (Path file : files(cpp, extension)) {
            String text = read(file); List<Map<String, Object>> todos = todos(file, text); counts.merge("todos", todos.size(), Integer::sum); if (!summary) details.get("todos").addAll(todos);
            if (!file.toString().contains("test") && !file.toString().contains("tools")) { Map<String, Object> issue = namespace(cpp, file, text); if (issue != null) { counts.merge("namespace_issues", 1, Integer::sum); if (!summary) details.get("namespace_issues").add(issue); } }
            if (extension.equals(".cpp") && emptyStub(text)) { counts.merge("empty_stubs", 1, Integer::sum); if (!summary) details.get("empty_stubs").add(file.toString()); }
        }
        for (Path file : files(kotlin, ".kt")) {
            String relative = kotlin.relativize(file).toString(); if (relative.toLowerCase(java.util.Locale.ROOT).contains("test") && !relative.contains("kotlinx-coroutines-test")) continue;
            var match = Pattern.compile("^package\\s+([\\w.]+)", Pattern.MULTILINE).matcher(read(file)); if (!match.find()) continue; String pkg = match.group(1), name = file.getFileName().toString().replaceFirst("\\.kt$", "").replace(".common", "").replace(".native", "").replace(".concurrent", "");
            Path expected = cpp.resolve(pkg.replace('.', '/') + "/" + name + ".cpp"), header = expected.resolveSibling(name + ".hpp");
            if (!Files.exists(expected) && !Files.exists(header)) { counts.merge("missing_files", 1, Integer::sum); if (!summary) details.get("missing_files").add(Map.of("kotlin", file.toString(), "package", pkg, "expected", expected.toString())); }
        }
        return new Analysis(details, counts);
    }
    public static int run(String... args) throws IOException {
        Path root = Path.of("tools"); boolean summary = false, json = false;
        for (int i = 0; i < args.length; i++) switch (args[i]) {
            case "--summary" -> summary = true; case "--json" -> json = true; case "--fix-preview" -> { /* Original option is a reporting-only preview. */ }
            case "--root" -> { if (++i == args.length) throw new IllegalArgumentException("Missing --root value"); root = Path.of(args[i]); }
            case "--help", "-h" -> { System.out.println("Usage: CodebaseLint [--root tools-directory] [--summary] [--json] [--fix-preview]"); return 0; }
            default -> throw new IllegalArgumentException("Unrecognized option: " + args[i]);
        }
        System.err.println("Scanning codebase..."); Analysis result = analyze(root, summary);
        if (json) System.out.println(Json.stringify(result.details()));
        else {
            System.out.println("KOTLINX.COROUTINES C++ PORT - LINT REPORT"); for (var count : result.counts().entrySet()) System.out.println("  " + count.getKey() + ": " + count.getValue());
            if (summary) System.out.println("Run without --summary for full details."); else for (String key : List.of("namespace_issues", "missing_files", "empty_stubs")) if (!result.details().get(key).isEmpty()) { System.out.println("\n" + key + " (first 20):"); result.details().get(key).stream().limit(20).forEach(item -> System.out.println(Json.stringify(item))); if (result.details().get(key).size() > 20) System.out.println("... and " + (result.details().get(key).size() - 20) + " more"); }
        }
        return 0;
    }
    public static void main(String[] args) throws IOException { System.exit(run(args)); }
}
