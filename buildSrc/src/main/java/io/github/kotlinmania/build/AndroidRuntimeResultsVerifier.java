package io.github.kotlinmania.build;

import static io.github.kotlinmania.build.AndroidPublicationVerifier.PublicationSupport.require;
import io.github.kotlinmania.build.AndroidPublicationVerifier.PublicationSupport;
import io.github.kotlinmania.build.JniArtifactVerifier.Binary;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.w3c.dom.Element;

/** Verifies actual Android property tests and the exact device binary recorded for their run. */
public final class AndroidRuntimeResultsVerifier {
    private AndroidRuntimeResultsVerifier() {}
    public static final Set<String> INPUT_CASES = Set.of("rejectsInteriorNulPropertyNames", "rejectsNonTerminatedCStringNames",
        "rejectsTrailingBytesAfterCStringTerminator", "rejectsMalformedUtf8CStringNames", "rejectsEmptyCStringBuffer");
    public static final Set<String> DEVICE_CASES = union(INPUT_CASES, "readsRealAndroidBuildProperties", "readsValidCStringAndMissingProperty");
    public static final Set<String> NATIVE_CASES = union(INPUT_CASES, "readsRealAndroidProperty", "legacyAndAvailableCallbackReadTheSameProperty",
        "missingPropertiesReturnNull", "dynamicallyResolvesRealLibcSymbols");
    private record Cases(List<String> names, List<String> files) {}

    private static Set<String> union(Set<String> values, String... additions) {
        Set<String> result = new TreeSet<>(values); result.addAll(List.of(additions)); return Set.copyOf(result);
    }
    private static Cases readCases(Path root, Path directory, Set<String> expected) throws IOException {
        List<Path> files;
        try (var paths = Files.walk(directory)) { files = paths.filter(p -> p.toString().endsWith(".xml") && Files.isRegularFile(p)).sorted().toList(); }
        require(!files.isEmpty(), "No test XML in " + directory);
        List<String> names = new ArrayList<>();
        for (Path file : files) {
            Element suite = PublicationSupport.xml(Files.readAllBytes(file));
            for (String key : List.of("failures", "errors", "skipped")) require("0".equals(suite.getAttribute(key)), file + ": " + key + " is not explicitly zero");
            var cases = suite.getElementsByTagName("testcase");
            for (int i = 0; i < cases.getLength(); i++) {
                Element testcase = (Element) cases.item(i);
                for (var child = testcase.getFirstChild(); child != null; child = child.getNextSibling()) {
                    require(!Set.of("failure", "error", "skipped").contains(child.getNodeName()), "Unsuccessful case in " + file);
                }
                require(testcase.hasAttribute("name"), "Unnamed testcase in " + file);
                names.add(testcase.getAttribute("name"));
            }
        }
        names.sort(String::compareTo);
        require(names.equals(new ArrayList<>(new TreeSet<>(expected))), "Unexpected or missing test cases: " + names);
        return new Cases(names, files.stream().map(p -> root.relativize(p).toString().replace('\\', '/')).toList());
    }

