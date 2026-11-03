package io.github.kotlinmania.build;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Deterministic port rules for LALRPOP; high-severity findings fail the command. */
public final class LalrpopPortLint {
    private LalrpopPortLint() {}
    public record Finding(String file, int line, int column, String rule, String severity, String message) {
        public String render() { return "%s:%d:%d  %-26s %-6s %s".formatted(file, line, column, rule, severity, message); }
    }
    private static final Pattern COLLAPSED = Pattern.compile("^\\s*[A-Za-z_][A-Za-z0-9_]*\\s*\\([^)]*\\)\\s*//.*?\\b([A-Za-z_][A-Za-z0-9_]*\\s*\\(\\s*[^)\\s][^)]*\\))");
    private static final Pattern OVERRIDE = Pattern.compile("^\\s*(?:(?:internal|private|public|protected)\\s+)*override\\s+fun\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");
    private static final Pattern SEALED = Pattern.compile("^\\s*(?:(?:internal|private|public|protected)\\s+)?sealed\\s+(?:class|interface)\\s+([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern DATA = Pattern.compile("^\\s*data\\s+(?:class|object)\\s+([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern TOSTRING = Pattern.compile("\\boverride\\s+fun\\s+toString\\s*\\(");
    private static final Pattern CITATION = Pattern.compile("\\.rs:\\d+|Mirrors\\s+upstream|Renamed\\s+from|[Tt]he\\s+Kotlin\\s+port|Direct\\s+port\\s+of\\s+upstream|upstream\\s+Rust\\s+(?:uses|has|wraps|emits)");
    private static final Pattern SNAKE = Pattern.compile("^\\s*(?:(?:internal|private|public|protected|override|open|inline|suspend)\\s+)*(?:fun|val|var)\\s+([a-z_][a-z0-9_]*)\\b");
    private static final Set<String> CONTROL = Set.of("if", "when", "for", "while", "do", "try", "fun", "val", "var", "return");
    private static int column(String text, int offset) { return text.codePointCount(0, offset) + 1; }
    static String parentheses(List<String> lines, int row, int start) {
        if (row >= lines.size() || start >= lines.get(row).length() || lines.get(row).charAt(start) != '(') return null;
        int depth = 0; StringBuilder result = new StringBuilder();
        for (int i = row; i < lines.size(); i++) {
            for (int j = i == row ? start : 0; j < lines.get(i).length(); j++) { char c = lines.get(i).charAt(j); if (c == '(') { depth++; if (depth > 1) result.append(c); } else if (c == ')') { if (--depth == 0) return result.toString(); result.append(c); } else result.append(c); }
            result.append('\n');
        }
        return null;
    }
    static int arguments(String text) {
        text = text.strip(); if (text.isEmpty()) return 0; int parens = 0, brackets = 0, angles = 0, result = 1;
        for (char c : text.toCharArray()) { if (c == '(') parens++; else if (c == ')') parens--; else if (c == '[') brackets++; else if (c == ']') brackets--; else if (c == '<') angles++; else if (c == '>') angles--; else if (c == ',' && parens == 0 && brackets == 0 && angles == 0) result++; }
        return result - (text.endsWith(",") ? 1 : 0);
    }
    static String methodBody(List<String> lines, int start) {
        int parens = 0, angles = 0, bodyRow = -1, bodyColumn = -1; char terminator = 0;
        for (int i = start; i < lines.size() && bodyRow < 0; i++) for (int j = 0; j < lines.get(i).length(); j++) {
            char c = lines.get(i).charAt(j); if (c == '(') parens++; else if (c == ')') parens--; else if (c == '<') angles++; else if (c == '>') angles--;
            else if (parens == 0 && angles == 0 && (c == '{' || c == '=' && (j + 1 >= lines.get(i).length() || lines.get(i).charAt(j + 1) != '='))) { bodyRow = i; bodyColumn = j + 1; terminator = c; break; }
        }
        if (bodyRow < 0) return ""; if (terminator == '=') return lines.get(bodyRow).substring(bodyColumn);
        int depth = 1; StringBuilder result = new StringBuilder();
        for (int i = bodyRow; i < lines.size(); i++) {
            for (int j = i == bodyRow ? bodyColumn : 0; j < lines.get(i).length(); j++) { char c = lines.get(i).charAt(j); if (c == '{') depth++; if (c == '}') { depth--; if (depth == 0) return result.toString(); } result.append(c); }
            result.append('\n');
        }
        return result.toString();
    }
    static int blockEnd(List<String> lines, int start) {
        int depth = 0; boolean opened = false;
        for (int i = start; i < lines.size(); i++) for (char c : lines.get(i).toCharArray()) { if (c == '{') { depth++; opened = true; } else if (c == '}' && --depth == 0 && opened) return i; }
        return lines.size();
    }
    public static List<Finding> lint(String path, String text) {
        List<String> lines = text.lines().toList(); List<Finding> result = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            var match = COLLAPSED.matcher(lines.get(i)); if (match.find() && !CONTROL.contains(match.group(1).substring(0, match.group(1).indexOf('(')).strip())) result.add(new Finding(path, i + 1, column(lines.get(i), match.start(1)), "collapsed-emit-comment", "HIGH", "call `" + match.group(1) + "` is inside a trailing comment; was it supposed to be a real call?"));
        }
        for (int i = 0; i < lines.size(); i++) {
            var signature = OVERRIDE.matcher(lines.get(i)); if (!signature.find()) continue; String name = signature.group(1), params = parentheses(lines, i, lines.get(i).indexOf('(', signature.end(1))); if (params == null) continue;
            String body = methodBody(lines, i); var call = Pattern.compile("(?<![A-Za-z0-9_.])\\b" + Pattern.quote(name) + "\\s*\\(").matcher(body); if (!call.find()) continue;
            List<String> bodyLines = body.lines().toList(); int offset = call.end() - 1, row = 0; while (row < bodyLines.size() && offset > bodyLines.get(row).length()) { offset -= bodyLines.get(row).length() + 1; row++; }
            String args = parentheses(bodyLines, row, offset); if (args != null && arguments(args) == arguments(params)) result.add(new Finding(path, i + 1, column(lines.get(i), signature.start(1)), "self-recursive-method", "HIGH", "override `" + name + "` calls `" + name + "(...)` unqualified with the same " + arguments(params) + "-arg shape — will recurse into itself"));
        }
        for (int i = 0; i < lines.size();) {
            var sealed = SEALED.matcher(lines.get(i)); if (!sealed.find()) { i++; continue; } int end = Math.min(lines.size(), blockEnd(lines, i) + 1); List<String> block = lines.subList(i, end);
            if (TOSTRING.matcher(String.join("\n", block)).find()) for (int j = 0; j < block.size(); j++) {
                var data = DATA.matcher(block.get(j)); if (!data.find()) continue; int subEnd = block.get(j).contains("{") ? Math.min(block.size(), blockEnd(block, j) + 1) : j + 1;
                if (!TOSTRING.matcher(String.join("\n", block.subList(j, subEnd))).find()) result.add(new Finding(path, i + j + 1, column(block.get(j), data.start(1)), "sealed-tostring-shadow", "MEDIUM", "`" + data.group(1) + "` (data subclass of sealed `" + sealed.group(1) + "` whose toString is overridden) has no toString override; auto-generated `Variant(prop=…)` will shadow the parent"));
            }
            i = end;
        }
        String[][] simple = {{"@Suppress\\b", "suppress-annotation", "HIGH", "`@Suppress` is forbidden — fix the underlying warning"}, {"^\\s*import\\s+(?:kotlin\\.jvm\\.|java\\.|javax\\.)", "jvm-import", "HIGH", "JVM-only import in a KMP project"}};
        for (String[] rule : simple) for (int i = 0; i < lines.size(); i++) { var match = Pattern.compile(rule[0]).matcher(lines.get(i)); if (match.find()) result.add(new Finding(path, i + 1, rule[1].equals("jvm-import") ? 1 : column(lines.get(i), match.start()), rule[1], rule[2], rule[3])); }
        for (int i = 0; i < lines.size(); i++) { var match = Pattern.compile("\\b(?:TODO|FIXME|XXX)\\b").matcher(lines.get(i)); if (match.find()) result.add(new Finding(path, i + 1, column(lines.get(i), match.start()), "todo-marker", "HIGH", "`" + match.group() + "` marker — repository policy forbids TODO/FIXME/XXX")); }
        for (int i = 0; i < lines.size(); i++) { String line = lines.get(i), trimmed = line.stripLeading(); if (!trimmed.startsWith("//") && !trimmed.startsWith("*") && !trimmed.startsWith("/*")) continue; var match = CITATION.matcher(line); if (match.find()) result.add(new Finding(path, i + 1, column(line, match.start()), "rust-source-citation", "LOW", "porter note `" + match.group().strip() + "` — translate Rust doc word-for-word; do not add Rust-vs-Kotlin commentary")); }
        for (int i = 0; i < lines.size(); i++) { var match = SNAKE.matcher(lines.get(i)); if (!match.find()) continue; String name = match.group(1); if (!name.contains("_") || name.endsWith("_") && !name.substring(0, name.length() - 1).contains("_")) continue; result.add(new Finding(path, i + 1, column(lines.get(i), match.start(1)), "snake-case-identifier", "MEDIUM", "identifier `" + name + "` uses snake_case — Kotlin convention is camelCase")); }
        return result;
    }
    public static int run(String... args) throws IOException {
        if (args.length > 1) throw new IllegalArgumentException("Usage: LalrpopPortLint [source-root]"); Path root = Path.of(args.length == 0 ? "src" : args[0]); if (!Files.isDirectory(root)) { System.err.println("port_lint: not a directory: " + root); return 2; }
        List<Path> files = new ArrayList<>(); Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attributes) { return !path.equals(root) && Set.of("build", ".gradle", "tmp", "node_modules").contains(path.getFileName().toString()) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE; }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) { if (file.toString().endsWith(".kt")) files.add(file); return FileVisitResult.CONTINUE; }
        });
        List<Finding> findings = new ArrayList<>(); for (Path file : files.stream().sorted().toList()) findings.addAll(lint(file.toString(), new String(Files.readAllBytes(file), StandardCharsets.UTF_8)));
        for (String severity : List.of("HIGH", "MEDIUM", "LOW")) findings.stream().filter(f -> f.severity().equals(severity)).forEach(f -> System.out.println(f.render()));
        long high = findings.stream().filter(f -> f.severity().equals("HIGH")).count(), medium = findings.stream().filter(f -> f.severity().equals("MEDIUM")).count(), low = findings.size() - high - medium;
        System.out.println("\nport_lint summary: " + high + " HIGH, " + medium + " MEDIUM, " + low + " LOW"); return high > 0 ? 1 : 0;
    }
    public static void main(String[] args) throws IOException { System.exit(run(args)); }
}
