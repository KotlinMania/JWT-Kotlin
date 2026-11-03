package io.github.kotlinmania.build;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** Executes producer-attested Kotlin/Native tests on an actual Linux ARM64 host. */
public final class LinuxArm64TestRunner {
    private static final Pattern PLANNED = Pattern.compile("(?m)^\\[==========\\] Running (\\d+) tests from \\d+ test cases\\.$");
    private static final Pattern SUMMARY = Pattern.compile("(?m)^\\[  PASSED  \\] (\\d+) tests\\.$");
    private static final Pattern STARTED = Pattern.compile("(?m)^\\[ RUN      \\] (.+)$");
    private static final Pattern FINISHED = Pattern.compile("(?m)^\\[       OK \\] (.+) \\((\\d+) ms\\)$");
    private static final Pattern DISALLOWED = Pattern.compile("\\[\\s*(?:FAILED|SKIPPED|DISABLED)\\s*\\]|YOU HAVE \\d+ DISABLED");

    private LinuxArm64TestRunner() {}

    public record TestCase(String name, long milliseconds) {}

    public static List<TestCase> passedCases(String output) {
        List<String> planned = groups(PLANNED, output);
        List<String> summary = groups(SUMMARY, output);
        List<String> started = groups(STARTED, output);
        List<TestCase> finished = new ArrayList<>();
        Matcher matcher = FINISHED.matcher(output);
        while (matcher.find()) finished.add(new TestCase(matcher.group(1), Long.parseLong(matcher.group(2))));
        if (planned.size() != 1 || summary.size() != 1 || Integer.parseInt(planned.getFirst()) == 0) {
            throw new IllegalArgumentException("Missing, empty or repeated Native test run");
        }
        if (DISALLOWED.matcher(output).find()) {
            throw new IllegalArgumentException("Native tests failed, were skipped or were disabled");
        }
        List<String> names = finished.stream().map(TestCase::name).toList();
        if (new HashSet<>(started).size() != started.size() || new HashSet<>(names).size() != names.size()) {
            throw new IllegalArgumentException("Duplicate Native test result");
        }
        if (started.size() != Integer.parseInt(planned.getFirst()) || !names.equals(started)
                || names.size() != Integer.parseInt(summary.getFirst())) {
            throw new IllegalArgumentException("Incomplete Native test execution");
        }
        Pattern completion = Pattern.compile("(?m)^\\[==========\\] (" + names.size()
                + ") tests from \\d+ test cases ran\\. \\(\\d+ ms total\\)$");
        if (groups(completion, output).size() != 1) {
            throw new IllegalArgumentException("Missing or repeated Native test completion");
        }
        return finished;
    }

    public static String verifyBinary(Path binary, String system, String machine) throws IOException {
        if (!system.equals("Linux") || !Set.of("aarch64", "arm64").contains(machine)) {
            throw new IllegalArgumentException("Actual Linux ARM64 execution required; got " + system + "/" + machine);
        }
        NativeTestRunner.verifyBinaryIdentity(binary, "linuxArm64");
        String digest = sha256(binary);
        String text = Files.readString(binary.resolveSibling(binary.getFileName() + ".sha256")).strip();
        List<String> manifest = text.isEmpty() ? List.of() : List.of(text.split("\\s+"));
        if (!manifest.equals(List.of(digest, binary.getFileName().toString()))) {
            throw new IllegalArgumentException("Executable differs from the producer's SHA256 manifest");
        }
        return digest;
    }

    public static void writeJUnit(Path report, List<TestCase> cases) throws Exception {
        DocumentBuilderFactory documents = DocumentBuilderFactory.newInstance();
        documents.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        Document document = documents.newDocumentBuilder().newDocument();
        Element suite = document.createElement("testsuite");
        suite.setAttribute("name", "linuxArm64Runtime");
        suite.setAttribute("tests", Integer.toString(cases.size()));
        for (String attribute : List.of("failures", "errors", "skipped")) suite.setAttribute(attribute, "0");
        document.appendChild(suite);
        for (TestCase test : cases) {
            Element element = document.createElement("testcase");
            int separator = test.name().lastIndexOf('.');
            element.setAttribute("classname", separator == -1 ? "" : test.name().substring(0, separator));
            element.setAttribute("name", test.name().substring(separator + 1));
            element.setAttribute("time", BigDecimal.valueOf(test.milliseconds(), 3).stripTrailingZeros().toPlainString());
            suite.appendChild(element);
        }
        TransformerFactory transformers = TransformerFactory.newInstance();
        transformers.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        var transformer = transformers.newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.transform(new DOMSource(document), new StreamResult(report.toFile()));
    }

