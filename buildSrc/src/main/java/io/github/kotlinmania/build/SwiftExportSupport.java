package io.github.kotlinmania.build;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.inject.Inject;
import org.gradle.api.Action;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.process.ExecOperations;
import org.gradle.process.ExecResult;
import org.gradle.workers.ClassLoaderWorkerSpec;
import org.gradle.workers.ProcessWorkerSpec;
import org.gradle.workers.WorkQueue;
import org.gradle.workers.WorkerExecutor;

/**
 * Configures Swift Export worker heap sizing, manifest enforcement, and Debug + Release smoke testing.
 */
public final class SwiftExportSupport {
    private static final Pattern SWIFT_TEST_RUN_PATTERN = Pattern.compile("Test run with [1-9]\\d* tests?");

    private SwiftExportSupport() {}

    public abstract static class SmokeTestTask extends DefaultTask {
        @Inject
        public abstract ExecOperations getExecOperations();
    }

    public static void configure(Project project, String swiftExportModuleName) {
        Project rootProject = project.getRootProject();
        String workerMaxHeapSize = prop(project, "project.swiftExport.workerMaxHeapSize", "4g");

        // Wrap SwiftExportTask's WorkerExecutor so the isolated Gradle Worker Daemon
        // starts with `workerMaxHeapSize` (default 4g) instead of Gradle's 512 MB default.
        project.getTasks().matching(task ->
            task.getClass().getName().startsWith("org.jetbrains.kotlin.gradle.plugin.mpp.apple.swiftexport.tasks.SwiftExportTask")
        ).configureEach(task -> task.doFirst(t -> wrapWorkerExecutor(t, workerMaxHeapSize)));

        File strictSwiftConfig = new File(rootProject.getProjectDir(), "verification/strict-swift.xcconfig");

        project.getTasks().matching(task ->
            task.getName().toLowerCase().endsWith("generatespmpackage")
        ).configureEach(task -> task.doLast(t -> {
            File spmDir = project.getLayout().getBuildDirectory().dir("SPMPackage").get().getAsFile();
            List<File> manifests = new ArrayList<>();
            collectPackageManifests(spmDir, manifests);
            if (manifests.isEmpty()) {
                throw new GradleException("No generated Swift package manifests in " + spmDir);
            }
            for (File manifest : manifests) {
                BuildToolTask.invoke(
                    SwiftManifestEnforcer.class.getName(),
                    manifest.getAbsolutePath(),
                    "--receipt",
                    new File(manifest.getParentFile(), "swift5-manifest-receipt.json").getAbsolutePath()
                );
            }
        }));

        project.getTasks().matching(task ->
            task.getName().toLowerCase().endsWith("buildspmpackage")
        ).configureEach(task -> {
            task.getInputs().file(strictSwiftConfig);
            task.doFirst(t -> {
                String actual = System.getenv("XCODE_XCCONFIG_FILE");
                try {
                    if (actual == null || !new File(actual).getCanonicalFile().equals(strictSwiftConfig.getCanonicalFile())) {
                        throw new GradleException(
                            "Set XCODE_XCCONFIG_FILE=" + strictSwiftConfig + " for strict Swift 5 dependency compilation"
                        );
                    }
                } catch (IOException e) {
                    throw new GradleException("Failed to resolve XCODE_XCCONFIG_FILE", e);
                }
            });
        });

        project.getTasks().register("swiftExportSmokeTest", SmokeTestTask.class, task -> {
            task.setGroup("verification");
            task.setDescription("Builds Debug and Release Swift Export SPM packages and runs swift test in both configurations.");
            task.getOutputs().upToDateWhen(t -> false);

            task.doLast(t -> {
                ExecOperations execOps = task.getExecOperations();
                File swiftBuildRoot = project.getLayout().getBuildDirectory().dir("swift-test").get().getAsFile();
                deleteRecursively(swiftBuildRoot);
                swiftBuildRoot.mkdirs();

                File spmPackageDir = project.getLayout().getBuildDirectory().dir("SPMPackage").get().getAsFile();
                deleteRecursively(spmPackageDir);

                File swiftExportDir = project.getLayout().getBuildDirectory().dir("SwiftExport").get().getAsFile();
                deleteRecursively(swiftExportDir);

                String[][] configurations = new String[][] {
                    { "Debug", "debug" },
                    { "Release", "release" }
                };

                for (String[] pair : configurations) {
                    String xcodeConfig = pair[0];
                    File configBuildDir = new File(swiftBuildRoot, xcodeConfig);
                    configBuildDir.mkdirs();

                    List<String> exportCommand = new ArrayList<>();
                    exportCommand.add(new File(rootProject.getProjectDir(), "gradlew").getAbsolutePath());
                    String embedTaskPath = ":".equals(project.getPath())
                        ? "embedSwiftExportForXcode"
                        : project.getPath() + ":embedSwiftExportForXcode";
                    exportCommand.add(embedTaskPath);
                    exportCommand.add("--no-configuration-cache");
                    exportCommand.add("--no-daemon");
                    exportCommand.add("--console=plain");
                    exportCommand.add("--max-workers=" + project.getGradle().getStartParameter().getMaxWorkerCount());
                    if (project.getGradle().getStartParameter().isOffline()) {
                        exportCommand.add("--offline");
                    }
                    project.getGradle().getStartParameter().getProjectProperties().forEach((k, v) ->
                        exportCommand.add("-P" + k + "=" + v)
                    );
                    String localMaven = project.getGradle().getStartParameter().getSystemPropertiesArgs().get("maven.repo.local");
                    if (localMaven != null) {
                        exportCommand.add("-Dmaven.repo.local=" + localMaven);
                    }

                    Map<String, String> env = new LinkedHashMap<>();
                    env.put("BUILT_PRODUCTS_DIR", configBuildDir.getAbsolutePath());
                    env.put("TARGET_BUILD_DIR", configBuildDir.getAbsolutePath());
                    env.put("SDK_NAME", "macosx");
                    env.put("CONFIGURATION", xcodeConfig);
                    env.put("ARCHS", "arm64");
                    env.put("FRAMEWORKS_FOLDER_PATH", "Frameworks");
                    env.put("MACOSX_DEPLOYMENT_TARGET", "15.0");
                    env.put("DEPLOYMENT_TARGET_SETTING_NAME", "MACOSX_DEPLOYMENT_TARGET");
                    env.put("XCODE_XCCONFIG_FILE", strictSwiftConfig.getAbsolutePath());

                    execOps.exec(spec -> {
                        spec.setWorkingDir(rootProject.getProjectDir());
                        spec.commandLine(exportCommand);
                        spec.environment(env);
                    }).assertNormalExitValue();

                    File expectedManifest = new File(spmPackageDir, "macosArm64/" + xcodeConfig + "/Package.swift");
                    if (!expectedManifest.isFile()) {
                        throw new GradleException("Missing generated " + xcodeConfig + " Swift Export Package.swift at " + expectedManifest);
                    }
                    File expectedStaticLib = new File(configBuildDir, "lib" + swiftExportModuleName + ".a");
                    if (!expectedStaticLib.isFile() || expectedStaticLib.length() == 0L) {
                        throw new GradleException("Missing generated " + xcodeConfig + " Swift Export static library at " + expectedStaticLib);
                    }
                }

                String developerDir;
                try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    execOps.exec(spec -> {
                        spec.commandLine("xcode-select", "-p");
                        spec.setStandardOutput(out);
                    }).assertNormalExitValue();
                    developerDir = out.toString(StandardCharsets.UTF_8).trim();
                } catch (IOException e) {
                    throw new GradleException("Failed to query xcode-select -p", e);
                }

                File harnessDir = new File(project.getProjectDir(), "src/swift/swift-test-harness");

                for (String[] pair : configurations) {
                    String xcodeConfig = pair[0];
                    String swiftConfig = pair[1];

                    execOps.exec(spec -> {
                        spec.setWorkingDir(harnessDir);
                        spec.commandLine("swift", "package", "reset");
                        spec.environment("SWIFT_EXPORT_CONFIGURATION", xcodeConfig);
                        spec.environment("CONFIGURATION", xcodeConfig);
                    }).assertNormalExitValue();

                    List<String> swiftTestCommand = new ArrayList<>(List.of(
                        "swift",
                        "test",
                        "-c",
                        swiftConfig,
                        "-v",
                        "-Xswiftc",
                        "-warnings-as-errors",
                        "-Xswiftc",
                        "-target",
                        "-Xswiftc",
                        "arm64-apple-macosx15.0"
                    ));
                    if (developerDir.endsWith("CommandLineTools")) {
                        String frameworks = developerDir + "/Library/Developer/Frameworks";
                        swiftTestCommand.addAll(List.of(
                            "-Xswiftc", "-F", "-Xswiftc", frameworks,
                            "-Xlinker", "-F", "-Xlinker", frameworks,
                            "-Xlinker", "-rpath", "-Xlinker", frameworks,
                            "-Xlinker", "-rpath", "-Xlinker", developerDir + "/Library/Developer/usr/lib"
                        ));
                    }

                    ByteArrayOutputStream testOut = new ByteArrayOutputStream();
                    ExecResult result = execOps.exec(spec -> {
                        spec.setWorkingDir(harnessDir);
                        spec.commandLine(swiftTestCommand);
                        spec.environment("SWIFT_EXPORT_CONFIGURATION", xcodeConfig);
                        spec.environment("CONFIGURATION", xcodeConfig);
                        spec.setStandardOutput(testOut);
                        spec.setErrorOutput(testOut);
                        spec.setIgnoreExitValue(true);
                    });
                    String log = testOut.toString(StandardCharsets.UTF_8);
                    System.out.println(log);
                    result.assertNormalExitValue();
                    if (!SWIFT_TEST_RUN_PATTERN.matcher(log).find()) {
                        throw new GradleException("swift test (-c " + swiftConfig + ") ran no Swift Testing tests (toolchain: " + developerDir + ")");
                    }
                }
            });
        });
    }

    private static void wrapWorkerExecutor(Task task, String maxHeapSize) {
        Class<?> cls = task.getClass();
        Field field = null;
        while (cls != null) {
            try {
                field = cls.getDeclaredField("workerExecutor");
                break;
            } catch (NoSuchFieldException ignored) {
                cls = cls.getSuperclass();
            }
        }
        if (field == null) {
            return;
        }
        try {
            field.setAccessible(true);
            Object raw = field.get(task);
            if (!(raw instanceof WorkerExecutor delegate)) {
                return;
            }
            WorkerExecutor wrapper = new WorkerExecutor() {
                @Override
                public WorkQueue noIsolation() {
                    return delegate.noIsolation();
                }

                @Override
                public WorkQueue noIsolation(Action<? super org.gradle.workers.WorkerSpec> action) {
                    return delegate.noIsolation(action);
                }

                @Override
                public WorkQueue classLoaderIsolation() {
                    return delegate.classLoaderIsolation();
                }

                @Override
                public WorkQueue classLoaderIsolation(Action<? super ClassLoaderWorkerSpec> action) {
                    return delegate.classLoaderIsolation(action);
                }

                @Override
                public WorkQueue processIsolation() {
                    return delegate.processIsolation(spec -> {
                        String existing = spec.getForkOptions().getMaxHeapSize();
                        if (existing == null || existing.isBlank()) {
                            spec.getForkOptions().setMaxHeapSize(maxHeapSize);
                        }
                    });
                }

                @Override
                public WorkQueue processIsolation(Action<? super ProcessWorkerSpec> action) {
                    return delegate.processIsolation(spec -> {
                        String existing = spec.getForkOptions().getMaxHeapSize();
                        if (existing == null || existing.isBlank()) {
                            spec.getForkOptions().setMaxHeapSize(maxHeapSize);
                        }
                        action.execute(spec);
                    });
                }

                @Override
                public void await() {
                    delegate.await();
                }
            };
            field.set(task, wrapper);
        } catch (IllegalAccessException e) {
            throw new GradleException("Failed to wrap SwiftExportTask.workerExecutor", e);
        }
    }

    private static void collectPackageManifests(File dir, List<File> out) {
        if (dir == null || !dir.exists()) {
            return;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collectPackageManifests(child, out);
            } else if ("Package.swift".equals(child.getName())) {
                out.add(child);
            }
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
