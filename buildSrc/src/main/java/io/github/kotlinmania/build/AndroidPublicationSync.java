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
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlTable;

/** Focused, opt-in Android publication sync. Plans one named checkout; never mutates Git. */
public final class AndroidPublicationSync {
    private AndroidPublicationSync() {}
    public static final String MARKER = "kotlinmania:android-publication:v1";
    private static final String JAVA = "buildSrc/src/main/java/io/github/kotlinmania/build/";
    private static final String FRAGMENT = "verification/android-publication.gradle.kts.fragment";
    public static final List<String> FILES = List.of(
        "buildSrc/build.gradle.kts", "buildSrc/settings.gradle.kts", JAVA + "Json.java", JAVA + "BuildToolTask.java", JAVA + "BuildToolMain.java",
        JAVA + "AndroidPublicationVerifier.java", JAVA + "PublicationPinsVerifier.java", JAVA + "AndroidClasspathVerifier.java");

    public record Token(String value, int start, int end) {}
    private record Block(int opening, int closing) {}
    private record Edit(int start, int end, String value) {}
    @FunctionalInterface interface GitReader { String read(Path repo, String... arguments) throws IOException; }

    /** Kotlin structural tokens with offsets; comments and strings never become edit anchors. */
    public static List<Token> tokens(String text) {
        List<Token> result = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (text.startsWith("//", i)) {
                int end = text.indexOf('\n', i);
                i = end < 0 ? text.length() : end;
            } else if (text.startsWith("/*", i)) {
                int depth = 1;
                i += 2;
                while (depth > 0 && i < text.length()) {
                    if (text.startsWith("/*", i)) { depth++; i += 2; }
                    else if (text.startsWith("*/", i)) { depth--; i += 2; }
                    else i++;
                }
                require(depth == 0, "Unterminated Kotlin comment");
            } else if (text.startsWith("\"\"\"", i)) {
                int end = text.indexOf("\"\"\"", i + 3);
                require(end >= 0, "Unterminated Kotlin raw string");
                i = end + 3;
            } else if (c == '\"' || c == '\'') {
                char quote = c;
                i++;
                while (i < text.length() && text.charAt(i) != quote) i += text.charAt(i) == '\\' ? 2 : 1;
                require(i < text.length(), "Unterminated Kotlin string");
                i++;
            } else {
                int start = i++;
                if (identifierStart(c)) {
                    while (i < text.length() && (identifierStart(text.charAt(i)) || Character.isDigit(text.charAt(i)))) i++;
                }
                result.add(new Token(text.substring(start, i), start, i));
            }
        }
        return result;
    }

    private static boolean identifierStart(char value) {
        return value >= 'a' && value <= 'z' || value >= 'A' && value <= 'Z' || value == '_';
    }

    private static boolean matches(List<Token> tokens, int start, List<String> pattern) {
        if (start < 0 || start + pattern.size() > tokens.size()) return false;
        for (int i = 0; i < pattern.size(); i++) if (!tokens.get(start + i).value().equals(pattern.get(i))) return false;
        return true;
    }

    private static List<String> words(String text) { return tokens(text).stream().map(Token::value).toList(); }

    public static void validateWarningPolicy(String text) {
        List<Token> tokens = tokens(text);
        for (List<String> pattern : List.of(
            List.of("allWarningsAsErrors", ".", "set", "(", "false", ")"), List.of("allWarningsAsErrors", "=", "false"))) {
            for (int i = 0; i < tokens.size(); i++) require(!matches(tokens, i, pattern),
                "Warnings-as-errors relaxation requires source repair; sync refuses to preserve/reintroduce it");
        }
    }

    private static Block block(List<Token> tokens, List<String> pattern, int start, int end) {
        List<Integer> candidates = new ArrayList<>();
        for (int i = start; i <= end - pattern.size(); i++) if (matches(tokens, i, pattern)) candidates.add(i + pattern.size() - 1);
        require(candidates.size() == 1, "Unsupported/ambiguous Kotlin block: " + pattern);
        int opening = candidates.getFirst();
        int depth = 1;
        for (int i = opening + 1; i < end; i++) {
            depth += tokens.get(i).value().equals("{") ? 1 : tokens.get(i).value().equals("}") ? -1 : 0;
            if (depth == 0) return new Block(opening, i);
        }
        throw new IllegalArgumentException("Unbalanced Kotlin block");
    }

    public static String transform(String text, Collection<String> aliases, String fragment) {
        return transform(text, aliases, fragment, Set.of());
    }

    public static String transform(String text, Collection<String> aliases, String fragment, Set<String> bundleAliases) {
        validateWarningPolicy(text);
        validateWarningPolicy(fragment);
        List<Token> tokens = tokens(text);
        Block kotlin = block(tokens, List.of("kotlin", "{"), 0, tokens.size());
        block(tokens, List.of("android", "{"), kotlin.opening() + 1, kotlin.closing());
        Block sourceSets = block(tokens, List.of("sourceSets", "{"), kotlin.opening() + 1, kotlin.closing());
        Block common = block(tokens, List.of("commonMain", ".", "dependencies", "{"), sourceSets.opening() + 1, sourceSets.closing());
        List<Edit> changes = new ArrayList<>();
        for (String alias : new TreeSet<>(aliases)) {
            require(alias.matches("libs\\.[a-zA-Z0-9_.]+"), "Unsupported catalog accessor: " + alias);
            List<String> middle = words(alias);
            List<Integer> calls = new ArrayList<>();
            for (int i = common.opening() + 1; i < common.closing(); i++) {
                if (Set.of("api", "implementation").contains(tokens.get(i).value())) {
                    List<String> pattern = new ArrayList<>(List.of(tokens.get(i).value(), "("));
                    pattern.addAll(middle);
                    pattern.add(")");
                    if (matches(tokens, i, pattern)) {
                        int depth = 0;
                        for (int j = common.opening() + 1; j < i; j++) depth += tokens.get(j).value().equals("{") ? 1 : tokens.get(j).value().equals("}") ? -1 : 0;
                        require(depth == 0 && !tokens.get(i + pattern.size()).value().equals("{"),
                            "Conditional/custom dependency override requires manual review");
                        calls.add(i);
                    }
                }
            }
            if (calls.isEmpty() && bundleAliases.contains(alias)) {
                List<String> pattern = List.of("implementation", "(", "commonMainDependencyBundle", ")");
                int count = 0;
                for (int i = common.opening() + 1; i < common.closing(); i++) if (matches(tokens, i, pattern)) count++;
                require(count == 1, "Unsupported commonMain bundle call");
                int end = tokens.get(common.closing()).start();
                changes.add(new Edit(end, end, "    api(" + alias + ")\n        "));
                continue;
            }
            require(calls.size() == 1, "Expected one direct commonMain dependency for " + alias + "; preserve custom DSL");
            Token call = tokens.get(calls.getFirst());
            if (call.value().equals("implementation")) changes.add(new Edit(call.start(), call.end(), "api"));
        }
        changes.sort(Comparator.comparingInt(Edit::start).thenComparingInt(Edit::end).thenComparing(Edit::value).reversed());
        for (Edit change : changes) text = text.substring(0, change.start()) + change.value() + text.substring(change.end());
        String begin = "// " + MARKER + " begin";
        String finish = "// " + MARKER + " end";
        if (text.contains(begin)) {
            require(count(text, begin) == 1 && count(text, finish) == 1, "Ambiguous publication guard markers");
            int start = text.indexOf(begin);
            int end = text.indexOf(finish) + finish.length();
            require(end >= start && text.substring(start, end).equals(fragment.strip()), "Existing publication override differs; manual review required");
        } else {
            require(!text.contains(finish), "Ambiguous publication guard markers");
            Set<String> words = new LinkedHashSet<>(words(text));
            require(!words.contains("verifyAndroidPublication") && !words.contains("verifyPublicationPins"),
                "Existing project publication verifier: preserve and review manually");
            require(words.containsAll(Set.of("publishing", "publishToCentralPortal", "centralPortalStaging"))
                || text.contains("centralPortalStaging") && words.containsAll(Set.of("publishing", "publishToCentralPortal")),
                "Unsupported first-party staging/upload layout");
            require(words.containsAll(Set.of("centralPortalBundle", "kotlinVersion")),
                "Unsupported release bundle/Kotlin version layout; preserve project specialization");
            require(words.contains("MavenPublication") && words.contains("PublishToMavenRepository"), "Unsupported publication import layout");
            text = text.stripTrailing() + "\n\n" + fragment;
        }
        return text;
    }

    private static int count(String text, String pattern) {
        int count = 0;
        for (int i = 0; (i = text.indexOf(pattern, i)) >= 0; i += pattern.length()) count++;
        return count;
    }

    public static Map<String, String> inventory(Path repo) throws IOException {
        Map<String, String> result = new TreeMap<>();
        Path github = repo.resolve(".github");
        if (Files.exists(github)) {
            try (var paths = Files.walk(github)) {
                for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                    if (path.toString().endsWith(".yaml") || path.toString().endsWith(".yml")) {
                        require(!Files.isSymbolicLink(path), "Symlink GitHub YAML is unsupported");
                        result.put(relative(repo, path), PublicationSupport.fingerprint(path));
                    }
                }
            }
        }
        return result;
    }

    public static Map<String, Object> plan(Path source, Path repo, String module, Collection<String> aliases) throws IOException {
        return plan(source, repo, module, aliases, AndroidPublicationSync::git);
    }

    static Map<String, Object> plan(Path source, Path repo, String module, Collection<String> aliases, GitReader git) throws IOException {
        source = source.toRealPath();
        repo = repo.toRealPath();
        require(repo.getParent().equals(source.getParent()) && Files.isDirectory(repo.resolve(".git")), "Use an existing primary shared checkout");
        Path modulePath = Path.of(module);
        require(!modulePath.isAbsolute() && java.util.stream.StreamSupport.stream(modulePath.spliterator(), false)
            .noneMatch(part -> part.toString().equals("..")), "Invalid Gradle module path");
        String branch = git.read(repo, "branch", "--show-current");
        require(!branch.isEmpty(), "Detached checkout is unsupported");
        Path build = repo.resolve(modulePath).resolve("build.gradle.kts").normalize();
        require(Files.exists(build) && !Files.isSymbolicLink(build), "Unsupported/missing Kotlin build file");
        require(build.toRealPath().startsWith(repo), "Module escapes checkout");
        Path catalog = repo.resolve("gradle/libs.versions.toml");
        require(Files.isRegularFile(catalog), "Unsupported catalog layout");
        var parsed = Toml.parse(catalog);
        require(!parsed.hasErrors(), "Invalid version catalog: " + parsed.errors());
        TomlTable plugins = parsed.getTable("plugins");
        require(plugins != null && plugins.toMap().values().stream()
            .anyMatch(value -> value instanceof TomlTable table && "com.android.kotlin.multiplatform.library".equals(table.getString("id"))),
            "Android KMP plugin missing; target migration requires owner review");
        String beforeBuild = Files.readString(build);
        List<Token> tokens = tokens(beforeBuild);
        Block pluginBlock = block(tokens, List.of("plugins", "{"), 0, tokens.size());
        boolean active = false;
        for (Map.Entry<String, Object> entry : plugins.toMap().entrySet()) {
            if (!(entry.getValue() instanceof TomlTable plugin) || !"com.android.kotlin.multiplatform.library".equals(plugin.getString("id"))) continue;
            List<String> pattern = new ArrayList<>(List.of("alias", "("));
            pattern.addAll(words("libs.plugins." + accessor(entry.getKey())));
            pattern.add(")");
            for (int i = pluginBlock.opening() + 1; i < pluginBlock.closing(); i++) {
                if (matches(tokens, i, pattern)) {
                    require(!matches(tokens, i + pattern.size(), List.of("apply", "false")), "Android plugin is apply false in selected module");
                    active = true;
                }
            }
        }
        require(active, "Unsupported Android plugin application");
        Map<String, String> properties = new LinkedHashMap<>();
        for (String line : Files.readAllLines(repo.resolve("gradle.properties"))) {
            int equals = line.indexOf('=');
            if (equals >= 0 && !line.stripLeading().startsWith("#")) properties.put(line.substring(0, equals), line.substring(equals + 1));
        }
        String bundleName = properties.getOrDefault("project.dependencies.commonMainBundle", "").strip();
        TomlTable bundles = parsed.getTable("bundles");
        TomlArray bundle = bundles == null || bundleName.isEmpty() ? null : bundles.getArray(List.of(bundleName));
        Set<String> bundleAliases = new TreeSet<>();
        if (bundle != null) for (int i = 0; i < bundle.size(); i++) bundleAliases.add("libs." + accessor(bundle.getString(i)));
        if (!bundleAliases.isEmpty()) require(beforeBuild.contains("\"project.dependencies.commonMainBundle\""), "Unsupported bundle property mapping");
        String buildName = relative(repo, build);
        Map<String, String> contents = new LinkedHashMap<>();
        contents.put(buildName, transform(beforeBuild, aliases, Files.readString(source.resolve(FRAGMENT)), bundleAliases));
        for (String name : FILES) contents.put(name, Files.readString(source.resolve(name)));
        Map<String, Object> changes = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : contents.entrySet()) {
            String name = entry.getKey();
            String after = entry.getValue();
            Path path = repo.resolve(name);
            rejectSymlinks(repo, path);
            String before = Files.exists(path) ? Files.readString(path) : "";
            if (!name.equals(buildName) && !before.isEmpty()) require(before.equals(after), "Existing project override: " + name);
            Map<String, Object> change = new LinkedHashMap<>();
            change.put("before", sha(before));
            change.put("after", sha(after));
            change.put("text", after);
            change.put("diff", diff(name, before, after));
            changes.put(name, change);
        }
        Map<String, String> inputs = new LinkedHashMap<>();
        for (Path path : List.of(catalog, repo.resolve("settings.gradle.kts"), repo.resolve("gradle.properties"))) {
            if (Files.exists(path)) inputs.put(relative(repo, path), PublicationSupport.fingerprint(path));
        }
        Map<String, String> hashes = new LinkedHashMap<>();
        List<String> sourceFiles = new ArrayList<>(FILES);
        sourceFiles.add(FRAGMENT);
        sourceFiles.add(JAVA + "AndroidPublicationSync.java");
        for (String name : sourceFiles) hashes.put(name, PublicationSupport.fingerprint(source.resolve(name)));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("repo", repo.toString());
        result.put("head", git.read(repo, "rev-parse", "HEAD"));
        result.put("branch", branch);
        result.put("module", module);
        result.put("api_aliases", new ArrayList<>(new TreeSet<>(aliases)));
        result.put("source", source.toString());
        boolean versionedSource = false;
        for (Path cursor = source; cursor != null; cursor = cursor.getParent()) {
            if (Files.exists(cursor.resolve(".git"))) { versionedSource = true; break; }
        }
        result.put("source_head", versionedSource ? git.read(source, "rev-parse", "HEAD")
            : "sha256:" + sha(Json.stringify(new TreeMap<>(hashes))));
        result.put("source_hashes", hashes);
        result.put("inputs", inputs);
        result.put("workflows", inventory(repo));
        result.put("changes", changes);
        return result;
    }

    public static void apply(Path source, Map<String, Object> saved, Map<String, Object> grant) throws IOException {
        apply(source, saved, grant, AndroidPublicationSync::git);
    }

    static void apply(Path source, Map<String, Object> saved, Map<String, Object> grant, GitReader git) throws IOException {
        require(saved.get("repo").equals(grant.get("repo")) && saved.get("head").equals(grant.get("head"))
            && Boolean.TRUE.equals(grant.get("source_write_granted")) && grant.get("owner") instanceof String owner && !owner.isBlank(),
            "Explicit current repo/HEAD/owner source grant required; held projects cannot be applied");
        require(saved.get("branch") instanceof String branch && !Set.of("main", "master", "").contains(branch),
            "A preserved feature branch is required to apply");
        Path repo = Path.of(PublicationSupport.string(saved, "repo"));
        List<String> aliases = PublicationSupport.array(saved.get("api_aliases")).stream().map(value -> {
            require(value instanceof String, "Invalid saved API alias");
            return (String) value;
        }).toList();
        Map<String, Object> fresh = plan(source, repo, PublicationSupport.string(saved, "module"), aliases, git);
        require(fresh.equals(saved), "Stale HEAD, source, hash, catalog or workflow plan; review a fresh diff");
        require(git.read(repo, "status", "--porcelain").isEmpty(), "Dirty checkout; preserve concurrent work");
        Map<String, Object> changes = PublicationSupport.object(saved.get("changes"));
        for (String name : changes.keySet()) rejectSymlinks(repo, repo.resolve(name));
        for (Map.Entry<String, Object> entry : changes.entrySet()) {
            Path path = repo.resolve(entry.getKey());
            rejectSymlinks(repo, path);
            Map<String, Object> change = PublicationSupport.object(entry.getValue());
            if (!change.get("before").equals(change.get("after"))) {
                String text = PublicationSupport.string(change, "text");
                if (Files.exists(path)) {
                    require(PublicationSupport.fingerprint(path).equals(change.get("before")), "Concurrent file modification");
                    Files.writeString(path, text, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS);
                } else {
                    Files.createDirectories(path.getParent());
                    Files.writeString(path, text, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                }
            }
        }
    }

    private static void rejectSymlinks(Path repo, Path path) {
        require(path.normalize().startsWith(repo.normalize()), "Path escapes checkout");
        for (Path cursor = path; cursor != null; cursor = cursor.getParent()) require(!Files.isSymbolicLink(cursor), "Symlink path is unsupported: " + path);
    }

    private static String accessor(String name) { return name.replace('-', '.').replace('_', '.'); }
    private static String relative(Path root, Path path) { return root.relativize(path).toString().replace('\\', '/'); }
    private static String sha(String text) { return PublicationSupport.hash("sha256", text.getBytes(StandardCharsets.UTF_8)); }

    /** A deterministic unified hunk containing the changed range and three context lines. */
    private static String diff(String name, String before, String after) {
        if (before.equals(after)) return "";
        List<String> oldLines = lines(before);
        List<String> newLines = lines(after);
        int prefix = 0;
        while (prefix < Math.min(oldLines.size(), newLines.size()) && oldLines.get(prefix).equals(newLines.get(prefix))) prefix++;
        int suffix = 0;
        while (suffix < Math.min(oldLines.size(), newLines.size()) - prefix
            && oldLines.get(oldLines.size() - suffix - 1).equals(newLines.get(newLines.size() - suffix - 1))) suffix++;
        int start = Math.max(0, prefix - 3);
        int oldEnd = Math.min(oldLines.size(), oldLines.size() - suffix + 3);
        int newEnd = Math.min(newLines.size(), newLines.size() - suffix + 3);
        StringBuilder result = new StringBuilder("--- a/" + name + "\n+++ b/" + name + "\n@@ -"
            + (oldLines.isEmpty() ? 0 : start + 1) + "," + (oldEnd - start) + " +" + (newLines.isEmpty() ? 0 : start + 1)
            + "," + (newEnd - start) + " @@\n");
        for (int i = start; i < prefix; i++) diffLine(result, ' ', oldLines.get(i));
        for (int i = prefix; i < oldLines.size() - suffix; i++) diffLine(result, '-', oldLines.get(i));
        for (int i = prefix; i < newLines.size() - suffix; i++) diffLine(result, '+', newLines.get(i));
        for (int i = oldLines.size() - suffix; i < oldEnd; i++) diffLine(result, ' ', oldLines.get(i));
        return result.toString();
    }

    private static List<String> lines(String text) {
        if (text.isEmpty()) return List.of();
        return List.of(text.split("(?<=\\n)"));
    }

    private static void diffLine(StringBuilder diff, char marker, String line) {
        diff.append(marker).append(line);
        if (!line.endsWith("\n")) diff.append("\n\\ No newline at end of file\n");
    }

    private static String git(Path repo, String... arguments) throws IOException {
        List<String> command = new ArrayList<>(List.of("git", "-C", repo.toString()));
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("GIT_OPTIONAL_LOCKS", "0");
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            require(process.waitFor() == 0, "Git read failed: " + output.strip());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while reading Git checkout", error);
        }
        return output.strip();
    }

    public static int run(String... args) {
        try {
            Map<String, List<String>> options = new LinkedHashMap<>();
            Set<String> allowed = Set.of("source", "repo", "module", "api-alias", "plan", "apply-plan", "grant");
            for (int i = 0; i < args.length; i++) {
                String key = args[i].startsWith("--") ? args[i].substring(2) : "";
                require(allowed.contains(key) && i + 1 < args.length, "Unknown or incomplete sync option: " + args[i]);
                options.computeIfAbsent(key, ignored -> new ArrayList<>()).add(args[++i]);
            }
            for (Map.Entry<String, List<String>> entry : options.entrySet()) require(entry.getKey().equals("api-alias") || entry.getValue().size() == 1,
                "Expected one --" + entry.getKey());
            Path source = Path.of(option(options, "source", "."));
            if (options.containsKey("apply-plan")) {
                require(options.containsKey("grant"), "--grant is required to apply");
                apply(source, PublicationSupport.object(Json.read(Path.of(option(options, "apply-plan", "")))),
                    PublicationSupport.object(Json.read(Path.of(option(options, "grant", "")))));
                System.out.println("Applied reviewed source plan; build/consumer/publication validation still required");
            } else {
                require(options.containsKey("repo"), "Name one --repo; fleet sync is unsupported");
                Map<String, Object> result = plan(source, Path.of(option(options, "repo", "")), option(options, "module", "."),
                    options.getOrDefault("api-alias", List.of()));
                for (Object change : PublicationSupport.object(result.get("changes")).values()) System.out.print(PublicationSupport.object(change).get("diff"));
                if (options.containsKey("plan")) Json.write(Path.of(option(options, "plan", "")), result);
            }
            return 0;
        } catch (IOException | IllegalArgumentException error) {
            System.err.println("Unsupported or unsafe sync: " + error.getMessage());
            return 2;
        }
    }

    private static String option(Map<String, List<String>> options, String key, String fallback) {
        return options.containsKey(key) ? options.get(key).getFirst() : fallback;
    }

    public static void main(String[] args) { System.exit(run(args)); }
}
