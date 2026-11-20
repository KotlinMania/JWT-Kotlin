package io.github.kotlinmania.build;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.initialization.IncludedBuild;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Copy;
import org.gradle.api.tasks.Exec;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.Sync;
import org.gradle.api.tasks.TaskAction;
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

    public abstract static class ExtractHostTestDependencyNativesTask extends DefaultTask {
        @Classpath
        public abstract ConfigurableFileCollection getRuntimeClasspath();

        @Input
        public abstract Property<String> getHostSubpath();

        @OutputDirectory
        public abstract DirectoryProperty getOutputDirectory();

        @TaskAction
        public void extractNatives() {
            File outDir = getOutputDirectory().get().getAsFile();
            if (outDir.exists()) {
                File[] existing = outDir.listFiles();
                if (existing != null) {
                    for (File f : existing) {
                        if (f.isFile()) {
                            f.delete();
                        }
                    }
                }
            }
            outDir.mkdirs();
            String marker = "/native/" + getHostSubpath().get() + "/";
            for (File file : getRuntimeClasspath().getFiles()) {
                if (!file.isFile() || !file.getName().endsWith(".jar")) {
                    continue;
                }
                try (JarFile jar = new JarFile(file)) {
                    Enumeration<JarEntry> entries = jar.entries();
                    while (entries.hasMoreElements()) {
                        JarEntry entry = entries.nextElement();
                        if (entry.isDirectory()) {
                            continue;
                        }
                        String rawName = entry.getName();
                        String normalizedName = rawName.startsWith("/") ? rawName : "/" + rawName;
                        int markerIdx = normalizedName.indexOf(marker);
                        if (markerIdx < 0) {
                            continue;
                        }
                        String leaf = normalizedName.substring(markerIdx + marker.length());
                        if (leaf.contains("/")
                            || !(leaf.endsWith(".dylib") || leaf.endsWith(".so") || leaf.endsWith(".dll"))) {
                            continue;
                        }
                        File dst = new File(outDir, leaf);
                        try (InputStream in = jar.getInputStream(entry)) {
                            Files.copy(in, dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                } catch (IOException e) {
                    throw new GradleException("Failed to extract host JNI natives from " + file, e);
                }
            }
        }
    }

    public static void configureHostTestDependencyNatives(
        Project project,
        boolean jvmFfiEnabled,
        String ffiName
    ) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        boolean isWindowsHost = os.contains("windows");
        boolean isMacHost = os.contains("mac");
        String hostPlatform = isWindowsHost ? "windows" : (isMacHost ? "macos" : "linux");
        String archRaw = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String hostArchitecture = ("aarch64".equals(archRaw) || "arm64".equals(archRaw)) ? "aarch64" : "x86_64";
        String hostSubpath = hostPlatform + "-" + hostArchitecture;

        TaskProvider<ExtractHostTestDependencyNativesTask> extractTask = project.getTasks().register(
            "extractHostTestDependencyNatives",
            ExtractHostTestDependencyNativesTask.class,
            task -> {
                task.setGroup("verification");
                task.setDescription("Extracts transitive host JNI shared libraries from jvmTestRuntimeClasspath JARs");
                task.getRuntimeClasspath().from(project.getConfigurations().named("jvmTestRuntimeClasspath"));
                task.getHostSubpath().set(hostSubpath);
                task.getOutputDirectory().set(project.getLayout().getBuildDirectory().dir("natives-deps"));
            }
        );

        project.getTasks().withType(Test.class)
            .matching(t -> "jvmTest".equals(t.getName()) || "testAndroidHostTest".equals(t.getName()))
            .configureEach(task -> {
                task.dependsOn(extractTask);
                String depsPath = project.getLayout().getBuildDirectory().dir("natives-deps").get().getAsFile().getAbsolutePath();
                if (jvmFfiEnabled) {
                    task.dependsOn("compile" + ffiName + "Jni");
                    String localPath = project.getLayout().getBuildDirectory().dir("natives").get().getAsFile().getAbsolutePath();
                    task.systemProperty("java.library.path", localPath + File.pathSeparator + depsPath);
                } else {
                    task.systemProperty("java.library.path", depsPath);
                }
            });
    }

    public static TaskProvider<ExecTask> registerNativeWrapperTask(
        Project project,
        String ffiName,
        String ffiWrapperBase,
        String konanTargetName,
        String targetCapitalized,
        File konanDataDir
    ) {
        TaskProvider<ExecTask> debugTask = registerNativeWrapperVariantTask(
            project, ffiName, ffiWrapperBase, konanTargetName, targetCapitalized, konanDataDir, true
        );
        TaskProvider<ExecTask> releaseTask = registerNativeWrapperVariantTask(
            project, ffiName, ffiWrapperBase, konanTargetName, targetCapitalized, konanDataDir, false
        );
        String wrapperTaskName = "compile" + ffiName + "WrapperFor" + targetCapitalized;
        return project.getTasks().register(wrapperTaskName, ExecTask.class, task -> {
            task.setGroup("build");
            task.setDescription("Stages compiled native C/C++ wrapper static libraries for " + konanTargetName);
            task.dependsOn(debugTask, releaseTask);
            File releaseDir = project.getLayout().getBuildDirectory().dir("cinterop-targets/" + konanTargetName + "/release").get().getAsFile();
            File outDir = project.getLayout().getBuildDirectory().dir("cinterop-targets/" + konanTargetName).get().getAsFile();
            task.getInputs().dir(releaseDir);
            if (konanTargetName.startsWith("linux")) {
                task.getOutputs().file(new File(outDir, "native-archive-verification.json"));
            }
            task.getOutputs().dir(outDir);
            task.doLast(t -> {
                outDir.mkdirs();
                File[] builtFiles = releaseDir.listFiles(f -> f.isFile() && (f.getName().endsWith(".a") || f.getName().endsWith(".json")));
                if (builtFiles == null || builtFiles.length == 0) {
                    throw new GradleException("No compiled release wrapper archives found in " + releaseDir);
                }
                for (File built : builtFiles) {
                    try {
                        Files.copy(built.toPath(), new File(outDir, built.getName()).toPath(), StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) {
                        throw new GradleException("Failed to stage native wrapper artifact " + built, e);
                    }
                }
            });
        });
    }

    private static TaskProvider<ExecTask> registerNativeWrapperVariantTask(
        Project project,
        String ffiName,
        String ffiWrapperBase,
        String konanTargetName,
        String targetCapitalized,
        File konanDataDir,
        boolean isDebug
    ) {
        Project rootProject = project.getRootProject();
        String variantCap = isDebug ? "Debug" : "Release";
        String variantLower = isDebug ? "debug" : "release";
        String wrapperTaskName = "compile" + ffiName + "Wrapper" + variantCap + "For" + targetCapitalized;
        Object minSdkProp = project.findProperty("android.minSdk");
        String androidMinSdk = minSdkProp != null ? minSdkProp.toString().trim() : "24";
        return project.getTasks().register(wrapperTaskName, ExecTask.class, task -> {
            task.setGroup("build");
            task.setDescription("Compiles " + variantLower + " native C/C++ wrapper sources into static libraries for " + konanTargetName);
            File cinteropDir = project.file("src/native/interop/cinterop");
            File outDir = project.getLayout().getBuildDirectory().dir("cinterop-targets/" + konanTargetName + "/" + variantLower).get().getAsFile();
            File outFile = new File(outDir, ffiWrapperBase + ".a");
            task.getInputs().files(project.fileTree(cinteropDir, spec -> spec.include("*.c", "*.cpp", "*.S", "*.s", "*.h", "*.hpp")));
            if (konanTargetName.startsWith("android")) {
                task.getInputs().property("androidMinSdk", androidMinSdk);
                task.getInputs().property("androidArm64BranchProtection", konanTargetName.contains("arm64") ? "standard" : "none");
            }
            if (konanTargetName.startsWith("linux")) {
                task.getInputs().dir(rootProject.file("buildSrc/src/main/java"));
                task.getOutputs().file(new File(outDir, "native-archive-verification.json"));
            }
            task.getOutputs().dir(outDir);

            task.doLast(t -> {
                outDir.mkdirs();
                if (outFile.exists()) {
                    outFile.delete();
                }
                if (konanTargetName.startsWith("linux")) {
                    new File(outDir, "native-archive-verification.json").delete();
                }
                boolean isArm64Target = konanTargetName.contains("arm64");
                boolean isX64Target = konanTargetName.contains("x64") || konanTargetName.contains("x86_64");
                List<File> asmSources = collectMatchingAsmSources(cinteropDir, isArm64Target, isX64Target);
                List<File> wrapperSources = new ArrayList<>(
                    project.fileTree(cinteropDir, spec -> spec.include("*_wrapper.c", "*_wrapper.cpp")).getFiles()
                );
                wrapperSources.sort((a, b) -> a.getName().compareTo(b.getName()));
                if (wrapperSources.isEmpty()) {
                    File singleC = new File(cinteropDir, ffiWrapperBase + ".c");
                    File singleCpp = new File(cinteropDir, ffiWrapperBase + ".cpp");
                    if (singleC.isFile()) {
                        wrapperSources.add(singleC);
                    } else if (singleCpp.isFile()) {
                        wrapperSources.add(singleCpp);
                    }
                }
                if (wrapperSources.isEmpty() && asmSources.isEmpty()) {
                    throw new GradleException("No native wrapper C/C++/ASM sources found in " + cinteropDir);
                }
                File konanDeps = project.getLayout().getBuildDirectory().dir("native-wrapper-dependencies").get().getAsFile();
                String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
                boolean isMac = os.contains("mac");
                boolean isLinux = os.contains("linux");
                boolean isWindows = os.contains("windows");
                ExecOperations execOps = task.getExecOperations();
                List<File> allObjFiles = new ArrayList<>();
                List<File> asmObjFiles = new ArrayList<>();

                for (int i = 0; i < asmSources.size(); i++) {
                    File asmFile = asmSources.get(i);
                    String asmName = asmFile.getName();
                    int dotIdx = asmName.lastIndexOf('.');
                    String asmStem = dotIdx > 0 ? asmName.substring(0, dotIdx) : asmName;
                    File asmObj = new File(outDir, "asm_" + i + "_" + asmStem + ".o");
                    if (compileSingleNativeObject(
                        project, konanTargetName, konanDataDir, konanDeps, androidMinSdk,
                        isDebug, isMac, isLinux, isWindows, execOps, asmFile, asmObj
                    ) && asmObj.isFile()) {
                        asmObjFiles.add(asmObj);
                    }
                }

                for (File srcFile : wrapperSources) {
                    String srcName = srcFile.getName();
                    int dotIdx = srcName.lastIndexOf('.');
                    String stemBase = dotIdx > 0 ? srcName.substring(0, dotIdx) : srcName;
                    File tempObj = new File(outDir, stemBase + ".o");
                    File stemArchive = new File(outDir, stemBase + ".a");
                    if (stemArchive.exists()) {
                        stemArchive.delete();
                    }

                    boolean compiledLocally = compileSingleNativeObject(
                        project, konanTargetName, konanDataDir, konanDeps, androidMinSdk,
                        isDebug, isMac, isLinux, isWindows, execOps, srcFile, tempObj
                    );
                    if (compiledLocally) {
                        List<File> archiveObjs = new ArrayList<>();
                        archiveObjs.add(tempObj);
                        if (stemBase.equals(ffiWrapperBase) || wrapperSources.size() == 1) {
                            archiveObjs.addAll(asmObjFiles);
                        }
                        archiveNativeObjects(
                            project, konanTargetName, konanDataDir, konanDeps,
                            isMac, isLinux, isWindows, execOps, stemArchive, archiveObjs
                        );
                    } else {
                        copyPrebuilt(
                            project,
                            konanTargetName.startsWith("mingw") ? "mingw_x64" : konanTargetName,
                            stemBase,
                            stemArchive
                        );
                    }

                    if (!stemArchive.exists() || stemArchive.length() == 0L) {
                        throw new GradleException(
                            "compile" + ffiName + "Wrapper" + variantCap + " failed: output library does not exist for " + konanTargetName + " at " + stemArchive
                        );
                    }
                    if (tempObj.exists()) {
                        allObjFiles.add(tempObj);
                    }
                }

                if (!outFile.exists()) {
                    if (wrapperSources.isEmpty() && !asmObjFiles.isEmpty()) {
                        archiveNativeObjects(
                            project, konanTargetName, konanDataDir, konanDeps,
                            isMac, isLinux, isWindows, execOps, outFile, asmObjFiles
                        );
                    } else if (!wrapperSources.isEmpty()) {
                        if (wrapperSources.size() > 1 && !asmObjFiles.isEmpty() && !allObjFiles.isEmpty()) {
                            List<File> combinedObjs = new ArrayList<>();
                            combinedObjs.add(allObjFiles.get(0));
                            combinedObjs.addAll(asmObjFiles);
                            archiveNativeObjects(
                                project, konanTargetName, konanDataDir, konanDeps,
                                isMac, isLinux, isWindows, execOps, outFile, combinedObjs
                            );
                        } else {
                            File firstArchive = new File(outDir, wrapperSources.get(0).getName().replaceFirst("\\.[^.]+$", ".a"));
                            if (firstArchive.exists()) {
                                try {
                                    Files.copy(firstArchive.toPath(), outFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                                } catch (IOException e) {
                                    throw new GradleException("Failed to create primary wrapper archive " + outFile, e);
                                }
                            }
                        }
                    }
                }
                for (File obj : allObjFiles) {
                    obj.delete();
                }
                for (File obj : asmObjFiles) {
                    obj.delete();
                }
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

        TaskProvider<ExecTask> compileFfiJniDebug = registerJniVariantTask(
            project, ffiName, ffiNameLower, isWindowsHost, isMacHost, true
        );
        TaskProvider<ExecTask> compileFfiJniRelease = registerJniVariantTask(
            project, ffiName, ffiNameLower, isWindowsHost, isMacHost, false
        );

        TaskProvider<ExecTask> compileFfiJni = project.getTasks().register("compile" + ffiName + "Jni", ExecTask.class, task -> {
            task.setGroup("build");
            task.setDescription("Stages compiled release JNI shared library for JVM JNI binding");
            task.dependsOn(compileFfiJniDebug, compileFfiJniRelease);
            File releaseDir = project.getLayout().getBuildDirectory().dir("natives/release").get().getAsFile();
            File outDir = project.getLayout().getBuildDirectory().dir("natives").get().getAsFile();
            task.getInputs().dir(releaseDir);
            task.getOutputs().dir(outDir);
            task.doLast(t -> {
                outDir.mkdirs();
                String libName = isWindowsHost
                    ? ffiNameLower + "_jni.dll"
                    : (isMacHost ? "lib" + ffiNameLower + "_jni.dylib" : "lib" + ffiNameLower + "_jni.so");
                File srcLib = new File(releaseDir, libName);
                File dstLib = new File(outDir, libName);
                if (!srcLib.isFile() || srcLib.length() == 0L) {
                    throw new GradleException("Missing compiled release JNI artifact: " + srcLib);
                }
                try {
                    Files.copy(srcLib.toPath(), dstLib.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    throw new GradleException("Failed to stage release JNI artifact to " + dstLib, e);
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

    private static TaskProvider<ExecTask> registerJniVariantTask(
        Project project,
        String ffiName,
        String ffiNameLower,
        boolean isWindowsHost,
        boolean isMacHost,
        boolean isDebug
    ) {
        String variantCap = isDebug ? "Debug" : "Release";
        String variantLower = isDebug ? "debug" : "release";
        String jvmMainCDir = IdeSourceSetSupport.sourceSetDir("jvmMain") + "/c/";
        return project.getTasks().register("compile" + ffiName + "Jni" + variantCap, ExecTask.class, task -> {
            task.setGroup("build");
            task.setDescription("Compiles " + variantLower + " JNI C sources into a shared library for JVM JNI binding");
            File cDir = project.file(jvmMainCDir);
            File cinteropDir = project.file("src/native/interop/cinterop");
            File outDir = project.getLayout().getBuildDirectory().dir("natives/" + variantLower).get().getAsFile();
            task.getInputs().files(project.fileTree(jvmMainCDir, spec -> spec.include("*.c", "*.S", "*.s", "*.h")));
            task.getInputs().files(project.fileTree("src/native/interop/cinterop", spec -> spec.include("*.h", "*.S", "*.s")));
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
                File[] cSources = cDir.listFiles((d, name) -> name.endsWith(".c"));
                if (cSources == null || cSources.length == 0) {
                    throw new GradleException("No JNI C sources found in " + cDir);
                }
                java.util.Arrays.sort(cSources, java.util.Comparator.comparing(File::getName));
                String archRaw = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
                boolean hostIsArm64 = "aarch64".equals(archRaw) || "arm64".equals(archRaw);
                boolean hostIsX64 = "amd64".equals(archRaw) || "x86_64".equals(archRaw);
                List<File> asmSources = new ArrayList<>();
                asmSources.addAll(collectMatchingAsmSources(cDir, hostIsArm64, hostIsX64));
                asmSources.addAll(collectMatchingAsmSources(cinteropDir, hostIsArm64, hostIsX64));
                List<File> asmObjs = new ArrayList<>();
                try {
                    for (int i = 0; i < asmSources.size(); i++) {
                        File asmSrc = asmSources.get(i);
                        File asmObj = new File(outDir, "jni_asm_" + i + ".o");
                        List<String> asmCmd = new ArrayList<>();
                        asmCmd.add("clang");
                        if (!isWindowsHost) {
                            asmCmd.add("-fPIC");
                        }
                        if (isDebug) {
                            asmCmd.add("-g");
                        }
                        asmCmd.add("-I" + cDir.getAbsolutePath());
                        if (cinteropDir.isDirectory()) {
                            asmCmd.add("-I" + cinteropDir.getAbsolutePath());
                        }
                        asmCmd.addAll(List.of("-c", asmSrc.getAbsolutePath(), "-o", asmObj.getAbsolutePath()));
                        task.getExecOperations().exec(s -> s.commandLine(asmCmd)).assertNormalExitValue();
                        asmObjs.add(asmObj);
                    }
                    List<String> cmd = new ArrayList<>();
                    cmd.add("clang");
                    cmd.add("-shared");
                    if (!isWindowsHost) {
                        cmd.add("-fPIC");
                        cmd.add("-fvisibility=hidden");
                    }
                    if (isDebug) {
                        cmd.add("-O0");
                        cmd.add("-g");
                    } else {
                        cmd.add("-O2");
                        cmd.add("-DNDEBUG");
                        if (!isWindowsHost) {
                            cmd.add(isMacHost ? "-Wl,-x" : "-Wl,--strip-debug");
                        }
                    }
                    cmd.add("-I" + javaInclude.getAbsolutePath());
                    cmd.add("-I" + javaOsInclude.getAbsolutePath());
                    cmd.add("-I" + cDir.getAbsolutePath());
                    if (cinteropDir.isDirectory()) {
                        cmd.add("-I" + cinteropDir.getAbsolutePath());
                    }
                    for (File cSrc : cSources) {
                        cmd.add(cSrc.getAbsolutePath());
                    }
                    for (File asmObj : asmObjs) {
                        cmd.add(asmObj.getAbsolutePath());
                    }
                    cmd.add("-o");
                    cmd.add(outFile.getAbsolutePath());
                    if (isWindowsHost) {
                        cmd.add("-lws2_32");
                    }
                    task.getExecOperations().exec(s -> s.commandLine(cmd)).assertNormalExitValue();
                } finally {
                    for (File asmObj : asmObjs) {
                        asmObj.delete();
                    }
                }
                if (!outFile.isFile() || outFile.length() == 0L) {
                    throw new GradleException("JNI compiler produced no artifact: " + outFile);
                }
            });
        });
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

        TaskProvider<AndroidJniCompileTask> debugTask = registerAndroidJniVariantTask(
            project, ffiName, ffiNameLower, nativeFfiEnabled, nativeFfiCompileWrapper, konanDataDir, isWindowsHost, isMacHost, true
        );
        TaskProvider<AndroidJniCompileTask> releaseTask = registerAndroidJniVariantTask(
            project, ffiName, ffiNameLower, nativeFfiEnabled, nativeFfiCompileWrapper, konanDataDir, isWindowsHost, isMacHost, false
        );

        return project.getTasks().register("compileAndroidJni", AndroidJniCompileTask.class, task -> {
            task.setGroup("build");
            task.setDescription("Stages compiled release Android JNI shared libraries");
            task.dependsOn(debugTask, releaseTask);
            task.getInputs().dir(releaseTask.flatMap(AndroidJniCompileTask::getNativeDirectory));
            task.getNativeDirectory().set(project.getLayout().getBuildDirectory().dir("android-jni"));
            task.doLast(t -> {
                File releaseRoot = releaseTask.get().getNativeDirectory().get().getAsFile();
                File outRoot = task.getNativeDirectory().get().getAsFile();
                for (String abi : List.of("arm64-v8a", "x86_64")) {
                    File src = new File(releaseRoot, abi + "/lib" + ffiNameLower + "_jni.so");
                    File dst = new File(outRoot, abi + "/lib" + ffiNameLower + "_jni.so");
                    if (!src.isFile() || src.length() == 0L) {
                        throw new GradleException("Missing compiled release Android JNI library: " + src);
                    }
                    dst.getParentFile().mkdirs();
                    try {
                        Files.copy(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) {
                        throw new GradleException("Failed to stage Android JNI library to " + dst, e);
                    }
                }
            });
        });
    }

    private static TaskProvider<AndroidJniCompileTask> registerAndroidJniVariantTask(
        Project project,
        String ffiName,
        String ffiNameLower,
        boolean nativeFfiEnabled,
        boolean nativeFfiCompileWrapper,
        File konanDataDir,
        boolean isWindowsHost,
        boolean isMacHost,
        boolean isDebug
    ) {
        String variantCap = isDebug ? "Debug" : "Release";
        String variantLower = isDebug ? "debug" : "release";
        String jvmMainCDir = IdeSourceSetSupport.sourceSetDir("jvmMain") + "/c/";
        Object minSdkProp = project.findProperty("android.minSdk");
        String configuredMinSdk = minSdkProp != null ? minSdkProp.toString().trim() : "24";
        return project.getTasks().register("compileAndroidJni" + variantCap, AndroidJniCompileTask.class, task -> {
            task.setGroup("build");
            task.setDescription("Compiles " + variantLower + " Android JNI C sources into shared libraries");
            if (nativeFfiEnabled && nativeFfiCompileWrapper) {
                task.dependsOn(
                    "compile" + ffiName + "Wrapper" + variantCap + "ForAndroidNativeArm64",
                    "compile" + ffiName + "Wrapper" + variantCap + "ForAndroidNativeX64"
                );
            }
            task.getInputs().property("androidMinSdk", configuredMinSdk);
            task.getInputs().property("androidArm64BranchProtection", "standard");
            task.getInputs().files(project.fileTree(jvmMainCDir, spec -> spec.include("*.c", "*.S", "*.s", "*.h")));
            task.getInputs().files(project.fileTree("src/native/interop/cinterop", spec -> spec.include("*.h", "*.S", "*.s")));
            task.getNativeDirectory().set(project.getLayout().getBuildDirectory().dir("android-jni-" + variantLower));
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
                File cDir = project.file(jvmMainCDir);
                File cinteropDir = project.file("src/native/interop/cinterop");
                File[] cSources = cDir.listFiles((d, name) -> name.endsWith(".c"));
                if (cSources == null || cSources.length == 0) {
                    throw new GradleException("No JNI C sources found in " + cDir);
                }
                java.util.Arrays.sort(cSources, java.util.Comparator.comparing(File::getName));
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
                    boolean abiIsArm64 = "arm64-v8a".equals(abi);
                    boolean abiIsX64 = "x86_64".equals(abi);
                    List<File> asmSources = new ArrayList<>();
                    asmSources.addAll(collectMatchingAsmSources(cDir, abiIsArm64, abiIsX64));
                    asmSources.addAll(collectMatchingAsmSources(cinteropDir, abiIsArm64, abiIsX64));
                    List<File> asmObjs = new ArrayList<>();
                    try {
                        for (int i = 0; i < asmSources.size(); i++) {
                            File asmSrc = asmSources.get(i);
                            File asmObj = new File(output.getParentFile(), "jni_asm_" + i + ".o");
                            List<String> asmCmd = new ArrayList<>();
                            asmCmd.add(compiler.getAbsolutePath());
                            asmCmd.add("--sysroot=" + new File(ndk, "sysroot").getAbsolutePath());
                            asmCmd.add("-fPIC");
                            if (abiIsArm64) {
                                asmCmd.add("-mbranch-protection=standard");
                            }
                            if (isDebug) {
                                asmCmd.add("-g");
                            }
                            asmCmd.add("-I" + cDir.getAbsolutePath());
                            if (cinteropDir.isDirectory()) {
                                asmCmd.add("-I" + cinteropDir.getAbsolutePath());
                            }
                            asmCmd.addAll(List.of("-c", asmSrc.getAbsolutePath(), "-o", asmObj.getAbsolutePath()));
                            task.getExecOperations().exec(s -> s.commandLine(asmCmd)).assertNormalExitValue();
                            asmObjs.add(asmObj);
                        }
                        List<String> cmd = new ArrayList<>();
                        cmd.add(compiler.getAbsolutePath());
                        cmd.add("--sysroot=" + new File(ndk, "sysroot").getAbsolutePath());
                        cmd.addAll(List.of("-shared", "-fPIC", "-fvisibility=hidden"));
                        if (abiIsArm64) {
                            cmd.add("-mbranch-protection=standard");
                        }
                        if (isDebug) {
                            cmd.addAll(List.of("-O0", "-g"));
                        } else {
                            cmd.addAll(List.of("-O2", "-DNDEBUG", "-Wl,--strip-debug"));
                        }
                        cmd.addAll(List.of("-Wall", "-Wextra", "-Werror", "-Wl,-z,max-page-size=16384"));
                        cmd.add("-I" + cDir.getAbsolutePath());
                        if (cinteropDir.isDirectory()) {
                            cmd.add("-I" + cinteropDir.getAbsolutePath());
                        }
                        for (File cSrc : cSources) {
                            cmd.add(cSrc.getAbsolutePath());
                        }
                        for (File asmObj : asmObjs) {
                            cmd.add(asmObj.getAbsolutePath());
                        }
                        cmd.addAll(List.of("-o", output.getAbsolutePath()));
                        task.getExecOperations().exec(s -> s.commandLine(cmd)).assertNormalExitValue();
                    } finally {
                        for (File asmObj : asmObjs) {
                            asmObj.delete();
                        }
                    }
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

        TaskProvider<BuildToolTask> buildNodeFfiDebug = project.getTasks().register(
            "buildNode" + ffiName + "Debug",
            BuildToolTask.class,
            task -> {
                task.setGroup("build");
                task.setDescription("Builds the debug native node-" + ffiNameLower + " N-API addon.");
                task.dependsOn(installNodeFfiHeaders);
                task.getToolClass().set(NodeFfiBuilder.class.getName());
                task.getArguments().addAll(
                    "--project-dir", rootProject.getProjectDir().getAbsolutePath(),
                    "--addon-dir", addonDirectory.getAbsolutePath(),
                    "--build-dir", project.getLayout().getBuildDirectory().get().getAsFile().getAbsolutePath(),
                    "--node-version", nodeVersion,
                    "--configuration", "Debug"
                );
                task.getArguments().addAll(nodeExecutable.map(it -> List.of("--node", it)));
                if (project.getGradle().getStartParameter().isOffline()) {
                    task.getArguments().add("--offline");
                }
            }
        );

        TaskProvider<BuildToolTask> buildNodeFfiRelease = project.getTasks().register(
            "buildNode" + ffiName + "Release",
            BuildToolTask.class,
            task -> {
                task.setGroup("build");
                task.setDescription("Builds the release native node-" + ffiNameLower + " N-API addon.");
                task.dependsOn(installNodeFfiHeaders);
                task.getToolClass().set(NodeFfiBuilder.class.getName());
                task.getArguments().addAll(
                    "--project-dir", rootProject.getProjectDir().getAbsolutePath(),
                    "--addon-dir", addonDirectory.getAbsolutePath(),
                    "--build-dir", project.getLayout().getBuildDirectory().get().getAsFile().getAbsolutePath(),
                    "--node-version", nodeVersion,
                    "--configuration", "Release"
                );
                task.getArguments().addAll(nodeExecutable.map(it -> List.of("--node", it)));
                if (project.getGradle().getStartParameter().isOffline()) {
                    task.getArguments().add("--offline");
                }
            }
        );

        TaskProvider<DefaultTask> buildNodeFfi = project.getTasks().register(
            "buildNode" + ffiName,
            DefaultTask.class,
            task -> {
                task.setGroup("build");
                task.setDescription("Builds the debug and release native node-" + ffiNameLower + " N-API addons.");
                task.dependsOn(buildNodeFfiDebug, buildNodeFfiRelease);
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
            .matching(t -> t.getName().startsWith("wasmJsNodeTest") || t.getName().startsWith("wasmWasiNodeTest"))
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
                    .matching(t -> t.getName().startsWith("jsNodeTest")
                        || t.getName().startsWith("wasmJsNodeTest")
                        || t.getName().startsWith("wasmWasiNodeTest"))
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

    private static boolean matchesTargetAssembly(String fileName, boolean isArm64, boolean isX64) {
        if (!fileName.endsWith(".S") && !fileName.endsWith(".s")) {
            return false;
        }
        String stem = fileName.substring(0, fileName.length() - 2).toLowerCase(Locale.ROOT);
        if (stem.endsWith("_aarch64") || stem.endsWith("_arm64")) {
            return isArm64;
        }
        if (stem.endsWith("_x86_64") || stem.endsWith("_amd64") || stem.endsWith("_x64")) {
            return isX64;
        }
        return true;
    }

    private static List<File> collectMatchingAsmSources(File dir, boolean isArm64, boolean isX64) {
        if (!dir.isDirectory()) {
            return List.of();
        }
        File[] files = dir.listFiles((d, name) -> matchesTargetAssembly(name, isArm64, isX64));
        if (files == null || files.length == 0) {
            return List.of();
        }
        List<File> result = new ArrayList<>(List.of(files));
        result.sort((a, b) -> a.getName().compareTo(b.getName()));
        return result;
    }

    private static boolean compileSingleNativeObject(
        Project project,
        String konanTargetName,
        File konanDataDir,
        File konanDeps,
        String androidMinSdk,
        boolean isDebug,
        boolean isMac,
        boolean isLinux,
        boolean isWindows,
        ExecOperations execOps,
        File srcFile,
        File tempObj
    ) {
        String srcName = srcFile.getName();
        boolean isCpp = srcName.endsWith(".cpp") || srcName.endsWith(".cxx") || srcName.endsWith(".cc");
        boolean isAsm = srcName.endsWith(".S") || srcName.endsWith(".s");
        String optFlag = isDebug ? "-O0" : "-O2";
        String defFlag = isDebug ? "-g" : "-DNDEBUG";

        if (isMac && (konanTargetName.startsWith("macos") || konanTargetName.startsWith("ios")
            || konanTargetName.startsWith("tvos") || konanTargetName.startsWith("watchos"))) {
            String appleTriple;
            String sdkName;
            switch (konanTargetName) {
                case "macos_arm64" -> { appleTriple = "arm64-apple-macos14.0"; sdkName = "macosx"; }
                case "ios_arm64" -> { appleTriple = "arm64-apple-ios14.0"; sdkName = "iphoneos"; }
                case "ios_simulator_arm64" -> { appleTriple = "arm64-apple-ios14.0-simulator"; sdkName = "iphonesimulator"; }
                case "watchos_device_arm64" -> { appleTriple = "arm64-apple-watchos7.0"; sdkName = "watchos"; }
                case "watchos_simulator_arm64" -> { appleTriple = "arm64-apple-watchos7.0-simulator"; sdkName = "watchsimulator"; }
                case "tvos_arm64" -> { appleTriple = "arm64-apple-tvos14.0"; sdkName = "appletvos"; }
                case "tvos_simulator_arm64" -> { appleTriple = "arm64-apple-tvos14.0-simulator"; sdkName = "appletvsimulator"; }
                default -> { appleTriple = "arm64-apple-macos14.0"; sdkName = "macosx"; }
            }
            List<String> cmd = new ArrayList<>(List.of(
                "xcrun", "-sdk", sdkName, isCpp ? "clang++" : "clang", "-target", appleTriple
            ));
            if (isAsm) {
                if (isDebug) cmd.add("-g");
            } else {
                cmd.addAll(List.of(optFlag, defFlag, "-fvisibility=hidden", "-Wall", "-Wextra", "-Werror", "-Wno-unused-parameter"));
            }
            cmd.addAll(List.of("-c", "-I" + srcFile.getParent(), srcFile.getAbsolutePath(), "-o", tempObj.getAbsolutePath()));
            execOps.exec(s -> s.commandLine(cmd)).assertNormalExitValue();
            return true;
        } else if (konanTargetName.startsWith("android")) {
            String hostToolchain = isMac ? "osx" : "linux";
            File ndkDir = ensureKonanDependency(
                project, "target-toolchain-2-" + hostToolchain + "-android_ndk", konanDataDir, konanDeps, execOps
            );
            ensureKonanDependency(project, "target-sysroot-1-android_ndk", konanDataDir, konanDeps, execOps);
            File sysroot = new File(ndkDir, "sysroot");
            String clangSuffix = isCpp ? "clang++" : "clang";
            boolean isArm64 = konanTargetName.contains("arm64");
            File clangBin = isArm64
                ? new File(ndkDir, "bin/aarch64-linux-android" + androidMinSdk + "-" + clangSuffix)
                : new File(ndkDir, "bin/x86_64-linux-android" + androidMinSdk + "-" + clangSuffix);
            if (clangBin.exists() && sysroot.exists()) {
                List<String> cmd = new ArrayList<>(List.of(
                    clangBin.getAbsolutePath(), "--sysroot=" + sysroot.getAbsolutePath(), "-fPIC"
                ));
                if (isArm64) {
                    cmd.add("-mbranch-protection=standard");
                }
                if (isAsm) {
                    if (isDebug) cmd.add("-g");
                } else {
                    cmd.addAll(List.of(optFlag, defFlag, "-fvisibility=hidden", "-Wall", "-Wextra", "-Werror", "-Wno-unused-parameter"));
                }
                cmd.addAll(List.of("-c", "-I" + srcFile.getParent(), srcFile.getAbsolutePath(), "-o", tempObj.getAbsolutePath()));
                execOps.exec(s -> s.commandLine(cmd)).assertNormalExitValue();
                return true;
            }
            return false;
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
                List<String> cmd = new ArrayList<>(List.of(
                    isCpp ? "clang++" : "clang", "--target=" + targetTriple, "--sysroot=" + sysroot.getAbsolutePath(), "-fPIC"
                ));
                if (isAsm) {
                    if (isDebug) cmd.add("-g");
                } else {
                    cmd.addAll(List.of(optFlag, defFlag, "-fvisibility=hidden", "-Wall", "-Wextra", "-Werror", "-Wno-unused-parameter"));
                }
                cmd.addAll(List.of("-c", "-I" + srcFile.getParent(), srcFile.getAbsolutePath(), "-o", tempObj.getAbsolutePath()));
                execOps.exec(s -> s.commandLine(cmd)).assertNormalExitValue();
                return true;
            } else if (isMac) {
                File gccDirectory = ensureKonanDependency(project, gccDirName, konanDataDir, konanDeps, execOps);
                File ndkDirectory = ensureKonanDependency(
                    project, "target-toolchain-2-osx-android_ndk", konanDataDir, konanDeps, execOps
                );
                File sysroot = new File(gccDirectory, targetTriple + "/sysroot");
                File llvmAr = new File(ndkDirectory, "bin/llvm-ar");
                if (sysroot.exists() && llvmAr.exists()) {
                    List<String> cmd = new ArrayList<>(List.of(
                        isCpp ? "clang++" : "clang", "--target=" + targetTriple, "--sysroot=" + sysroot.getAbsolutePath(), "-fPIC"
                    ));
                    if (isAsm) {
                        if (isDebug) cmd.add("-g");
                    } else {
                        cmd.addAll(List.of(optFlag, defFlag, "-fvisibility=hidden", "-Wall", "-Wextra", "-Werror", "-Wno-unused-parameter"));
                    }
                    cmd.addAll(List.of("-c", "-I" + srcFile.getParent(), srcFile.getAbsolutePath(), "-o", tempObj.getAbsolutePath()));
                    execOps.exec(s -> s.commandLine(cmd)).assertNormalExitValue();
                    return true;
                }
            }
            return false;
        } else if (konanTargetName.startsWith("mingw")) {
            File mingwSysroot = ensureKonanDependency(project, "msys2-mingw-w64-x86_64-2", konanDataDir, konanDeps, execOps);
            if ((isWindows && mingwSysroot.exists()) || isMac) {
                if (isMac) {
                    File ndkDirectory = ensureKonanDependency(
                        project, "target-toolchain-2-osx-android_ndk", konanDataDir, konanDeps, execOps
                    );
                    File llvmAr = new File(ndkDirectory, "bin/llvm-ar");
                    if (!mingwSysroot.exists() || !llvmAr.exists()) {
                        return false;
                    }
                }
                List<String> cmd = new ArrayList<>(List.of(
                    isCpp ? "clang++" : "clang", "--target=x86_64-w64-mingw32", "--sysroot=" + mingwSysroot.getAbsolutePath()
                ));
                if (isAsm) {
                    if (isDebug) cmd.add("-g");
                } else {
                    cmd.addAll(List.of(optFlag, defFlag, "-Wall", "-Wextra", "-Werror", "-Wno-unused-parameter"));
                }
                cmd.addAll(List.of("-c", "-I" + srcFile.getParent(), srcFile.getAbsolutePath(), "-o", tempObj.getAbsolutePath()));
                execOps.exec(s -> s.commandLine(cmd)).assertNormalExitValue();
                return true;
            }
            return false;
        }
        return false;
    }

    private static void archiveNativeObjects(
        Project project,
        String konanTargetName,
        File konanDataDir,
        File konanDeps,
        boolean isMac,
        boolean isLinux,
        boolean isWindows,
        ExecOperations execOps,
        File archiveFile,
        List<File> objFiles
    ) {
        List<String> cmd = new ArrayList<>();
        if (isMac && (konanTargetName.startsWith("macos") || konanTargetName.startsWith("ios")
            || konanTargetName.startsWith("tvos") || konanTargetName.startsWith("watchos"))) {
            String sdkName = switch (konanTargetName) {
                case "ios_arm64" -> "iphoneos";
                case "ios_simulator_arm64" -> "iphonesimulator";
                case "watchos_device_arm64" -> "watchos";
                case "watchos_simulator_arm64" -> "watchsimulator";
                case "tvos_arm64" -> "appletvos";
                case "tvos_simulator_arm64" -> "appletvsimulator";
                default -> "macosx";
            };
            cmd.addAll(List.of("xcrun", "-sdk", sdkName, "ar", "rcs", archiveFile.getAbsolutePath()));
        } else if (konanTargetName.startsWith("android")) {
            String hostToolchain = isMac ? "osx" : "linux";
            File ndkDir = ensureKonanDependency(
                project, "target-toolchain-2-" + hostToolchain + "-android_ndk", konanDataDir, konanDeps, execOps
            );
            boolean isArm64 = konanTargetName.contains("arm64");
            File arBin = isArm64
                ? new File(ndkDir, "bin/aarch64-linux-android-ar")
                : new File(ndkDir, "bin/x86_64-linux-android-ar");
            if (!arBin.exists()) {
                arBin = new File(ndkDir, "bin/llvm-ar");
            }
            cmd.addAll(List.of(arBin.getAbsolutePath(), "rcs", archiveFile.getAbsolutePath()));
        } else if (konanTargetName.startsWith("linux")) {
            if (isLinux) {
                cmd.addAll(List.of("ar", "rcs", archiveFile.getAbsolutePath()));
            } else {
                File ndkDirectory = ensureKonanDependency(
                    project, "target-toolchain-2-osx-android_ndk", konanDataDir, konanDeps, execOps
                );
                cmd.addAll(List.of(new File(ndkDirectory, "bin/llvm-ar").getAbsolutePath(), "rcs", archiveFile.getAbsolutePath()));
            }
        } else if (konanTargetName.startsWith("mingw")) {
            if (isWindows) {
                File mingwSysroot = ensureKonanDependency(project, "msys2-mingw-w64-x86_64-2", konanDataDir, konanDeps, execOps);
                File arExe = new File(mingwSysroot, "bin/ar.exe");
                cmd.addAll(List.of(arExe.exists() ? arExe.getAbsolutePath() : "ar", "rcs", archiveFile.getAbsolutePath()));
            } else {
                File ndkDirectory = ensureKonanDependency(
                    project, "target-toolchain-2-osx-android_ndk", konanDataDir, konanDeps, execOps
                );
                cmd.addAll(List.of(new File(ndkDirectory, "bin/llvm-ar").getAbsolutePath(), "rcs", archiveFile.getAbsolutePath()));
            }
        } else {
            throw new GradleException("Unsupported native target for archive creation: " + konanTargetName);
        }
        for (File obj : objFiles) {
            cmd.add(obj.getAbsolutePath());
        }
        execOps.exec(s -> s.commandLine(cmd)).assertNormalExitValue();
    }
}
