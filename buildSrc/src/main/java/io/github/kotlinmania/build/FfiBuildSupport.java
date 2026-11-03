package io.github.kotlinmania.build;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.initialization.IncludedBuild;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Copy;
import org.gradle.api.tasks.Exec;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.Sync;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.testing.Test;
import org.gradle.process.ExecOperations;

/**
 * Compiles per-target C static wrappers and JVM/Android JNI shared libraries.
 */
public final class FfiBuildSupport {
    private FfiBuildSupport() {}

    public abstract static class ExecTask extends DefaultTask {
        @Inject
        public abstract ExecOperations getExecOperations();
    }

    public abstract static class AndroidJniCompileTask extends ExecTask {
        @OutputDirectory
        public abstract DirectoryProperty getNativeDirectory();
    }

    public static TaskProvider<ExecTask> registerNativeWrapperTask(
        Project project,
        String ffiName,
        String ffiWrapperBase,
        String konanTargetName,
        String targetCapitalized,
        File konanDataDir
    ) {
        Project rootProject = project.getRootProject();
        String wrapperTaskName = "compile" + ffiName + "WrapperFor" + targetCapitalized;
        return project.getTasks().register(wrapperTaskName, ExecTask.class, task -> {
            task.setGroup("build");
            task.setDescription("Compiles " + ffiWrapperBase + ".c into a static library for " + konanTargetName);
            File cSource = project.file("src/native/interop/cinterop/" + ffiWrapperBase + ".c");
            File hSource = project.file("src/native/interop/cinterop/" + ffiWrapperBase + ".h");
            File outDir = project.getLayout().getBuildDirectory().dir("cinterop-targets/" + konanTargetName).get().getAsFile();
            File outFile = new File(outDir, ffiWrapperBase + ".a");
            task.getInputs().file(cSource);
            task.getInputs().file(hSource);
            if (konanTargetName.startsWith("linux")) {
                task.getInputs().dir(rootProject.file("buildSrc/src/main/java"));
                task.getOutputs().file(new File(outDir, "native-archive-verification.json"));
            }
            task.getOutputs().file(outFile);

            task.doLast(t -> {
                outDir.mkdirs();
                if (outFile.exists()) {
                    outFile.delete();
                }
                if (konanTargetName.startsWith("linux")) {
                    new File(outDir, "native-archive-verification.json").delete();
                }
                File tempObj = new File(outDir, ffiWrapperBase + ".o");
                File konanDeps = project.getLayout().getBuildDirectory().dir("native-wrapper-dependencies").get().getAsFile();
                String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
                boolean isMac = os.contains("mac");
                boolean isLinux = os.contains("linux");
                boolean isWindows = os.contains("windows");
                ExecOperations execOps = task.getExecOperations();

                if (isMac && (konanTargetName.startsWith("macos") || konanTargetName.startsWith("ios")
                    || konanTargetName.startsWith("tvos") || konanTargetName.startsWith("watchos"))) {
                    String appleTriple;
                    String sdkName;
                    switch (konanTargetName) {
                        case "macos_arm64" -> { appleTriple = "arm64-apple-macos14.0"; sdkName = "macosx"; }
                        case "macos_x64" -> { appleTriple = "x86_64-apple-macos14.0"; sdkName = "macosx"; }
                        case "ios_arm64" -> { appleTriple = "arm64-apple-ios14.0"; sdkName = "iphoneos"; }
                        case "ios_simulator_arm64" -> { appleTriple = "arm64-apple-ios14.0-simulator"; sdkName = "iphonesimulator"; }
                        case "ios_x64" -> { appleTriple = "x86_64-apple-ios14.0-simulator"; sdkName = "iphonesimulator"; }
                        case "watchos_device_arm64" -> { appleTriple = "arm64-apple-watchos7.0"; sdkName = "watchos"; }
                        case "watchos_simulator_arm64" -> { appleTriple = "arm64-apple-watchos7.0-simulator"; sdkName = "watchsimulator"; }
                        case "tvos_arm64" -> { appleTriple = "arm64-apple-tvos14.0"; sdkName = "appletvos"; }
                        case "tvos_simulator_arm64" -> { appleTriple = "arm64-apple-tvos14.0-simulator"; sdkName = "appletvsimulator"; }
                        default -> { appleTriple = "arm64-apple-macos14.0"; sdkName = "macosx"; }
                    }
                    execOps.exec(s -> s.commandLine(
                        "xcrun", "-sdk", sdkName, "clang", "-target", appleTriple,
                        "-Wall", "-Wextra", "-Werror", "-c", "-I" + cSource.getParent(),
                        cSource.getAbsolutePath(), "-o", tempObj.getAbsolutePath()
                    )).assertNormalExitValue();
                    execOps.exec(s -> s.commandLine(
                        "xcrun", "-sdk", sdkName, "ar", "rcs", outFile.getAbsolutePath(), tempObj.getAbsolutePath()
                    )).assertNormalExitValue();
                } else if (konanTargetName.startsWith("android")) {
                    String hostToolchain = isMac ? "osx" : "linux";
                    File ndkDir = ensureKonanDependency(
                        project, "target-toolchain-2-" + hostToolchain + "-android_ndk", konanDataDir, konanDeps, execOps
                    );
                    ensureKonanDependency(project, "target-sysroot-1-android_ndk", konanDataDir, konanDeps, execOps);
                    File sysroot = new File(ndkDir, "sysroot");
                    File clangBin = konanTargetName.contains("arm64")
                        ? new File(ndkDir, "bin/aarch64-linux-android21-clang")
                        : new File(ndkDir, "bin/x86_64-linux-android21-clang");
                    File arBin = konanTargetName.contains("arm64")
                        ? new File(ndkDir, "bin/aarch64-linux-android-ar")
                        : new File(ndkDir, "bin/x86_64-linux-android-ar");
                    if (clangBin.exists() && sysroot.exists()) {
                        execOps.exec(s -> s.commandLine(
                            clangBin.getAbsolutePath(), "--sysroot=" + sysroot.getAbsolutePath(),
                            "-fPIC", "-Wall", "-Wextra", "-Werror", "-c", "-I" + cSource.getParent(),
                            cSource.getAbsolutePath(), "-o", tempObj.getAbsolutePath()
                        )).assertNormalExitValue();
                        execOps.exec(s -> s.commandLine(
                            arBin.getAbsolutePath(), "rcs", outFile.getAbsolutePath(), tempObj.getAbsolutePath()
                        )).assertNormalExitValue();
                    } else {
                        copyPrebuilt(project, konanTargetName, ffiWrapperBase, outFile);
                    }
                } else if (konanTargetName.startsWith("linux")) {
                    String targetTriple = konanTargetName.contains("arm64")
                        ? "aarch64-unknown-linux-gnu" : "x86_64-unknown-linux-gnu";
                    String gccDirName = konanTargetName.contains("arm64")
                        ? "aarch64-unknown-linux-gnu-gcc-8.3.0-glibc-2.25-kernel-4.9-2"
                        : "x86_64-unknown-linux-gnu-gcc-8.3.0-glibc-2.19-kernel-4.9-2";
                    if (isLinux) {
                        File gccDirectory = ensureKonanDependency(project, gccDirName, konanDataDir, konanDeps, execOps);
                        File sysroot = new File(gccDirectory, targetTriple + "/sysroot");
                        if (!sysroot.isDirectory()) {
                            throw new GradleException("Missing target sysroot for " + konanTargetName + ": " + sysroot);
                        }
                        execOps.exec(s -> s.commandLine(
                            "clang", "--target=" + targetTriple, "--sysroot=" + sysroot.getAbsolutePath(),
                            "-fPIC", "-Wall", "-Wextra", "-Werror", "-c", "-I" + cSource.getParent(),
                            cSource.getAbsolutePath(), "-o", tempObj.getAbsolutePath()
                        )).assertNormalExitValue();
                        execOps.exec(s -> s.commandLine(
                            "ar", "rcs", outFile.getAbsolutePath(), tempObj.getAbsolutePath()
                        )).assertNormalExitValue();
                    } else if (isMac) {
                        File gccDirectory = ensureKonanDependency(project, gccDirName, konanDataDir, konanDeps, execOps);
                        File ndkDirectory = ensureKonanDependency(
                            project, "target-toolchain-2-osx-android_ndk", konanDataDir, konanDeps, execOps
                        );
                        File sysroot = new File(gccDirectory, targetTriple + "/sysroot");
                        File llvmAr = new File(ndkDirectory, "bin/llvm-ar");
                        if (sysroot.exists() && llvmAr.exists()) {
                            execOps.exec(s -> s.commandLine(
                                "clang", "--target=" + targetTriple, "--sysroot=" + sysroot.getAbsolutePath(),
                                "-fPIC", "-Wall", "-Wextra", "-Werror", "-c", "-I" + cSource.getParent(),
                                cSource.getAbsolutePath(), "-o", tempObj.getAbsolutePath()
                            )).assertNormalExitValue();
                            execOps.exec(s -> s.commandLine(
                                llvmAr.getAbsolutePath(), "rcs", outFile.getAbsolutePath(), tempObj.getAbsolutePath()
                            )).assertNormalExitValue();
                        } else {
                            copyPrebuilt(project, konanTargetName, ffiWrapperBase, outFile);
                        }
                    } else {
                        copyPrebuilt(project, konanTargetName, ffiWrapperBase, outFile);
                    }
                } else if (konanTargetName.startsWith("mingw")) {
                    File mingwSysroot = ensureKonanDependency(project, "msys2-mingw-w64-x86_64-2", konanDataDir, konanDeps, execOps);
                    File arExe = new File(mingwSysroot, "bin/ar.exe");
                    if (isWindows && mingwSysroot.exists()) {
                        execOps.exec(s -> s.commandLine(
                            "clang", "--target=x86_64-w64-mingw32", "--sysroot=" + mingwSysroot.getAbsolutePath(),
                            "-Wall", "-Wextra", "-Werror", "-c", "-I" + cSource.getParent(),
                            cSource.getAbsolutePath(), "-o", tempObj.getAbsolutePath()
                        )).assertNormalExitValue();
                        execOps.exec(s -> s.commandLine(
                            arExe.exists() ? arExe.getAbsolutePath() : "ar", "rcs", outFile.getAbsolutePath(), tempObj.getAbsolutePath()
                        )).assertNormalExitValue();
                    } else if (isMac) {
                        File ndkDirectory = ensureKonanDependency(
                            project, "target-toolchain-2-osx-android_ndk", konanDataDir, konanDeps, execOps
                        );
                        File llvmAr = new File(ndkDirectory, "bin/llvm-ar");
                        if (mingwSysroot.exists() && llvmAr.exists()) {
                            execOps.exec(s -> s.commandLine(
                                "clang", "--target=x86_64-w64-mingw32", "--sysroot=" + mingwSysroot.getAbsolutePath(),
                                "-Wall", "-Wextra", "-Werror", "-c", "-I" + cSource.getParent(),
                                cSource.getAbsolutePath(), "-o", tempObj.getAbsolutePath()
                            )).assertNormalExitValue();
                            execOps.exec(s -> s.commandLine(
                                llvmAr.getAbsolutePath(), "rcs", outFile.getAbsolutePath(), tempObj.getAbsolutePath()
                            )).assertNormalExitValue();
                        } else {
                            copyPrebuilt(project, "mingw_x64", ffiWrapperBase, outFile);
                        }
                    } else {
                        copyPrebuilt(project, "mingw_x64", ffiWrapperBase, outFile);
                    }
                } else {
                    copyPrebuilt(project, konanTargetName, ffiWrapperBase, outFile);
                }

                if (!outFile.exists() || outFile.length() == 0L) {
                    throw new GradleException(
                        "compile" + ffiName + "Wrapper failed: output library does not exist for " + konanTargetName + " at " + outFile
                    );
                }
                tempObj.delete();
            });
        });
    }

