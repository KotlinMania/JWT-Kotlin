package io.github.kotlinmania.build;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Preserves the explicit Swift 6 policy of projects already using that manifest enforcer. */
public final class Swift6ManifestEnforcer {
    private Swift6ManifestEnforcer() {}
    private record Edit(int start, int end, String value) {}

    private static void require(boolean condition, String message) { SwiftManifestEnforcer.require(condition, message); }

    public static String enforce(String text) {
        Matcher header = Pattern.compile("^// swift-tools-version:\\s*(\\d+)\\.(\\d+)(?:\\.\\d+)?[^\\n]*(?:\\n|$)").matcher(text);
        require(header.find(), "Missing Swift tools version header");
        require(Integer.parseInt(header.group(1)) <= 6, "Newer Swift tools manifest requires review");
        List<SwiftManifestEnforcer.Token> tokens = SwiftManifestEnforcer.tokens(text);
        List<String> values = tokens.stream().map(SwiftManifestEnforcer.Token::value).toList();
        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i + 1 < values.size(); i++) if (values.get(i).equals("Package") && values.get(i + 1).equals("(")) starts.add(i);
        require(starts.size() == 1, "Unsupported/ambiguous Package initializer");
        int opening = starts.getFirst() + 1, closing = -1;
        List<String> stack = new ArrayList<>(List.of("("));
        Map<String, Integer> arguments = new LinkedHashMap<>();
        boolean atArgument = true;
        for (int i = opening + 1; i < values.size(); i++) {
            String value = values.get(i);
            if (stack.size() == 1) {
                if (value.equals(")")) { closing = i; break; }
                if (value.equals(",")) { atArgument = true; continue; }
                if (atArgument) {
                    require(i + 1 < values.size() && values.get(i + 1).equals(":"), "Unsupported package argument");
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
        for (int i = 0; i < values.size(); i++) {
            if (values.get(i).equals("swiftLanguageMode")) require(i + 5 <= values.size() && values.subList(i, i + 5).equals(List.of("swiftLanguageMode", "(", ".", "v6", ")")), "Target-specific Swift language mode must be v6");
        }
        List<Edit> edits = new ArrayList<>();
        if (Integer.parseInt(header.group(1)) < 6) edits.add(new Edit(0, header.end(), "// swift-tools-version: 6.0\n"));
        if (!arguments.containsKey("platforms")) {
            int first = List.of("products", "dependencies", "targets").stream().filter(arguments::containsKey).mapToInt(arguments::get).min().orElseThrow();
            int position = tokens.get(first).start();
            String indent = text.substring(text.lastIndexOf('\n', position - 1) + 1, position);
            edits.add(new Edit(position, position, "platforms: [.macOS(\"15.0\")]," + (indent.isBlank() ? "\n" + indent : " ")));
        }
        if (arguments.containsKey("swiftLanguageModes")) {
            int at = arguments.get("swiftLanguageModes");
            require(at + 6 < values.size() && values.subList(at, at + 6).equals(List.of("swiftLanguageModes", ":", "[", ".", "v6", "]")), "Explicit package language modes must be v6 only");
            require(List.of(",", ")").contains(values.get(at + 6)), "Multiple package language modes require review");
        } else {
            List<Integer> cArguments = List.of("cLanguageStandard", "cxxLanguageStandard").stream().filter(arguments::containsKey).map(arguments::get).toList();
            if (!cArguments.isEmpty()) {
                int position = tokens.get(cArguments.stream().mapToInt(Integer::intValue).min().orElseThrow()).start();
                String indent = text.substring(text.lastIndexOf('\n', position - 1) + 1, position);
                require(indent.isBlank(), "Inline C standard arguments require review");
                edits.add(new Edit(position, position, "swiftLanguageModes: [.v6],\n" + indent));
            } else {
                SwiftManifestEnforcer.Token last = tokens.get(closing - 1);
                if (!last.value().equals(",")) edits.add(new Edit(last.end(), last.end(), ","));
                edits.add(new Edit(tokens.get(closing).start(), tokens.get(closing).start(), "\n    swiftLanguageModes: [.v6]\n"));
            }
        }
        // At a shared insertion point the comma belongs before the new argument.
        edits.sort(Comparator.comparingInt(Edit::start).reversed().thenComparing(Comparator.comparingInt(Edit::end).reversed()).thenComparing(Edit::value));
        for (Edit edit : edits) text = text.substring(0, edit.start()) + edit.value() + text.substring(edit.end());
        return text;
    }

    public static int run(String... args) throws IOException {
        Path manifest = null, receipt = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--receipt") && i + 1 < args.length) receipt = Path.of(args[++i]);
            else if (!args[i].startsWith("-") && manifest == null) manifest = Path.of(args[i]);
            else throw new IllegalArgumentException("Usage: Swift6ManifestEnforcer <manifest> --receipt <path>");
        }
        require(manifest != null && receipt != null, "Usage: Swift6ManifestEnforcer <manifest> --receipt <path>");
        String before = Files.readString(manifest), after = enforce(before);
        if (!before.equals(after)) Files.writeString(manifest, after);
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("manifest", manifest.toAbsolutePath().normalize().toString());
        record.put("swift_language_mode", "6");
        record.put("scope", "Every Swift target in this generated package, including dependency modules");
        record.put("sha256", SwiftManifestEnforcer.sha256(after.getBytes(StandardCharsets.UTF_8)));
        record.put("swift_api_sources_modified", false);
        Json.write(receipt, record);
        return 0;
    }

    public static void main(String[] args) throws IOException { System.exit(run(args)); }
}
