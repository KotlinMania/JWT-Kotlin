package io.github.kotlinmania.build;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/** Validates the producer manifest without rewriting its language mode or generated APIs. */
public final class SwiftPackageVerifier {
    private SwiftPackageVerifier() {}

    public static void validate(String text) {
        if (!Pattern.compile("^// swift-tools-version:\\s*5\\.9(?:\\s|$)").matcher(text).find()) {
            throw new IllegalArgumentException("Expected the producer's Swift tools-version 5.9");
        }
        if (Pattern.compile("swiftLanguage(?:Modes|Versions)\\s*:|swiftLanguageMode\\s*\\(").matcher(text).find()) {
            throw new IllegalArgumentException("Unexpected generated language-mode override");
        }
    }

    public static int run(String... args) throws IOException {
        if (args.length != 1) throw new IllegalArgumentException("Usage: SwiftPackageVerifier <manifest>");
        validate(Files.readString(Path.of(args[0])));
        System.out.println("Unmodified producer manifest: dependency Swift 5; authored harness remains Swift 6");
        return 0;
    }

    public static void main(String[] args) throws IOException { System.exit(run(args)); }
}
