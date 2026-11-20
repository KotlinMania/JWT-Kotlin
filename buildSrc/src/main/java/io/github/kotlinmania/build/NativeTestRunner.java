package io.github.kotlinmania.build;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs an already linked Linux ARM64/x64, Windows x64, or Android Native ARM64/x64 test executable
 * locally, in an existing selected Parallels guest, or on a connected Android device/emulator via adb.
 * It checks architecture, linker, and binary hashes, executes real test cases, and writes runtime
 * evidence reports. It neither builds the product nor silently substitutes another platform.
 */
public final class NativeTestRunner {
    private static final Set<String> TARGETS = Set.of(
            "androidNativeArm64", "androidNativeX64", "linuxArm64", "linuxX64", "mingwX64");
    private static final Set<String> LINUX_GUESTS = Set.of(
            "ubuntu", "debian", "fedora", "linux", "centos", "rhel", "redhat", "opensuse", "kali");
    private static final Set<String> USABLE_GUEST_TOOLS_STATES = Set.of("installed", "outdated");
    private static final Pattern RAN = Pattern.compile("\\[==========\\] (\\d+) tests? from .* ran\\.");
    private static final Pattern PASSED = Pattern.compile("\\[  PASSED  \\] (\\d+) tests?\\.");
    private static final Pattern DISALLOWED = Pattern.compile(
            "\\[\\s*(?:SKIPPED|FAILED|DISABLED)\\s*\\]|YOU HAVE \\d+ DISABLED");
    private static final Pattern GUEST_ARCH = Pattern.compile("(?m)^GUEST_ARCH=(\\S+)\\s*$");
    private static final Pattern GUEST_EXIT = Pattern.compile("(?m)^GUEST_TEST_EXIT=(-?\\d+)\\s*$");
    private static final int DEFAULT_TIMEOUT_SECONDS = 900;

    private NativeTestRunner() {}

    public record Guest(String id, String name) {}

    /** An unavailable machine is different from a failed, empty, or skipped test suite. */
    public static final class RuntimeUnavailableException extends Exception {
        private static final long serialVersionUID = 1L;

        public RuntimeUnavailableException(String message) {
            super(message);
        }
    }

    private record Options(String target, Path binary, Path reportDirectory, String vm,
                           Path adb, Path scratchDirectory, boolean allowUnavailable, int timeoutSeconds) {}

    private record ProcessResult(int exitCode, String output, boolean timedOut) {}

    public static String guestPath(Path hostPath, String target) {
        Path path = hostPath.toAbsolutePath().normalize();
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        String share;
        Path relative;
        if (path.startsWith(Path.of("/Volumes")) && path.getNameCount() >= 3) {
            share = path.getName(1).toString();
            relative = path.subpath(2, path.getNameCount());
        } else if (path.startsWith(home) && !path.equals(home)) {
            share = "Home";
            relative = home.relativize(path);
        } else {
            throw new IllegalArgumentException(
                    "The linked binary and scratch directory must be in an existing shared volume or home directory");
        }
        if (target.equals("mingwX64")) {
            return "\\\\Mac\\" + share + "\\" + relative.toString().replace('/', '\\');
        }
        return "/media/psf/" + share + "/" + relative.toString().replace('\\', '/');
    }

    public static Guest selectGuest(Object inventory, String target, String requested)
            throws RuntimeUnavailableException {
        if (!(inventory instanceof List<?> guests)) {
            throw new IllegalArgumentException("Parallels returned an invalid guest inventory");
        }
        List<Guest> candidates = new ArrayList<>();
        for (Object entry : guests) {
            if (!(entry instanceof Map<?, ?> guest)) {
                throw new IllegalArgumentException("Parallels returned an invalid guest entry");
            }
            String operatingSystem = String.valueOf(guest.get("OS")).toLowerCase(Locale.ROOT);
            boolean matchesOperatingSystem = target.equals("mingwX64")
                    ? operatingSystem.startsWith("win") : LINUX_GUESTS.contains(operatingSystem);
            Object tools = guest.get("GuestTools");
            String toolsState = tools instanceof Map<?, ?> toolState
                    ? String.valueOf(toolState.get("state")).toLowerCase(Locale.ROOT)
                    : "";
            if (!"running".equals(guest.get("State"))
                    || !USABLE_GUEST_TOOLS_STATES.contains(toolsState)
                    || !matchesOperatingSystem) {
                continue;
            }
            Object id = guest.get("ID");
            Object name = guest.get("Name");
            if (!(id instanceof String idText) || !(name instanceof String nameText)) {
                throw new IllegalArgumentException("A running Parallels guest has no valid ID or name");
            }
            if (requested == null || requested.equals(idText) || requested.equals(nameText)) {
                candidates.add(new Guest(idText, nameText));
            }
        }
        if (candidates.isEmpty()) {
            throw new RuntimeUnavailableException(target
                    + " has no suitable running guest with existing Parallels Tools"
                    + (requested == null ? "" : " matching " + requested));
        }
        if (candidates.size() != 1) {
            throw new IllegalArgumentException(target + " requires exactly one running guest with existing Parallels Tools; found "
                    + candidates.size() + ". Select an existing guest with --vm when ambiguous.");
        }
        return candidates.getFirst();
    }

