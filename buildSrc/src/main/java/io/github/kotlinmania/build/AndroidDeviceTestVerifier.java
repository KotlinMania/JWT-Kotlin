package io.github.kotlinmania.build;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

/** Rejects a connected-device task that succeeds without executing tests. */
public final class AndroidDeviceTestVerifier {
    private AndroidDeviceTestVerifier() {}

    public static void verify(File resultsDirectory) throws Exception {
        if (!resultsDirectory.isDirectory()) {
            throw new IllegalStateException("Missing Android device-test results: " + resultsDirectory);
        }
        List<Path> reports;
        try (var paths = Files.walk(resultsDirectory.toPath())) {
            reports = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith("TEST-"))
                    .filter(path -> path.getFileName().toString().endsWith(".xml"))
                    .toList();
        }
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        int executed = 0;
        for (var report : reports) {
            Element root = factory.newDocumentBuilder().parse(report.toFile()).getDocumentElement();
            int cases = root.getElementsByTagName("testcase").getLength();
            int declared = count(root, "tests");
            if (cases == 0 || declared != cases) {
                throw new IllegalStateException("Android device-test report has no executed tests or inconsistent counts: " + report);
            }
            if (count(root, "failures") != 0 || count(root, "errors") != 0 || count(root, "skipped") != 0
                    || root.getElementsByTagName("failure").getLength() != 0
                    || root.getElementsByTagName("error").getLength() != 0
                    || root.getElementsByTagName("skipped").getLength() != 0) {
                throw new IllegalStateException("Android device tests failed or skipped: " + report);
            }
            executed += cases;
        }
        if (executed == 0) {
            throw new IllegalStateException("No Android device tests executed: " + resultsDirectory);
        }
        System.out.println("Verified " + executed + " executed Android device tests");
    }

    private static int count(Element root, String attribute) {
        String value = root.getAttribute(attribute);
        return value.isEmpty() ? 0 : Integer.parseInt(value);
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) throw new IllegalArgumentException("Expected Android results directory");
        verify(new File(arguments[0]));
    }
}
