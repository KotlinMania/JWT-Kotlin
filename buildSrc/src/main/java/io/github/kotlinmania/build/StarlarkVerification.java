package io.github.kotlinmania.build;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Native compiler/oracle checks and read-only declaration drift for the Starlark port. */
public final class StarlarkVerification {
    private StarlarkVerification() {}
    private static void require(boolean value, String message) { if (!value) throw new IllegalArgumentException(message); }
    private static String hash(Path file) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))); }
    private static final List<String> PROFILES = List.of("DEBUG", "RELEASE", "CHECKED_RELEASE", "UNCHECKED_DEBUG");

    private static final class Execution implements AutoCloseable {
        final Path root, output, work;
        final Map<String, Object> receipt = new LinkedHashMap<>();
        final Map<String, String> inputs = new LinkedHashMap<>();
        final List<Map<String, Object>> commands = new ArrayList<>();
        Execution(Path root, String mode) throws Exception {
            this.root = root;
            Path parent = root.resolve("build/reports/" + mode); Files.createDirectories(parent);
            output = Files.createTempDirectory(parent, "run-");
            Path temporary = root.resolve("build/tmp/" + mode); Files.createDirectories(temporary);
            work = Files.createTempDirectory(temporary, "compile-");
            receipt.put("status", "running"); receipt.put("output", output.toString()); receipt.put("commands", commands); receipt.put("inputs", inputs);
        }
        Path input(Path source) throws Exception { inputs.put(source.toString(), hash(source)); return source; }
        Path snapshot(Path source, String name) throws Exception { input(source); Path target = work.resolve(name); Files.copy(source, target); return target; }
        Path run(String name, List<String> command, long timeout) throws Exception {
            Path stdout = output.resolve(name + ".stdout"), stderr = output.resolve(name + ".stderr");
            ProcessBuilder builder = new ProcessBuilder(command).directory(root.toFile()).redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
            builder.environment().putAll(Map.of("TMPDIR", work.toString(), "TMP", work.toString(), "TEMP", work.toString()));
            Process process = builder.start();
            int exit;
            try {
                if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
                    process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly(); process.waitFor(); exit = 124;
                } else exit = process.exitValue();
            } catch (InterruptedException error) { process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly(); Thread.currentThread().interrupt(); throw error; }
            commands.add(Map.of("command", command, "exit", exit, "stdout", stdout.getFileName().toString(), "stderr", stderr.getFileName().toString()));
            Json.write(output.resolve("results.json"), receipt);
            String errors = Files.readString(stderr);
            require(exit == 0, name + " exited " + exit + ": " + errors.substring(Math.max(0, errors.length() - 4000)));
            return stdout;
        }
        void unchanged() throws Exception { for (var input : inputs.entrySet()) require(hash(Path.of(input.getKey())).equals(input.getValue()), "Input changed: " + input.getKey()); }
        @Override public void close() throws java.io.IOException {
            if (receipt.get("status").equals("running")) receipt.put("status", "failed");
            Json.write(output.resolve("results.json"), receipt);
            try (var paths = Files.walk(work)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); }
            System.out.println(output);
        }
    }

    public static Map<String, Object> compare(Path expected, Path actual) throws Exception {
        require(Files.mismatch(expected, actual) == -1, "Rust/Kotlin output differs: " + expected + " versus " + actual);
        long rows; try (var lines = Files.lines(expected)) { rows = lines.count(); }
        return Map.of("rows", rows, "matches", true, "sha256", hash(expected));
    }

    private static List<String> command(Object... values) { return Arrays.stream(values).map(String::valueOf).toList(); }

    private static void translation(Path root, String konanc, String rustc) throws Exception {
        try (Execution run = new Execution(root, "translation-repairs")) {
            Path source = root.resolve("src/common/commonMain/kotlin/io/github/kotlinmania/starlarksyntax");
            Path fixtures = root.resolve("tools/translation-repair");
            run.run("rust-version", command(rustc, "-Vv"), 180); run.run("kotlin-version", command(konanc, "-version"), 180);
            List<String> compile = new ArrayList<>(List.of(konanc));
            for (String file : List.of("cursors/Cursors.kt", "cursors/CharacterDebug.kt", "semantics/BuildProfile.kt", "convertindices/ConvertIndices.kt")) compile.add(run.input(source.resolve(file)).toString());
            compile.add(run.input(fixtures.resolve("Trace.kt")).toString());
            compile.addAll(command("-Werror", "-entry", "portrepair.main", "-o", run.work.resolve("port")));
            run.run("compile-port", compile, 180);
            Map<String, Path> originals = new LinkedHashMap<>();
            for (String kind : List.of("cursors", "indices")) {
                Path original = run.input(root.resolve("tmp/starlark_syntax/src/" + (kind.equals("cursors") ? "cursors.rs" : "convert_indices.rs")));
                Path fixture = run.input(fixtures.resolve(kind + ".rs"));
                Path combined = run.work.resolve(kind + "-oracle.rs"); Files.writeString(combined, Files.readString(original) + "\n" + Files.readString(fixture)); originals.put(kind, combined);
            }
            Map<String, Object> profiles = new LinkedHashMap<>(); run.receipt.put("profiles", profiles);
            for (String profile : PROFILES) {
                boolean overflow = profile.equals("DEBUG") || profile.equals("CHECKED_RELEASE"), debug = profile.equals("DEBUG") || profile.equals("UNCHECKED_DEBUG");
                Map<String, Object> results = new LinkedHashMap<>();
                for (String kind : List.of("cursors", "indices")) {
                    Path binary = run.work.resolve(kind + "-" + profile);
                    run.run("compile-" + binary.getFileName(), command(rustc, "--edition=2021", "--crate-name", kind, "-C", "panic=unwind", "-C", "overflow-checks=" + (overflow ? "yes" : "no"), "-C", "debug-assertions=" + (debug ? "yes" : "no"), originals.get(kind), "-o", binary), 180);
                    Path rust = run.run("rust-" + kind + "-" + profile, command(binary), 180);
                    Path kotlin = run.run("kotlin-" + kind + "-" + profile, command(run.work.resolve("port.kexe"), profile, kind), 180);
                    results.put(kind, compare(rust, kotlin));
                }
                profiles.put(profile, results); System.out.println(profile + " " + Json.stringify(results));
            }
            Map<String, Object> scalars = compare(run.run("rust-scalars", command(run.work.resolve("cursors-DEBUG"), "scalars"), 180), run.run("kotlin-scalars", command(run.work.resolve("port.kexe"), "DEBUG", "scalars"), 180));
            require(((Number) scalars.get("rows")).longValue() == 1112064, "Expected every Unicode scalar");
            run.receipt.put("all_scalars", scalars); run.unchanged(); run.receipt.put("status", "passed");
        }
    }

    public static List<String> dotCases() {
        List<String> result = new ArrayList<>(List.of("é", "🙂", "é🙂", "{é}", "{🙂!r}", "foo{x}tail", "", "{}", "{!r}", "{!s}", "{{", "}}", "é🙂{x!r}尾", "a{é}b", "{🙂}", "{é!s}", "{🙂!s}", "{é!r}", "{aé🙂!r}尾", "{{é}}🙂", "é{x}{y}🙂", "é{🙂!q}尾", "{é!}tail", "{🙂!r", "{é!rX}尾", "{🙂!", "{é{", "é}", "{é", "}é", "{x!r}é", "{x!s}🙂", "e\u0301{e\u0301}", "漢{漢!r}尾", "{é!🙂}", "{é!s}{{🙂}}"));
        for (int value : new int[]{0, 0x7f, 0x80, 0x7ff, 0x800, 0xd7ff, 0xe000, 0xffff, 0x10000, 0x10ffff}) {
            String scalar = new String(Character.toChars(value)); result.addAll(List.of(scalar, "{" + scalar + "}", "{" + scalar + "!r}", scalar + "{x}" + scalar));
        }
        return result;
    }

    private static void dotFormat(Path root, String konanc, String cargo, boolean allowDifferences) throws Exception {
        try (Execution run = new Execution(root, "dot-format-repair")) {
            Path fixtures = root.resolve("tools/translation-repair");
            run.snapshot(root.resolve("tmp/starlark_syntax/src/dot_format_parser.rs"), "original.rs");
            Path port = run.input(root.resolve("src/common/commonMain/kotlin/io/github/kotlinmania/starlarksyntax/dotformatparser/DotFormatParser.kt"));
            run.snapshot(fixtures.resolve("dot-format.rs"), "harness.rs");
            Path trace = run.input(fixtures.resolve("DotFormatTrace.kt"));
            List<String> cases = dotCases(); Json.write(run.output.resolve("cases.json"), cases);
            Path manifest = run.work.resolve("Cargo.toml");
            Files.writeString(manifest, """
                    [package]
                    name = "dot-format-repair-oracle"
                    version = "0.0.0"
                    edition = "2024"
                    [workspace]
                    [[bin]]
                    name = "oracle"
                    path = "harness.rs"
                    [dependencies]
                    anyhow = "=1.0.102"
                    dupe = "=0.9.1"
                    [profile.checked-release]
                    inherits = "release"
                    overflow-checks = true
                    debug-assertions = false
                    [profile.unchecked-debug]
                    inherits = "dev"
                    overflow-checks = false
                    debug-assertions = true
                    """);
            run.run("cargo-version", command(cargo, "--version"), 240); run.run("kotlin-version", command(konanc, "-version"), 240);
            run.run("compile-port", command(konanc, port, trace, "-Werror", "-entry", "portrepair.main", "-o", run.work.resolve("port")), 240);
            List<String> encoded = cases.stream().map(text -> HexFormat.of().formatHex(text.getBytes(StandardCharsets.UTF_8))).toList();
            List<String> kotlinCommand = new ArrayList<>(command(run.work.resolve("port.kexe"))); kotlinCommand.addAll(encoded);
            List<String> kotlin = Files.readAllLines(run.run("kotlin", kotlinCommand, 240)); require(kotlin.size() == cases.size() * 12, "Missing Kotlin trace observations");
            Map<String, Object> profiles = new LinkedHashMap<>(); run.receipt.put("profiles", profiles); boolean matches = true;
            for (String profile : List.of("dev", "release", "checked-release", "unchecked-debug")) {
                List<String> rustCommand = new ArrayList<>(command(cargo, "run", "--offline", "--quiet", "--manifest-path", manifest, "--target-dir", run.work.resolve("target"), "--profile", profile, "--")); rustCommand.addAll(encoded);
                List<String> rust = Files.readAllLines(run.run("rust-" + profile, rustCommand, 240)); require(rust.size() == kotlin.size(), "Missing Rust trace observations");
                List<Map<String, Object>> differences = new ArrayList<>(); Set<String> changed = new TreeSet<>();
                for (int i = 0; i < rust.size(); i++) if (!rust.get(i).equals(kotlin.get(i))) {
                    differences.add(Map.of("case", cases.get(i / 12), "step", i % 12, "rust", rust.get(i), "kotlin", kotlin.get(i))); changed.add(cases.get(i / 12));
                }
                profiles.put(profile, Map.of("rows", rust.size(), "cases", cases.size(), "matches", differences.isEmpty(), "different_cases", changed.size(), "differences", differences));
                matches &= differences.isEmpty(); System.out.println(profile + " " + cases.size() + " cases, " + differences.size() + " different observations");
            }
            run.run("rust-source-tests", command(cargo, "test", "--offline", "--quiet", "--manifest-path", manifest, "--target-dir", run.work.resolve("target")), 240);
            run.unchanged(); run.receipt.put("status", matches ? "passed" : "differences recorded"); require(matches || allowDifferences, "Rust/Kotlin trace differences; see results.json");
        }
    }

    public static String camel(String name) {
        String[] parts = name.replaceFirst("^_+", "").split("_", -1); StringBuilder result = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) if (!parts[i].isEmpty()) result.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1)); return result.toString();
    }

    private static int drift(Path project, String relative) throws Exception {
        Path kotlin = project.resolve(relative); require(Files.isDirectory(kotlin), "Missing Kotlin source root: " + kotlin);
        Path upstream; try (var entries = Files.list(project.resolve("tmp"))) { upstream = entries.filter(Files::isDirectory).sorted().findFirst().orElseThrow(() -> new IllegalArgumentException("No tmp/<crate> source")); }
        Pattern source = Pattern.compile("^\\s*//\\s*port-lint:\\s*source\\s+(\\S+)");
        Pattern rustName = Pattern.compile("^\\s*(?:pub(?:\\([^)]+\\))?\\s+)?(?:async\\s+)?(?:unsafe\\s+)?(?:extern\\s+(?:\"[^\"]+\"\\s+)?)?fn\\s+([A-Za-z_][A-Za-z0-9_]*)");
        Pattern kotlinName = Pattern.compile("^\\s*(?:(?:public|private|internal|protected|override|open|final|inline|infix|operator|tailrec|suspend|external|abstract)\\s+)*fun(?:\\s*<[^>]+>)?\\s+(?:[A-Za-z_][A-Za-z0-9_<>,?.\\s]*\\.\\s*)?`?([A-Za-z_][A-Za-z0-9_]*)`?");
        List<Map<String, Object>> rows = new ArrayList<>();
        try (var files = Files.walk(kotlin)) {
            for (Path path : files.filter(p -> p.toString().endsWith(".kt")).sorted().toList()) {
                List<String> lines = Files.readAllLines(path); String ref = null;
                for (String line : lines.stream().limit(20).toList()) { var match = source.matcher(line); if (match.find()) { ref = match.group(1); break; } }
                Map<String, Object> row = new LinkedHashMap<>(); row.put("file", project.relativize(path).toString());
                if (ref == null) { row.put("status", "port-lint source missing"); rows.add(row); continue; }
                Path rust = upstream.resolve(ref).normalize(); row.put("upstream", ref);
                if (!rust.startsWith(upstream) || !Files.isRegularFile(rust)) { row.put("status", "upstream source missing"); rows.add(row); continue; }
                Set<String> rs = new TreeSet<>(), kt = new TreeSet<>();
                for (String line : Files.readAllLines(rust)) { var match = rustName.matcher(line); if (match.find()) rs.add(match.group(1)); }
                for (String line : lines) { var match = kotlinName.matcher(line); if (match.find()) kt.add(match.group(1)); }
                List<String> rustOnly = rs.stream().filter(name -> !kt.contains(name) && !kt.contains(camel(name))).toList();
                List<String> kotlinOnly = kt.stream().filter(name -> !rs.contains(name) && rs.stream().noneMatch(r -> camel(r).equals(name))).toList();
                row.put("rust_only", rustOnly); row.put("kotlin_only", kotlinOnly); row.put("status", rustOnly.isEmpty() && kotlinOnly.isEmpty() ? "clean" : "drift"); rows.add(row);
            }
        }
        System.out.println(Json.stringify(Map.of("project", project.toString(), "scope", "Function-name inventory only; no behavioral parity claim", "files", rows))); return 0;
    }

    public static int run(String... args) throws Exception {
        require(args.length > 0, "Expected translation, dot-format, or drift"); String mode = args[0]; Map<String, String> options = new LinkedHashMap<>(); boolean allow = false;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--allow-differences")) { allow = true; continue; }
            require(i + 1 < args.length && Set.of("--root", "--project", "--kt-root", "--konanc", "--rustc", "--cargo").contains(args[i]), "Unknown or incomplete option"); options.put(args[i], args[++i]);
        }
        Path root = Path.of(options.getOrDefault("--root", options.getOrDefault("--project", "."))).toRealPath();
        if (mode.equals("drift")) return drift(root, options.getOrDefault("--kt-root", "src/common/commonMain/kotlin"));
        require(options.containsKey("--konanc"), "--konanc is required");
        if (mode.equals("translation")) translation(root, options.get("--konanc"), options.getOrDefault("--rustc", "rustc"));
        else if (mode.equals("dot-format")) dotFormat(root, options.get("--konanc"), options.getOrDefault("--cargo", "cargo"), allow);
        else throw new IllegalArgumentException("Unknown Starlark verifier mode: " + mode);
        return 0;
    }
}
