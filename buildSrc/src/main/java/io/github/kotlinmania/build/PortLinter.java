package io.github.kotlinmania.build;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Shared Codex/Ratatui port inventory, definition matching, diagnostics and compiler checks. */
public final class PortLinter {
    private PortLinter() {}
    private static final Set<String> SKIP = Set.of("target", "build", ".git", ".gradle", ".idea", "node_modules", "dist", ".next");
    private static final Pattern SOURCE = Pattern.compile("//\\s*port-lint:\\s*source\\s+(.+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern IGNORE = Pattern.compile("//\\s*port-lint:\\s*ignore(?:-duplicate)?", Pattern.CASE_INSENSITIVE);
    public record Definition(String name, String kind, int line, String context, String normalized) {}
    public record Location(Path path, int line, String context) {}
    public record NameMatch(String name, double score, String type, Location location) {}
    private record Rule(Pattern pattern, String kind) {}
    private static Rule rule(String pattern, String kind) { return new Rule(Pattern.compile(pattern, Pattern.UNICODE_CHARACTER_CLASS), kind); }
    static String read(Path path) throws IOException { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.IGNORE).onUnmappableCharacter(CodingErrorAction.IGNORE).decode(ByteBuffer.wrap(Files.readAllBytes(path))).toString(); }
    static String stem(String name) { int dot = name.lastIndexOf('.'); return dot > 0 ? name.substring(0, dot) : name; }
    static String normalize(String name) { return stem(name).replace("_", "").replace("-", "").toLowerCase(Locale.ROOT); }
    static String normalizeSimilarity(String name) { return name.replaceAll("(.)([A-Z][a-z]+)", "$1_$2").replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT).replace('-', '_').replaceAll("_+", "_").replaceAll("^_|_$", ""); }
    static String capitalize(String value) { return value.isEmpty() ? value : value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1).toLowerCase(Locale.ROOT); }
    static String pascal(String name) { StringBuilder result = new StringBuilder(); for (String part : name.split("_", -1)) result.append(capitalize(part)); return result.toString(); }
    static String camel(String name) { String[] parts = name.split("_", -1); StringBuilder result = new StringBuilder(parts[0].toLowerCase(Locale.ROOT)); for (int i = 1; i < parts.length; i++) result.append(capitalize(parts[i])); return result.toString(); }
    static String snake(String name) { return name.replaceAll("([A-Z])", "_$1").toLowerCase(Locale.ROOT).replaceFirst("^_+", ""); }
    static boolean suppressed(List<String> lines, int line) {
        int index = line - 1; if (index < lines.size() && IGNORE.matcher(lines.get(index)).find()) return true;
        for (int i = index - 1; i >= 0; i--) { String previous = lines.get(i).strip(); if (IGNORE.matcher(previous).find()) return true; if (previous.startsWith("@")) continue; break; } return false;
    }
    static String sourceAnnotation(String text) { List<String> lines = text.lines().limit(51).toList(); for (String line : lines) { var match = SOURCE.matcher(line); if (match.find()) return match.group(1).strip(); } return null; }
    static List<Path> files(Path root, Set<String> extensions, boolean treeSkips, boolean rustGlobal) throws IOException {
        List<Path> result = new ArrayList<>(); if (!Files.isDirectory(root)) return result;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) { return !directory.equals(root) && treeSkips && SKIP.contains(directory.getFileName().toString()) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE; }
            @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) {
                String full = path.toString(), relative = root.relativize(path).toString(); boolean skip = !treeSkips && (rustGlobal ? relative.contains("target") || relative.contains(".git") : relative.contains("build") || relative.contains(".gradle") || relative.contains(".git"));
                if (!skip && extensions.stream().anyMatch(full::endsWith)) result.add(path); return FileVisitResult.CONTINUE;
            }
        }); return result.stream().sorted().toList();
    }
    private static final List<Rule> RUST_TYPES = List.of(rule("^\\s*pub(?:\\([^)]+\\))?\\s+enum\\s+(\\w+)", "enum"), rule("^\\s*pub(?:\\([^)]+\\))?\\s+struct\\s+(\\w+)", "struct"), rule("^\\s*pub(?:\\([^)]+\\))?\\s+trait\\s+(\\w+)", "trait"), rule("^\\s*enum\\s+(\\w+)", "enum"), rule("^\\s*struct\\s+(\\w+)", "struct"), rule("^\\s*trait\\s+(\\w+)", "trait"));
    private static final List<Rule> KOTLIN_TYPES = List.of(rule("^\\s*(sealed\\s+)?class\\s+(\\w+)", "class"), rule("^\\s*enum\\s+class\\s+(\\w+)", "enum"), rule("^\\s*data\\s+class\\s+(\\w+)", "data class"), rule("^\\s*object\\s+(\\w+)", "object"), rule("^\\s*interface\\s+(\\w+)", "interface"));
    private static final Set<String> FALSE_RUST = Set.of("self", "Self", "super", "crate", "pub", "let", "mut", "if", "else", "match", "for", "while", "loop", "return", "break", "continue", "f", "e", "v", "x", "y", "i", "n", "s", "ok", "err", "Ok", "Err", "Some", "None");
    private static final Set<String> DERIVES = Set.of("Debug", "Clone", "Copy", "Default", "PartialEq", "Eq", "PartialOrd", "Ord", "Hash", "Serialize", "Deserialize", "Display", "JsonSchema", "TS", "EnumIter", "Send", "Sync", "Sized", "Unpin", "From", "Into", "TryFrom", "TryInto", "AsRef", "AsMut", "Deref", "DerefMut", "Drop", "Error", "FromStr");
    private static final Set<String> TRAIT_METHODS = Set.of("fmt", "serialize", "deserialize", "schema_name", "json_schema", "clone", "default", "eq", "ne", "partial_cmp", "cmp", "hash", "as_ref", "as_mut", "deref", "deref_mut", "drop", "from", "into", "try_from", "try_into", "from_str", "formatter");
    private static final Set<String> FALSE_KOTLIN = Set.of("it", "this", "super", "if", "else", "when", "for", "while", "return", "break", "continue", "is", "as", "in", "val", "var", "fun", "class", "object");
    private static final Pattern RUST_HELPER = Pattern.compile("^should_serialize_|^is_default$|^is_empty$|_placeholder$|Serde$|^make_.*_formatter$|^convert_|_with_formatter$");
    static List<Definition> rustDefinitions(String text) {
        List<Rule> rules = new ArrayList<>(RUST_TYPES.subList(0, 3)); rules.addAll(List.of(rule("^\\s*pub(?:\\([^)]+\\))?\\s+fn\\s+(\\w+)", "fn"), rule("^\\s*pub(?:\\([^)]+\\))?\\s+const\\s+(\\w+)", "const"), rule("^\\s*pub(?:\\([^)]+\\))?\\s+type\\s+(\\w+)", "type"))); rules.addAll(RUST_TYPES.subList(3, 6)); rules.addAll(List.of(rule("^\\s*fn\\s+(\\w+)", "fn"), rule("^\\s*pub\\s+(\\w+)\\s*:", "field"), rule("^\\s*(\\w+)\\s*[,\\{]", "variant")));
        List<Definition> result = new ArrayList<>(); List<String> lines = text.lines().toList(); boolean derive = false, testModule = false;
        for (int i = 0; i < lines.size(); i++) { String stripped = lines.get(i).strip(); if (stripped.startsWith("#[derive(")) derive = true; if (derive) { if (stripped.contains(")]")) derive = false; continue; } if (stripped.contains("#[cfg(test)]")) { testModule = true; continue; } if (testModule || stripped.startsWith("#[")) continue;
            for (Rule rule : rules) { var match = rule.pattern().matcher(lines.get(i)); if (!match.find()) continue; String name = match.group(1); if (FALSE_RUST.contains(name) || DERIVES.contains(name) || TRAIT_METHODS.contains(name) || RUST_HELPER.matcher(name).find()) continue; result.add(new Definition(name, rule.kind(), i + 1, stripped, normalize(name))); break; }
        } return result;
    }
    static List<Definition> kotlinDefinitions(String text) {
        List<Rule> rules = List.of(rule("^\\s*sealed\\s+class\\s+(\\w+)", "sealed class"), rule("^\\s*data\\s+class\\s+(\\w+)", "data class"), rule("^\\s*enum\\s+class\\s+(\\w+)", "enum class"), rule("^\\s*class\\s+(\\w+)", "class"), rule("^\\s*object\\s+(\\w+)", "object"), rule("^\\s*interface\\s+(\\w+)", "interface"), rule("^\\s*typealias\\s+(\\w+)", "typealias"), rule("^\\s*fun\\s+(?:<[^>]+>\\s+)?(\\w+)", "fun"), rule("^\\s*(?:@\\w+(?:\\([^)]*\\))?\\s+)*(?:val|var)\\s+(\\w+)\\s*[=:]", "property"), rule("^\\s*const\\s+val\\s+(\\w+)", "const"), rule("^\\s*(?:@\\w+(?:\\([^)]*\\))?\\s+)*([A-Z][A-Za-z0-9]*)\\s*[,\\(;]", "entry"), rule("^\\s*(?:@\\w+(?:\\([^)]*\\))?\\s+)+([A-Z][A-Za-z0-9]*)\\s*$", "entry"), rule("^\\s*(\\w+)\\s*[,\\(]", "entry"), rule("^\\s*([A-Z][A-Za-z0-9]*)\\s*;", "entry"), rule("^\\s*([A-Z][A-Za-z0-9]*)\\s*$", "entry"), rule("^\\s*(?:data\\s+)?class\\s+(\\w+)\\s*[:\\(]", "variant"), rule("^\\s*(?:@\\w+(?:\\([^)]*\\))?\\s+)*object\\s+(\\w+)\\s*:", "variant"));
        List<Definition> result = new ArrayList<>(); Set<String> seen = new LinkedHashSet<>(); List<String> lines = text.lines().toList();
        for (int i = 0; i < lines.size(); i++) { if (suppressed(lines, i + 1)) continue; String line = lines.get(i);
            for (Rule rule : rules) { var match = rule.pattern().matcher(line); if (!match.find()) continue; String name = match.group(1); if (FALSE_KOTLIN.contains(name)) continue; if (seen.add(normalize(name))) result.add(new Definition(name, rule.kind(), i + 1, line.strip(), normalize(name))); break; }
            var properties = Pattern.compile("(?:@\\w+(?:\\([^)]*\\))?\\s+)*(?:val|var)\\s+(\\w+)\\s*:", Pattern.UNICODE_CHARACTER_CLASS).matcher(line);
            while (properties.find()) { String name = properties.group(1); if (!FALSE_KOTLIN.contains(name) && seen.add(normalize(name))) result.add(new Definition(name, "property", i + 1, clip(line.strip(), 80), normalize(name))); }
        } return result;
    }
    private static String clip(String text, int max) { return text.length() > max ? text.substring(0, max) + "..." : text; }
    static Map<String, Integer> ngrams(String text) { text = "$$" + text.toLowerCase(Locale.ROOT) + "$$"; Map<String, Integer> result = new LinkedHashMap<>(); for (int i = 0; i + 3 <= text.length(); i++) result.merge(text.substring(i, i + 3), 1, Integer::sum); return result; }
    static double cosine(Map<String, Integer> a, Map<String, Integer> b) { double dot = 0, aa = 0, bb = 0; for (var value : a.entrySet()) { dot += value.getValue() * b.getOrDefault(value.getKey(), 0); aa += value.getValue() * value.getValue(); } for (int value : b.values()) bb += value * value; return aa == 0 || bb == 0 ? 0 : dot / Math.sqrt(aa * bb); }
    static double jaccard(Set<String> a, Set<String> b) { if (a.isEmpty() || b.isEmpty()) return 0; Set<String> union = new LinkedHashSet<>(a); union.addAll(b); return (double) a.stream().filter(b::contains).count() / union.size(); }
    private static Set<String> tokens(String text) { Set<String> result = new LinkedHashSet<>(List.of(normalizeSimilarity(text).split("_", -1))); result.remove(""); return result; }
    public static final class SimilarityIndex {
        final Map<String, List<Location>> names = new LinkedHashMap<>();
        public void add(String name, Location location) { names.computeIfAbsent(name, key -> new ArrayList<>()).add(location); }
        public List<NameMatch> find(String query, double threshold, int limit) {
            List<NameMatch> result = new ArrayList<>(); String norm = normalizeSimilarity(query);
            for (var entry : names.entrySet()) { String name = entry.getKey(), other = normalizeSimilarity(name), type; double score;
                if (name.equalsIgnoreCase(query)) { score = 1; type = "exact"; } else if (norm.equals(other)) { score = 0.99; type = "normalized"; }
                else { double token = jaccard(tokens(query), tokens(name)), gram = cosine(ngrams(norm), ngrams(other)); score = 0.4 * token + 0.6 * gram; if (score < threshold) continue; type = token > gram ? "token" : "ngram"; }
                for (Location location : entry.getValue()) result.add(new NameMatch(name, score, type, location));
            }
            result.sort(Comparator.comparingDouble(NameMatch::score).reversed()); return result.stream().limit(limit).toList();
        }
    }
    static String namespace(Path root, Path file, String language) {
        List<String> parts = new ArrayList<>(); for (Path part : root.relativize(file)) parts.add(part.toString());
        if (language.equals("rust")) { int src = parts.indexOf("src"); String crate = src > 0 ? parts.get(src - 1) : null; if (src >= 0) parts = new ArrayList<>(parts.subList(src + 1, parts.size())); if (!parts.isEmpty()) parts.set(parts.size() - 1, stem(parts.getLast())); if (!parts.isEmpty() && Set.of("mod", "lib").contains(parts.getLast())) parts.removeLast(); if (crate != null) parts.addFirst(crate); }
        else if (language.equals("kotlin")) { int kotlin = parts.indexOf("kotlin"); if (kotlin >= 0) parts = new ArrayList<>(parts.subList(kotlin + 1, parts.size())); for (int i = 0; i < parts.size(); i++) if (Set.of("core", "protocol", "exec", "mcp", "utils", "client", "platform").contains(parts.get(i).toLowerCase(Locale.ROOT))) { parts = new ArrayList<>(parts.subList(i, parts.size())); break; } if (!parts.isEmpty()) parts.set(parts.size() - 1, stem(parts.getLast())); }
        else { int src = parts.indexOf("src"); if (src >= 0) { String pkg = src > 0 && !Set.of("packages", "apps", "libs").contains(parts.get(src - 1)) ? parts.get(src - 1) : null; parts = new ArrayList<>(parts.subList(src + 1, parts.size())); if (pkg != null) parts.addFirst(pkg); } if (!parts.isEmpty()) parts.set(parts.size() - 1, stem(parts.getLast())); if (!parts.isEmpty() && parts.getLast().equals("index")) parts.removeLast(); }
        return String.join(".", parts.stream().map(PortLinter::normalize).toList());
    }
    static final class Node {
        final Path path; final String name, namespace, annotation; Node match; String method;
        Node(Path root, Path path, String language) throws IOException { this.path = path; this.name = normalize(path.getFileName().toString()); this.namespace = namespace(root, path, language); this.annotation = language.equals("rust") ? null : sourceAnnotation(read(path)); }
    }
    static final class Inventory {
        final Path sourceRoot, targetRoot; final String sourceLanguage, targetLanguage; final List<Node> source = new ArrayList<>(), target = new ArrayList<>();
        Inventory(Path sourceRoot, Path targetRoot, String sourceLanguage, String targetLanguage) throws IOException {
            this.sourceRoot = sourceRoot; this.targetRoot = targetRoot; this.sourceLanguage = sourceLanguage; this.targetLanguage = targetLanguage;
            for (Path path : files(sourceRoot, extensions(sourceLanguage), true, false)) source.add(new Node(sourceRoot, path, sourceLanguage)); for (Path path : files(targetRoot, extensions(targetLanguage), true, false)) target.add(new Node(targetRoot, path, targetLanguage));
            Map<String, Node> sourcePaths = new LinkedHashMap<>(); for (Node node : source) { String relative = sourceRoot.relativize(node.path).toString().replace('\\', '/'); sourcePaths.put(relative, node); String[] parts = relative.split("/"); for (int i = 1; i < parts.length; i++) sourcePaths.putIfAbsent(String.join("/", java.util.Arrays.copyOfRange(parts, i, parts.length)), node); }
            for (Node node : target) if (node.annotation != null) { Node original = sourcePaths.get(node.annotation); if (original == null) System.out.println("WARNING: Invalid source annotation in " + node.path + ": '" + node.annotation + "' not found in source tree"); else match(original, node, "source annotation"); }
            for (Node node : source) if (node.match == null) { Node byNamespace = target.stream().filter(t -> t.match == null && t.namespace.equals(node.namespace)).findFirst().orElse(null); if (byNamespace != null) match(node, byNamespace, "namespace"); else { Node byName = target.stream().filter(t -> t.match == null && t.name.equals(node.name)).findFirst().orElse(null); if (byName != null) match(node, byName, "filename"); } }
        }
        private static void match(Node source, Node target, String method) { source.match = target; target.match = source; source.method = method; target.method = method; }
    }
    private static Set<String> extensions(String language) { return switch (language) { case "rust" -> Set.of(".rs"); case "kotlin" -> Set.of(".kt", ".kts"); case "typescript" -> Set.of(".ts", ".tsx"); default -> throw new IllegalArgumentException("Unknown source language: " + language); }; }
    private static List<Definition> definitions(Node node, String language) throws IOException { return language.equals("kotlin") ? kotlinDefinitions(read(node.path)) : rustDefinitions(read(node.path)); }
    record Unported(Definition definition, Path source, Path target, int priority) {}
    static List<Unported> unported(Inventory inventory) throws IOException {
        List<Unported> result = new ArrayList<>();
        for (Node node : inventory.source) {
            List<Definition> source = definitions(node, inventory.sourceLanguage);
            if (node.match != null) { Set<String> target = new LinkedHashSet<>(); definitions(node.match, inventory.targetLanguage).forEach(d -> target.add(d.normalized())); for (Definition definition : source) if (!target.contains(definition.normalized())) result.add(new Unported(definition, node.path, node.match.path, 0)); }
            else if (source.isEmpty()) result.add(new Unported(new Definition(node.path.getFileName().toString(), "file", 1, "// " + node.path.getFileName(), node.name), node.path, null, 1));
            else source.stream().filter(d -> Set.of("struct", "enum", "trait", "fn").contains(d.kind())).findFirst().ifPresent(d -> result.add(new Unported(d, node.path, null, 1)));
        }
        result.sort(Comparator.comparingInt(Unported::priority).thenComparing(u -> u.source().toString()).thenComparingInt(u -> u.definition().line())); return result;
    }
    private static void inventoryReport(Inventory inventory, boolean verbose, boolean matched) {
        long count = inventory.source.stream().filter(n -> n.match != null).count(), extra = inventory.target.stream().filter(n -> n.match == null).count(); System.out.println("PORT LINTER REPORT\nSource files: " + inventory.source.size() + "\nTarget files: " + inventory.target.size() + "\nMatched: " + count + "\nMissing: " + (inventory.source.size() - count) + "\nTarget-only: " + extra);
        if (verbose || inventory.source.size() - count <= 50) for (Node node : inventory.source) if (node.match == null) System.out.println("MISSING " + inventory.sourceRoot.relativize(node.path) + " -> " + (inventory.targetLanguage.equals("kotlin") ? pascal(stem(node.path.getFileName().toString())) + ".kt" : snake(stem(node.path.getFileName().toString())) + ".rs"));
        if (verbose || matched) for (Node node : inventory.source) if (node.match != null) System.out.println("MATCHED [" + node.method + "] " + node.path + " <-> " + node.match.path + " (" + node.namespace + " / " + node.match.namespace + ")");
        if (verbose) for (Node node : inventory.target) if (node.match == null) System.out.println("TARGET ONLY " + inventory.targetRoot.relativize(node.path));
    }
    private static void deep(Inventory inventory, boolean missingOnly) throws IOException {
        int sourceCount = 0, targetCount = 0, matched = 0, missing = 0, extra = 0, pairs = 0;
        for (Node node : inventory.source) if (node.match != null) {
            pairs++; List<Definition> source = definitions(node, inventory.sourceLanguage), target = definitions(node.match, inventory.targetLanguage); sourceCount += source.size(); targetCount += target.size(); Set<String> sourceNames = new LinkedHashSet<>(), targetNames = new LinkedHashSet<>(); source.forEach(d -> sourceNames.add(d.normalized())); target.forEach(d -> targetNames.add(d.normalized()));
            List<Definition> sourceOnly = source.stream().filter(d -> !targetNames.contains(d.normalized())).toList(), targetOnly = target.stream().filter(d -> !sourceNames.contains(d.normalized())).toList(); missing += sourceOnly.size(); extra += targetOnly.size(); matched += source.size() - sourceOnly.size();
            if (!sourceOnly.isEmpty() || !missingOnly && !targetOnly.isEmpty()) { System.out.println(node.path + " <-> " + node.match.path); sourceOnly.forEach(d -> System.out.println("  MISSING " + d.kind() + " " + d.name() + " line " + d.line())); if (!missingOnly) targetOnly.forEach(d -> System.out.println("  TARGET-ONLY " + d.kind() + " " + d.name() + " line " + d.line())); }
        }
        System.out.println("DEEP COMPARISON: " + pairs + " files, " + sourceCount + " source definitions, " + targetCount + " target definitions; " + matched + " matched, " + missing + " missing, " + extra + " target-only");
    }
    static Map<String, List<Location>> globalTypes(Path root, boolean rust, boolean suppression) throws IOException {
        Map<String, List<Location>> result = new LinkedHashMap<>(); List<Rule> rules = rust ? RUST_TYPES : KOTLIN_TYPES;
        for (Path path : files(root, Set.of(rust ? ".rs" : ".kt"), false, rust)) { List<String> lines = read(path).lines().toList(); for (int i = 0; i < lines.size(); i++) for (Rule rule : rules) { var match = rule.pattern().matcher(lines.get(i)); if (!match.find() || suppression && suppressed(lines, i + 1)) continue; String name = match.group(match.groupCount()), key = rust ? snake(name) : rule.kind() + ":" + name; result.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new Location(path, i + 1, lines.get(i).strip())); } }
        return result;
    }
    static List<String> transpositions(String name, boolean all) {
        String normalized = snake(name); List<String> result = new ArrayList<>(List.of(normalized)); List<String> parts = new ArrayList<>(List.of(normalized.split("_", -1)));
        if (parts.size() >= 2) { result.add(String.join("_", parts.reversed())); if (all && parts.size() == 3) { result.add(parts.get(1) + "_" + parts.get(0) + "_" + parts.get(2)); result.add(parts.get(0) + "_" + parts.get(2) + "_" + parts.get(1)); } } return result;
    }
    static String duplicateCategory(String name, Map<String, List<Location>> rust) { for (String candidate : transpositions(name, false)) if (rust.containsKey(candidate)) return rust.get(candidate).size() == 1 ? "PORTING MISTAKE" : "INHERITED FROM RUST"; return "KOTLIN-ONLY DUPLICATE"; }
    private static void duplicates(Path source, Path target) throws IOException {
        Map<String, List<Location>> rust = globalTypes(source, true, false), kotlin = globalTypes(target, false, true); Map<String, Integer> categories = new TreeMap<>(); int only = 0;
        for (var entry : kotlin.entrySet()) { String name = entry.getKey().substring(entry.getKey().indexOf(':') + 1); boolean absent = transpositions(name, false).stream().noneMatch(rust::containsKey); if (absent) { only++; System.out.println("KOTLIN-ONLY TYPE " + entry.getKey() + " " + entry.getValue()); }
            if (entry.getValue().size() <= 1) continue; String category = duplicateCategory(name, rust); categories.merge(category, 1, Integer::sum); System.out.println(category + " " + entry.getKey() + " " + entry.getValue());
            if (absent) { String snake = snake(name); List<Location> suggestions = new ArrayList<>(); for (var original : rust.entrySet()) if (original.getKey().contains(snake) || snake.contains(original.getKey()) || original.getKey().contains(name.toLowerCase(Locale.ROOT)) || original.getKey().replace("_", "").equals(snake.replace("_", "")) || transpositions(name, true).contains(original.getKey())) suggestions.addAll(original.getValue()); System.out.println("  Potential Rust matches: " + suggestions.stream().limit(5).toList()); }
            else for (String candidate : transpositions(name, false)) if (rust.containsKey(candidate)) { System.out.println("  Rust locations: " + rust.get(candidate)); break; }
        }
        System.out.println("DUPLICATE SUMMARY " + categories + "; Kotlin-only types=" + only);
    }
    record SnakeFinding(Path path, int line, String name, String kind, String context) {}
    static List<SnakeFinding> snakeFindings(Path path, String text) {
        List<Rule> rules = List.of(rule("^\\s*(?:val|var)\\s+([a-z][a-z0-9]*(?:_[a-z0-9]+)+)\\s*[=:]", "property"), rule("(?:^|,|\\()\\s*(?:val\\s+)?([a-z][a-z0-9]*(?:_[a-z0-9]+)+)\\s*:", "parameter"), rule("\\.([a-z][a-z0-9]*(?:_[a-z0-9]+)+)(?:\\s*[=\\(\\[]|\\s*$)", "access"));
        List<SnakeFinding> result = new ArrayList<>(); List<String> lines = text.lines().toList(); for (int i = 0; i < lines.size(); i++) { String line = lines.get(i); if (line.contains("@SerialName") || line.contains("@Json") || line.strip().startsWith("//") || line.strip().startsWith("*")) continue; for (Rule rule : rules) { var match = rule.pattern().matcher(line); while (match.find()) if (!Set.of("is_error", "call_id").contains(match.group(1))) result.add(new SnakeFinding(path, i + 1, match.group(1), rule.kind(), line.strip())); } } return result;
    }
    private static void snakeReport(Path source, Path target, boolean rust) throws IOException { Map<String, List<Location>> originals = rust ? globalTypes(source, true, false) : Map.of(); List<SnakeFinding> findings = new ArrayList<>(); List<Path> paths = files(target, Set.of(".kt"), false, false); for (Path file : paths) findings.addAll(snakeFindings(file, read(file))); for (SnakeFinding finding : findings) System.out.println(finding.path() + ":" + finding.line() + " " + finding.kind() + " " + finding.name() + (originals.containsKey(finding.name().toLowerCase(Locale.ROOT)) ? " [exists in Rust]" : "") + "\n  " + finding.context()); System.out.println("SNAKE_CASE: " + findings.size() + " violations in " + paths.size() + " files"); }
    private static SimilarityIndex index(Path root, boolean rust) throws IOException {
        SimilarityIndex result = new SimilarityIndex(); List<Rule> rules = new ArrayList<>(rust ? RUST_TYPES.subList(0, 3) : KOTLIN_TYPES); if (rust) { rules.add(rule("^\\s*pub(?:\\([^)]+\\))?\\s+fn\\s+(\\w+)", "fn")); rules.addAll(RUST_TYPES.subList(3, 5)); } else rules.add(rule("^\\s*fun\\s+(\\w+)", "fun"));
        for (Path path : files(root, Set.of(rust ? ".rs" : ".kt"), false, rust)) { List<String> lines = read(path).lines().toList(); for (int i = 0; i < lines.size(); i++) for (Rule rule : rules) { var match = rule.pattern().matcher(lines.get(i)); if (match.find()) result.add(match.group(match.groupCount()), new Location(path, i + 1, lines.get(i).strip())); } } return result;
    }
    private static void similar(Path source, Path target, double threshold, String query) throws IOException {
        SimilarityIndex rust = index(source, true), kotlin = index(target, false); System.out.println("SIMILARITY MATCHING (0.4 Jaccard + 0.6 cosine), threshold=" + threshold);
        if (query != null) { System.out.println("Rust: " + rust.find(query, 0.5, 10)); System.out.println("Kotlin: " + kotlin.find(query, 0.5, 10)); return; }
        int matched = 0, extra = 0, missing = 0; Set<String> matchedRust = new LinkedHashSet<>();
        for (var entry : kotlin.names.entrySet()) { List<NameMatch> candidates = rust.find(entry.getKey(), threshold, 1); if (candidates.isEmpty()) { extra += entry.getValue().size(); System.out.println("UNMATCHED KOTLIN " + entry); } else { matched++; NameMatch best = candidates.getFirst(); matchedRust.add(best.name()); String confidence = best.score() >= .95 ? "EXACT/NORMALIZED" : best.score() >= .8 ? "HIGH CONFIDENCE" : "LOWER CONFIDENCE"; System.out.println(confidence + " " + entry.getKey() + " <-> " + best); } }
        for (var entry : rust.names.entrySet()) if (!matchedRust.contains(entry.getKey()) && kotlin.find(entry.getKey(), threshold, 1).isEmpty()) { missing += entry.getValue().size(); System.out.println("RUST TO PORT " + entry); }
        System.out.println("SIMILARITY SUMMARY: " + matched + " matched pairs; " + missing + " unmatched Rust; " + extra + " unmatched Kotlin");
    }
    private static void search(Path source, Path target, String symbol) throws IOException {
        Set<String> variants = new LinkedHashSet<>(List.of(symbol, symbol.toLowerCase(Locale.ROOT), pascal(symbol), camel(symbol), snake(symbol), normalize(symbol))); Pattern query = Pattern.compile(String.join("|", variants.stream().map(Pattern::quote).toList()), Pattern.CASE_INSENSITIVE);
        for (var pair : List.of(Map.entry(source, true), Map.entry(target, false))) { int total = 0; for (Path path : files(pair.getKey(), Set.of(pair.getValue() ? ".rs" : ".kt"), false, pair.getValue())) { List<String> lines = read(path).lines().toList(); int count = 0; for (int i = 0; i < lines.size() && count < 3; i++) if (query.matcher(lines.get(i)).find()) { count++; total++; System.out.println(path + ":" + (i + 1) + " " + clip(lines.get(i).stripTrailing(), 80)); } } System.out.println(pair.getKey() + ": " + total + " displayed matches"); }
    }
    public record Diagnostic(String level, Path path, int line, int column, String message, String category, String snakeName, String camelName) {}
    static List<Diagnostic> diagnostics(String text) {
        List<Diagnostic> result = new ArrayList<>(); var matcher = Pattern.compile("^([ew]): file:///(.+?):(\\d+):(\\d+)\\s+(.+)$", Pattern.MULTILINE).matcher(text);
        while (matcher.find()) { String message = matcher.group(5), lower = message.toLowerCase(Locale.ROOT), category;
            if (matcher.group(1).equals("e")) category = message.contains("Unresolved reference") || message.contains("None of the following candidates") ? "unresolved reference" : lower.contains("type mismatch") || lower.contains("required:") ? "type mismatch" : message.contains("_") && (message.contains("camelCase") || lower.contains("parameter")) ? "naming" : "other error";
            else category = lower.contains("deprecated") ? "deprecated" : lower.contains("unused") || lower.contains("never used") ? "unused" : "other warning";
            var snake = Pattern.compile("'([a-z]+_[a-z_]+)'").matcher(message); String original = snake.find() ? snake.group(1) : null; result.add(new Diagnostic(matcher.group(1), Path.of("/" + matcher.group(2)), Integer.parseInt(matcher.group(3)), Integer.parseInt(matcher.group(4)), message, category, original, original == null ? null : camel(original)));
        } return result;
    }
    static int compileCheck(Path kotlinRoot, boolean verbose) throws Exception {
        Path project = kotlinRoot.toAbsolutePath().normalize().getParent(), wrapper = project.resolve("gradlew"); if (!Files.isRegularFile(wrapper)) { System.err.println("gradlew not found: " + wrapper); return 1; }
        Path output = project.resolve("build/reports/port-linter"); Files.createDirectories(output.resolve("tmp")); Path stdout = output.resolve("compile.stdout"), stderr = output.resolve("compile.stderr");
        ProcessBuilder builder = new ProcessBuilder("./gradlew", "compileNativeMainKotlinMetadata", "--no-daemon").directory(project.toFile()).redirectOutput(stdout.toFile()).redirectError(stderr.toFile()); builder.environment().put("TMPDIR", output.resolve("tmp").toString()); builder.environment().put("JAVA_TOOL_OPTIONS", builder.environment().getOrDefault("JAVA_TOOL_OPTIONS", "") + " -Djava.io.tmpdir=" + output.resolve("tmp"));
        Process process = builder.start(); boolean completed = false; try { completed = process.waitFor(300, TimeUnit.SECONDS); } finally { if (!completed) { try { process.descendants().forEach(ProcessHandle::destroyForcibly); } catch (RuntimeException unavailable) { System.err.println("Cannot enumerate compiler descendants: " + unavailable.getMessage()); } process.destroyForcibly(); process.waitFor(); } }
        int exit = completed ? process.exitValue() : 124; List<Diagnostic> results = diagnostics(Files.readString(stdout) + Files.readString(stderr));
        for (Diagnostic diagnostic : results) if (verbose || diagnostic.level().equals("e") || diagnostic.category().equals("deprecated")) System.out.println(diagnostic); Map<String, Long> counts = new TreeMap<>(); for (Diagnostic diagnostic : results) counts.merge(diagnostic.category(), 1L, Long::sum);
        Json.write(output.resolve("compile.json"), Map.of("exit", exit, "success", exit == 0, "timed_out", !completed, "categories", counts, "diagnostics", results.stream().map(d -> Map.of("level", d.level(), "path", d.path().toString(), "line", d.line(), "column", d.column(), "message", d.message(), "category", d.category())).toList()));
        System.out.println("COMPILE CHECK " + counts + "; Build: " + (exit == 0 ? "SUCCESS" : "FAILED (exit " + exit + ")")); return exit == 0 ? 0 : 1;
    }
    static Map<String, String> options(String... args) {
        Set<String> flags = Set.of("auto", "compile-check", "compare", "duplicates", "snake-case", "similar", "verbose", "matched", "deep", "missing-only"), values = Set.of("rust-root", "kotlin-root", "ts-root", "mode", "search", "similar-query", "threshold", "focus"); Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) { String key = args[i].equals("-v") ? "verbose" : args[i].startsWith("--") ? args[i].substring(2) : ""; if (flags.contains(key)) result.put(key, "true"); else { if (!values.contains(key) || i + 1 >= args.length || args[i + 1].startsWith("--")) throw new IllegalArgumentException("Unrecognized or missing option value: " + args[i]); result.put(key, args[++i]); } }
        String mode = result.getOrDefault("mode", "rust2kotlin"); if (!Set.of("rust2kotlin", "ts2rust").contains(mode)) throw new IllegalArgumentException("Invalid --mode"); double threshold = Double.parseDouble(result.getOrDefault("threshold", ".7")); if (!Double.isFinite(threshold) || threshold < 0 || threshold > 1) throw new IllegalArgumentException("Threshold must be between 0 and 1"); return result;
    }
    public static int run(String... args) throws Exception {
        if (List.of(args).contains("--help") || List.of(args).contains("-h")) { System.out.println("PortLinter [--rust-root PATH] [--kotlin-root PATH] [--mode rust2kotlin|ts2rust --ts-root PATH] [--auto|--compile-check|--compare|--deep|--similar|--similar-query NAME|--snake-case|--duplicates|--search SYMBOL] [--threshold 0..1] [--focus DIR] [--verbose|-v] [--matched] [--missing-only]"); return 0; }
        Map<String, String> options = options(args); Path rust = Path.of(options.getOrDefault("rust-root", "codex-rs")), kotlin = Path.of(options.getOrDefault("kotlin-root", "src")); boolean ts = options.getOrDefault("mode", "rust2kotlin").equals("ts2rust"); if (ts && !options.containsKey("ts-root")) throw new IllegalArgumentException("--ts-root is required for ts2rust"); Path source = ts ? Path.of(options.get("ts-root")) : rust, target = ts ? rust : kotlin;
        if (!Files.isDirectory(source) || !Files.isDirectory(target)) { System.err.println("Source or target codebase not found: " + source + ", " + target); return 1; } boolean verbose = options.containsKey("verbose");
        if (options.containsKey("compile-check") && !options.containsKey("auto")) return compileCheck(target, verbose);
        if (!options.containsKey("auto") && (options.containsKey("similar") || options.containsKey("similar-query"))) { similar(source, target, Double.parseDouble(options.getOrDefault("threshold", ".7")), options.get("similar-query")); return 0; }
        if (!options.containsKey("auto") && options.containsKey("snake-case")) { snakeReport(source, target, !ts); return 0; }
        if (!options.containsKey("auto") && options.containsKey("duplicates")) { duplicates(source, target); return 0; }
        if (!options.containsKey("auto") && !options.containsKey("deep") && options.containsKey("search")) { search(source, target, options.get("search")); return 0; }
        if (options.containsKey("focus")) { Path focused = source.resolve(options.get("focus")); if (Files.exists(focused)) source = focused; else System.out.println("Warning: --focus directory not found: " + focused); }
        Inventory inventory = new Inventory(source, target, ts ? "typescript" : "rust", ts ? "rust" : "kotlin");
        if (options.containsKey("auto")) { List<Unported> items = unported(inventory); if (items.isEmpty()) System.out.println("No unported items found by the definition inventory; runtime behavior requires the project test suite."); else { System.out.println("NEXT ITEM TO PORT (" + items.size() + " remaining): " + items.getFirst()); System.out.println("NEXT UP: " + items.stream().skip(1).limit(5).toList()); } }
        else if (options.containsKey("deep")) deep(inventory, options.containsKey("missing-only")); else inventoryReport(inventory, verbose, options.containsKey("matched")); return 0;
    }
    public static void main(String[] args) throws Exception { System.exit(run(args)); }
}
