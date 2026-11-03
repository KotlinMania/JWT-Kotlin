package io.github.kotlinmania.build;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Read-only Rust/Kotlin signature and parser-family comparison. */
public final class SourceSignatureDiff {
    private SourceSignatureDiff() {}
    private static final Pattern RUST = Pattern.compile("^(\\s*)((?:pub(?:\\([^)]+\\))?\\s+)?(?:async\\s+)?(?:unsafe\\s+)?(?:extern\\s+(?:\"[^\"]+\"\\s+)?)?fn\\s+[A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern KOTLIN = Pattern.compile("^(\\s*)((?:(?:public|private|internal|protected|override|open|final|inline|infix|operator|tailrec|suspend|external|abstract)\\s+)*fun(?:\\s*<[^>]+>)?\\s+`?[A-Za-z_][A-Za-z0-9_]*`?)");
    public record Signature(int line, String text, String name) {}
    private record Collected(String text, int end) {}
    static Collected collect(List<String> lines, int start, boolean kotlin) {
        int parens = 0, brackets = 0; StringBuilder result = new StringBuilder();
        for (int i = start; i < lines.size(); i++) {
            String line = lines.get(i);
            for (int j = 0; j < line.length(); j++) {
                char c = line.charAt(j); if (c == '(') parens++; else if (c == ')') parens--; else if (c == '[') brackets++; else if (c == ']') brackets--;
                else if (c == '{' && parens == 0 && brackets == 0) return new Collected(result.toString(), i);
                else if (c == ';' && parens == 0 && brackets == 0) return new Collected(result.append(c).toString(), i);
                else if (c == '=' && kotlin && parens == 0 && brackets == 0 && (j + 1 >= line.length() || line.charAt(j + 1) != '=') && (result.isEmpty() || "=!<>+-*/%".indexOf(result.charAt(result.length() - 1)) < 0)) return new Collected(result.toString().stripTrailing(), i);
                result.append(c);
            }
            result.append(' '); if (i - start > 50) break;
        }
        return new Collected(result.toString(), lines.size() - 1);
    }
    public static List<Signature> extract(String text, boolean kotlin) {
        List<String> lines = text.lines().toList(); List<Signature> result = new ArrayList<>(); Pattern start = kotlin ? KOTLIN : RUST;
        Pattern name = Pattern.compile(kotlin ? "\\bfun\\s+(?:<[^>]+>\\s+)?`?([A-Za-z_][A-Za-z0-9_]*)`?" : "\\bfn\\s+([A-Za-z_][A-Za-z0-9_]*)");
        for (int i = 0; i < lines.size();) { if (!start.matcher(lines.get(i)).find()) { i++; continue; } Collected signature = collect(lines, i, kotlin); String normalized = signature.text().replaceAll("\\s+", " ").strip(); var named = name.matcher(normalized); result.add(new Signature(i + 1, normalized, named.find() ? named.group(1) : "?")); i = signature.end() + 1; }
        return result;
    }
    static String snakeToCamel(String name) { String[] parts = name.split("_", -1); StringBuilder result = new StringBuilder(parts[0]); for (int i = 1; i < parts.length; i++) if (!parts[i].isEmpty()) result.append(parts[i].substring(0, 1).toUpperCase(Locale.ROOT)).append(parts[i].substring(1)); return result.toString(); }
    static String family(String name) {
        String base = name.replaceFirst("^_+", ""); if (base.isEmpty()) return null; if (base.contains("_")) base = snakeToCamel(base); var numbered = Pattern.compile("^([A-Za-z][A-Za-z]*?)(\\d+)$").matcher(base);
        if (numbered.find()) return numbered.group(1).substring(0, 1).toLowerCase(Locale.ROOT) + numbered.group(1).substring(1);
        if (Set.of("accepts", "simulateReduce", "tokenToInteger", "tokenToSymbol").contains(base)) return base; return base.startsWith("expectedTokens") ? "expectedTokens" : null;
    }
    static Map<String, Integer> counts(List<Signature> signatures) { Map<String, Integer> result = new TreeMap<>(); signatures.forEach(signature -> result.merge(signature.name(), 1, Integer::sum)); return result; }
    private static Set<String> aliases(Set<String> names) { Set<String> result = new TreeSet<>(); for (String name : names) { result.add(name); result.add(snakeToCamel(name)); result.add(snakeToCamel(name.replaceFirst("^_+", ""))); } return result; }
    static List<String> only(Map<String, Integer> own, Map<String, Integer> other, boolean rust) {
        Set<String> alternatives = aliases(other.keySet()); return own.keySet().stream().filter(name -> !alternatives.contains(name) && !alternatives.contains(snakeToCamel(name)) && (!rust || !alternatives.contains(snakeToCamel(name.replaceFirst("^_+", ""))))).sorted().toList();
    }
    private static List<String> countLines(Map<String, Integer> counts, List<String> names) { return names.stream().map(name -> "%5d  %s".formatted(counts.get(name), name)).toList(); }
    private static void write(Path root, String name, List<String> lines) throws IOException { Files.writeString(root.resolve(name), String.join("\n", lines) + (lines.isEmpty() ? "" : "\n")); }
    public static void report(Path rust, Path kotlin, Path output) throws IOException {
        List<Signature> rs = extract(Files.readString(rust), false), kt = extract(Files.readString(kotlin), true); Map<String, Integer> rsNames = counts(rs), ktNames = counts(kt);
        Files.createDirectories(output); write(output, "rust_sigs.txt", rs.stream().map(s -> "%6d  %s".formatted(s.line(), s.text())).toList()); write(output, "kotlin_sigs.txt", kt.stream().map(s -> "%6d  %s".formatted(s.line(), s.text())).toList());
        write(output, "rust_name_counts.txt", countLines(rsNames, new ArrayList<>(rsNames.keySet()))); write(output, "kotlin_name_counts.txt", countLines(ktNames, new ArrayList<>(ktNames.keySet())));
        List<String> rsOnly = only(rsNames, ktNames, true), ktOnly = only(ktNames, rsNames, false); write(output, "rust_only_names.txt", countLines(rsNames, rsOnly)); write(output, "kotlin_only_names.txt", countLines(ktNames, ktOnly));
        Map<String, Integer> rsFamilies = new TreeMap<>(), ktFamilies = new TreeMap<>(); int rsSingletons = 0, ktSingletons = 0;
        for (var row : rsNames.entrySet()) { String family = family(row.getKey()); if (family == null) rsSingletons += row.getValue(); else rsFamilies.merge(family, row.getValue(), Integer::sum); }
        for (var row : ktNames.entrySet()) { String family = family(row.getKey()); if (family == null) ktSingletons += row.getValue(); else ktFamilies.merge(family, row.getValue(), Integer::sum); }
        List<String> summary = new ArrayList<>(List.of("# sig_diff summary", "", "Rust   : " + rust, "Kotlin : " + kotlin, "", "Rust   fn  signatures: " + rs.size() + "  (" + rsNames.size() + " unique names)", "Kotlin fun signatures: " + kt.size() + "  (" + ktNames.size() + " unique names)", "", "## Family counts", "", "%-28s  Rust   Kotlin".formatted("family")));
        Set<String> families = new TreeSet<>(rsFamilies.keySet()); families.addAll(ktFamilies.keySet()); for (String family : families) summary.add("%-28s  %5d  %5d".formatted(family, rsFamilies.getOrDefault(family, 0), ktFamilies.getOrDefault(family, 0)));
        summary.add("%-28s  %5d  %5d".formatted("<one-offs>", rsSingletons, ktSingletons)); summary.addAll(List.of("", "## Divergence", "", "Rust-only names  : " + rsOnly.size(), "Kotlin-only names: " + ktOnly.size()));
        for (var pair : List.of(Map.entry("Rust-only:", countLines(rsNames, rsOnly)), Map.entry("Kotlin-only:", countLines(ktNames, ktOnly)))) if (!pair.getValue().isEmpty()) { summary.add(""); summary.add(pair.getKey()); pair.getValue().forEach(line -> summary.add("  " + line)); }
        write(output, "summary.txt", summary); System.out.println(String.join("\n", summary)); System.out.println("\nFull output written under: " + output);
    }
    public static int run(String... args) throws IOException {
        if (List.of(args).contains("--help") || List.of(args).contains("-h")) { System.out.println("Usage: SourceSignatureDiff rust-file kotlin-file [--out directory] [--root repository]"); return 0; }
        Path root = Path.of(".").toAbsolutePath().normalize(); String output = null; List<String> paths = new ArrayList<>();
        for (int i = 0; i < args.length; i++) if (args[i].equals("--out") || args[i].equals("--root")) { String key = args[i]; if (++i == args.length) throw new IllegalArgumentException("Missing value: " + key); if (key.equals("--root")) root = Path.of(args[i]).toAbsolutePath().normalize(); else output = args[i]; } else { if (args[i].startsWith("--")) throw new IllegalArgumentException("Unknown option: " + args[i]); paths.add(args[i]); }
        if (paths.size() != 2) throw new IllegalArgumentException("Usage: SourceSignatureDiff rust-file kotlin-file [--out directory] [--root repository]"); Path rust = root.resolve(paths.get(0)).normalize(), kotlin = root.resolve(paths.get(1)).normalize();
        if (!Files.isRegularFile(rust) || !Files.isRegularFile(kotlin)) { System.err.println("error: Rust or Kotlin input file not found"); return 2; }
        Path out = output == null ? root.resolve("build/sig-diff/" + kotlin.getFileName().toString().replaceFirst("\\.[^.]+$", "")) : root.resolve(output).normalize();
        if (!out.startsWith(root) || out.equals(root)) throw new IllegalArgumentException("Signature reports must stay inside the owning repository"); for (Path path = out; path != null && path.startsWith(root); path = path.getParent()) if (Files.isSymbolicLink(path)) throw new IllegalArgumentException("Output cannot traverse symbolic links");
        report(rust, kotlin, out); return 0;
    }
    public static void main(String[] args) throws IOException { System.exit(run(args)); }
}
