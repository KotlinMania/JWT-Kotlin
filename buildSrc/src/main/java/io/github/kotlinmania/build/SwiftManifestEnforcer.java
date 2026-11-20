package io.github.kotlinmania.build;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Prepares generated Swift dependency manifests without rewriting generated Swift API sources.
///
/// The template calls this helper after tasks ending in `generateSPMPackage`. `enforce` checks
/// the supported `Package(...)` structure and adds a missing macOS 15 platform declaration.
/// `run` applies that transformation to a file and writes a receipt beside the generated package.
///
/// The intended dependency policy is Swift 5 through a tools-version 5.9 manifest; the authored
/// consumer harness uses Swift 6 separately. This helper does not rewrite the tools-version
/// header, validate an existing `platforms` value, or inspect effective compiler flags. The
/// receipt records intended policy, not proof of compilation or runtime behavior.
///
/// Its structural tokenizer and small support methods are also used by standalone Swift tools.
public final class SwiftManifestEnforcer {
    private static final Pattern HEADER = Pattern.compile("^// swift-tools-version:\\s*(\\d+)\\.(\\d+)(?:\\.\\d+)?[^\\n]*(?:\\n|$)");

    private SwiftManifestEnforcer() {}

    /// Structural token and its half-open character range in the original source.
    ///
    /// @param value the identifier or punctuation text
    /// @param start the inclusive start offset, in Java string characters
    /// @param end the exclusive end offset
    record Token(String value, int start, int end) {}

    /// Extracts identifiers and punctuation while skipping comments and quoted text.
    ///
    /// Offsets refer to the original input so callers can insert text without reformatting it.
    /// Nested block comments and simple triple-quoted strings are recognized. This is a limited
    /// structural scanner shared with Kotlin configuration checks, not a complete Swift parser.
    ///
    /// @param text the source or configuration text to scan
    /// @return a new mutable token list in source order
    /// @throws IllegalArgumentException if a recognized comment or quoted string is unterminated
    static List<Token> tokens(String text) {
        List<Token> result = new ArrayList<>();
        for (int i = 0; i < text.length();) {
            if (Character.isWhitespace(text.charAt(i))) {
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
            } else if (text.charAt(i) == '\"' || text.charAt(i) == '\'') {
                char quote = text.charAt(i++);
                while (i < text.length() && text.charAt(i) != quote) i += text.charAt(i) == '\\' ? 2 : 1;
                require(i < text.length(), "Unterminated Kotlin string");
                i++;
            } else {
                int start = i++;
                char first = text.charAt(start);
                if (identifierStart(first)) {
                    while (i < text.length() && (identifierStart(text.charAt(i)) || Character.isDigit(text.charAt(i)))) i++;
                }
                result.add(new Token(text.substring(start, i), start, i));
            }
        }
        return result;
    }

    private static boolean identifierStart(char c) {
        return c == '_' || c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z';
    }

    /// Rejects two direct forms of disabling `allWarningsAsErrors` in configuration text.
    ///
    /// Comments and strings are excluded before matching assignment and `.set(false)` tokens.
    /// Indirect or computed settings are not evaluated; this does not prove the effective policy.
    ///
    /// @param text the Kotlin build configuration to inspect
    /// @throws IllegalArgumentException if a recognized disabling form or malformed token is found
    static void validateWarningPolicy(String text) {
        List<String> values = tokens(text).stream().map(Token::value).toList();
        for (List<String> pattern : List.of(
                List.of("allWarningsAsErrors", ".", "set", "(", "false", ")"),
                List.of("allWarningsAsErrors", "=", "false"))) {
            for (int i = 0; i + pattern.size() <= values.size(); i++) {
                require(!values.subList(i, i + pattern.size()).equals(pattern),
                        "Warnings-as-errors relaxation requires source repair; sync refuses to preserve/reintroduce it");
            }
        }
    }

