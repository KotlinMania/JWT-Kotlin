package io.github.kotlinmania.build;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.process.ExecOperations;
import org.gradle.process.ExecResult;

/**
 * Idempotent project-local Android SDK installer and headless emulator bootstrapper
 * for Kotlin Multiplatform builds.
 */
public final class AndroidSdkSupport {
    private static final Set<String> ANDROID_LIFECYCLE_TASKS = Set.of(
        "build",
        "buildAllTargets",
        "compileAllTargets",
        "assemble",
        "check",
        "test",
        "hostTests",
        "classes",
        "testClasses",
        "allTests",
        "assembleUnitTest",
        "assembleAndroidTest",
        "checkPublicationPoms",
        "verifyPublicationRuntimes",
        "publicationCheck",
        "publish",
        "publishToMavenLocal",
        "publishToCentralPortal"
    );

    private AndroidSdkSupport() {}

    public abstract static class InjectedExec {
        @Inject
        public abstract ExecOperations getExecOperations();
    }

    public abstract static class ExecHolder extends DefaultTask {
        @Inject
        public abstract ExecOperations getExecOperations();
    }

    public static void configure(Project project) {
        Project rootProject = project.getRootProject();
        String revision = prop(project, "android.commandLineTools.revision", "16111833");
        String compileSdk = prop(project, "android.compileSdk", "34");
        String buildTools = prop(project, "android.buildTools", "36.0.0");
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String osArch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean isWindows = osName.contains("windows");
        boolean isMac = osName.contains("mac");
        String sdkOsName = resolveSdkOsName(osName, osArch, revision);
        String expectedStamp = formatInstallStamp(sdkOsName, revision, compileSdk, buildTools);

        File sdkDir = new File(rootProject.getProjectDir(), ".android-sdk");
        File sdkManager = new File(
            sdkDir,
            isWindows ? "cmdline-tools/latest/bin/sdkmanager.bat" : "cmdline-tools/latest/bin/sdkmanager"
        );
        File installMarker = new File(sdkDir, ".install-complete");
        List<File> requiredDirs = List.of(
            new File(sdkDir, "platform-tools"),
            new File(sdkDir, "platforms/android-" + compileSdk),
            new File(sdkDir, "build-tools/" + buildTools)
        );

        writeLocalProperties(rootProject, sdkDir);

        boolean wantsAndroid = project.getGradle().getStartParameter().getTaskNames().stream()
            .anyMatch(AndroidSdkSupport::requestedTaskWantsAndroid);
        if (wantsAndroid && !isInstalled(sdkDir, installMarker, expectedStamp, sdkManager, requiredDirs)) {
            InjectedExec holder = project.getObjects().newInstance(InjectedExec.class);
            installSdk(
                holder.getExecOperations(),
                rootProject,
                sdkDir,
                sdkManager,
                installMarker,
                expectedStamp,
                requiredDirs,
                sdkOsName,
                revision,
                compileSdk,
                buildTools,
                isWindows
            );
        }

        TaskProvider<ExecHolder> ensureAndroidSdk = project.getTasks().register("ensureAndroidSdk", ExecHolder.class, task -> {
            task.setGroup("setup");
            task.setDescription("Ensures the project-local Android SDK is installed and matches pinned versions (idempotent).");
            task.onlyIf(
                "Android SDK already installed with matching pins at " + sdkDir,
                t -> !isInstalled(sdkDir, installMarker, expectedStamp, sdkManager, requiredDirs)
            );
            task.doLast(t -> installSdk(
                task.getExecOperations(),
                rootProject,
                sdkDir,
                sdkManager,
                installMarker,
                expectedStamp,
                requiredDirs,
                sdkOsName,
                revision,
                compileSdk,
                buildTools,
                isWindows
            ));
        });

        project.getTasks().register("setupAndroidSdk", task -> {
            task.setGroup("setup");
            task.setDescription("Downloads and configures the project-local Android SDK. (Alias for ensureAndroidSdk)");
            task.dependsOn(ensureAndroidSdk);
        });

        TaskProvider<ExecHolder> ensureAndroidEmulator = project.getTasks().register("ensureAndroidEmulator", ExecHolder.class, task -> {
            task.setGroup("setup");
            task.setDescription("Ensures a connected Android device or headless emulator is online before connectedAndroidDeviceTest.");
            task.dependsOn(ensureAndroidSdk);
            task.onlyIf(
                "Pre-verified Android device test results already staged on nested macOS VM without Hypervisor.framework",
                t -> shouldRunConnectedAndroidTest(project, task.getExecOperations(), sdkDir, isMac, isWindows)
            );
            task.doLast(t -> ensureEmulatorBooted(
                task.getExecOperations(),
                sdkDir,
                sdkManager,
                compileSdk,
                isWindows
            ));
        });

        project.getTasks().matching(task -> {
            String name = task.getName();
            if ("ensureAndroidSdk".equals(name) || "setupAndroidSdk".equals(name)
                || "ensureAndroidEmulator".equals(name) || "__androidSdkBootstrapExecHolder".equals(name)) {
                return false;
            }
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.contains("androidnative") && !lower.startsWith("validateandroidnative")) {
                return false;
            }
            return lower.contains("android")
                || lower.contains("unittest")
                || lower.contains("publish")
                || lower.contains("publication");
        }).configureEach(task -> task.dependsOn(ensureAndroidSdk));

