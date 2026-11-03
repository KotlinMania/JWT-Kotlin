package io.github.kotlinmania.build;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Runs the URL project's native drift analyzer with terminal-compatible output. */
public final class AstDistanceRunner {
    private AstDistanceRunner() {}
    public static int run(String... args) throws Exception {
        if (args.length != 0 && (args.length != 2 || !args[0].equals("--root"))) throw new IllegalArgumentException("Expected optional --root PROJECT");
        Path root = Path.of(args.length == 0 ? "." : args[1]).toRealPath();
        Path tool = root.resolve("tools/ast_distance");
        if (!Files.isRegularFile(tool)) throw new IllegalArgumentException("Missing native analyzer: " + tool);
        List<String> analyzer = List.of(tool.toString(), "--deep", "tmp/url/src", "rust", "src/common/commonMain/kotlin/io/github/kotlinmania/url", "kotlin");
        String os = System.getProperty("os.name"); List<String> command = new ArrayList<>();
        if (os.startsWith("Mac")) { command.addAll(List.of("/usr/bin/script", "-q", "/dev/null")); command.addAll(analyzer); }
        else if (os.startsWith("Linux")) {
            String shellCommand = analyzer.stream().map(value -> "'" + value.replace("'", "'\\''") + "'").collect(java.util.stream.Collectors.joining(" "));
            command.addAll(List.of("script", "-q", "-e", "-c", shellCommand, "/dev/null"));
        } else command.addAll(analyzer);
        Process process = new ProcessBuilder(command).directory(root.toFile()).inheritIO().start();
        try { return process.waitFor(); }
        catch (InterruptedException error) { process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly(); Thread.currentThread().interrupt(); throw error; }
    }
}
