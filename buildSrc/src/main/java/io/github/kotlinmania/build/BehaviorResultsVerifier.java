package io.github.kotlinmania.build;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Requires actual successful named behavior cases in Gradle's test XML. */
public final class BehaviorResultsVerifier {
    private BehaviorResultsVerifier() {}

    public static int verify(Path directory, List<String> required) throws Exception {
        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(directory)) try (var paths = Files.walk(directory)) {
            files.addAll(paths.filter(Files::isRegularFile).filter(path -> path.getFileName().toString().startsWith("TEST-") && path.getFileName().toString().endsWith(".xml")).sorted().toList());
        }
        require(!files.isEmpty(), "No actual test XML in " + directory);
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        List<Element> cases = new ArrayList<>();
        for (Path file : files) {
            NodeList nodes = factory.newDocumentBuilder().parse(file.toFile()).getElementsByTagName("testcase");
            for (int i = 0; i < nodes.getLength(); i++) cases.add((Element) nodes.item(i));
        }
        require(!cases.isEmpty(), "Reports contain no executed test cases");
        for (Element test : cases) {
            for (int i = 0; i < test.getChildNodes().getLength(); i++) {
                String kind = test.getChildNodes().item(i).getNodeName();
                require(!List.of("failure", "error", "skipped").contains(kind), "Unsuccessful test: " + test.getAttribute("classname") + "." + test.getAttribute("name"));
            }
        }
        for (String name : required) require(cases.stream().anyMatch(test -> test.getAttribute("name").contains(name)), "Required behavioral test did not execute: " + name);
        System.out.println(directory + ": " + cases.size() + " executed and passed; 0 failed, 0 skipped; required names verified");
        return cases.size();
    }

    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }

    public static int run(String... args) throws Exception {
        Path directory = null;
        List<String> required = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--require") && i + 1 < args.length) required.add(args[++i]);
            else if (!args[i].startsWith("-") && directory == null) directory = Path.of(args[i]);
            else throw new IllegalArgumentException("Usage: BehaviorResultsVerifier <directory> [--require <name> ...]");
        }
        require(directory != null, "A test-result directory is required");
        verify(directory, required);
        return 0;
    }

    public static void main(String[] args) throws Exception { System.exit(run(args)); }
}