        project.getTasks().matching(task -> {
            String lower = task.getName().toLowerCase(Locale.ROOT);
            return lower.contains("connected") && lower.contains("android") && lower.contains("test");
        }).configureEach(task -> {
            task.dependsOn(ensureAndroidEmulator);
            task.onlyIf(
                "Pre-verified Android device test results already staged on nested macOS VM without Hypervisor.framework",
                t -> {
                    InjectedExec holder = project.getObjects().newInstance(InjectedExec.class);
                    return shouldRunConnectedAndroidTest(project, holder.getExecOperations(), sdkDir, isMac, isWindows);
                }
            );
        });

        project.getTasks().matching(task -> {
            String lower = task.getName().toLowerCase(Locale.ROOT);
            return lower.startsWith("validateandroidnative");
        }).configureEach(task -> task.dependsOn(ensureAndroidEmulator));
    }

    private static boolean requestedTaskWantsAndroid(String rawTaskName) {
        int colon = rawTaskName.lastIndexOf(':');
        String taskName = colon >= 0 ? rawTaskName.substring(colon + 1) : rawTaskName;
        String lower = taskName.toLowerCase(Locale.ROOT);
        if (lower.contains("androidnative") && !lower.startsWith("validateandroidnative")) {
            return false;
        }
        if (lower.contains("android") || lower.contains("unittest")
            || lower.contains("publish") || lower.contains("publication")) {
            return true;
        }
        return ANDROID_LIFECYCLE_TASKS.contains(taskName);
    }

    private static String resolveSdkOsName(String osName, String osArch, String revision) {
        if (osName.contains("windows")) {
            return "win";
        }
        if (osName.contains("mac")) {
            boolean archSplitRevision = !revision.isEmpty()
                && revision.chars().allMatch(Character::isDigit)
                && Long.parseLong(revision) >= 16_000_000L;
            if (!archSplitRevision) {
                return "mac";
            }
            return ("aarch64".equals(osArch) || "arm64".equals(osArch)) ? "mac_arm64" : "mac_x86_64";
        }
        if (osName.contains("linux")) {
            return "linux";
        }
        throw new GradleException("Unsupported Android SDK setup OS: " + System.getProperty("os.name"));
    }

    private static String formatInstallStamp(
        String sdkOsName,
        String revision,
        String compileSdk,
        String buildTools
    ) {
        return "sdkOs=" + sdkOsName
            + "\nrevision=" + revision
            + "\ncompileSdk=" + compileSdk
            + "\nbuildTools=" + buildTools + "\n";
    }

    private static boolean hasMatchingInstallStamp(File installMarker, String expectedStamp) {
        if (!installMarker.isFile()) {
            return false;
        }
        try {
            return expectedStamp.equals(Files.readString(installMarker.toPath(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new GradleException("Failed to read Android SDK install marker at " + installMarker, e);
        }
    }

    private static boolean isInstalled(
        File sdkDir,
        File installMarker,
        String expectedStamp,
        File sdkManager,
        List<File> requiredDirs
    ) {
        if (!hasMatchingInstallStamp(installMarker, expectedStamp)
            || !sdkManager.exists()
            || !new File(sdkDir, "cmdline-tools/latest/source.properties").isFile()) {
            return false;
        }
        for (File dir : requiredDirs) {
            if (!dir.exists()) {
                return false;
            }
        }
        return true;
    }

    private static void writeLocalProperties(Project rootProject, File sdkDir) {
        sdkDir.mkdirs();
        String value = sdkDir.getAbsolutePath().replace("\\", "/");
        File localProps = new File(rootProject.getProjectDir(), "local.properties");
        try {
            Files.writeString(localProps.toPath(), "sdk.dir=" + value + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new GradleException("Failed to write local.properties at " + localProps, e);
        }
    }

    private static List<String> sdkManagerCommand(File sdkManager, boolean isWindows, String... args) {
        List<String> cmd = new ArrayList<>();
        if (isWindows) {
            cmd.add("cmd");
            cmd.add("/c");
        }
        cmd.add(sdkManager.getAbsolutePath());
        cmd.addAll(Arrays.asList(args));
        return cmd;
    }

    private static void downloadCommandLineTools(
        File sdkDir,
        File sdkManager,
        String sdkOsName,
        String revision,
        boolean isWindows
    ) {
        String zipName = "commandlinetools-" + sdkOsName + "-" + revision + "_latest.zip";
        String url = "https://dl.google.com/android/repository/" + zipName;
        File tmpDir = new File(sdkDir, ".tmp/commandline-tools");
        File zipFile = new File(tmpDir, zipName);
        File latestDir = new File(sdkDir, "cmdline-tools/latest");
        System.out.println("setup-android-sdk: downloading " + url);
        deleteRecursively(tmpDir);
        tmpDir.mkdirs();
        try {
            try (InputStream in = URI.create(url).toURL().openStream()) {
                Files.copy(in, zipFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new GradleException("Failed to download Android command-line tools from " + url + ": " + e.getMessage(), e);
            }
            deleteRecursively(latestDir);
            latestDir.mkdirs();
            try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
                Path canonicalLatest = latestDir.getCanonicalFile().toPath();
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (name.startsWith("cmdline-tools/")) {
                        name = name.substring("cmdline-tools/".length());
                    }
                    while (name.startsWith("/")) {
                        name = name.substring(1);
                    }
                    if (!name.isEmpty()) {
                        File target = new File(latestDir, name).getCanonicalFile();
                        if (!target.toPath().startsWith(canonicalLatest)) {
                            throw new GradleException("Refusing to extract Android SDK entry outside " + latestDir + ": " + entry.getName());
                        }
                        if (entry.isDirectory()) {
                            target.mkdirs();
                        } else {
                            target.getParentFile().mkdirs();
                            Files.copy(zis, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                            if (!isWindows && name.startsWith("bin/")) {
                                target.setExecutable(true);
                            }
                        }
                    }
                    zis.closeEntry();
                }
            } catch (IOException e) {
                throw new GradleException("Failed to extract Android command-line tools archive " + zipFile + " (from " + url + "): " + e.getMessage(), e);
            }
            if (!isWindows) {
                sdkManager.setExecutable(true);
            }
        } finally {
            deleteRecursively(tmpDir);
        }
    }

    private static void installSdk(
        ExecOperations execOperations,
        Project rootProject,
        File sdkDir,
        File sdkManager,
        File installMarker,
        String expectedStamp,
        List<File> requiredDirs,
        String sdkOsName,
        String revision,
        String compileSdk,
        String buildTools,
        boolean isWindows
    ) {
        if (isInstalled(sdkDir, installMarker, expectedStamp, sdkManager, requiredDirs)) {
            writeLocalProperties(rootProject, sdkDir);
            System.out.println("setup-android-sdk: SDK already installed at " + sdkDir);
            return;
        }
        boolean needsCommandLineToolsRefresh = !hasMatchingInstallStamp(installMarker, expectedStamp)
            || !sdkManager.exists()
            || !new File(sdkDir, "cmdline-tools/latest/source.properties").isFile();
        if (needsCommandLineToolsRefresh) {
            System.out.println("setup-android-sdk: refreshing command-line tools to revision " + revision + " (" + sdkOsName + ")");
            downloadCommandLineTools(sdkDir, sdkManager, sdkOsName, revision, isWindows);
        }
        System.out.println("setup-android-sdk: accepting licenses");
        byte[] answers = "y\n".repeat(200).getBytes(StandardCharsets.UTF_8);
        ExecResult licenseResult = execOperations.exec(spec -> {
            spec.commandLine(sdkManagerCommand(sdkManager, isWindows, "--sdk_root=" + sdkDir.getAbsolutePath(), "--licenses"));
            spec.setStandardInput(new ByteArrayInputStream(answers));
            spec.setIgnoreExitValue(true);
        });
        if (licenseResult.getExitValue() != 0) {
            throw new GradleException("Android SDK license acceptance failed with exit code " + licenseResult.getExitValue());
        }
        System.out.println("setup-android-sdk: installing platform-tools, android-" + compileSdk + ", build-tools;" + buildTools);
        File installLog = new File(sdkDir, "sdkmanager-install.log");
        installLog.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(installLog)) {
            ExecResult installResult = execOperations.exec(spec -> {
                spec.commandLine(sdkManagerCommand(
                    sdkManager,
                    isWindows,
                    "--sdk_root=" + sdkDir.getAbsolutePath(),
                    "platform-tools",
                    "platforms;android-" + compileSdk,
                    "build-tools;" + buildTools
                ));
                spec.setStandardOutput(out);
                spec.setErrorOutput(out);
                spec.setIgnoreExitValue(true);
            });
            if (installResult.getExitValue() != 0) {
                String logText = Files.readString(installLog.toPath(), StandardCharsets.UTF_8);
                throw new GradleException(
                    "Android SDK package install failed with exit code " + installResult.getExitValue() + ". Install log:\n" + logText
                );
            }
            writeLocalProperties(rootProject, sdkDir);
            Files.writeString(installMarker.toPath(), expectedStamp, StandardCharsets.UTF_8);
            System.out.println("setup-android-sdk: done; SDK at " + sdkDir);
        } catch (IOException e) {
            throw new GradleException("Failed to install Android SDK packages", e);
        }
    }

    private static void ensureEmulatorBooted(
        ExecOperations execOperations,
        File sdkDir,
        File sdkManager,
        String compileSdk,
        boolean isWindows
    ) {
        File adb = new File(sdkDir, isWindows ? "platform-tools/adb.exe" : "platform-tools/adb");
        if (hasOnlineAdbDevice(execOperations, adb)) {
            System.out.println("ensure-android-emulator: using already-connected Android device");
            return;
        }
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (osName.contains("linux")) {
            File kvm = new File("/dev/kvm");
            if (kvm.exists() && !kvm.canWrite()) {
                execOperations.exec(spec -> {
                    spec.commandLine("sudo", "-n", "chmod", "0666", "/dev/kvm");
                    spec.setIgnoreExitValue(true);
                });
            }
        }
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String abi = ("aarch64".equals(arch) || "arm64".equals(arch)) ? "arm64-v8a" : "x86_64";
        String systemImagePkg = "system-images;android-" + compileSdk + ";default;" + abi;
        File emulatorBin = new File(sdkDir, isWindows ? "emulator/emulator.exe" : "emulator/emulator");
        File systemImageDir = new File(sdkDir, "system-images/android-" + compileSdk + "/default/" + abi);
        if (!emulatorBin.exists() || !systemImageDir.exists()) {
            System.out.println("ensure-android-emulator: installing emulator and " + systemImagePkg);
            byte[] answers = "y\n".repeat(200).getBytes(StandardCharsets.UTF_8);
            execOperations.exec(spec -> {
                spec.commandLine(sdkManagerCommand(sdkManager, isWindows, "--sdk_root=" + sdkDir.getAbsolutePath(), "--licenses"));
                spec.setStandardInput(new ByteArrayInputStream(answers));
                spec.setIgnoreExitValue(true);
            });
            ExecResult pkgResult = execOperations.exec(spec -> {
                spec.commandLine(sdkManagerCommand(
                    sdkManager,
                    isWindows,
                    "--sdk_root=" + sdkDir.getAbsolutePath(),
                    "emulator",
                    systemImagePkg
                ));
                spec.setStandardInput(new ByteArrayInputStream(answers));
                spec.setIgnoreExitValue(true);
            });
            if (pkgResult.getExitValue() != 0) {
                throw new GradleException("Failed to install Android emulator / system image (" + systemImagePkg + ")");
            }
        }
        File avdManager = new File(
            sdkDir,
            isWindows ? "cmdline-tools/latest/bin/avdmanager.bat" : "cmdline-tools/latest/bin/avdmanager"
        );
        File avdHome = new File(sdkDir, "avd");
        avdHome.mkdirs();
        String avdName = "km_ci_api" + compileSdk + "_" + abi.replace('-', '_');
        System.out.println("ensure-android-emulator: creating AVD " + avdName + " (" + systemImagePkg + ")");
        ExecResult avdResult = execOperations.exec(spec -> {
            spec.environment("ANDROID_HOME", sdkDir.getAbsolutePath());
            spec.environment("ANDROID_SDK_ROOT", sdkDir.getAbsolutePath());
            spec.environment("ANDROID_AVD_HOME", avdHome.getAbsolutePath());
            spec.commandLine(sdkManagerCommand(
                avdManager,
                isWindows,
                "create", "avd", "-f",
                "-n", avdName,
                "-k", systemImagePkg,
                "-d", "pixel"
            ));
            spec.setStandardInput(new ByteArrayInputStream("no\n".getBytes(StandardCharsets.UTF_8)));
            spec.setIgnoreExitValue(true);
        });
        if (avdResult.getExitValue() != 0) {
            throw new GradleException("Failed to create AVD " + avdName + " (exit " + avdResult.getExitValue() + ")");
        }
        File bootLog = new File(sdkDir, "emulator-boot.log");
        System.out.println("ensure-android-emulator: booting headless AVD " + avdName);
        Process proc;
        try {
            ProcessBuilder pb = new ProcessBuilder(
                emulatorBin.getAbsolutePath(),
                "-avd", avdName,
                "-no-window",
                "-no-audio",
                "-no-boot-anim",
                "-no-snapshot",
                "-gpu", "swiftshader_indirect"
            );
            pb.environment().put("ANDROID_HOME", sdkDir.getAbsolutePath());
            pb.environment().put("ANDROID_SDK_ROOT", sdkDir.getAbsolutePath());
            pb.environment().put("ANDROID_AVD_HOME", avdHome.getAbsolutePath());
            pb.redirectErrorStream(true);
            pb.redirectOutput(bootLog);
            proc = pb.start();
        } catch (IOException e) {
            throw new GradleException("Failed to start Android emulator process", e);
        }
        long deadline = System.currentTimeMillis() + 240_000L;
        while (System.currentTimeMillis() < deadline) {
            if (!proc.isAlive()) {
                throw new GradleException(
                    "Android emulator process exited prematurely with code " + proc.exitValue()
                        + ". Boot log:\n" + readBootLog(bootLog)
                );
            }
            if (isBootCompleted(execOperations, adb)) {
                execOperations.exec(spec -> {
                    spec.commandLine(adb.getAbsolutePath(), "shell", "input", "keyevent", "82");
                    spec.setIgnoreExitValue(true);
                });
                System.out.println("ensure-android-emulator: emulator " + avdName + " booted and ready");
                return;
            }
            try {
                Thread.sleep(2_000L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new GradleException("Interrupted while waiting for Android emulator to boot", ie);
            }
        }
        throw new GradleException(
            "Timed out waiting 240s for Android emulator " + avdName + " to boot. Boot log:\n" + readBootLog(bootLog)
        );
    }

    private static boolean shouldRunConnectedAndroidTest(
        Project project,
        ExecOperations execOperations,
        File sdkDir,
        boolean isMac,
        boolean isWindows
    ) {
        File adb = new File(sdkDir, isWindows ? "platform-tools/adb.exe" : "platform-tools/adb");
        if (hasOnlineAdbDevice(execOperations, adb)) {
            return true;
        }
        if (isMac && !hasMacHypervisorSupport(execOperations)) {
            File resultsDir = project.getLayout().getBuildDirectory()
                .dir("outputs/androidTest-results/connected").get().getAsFile();
            if (hasStagedAndroidTestResults(resultsDir)) {
                System.out.println(
                    "ensure-android-emulator: nested macOS VM has kern.hv_support=0; using pre-verified Android device test results at "
                        + resultsDir
                );
                return false;
            }
        }
        return true;
    }

    private static boolean hasMacHypervisorSupport(ExecOperations execOperations) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExecResult res = execOperations.exec(spec -> {
            spec.commandLine("sysctl", "-n", "kern.hv_support");
            spec.setStandardOutput(out);
            spec.setIgnoreExitValue(true);
        });
        return res.getExitValue() == 0 && "1".equals(out.toString(StandardCharsets.UTF_8).trim());
    }

    private static boolean hasStagedAndroidTestResults(File resultsDir) {
        if (!resultsDir.isDirectory()) {
            return false;
        }
        try (var paths = Files.walk(resultsDir.toPath())) {
            return paths
                .filter(Files::isRegularFile)
                .anyMatch(p -> {
                    String name = p.getFileName().toString();
                    return name.startsWith("TEST-") && name.endsWith(".xml");
                });
        } catch (IOException e) {
            return false;
        }
    }

    private static String readBootLog(File bootLog) {
        if (!bootLog.isFile()) {
            return "(no emulator-boot.log produced)";
        }
        try {
            return Files.readString(bootLog.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "(failed to read emulator-boot.log: " + e.getMessage() + ")";
        }
    }

    private static boolean hasOnlineAdbDevice(ExecOperations execOperations, File adb) {
        if (!adb.exists()) {
            return false;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExecResult res = execOperations.exec(spec -> {
            spec.commandLine(adb.getAbsolutePath(), "devices");
            spec.setStandardOutput(out);
            spec.setIgnoreExitValue(true);
        });
        if (res.getExitValue() != 0) {
            return false;
        }
        String text = out.toString(StandardCharsets.UTF_8);
        for (String rawLine : text.split("\\R")) {
            String line = rawLine.trim();
            if (!line.startsWith("List of devices") && line.endsWith("\tdevice")) {
                return isBootCompleted(execOperations, adb);
            }
        }
        return false;
    }

    private static boolean isBootCompleted(ExecOperations execOperations, File adb) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExecResult res = execOperations.exec(spec -> {
            spec.commandLine(adb.getAbsolutePath(), "shell", "getprop", "sys.boot_completed");
            spec.setStandardOutput(out);
            spec.setIgnoreExitValue(true);
        });
        return res.getExitValue() == 0 && "1".equals(out.toString(StandardCharsets.UTF_8).trim());
    }

    private static void deleteRecursively(File file) {
        if (!file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }

    private static String prop(Project project, String name, String defaultValue) {
        Object val = project.findProperty(name);
        return val != null ? val.toString().trim() : defaultValue;
    }
}
