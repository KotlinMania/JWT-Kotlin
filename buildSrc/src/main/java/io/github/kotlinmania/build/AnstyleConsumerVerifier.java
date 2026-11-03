package io.github.kotlinmania.build;

import static io.github.kotlinmania.build.AndroidPublicationVerifier.PublicationSupport.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.w3c.dom.Element;

/** Checks actual Anstyle consumer execution, variant selection and staged payload identity. */
public final class AnstyleConsumerVerifier {
    private AnstyleConsumerVerifier() {}
    private static String hash(Path path) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))); }

    public static Map<String, Object> verify(Path fixture, Path repository, String version) throws Exception {
        Path reports = fixture.resolve("build/reports/publication");
        Map<String, Object> variant = object(Json.read(reports.resolve("selected-variant.json")));
        require("io.github.kotlinmania".equals(variant.get("group")) && "anstyle-kotlin-android".equals(variant.get("module"))
                && version.equals(variant.get("version")), "Unexpected selected Anstyle publication: " + variant);
        Map<String, Object> attrs = object(variant.get("attributes"));
        require("androidJvm".equals(attrs.get("org.jetbrains.kotlin.platform.type")) && "aar".equals(attrs.get("org.gradle.libraryelements"))
                && "android".equals(attrs.get("org.gradle.jvm.environment")), "Wrong Android variant: " + attrs);
        Map<String, Object> duplicate = object(Json.read(reports.resolve("duplicates.json")));
        require(Map.of("compile", Map.of(), "runtime", Map.of()).equals(duplicate.get("duplicates_by_configuration")), "Duplicate consumer classes");
        List<Map<String, Object>> tests = new ArrayList<>();
        Set<String> expectedCases = Set.of("paletteConversion", "rgbRendering", "stylesDoNotMutateAliases", "effectsConversion", "parserPreservesUnicodeScalars");
        for (String task : List.of("testAndroidHostTest", "jvmTest")) {
            Path results = fixture.resolve("build/test-results/" + task);
            require(Files.isDirectory(results), "Missing actual consumer test reports: " + task);
            List<String> cases = new ArrayList<>(); Set<String> names = new LinkedHashSet<>();
            try (var files = Files.list(results)) {
                for (Path path : files.filter(p -> p.toString().endsWith(".xml")).sorted().toList()) {
                    Element suite = xml(Files.readAllBytes(path));
                    for (String verdict : List.of("failure", "error", "skipped")) require(suite.getElementsByTagName(verdict).getLength() == 0, "Unsuccessful consumer test: " + path);
                    var nodes = suite.getElementsByTagName("testcase");
                    for (int i = 0; i < nodes.getLength(); i++) {
                        Element item = (Element) nodes.item(i);
                        String name = item.getAttribute("name");
                        cases.add(item.getAttribute("classname") + "." + name);
                        names.add(name.endsWith("[jvm]") ? name.substring(0, name.length() - 5) : name);
                    }
                }
            }
            require(cases.size() == 5 && names.equals(expectedCases), "Missing, duplicate or unexpected " + task + " cases: " + cases);
            tests.add(Map.of("task", task, "cases", cases, "executed", cases.size(), "failures", 0, "errors", 0, "skipped", 0));
        }
        Map<String, Object> selected = new LinkedHashMap<>();
        for (String platform : List.of("android", "jvm")) {
            String report = platform.equals("android") ? "raw-classpaths.json" : "jvm-classpaths.json";
            String extension = platform.equals("android") ? "aar" : "jar";
            Map<String, Object> paths = object(Json.read(reports.resolve(report)));
            String module = "anstyle-kotlin-" + platform;
            String expected = hash(repository.resolve("io/github/kotlinmania/" + module + "/" + version + "/" + module + "-" + version + "." + extension));
            Map<String, Object> scopes = new LinkedHashMap<>();
            for (String scope : List.of("compile", "runtime")) {
                List<String> matches = array(paths.get(scope)).stream().map(String::valueOf).filter(value -> value.replace('\\', '/').contains("/" + module + "/" + version + "/") && value.endsWith("." + extension)).distinct().sorted().toList();
                require(matches.size() == 1, "Expected one selected " + platform + " " + scope + " artifact: " + matches);
                require(hash(Path.of(matches.getFirst())).equals(expected), "Selected payload differs from staged Anstyle artifact");
                scopes.put(scope, Map.of("path", matches.getFirst(), "sha256", expected));
            }
            selected.put(platform, scopes);
        }
        return Map.of("status", "passed", "version", version, "availability", "Filesystem staging only; not Central publication",
                "tests", tests, "selected_variant", variant, "selected_payloads", selected, "duplicates_by_configuration", duplicate.get("duplicates_by_configuration"));
    }

    public static int run(String... args) throws Exception {
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            require(i + 1 < args.length && Set.of("--fixture", "--repository", "--version", "--receipt").contains(args[i]), "Unknown/incomplete option");
            require(options.put(args[i], args[i + 1]) == null, "Repeated option");
        }
        require(options.size() == 4, "Expected --fixture --repository --version --receipt");
        Json.write(Path.of(options.get("--receipt")), verify(Path.of(options.get("--fixture")), Path.of(options.get("--repository")), options.get("--version")));
        System.out.println("PASS: five Android host and five JVM cases, selected payload hashes and independent classpaths"); return 0;
    }
}