    public static int validateTestOutput(String output, int processExit) {
        if (processExit != 0) {
            throw new IllegalArgumentException("Native test process failed with exit " + processExit);
        }
        Matcher ran = RAN.matcher(output);
        Matcher passed = PASSED.matcher(output);
        if (!ran.find() || !passed.find() || Integer.parseInt(ran.group(1)) == 0
                || !ran.group(1).equals(passed.group(1))) {
            throw new IllegalArgumentException("Missing, empty or failing native test result");
        }
        if (DISALLOWED.matcher(output).find()) {
            throw new IllegalArgumentException("Required native tests were skipped, disabled or failed");
        }
        return Integer.parseInt(ran.group(1));
    }

    /** Reject malformed or incorrectly targeted binaries before considering runtime availability. */
    public static void verifyBinaryIdentity(Path binary, String target) throws IOException {
        try (FileChannel channel = FileChannel.open(binary)) {
            ByteBuffer header = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
            readHeader(channel, header, "Linked executable header is truncated");
            if (target.equals("mingwX64")) {
                if (header.get(0) != 'M' || header.get(1) != 'Z') {
                    throw new IllegalArgumentException("Linked Windows binary is not a PE executable");
                }
                long offset = Integer.toUnsignedLong(header.getInt(60));
                if (offset < 64 || offset > channel.size() - 26) {
                    throw new IllegalArgumentException("Linked Windows binary has an invalid PE header offset");
                }
                channel.position(offset);
                ByteBuffer pe = ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN);
                readHeader(channel, pe, "Linked PE executable header is truncated");
                int characteristics = Short.toUnsignedInt(pe.getShort(22));
                if (pe.getInt(0) != 0x00004550 || Short.toUnsignedInt(pe.getShort(4)) != 0x8664
                        || Short.toUnsignedInt(pe.getShort(24)) != 0x20b
                        || (characteristics & 2) == 0 || (characteristics & 0x2000) != 0) {
                    throw new IllegalArgumentException("Linked Windows binary is not an x86-64 PE executable");
                }
            } else {
                int machine = (target.equals("linuxArm64") || target.equals("androidNativeArm64"))
                        ? 183
                        : (target.equals("linuxX64") || target.equals("androidNativeX64")) ? 62 : -1;
                int type = Short.toUnsignedInt(header.getShort(16));
                if (machine == -1 || header.getInt(0) != 0x464c457f || header.get(4) != 2
                        || header.get(5) != 1 || header.get(6) != 1 || header.getInt(20) != 1
                        || (type != 2 && type != 3) || Short.toUnsignedInt(header.getShort(18)) != machine) {
                    throw new IllegalArgumentException("Linked ELF binary is not a " + target + " ELF64 executable");
                }
                if (target.startsWith("androidNative")) {
                    long phoff = header.getLong(32);
                    int phentsize = Short.toUnsignedInt(header.getShort(54));
                    int phnum = Short.toUnsignedInt(header.getShort(56));
                    if (phoff <= 0 || phentsize < 56 || phnum <= 0) {
                        throw new IllegalArgumentException("Linked Android binary is missing ELF64 program headers");
                    }
                    String interp = null;
                    ByteBuffer ph = ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN);
                    for (int i = 0; i < phnum; i++) {
                        ph.clear();
                        channel.position(phoff + (long) i * phentsize);
                        readHeader(channel, ph, "Linked Android ELF64 program header is truncated");
                        ph.flip();
                        if (ph.getInt(0) == 3) { // PT_INTERP
                            long interpOff = ph.getLong(8);
                            long interpSize = ph.getLong(32);
                            if (interpOff <= 0 || interpSize <= 0 || interpSize > 256) {
                                throw new IllegalArgumentException("Linked Android binary has invalid PT_INTERP segment");
                            }
                            ByteBuffer ibuf = ByteBuffer.allocate((int) interpSize);
                            channel.position(interpOff);
                            readHeader(channel, ibuf, "Linked Android PT_INTERP path is truncated");
                            byte[] raw = ibuf.array();
                            int len = raw.length;
                            while (len > 0 && raw[len - 1] == 0) {
                                len--;
                            }
                            interp = new String(raw, 0, len, StandardCharsets.UTF_8);
                            break;
                        }
                    }
                    if (!"/system/bin/linker64".equals(interp)) {
                        throw new IllegalArgumentException(
                                "Linked Android binary must use Bionic /system/bin/linker64 (found: " + interp + ")");
                    }
                }
            }
        }
    }

    private static void readHeader(FileChannel channel, ByteBuffer header, String message) throws IOException {
        while (header.hasRemaining()) {
            if (channel.read(header) == -1) {
                throw new IllegalArgumentException(message);
            }
        }
    }

    public static List<String> guestCommand(String target, String digest, String binary,
                                            String temporaryDirectory, String workingDirectory) {
        return guestCommand(target, digest, binary, temporaryDirectory, workingDirectory, DEFAULT_TIMEOUT_SECONDS);
    }

    public static List<String> guestCommand(String target, String digest, String binary,
                                            String temporaryDirectory, String workingDirectory,
                                            int timeoutSeconds) {
        if (!digest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid binary SHA-256 digest");
        }
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("Timeout must be positive");
        }
        if (target.equals("mingwX64")) {
            long timeoutMillis = (long) timeoutSeconds * 1000L;
            String script = """
                    $ErrorActionPreference = 'Stop'
                    $ProgressPreference = 'SilentlyContinue'
                    $localDir = Join-Path ([System.IO.Path]::GetTempPath()) ("km-native-" + [Guid]::NewGuid().ToString("N"))
                    try {
                      $request = [Console]::ReadLine() | ConvertFrom-Json
                      $binary = $request.binary
                      $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $binary).Hash.ToLowerInvariant()
                      if ($hash -ne '@HASH@') { throw 'Shared binary hash mismatch' }
                      New-Item -ItemType Directory -Path $localDir -Force | Out-Null
                      $localBinary = Join-Path $localDir ([System.IO.Path]::GetFileName($binary))
                      Copy-Item -LiteralPath $binary -Destination $localBinary -Force
                      $localHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $localBinary).Hash.ToLowerInvariant()
                      if ($localHash -ne '@HASH@') { throw 'Local binary hash mismatch' }
                      Write-Output "BINARY_SHA256=$localHash"
                      Write-Output 'GUEST_OS=Windows'
                      Write-Output "GUEST_ARCH=$([System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture)"
                      $start = New-Object System.Diagnostics.ProcessStartInfo
                      $start.FileName = $localBinary
                      $start.Arguments = '--ktest_logger=GTEST'
                      $start.WorkingDirectory = $request.workingDirectory
                      $start.UseShellExecute = $false
                      $start.RedirectStandardOutput = $true
                      $start.RedirectStandardError = $true
                      $start.EnvironmentVariables['TMPDIR'] = $localDir
                      $start.EnvironmentVariables['TMP'] = $localDir
                      $start.EnvironmentVariables['TEMP'] = $localDir
                      $process = New-Object System.Diagnostics.Process
                      $process.StartInfo = $start
                      if (-not $process.Start()) { throw 'Could not start native test executable' }
                      $stdout = $process.StandardOutput.ReadToEndAsync()
                      $stderr = $process.StandardError.ReadToEndAsync()
                      $finished = $process.WaitForExit(@TIMEOUT_MS@)
                      if (-not $finished) {
                        & taskkill.exe /PID $process.Id /T /F | Out-Null
                        $process.WaitForExit()
                      }
                      [Console]::Out.Write($stdout.GetAwaiter().GetResult())
                      [Console]::Out.Write($stderr.GetAwaiter().GetResult())
                      $testExit = if ($finished) { $process.ExitCode } else { 124 }
                      $process.Dispose()
                      Write-Output "GUEST_TEST_EXIT=$testExit"
                      exit $testExit
                    } catch { [Console]::Error.WriteLine($_); exit 1 }
                    finally {
                      if (Test-Path -LiteralPath $localDir) {
                        Remove-Item -LiteralPath $localDir -Recurse -Force -ErrorAction SilentlyContinue
                      }
                    }
                    """.replace("@HASH@", digest)
                    .replace("@TIMEOUT_MS@", Long.toString(timeoutMillis));
            return List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand",
                    Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE)));
        }
        String script = """
                set -u
                binary=@BINARY@
                temporary=@TEMPORARY@
                working=@WORKING@
                [ "$(uname -s)" = Linux ] || { printf '%s\\n' 'A Linux guest is required' >&2; exit 1; }
                validation_hash=$(sha256sum < "$binary") || exit 1
                validation_hash=${validation_hash%% *}
                [ "$validation_hash" = '@HASH@' ] || { printf '%s\\n' 'Shared binary hash mismatch' >&2; exit 1; }
                [ -d "$temporary" ] || { printf '%s\\n' 'Project temporary directory is unavailable' >&2; exit 1; }
                printf 'BINARY_SHA256=%s\\n' "$validation_hash"
                printf 'GUEST_OS=Linux\\nGUEST_ARCH=%s\\n' "$(uname -m)"
                cd "$working" || exit 1
                export TMPDIR="$temporary" TMP="$temporary" TEMP="$temporary"
                timeout --signal=TERM --kill-after=5s @TIMEOUT_S@s "$binary" --ktest_logger=GTEST
                test_exit=$?
                printf 'GUEST_TEST_EXIT=%s\\n' "$test_exit"
                exit "$test_exit"
                """.replace("@HASH@", digest)
                .replace("@TIMEOUT_S@", Integer.toString(timeoutSeconds))
                .replace("@BINARY@", shellQuote(binary))
                .replace("@TEMPORARY@", shellQuote(temporaryDirectory))
                .replace("@WORKING@", shellQuote(workingDirectory));
        return List.of("/bin/sh -c " + shellQuote(script));
    }

    public static int run(String... arguments) {
        if (List.of(arguments).contains("--help")) {
            System.out.println("NativeTestRunner --target androidNativeArm64|androidNativeX64|linuxArm64|linuxX64|mingwX64"
                    + " --binary FILE --report-dir DIR [--adb FILE] [--vm NAME_OR_ID] [--scratch-dir DIR]"
                    + " [--timeout-seconds SECONDS] [--allow-unavailable]");
            return 0;
        }
        Options options;
        try {
            options = parse(arguments);
        } catch (IllegalArgumentException exception) {
            System.err.println("Native runtime validation failed: " + exception.getMessage());
            return 1;
        }
        String hostOperatingSystem = hostOperatingSystem();
        String hostArchitecture = System.getProperty("os.arch");
        String binaryArchitecture = (options.target().equals("linuxArm64") || options.target().equals("androidNativeArm64"))
                ? "aarch64" : "x86_64";
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("target", options.target());
        receipt.put("hostOs", hostOperatingSystem);
        receipt.put("hostArch", hostArchitecture);
        receipt.put("binaryArch", binaryArchitecture);
        receipt.put("available", true);
        String output = "";
        Path temporary = null;
        int exit = 1;
        try {
            Files.createDirectories(options.reportDirectory());
            Path binary = options.binary().toRealPath();
            if (!Files.isRegularFile(binary)) {
                throw new IllegalArgumentException("Linked binary is not a regular file: " + binary);
            }
            verifyBinaryIdentity(binary, options.target());
            String digest = sha256(binary);
            receipt.put("binary", binary.toString());
            receipt.put("binarySha256", digest);
            String nativeOperatingSystem = options.target().startsWith("androidNative")
                    ? "Android"
                    : options.target().equals("mingwX64") ? "Windows" : "Linux";
            Path scratchRoot = options.scratchDirectory() == null ? binary.getParent() : options.scratchDirectory();
            Files.createDirectories(scratchRoot);
            temporary = Files.createTempDirectory(scratchRoot, "native-runtime-").toRealPath();
            Duration hostTimeout = Duration.ofSeconds((long) options.timeoutSeconds() + 60L);
            ProcessResult result;
            if (options.target().startsWith("androidNative")) {
                result = runAndroidAdb(options, binary, digest, temporary, hostTimeout, receipt);
                output = result.output();
                Matcher guestExit = GUEST_EXIT.matcher(output);
                if (!output.lines().anyMatch(line -> line.equals("BINARY_SHA256=" + digest))
                        || !guestExit.find() || !guestExit.group(1).equals("0")) {
                    throw new IllegalArgumentException(
                            "Android device did not confirm binary hash and successful test exit (host exit "
                                    + result.exitCode() + ")");
                }
            } else if (hostOperatingSystem.equals(nativeOperatingSystem)) {
                receipt.put("executionMode", "local-host");
                result = execute(List.of(binary.toString(), "--ktest_logger=GTEST"), binary.getParent(),
                        temporaryEnvironment(temporary), "", hostTimeout, options.reportDirectory());
            } else {
                Path parallels = findExecutable("prlctl");
                if (parallels == null) {
                    throw new RuntimeUnavailableException("No native " + nativeOperatingSystem
                            + " host or existing Parallels CLI is available");
                }
                ProcessResult inventory = execute(List.of(parallels.toString(), "list", "-a", "-i", "-j"),
                        binary.getParent(), temporaryEnvironment(temporary), "", Duration.ofSeconds(30), options.reportDirectory());
                if (inventory.timedOut() || inventory.exitCode() != 0) {
                    throw new IOException("Could not inspect existing Parallels guests: " + tail(inventory.output(), 3000));
                }
                Guest guest = selectGuest(Json.parse(inventory.output()), options.target(), options.vm());
                String guestBinary = guestPath(binary, options.target());
                String guestTemporary = guestPath(temporary, options.target());
                String guestWorking = guestPath(binary.getParent(), options.target());
                receipt.put("executionMode", "local-parallels");
                receipt.put("guestId", guest.id());
                receipt.put("guestName", guest.name());
                receipt.put("guestBinary", guestBinary);
                List<String> command = new ArrayList<>(List.of(parallels.toString(), "exec", guest.id(), "--current-user"));
                List<String> launcher = guestCommand(
                        options.target(), digest, guestBinary, guestTemporary, guestWorking, options.timeoutSeconds());
                if (options.target().equals("mingwX64")) {
                    // Keep the Parallels command short: long encoded commands exceed its guest argument limit.
                    Path script = temporary.resolve("run-native-tests.ps1");
                    Files.writeString(script, new String(Base64.getDecoder().decode(launcher.getLast()),
                            StandardCharsets.UTF_16LE), StandardCharsets.UTF_8);
                    command.addAll(List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                            "-File", guestPath(script, options.target())));
                } else {
                    command.addAll(launcher);
                }
                String input = options.target().equals("mingwX64")
                        ? Json.stringify(Map.of("binary", guestBinary, "temporaryDirectory", guestTemporary,
                                "workingDirectory", guestWorking)) + "\n" : "";
                result = execute(command, binary.getParent(), temporaryEnvironment(temporary), input,
                        hostTimeout, options.reportDirectory());
                output = result.output();
                receipt.put("processExit", result.exitCode());
                Matcher architecture = GUEST_ARCH.matcher(output);
                if (architecture.find()) {
                    receipt.put("guestArch", architecture.group(1));
                }
                Matcher guestExit = GUEST_EXIT.matcher(output);
                if (!output.lines().anyMatch(line -> line.equals("BINARY_SHA256=" + digest))
                        || !guestExit.find() || !guestExit.group(1).equals("0")) {
                    throw new IllegalArgumentException("Guest did not confirm the binary hash and successful test exit (host exit "
                            + result.exitCode() + ")");
                }
            }
            output = result.output();
            receipt.put("processExit", result.exitCode());
            String runtimeArchitecture = normalizeArchitecture(String.valueOf(receipt.getOrDefault("guestArch", hostArchitecture)));
            receipt.put("emulated", !runtimeArchitecture.equals(binaryArchitecture));
            if (result.timedOut()) {
                throw new IOException("Native test process timed out");
            }
            int tests = validateTestOutput(output, result.exitCode());
            if (!sha256(binary).equals(digest)) {
                throw new IllegalArgumentException("Linked binary changed during test execution; rerun validation");
            }
            receipt.put("tests", tests);
            receipt.put("passed", true);
            receipt.put("status", "passed");
            System.out.println(options.target() + ": " + tests + " tests passed (" + receipt.get("executionMode") + "); sha256=" + digest);
            exit = 0;
        } catch (RuntimeUnavailableException exception) {
            receipt.put("available", false);
            receipt.put("passed", false);
            receipt.put("status", "unavailable");
            receipt.put("unavailableReason", exception.getMessage());
            receipt.put("skipped", options.allowUnavailable());
            if (options.allowUnavailable()) {
                System.out.println(options.target() + ": runtime unavailable (skipped; allowed on this host): " + exception.getMessage());
                exit = 0;
            } else {
                System.err.println(options.target() + ": runtime unavailable: " + exception.getMessage());
                exit = 1;
            }
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            receipt.put("passed", false);
            receipt.put("status", "failed");
            receipt.put("error", exception.getMessage() == null ? exception.toString() : exception.getMessage());
            System.err.println(options.target() + ": " + receipt.get("error"));
            if (!output.isEmpty()) {
                System.err.println(tail(output, 3000));
            }
        } finally {
            try {
                if (temporary != null) {
                    deleteTemporaryDirectory(temporary);
                }
            } catch (IOException exception) {
                receipt.put("passed", false);
                receipt.put("status", "failed");
                receipt.put("cleanupError", exception.getMessage());
                System.err.println("Could not remove project test temporary directory: " + exception.getMessage());
                exit = 1;
            }
            try {
                Files.createDirectories(options.reportDirectory());
                Files.writeString(options.reportDirectory().resolve(options.target() + ".log"), output, StandardCharsets.UTF_8);
                Json.write(options.reportDirectory().resolve(options.target() + ".json"), receipt);
            } catch (IOException exception) {
                System.err.println("Could not write native validation report: " + exception.getMessage());
                exit = 1;
            }
        }
        return exit;
    }

    public static void main(String[] arguments) {
        System.exit(run(arguments));
    }

    private static ProcessResult runAndroidAdb(
            Options options,
            Path binary,
            String digest,
            Path temporary,
            Duration hostTimeout,
            Map<String, Object> receipt)
            throws IOException, InterruptedException, RuntimeUnavailableException {
        Path adb = resolveAdbExecutable(options.adb());
        if (adb == null) {
            throw new RuntimeUnavailableException("No Android adb executable is available");
        }
        ProcessResult devicesResult = execute(
                List.of(adb.toString(), "devices"),
                binary.getParent(),
                temporaryEnvironment(temporary),
                "",
                Duration.ofSeconds(30),
                options.reportDirectory());
        if (devicesResult.timedOut() || devicesResult.exitCode() != 0) {
            throw new IOException("Could not query adb devices: " + tail(devicesResult.output(), 3000));
        }
        List<String> onlineSerials = new ArrayList<>();
        for (String rawLine : devicesResult.output().lines().toList()) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("List of devices") || line.startsWith("*")) {
                continue;
            }
            String[] cols = line.split("\\s+");
            if (cols.length >= 2 && "device".equals(cols[1])) {
                if (options.vm() == null || options.vm().equals(cols[0])) {
                    onlineSerials.add(cols[0]);
                }
            }
        }
        if (onlineSerials.isEmpty()) {
            throw new RuntimeUnavailableException(
                    "No online Android device or emulator found via adb"
                            + (options.vm() == null ? "" : " matching " + options.vm()));
        }
        String requiredAbi = options.target().equals("androidNativeArm64") ? "arm64-v8a" : "x86_64";
        String selectedSerial = null;
        String selectedSdk = "";
        List<String> seenAbis = new ArrayList<>();
        for (String serial : onlineSerials) {
            ProcessResult propRes = execute(
                    List.of(adb.toString(), "-s", serial, "shell",
                            "printf 'ABILIST=%s\\nSDK=%s\\n' \"$(getprop ro.product.cpu.abilist)\" \"$(getprop ro.build.version.sdk)\""),
                    binary.getParent(),
                    temporaryEnvironment(temporary),
                    "",
                    Duration.ofSeconds(15),
                    options.reportDirectory());
            if (propRes.timedOut() || propRes.exitCode() != 0) {
                continue;
            }
            String abilist = "";
            String sdk = "";
            for (String line : propRes.output().lines().toList()) {
                String trimmed = line.trim();
                if (trimmed.startsWith("ABILIST=")) {
                    abilist = trimmed.substring("ABILIST=".length());
                } else if (trimmed.startsWith("SDK=")) {
                    sdk = trimmed.substring("SDK=".length());
                }
            }
            seenAbis.add(serial + ":" + abilist);
            List<String> abis = List.of(abilist.split(","));
            if (abis.contains(requiredAbi)) {
                selectedSerial = serial;
                selectedSdk = sdk;
                break;
            }
        }
        if (selectedSerial == null) {
            throw new RuntimeUnavailableException(options.target()
                    + " has no online Android device or emulator supporting ABI " + requiredAbi
                    + " (online: " + String.join("; ", seenAbis) + ")");
        }
        String remoteDir = "/data/local/tmp/km-native-" + UUID.randomUUID().toString().replace("-", "");
        String remoteBinary = remoteDir + "/test.kexe";
        receipt.put("executionMode", "android-adb");
        receipt.put("deviceSerial", selectedSerial);
        receipt.put("androidSdk", selectedSdk);
        receipt.put("remoteBinary", remoteBinary);
        try {
            ProcessResult mkdirRes = execute(
                    List.of(adb.toString(), "-s", selectedSerial, "shell", "mkdir -p " + shellQuote(remoteDir)),
                    binary.getParent(),
                    temporaryEnvironment(temporary),
                    "",
                    Duration.ofSeconds(30),
                    options.reportDirectory());
            if (mkdirRes.timedOut() || mkdirRes.exitCode() != 0) {
                throw new IOException("Could not create Android test directory " + remoteDir + ": " + tail(mkdirRes.output(), 2000));
            }
            ProcessResult pushRes = execute(
                    List.of(adb.toString(), "-s", selectedSerial, "push", binary.toString(), remoteBinary),
                    binary.getParent(),
                    temporaryEnvironment(temporary),
                    "",
                    Duration.ofSeconds(120),
                    options.reportDirectory());
            if (pushRes.timedOut() || pushRes.exitCode() != 0) {
                throw new IOException("Could not push Android native test binary to " + remoteBinary + ": " + tail(pushRes.output(), 2000));
            }
            String script = """
                    set -u
                    dir=@DIR@
                    binary=@BINARY@
                    validation_hash=$(sha256sum "$binary" 2>/dev/null || toybox sha256sum "$binary") || exit 1
                    validation_hash=${validation_hash%% *}
                    [ "$validation_hash" = '@HASH@' ] || { printf '%s\\n' 'Pushed Android binary hash mismatch' >&2; exit 1; }
                    printf 'BINARY_SHA256=%s\\n' "$validation_hash"
                    printf 'GUEST_OS=Android\\nGUEST_ARCH=%s\\n' "$(uname -m)"
                    cd "$dir" || exit 1
                    chmod 755 "$binary" || exit 1
                    export TMPDIR="$dir" TMP="$dir" TEMP="$dir"
                    if command -v timeout >/dev/null 2>&1; then
                      timeout @TIMEOUT_S@ "$binary" --ktest_logger=GTEST
                    else
                      "$binary" --ktest_logger=GTEST
                    fi
                    test_exit=$?
                    printf 'GUEST_TEST_EXIT=%s\\n' "$test_exit"
                    exit "$test_exit"
                    """
                    .replace("@DIR@", shellQuote(remoteDir))
                    .replace("@BINARY@", shellQuote(remoteBinary))
                    .replace("@HASH@", digest)
                    .replace("@TIMEOUT_S@", Integer.toString(options.timeoutSeconds()));
            ProcessResult runRes = execute(
                    List.of(adb.toString(), "-s", selectedSerial, "shell", script),
                    binary.getParent(),
                    temporaryEnvironment(temporary),
                    "",
                    hostTimeout,
                    options.reportDirectory());
            String runOut = runRes.output().replace("\r\n", "\n");
            Matcher architecture = GUEST_ARCH.matcher(runOut);
            if (architecture.find()) {
                receipt.put("guestArch", architecture.group(1));
            }
            return new ProcessResult(runRes.exitCode(), runOut, runRes.timedOut());
        } finally {
            execute(
                    List.of(adb.toString(), "-s", selectedSerial, "shell", "rm -rf " + shellQuote(remoteDir)),
                    binary.getParent(),
                    temporaryEnvironment(temporary),
                    "",
                    Duration.ofSeconds(30),
                    options.reportDirectory());
        }
    }

    private static Path resolveAdbExecutable(Path explicitAdb) {
        if (explicitAdb != null && Files.isRegularFile(explicitAdb) && Files.isExecutable(explicitAdb)) {
            return explicitAdb;
        }
        boolean isWin = hostOperatingSystem().equals("Windows");
        String adbName = isWin ? "adb.exe" : "adb";
        for (String envVar : List.of("ANDROID_HOME", "ANDROID_SDK_ROOT")) {
            String val = System.getenv(envVar);
            if (val != null && !val.isBlank()) {
                Path candidate = Path.of(val, "platform-tools", adbName);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return candidate;
                }
            }
        }
        return findExecutable(adbName);
    }

    private static Options parse(String[] arguments) {
        String target = null;
        Path binary = null;
        Path report = null;
        Path adb = null;
        Path scratch = null;
        String vm = null;
        boolean allowUnavailable = false;
        int timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
        for (int index = 0; index < arguments.length; index++) {
            String argument = arguments[index];
            if (argument.equals("--allow-unavailable")) {
                allowUnavailable = true;
                continue;
            }
            if (++index == arguments.length) {
                throw new IllegalArgumentException("Missing value for " + argument);
            }
            String value = arguments[index];
            switch (argument) {
                case "--target" -> target = value;
                case "--binary" -> binary = Path.of(value).toAbsolutePath().normalize();
                case "--report-dir" -> report = Path.of(value).toAbsolutePath().normalize();
                case "--adb" -> adb = Path.of(value).toAbsolutePath().normalize();
                case "--scratch-dir" -> scratch = Path.of(value).toAbsolutePath().normalize();
                case "--vm" -> vm = value;
                case "--timeout-seconds" -> {
                    try {
                        timeoutSeconds = Integer.parseInt(value);
                    } catch (NumberFormatException exception) {
                        throw new IllegalArgumentException("Invalid --timeout-seconds: " + value, exception);
                    }
                    if (timeoutSeconds <= 0) {
                        throw new IllegalArgumentException("--timeout-seconds must be positive: " + value);
                    }
                }
                default -> throw new IllegalArgumentException("Unknown option: " + argument);
            }
        }
        if (!TARGETS.contains(target == null ? "" : target) || binary == null || report == null) {
            throw new IllegalArgumentException("A valid --target, --binary and --report-dir are required");
        }
        return new Options(target, binary, report, vm, adb, scratch, allowUnavailable, timeoutSeconds);
    }

    private static ProcessResult execute(List<String> command, Path workingDirectory, Map<String, String> environment,
                                         String input, Duration timeout, Path reportDirectory)
            throws IOException, InterruptedException {
        Path capture = Files.createTempFile(reportDirectory, "native-process-", ".log");
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command).directory(workingDirectory.toFile())
                    .redirectErrorStream(true).redirectOutput(capture.toFile());
            builder.environment().putAll(environment);
            process = builder.start();
            try (OutputStream standardInput = process.getOutputStream()) {
                standardInput.write(input.getBytes(StandardCharsets.UTF_8));
            }
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                terminate(process);
            }
            return new ProcessResult(finished ? process.exitValue() : 124,
                    Files.readString(capture, StandardCharsets.UTF_8), !finished);
        } finally {
            if (process != null && process.isAlive()) {
                terminate(process);
            }
            Files.deleteIfExists(capture);
        }
    }

    private static void terminate(Process process) {
        List<ProcessHandle> descendants = process.descendants().toList();
        descendants.forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                descendants.forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException exception) {
            descendants.forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    private static Map<String, String> temporaryEnvironment(Path temporary) {
        Map<String, String> environment = new HashMap<>();
        environment.put("TMPDIR", temporary.toString());
        environment.put("TMP", temporary.toString());
        environment.put("TEMP", temporary.toString());
        return environment;
    }

    private static Path findExecutable(String name) {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String directory : path.split(Pattern.quote(System.getProperty("path.separator")))) {
            Path candidate = Path.of(directory).resolve(name);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static String hostOperatingSystem() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) {
            return "Darwin";
        }
        if (os.startsWith("windows")) {
            return "Windows";
        }
        return os.contains("linux") ? "Linux" : System.getProperty("os.name");
    }

    private static String normalizeArchitecture(String architecture) {
        return switch (architecture.toLowerCase(Locale.ROOT)) {
            case "arm64" -> "aarch64";
            case "amd64", "x64" -> "x86_64";
            default -> architecture.toLowerCase(Locale.ROOT);
        };
    }

    private static String sha256(Path binary) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream stream = Files.newInputStream(binary)) {
                byte[] buffer = new byte[65536];
                int read;
                while ((read = stream.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK SHA-256 implementation is unavailable", exception);
        }
    }

    private static void deleteTemporaryDirectory(Path directory) throws IOException {
        try (var entries = Files.walk(directory)) {
            for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(entry);
            }
        }
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static String tail(String value, int length) {
        return value.substring(Math.max(0, value.length() - length));
    }
}
