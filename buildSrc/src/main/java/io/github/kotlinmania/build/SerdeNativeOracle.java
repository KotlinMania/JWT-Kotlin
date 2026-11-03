package io.github.kotlinmania.build;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Direct Native production/oracle checks for serde and serde-json, without an interpreter. */
public final class SerdeNativeOracle {
    private SerdeNativeOracle() {}
    static final Set<String> PROFILES = Set.of("generated-cached-01", "generated-errors-01", "generated-rounding-01", "content-adoption-01", "content-borrow-01", "seed-adoption-01", "seed-place-prep-01");
    static final String SEED_GENERATED = "53ca4518e8e60cd801b1250ac6bbc40282fb353b552fbbf2f9c6682c6aa84d34";
    static final String SEED_RUST = "3f6e098c5bd314788370dcaf3ab0152fcd7feb6bcf36a9c51808938cd58071eb";
    static final String SEED_PREFIX = "// port-lint: source private/seed.rs\npackage io.github.kotlinmania.serdecore.priv\n\n";
    private static final String CORE = "serde_core/src/common/commonMain/kotlin/io/github/kotlinmania/serdecore/";
    static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
    static Map<String, Object> object(Object value) {
        require(value instanceof Map<?, ?>, "Expected JSON object"); Map<String, Object> result = new LinkedHashMap<>();
        ((Map<?, ?>) value).forEach((key, item) -> result.put(String.valueOf(key), item)); return result;
    }
    static String string(Object value) { require(value instanceof String, "Expected JSON string"); return (String) value; }
    static List<?> array(Object value) { require(value instanceof List<?>, "Expected JSON list"); return (List<?>) value; }
    static Map<String, Object> row(Object... pairs) { Map<String, Object> result = new LinkedHashMap<>(); for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]); return result; }
    static Map<String, Object> read(Path path) throws IOException { return object(Json.read(path)); }
    static String sha(Path path) throws IOException {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    static List<Path> files(Path root) throws IOException {
        require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS), "Required directory is missing: " + root);
        try (var stream = Files.walk(root)) {
            List<Path> result = new ArrayList<>();
            for (Path path : stream.sorted().toList()) { require(!Files.isSymbolicLink(path), "Symbolic-link input is unsupported: " + path); if (!Files.isDirectory(path)) { require(Files.isRegularFile(path), "Special input is unsupported: " + path); result.add(path); } }
            return result;
        }
    }
    static Map<String, String> hashes(List<Path> paths) throws IOException { Map<String, String> result = new TreeMap<>(); for (Path path : paths) result.put(path.toAbsolutePath().normalize().toString(), sha(path)); return result; }
    static void verifyHashes(Map<String, String> pins) throws IOException {
        for (var pin : pins.entrySet()) require(Files.isRegularFile(Path.of(pin.getKey())) && pin.getValue().equals(sha(Path.of(pin.getKey()))), "Input changed during validation: " + pin.getKey());
    }
    static Map<String, String> stringMap(Object value) { Map<String, String> result = new TreeMap<>(); object(value).forEach((key, item) -> result.put(key, string(item))); return result; }
    static List<Path> nativeInputs(Path receipt) throws IOException {
        List<Path> result = Files.readAllLines(receipt).stream().filter(line -> line.startsWith("NATIVE_INPUT=")).map(line -> Path.of(line.substring("NATIVE_INPUT=".length())).toAbsolutePath().normalize()).toList();
        require(!result.isEmpty(), "Native dependency receipt contains no NATIVE_INPUT entries: " + receipt); require(result.stream().distinct().count() == result.size(), "Native dependency receipt contains duplicate paths");
        for (Path path : result) require(Files.isRegularFile(path), "Missing recorded Native dependency: " + path); return result;
    }
    static Path ownedOutput(Path root, Path output) throws IOException {
        Path repo = root.toAbsolutePath().normalize(), out = output.toAbsolutePath().normalize(), build = repo.resolve("build");
        require(Files.isDirectory(repo), "Owning repository is missing: " + repo);
        require(out.startsWith(build) && !out.equals(build), "Oracle output must be inside owning repository build/: " + out);
        for (Path path = out; path != null && path.startsWith(repo); path = path.getParent()) require(!Files.isSymbolicLink(path), "Oracle output cannot traverse symbolic links");
        require(!Files.exists(out, LinkOption.NOFOLLOW_LINKS), "Oracle output must be a new directory: " + out); Files.createDirectories(out); return out;
    }
    static List<String> nativeFlags(Path compiler, boolean returnChecker, boolean expectActual) {
        List<String> result = new ArrayList<>(List.of(compiler.toString(), "-target", "macos_arm64", "-Werror"));
        if (expectActual) result.add("-Xexpect-actual-classes"); if (returnChecker) result.add("-Xreturn-value-checker=full");
        result.addAll(List.of("-Xoverride-konan-properties=airplaneMode=true", "-Xbackend-threads=1")); return result;
    }
    static List<String> append(List<String> values, Object... more) { List<String> result = new ArrayList<>(values); for (Object value : more) result.add(value.toString()); return result; }
    static List<String> libraries(List<Path> paths) { List<String> result = new ArrayList<>(); for (Path path : paths) result.addAll(List.of("-library", path.toString())); return result; }
    static List<String> combine(List<String> first, List<String> second) { List<String> result = new ArrayList<>(first); result.addAll(second); return result; }
    static int observations(byte[] bytes) { return new String(bytes, StandardCharsets.UTF_8).lines().toList().size(); }
    static boolean endsWith(byte[] value, byte[] suffix) { return value.length >= suffix.length && Arrays.equals(value, value.length - suffix.length, value.length, suffix, 0, suffix.length); }
    static void compare(Path diff, byte[] expected, byte[] actual) throws IOException {
        if (Arrays.equals(expected, actual)) { Files.writeString(diff, ""); return; }
        List<String> before = new String(expected, StandardCharsets.UTF_8).lines().toList(), after = new String(actual, StandardCharsets.UTF_8).lines().toList();
        int first = 0; while (first < before.size() && first < after.size() && before.get(first).equals(after.get(first))) first++;
        StringBuilder text = new StringBuilder("--- canonical Rust observations\n+++ actual Native observations\n@@ first differing line " + (first + 1) + " @@\n");
        for (int i = Math.max(0, first - 2); i < Math.min(Math.max(before.size(), after.size()), first + 12); i++) {
            if (i < before.size()) text.append('-').append(before.get(i)).append('\n'); if (i < after.size()) text.append('+').append(after.get(i)).append('\n');
        }
        text.append("Expected bytes: ").append(expected.length).append("; actual bytes: ").append(actual.length).append('\n'); Files.writeString(diff, text);
        throw new IllegalArgumentException("Native observations differ from canonical Rust output; see " + diff);
    }

    static final class Context {
        final String profile; final Path root, workspace, here, out, compiler; final Map<String, String> options;
        final Map<String, Object> report = row("status", "running", "commands", new ArrayList<Map<String, Object>>(), "target", "macos_arm64");
        final List<Map<String, Object>> commands = new ArrayList<>(); final Map<String, String> pins = new TreeMap<>(); final Map<Path, Map<String, String>> trees = new LinkedHashMap<>(); final List<Path> generated = new ArrayList<>();
        Context(String profile, Path root, Path out, Map<String, String> options) throws IOException {
            this.profile = profile; this.root = root.toAbsolutePath().normalize(); this.workspace = this.root.getParent(); this.here = this.root.resolve("verification/" + profile); this.out = ownedOutput(this.root, out); this.options = options;
            this.compiler = Path.of(System.getProperty("user.home"), ".konan/kotlin-native-prebuilt-macos-aarch64-2.4.21/bin/konanc");
            report.put("profile", profile); report.put("compiler", compiler.toString()); report.put("commands", commands); Files.createDirectories(this.out.resolve("tmp"));
        }
        void pin(Path... paths) throws IOException { pins.putAll(hashes(List.of(paths))); }
        void pin(List<Path> paths) throws IOException { pins.putAll(hashes(paths)); }
        Map<String, String> inventory(Path directory) throws IOException {
            Map<String, String> result = new TreeMap<>(); List<Path> paths = files(directory); require(!paths.isEmpty(), "Empty production input directory: " + directory);
            for (Path path : paths) result.put(directory.relativize(path).toString().replace('\\', '/'), sha(path)); trees.put(directory, result); pin(paths); return result;
        }
        void verify() throws IOException {
            verifyHashes(pins);
            for (var tree : trees.entrySet()) { Map<String, String> actual = new TreeMap<>(); for (Path path : files(tree.getKey())) actual.put(tree.getKey().relativize(path).toString().replace('\\', '/'), sha(path)); require(actual.equals(tree.getValue()), "Production input inventory changed: " + tree.getKey()); }
        }
        void save() throws IOException { Json.write(out.resolve("validation.json"), report); }
        Path source(String label, String body, String extension) throws IOException {
            Path path = out.resolve("generated/" + label + extension); Files.createDirectories(path.getParent()); Files.writeString(path, body); generated.add(path); pin(path);
            report.put("generated_recipe_hashes", hashes(generated)); return path;
        }
        byte[] run(String label, List<String> args) throws Exception { return run(label, args, Map.of(), null); }
        byte[] run(String label, List<String> args, Map<String, String> environment, Path input) throws Exception {
            verify(); Path stdout = out.resolve(label + ".stdout"), stderr = out.resolve(label + ".stderr");
            ProcessBuilder builder = new ProcessBuilder(args).directory(root.toFile()).redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
            builder.environment().putAll(environment); builder.environment().put("TMPDIR", out.resolve("tmp").toString());
            String java = builder.environment().getOrDefault("JAVA_TOOL_OPTIONS", ""); builder.environment().put("JAVA_TOOL_OPTIONS", java + " -Djava.io.tmpdir=" + out.resolve("tmp"));
            if (input != null) builder.redirectInput(input.toFile()); Process process = builder.start(); if (input == null) process.getOutputStream().close();
            int exit;
            try { exit = process.waitFor(); }
            catch (InterruptedException interrupted) { process.destroyForcibly(); Thread.currentThread().interrupt(); throw interrupted; }
            commands.add(row("label", label, "argv", args, "exit", exit)); save(); verify();
            String diagnostic = Files.readString(stderr); require(exit == 0, label + " failed with exit " + exit + ": " + diagnostic.substring(Math.max(0, diagnostic.length() - 6000)));
            System.out.println(label + " passed"); return Files.readAllBytes(stdout);
        }
        List<Path> dependencies(boolean json) throws IOException {
            Path receipt = json ? root.resolve("verification/generated-shift-01/native-inputs.txt") : workspace.resolve("rust-kotlin/verification/production-adoption-01/serde-native-attempt-02.log");
            pin(receipt); List<Path> dependencies = nativeInputs(receipt); pin(dependencies); report.put(json ? "dependencies" : "library_hashes", hashes(dependencies)); return dependencies;
        }
        void compareSource(String label, String source, List<String> flags, byte[] expected, Path input) throws Exception {
            Path file = source(label, source, ".kt"); run(label + "-compile", append(flags, file, "-o", out.resolve(label)));
            byte[] actual = run(label, List.of(out.resolve(label + ".kexe").toString()), Map.of(), input); compare(out.resolve(label + ".diff"), expected, actual);
            report.put(label, row("observations", observations(actual), "matches_rust", true));
        }
        void cleanupSources() throws IOException {
            for (Path file : generated) Files.deleteIfExists(file);
            if (Files.isDirectory(out.resolve("generated"))) Files.delete(out.resolve("generated"));
            report.put("generated_source_copies_retained", false);
        }
    }

    static String cachedAdapter(String original) {
        String result = original.replace("__rtkImpl0.get_powers()", "ExtendedFloatCache.getPowers()").replace(".len()", ".length.toULong()").replace(".small_int", ".smallInt");
        for (String[] pair : List.of(new String[] {"powers.get_small(index)", "powers.getSmall(toLegacyIndex(index))"}, new String[] {"powers.get_large(index)", "powers.getLarge(toLegacyIndex(index))"}, new String[] {"powers.get_small_int(index)", "powers.getSmallInt(toLegacyIndex(index))"}, new String[] {"values.get_extended_float(index)", "values.getExtendedFloat(toLegacyIndex(index))"}, new String[] {"ExtendedFloatArray(mant,exp)", "ExtendedFloatArray(mant.toULongArray(),exp.toIntArray())"})) result = result.replace(pair[0], pair[1]);
        result = replaceFirst(result, "    val result=try", "    if (!legacyIndex(index)) return\n    val result=try").replace("    for(index in indices) {", "    for(index in indices) {\n        if (!legacyIndex(index)) continue");
        return "@file:OptIn(kotlin.ExperimentalUnsignedTypes::class)\nimport io.github.kotlinmania.serdejson.lexical.*\nimport io.github.kotlinmania.serdejson.lexical.generated.cached.__rtkPanic\nprivate fun legacyIndex(index:ULong) = index <= Int.MAX_VALUE.toULong() || index == ULong.MAX_VALUE\nprivate fun toLegacyIndex(index:ULong):Int { require(legacyIndex(index)); return index.toInt() }\n" + result;
    }
    static String replaceFirst(String source, String old, String replacement) { int start = source.indexOf(old); require(start >= 0, "Oracle recipe anchor missing: " + old); return source.substring(0, start) + replacement + source.substring(start + old.length()); }
    static byte[] legacyCachedExpected(byte[] expected) {
        StringBuilder result = new StringBuilder(); BigInteger maximum = new BigInteger("18446744073709551615");
        for (String line : new String(expected, StandardCharsets.UTF_8).split("(?<=\\n)", -1)) {
            if (line.isEmpty()) continue; String[] columns = line.split("\\|", -1); boolean include = true;
            if (Set.of("small", "large", "int", "array").contains(columns[0])) { require(columns.length >= 2, "Malformed cached oracle index"); BigInteger index = new BigInteger(columns[1]); include = index.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) <= 0 || index.equals(maximum); }
            if (include) result.append(line);
        }
        return result.toString().getBytes(StandardCharsets.UTF_8);
    }
    static String errorsAdapter(String original) {
        return original.replace("import io.github.kotlinmania.serdejson.lexical.F32Float", "import io.github.kotlinmania.serdejson.lexical.*")
            .replace("__rtkImpl0.error_scale()", "ULongFloatErrors.errorScale()").replace("__rtkImpl0.error_halfscale()", "ULongFloatErrors.errorHalfscale()")
            .replace("__rtkImpl0.error_is_accurate(count,fp,if(width==32) F32Float else F64Float)", "errorIsAccurate(if(width==32) F32Float else F64Float,count,fp)");
    }
    static String roundingAdapter(String original) {
        String result = "import io.github.kotlinmania.serdejson.lexical.ExtendedFloat\n" + original;
        for (String[] pair : List.of(new String[] {"round_nearest_tie_even", "roundNearestTieEven"}, new String[] {"round_nearest", "roundNearest"}, new String[] {"round_downward", "roundDownward"}, new String[] {"internal_n_mask", "internalNMask"}, new String[] {"lower_n_halfway", "lowerNHalfway"}, new String[] {"lower_n_mask", "lowerNMask"}, new String[] {"nth_bit", "nthBit"}, new String[] {"tie_even", "tieEven"})) result = "import io.github.kotlinmania.serdejson.lexical." + pair[1] + "\n" + result.replace(pair[0] + "(", pair[1] + "(");
        for (String[] pair : List.of(new String[] {"round_to_float", "roundToFloat"}, new String[] {"round_to_native", "roundToNative"})) result = "import io.github.kotlinmania.serdejson.lexical." + pair[1] + "\n" + result.replace(pair[0] + "(fp, callback, provider)", pair[1] + "(fp, provider, callback)");
        return "import io.github.kotlinmania.serdejson.lexical.avoidOverflow\n" + result.replace("avoid_overflow(", "avoidOverflow(");
    }
    static String rustCallbackTrace(String original) {
        for (String anchor : List.of("let mut observed = None;", "observed = Some(shift);", "|{calls}|{:?}\",", "fp.mant, fp.exp, observed);")) require(original.contains(anchor), "Callback Rust recipe anchor missing: " + anchor);
        return original.replace("let mut observed = None;", "let mut observed = None;\n                    let mut callback_state = None;")
            .replace("observed = Some(shift);", "observed = Some(shift);\n                            callback_state = Some((value.mant, value.exp));")
            .replace("|{calls}|{:?}\",", "|{calls}|{:?}|{:?}\",").replace("fp.mant, fp.exp, observed);", "fp.mant, fp.exp, observed, callback_state);");
    }
    static String kotlinCallbackTrace(String original) {
        for (String anchor : List.of("var observed: Int? = null", "observed = shift", "?: \"None\"}\")")) require(original.contains(anchor), "Callback Kotlin recipe anchor missing: " + anchor);
        return original.replace("var observed: Int? = null", "var observed: Int? = null\n            var callbackState: String? = null")
            .replace("observed = shift", "observed = shift\n                check(value === fp)\n                callbackState = \"(${value.mant}, ${value.exp})\"")
            .replace("?: \"None\"}\")", "?: \"None\"}|${callbackState?.let { \"Some($it)\" } ?: \"None\"}\")");
    }
    static byte[] stripCallbackTrace(byte[] traced) {
        List<String> lines = new String(traced, StandardCharsets.UTF_8).lines().toList(); StringBuilder result = new StringBuilder();
        for (String line : lines) { if (line.startsWith("generic|")) { int end = line.lastIndexOf('|'); require(end > "generic|".length(), "Missing callback-entry trace"); line = line.substring(0, end); } result.append(line).append('\n'); }
        return result.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void generated(Context c) throws Exception {
        String kind = c.profile.substring("generated-".length(), c.profile.length() - 3); boolean rounding = kind.equals("rounding"), errors = kind.equals("errors");
        Path foundation = c.workspace.resolve("rust-kotlin/verification/foundation-batch-04"), generation = foundation.resolve(rounding ? "rounding-06" : errors ? "errors-05" : "cached-03");
        Path raw = generation.resolve("generated/generated.kt"), installed = c.root.resolve("src/common/commonMain/kotlin/io/github/kotlinmania/serdejson/lexical/generated/" + kind + "/" + Character.toUpperCase(kind.charAt(0)) + kind.substring(1) + ".kt");
        c.pin(raw, installed); require(endsWith(Files.readAllBytes(installed), Files.readAllBytes(raw)), "Installed generated body differs from canonical generated source");
        c.report.put("generated", row("raw_sha256", sha(raw), "installed", installed.toString(), "installed_sha256", sha(installed), "raw_body_unchanged", true));
        if (rounding) { Path adapter = c.root.resolve("src/common/commonMain/kotlin/io/github/kotlinmania/serdejson/lexical/Rounding.kt"); c.pin(adapter); Map<String, Object> evidence = object(c.report.get("generated")); evidence.put("adapter_sha256", sha(adapter)); c.report.put("generated", evidence); c.report.put("new_adoptions", 0); }
        Path klib = c.root.resolve("build/classes/kotlin/macosArm64/main/klib/serde-json-kotlin"); c.report.put("production_klib", c.inventory(klib)); List<Path> dependencies = c.dependencies(true);
        Path oracle = rounding ? foundation.resolve("concrete-methods-01/Oracle.kt") : generation.resolve("Oracle.kt"), expectedFile = rounding ? foundation.resolve("rounding-02/rust.stdout") : generation.resolve("rust.stdout");
        c.pin(oracle, expectedFile); String original = Files.readString(oracle); byte[] expected = Files.readAllBytes(expectedFile);
        List<String> flags = combine(append(nativeFlags(c.compiler, false, false), "-friend-modules", klib, "-library", klib), libraries(dependencies)); Path cases = errors ? generation.resolve("cases.txt") : null; if (cases != null) c.pin(cases);
        c.compareSource("raw-production", "import io.github.kotlinmania.serdejson.lexical.generated." + kind + ".*\n" + original, flags, expected, cases);
        String adapter = rounding ? roundingAdapter(original) : errors ? errorsAdapter(original) : cachedAdapter(original);
        c.compareSource(errors || rounding ? "adapter-production" : "legacy-production", adapter, flags, errors || rounding ? expected : legacyCachedExpected(expected), cases);
        if (rounding) {
            Path rust = foundation.resolve("rounding-02/Oracle.rs"), validation = foundation.resolve("shift-01/rust-validation.json"); c.pin(rust, validation);
            Path source = c.source("CallbackOracle", rustCallbackTrace(Files.readString(rust)), ".rs"); String rustc = string(read(validation).get("compiler")); require(!rustc.contains("/automation-artifacts/"), "Recorded Rust compiler was removed with forbidden storage; restore the canonical compiler provenance in its owning repository");
            c.run("callback-rust-compile", List.of(rustc, "--edition=2021", "--cfg", "feature=\"std\"", "--cfg", "feature=\"alloc\"", "--cfg", "fast_arithmetic=\"64\"", "-C", "overflow-checks=on", "-C", "debug-assertions=on", "-C", "panic=unwind", source.toString(), "-o", c.out.resolve("callback-rust").toString()));
            byte[] traced = c.run("callback-rust", List.of(c.out.resolve("callback-rust").toString())); compare(c.out.resolve("callback-original-observations.diff"), expected, stripCallbackTrace(traced));
            c.compareSource("callback-adapter", kotlinCallbackTrace(adapter), flags, traced, null); c.report.put("new_adoptions", 1);
            c.report.put("boundaries", List.of("Complete raw production and existing adapter API match canonical checked usize64 Rust behavior", "Same frozen cases compare callback-entry scalar state; adapter callbacks receive the original Kotlin object", "Callback calls, arguments, mutations and exceptional copyback preserved with actual f32/f64 providers", "No allocation, custom Drop, escaping loans, Swift ABI or other-target runtime equivalence claim"));
        } else {
            c.report.put("adopted_whole_files", 1);
            c.report.put("boundaries", errors ? List.of("Existing entry points forward the explicit FloatTraits provider; original f32/f64 providers verified", "Scalar snapshot adapter preserves shared input values; callers must not concurrently mutate shared state", "Overflow/debug checks on, panic unwind, usize64; masks use production helpers checked against original Rust", "No allocation, full dependency translation, Swift ABI or other-platform runtime equivalence claim")
                : List.of("Raw API checks all usize64 cases against Rust; Int adapter checks representable indices plus -1 mapped to usize::MAX", "Primitive-array adapter retains read-only list views; backing cached arrays must remain immutable for the view lifetime", "Explicit static binding reuses generated cached_float80 values; no new table or handwritten lookup implementation", "No all-target runtime, layout/allocation or Swift ABI equivalence claim"));
        }
    }

    private static List<Path> kotlinFiles(Path root) throws IOException { return Files.exists(root) ? files(root).stream().filter(path -> path.toString().endsWith(".kt")).toList() : List.of(); }
    static List<Path> selectedRootTests(List<Path> tests, boolean focused) { return focused ? tests.stream().filter(path -> !path.getFileName().toString().equals("TestRoundtripTest.kt")).toList() : tests; }
    static void validateReuse(Map<String, Object> previous, Map<String, String> sources, Map<String, String> dependencies, Path directory) throws IOException {
        require("passed".equals(previous.get("status")), "Cannot reuse a failed or incomplete build"); Map<String, String> previousSources = stringMap(previous.get("source_hashes"));
        for (var pin : sources.entrySet()) require(pin.getValue().equals(previousSources.get(pin.getKey())), "Source changed since the compiled libraries: " + pin.getKey());
        require(dependencies.equals(stringMap(previous.get("dependency_hashes"))), "Dependency changed since the compiled libraries");
        require(sha(directory.resolve("serde-core.klib")).equals(previous.get("production_core_sha256")) && sha(directory.resolve("serde.klib")).equals(previous.get("production_root_sha256")), "Reused production library differs from passing receipt");
        require(array(previous.get("commands")).stream().map(SerdeNativeOracle::object).anyMatch(command -> "core-tests".equals(command.get("label")) && Integer.valueOf(0).equals(command.get("exit"))), "Reuse requires a prior passing core-test command");
    }
    private static void content(Context c) throws Exception {
        boolean focused = c.options.containsKey("content-regressions"); c.report.put("full_suite", !focused); c.report.put("validation_mode", focused ? "Focused Content regressions; excludes network-address roundtrip tests and is not full-suite acceptance" : "Direct Native compilation of complete current production source sets and all existing Native test suites; no Maven resolution");
        List<Path> common = kotlinFiles(c.root.resolve("serde_core/src/common/commonMain/kotlin")), platform = kotlinFiles(c.root.resolve("serde_core/src/native/nativeMain/kotlin"));
        Path candidates = c.workspace.resolve("rust-lang-kotlin/candidates"); List<Path> kernel = List.of(candidates.resolve("core/kotlin/org/rustlang/core/slice/SharedByteSlice.kt"), candidates.resolve("core/kotlin/org/rustlang/core/str/Utf8ByteOperations.kt"), candidates.resolve("alloc/kotlin/org/rustlang/alloc/borrow/Utf8Cow.kt"));
        List<Path> dependencies = c.dependencies(false); Map<String, String> dependencyHashes = hashes(dependencies); c.report.put("dependency_hashes", dependencyHashes);
        List<Path> rootSources = kotlinFiles(c.root.resolve("src/common/commonMain/kotlin")); List<Path> coreTests = new ArrayList<>(kotlinFiles(c.root.resolve("serde_core/src/common/commonTest/kotlin"))); coreTests.addAll(kotlinFiles(c.root.resolve("serde_core/src/apple/macos/macosArm64Test/kotlin")));
        List<Path> rootTests = new ArrayList<>(kotlinFiles(c.root.resolve("src/common/commonTest/kotlin"))); rootTests.addAll(kotlinFiles(c.root.resolve("src/apple/macos/macosArm64Test/kotlin"))); rootTests = selectedRootTests(rootTests, focused);
        if (focused) c.report.put("excluded_tests", Map.of("src/common/commonTest/kotlin/io/github/kotlinmania/serde/TestRoundtripTest.kt", "Two network-address roundtrip tests require serde-test transitive dependencies; excluded only from explicitly requested focused Content validation."));
        List<Path> sources = new ArrayList<>(); for (List<Path> group : List.of(common, platform, kernel, rootSources, coreTests, rootTests, List.of(c.here.resolve("Oracle.kt")))) sources.addAll(group);
        c.pin(sources); Map<String, String> sourceHashes = hashes(sources); c.report.put("source_hashes", sourceHashes); Path expectedPath = c.workspace.resolve("rust-kotlin/verification/foundation-batch-04/content-01/rust.stdout"); c.pin(expectedPath);
        List<String> flags = nativeFlags(c.compiler, true, true), libs = libraries(dependencies); Path core = c.out.resolve("serde-core.klib"), root = c.out.resolve("serde.klib");
        if (c.options.containsKey("reuse-build")) {
            Path previousDirectory = Path.of(c.options.get("reuse-build")).toAbsolutePath().normalize(); Map<String, Object> previous = read(previousDirectory.resolve("validation.json")); validateReuse(previous, sourceHashes, dependencyHashes, previousDirectory);
            c.pin(previousDirectory.resolve("validation.json"), previousDirectory.resolve("serde-core.klib"), previousDirectory.resolve("serde.klib"), previousDirectory.resolve("core-tests.stdout"));
            Files.copy(previousDirectory.resolve("serde-core.klib"), core, StandardCopyOption.COPY_ATTRIBUTES); Files.copy(previousDirectory.resolve("serde.klib"), root, StandardCopyOption.COPY_ATTRIBUTES);
            c.report.put("reused_build", previousDirectory.toString()); c.report.put("prior_core_tests_stdout_sha256", sha(previousDirectory.resolve("core-tests.stdout")));
        } else {
            List<Path> commonSources = new ArrayList<>(common); commonSources.addAll(kernel);
            List<String> compile = append(flags, "-produce", "library", "-module-name", "serde_core", "-Xmulti-platform", "-Xcommon-sources=" + String.join(",", commonSources.stream().map(Path::toString).toList()));
            for (List<Path> group : List.of(common, platform, kernel)) for (Path path : group) compile.add(path.toString());
            c.run("core-compile", append(combine(compile, libs), "-o", core));
            c.run("root-compile", append(combine(combine(append(flags, "-produce", "library", "-module-name", "serde"), rootSources.stream().map(Path::toString).toList()), libs), "-library", core, "-o", root));
            c.run("core-tests-compile", append(combine(combine(append(flags, "-generate-test-runner"), coreTests.stream().map(Path::toString).toList()), libs), "-library", core, "-friend-modules", core, "-o", c.out.resolve("core-tests")));
            c.run("core-tests", List.of(c.out.resolve("core-tests.kexe").toString()));
        }
        c.pin(core, root);
        c.run("root-tests-compile", append(combine(combine(append(flags, "-generate-test-runner"), rootTests.stream().map(Path::toString).toList()), libs), "-library", root, "-library", core, "-friend-modules", root, "-o", c.out.resolve("root-tests")));
        c.run("root-tests", List.of(c.out.resolve("root-tests.kexe").toString()));
        c.run("oracle-compile", append(combine(append(flags, c.here.resolve("Oracle.kt"), "-library", core, "-friend-modules", core), libs), "-o", c.out.resolve("oracle")));
        byte[] actual = c.run("oracle", List.of(c.out.resolve("oracle.kexe").toString())); compare(c.out.resolve("compare.diff"), Files.readAllBytes(expectedPath), actual);
        c.report.putAll(row("observations", observations(actual), "production_core_sha256", sha(core), "production_root_sha256", sha(root)));
    }

    private static void cargoOracle(Context c) throws Exception {
        boolean borrow = c.profile.equals("content-borrow-01"); c.report.put("new_translated_files", 0); if (!borrow) c.report.put("new_adopted_files", 0);
        Path baseline = c.workspace.resolve("rust-lang-kotlin/verification/cow-utf8-native-01/baseline.json"); Map<String, Object> rust = read(baseline); c.pin(baseline);
        String rustc = c.options.getOrDefault("rustc", string(rust.get("rustc"))); require(!rustc.contains("/automation-artifacts/"), "Recorded Rust compiler used removed storage; provide --rustc with the same pinned revision in a supported toolchain location");
        byte[] version = c.run("rust-version", List.of(rustc, "-Vv")); require(new String(version, StandardCharsets.UTF_8).contains(string(rust.get("rust_revision"))), "Rust compiler revision differs from canonical evidence");
        String rustflags = (borrow ? "--cfg feature=\"std\" --cfg feature=\"alloc\" --check-cfg=cfg(feature,values(\"std\",\"alloc\")) " : "") + "-C overflow-checks=on -C debug-assertions=yes -C panic=unwind -Dwarnings";
        Map<String, String> environment = Map.of("RUSTC", rustc, "RUSTFLAGS", rustflags); c.report.put(borrow ? "compiler_environment" : "rust_environment", environment);
        List<Path> sources = new ArrayList<>(List.of(c.here.resolve("Oracle.rs"), c.here.resolve("Oracle.kt"), c.here.resolve("Cargo.toml"), c.here.resolve("Cargo.lock")));
        Path tests = c.root.resolve("serde_core/src/common/commonTest/kotlin/io/github/kotlinmania/serdecore/de/MutablePlaceTest.kt");
        if (borrow) sources.addAll(List.of(c.root.resolve("tmp/serde/serde/src/private/de.rs"), c.root.resolve("tmp/serde/serde/src/core/private/content.rs"), c.root.resolve(CORE + "priv/ContentVisitor.kt")));
        else sources.addAll(List.of(c.root.resolve(CORE + "de/MutablePlace.kt"), c.root.resolve(CORE + "de/Deserialize.kt"), c.root.resolve(CORE + "de/Impls.kt"), tests, c.root.resolve("tmp/serde/serde_core/src/de/impls.rs"), c.root.resolve("tmp/serde/serde_core/src/private/seed.rs")));
        c.pin(sources); c.report.put("sources", hashes(sources)); List<Path> dependencies = c.dependencies(false);
        Path core = c.root.resolve("serde_core/build/classes/kotlin/macosArm64/main/klib/serde-core"); c.report.put("production_klib", c.inventory(core)); c.report.put("libraries", hashes(dependencies));
        c.run("rust-compile", List.of("cargo", "build", "--offline", "--locked", "--quiet", "--manifest-path", c.here.resolve("Cargo.toml").toString(), "--target", "aarch64-apple-darwin", "--target-dir", c.out.resolve("cargo-target").toString()), environment, null);
        byte[] expected = c.run("rust", List.of(c.out.resolve("cargo-target/aarch64-apple-darwin/debug/" + (borrow ? "serde-content-borrow-oracle" : "serde-writable-place-oracle")).toString()));
        List<String> flags = append(nativeFlags(c.compiler, !borrow, false), c.here.resolve("Oracle.kt")); if (borrow) flags.addAll(List.of("-friend-modules", core.toString())); else flags.add(tests.toString());
        c.run("native-compile", append(combine(append(flags, "-library", core), libraries(dependencies)), "-o", c.out.resolve("native")));
        byte[] actual = c.run("native", List.of(c.out.resolve("native.kexe").toString())); compare(c.out.resolve("compare.diff"), expected, actual);
        c.report.putAll(row("observations", observations(actual), "rust_target", "aarch64-apple-darwin", "scope", borrow ? "Actual Rust ContentVisitor + ContentRefDeserializer clone versus actual production serde-core KLIB; whole ByteArray borrowing only" : "Actual Rust Vec in-place implementation versus production Kotlin mutable-list place overload; error specialization and provider panic boundaries remain separately documented"));
    }

    private static void seed(Context c) throws Exception {
        c.report.put("generated_body_compiled_separately", false); Path raw = c.workspace.resolve("rust-kotlin/verification/foundation-batch-04/seed-01");
        Path generated = raw.resolve("generated/generated.kt"), installed = c.root.resolve(CORE + "priv/Seed.kt"), frozen = c.root.resolve("tmp/serde/serde/src/core/private/seed.rs");
        require(sha(generated).equals(SEED_GENERATED), "Canonical generated Seed hash mismatch"); require(Arrays.equals(Files.readAllBytes(installed), (SEED_PREFIX + Files.readString(generated)).getBytes(StandardCharsets.UTF_8)), "Installed Seed must equal the exact generated body and package prefix"); require(sha(frozen).equals(SEED_RUST), "Frozen Rust Seed hash mismatch");
        Path originalReceipt = raw.resolve("check-02/validation.json"); Map<String, Object> original = read(originalReceipt); require("passed".equals(original.get("status")), "Canonical Seed check did not pass"); Map<String, String> originalPins = stringMap(original.get("sources")); verifyHashes(originalPins); c.pins.putAll(originalPins);
        require(Arrays.equals(Files.readAllBytes(c.here.resolve("Oracle.kt")), ("import io.github.kotlinmania.serdecore.priv.InPlaceSeed\n" + Files.readString(raw.resolve("Oracle.kt"))).getBytes(StandardCharsets.UTF_8)), "Installed Seed oracle differs from canonical oracle");
        Path prep = c.root.resolve("verification/seed-place-prep-01"), prepReceipt = prep.resolve("validation.json"), rustVec = c.root.resolve("tmp/serde/serde_core/src/de/impls.rs");
        Map<String, Object> previous = read(prepReceipt); require(sha(rustVec).equals(object(previous.get("sources")).get(rustVec.toString())), "Rust Vec in-place source changed");
        Path tests = c.root.resolve("serde_core/src/common/commonTest/kotlin/io/github/kotlinmania/serdecore/de/MutablePlaceTest.kt");
        List<Path> sources = List.of(installed, c.root.resolve(CORE + "de/Impls.kt"), tests, c.here.resolve("Oracle.kt"), prep.resolve("Oracle.kt"), frozen, rustVec);
        c.pin(sources); c.pin(generated, raw.resolve("Oracle.kt"), originalReceipt, prepReceipt); c.report.put("source_hashes", hashes(sources));
        Path core = c.root.resolve("serde_core/build/classes/kotlin/macosArm64/main/klib/serde-core"); c.report.put("production_klib", c.inventory(core)); List<Path> dependencies = c.dependencies(false);
        List<String> flags = combine(append(nativeFlags(c.compiler, true, false), "-library", core), libraries(dependencies)); Map<String, Integer> counts = new LinkedHashMap<>();
        for (String label : List.of("seed", "collection")) {
            Path expected = label.equals("seed") ? raw.resolve("check-02/rust.stdout") : prep.resolve("rust.stdout"); c.pin(expected);
            List<String> command = append(flags, label.equals("seed") ? c.here.resolve("Oracle.kt") : prep.resolve("Oracle.kt")); if (label.equals("collection")) command.add(tests.toString());
            c.run(label + "-compile", append(command, "-o", c.out.resolve(label))); byte[] actual = c.run(label, List.of(c.out.resolve(label + ".kexe").toString())); compare(c.out.resolve(label + ".diff"), Files.readAllBytes(expected), actual); counts.put(label, observations(actual));
        }
        require(counts.equals(Map.of("seed", 28, "collection", 9)), "Unexpected Seed/collection observation counts: " + counts);
        c.report.putAll(row("observations", counts, "scope", "Installed production Seed and its production Vec in-place consumer versus canonical Rust outputs; oracle compile inputs contain no generated Seed source copy"));
    }

    static Map<String, String> options(String... args) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) { String key = args[i]; require(key.startsWith("--"), "Expected named option: " + key); key = key.substring(2); require(!result.containsKey(key), "Duplicate option: " + key);
            if (key.equals("content-regressions")) result.put(key, "true"); else { require(Set.of("root", "out", "profile", "reuse-build", "rustc").contains(key), "Unrecognized option: " + key); require(i + 1 < args.length && !args[i + 1].startsWith("--"), "Value required: " + key); result.put(key, args[++i]); }
        }
        for (String name : List.of("profile", "root", "out")) require(result.containsKey(name), "Missing --" + name); String profile = result.get("profile"); require(PROFILES.contains(profile), "Unknown oracle profile: " + profile);
        require(profile.equals("content-adoption-01") || !result.containsKey("reuse-build") && !result.containsKey("content-regressions"), "Reuse/focused options apply only to content-adoption-01");
        require(Set.of("content-borrow-01", "seed-place-prep-01").contains(profile) || !result.containsKey("rustc"), "--rustc relocation requires a profile with a pinned Rust revision"); return result;
    }
    public static int run(String... args) throws Exception {
        Map<String, String> options = options(args); Context c = new Context(options.get("profile"), Path.of(options.get("root")), Path.of(options.get("out")), options);
        try {
            c.pin(c.compiler); c.report.put("compiler_sha256", sha(c.compiler));
            if (c.profile.startsWith("generated-")) generated(c); else if (c.profile.equals("content-adoption-01")) content(c); else if (c.profile.equals("seed-adoption-01")) seed(c); else cargoOracle(c);
            c.verify(); c.report.put("status", "passed"); c.report.put("input_hashes", c.pins); return 0;
        } catch (Exception failure) { c.report.put("status", "failed"); c.report.put("error", String.valueOf(failure.getMessage())); throw failure; }
        finally {
            try { c.cleanupSources(); }
            catch (IOException failure) { c.report.put("status", "failed"); c.report.put("cleanup_error", failure.toString()); throw failure; }
            finally { c.save(); }
        }
    }
    public static void main(String[] args) throws Exception { System.exit(run(args)); }
}