    public static TaskProvider<Sync> registerJniTasks(
        Project project,
        String ffiName,
        String ffiNameLower,
        String projectNamespace
    ) {
        String projectNsPath = projectNamespace.replace('.', '/');
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        boolean isWindowsHost = os.contains("windows");
        boolean isMacHost = os.contains("mac");

        String jvmMainCDir = IdeSourceSetSupport.sourceSetDir("jvmMain") + "/c/";
        TaskProvider<ExecTask> compileFfiJni = project.getTasks().register("compile" + ffiName + "Jni", ExecTask.class, task -> {
            task.setGroup("build");
            task.setDescription("Compiles " + ffiNameLower + "_jni.c into a shared library for JVM JNI binding");
            File cSource = project.file(jvmMainCDir + ffiNameLower + "_jni.c");
            File outDir = project.getLayout().getBuildDirectory().dir("natives").get().getAsFile();
            task.getInputs().file(cSource);
            task.getInputs().files(project.fileTree("src/native/interop/cinterop", spec -> spec.include("*.h")));
            task.getOutputs().dir(outDir);

            task.doLast(t -> {
                outDir.mkdirs();
                String javaHome = System.getProperty("java.home");
                if (javaHome == null || javaHome.isEmpty()) {
                    javaHome = System.getenv("JAVA_HOME");
                }
                String libName;
                String osInclude;
                if (isWindowsHost) {
                    libName = ffiNameLower + "_jni.dll";
                    osInclude = "win32";
                } else if (isMacHost) {
                    libName = "lib" + ffiNameLower + "_jni.dylib";
                    osInclude = "darwin";
                } else {
                    libName = "lib" + ffiNameLower + "_jni.so";
                    osInclude = "linux";
                }
                File outFile = new File(outDir, libName);
                File javaInclude = new File(javaHome != null ? javaHome : "", "include");
                File javaOsInclude = new File(javaInclude, osInclude);
                if (outFile.exists() && !outFile.delete()) {
                    throw new GradleException("Cannot remove old JNI artifact: " + outFile);
                }
                if (!new File(javaInclude, "jni.h").isFile() || !new File(javaOsInclude, "jni_md.h").isFile()) {
                    throw new GradleException("JNI headers are required at " + javaInclude + " and " + javaOsInclude);
                }
                List<String> cmd = new ArrayList<>();
                cmd.add("clang");
                cmd.add("-shared");
                if (!isWindowsHost) {
                    cmd.add("-fPIC");
                }
                cmd.add("-O2");
                cmd.add("-I" + javaInclude.getAbsolutePath());
                cmd.add("-I" + javaOsInclude.getAbsolutePath());
                cmd.add(cSource.getAbsolutePath());
                cmd.add("-o");
                cmd.add(outFile.getAbsolutePath());
                if (isWindowsHost) {
                    cmd.add("-lws2_32");
                }
                task.getExecOperations().exec(s -> s.commandLine(cmd)).assertNormalExitValue();
                if (!outFile.isFile() || outFile.length() == 0L) {
                    throw new GradleException("JNI compiler produced no artifact: " + outFile);
                }
            });
        });

        TaskProvider<Sync> packageFfiJni = project.getTasks().register("package" + ffiName + "Jni", Sync.class, task -> {
            task.dependsOn(compileFfiJni);
            String hostPlatform = isWindowsHost ? "windows" : (isMacHost ? "macos" : "linux");
            String archRaw = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
            String hostArchitecture;
            if ("aarch64".equals(archRaw) || "arm64".equals(archRaw)) {
                hostArchitecture = "aarch64";
            } else if ("amd64".equals(archRaw) || "x86_64".equals(archRaw)) {
                hostArchitecture = "x86_64";
            } else {
                throw new GradleException("Unsupported JNI host architecture: " + archRaw);
            }
            task.from(project.getLayout().getBuildDirectory().dir("natives"), spec -> {
                spec.include("*.so", "*.dll", "*.dylib");
                spec.into(projectNsPath + "/native/" + hostPlatform + "-" + hostArchitecture);
            });
            task.from(project.getLayout().getBuildDirectory().dir("jni-distribution"), spec -> {
                spec.exclude(hostPlatform + "-" + hostArchitecture + "/**");
                spec.into(projectNsPath + "/native");
            });
            task.into(project.getLayout().getBuildDirectory().dir("generated/jniResources"));
        });

        project.getTasks().register("verifyJniDistribution", task -> {
            task.dependsOn(packageFfiJni);
            task.doLast(t -> {
                File root = project.getLayout().getBuildDirectory()
                    .dir("generated/jniResources/" + projectNsPath + "/native").get().getAsFile();
                List<String> required = List.of(
                    "macos-aarch64/lib" + ffiNameLower + "_jni.dylib",
                    "linux-x86_64/lib" + ffiNameLower + "_jni.so",
                    "linux-aarch64/lib" + ffiNameLower + "_jni.so",
                    "windows-x86_64/" + ffiNameLower + "_jni.dll"
                );
                for (String rel : required) {
                    if (!new File(root, rel).isFile()) {
                        throw new GradleException("Missing release JNI payload: " + rel);
                    }
                }
            });
        });

        return packageFfiJni;
    }

