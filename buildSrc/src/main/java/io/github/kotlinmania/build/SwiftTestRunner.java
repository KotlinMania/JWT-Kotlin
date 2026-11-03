package io.github.kotlinmania.build;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Runs the existing four-case Whoami/StrumMacros Swift consumer gates with complete logs. */
public final class SwiftTestRunner {
    private SwiftTestRunner() {}

    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
    private static long count(String pattern, String text) { return Pattern.compile(pattern).matcher(text).results().count(); }

    public static Map<String, String> requiredCases(String profile) {
        Map<String, String> result = new LinkedHashMap<>();
        if (profile.equals("whoami")) {
            for (String name : List.of("architectureDisplayNames", "platformDisplayNames", "languageCountryAndDisplay", "countryAndWidthDisplay")) result.put(name, name);
        } else if (profile.equals("strum-macros")) {
            result.put("enumCountTokensExport", "Enum count emits the number of declared variants");
            result.put("variantNameTokensExport", "Variant names retain every declared name");
            result.put("parserTokensExport", "Enum string parser emits parsing and error branches");
            result.put("nonEnumDiagnosticExport", "An unsupported structure emits the upstream enum diagnostic");
        } else throw new IllegalArgumentException("Unknown Swift execution profile: " + profile);
        return result;
    }

    private static String option(List<String> args, String name) {
        int at = args.indexOf(name);
        if (at < 0) return null;
        require(at + 1 < args.size(), "Missing compiler argument value: " + name);
        return args.get(at + 1);
    }

    public static void verifyLog(String profile, String output) {
        Map<String, String> cases = requiredCases(profile);
        List<String> frontends = new ArrayList<>();
        int manifests = 0;
        for (String line : output.split("\\R")) {
            if (!line.contains("swift-frontend") || !line.contains(" -frontend ")) continue;
            List<String> args = SwiftProblemScanner.shellWords(line);
            String module = option(args, "-module-name");
            if (module == null) continue;
            if (module.equals("main") && args.stream().anyMatch(value -> Path.of(value).getFileName() != null && Path.of(value).getFileName().toString().equals("Package.swift"))) { manifests++; continue; }
            String mode = option(args, "-swift-version");
            require(args.contains("-warnings-as-errors"), "Frontend lacks warnings as errors: " + module);
            if (module.equals("SwiftTestHarnessTests")) require("6".equals(mode), "Authored consumer is not explicitly Swift 6");
            if (!Set.of("SwiftTestHarnessTests", "SwiftTestHarnessPackageTests", "SwiftTestHarnessPackageDiscoveredTests").contains(module)) require("5".equals(mode), "Generated dependency is not explicitly Swift 5: " + module);
            frontends.add(module);
        }
        require(frontends.contains("SwiftTestHarnessTests") && frontends.contains(profile.equals("whoami") ? "Whoami" : "StrumMacros"), "Missing actual dependency/consumer frontend evidence");
        for (Map.Entry<String, String> test : cases.entrySet()) {
            String pattern;
            if (profile.equals("whoami")) pattern = "Test " + Pattern.quote(test.getKey()) + "\\(\\) passed";
            else {
                String label = "(?:\"" + Pattern.quote(test.getValue()) + "\"|" + Pattern.quote(test.getKey()) + "\\(\\))";
                String alias = "(?: \\(aka '" + Pattern.quote(test.getKey()) + "\\(\\)'\\))?";
                pattern = "Test " + label + alias + " passed\\b";
            }
            require(count(pattern, output) == 1, "Expected exactly one passed execution: " + test.getKey());
        }
        require(count("Test run with 4 tests? in .* passed", output) == 1, "Expected exactly four Swift behavior cases");
        System.out.println("Verified product frontends: " + frontends.size() + "; separate manifest frontends: " + manifests);
    }

    public static List<String> command(Path root, String developer) {
        List<String> result = new ArrayList<>(List.of("swift", "test", "--scratch-path", root.resolve("build/swift-harness-km2").toString(), "--jobs", "2", "-v",
                "-Xswiftc", "-warnings-as-errors", "-Xswiftc", "-target", "-Xswiftc", "arm64-apple-macosx15.0"));
        if (developer.endsWith("CommandLineTools")) {
            String frameworks = developer + "/Library/Developer/Frameworks";
            result.addAll(List.of("-Xswiftc", "-F", "-Xswiftc", frameworks, "-Xlinker", "-F", "-Xlinker", frameworks,
                    "-Xlinker", "-rpath", "-Xlinker", frameworks, "-Xlinker", "-rpath", "-Xlinker", developer + "/Library/Developer/usr/lib"));
        }
        return result;
    }

    public static int run(String... args) throws IOException, InterruptedException {
        Path root = Path.of(".").toAbsolutePath().normalize(), log = null;
        String profile = null;
        for (int i = 0; i < args.length; i++) {
            String option = args[i];
            if (i + 1 >= args.length) throw new IllegalArgumentException("Missing value for " + option);
            String value = args[++i];
            switch (option) {
                case "--root" -> root = Path.of(value).toAbsolutePath().normalize();
                case "--profile" -> profile = value;
                case "--verify-log" -> log = Path.of(value);
                default -> throw new IllegalArgumentException("Unknown argument: " + option);
            }
        }
        require(profile != null, "--profile whoami or strum-macros is required");
        requiredCases(profile);
        if (log != null) { verifyLog(profile, Files.readString(log)); return 0; }
        Path scratch = root.resolve("build/tmp/swift-runner");
        Files.createDirectories(scratch);
        ProcessBuilder selector = new ProcessBuilder("xcode-select", "-p").redirectErrorStream(true);
        selector.environment().put("TMPDIR", scratch.toString());
        Process selection = selector.start();
        String developer;
        try (var input = selection.getInputStream()) { developer = new String(input.readAllBytes(), StandardCharsets.UTF_8).strip(); }
        require(selection.waitFor() == 0, "xcode-select failed: " + developer);
        log = root.resolve("build/reports/swift/consumer.log");
        Files.createDirectories(log.getParent());
        ProcessBuilder builder = new ProcessBuilder(command(root, developer)).directory(root.resolve("src/swift/swift-test-harness").toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("MACOSX_DEPLOYMENT_TARGET", "15.0");
        builder.environment().put("TMPDIR", scratch.toString());
        int exit = builder.start().waitFor();
        String output = Files.readString(log);
        System.out.print(output);
        SwiftToolingPreparer.verify(root.resolve("build/swift-tooling-km2"), root.resolve("verification/swift-tooling-lock.json"));
        if (exit != 0) return exit;
        verifyLog(profile, output);
        return 0;
    }

    public static void main(String[] args) throws IOException, InterruptedException { System.exit(run(args)); }
}