    public static Map<String, Object> verify(Path root, String target, Path identityPath, String expectedAvd) throws IOException {
        return verify(root, target, identityPath, expectedAvd, command(root, List.of("git", "rev-parse", "HEAD")).strip());
    }
    static Map<String, Object> verify(Path root, String target, Path identityPath, String expectedAvd, String sourceHead) throws IOException {
        require(Set.of("androidNativeArm64", "androidNativeX64").contains(target), "Unsupported Android runtime target: " + target);
        String abi = target.equals("androidNativeArm64") ? "arm64-v8a" : "x86_64";
        int machine = target.equals("androidNativeArm64") ? 183 : 62;
        Map<String, Object> identity = PublicationSupport.object(Json.read(identityPath));
        require("35".equals(identity.get("api")), "Unexpected runtime API: " + identity);
        require(abi.equals(identity.get("abi")), "Unexpected runtime ABI: " + identity);
        require(expectedAvd.equals(identity.get("avd")), "Unexpected AVD: " + identity);
        require("emulator-5554".equals(identity.get("serial")), "Unexpected owned device serial: " + identity);
        Cases device = readCases(root, root.resolve("build/outputs/androidTest-results/connected"), DEVICE_CASES);
        Cases host = readCases(root, root.resolve("build/test-results/testAndroidHostTest"), INPUT_CASES);
        String text = Files.readString(root.resolve("build/test-results/" + target + "DeviceTest/runtime.log"));
        require(!text.contains("testFailed") && !text.contains("testIgnored"), "Native failures/ignored tests found");
        List<String> finished = Pattern.compile("##teamcity\\[testFinished name='([^']+)'").matcher(text).results()
            .map(match -> match.group(1)).sorted().toList();
        require(finished.equals(new ArrayList<>(new TreeSet<>(NATIVE_CASES))), "Unexpected native execution: " + finished);
        byte[] payload = Files.readAllBytes(root.resolve("build/bin/" + target + "/debugTest/test.kexe"));
        Binary.elf(payload, machine, "Native test binary");
        String digest = PublicationSupport.hash("sha256", payload);
        require(digest.equals(identity.get("device_binary_sha256")), "Pushed native binary differs from built binary");
        List<Path> sourceFiles = new ArrayList<>();
        if (Files.isDirectory(root.resolve("src"))) try (var paths = Files.walk(root.resolve("src"))) {
            sourceFiles.addAll(paths.filter(p -> p.toString().endsWith(".kt") && Files.isRegularFile(p)).toList());
        }
        sourceFiles.addAll(List.of(root.resolve("build.gradle.kts"), root.resolve("gradle.properties"), root.resolve("gradle/libs.versions.toml")));
        sourceFiles.sort(Path::compareTo);
        Map<String, String> hashes = new LinkedHashMap<>();
        for (Path path : sourceFiles) hashes.put(root.relativize(path).toString().replace('\\', '/'), PublicationSupport.fingerprint(path));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("head", sourceHead); result.put("target", target); result.put("identity", identity); result.put("native_binary_sha256", digest);
        result.put("native_binary_bytes", payload.length); result.put("host_cases", host.names()); result.put("instrumentation_cases", device.names());
        result.put("native_cases", finished); result.put("instrumentation_xml", device.files()); result.put("host_xml", host.files());
        result.put("failed", 0); result.put("skipped", 0); result.put("source_sha256", hashes);
        return result;
    }

    static String command(Path root, List<String> arguments) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(arguments).directory(root.toFile()).redirectError(ProcessBuilder.Redirect.INHERIT);
        builder.environment().put("GIT_OPTIONAL_LOCKS", "0");
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try { require(process.waitFor() == 0, "Command failed: " + arguments + ": " + output); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException("Interrupted verifier command", error); }
        return output;
    }
    public static int run(String... args) throws IOException {
        PublicationSupport.Options options = PublicationSupport.Options.parse(args, Set.of("root", "device-identity", "expected-avd"), Set.of());
        Path root = options.many("root").isEmpty() ? Path.of(".").toAbsolutePath().normalize() : Path.of(options.one("root"));
        String target = options.path().toString();
        Map<String, Object> result = verify(root, target, Path.of(options.one("device-identity")), options.one("expected-avd"));
        Path report = root.resolve("build/reports/runtime/" + target + "-verified.json");
        Json.write(report, result);
        System.out.println(Json.stringify(Map.of("report", report.toString(), "host", INPUT_CASES.size(), "instrumentation", DEVICE_CASES.size(),
            "native", NATIVE_CASES.size(), "failed", 0, "skipped", 0, "binary_sha256", result.get("native_binary_sha256"))));
        return 0;
    }
    public static void main(String[] args) throws IOException { System.exit(run(args)); }
}
