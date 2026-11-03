package io.github.kotlinmania.build;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Project-specific named Swift behaviors and their existing effective compiler policies. */
public final class SwiftTestVerifier {
    private SwiftTestVerifier() {}

    private record Profile(String module, Map<String, String> cases, boolean strictCounts, boolean compiler,
                           boolean configurationCount, boolean flexibleNames, boolean wordBoundary,
                           Set<String> requiredModules) {}

    private static Map<String, String> cases(String... pairs) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put(pairs[i], pairs[i + 1]);
        return Collections.unmodifiableMap(result);
    }

    private static Profile profile(String module, boolean strict, boolean configurationCount, Map<String, String> cases) {
        return new Profile(module, cases, strict, true, configurationCount, false, false,
                Set.of(module, "KotlinRuntimeSupport", "KotlinCoroutineSupport", "SwiftTestHarnessTests"));
    }

    private static Map<String, Profile> profiles() {
        Map<String, Profile> result = new LinkedHashMap<>();
        result.put("byteorder", new Profile("Byteorder", cases(
                "unsignedEndianRoundTrip", "Unsigned endian encoding crosses Swift boundary",
                "signedWidthRoundTrip", "Signed nonstandard widths cross Swift boundary",
                "floatingBitPatterns", "Floating bit patterns cross Swift boundary"), true, true, true, false, false,
                Set.of("Byteorder", "ExportedKotlinPackages", "KotlinStdlib", "KotlinRuntimeSupport", "SwiftTestHarnessTests", "SwiftTestHarnessPackageTests")));
        result.put("serde", new Profile("Serde", cases("testSwiftModuleLoads", "Swift module loads", "textHasConcreteSwiftType", "textHasConcreteSwiftType",
                "primitivesHaveConcreteSwiftTypes", "primitivesHaveConcreteSwiftTypes", "failureRetainsOriginalDiagnostic", "failureRetainsOriginalDiagnostic",
                "actualDecodeFailureIsTyped", "actualDecodeFailureIsTyped"), true, true, true, false, false,
                Set.of("Serde", "SerdeCore", "KotlinRuntimeSupport", "KotlinCoroutineSupport", "SwiftTestHarnessTests")));
        result.put("strum", profile("Strum", true, true, cases("parseErrorExport", "ParseError export works", "caseStyleExport", "CaseStyle and convertCase export works", "strumEntryExport", "StrumEntry export works")));
        result.put("syn", profile("Syn", true, true, cases(
                "smokeTest", "Verify Syn module imports cleanly and does not shadow Swift.Error or Swift.Result",
                "pathRemainsModuleStyleWithLeadingColon", "Construct and inspect a syntax path through the exported API",
                "tokenTransformPreservesTheTokenStream", "Pass a typed token transformation through the exported callback",
                "diagnosticRetainsItsMessageAndIdentity", "Inspect and render a Syn diagnostic through a failure result",
                "fileResultExposesItsSyntaxTree", "Parse a file and inspect its concrete syntax tree without an erased cast",
                "tokenResultExposesItsTokenStream", "Inspect a concrete token result without an erased success value")));
        result.put("zip", profile("Zip", true, true, cases("swiftModuleLoads", "Zip constants and functions work from Swift")));
        result.put("wiremock", new Profile("Wiremock", cases("swiftModuleLoads", "Swift module loads and basic response template works",
                "responseHeadersRoundTrip", "Response header maps preserve ordered Unicode values", "requestHeadersRoundTrip", "Request header maps cross the typed constructor boundary"),
                true, true, true, false, true, Set.of("Wiremock", "KotlinRuntimeSupport", "KotlinCoroutineSupport", "SwiftTestHarnessTests")));
        result.put("proc-macro2", profile("ProcMacro2", false, false, cases(
                "tokenStreamParsingAndFormatting", "TokenStream parses valid Rust tokens and formats groups", "tokenStreamParseFailure", "TokenStream reports parse errors on invalid input",
                "literalAndPunctExport", "Literal and Punct bridge cleanly across Swift Export")));
        result.put("aws-credential-types", profile("AwsCredentialTypes", false, false, cases(
                "credentialsRoundTripAndZeroize", "credentialsRoundTripAndZeroize", "tokenRoundTripAndRedaction", "tokenRoundTripAndRedaction",
                "credentialsFutureRetainsItsValueAcrossAwaitAndPoll", "credentialsFutureRetainsItsValueAcrossAwaitAndPoll",
                "tokenFutureRetainsItsValueAcrossAwaitAndPoll", "tokenFutureRetainsItsValueAcrossAwaitAndPoll",
                "credentialsProviderResolvesIdentityOnCallerActor", "credentialsProviderResolvesIdentityOnCallerActor",
                "tokenProviderResolvesIdentityOnCallerActor", "tokenProviderResolvesIdentityOnCallerActor",
                "credentialsResultFailureThrowsInSwift", "credentialsResultFailureThrowsInSwift",
                "credentialsIdentityResultFailureThrowsInSwift", "credentialsIdentityResultFailureThrowsInSwift",
                "tokenResultFailureThrowsInSwift", "tokenResultFailureThrowsInSwift", "tokenIdentityResultFailureThrowsInSwift", "tokenIdentityResultFailureThrowsInSwift")));
        result.put("anstyle", new Profile("Anstyle", cases("ansiPaletteConversion", "ansiPaletteConversion", "rgbComponents", "rgbComponents",
                "styleEffects", "styleEffects", "effectsIdentityConversion", "effectsIdentityConversion"), false, true, false, true, false,
                Set.of("Anstyle", "KotlinRuntimeSupport", "KotlinCoroutineSupport", "SwiftTestHarnessTests")));
        result.put("tokio-util", new Profile("TokioUtil", cases("testCancellationToken", "CancellationToken creation and cancellation",
                "testCodecRoundtrip", "LinesCodec and BytesCodec encoding and decoding", "testTaskTracker", "TaskTracker lifecycle and token close"),
                false, false, false, false, false, Set.of()));
        return Collections.unmodifiableMap(result);
    }

    private static final Map<String, Profile> PROFILES = profiles();
    public static Set<String> profileNames() { return PROFILES.keySet(); }
    public static Map<String, String> requiredCases(String name) { return getProfile(name).cases(); }
    public static Set<String> requiredModules(String name) { return getProfile(name).requiredModules(); }

    private static Profile getProfile(String name) {
        Profile profile = PROFILES.get(name);
        if (profile == null) throw new IllegalArgumentException("Unknown Swift behavior profile: " + name);
        return profile;
    }

    private static long count(String regex, String text) { return Pattern.compile(regex).matcher(text).results().count(); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
    private static String last(String regex, String line) {
        Matcher matches = Pattern.compile(regex).matcher(line);
        String result = null;
        while (matches.find()) result = matches.group(1);
        return result;
    }

    private static Map<String, Object> object(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }

    public static Map<String, Object> verify(String name, String text) {
        Profile profile = getProfile(name);
        String clean = text.replaceAll("\u001b\\[[0-9;]*m", "");
        String plural = name.equals("zip") ? "test" : name.equals("wiremock") ? "tests?" : "tests";
        long summaries = count("Test run with " + profile.cases().size() + " " + plural + "[^\\r\\n]*passed", clean);
        require(profile.strictCounts() ? summaries == 1 : summaries > 0, "Expected " + (profile.strictCounts() ? "one passing complete " : "all ") + profile.module() + " Swift test summary (" + profile.cases().size() + " cases)");
        require(count("(?m)^.*(?:Test|Suite) .*\\b(?:failed|skipped)\\b", clean) == 0, profile.module() + " Swift test failed or was skipped");
        String boundary = profile.wordBoundary() ? "\\b" : "";
        if (profile.strictCounts()) {
            String passing = "(?m)^.*" + boundary + "Test (?:\"[^\"\\r\\n]+\"|[A-Za-z_]\\w*\\(\\))(?: \\(aka '[^\\r\\n]+'\\))? passed\\b";
            require(count(passing, clean) == profile.cases().size(), "Unexpected or duplicated Swift case execution");
        }
        for (Map.Entry<String, String> test : profile.cases().entrySet()) {
            String label = profile.flexibleNames() ? "(?:" + Pattern.quote(test.getKey()) + "\\(\\))"
                    : "(?:\"" + Pattern.quote(test.getValue()) + "\"|" + Pattern.quote(test.getKey()) + "\\(\\))";
            String alias = "(?: \\(aka '" + Pattern.quote(test.getKey()) + "\\(\\)'\\))?";
            long executions = count(boundary + "Test " + label + alias + " passed\\b", clean);
            if (profile.flexibleNames()) require(executions > 0 || count("Test " + Pattern.quote(test.getKey()) + "\\b.*passed\\b", clean) > 0, "Expected passing execution of " + test.getKey());
            else require(executions == 1, "Expected exactly one passing execution of " + test.getKey());
        }
        List<Map<String, Object>> frontends = new ArrayList<>();
        Set<String> modules = new LinkedHashSet<>();
        int configurations = 0;
        if (profile.compiler()) for (String line : (name.equals("aws-credential-types") ? text : clean).split("\\R")) {
            if (!line.contains("swift-frontend") || !line.contains("-module-name ")) continue;
            String module = last("-module-name\\s+(\\S+)", line);
            if ("main".equals(module) && line.contains("Package.swift")) { configurations++; continue; }
            String mode = last("-swift-version\\s+(\\S+)", line);
            require(line.contains("-warnings-as-errors") && count("-target\\s+arm64-apple-macos(?:x)?15\\.0", line) > 0, "Missing Swift warning/deployment gate: " + module);
            boolean allowed;
            if ("SwiftTestHarnessTests".equals(module)) allowed = "6".equals(mode) || "6.0".equals(mode);
            else if ("SwiftTestHarnessPackageTests".equals(module)) allowed = mode == null || "6".equals(mode) || "6.0".equals(mode);
            else allowed = "5".equals(mode) || "5.0".equals(mode);
            require(allowed, "Unexpected effective Swift language mode: " + module);
            frontends.add(object("module", module, "swift_language_mode", mode, "warnings_as_errors", true, "macos_deployment_target", "15.0"));
            modules.add(module);
        }
        require(modules.containsAll(profile.requiredModules()), "Missing actual dependency/consumer frontend evidence");
        Map<String, Object> result = object("actual_behavior_tests", profile.cases().size(), "failures", 0, "skips", 0);
        if (profile.compiler()) {
            result.put("frontends", frontends);
            if (profile.configurationCount()) result.put("configuration_frontends", configurations);
            result.put("generated_runner_classified_separately", true);
        }
        return result;
    }

    static Path withSuffix(Path path, String suffix) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return path.resolveSibling((dot <= 0 ? name : name.substring(0, dot)) + suffix);
    }

    public static List<String> wrongPayloadCommand(String text, Path source) {
        List<String> lines = text.lines().filter(line -> line.contains("swift-frontend") && line.contains("-module-name SwiftTestHarnessTests")).toList();
        require(!lines.isEmpty(), "Missing authored frontend for the real negative consumer");
        List<String> tokens = SwiftProblemScanner.shellWords(lines.getLast());
        Path frontend = tokens.stream().filter(token -> token.endsWith("/swift-frontend")).map(Path::of).findFirst().orElseThrow(() -> new IllegalArgumentException("Missing compiler path for the real negative consumer"));
        List<String> command = new ArrayList<>(List.of(frontend.resolveSibling("swiftc").toString(), "-typecheck", "-module-name", "SerdeWrongPayloadClient", "-swift-version", "6", "-warnings-as-errors", "-target", "arm64-apple-macosx15.0"));
        Set<String> keep = Set.of("-I", "-F", "-sdk", "-resource-dir", "-Xcc", "-module-cache-path");
        for (int i = 0; i + 1 < tokens.size(); i++) if (keep.contains(tokens.get(i))) { command.add(tokens.get(i)); command.add(tokens.get(i + 1)); }
        command.add(source.toString());
        return command;
    }

    public static Map<String, Object> verifyWrongPayload(String text, Path source, Path output) throws IOException, InterruptedException {
        List<String> command = wrongPayloadCommand(text, source);
        Path scratch = output.toAbsolutePath().getParent().resolve("tmp");
        Files.createDirectories(scratch);
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("TMPDIR", scratch.toString());
        Process process = builder.start();
        String log;
        try (var input = process.getInputStream()) { log = new String(input.readAllBytes(), StandardCharsets.UTF_8); }
        int exit = process.waitFor();
        Files.writeString(withSuffix(output, ".log"), log);
        boolean rejected = exit != 0 && count("cannot convert[^\\n]*String[^\\n]*Int64", log) > 0;
        Map<String, Object> result = object("command", command, "exit_code", exit, "rejected_wrong_payload", rejected);
        Json.write(output, result);
        require(rejected, "Wrong-payload client was not rejected for its concrete String-to-Int64 mismatch");
        return result;
    }

    public static int run(String... args) throws Exception {
        Path log = null, output = null, negative = null;
        String profile = null;
        for (int i = 0; i < args.length; i++) {
            String option = args[i];
            if (!option.startsWith("-") && log == null) { log = Path.of(option); continue; }
            if (i + 1 >= args.length) throw new IllegalArgumentException("Missing value for " + option);
            String value = args[++i];
            switch (option) {
                case "--profile" -> profile = value;
                case "--output" -> output = Path.of(value);
                case "--negative" -> negative = Path.of(value);
                default -> throw new IllegalArgumentException("Unknown argument: " + option);
            }
        }
        require(log != null && profile != null, "Usage: SwiftTestVerifier <log> --profile <project> [--output <receipt>] [--negative <source>]");
        require(negative == null || profile.equals("serde"), "The wrong-payload consumer belongs to the serde profile");
        String text = new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
        Map<String, Object> result = verify(profile, text);
        Path receipt = output == null ? withSuffix(log, ".json") : output;
        if (negative != null) result.put("wrong_payload_client", verifyWrongPayload(text, negative, receipt.resolveSibling("swift-wrong-payload.json")));
        if (!profile.equals("tokio-util") || output != null) Json.write(receipt, result);
        System.out.println("Verified " + result.get("actual_behavior_tests") + " actual " + getProfile(profile).module() + " Swift behaviors" + (getProfile(profile).compiler() ? " and effective compiler settings" : ""));
        return 0;
    }

    public static void main(String[] args) throws Exception { System.exit(run(args)); }
}
