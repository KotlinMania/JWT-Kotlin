package io.github.kotlinmania.build;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Bounded boot diagnostics and exact emulator identity evidence for the Android CI runtime. */
public final class AndroidEmulatorEvidence {
    private AndroidEmulatorEvidence() {}

    public static void verifyIdentity(Map<String, Object> identity, String serial, String avd, String api, String abi) {
        if (!serial.equals(identity.get("serial")) || !avd.equals(identity.get("avd"))
                || !api.equals(identity.get("api")) || !abi.equals(identity.get("abi"))) {
            throw new IllegalArgumentException("Android device identity mismatch: " + Json.stringify(identity));
        }
    }

    public static String binaryDigest(String output) {
        String[] fields = output.strip().split("\\s+");
        if (fields.length == 0 || !fields[0].matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("Device did not report a SHA-256 binary digest");
        }
        return fields[0].toLowerCase(java.util.Locale.ROOT);
    }

    /** Every probe gets its own complete receipt, including timeout and process-launch failures. */
    public static Map<String, Object> probe(List<String> command, Path directory, String name, long started, Duration timeout)
            throws IOException, InterruptedException {
        Files.createDirectories(directory);
        Path stdout = Files.createTempFile(directory, "adb-stdout-", ".log");
        Path stderr = Files.createTempFile(directory, "adb-stderr-", ".log");
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("command", command);
        receipt.put("elapsed_seconds", Math.round((System.nanoTime() - started) / 1_000_000.0) / 1000.0);
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command).redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
            String temporary = directory.toAbsolutePath().toString();
            builder.environment().putAll(Map.of("TMPDIR", temporary, "TMP", temporary, "TEMP", temporary));
            process = builder.start();
            process.getOutputStream().close();
            if (process.waitFor(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS)) {
                receipt.put("exit_code", process.exitValue());
            } else {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor();
                receipt.put("timed_out", true);
            }
            receipt.put("stdout", Files.readString(stdout));
            receipt.put("stderr", Files.readString(stderr));
        } catch (IOException exception) {
            receipt.put("error", exception.getMessage());
        } finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
            Files.deleteIfExists(stdout);
            Files.deleteIfExists(stderr);
        }
        Json.write(directory.resolve(name + ".json"), receipt);
        return receipt;
    }

    public static int run(String... arguments) {
        try {
            if (arguments.length == 0) throw new IllegalArgumentException("Expected boot, identity or hash mode");
            String mode = arguments[0];
            Map<String, String> options = new LinkedHashMap<>();
            Set<String> supported = Set.of("--adb", "--serial", "--avd", "--pid", "--report-dir", "--api", "--abi", "--output", "--identity", "--binary");
            for (int index = 1; index < arguments.length; index += 2) {
                if (!supported.contains(arguments[index]) || index + 1 == arguments.length
                        || options.put(arguments[index], arguments[index + 1]) != null) {
                    throw new IllegalArgumentException("Unknown, repeated or incomplete option: " + arguments[index]);
                }
            }
            List<String> adb = List.of(required(options, "--adb"), "-s", required(options, "--serial"));
            if (mode.equals("boot")) {
                Path report = Path.of(required(options, "--report-dir"));
                boot(adb, required(options, "--avd"), Long.parseLong(required(options, "--pid")), report);
            } else if (mode.equals("identity")) {
                Path output = Path.of(required(options, "--output")).toAbsolutePath();
                Path probes = output.getParent().resolve("android-identity-probes");
                Map<String, Object> identity = new LinkedHashMap<>();
                identity.put("serial", required(options, "--serial"));
                identity.put("avd", query(adb, probes, "avd", "emu", "avd", "name").lines().findFirst().orElse(""));
                identity.put("api", query(adb, probes, "api", "shell", "getprop", "ro.build.version.sdk"));
                identity.put("abi", query(adb, probes, "abi", "shell", "getprop", "ro.product.cpu.abi"));
                verifyIdentity(identity, required(options, "--serial"), required(options, "--avd"), required(options, "--api"), required(options, "--abi"));
                Json.write(output, identity);
            } else if (mode.equals("hash")) {
                Path file = Path.of(required(options, "--identity")).toAbsolutePath();
                Map<String, Object> identity = object(Json.read(file));
                Path probes = file.getParent().resolve("android-binary-probes");
                String avd = query(adb, probes, "avd", "emu", "avd", "name").lines().findFirst().orElse("");
                if (!required(options, "--serial").equals(identity.get("serial")) || !avd.equals(identity.get("avd"))) {
                    throw new IllegalArgumentException("Refusing to attest a different Android device");
                }
                identity.put("device_binary_sha256", binaryDigest(query(adb, probes, "binary", "shell", "sha256sum", required(options, "--binary"))));
                Json.write(file, identity);
            } else {
                throw new IllegalArgumentException("Unknown Android evidence mode: " + mode);
            }
            return 0;
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            System.err.println("Android emulator evidence failed: " + exception.getMessage());
            return 1;
        }
    }

    private static void boot(List<String> adb, String expectedAvd, long pid, Path report) throws IOException, InterruptedException {
        if (pid <= 0) throw new IllegalArgumentException("An owned emulator PID is required");
        long started = System.nanoTime();
        long deadline = started + Duration.ofSeconds(600).toNanos();
        String failure = "Android boot did not complete within 600 seconds / 300 attempts";
        for (int attempt = 1; attempt <= 300; attempt++) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) break;
            if (ProcessHandle.of(pid).filter(ProcessHandle::isAlive).isEmpty()) {
                failure = "Owned emulator exited before Android completed boot";
                break;
            }
            Map<String, Object> result = probe(command(adb, "shell", "getprop", "sys.boot_completed"), report,
                    "probe-" + String.format(java.util.Locale.ROOT, "%03d", attempt), started,
                    Duration.ofNanos(Math.min(Duration.ofSeconds(5).toNanos(), remaining)));
            Map<String, Object> printed = new LinkedHashMap<>();
            printed.put("attempt", attempt);
            printed.putAll(result);
            System.out.println(Json.stringify(printed));
            if (Integer.valueOf(0).equals(result.get("exit_code")) && "1".equals(String.valueOf(result.getOrDefault("stdout", "")).strip())) return;
            long pause = Math.max(0, Math.min(Duration.ofSeconds(2).toNanos(), deadline - System.nanoTime()));
            if (pause > 0) TimeUnit.NANOSECONDS.sleep(pause);
        }
        try {
            probe(command(adb, "get-state"), report, "adb-state", started, Duration.ofSeconds(5));
            Map<String, Object> identity = probe(command(adb, "emu", "avd", "name"), report, "avd-identity", started, Duration.ofSeconds(5));
            String actual = String.valueOf(identity.getOrDefault("stdout", "")).lines().findFirst().orElse("").strip();
            if (Integer.valueOf(0).equals(identity.get("exit_code")) && expectedAvd.equals(actual)) {
                probe(command(adb, "shell", "getprop"), report, "guest-properties", started, Duration.ofSeconds(10));
                probe(command(adb, "logcat", "-b", "all", "-d", "-t", "500"), report, "guest-logcat", started, Duration.ofSeconds(10));
                probe(command(adb, "shell", "dmesg"), report, "guest-kernel", started, Duration.ofSeconds(10));
            } else {
                System.err.println("Owned AVD identity unavailable/mismatched; refusing guest probes.");
            }
        } catch (IOException exception) {
            System.err.println("Best-effort boot diagnostics failed: " + exception.getMessage());
        }
        throw new IOException(failure);
    }

    private static String query(List<String> adb, Path probes, String name, String... arguments) throws IOException, InterruptedException {
        Map<String, Object> result = probe(command(adb, arguments), probes, name, System.nanoTime(), Duration.ofSeconds(10));
        if (!Integer.valueOf(0).equals(result.get("exit_code"))) throw new IOException("Android query failed: " + Json.stringify(result));
        return String.valueOf(result.get("stdout")).strip();
    }

    private static List<String> command(List<String> adb, String... arguments) {
        List<String> command = new ArrayList<>(adb);
        command.addAll(List.of(arguments));
        return command;
    }

    private static String required(Map<String, String> values, String name) {
        if (!values.containsKey(name) || values.get(name).isBlank()) throw new IllegalArgumentException("Missing " + name);
        return values.get(name);
    }

    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException("Invalid Android device identity");
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException("Invalid identity field");
            result.put(key, entry.getValue());
        }
        return result;
    }

    public static void main(String[] arguments) {
        System.exit(run(arguments));
    }
}