    public static TaskProvider<AndroidJniCompileTask> registerAndroidJniTask(
        Project project,
        String ffiName,
        String ffiNameLower,
        boolean nativeFfiEnabled,
        boolean nativeFfiCompileWrapper,
        File konanDataDir
    ) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        boolean isWindowsHost = os.contains("windows");
        boolean isMacHost = os.contains("mac");

        String jvmMainCDir = IdeSourceSetSupport.sourceSetDir("jvmMain") + "/c/";
        return project.getTasks().register("compileAndroidJni", AndroidJniCompileTask.class, task -> {
            if (nativeFfiEnabled && nativeFfiCompileWrapper) {
                task.dependsOn("compile" + ffiName + "WrapperForAndroidNativeArm64", "compile" + ffiName + "WrapperForAndroidNativeX64");
            }
            task.getInputs().file(jvmMainCDir + ffiNameLower + "_jni.c");
            task.getInputs().files(project.fileTree("src/native/interop/cinterop", spec -> spec.include("*.h")));
            task.getNativeDirectory().set(project.getLayout().getBuildDirectory().dir("android-jni"));
            task.doLast(t -> {
                if (isWindowsHost) {
                    throw new GradleException("Build Android JNI payloads on the coordinated macOS or Linux host");
                }
                String host = isMacHost ? "osx" : "linux";
                String depName = "target-toolchain-2-" + host + "-android_ndk";
                File compilerDeps = new File(konanDataDir, "dependencies");
                File compilerReceipt = new File(compilerDeps, ".extracted");
                File compilerDir = new File(compilerDeps, depName);
                File ndk;
                if (isReceiptExtracted(compilerReceipt, depName, compilerDir)) {
                    ndk = compilerDir;
                } else {
                    ndk = project.getLayout().getBuildDirectory()
                        .dir("native-wrapper-dependencies/" + depName).get().getAsFile();
                }
                File source = project.file(jvmMainCDir + ffiNameLower + "_jni.c");
                Object minSdkObj = project.findProperty("android.minSdk");
                String api = minSdkObj != null ? minSdkObj.toString().trim() : "24";
                String[][] abis = new String[][] {
                    { "arm64-v8a", "aarch64-linux-android" },
                    { "x86_64", "x86_64-linux-android" }
                };
                for (String[] pair : abis) {
                    String abi = pair[0];
                    String triple = pair[1];
                    File compiler = new File(ndk, "bin/" + triple + api + "-clang");
                    if (!compiler.exists()) {
                        throw new GradleException("Missing Android JNI compiler " + compiler);
                    }
                    File output = new File(task.getNativeDirectory().get().getAsFile(), abi + "/lib" + ffiNameLower + "_jni.so");
                    output.getParentFile().mkdirs();
                    task.getExecOperations().exec(s -> s.commandLine(
                        compiler.getAbsolutePath(),
                        "--sysroot=" + new File(ndk, "sysroot").getAbsolutePath(),
                        "-shared", "-fPIC", "-O2", "-Wall", "-Wextra", "-Werror",
                        "-Wl,-z,max-page-size=16384",
                        source.getAbsolutePath(), "-o", output.getAbsolutePath()
                    )).assertNormalExitValue();
                }
            });
        });
    }

    public static void registerNapiTasks(
        Project project,
        String ffiName,
        String ffiNameLower,
        String ffiNapiDir,
        String ffiNpmPackage,
        String nodeVersion,
        Provider<String> nodeExecutable
    ) {
        Project rootProject = project.getRootProject();
        File addonDirectory = rootProject.file(ffiNapiDir);
        TaskProvider<Exec> installNodeFfiHeaders = project.getTasks().register(
            "installNode" + ffiName + "Headers",
            Exec.class,
            task -> {
                task.setGroup("build");
                task.setDescription("Installs the N-API C++ header dependency with package scripts disabled.");
                task.dependsOn(rootProject.getTasks().named("kotlinNodeJsSetup"));
                task.setWorkingDir(addonDirectory);
                task.doFirst(t -> {
                    File node = new File(nodeExecutable.get());
                    File npm = System.getProperty("os.name", "").startsWith("Windows")
                        ? new File(node.getParentFile(), "node_modules/npm/bin/npm-cli.js")
                        : new File(node.getParentFile().getParentFile(), "lib/node_modules/npm/bin/npm-cli.js");
                    File scratch = project.getLayout().getBuildDirectory().dir("tmp/node-ffi-npm").get().getAsFile();
                    scratch.mkdirs();
                    task.environment("TMPDIR", scratch.getAbsolutePath());
                    task.environment("TMP", scratch.getAbsolutePath());
                    task.environment("TEMP", scratch.getAbsolutePath());
                    List<String> cmd = new ArrayList<>();
                    cmd.add(node.getAbsolutePath());
                    cmd.add(npm.getAbsolutePath());
                    cmd.add(new File(addonDirectory, "package-lock.json").isFile() ? "ci" : "install");
                    cmd.add("--ignore-scripts");
                    cmd.add("--omit=dev");
                    cmd.add("--no-audit");
                    cmd.add("--no-fund");
                    if (project.getGradle().getStartParameter().isOffline()) {
                        cmd.add("--offline");
                    }
                    task.commandLine(cmd);
                });
            }
        );

        TaskProvider<BuildToolTask> buildNodeFfi = project.getTasks().register(
            "buildNode" + ffiName,
            BuildToolTask.class,
            task -> {
                task.setGroup("build");
                task.setDescription("Builds the native node-" + ffiNameLower + " N-API addon with its declared binding settings.");
                task.dependsOn(installNodeFfiHeaders);
                task.getToolClass().set(NodeFfiBuilder.class.getName());
                task.getArguments().addAll(
                    "--project-dir", rootProject.getProjectDir().getAbsolutePath(),
                    "--addon-dir", addonDirectory.getAbsolutePath(),
                    "--build-dir", project.getLayout().getBuildDirectory().get().getAsFile().getAbsolutePath(),
                    "--node-version", nodeVersion
                );
                task.getArguments().addAll(nodeExecutable.map(it -> List.of("--node", it)));
                if (project.getGradle().getStartParameter().isOffline()) {
                    task.getArguments().add("--offline");
                }
            }
        );

        TaskProvider<Copy> copyNodeFfi = project.getTasks().register(
            "copyNode" + ffiName,
            Copy.class,
            task -> {
                task.dependsOn(
                    buildNodeFfi,
                    rootProject.getTasks().named("kotlinNpmInstall"),
                    rootProject.getTasks().named("kotlinUpgradeYarnLock")
                );
                task.getOutputs().upToDateWhen(t -> false);
                task.from(addonDirectory);
                task.into(rootProject.getLayout().getBuildDirectory().dir("js/node_modules/" + ffiNpmPackage));
            }
        );

        TaskProvider<Copy> copyWasmNodeFfi = project.getTasks().register(
            "copyWasmNode" + ffiName,
            Copy.class,
            task -> {
                task.dependsOn(
                    buildNodeFfi,
                    rootProject.getTasks().named("kotlinWasmNpmInstall"),
                    rootProject.getTasks().named("kotlinWasmUpgradeYarnLock")
                );
                task.getOutputs().upToDateWhen(t -> false);
                task.from(addonDirectory);
                task.into(rootProject.getLayout().getBuildDirectory().dir("wasm/node_modules/" + ffiNpmPackage));
            }
        );

        project.getTasks()
            .matching(t -> t.getName().startsWith("jsNodeTest"))
            .configureEach(t -> t.dependsOn(copyNodeFfi));

        project.getTasks()
            .matching(t -> t.getName().startsWith("wasmJsNodeTest"))
            .configureEach(t -> t.dependsOn(copyWasmNodeFfi));
    }

    public static void configureIncludedBuildsFfi(Project project, boolean jvmFfiEnabled) {
        Project rootProject = project.getRootProject();
        List<String> extraJavaLibPaths = new ArrayList<>();
        if (jvmFfiEnabled) {
            extraJavaLibPaths.add(project.getLayout().getBuildDirectory().dir("natives").get().getAsFile().getAbsolutePath());
        }
        for (IncludedBuild ib : project.getGradle().getIncludedBuilds()) {
            File ibPropsFile = new File(ib.getProjectDir(), "gradle.properties");
            if (!ibPropsFile.exists()) {
                continue;
            }
            Map<String, String> ibProps = new HashMap<>();
            try {
                for (String rawLine : Files.readAllLines(ibPropsFile.toPath(), StandardCharsets.UTF_8)) {
                    String line = rawLine.trim();
                    int eq = line.indexOf('=');
                    if (!line.isEmpty() && !line.startsWith("#") && eq > 0) {
                        ibProps.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                    }
                }
            } catch (IOException ignored) {
                continue;
            }
            String ibFfiRaw = ibProps.getOrDefault("project.ffi.name", "Ffi").trim();
            String ibFfiName = ibFfiRaw.isEmpty() ? "Ffi" : Character.toUpperCase(ibFfiRaw.charAt(0)) + ibFfiRaw.substring(1);
            String ibFfiLower = ibFfiRaw.toLowerCase(Locale.ROOT);
            boolean ibJvmFfi = "true".equalsIgnoreCase(ibProps.getOrDefault("project.ffi.jvm", "false").trim());
            boolean ibNapiFfi = "true".equalsIgnoreCase(ibProps.getOrDefault("project.ffi.napi", "false").trim());
            boolean ibHasLibrarySubproject = new File(ib.getProjectDir(), "library").isDirectory();
            String ibTaskPrefix = ibHasLibrarySubproject ? ":library" : "";
            String ibBuildPrefix = ibHasLibrarySubproject ? "library/build" : "build";

            if (ibJvmFfi) {
                extraJavaLibPaths.add(new File(ib.getProjectDir(), ibBuildPrefix + "/natives").getAbsolutePath());
                project.getTasks().named("jvmTest", Test.class, t -> t.dependsOn(ib.task(ibTaskPrefix + ":compile" + ibFfiName + "Jni")));
            }
            if (ibNapiFfi) {
                TaskProvider<Copy> copyIbNode = project.getTasks().register(
                    "copyIncludedNode" + ibFfiName,
                    Copy.class,
                    task -> {
                        task.dependsOn(
                            ib.task(ibTaskPrefix + ":buildNode" + ibFfiName),
                            rootProject.getTasks().named("kotlinNpmInstall"),
                            rootProject.getTasks().named("kotlinUpgradeYarnLock")
                        );
                        task.getOutputs().upToDateWhen(t -> false);
                        task.from(new File(ib.getProjectDir(), "src/js/node-" + ibFfiLower + "/dist"));
                        task.into(rootProject.getLayout().getBuildDirectory().dir("js/node_modules/@kotlinmania/" + ibFfiLower + "-native-bindings"));
                    }
                );
                TaskProvider<Copy> copyIbWasmNode = project.getTasks().register(
                    "copyIncludedWasmNode" + ibFfiName,
                    Copy.class,
                    task -> {
                        task.dependsOn(
                            ib.task(ibTaskPrefix + ":buildNode" + ibFfiName),
                            rootProject.getTasks().named("kotlinWasmNpmInstall"),
                            rootProject.getTasks().named("kotlinWasmUpgradeYarnLock")
                        );
                        task.getOutputs().upToDateWhen(t -> false);
                        task.from(new File(ib.getProjectDir(), "src/js/node-" + ibFfiLower + "/dist"));
                        task.into(rootProject.getLayout().getBuildDirectory().dir("wasm/node_modules/@kotlinmania/" + ibFfiLower + "-native-bindings"));
                    }
                );
                project.getTasks()
                    .matching(t -> t.getName().startsWith("jsNodeTest") || t.getName().startsWith("wasmJsNodeTest"))
                    .configureEach(t -> t.dependsOn(copyIbNode, copyIbWasmNode));
            }
        }
        if (!extraJavaLibPaths.isEmpty()) {
            String joined = String.join(File.pathSeparator, extraJavaLibPaths);
            project.getTasks().named("jvmTest", Test.class, t -> t.systemProperty("java.library.path", joined));
        }
    }

    private static File ensureKonanDependency(
        Project project,
        String name,
        File konanDataDir,
        File konanDeps,
        ExecOperations execOps
    ) {
        File compilerDeps = new File(konanDataDir, "dependencies");
        File compilerDir = new File(compilerDeps, name);
        File compilerReceipt = new File(compilerDeps, ".extracted");
        if (isReceiptExtracted(compilerReceipt, name, compilerDir)) {
            return compilerDir;
        }
        File targetDir = new File(konanDeps, name);
        String[] list = targetDir.list();
        if (targetDir.isDirectory() && list != null && list.length > 0) {
            return targetDir;
        }
        konanDeps.mkdirs();
        File cacheDir = new File(konanDeps, "cache");
        cacheDir.mkdirs();
        File archive = new File(cacheDir, name + ".tar.gz");
        String url = "https://download.jetbrains.com/kotlin/native/" + name + ".tar.gz";
        if (!archive.exists() || archive.length() == 0L) {
            if (project.getGradle().getStartParameter().isOffline()) {
                throw new GradleException("Missing Kotlin/Native dependency " + name + " in " + konanDeps + "; offline mode forbids downloading " + url);
            }
            System.out.println("Downloading Kotlin/Native dependency " + name + " from " + url + " ...");
            execOps.exec(s -> s.commandLine("curl", "-sSL", url, "-o", archive.getAbsolutePath())).assertNormalExitValue();
        }
        System.out.println("Extracting " + archive + " into " + konanDeps + " ...");
        execOps.exec(s -> s.commandLine("tar", "-xzf", archive.getAbsolutePath(), "-C", konanDeps.getAbsolutePath())).assertNormalExitValue();
        String[] extracted = targetDir.list();
        if (!targetDir.isDirectory() || extracted == null || extracted.length == 0) {
            throw new GradleException("Native wrapper dependency extraction produced no directory: " + targetDir);
        }
        return targetDir;
    }

    private static boolean isReceiptExtracted(File receipt, String name, File dir) {
        if (!receipt.isFile() || !dir.isDirectory()) {
            return false;
        }
        String[] list = dir.list();
        if (list == null || list.length == 0) {
            return false;
        }
        try {
            return Files.readAllLines(receipt.toPath(), StandardCharsets.UTF_8).contains(name);
        } catch (IOException e) {
            return false;
        }
    }

    private static void copyPrebuilt(Project project, String konanTargetName, String ffiWrapperBase, File outFile) {
        File prebuilt = project.file("src/native/interop/cinterop/targets/" + konanTargetName + "/" + ffiWrapperBase + ".a");
        File fallback = project.file("src/native/interop/cinterop/" + ffiWrapperBase + ".a");
        try {
            if (prebuilt.exists()) {
                Files.copy(prebuilt.toPath(), outFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } else if (fallback.exists()) {
                Files.copy(fallback.toPath(), outFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new GradleException("Failed to copy prebuilt wrapper archive to " + outFile, e);
        }
    }
}