    /// Adds a missing macOS deployment declaration to a supported generated package manifest.
    ///
    /// Exactly one `Package(...)` initializer with a `targets` argument is required. A tools
    /// header is required and major versions above 6 are rejected. Explicit package language
    /// overrides and the `swiftLanguageMode` identifier are rejected outside quoted text.
    ///
    /// If `platforms` is absent, `platforms: [.macOS("15.0")]` is inserted before the first
    /// products/dependencies/targets argument, preserving surrounding text. An existing platform
    /// declaration and the tools header are left unchanged. This method performs no file I/O.
    ///
    /// @param text the complete `Package.swift` content
    /// @return transformed text, or the original text if a platforms argument is already present
    /// @throws IllegalArgumentException if the header, structure or language settings are
    ///   unsupported
    public static String enforce(String text) {
        Matcher header = HEADER.matcher(text);
        require(header.find(), "Missing Swift tools version header");
        require(Integer.parseInt(header.group(1)) <= 6, "Newer Swift tools manifest requires review");
        List<Token> tokens = tokens(text);
        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i + 1 < tokens.size(); i++) {
            if (tokens.get(i).value().equals("Package") && tokens.get(i + 1).value().equals("(")) starts.add(i);
        }
        require(starts.size() == 1, "Unsupported/ambiguous Package initializer");
        int opening = starts.getFirst() + 1;
        List<String> stack = new ArrayList<>(List.of("("));
        Map<String, Integer> arguments = new LinkedHashMap<>();
        boolean atArgument = true;
        int closing = -1;
        for (int i = opening + 1; i < tokens.size(); i++) {
            String value = tokens.get(i).value();
            if (stack.size() == 1) {
                if (value.equals(")")) { closing = i; break; }
                if (value.equals(",")) { atArgument = true; continue; }
                if (atArgument) {
                    require(i + 1 < tokens.size() && tokens.get(i + 1).value().equals(":"), "Unsupported package argument");
                    require(!arguments.containsKey(value), "Duplicate package argument");
                    arguments.put(value, i);
                    atArgument = false;
                }
            }
            if (List.of("(", "[", "{").contains(value)) stack.add(value);
            else if (List.of(")", "]", "}").contains(value)) {
                String expected = switch (value) { case ")" -> "("; case "]" -> "["; default -> "{"; };
                require(!stack.isEmpty() && stack.getLast().equals(expected), "Unbalanced Swift manifest");
                stack.removeLast();
            }
        }
        require(closing >= 0, "Unclosed Package initializer");
        require(arguments.containsKey("targets"), "Generated package must declare its targets");
        require(!arguments.containsKey("swiftLanguageVersions"), "Legacy explicit Swift language override requires owner review");
        require(tokens.stream().noneMatch(token -> token.value().equals("swiftLanguageMode")),
                "Per-target swiftLanguageMode override found; dependencies must default to Swift 5 via swift-tools-version");
        require(!arguments.containsKey("swiftLanguageModes"),
                "swiftLanguageModes must not be set on generated dependency packages; dependencies default to Swift 5 via swift-tools-version 5.9");
        if (!arguments.containsKey("platforms")) {
            int first = List.of("products", "dependencies", "targets").stream().filter(arguments::containsKey)
                    .mapToInt(arguments::get).min().orElseThrow();
            int position = tokens.get(first).start();
            String indent = text.substring(text.lastIndexOf('\n', position - 1) + 1, position);
            String separator = indent.isBlank() ? "\n" + indent : " ";
            return text.substring(0, position) + "platforms: [.macOS(\"15.0\")]," + separator + text.substring(position);
        }
        return text;
    }

    /// Computes the lowercase SHA-256 fingerprint used in manifest receipts.
    ///
    /// @param value the exact encoded bytes to fingerprint
    /// @return the 64-character hexadecimal digest
    /// @throws IllegalStateException if the Java runtime lacks SHA-256
    static String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    /// Rejects an unsupported source shape with a caller-supplied diagnostic.
    ///
    /// @param condition whether the required invariant holds
    /// @param message the diagnostic used when the condition is false
    /// @throws IllegalArgumentException if the condition is false
    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    /// Applies the manifest transformation and writes the requested JSON receipt.
    ///
    /// Arguments are `MANIFEST --receipt RECEIPT`. Both files use UTF-8. The manifest is rewritten
    /// only when `enforce` changes its text; the receipt is always replaced and its parents are
    /// created. Writes are not transactional: receipt failure does not undo a manifest change.
    /// No Swift compiler or test process is started.
    ///
    /// @param args the manifest path and required receipt option
    /// @return zero after both transformation and receipt writing succeed
    /// @throws IOException if input cannot be read or either output cannot be written
    /// @throws IllegalArgumentException if arguments or manifest content are unsupported
    public static int run(String... args) throws IOException {
        Path manifest = null;
        Path receipt = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--receipt") && i + 1 < args.length) receipt = Path.of(args[++i]);
            else if (!args[i].startsWith("-") && manifest == null) manifest = Path.of(args[i]);
            else throw new IllegalArgumentException("Usage: SwiftManifestEnforcer <manifest> --receipt <path>");
        }
        require(manifest != null && receipt != null, "Usage: SwiftManifestEnforcer <manifest> --receipt <path>");
        String before = Files.readString(manifest);
        String after = enforce(before);
        if (!before.equals(after)) Files.writeString(manifest, after);
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("manifest", manifest.toAbsolutePath().normalize().toString());
        record.put("swift_language_mode", "5");
        record.put("scope", "Dependency modules default to Swift 5 via swift-tools-version 5.9; consumer harness uses swift-tools-version 6.0 for Swift 6 consumer tests");
        record.put("sha256", sha256(after.getBytes(StandardCharsets.UTF_8)));
        record.put("swift_api_sources_modified", false);
        Json.write(receipt, record);
        return 0;
    }

    /// Runs the manifest command and exits the JVM on success.
    ///
    /// @param args the same arguments accepted by `run`
    /// @throws IOException if `run` cannot read or write a file
    /// @throws IllegalArgumentException if `run` rejects the arguments or manifest
    public static void main(String[] args) throws IOException {
        System.exit(run(args));
    }
}
