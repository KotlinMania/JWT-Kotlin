package io.github.kotlinmania.build;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Streams a command and terminates its process tree when its wall-clock limit expires. */
public final class CommandTimeout {
    private CommandTimeout() {}

    public static int execute(List<String> command, Path directory, Map<String, String> environment, long seconds, long grace) throws Exception {
        if (command.isEmpty() || seconds <= 0 || grace < 0) throw new IllegalArgumentException("Expected command, positive timeout, and nonnegative grace");
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile()).inheritIO();
        builder.environment().putAll(environment);
        Process process = builder.start();
        try {
            if (process.waitFor(seconds, TimeUnit.SECONDS)) return process.exitValue();
            System.err.println("[TIMEOUT] " + seconds + "s exceeded; terminating command and children");
            List<ProcessHandle> children = process.descendants().toList();
            children.forEach(ProcessHandle::destroy); process.destroy();
            if (!process.waitFor(grace, TimeUnit.SECONDS)) process.destroyForcibly();
            children.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
            process.waitFor(); return 124;
        } catch (InterruptedException error) {
            process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly();
            Thread.currentThread().interrupt(); throw error;
        }
    }

    public static int run(String... args) throws Exception {
        long seconds = -1, grace = 5; Path cwd = Path.of(".");
        Map<String, String> environment = new LinkedHashMap<>(); List<String> command = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String option = args[i];
            if (option.equals("--")) { command.addAll(List.of(args).subList(i + 1, args.length)); break; }
            if (!option.startsWith("--")) { command.addAll(List.of(args).subList(i, args.length)); break; }
            if (i + 1 >= args.length) throw new IllegalArgumentException("Missing value for " + option);
            String value = args[++i];
            switch (option) {
                case "--timeout" -> seconds = Long.parseLong(value);
                case "--grace" -> grace = Long.parseLong(value);
                case "--cwd" -> cwd = Path.of(value);
                case "--env" -> { int equal = value.indexOf('='); if (equal < 1) throw new IllegalArgumentException("Expected NAME=value"); environment.put(value.substring(0, equal), value.substring(equal + 1)); }
                default -> throw new IllegalArgumentException("Unknown option: " + option);
            }
        }
        return execute(command, cwd, environment, seconds, grace);
    }
    public static void main(String[] args) throws Exception { System.exit(run(args)); }
}
