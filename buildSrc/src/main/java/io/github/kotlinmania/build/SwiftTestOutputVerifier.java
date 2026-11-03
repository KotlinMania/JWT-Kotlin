package io.github.kotlinmania.build;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Rejects a successful command that executed zero Swift Testing tests. */
public final class SwiftTestOutputVerifier {
    private SwiftTestOutputVerifier() {}

    public static int executedTests(String text) {
        Matcher result = Pattern.compile("Test run with ([1-9]\\d*) tests?[^\\n]*passed").matcher(text);
        if (!result.find()) throw new IllegalArgumentException("No successful nonzero Swift Testing execution found");
        return Integer.parseInt(result.group(1));
    }

    public static int run(String... args) throws IOException {
        Path log = null, receipt = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--receipt") && i + 1 < args.length) receipt = Path.of(args[++i]);
            else if (!args[i].startsWith("-") && log == null) log = Path.of(args[i]);
            else throw new IllegalArgumentException("Usage: SwiftTestOutputVerifier <log> --receipt <path>");
        }
        if (log == null || receipt == null) throw new IllegalArgumentException("A log and --receipt are required");
        Json.write(receipt, Map.of("status", "passed", "executed_tests", executedTests(Files.readString(log)), "log", log.toAbsolutePath().normalize().toString()));
        return 0;
    }

    public static void main(String[] args) throws IOException { System.exit(run(args)); }
}
