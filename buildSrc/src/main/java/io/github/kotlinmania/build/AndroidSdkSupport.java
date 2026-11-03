package io.github.kotlinmania.build;

import java.io.ByteArrayInputStream;
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
 * Idempotent project-local Android SDK installer for Kotlin Multiplatform builds.
 */
public final class AndroidSdkSupport {
    private static final Set<String> ANDROID_LIFECYCLE_TASKS = Set.of(
        "build",
        "assemble",
        "check",
        "test",
        "hostTests",
        "classes",
        "testClasses",
        "allTests",
        "assembleUnitTest",
        "assembleAndroidTest"
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
        String revision = prop(project, "android.commandLineTools.revision", "14742923");
        String compileSdk = prop(project, "android.compileSdk", "34");
        String buildTools = prop(project, "android.buildTools", "36.0.0");
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        boolean isWindows = osName.contains("windows");
        boolean isMac = osName.contains("mac");
        String sdkOsName;
        if (isWindows) {
            sdkOsName = "win";
        } else if (isMac) {
            sdkOsName = "mac";
        } else if (osName.contains("linux")) {
            sdkOsName = "linux";
        } else {
            throw new GradleException("Unsupported Android SDK setup OS: " + System.getProperty("os.name"));
        }

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
        if (wantsAndroid && !isInstalled(installMarker, sdkManager, requiredDirs)) {
            InjectedExec holder = project.getObjects().newInstance(InjectedExec.class);
            installSdk(
                holder.getExecOperations(),
                rootProject,
                sdkDir,
                sdkManager,
                installMarker,
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
            task.setDescription("Ensures the project-local Android SDK is installed (idempotent).");
            task.onlyIf(
                "Android SDK already installed at " + sdkDir,
                t -> !isInstalled(installMarker, sdkManager, requiredDirs)
            );
            task.doLast(t -> installSdk(
                task.getExecOperations(),
                rootProject,
                sdkDir,
                sdkManager,
                installMarker,
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

        project.getTasks().matching(task -> {
            String name = task.getName();
            if ("ensureAndroidSdk".equals(name) || "setupAndroidSdk".equals(name) || "__androidSdkBootstrapExecHolder".equals(name)) {
                return false;
            }
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.contains("androidnative")) {
                return false;
            }
            return lower.contains("android") || "assembleUnitTest".equals(name) || "assembleAndroidTest".equals(name);
        }).configureEach(task -> task.dependsOn(ensureAndroidSdk));
    }

    private static boolean requestedTaskWantsAndroid(String rawTaskName) {
        int colon = rawTaskName.lastIndexOf(':');
        String taskName = colon >= 0 ? rawTaskName.substring(colon + 1) : rawTaskName;
        String lower = taskName.toLowerCase(Locale.ROOT);
        if (lower.contains("androidnative")) {
            return false;
        }
        if (lower.contains("android")) {
            return true;
        }
        return ANDROID_LIFECYCLE_TASKS.contains(taskName);
    }

    private static boolean isInstalled(File installMarker, File sdkManager, List<File> requiredDirs) {
        if (!installMarker.exists() || !sdkManager.exists()) {
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
            }
            deleteRecursively(latestDir);
            latestDir.mkdirs();
            Path canonicalLatest = latestDir.getCanonicalFile().toPath();
            try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
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
            }
            if (!isWindows) {
                sdkManager.setExecutable(true);
            }
        } catch (IOException e) {
            throw new GradleException("Failed to download or extract Android command-line tools", e);
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
        List<File> requiredDirs,
        String sdkOsName,
        String revision,
        String compileSdk,
        String buildTools,
        boolean isWindows
    ) {
        if (isInstalled(installMarker, sdkManager, requiredDirs)) {
            writeLocalProperties(rootProject, sdkDir);
            System.out.println("setup-android-sdk: SDK already installed at " + sdkDir);
            return;
        }
        if (!sdkManager.exists()) {
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
            Files.writeString(installMarker.toPath(), "", StandardCharsets.UTF_8);
            System.out.println("setup-android-sdk: done; SDK at " + sdkDir);
        } catch (IOException e) {
            throw new GradleException("Failed to install Android SDK packages", e);
        }
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
