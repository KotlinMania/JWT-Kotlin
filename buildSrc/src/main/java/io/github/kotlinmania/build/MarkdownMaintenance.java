package io.github.kotlinmania.build;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** README table-of-contents and strict UTF-8/ASCII checks used by Codex packaging. */
public final class MarkdownMaintenance {
    private MarkdownMaintenance() {}
    static final String BEGIN = "<!-- Begin ToC -->", END = "<!-- End ToC -->";
    private static final Map<Integer, String> SUBSTITUTIONS = Map.of(0xA0, " ", 0x2011, "-", 0x2013, "-", 0x2014, "-", 0x2018, "'", 0x2019, "'", 0x201C, "\"", 0x201D, "\"", 0x2026, "...", 0x202F, " ");
    public record InvalidCharacter(int line, int column, int codepoint) {}
    static List<String> toc(String text) {
        List<String> result = new ArrayList<>(); boolean code = false; Pattern heading = Pattern.compile("^(#{2,6})\\s+(.*)$", Pattern.UNICODE_CHARACTER_CLASS);
        for (String line : text.lines().toList()) {
            if (line.strip().startsWith("```")) { code = !code; continue; } if (code) continue;
            var match = heading.matcher(line); if (!match.find()) continue; String title = match.group(2).strip();
            String slug = title.toLowerCase(Locale.ROOT).replace('\u00a0', ' ').replace('\u2011', '-').replace('\u2013', '-').replace('\u2014', '-').replaceAll("(?U)[^0-9a-z\\s-]", "").strip().replace(' ', '-');
            result.add("  ".repeat(match.group(1).length() - 2) + "- [" + title + "](#" + slug + ")");
        }
        return result;
    }
    public static int checkToc(Path file, boolean fix) throws IOException {
        if (!Files.isRegularFile(file)) { System.err.println("Error: file not found: " + file); return 1; }
        List<String> lines = Files.readString(file).lines().toList(); int begin = -1, end = -1;
        for (int i = 0; i < lines.size(); i++) { if (begin < 0 && lines.get(i).strip().equals(BEGIN)) begin = i; if (end < 0 && lines.get(i).strip().equals(END)) end = i; }
        if (begin < 0 || end < 0) { System.out.println("Note: Skipping ToC check; no markers found in " + file + "."); return 0; }
        if (end <= begin) { System.err.println("ERROR: ToC end marker must follow its begin marker: " + file); return 1; }
        List<String> current = lines.subList(begin + 1, end).stream().filter(line -> line.stripLeading().startsWith("- [")).toList();
        List<String> outside = new ArrayList<>(lines.subList(0, begin)); outside.addAll(lines.subList(end + 1, lines.size())); List<String> expected = toc(String.join("\n", outside));
        if (current.equals(expected)) return 0;
        if (!fix) { System.out.println("ERROR: README ToC is out of date.\n--- existing ToC\n+++ generated ToC"); current.forEach(line -> System.out.println("-" + line)); expected.forEach(line -> System.out.println("+" + line)); return 1; }
        // Preserve both markers so a second check validates the repaired block.
        List<String> replacement = new ArrayList<>(lines.subList(0, begin + 1)); replacement.add(""); replacement.addAll(expected); replacement.add(""); replacement.addAll(lines.subList(end, lines.size()));
        Files.writeString(file, String.join("\n", replacement) + "\n"); System.out.println("Updated ToC in " + file + "."); return 0;
    }
    static List<InvalidCharacter> invalidCharacters(String text) {
        List<InvalidCharacter> result = new ArrayList<>(); int line = 1, column = 1;
        for (int offset = 0; offset < text.length();) { int c = text.codePointAt(offset); offset += Character.charCount(c);
            if (c != '\n' && !(c >= 0x20 && c <= 0x7e) && c != 0x2728) result.add(new InvalidCharacter(line, column, c));
            if (c == '\n' || c == '\r' && (offset >= text.length() || text.charAt(offset) != '\n') || c == 0xb || c == 0xc || c == 0x1c || c == 0x1d || c == 0x1e || c == 0x85 || c == 0x2028 || c == 0x2029) { line++; column = 1; } else column++;
        }
        return result;
    }
    public static int checkAscii(Path file, boolean fix) throws IOException {
        byte[] bytes = Files.readAllBytes(file); String text;
        try { text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException invalid) {
            var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT); ByteBuffer input = ByteBuffer.wrap(bytes); decoder.decode(input, CharBuffer.allocate(bytes.length), true); int offset = input.position(), line = 1, start = -1;
            for (int i = 0; i < offset; i++) if (bytes[i] == '\n') { line++; start = i; }
            System.out.println("UTF-8 decoding error:\n  byte offset: " + offset + "\n  location: line " + line + ", column " + (offset - start)); return 1;
        }
        List<InvalidCharacter> errors = invalidCharacters(text);
        for (InvalidCharacter error : errors) System.out.printf("Invalid character at line %d, column %d: U+%04X%n", error.line(), error.column(), error.codepoint());
        if (!errors.isEmpty() && fix) {
            StringBuilder result = new StringBuilder(); int replacements = 0;
            for (int offset = 0; offset < text.length();) { int c = text.codePointAt(offset); offset += Character.charCount(c); String replacement = SUBSTITUTIONS.get(c); if (replacement == null) result.appendCodePoint(c); else { result.append(replacement); replacements++; } }
            Files.writeString(file, result); System.out.println("Fixed " + replacements + " of " + errors.size() + " errors in " + file + ".");
        }
        return errors.isEmpty() ? 0 : 1;
    }
    public static int run(String... args) throws IOException {
        if (List.of(args).contains("--help") || List.of(args).contains("-h")) { System.out.println("Usage: MarkdownMaintenance toc [file] [--fix] | ascii [--fix] files..."); return 0; }
        if (args.length == 0 || !List.of("toc", "ascii").contains(args[0])) throw new IllegalArgumentException("Usage: MarkdownMaintenance toc [file] [--fix] | ascii [--fix] files...");
        boolean fix = false; List<Path> files = new ArrayList<>();
        for (int i = 1; i < args.length; i++) if (args[i].equals("--fix")) fix = true; else { if (args[i].startsWith("--")) throw new IllegalArgumentException("Unrecognized option: " + args[i]); files.add(Path.of(args[i])); }
        if (args[0].equals("toc")) { if (files.size() > 1) throw new IllegalArgumentException("Expected at most one Markdown file"); return checkToc(files.isEmpty() ? Path.of("README.md") : files.getFirst(), fix); }
        if (files.isEmpty()) throw new IllegalArgumentException("Expected at least one file"); int result = 0; for (Path file : files) result |= checkAscii(file, fix); return result;
    }
    public static void main(String[] args) throws IOException { System.exit(run(args)); }
}
