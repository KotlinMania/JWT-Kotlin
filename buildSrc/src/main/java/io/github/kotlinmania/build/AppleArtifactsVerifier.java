package io.github.kotlinmania.build;

import static io.github.kotlinmania.build.AndroidPublicationVerifier.PublicationSupport.require;
import io.github.kotlinmania.build.AndroidPublicationVerifier.PublicationSupport;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Checks static arm64 XCFramework slices using their actual lipo and Mach-O platform records. */
public final class AppleArtifactsVerifier {
    private AppleArtifactsVerifier() {}
    private record Platform(String platform, String environment) {}
    private static final Map<Platform, Integer> EXPECTED = Map.of(new Platform("macos", ""), 1,
        new Platform("ios", ""), 2, new Platform("ios", "simulator"), 7, new Platform("tvos", ""), 3,
        new Platform("tvos", "simulator"), 8, new Platform("watchos", ""), 4, new Platform("watchos", "simulator"), 9);
    @FunctionalInterface interface Command { String run(List<String> arguments) throws IOException; }

    public static List<Map<String, Object>> verify(Path root, String module) throws IOException {
        return verify(root, module, args -> AndroidRuntimeResultsVerifier.command(root.toAbsolutePath(), args));
    }
    static List<Map<String, Object>> verify(Path root, String module, Command command) throws IOException {
        List<Map<String, Object>> receipts = new ArrayList<>();
        for (String mode : List.of("debug", "release")) {
            Path framework = root.resolve(mode).resolve(module + ".xcframework").toAbsolutePath().normalize();
            Map<String, Object> info = plist(framework.resolve("Info.plist"), command);
            List<Map<String, Object>> libraries = PublicationSupport.array(info.get("AvailableLibraries")).stream().map(PublicationSupport::object).toList();
            List<Platform> actual = libraries.stream().map(e -> new Platform(PublicationSupport.string(e, "SupportedPlatform"),
                (String) e.getOrDefault("SupportedPlatformVariant", ""))).toList();
            require(actual.size() == EXPECTED.size() && Set.copyOf(actual).equals(EXPECTED.keySet()), mode + " XCFramework has incorrect device/simulator slices: " + actual);
            for (Map<String, Object> entry : libraries) {
                require(List.of("arm64").equals(entry.get("SupportedArchitectures")), "Unexpected or retired architecture: " + entry);
                Path binary = framework.resolve(PublicationSupport.string(entry, "LibraryIdentifier")).resolve(PublicationSupport.string(entry, "BinaryPath")).normalize();
                require(binary.startsWith(framework), "XCFramework binary escapes its framework");
                byte[] data = Files.readAllBytes(binary);
                require(JniArtifactVerifier.Binary.starts(data, "!<arch>\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII)), "Expected a compiled static library archive: " + binary);
                String architecturesText = command.run(List.of("xcrun", "lipo", "-archs", binary.toString())).strip();
                List<String> architectures = architecturesText.isEmpty() ? List.of() : List.of(architecturesText.split("\\s+"));
                require(architectures.equals(List.of("arm64")), "Binary architecture disagrees with XCFramework metadata: " + binary + ": " + architectures);
                String commands = command.run(List.of("xcrun", "otool", "-l", binary.toString()));
                Set<Integer> platforms = Pattern.compile("cmd LC_BUILD_VERSION\\s+cmdsize \\d+\\s+platform (\\d+)").matcher(commands).results()
                    .map(m -> Integer.parseInt(m.group(1))).collect(java.util.stream.Collectors.toSet());
                int expected = EXPECTED.get(new Platform(PublicationSupport.string(entry, "SupportedPlatform"), (String) entry.getOrDefault("SupportedPlatformVariant", "")));
                require(platforms.equals(Set.of(expected)), "Compiled Mach-O platform disagrees with XCFramework metadata: " + binary + ": " + platforms);
                require(Files.readString(binary.getParent().resolve("Headers/" + module + ".h")).contains("@interface")
                    && Files.readString(binary.getParent().resolve("Modules/module.modulemap")).contains("framework module \"" + module + "\""),
                    "Framework header or module declaration is missing: " + binary);
                Map<String, Object> receipt = new LinkedHashMap<>();
                receipt.put("mode", mode); receipt.put("platform", entry.get("SupportedPlatform"));
                receipt.put("environment", entry.getOrDefault("SupportedPlatformVariant", "device"));
                receipt.put("binary", binary.toRealPath().toString()); receipt.put("size", data.length); receipt.put("sha256", PublicationSupport.hash("sha256", data));
                receipt.put("architectures", architectures); receipt.put("mach_o_platform", expected); receipts.add(receipt);
            }
        }
        return receipts;
    }

    private static Map<String, Object> plist(Path path, Command command) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        if (JniArtifactVerifier.Binary.starts(bytes, "bplist".getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
            return PublicationSupport.object(Json.parse(command.run(List.of("plutil", "-convert", "json", "-o", "-", "--", path.toString()))));
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
            Element root = factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes)).getDocumentElement();
            require(root.getTagName().equals("plist"), "Expected a property list");
            return PublicationSupport.object(plistValue(children(root).getFirst()));
        } catch (Exception error) { throw new IOException("Invalid XCFramework Info.plist: " + path + ": " + error.getMessage(), error); }
    }
    private static List<Element> children(Element element) {
        List<Element> result = new ArrayList<>();
        for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) if (node instanceof Element child) result.add(child);
        return result;
    }
    private static Object plistValue(Element element) {
        return switch (element.getTagName()) {
            case "dict" -> {
                List<Element> values = children(element); require(values.size() % 2 == 0, "Invalid plist dictionary");
                Map<String, Object> result = new LinkedHashMap<>();
                for (int i = 0; i < values.size(); i += 2) {
                    require(values.get(i).getTagName().equals("key"), "Invalid plist dictionary key");
                    String key = values.get(i).getTextContent(); require(!result.containsKey(key), "Duplicate plist dictionary key");
                    result.put(key, plistValue(values.get(i + 1)));
                }
                yield result;
            }
            case "array" -> children(element).stream().map(AppleArtifactsVerifier::plistValue).toList();
            case "string", "key", "date", "data" -> element.getTextContent();
            case "integer" -> Long.parseLong(element.getTextContent());
            case "real" -> Double.parseDouble(element.getTextContent());
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException("Unknown plist type: " + element.getTagName());
        };
    }
    public static int run(String... args) throws IOException {
        PublicationSupport.Options options = PublicationSupport.Options.parse(args, Set.of("module", "receipt"), Set.of());
        List<Map<String, Object>> receipts = verify(options.path(), options.one("module"));
        Json.write(Path.of(options.one("receipt")), Map.of("xcframework_slices", receipts));
        System.out.println("Verified " + receipts.size() + " static Apple framework slices. Receipt: " + options.one("receipt"));
        return 0;
    }
    public static void main(String[] args) throws IOException { System.exit(run(args)); }
}
