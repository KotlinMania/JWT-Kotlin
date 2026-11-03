package io.github.kotlinmania.build;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Compiles positive and negative typed contracts against an explicitly captured producer. */
public final class StrumContracts {
    private StrumContracts() {}
    private static void require(boolean value, String message) { if (!value) throw new IllegalArgumentException(message); }
    private static String hash(Path file) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))); }
    private static List<Path> paths(Object value) { return ((List<?>) value).stream().map(item -> Path.of(String.valueOf(item))).toList(); }

    public static int run(String... args) throws Exception {
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            require(i + 1 < args.length && List.of("--root", "--classpath-receipt", "--output").contains(args[i]), "Unknown or incomplete option");
            require(options.put(args[i], args[i + 1]) == null, "Repeated option");
        }
        require(options.containsKey("--classpath-receipt") && options.containsKey("--output"), "Expected --classpath-receipt and --output");
        Path project = Path.of(options.getOrDefault("--root", ".")).toRealPath();
        Path root = project.resolve("verification/typed-contracts");
        Path output = Path.of(options.get("--output")).toAbsolutePath().normalize();
        require(output.startsWith(project.resolve("build")) && !Files.exists(output), "Use a new output directory inside this project's build folder");
        Files.createDirectories(output);
        require(output.toRealPath().startsWith(project.resolve("build").toRealPath()), "Output resolves outside the project's build folder");
        Path receipt = Path.of(options.get("--classpath-receipt"));
        Map<?, ?> data = (Map<?, ?>) Json.read(receipt);
        List<Path> compiler = paths(data.get("compiler"));
        List<Path> dependencies = new ArrayList<>(List.of(Path.of(String.valueOf(data.get("jar")))));
        dependencies.addAll(paths(data.get("dependencies")));
        require(compiler.stream().anyMatch(path -> path.getFileName().toString().equals("kotlin-compiler-embeddable-2.4.20.jar")), "Expected captured Kotlin 2.4.20 compiler");
        List<Path> inputs = new ArrayList<>(compiler); inputs.addAll(dependencies); inputs.add(receipt);
        try (var files = Files.list(root)) { inputs.addAll(files.filter(path -> path.toString().endsWith(".kt")).sorted().toList()); }
        Map<String, String> frozen = new LinkedHashMap<>();
        for (Path path : inputs) frozen.put(path.toString(), hash(path));
        Json.write(output.resolve("frozen-inputs.json"), frozen);
        Map<String, String> cases = new LinkedHashMap<>(); cases.put("Positive", null);
        cases.put("WrongElement", "UPPER_BOUND_VIOLATED"); cases.put("WrongDiscriminant", "RETURN_TYPE_MISMATCH_ON_OVERRIDE");
        cases.put("WrongReference", "RETURN_TYPE_MISMATCH_ON_OVERRIDE"); cases.put("WrongVariants", "PROPERTY_TYPE_MISMATCH_ON_OVERRIDE");
        cases.put("WrongVariantIterator", "UPPER_BOUND_VIOLATED"); cases.put("MissingReverse", "ABSTRACT_MEMBER_NOT_IMPLEMENTED");
        cases.put("WrongAssociatedClone", "UPPER_BOUND_VIOLATED");
        Map<String, Object> results = new LinkedHashMap<>();
        String java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        for (var item : cases.entrySet()) {
            String name = item.getKey(), expected = item.getValue();
            Path destination = output.resolve(name); Files.createDirectories(destination);
            List<String> command = List.of(java, "-Xmx512m", "-cp", String.join(File.pathSeparator, compiler.stream().map(Path::toString).toList()),
                    "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-jvm-target", "21", "-Werror", "-Xrender-internal-diagnostic-names",
                    "-classpath", String.join(File.pathSeparator, dependencies.stream().map(Path::toString).toList()), "-d", destination.toString(), root.resolve(name + ".kt").toString());
            Path log = output.resolve(name + ".log");
            Process process = new ProcessBuilder(command).directory(project.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            Json.write(output.resolve("active-process.json"), Map.of("pid", process.pid(), "case", name, "active", true));
            int exit;
            try { exit = process.waitFor(); }
            catch (InterruptedException error) { process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly(); Thread.currentThread().interrupt(); throw error; }
            String text = Files.readString(log);
            List<String> diagnostics = Pattern.compile("\\[([A-Z_]+)\\]").matcher(text).results().map(match -> match.group(1)).toList();
            Json.write(output.resolve("active-process.json"), Map.of("pid", process.pid(), "case", name, "active", false, "exit", exit));
            Map<String, Object> result = new LinkedHashMap<>(); result.put("exit", exit); result.put("diagnostics", diagnostics); result.put("expected", expected); result.put("command", command);
            results.put(name, result); Json.write(output.resolve("case-results.json"), results);
            require(expected == null ? exit == 0 && diagnostics.isEmpty() : exit != 0 && diagnostics.contains(expected) && diagnostics.stream().noneMatch(d -> d.contains("UNRESOLVED")), "Unexpected compiler result for " + name + ": " + text);
        }
        for (var input : frozen.entrySet()) require(hash(Path.of(input.getKey())).equals(input.getValue()), "Input changed: " + input.getKey());
        Map<String, Object> result = Map.of("positive_cases", 1, "negative_cases", 7, "all_expected", true, "producer_jar_sha256", hash(dependencies.getFirst()),
                "warnings_as_errors", true, "source_and_classpath_unchanged", true, "scope", "Actual Kotlin/JVM compiler contracts, not runtime or published-consumer acceptance");
        Json.write(output.resolve("verified-result.json"), result); System.out.println(Json.stringify(result)); return 0;
    }
}