    public static int run(String... arguments) {
        Path binary = null;
        Path reports = null;
        String revision = null;
        try {
            for (int index = 0; index < arguments.length; index++) {
                String argument = arguments[index];
                if (argument.equals("--help")) {
                    System.out.println("LinuxArm64TestRunner BINARY --revision REVISION --reports DIRECTORY");
                    return 0;
                }
                if (argument.startsWith("--")) {
                    if (++index == arguments.length) throw new IllegalArgumentException("Missing value for " + argument);
                    switch (argument) {
                        case "--revision" -> revision = arguments[index];
                        case "--reports" -> reports = Path.of(arguments[index]).toAbsolutePath().normalize();
                        default -> throw new IllegalArgumentException("Unknown option: " + argument);
                    }
                } else if (binary == null) {
                    binary = Path.of(argument).toAbsolutePath().normalize();
                } else {
                    throw new IllegalArgumentException("Unexpected argument: " + argument);
                }
            }
            if (binary == null || reports == null || revision == null || revision.isBlank()) {
                throw new IllegalArgumentException("BINARY, --revision and --reports are required");
            }
        } catch (IllegalArgumentException exception) {
            System.err.println(exception.getMessage());
            return 1;
        }
        String system = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("linux") ? "Linux" : System.getProperty("os.name");
        String machine = System.getProperty("os.arch");
        Map<String, Object> receipt = new LinkedHashMap<>(Map.of("revision", revision, "system", system, "machine", machine, "passed", false));
        Path temporary = null;
        Process process = null;
        int exit = 1;
        try {
            Files.createDirectories(reports);
            Files.deleteIfExists(reports.resolve("TEST-linuxArm64Runtime.xml"));
            String digest = verifyBinary(binary, system, machine);
            receipt.put("binary_sha256", digest);
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(binary);
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(binary, permissions);
            temporary = Files.createTempDirectory(binary.getParent(), "linux-arm64-runtime-");
            Path log = reports.resolve("native.log");
            ProcessBuilder builder = new ProcessBuilder(binary.toRealPath().toString(), "--ktest_logger=GTEST")
                    .redirectErrorStream(true).redirectOutput(log.toFile());
            builder.environment().putAll(Map.of("TMPDIR", temporary.toString(), "TMP", temporary.toString(), "TEMP", temporary.toString()));
            process = builder.start();
            process.getOutputStream().close();
            boolean finished = process.waitFor(30, TimeUnit.MINUTES);
            if (!finished) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor();
            }
            String output = Files.readString(log);
            System.out.print(output);
            receipt.put("exit_code", finished ? process.exitValue() : 124);
            if (!finished) throw new IOException("Native executable timed out after 1800 seconds");
            if (process.exitValue() != 0) throw new IllegalArgumentException("Native executable exited " + process.exitValue());
            List<TestCase> cases = passedCases(output);
            if (!sha256(binary).equals(digest)) throw new IllegalArgumentException("Attested executable changed during execution");
            writeJUnit(reports.resolve("TEST-linuxArm64Runtime.xml"), cases);
            receipt.put("passed", true);
            receipt.put("executed_tests", cases.stream().map(TestCase::name).toList());
            exit = 0;
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            receipt.put("error", exception.getMessage() == null ? exception.toString() : exception.getMessage());
            System.err.println("Linux ARM64 execution failed: " + receipt.get("error"));
        } finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
            try {
                if (temporary != null) {
                    try (var entries = Files.walk(temporary)) {
                        for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) Files.delete(entry);
                    }
                }
                Json.write(reports.resolve("execution.json"), receipt);
            } catch (IOException exception) {
                System.err.println("Could not clean or report Linux ARM64 execution: " + exception.getMessage());
                exit = 1;
            }
        }
        return exit;
    }

    public static void main(String[] arguments) {
        System.exit(run(arguments));
    }

    private static List<String> groups(Pattern pattern, String text) {
        List<String> result = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) result.add(matcher.group(1));
        return result;
    }

    private static String sha256(Path binary) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(binary)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
