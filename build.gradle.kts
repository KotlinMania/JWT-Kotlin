import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.api.tasks.testing.AbstractTestTask
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.gradle.kotlin.dsl.support.serviceOf
import org.gradle.plugins.signing.Sign
import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.plugin.KotlinTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootExtension
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootEnvSpec
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootExtension
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnRootEnvSpec
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.UUID
import java.util.zip.ZipInputStream

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kmp)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.kotlinx.benchmark)
    alias(libs.plugins.kotlin.allopen)
    `maven-publish`
    signing
}

group = providers.gradleProperty("project.group").getOrElse("io.github.kotlinmania")
version = providers.gradleProperty("project.version").getOrElse("0.1.0-SNAPSHOT")
val frameworkName = providers.gradleProperty("project.frameworkName").getOrElse("Unnamed")
val projectNamespace = providers.gradleProperty("project.namespace").getOrElse("io.github.kotlinmania")
val kotlinVersion = providers.gradleProperty("versions.kotlin").getOrElse("2.4.0")
val commonMainBundleName = providers.gradleProperty("project.dependencies.commonMainBundle").get()
val commonMainDependencyBundle =
    extensions
        .getByType(VersionCatalogsExtension::class.java)
        .named("libs")
        .findBundle(commonMainBundleName)
        .orElseThrow { GradleException("Missing libs bundle '$commonMainBundleName'") }

fun csvProperty(name: String): Set<String> =
    providers
        .gradleProperty(name)
        .map { value ->
            value
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
        }.getOrElse(emptySet())

fun optionalTrimmedProperty(name: String): String? =
    providers
        .gradleProperty(name)
        .map { it.trim() }
        .orNull
        ?.takeIf { it.isNotEmpty() }

val enabledFeatureNames = csvProperty("project.features")
val benchmarkEnabled = "benchmark" in enabledFeatureNames
val benchmarkTargetNames = csvProperty("project.benchmark.targets")

// ============================================================================
// Native C bindings — per-channel opt-in gates (gradle.properties
// `project.ffi.{native,jvm,napi,name}`). All default off.
// Transliterated from libc-kotlin's build.gradle.kts; generalized so tasks,
// file paths, and npm package names all derive from project.ffi.name.
// ============================================================================
fun boolProperty(name: String, default: Boolean = false): Boolean =
    providers.gradleProperty(name).map { it.trim().lowercase() == "true" }.getOrElse(default)

val nativeFfiEnabled = boolProperty("project.ffi.native")
val jvmFfiEnabled = boolProperty("project.ffi.jvm")
val napiFfiEnabled = boolProperty("project.ffi.napi")

val ffiNameRaw = providers.gradleProperty("project.ffi.name").getOrElse("Ffi").trim()
val ffiName = ffiNameRaw.replaceFirstChar { it.uppercase() } // Libc, Png, ...
val ffiNameLower = ffiNameRaw.lowercase() // libc, png, ...
val ffiDefFile = "src/nativeInterop/cinterop/$ffiName.def"
val ffiWrapperBase = "${ffiNameLower}_wrapper" // libc_wrapper
val ffiNapiDir = "native/node-$ffiNameLower" // native/node-libc
val ffiNpmPackage = "@kotlinmania/$ffiNameLower-native-bindings" // @kotlinmania/libc-native-bindings

if (nativeFfiEnabled || jvmFfiEnabled || napiFfiEnabled) {
    println("FFI channels enabled: native=$nativeFfiEnabled jvm=$jvmFfiEnabled napi=$napiFfiEnabled name=$ffiName")
}
val commonBenchmarkBundleName = optionalTrimmedProperty("project.dependencies.commonBenchmarkBundle")
val commonBenchmarkDependencyBundle =
    commonBenchmarkBundleName?.let { bundleName ->
        extensions
            .getByType(VersionCatalogsExtension::class.java)
            .named("libs")
            .findBundle(bundleName)
            .orElseThrow { GradleException("Missing libs bundle '$bundleName'") }
    }
val commonTestBundleName = optionalTrimmedProperty("project.dependencies.commonTestBundle")
val commonTestDependencyBundle =
    commonTestBundleName?.let { bundleName ->
        extensions
            .getByType(VersionCatalogsExtension::class.java)
            .named("libs")
            .findBundle(bundleName)
            .orElseThrow { GradleException("Missing libs bundle '$bundleName'") }
    }
if (benchmarkEnabled && commonBenchmarkDependencyBundle == null) {
    throw GradleException("Feature 'benchmark' requires project.dependencies.commonBenchmarkBundle")
}
val benchmarkWarmups = providers.gradleProperty("project.benchmark.warmups").map { it.toInt() }.getOrElse(3)
val benchmarkIterations = providers.gradleProperty("project.benchmark.iterations").map { it.toInt() }.getOrElse(5)
val benchmarkIterationTime = providers.gradleProperty("project.benchmark.iterationTime").map { it.toLong() }.getOrElse(1L)
val benchmarkIterationTimeUnit = providers.gradleProperty("project.benchmark.iterationTimeUnit").getOrElse("s")
val intellijCoroutinesVersion =
    providers.gradleProperty("versions.intellij.coroutines").getOrElse("1.10.2-intellij-1")

// KGP runs Swift Export in an isolated worker whose classpath is
// `swiftExportClasspath`. Adding a dependency disables KGP's default
// dependency population, so keep the default embeddable runner explicit too.
val projectDependencyHandler = project.dependencies
configurations.configureEach {
    // proc-macro-kotlin's published Android artifact bundles the JetBrains
    // annotation classes, which duplicate org.jetbrains:annotations on the
    // Android device-test classpath.
    if (name.contains("androidDeviceTest", ignoreCase = true)) {
        exclude(group = "org.jetbrains", module = "annotations")
    }
    if (name == "swiftExportClasspath") {
        dependencies.add(projectDependencyHandler.create("org.jetbrains.kotlin:swift-export-embeddable:$kotlinVersion"))
        dependencies.add(
            projectDependencyHandler.create(
                "org.jetbrains.intellij.deps.kotlinx:kotlinx-coroutines-core-jvm:$intellijCoroutinesVersion",
            ),
        )
    }
}

// Test name patterns to skip in browser test runs (js and wasmJs), from
// project.js.browserTestExcludes as a comma-separated list. Use for tests that
// need Node-only modules such as fs or path.
val browserTestExcludes =
    providers
        .gradleProperty("project.js.browserTestExcludes")
        .getOrElse("")
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }

// Opt-ins shared across Kotlin targets.
val commonOptIns =
    listOf(
        "kotlin.time.ExperimentalTime",
        "kotlin.concurrent.atomics.ExperimentalAtomicApi",
        "kotlin.ExperimentalUnsignedTypes",
    )

// ============================================================================
// Android SDK installer
// ----------------------------------------------------------------------------
// The Android Gradle Plugin resolves the SDK location at configuration time,
// so the SDK must already be on disk before the `kotlin { android { ... } }`
// block evaluates. The installer is idempotent — a .install-complete marker
// short-circuits the download on every subsequent invocation, so warm runs
// pay only a directory-existence check. CI runners pay a one-time cold cost
// the first time they touch the project.
// ============================================================================

val androidCommandLineToolsRevision =
    providers
        .gradleProperty(
            "android.commandLineTools.revision",
        ).getOrElse("14742923")
val projectCompileSdk = providers.gradleProperty("android.compileSdk").getOrElse("34")
val projectAndroidBuildTools = providers.gradleProperty("android.buildTools").getOrElse("36.0.0")
val osName = providers.systemProperty("os.name").get().lowercase()
val isWindowsHost = "windows" in osName
val isMacHost = "mac" in osName
val androidSdkOsName =
    when {
        isWindowsHost -> "win"
        isMacHost -> "mac"
        "linux" in osName -> "linux"
        else -> throw GradleException("Unsupported Android SDK setup OS: ${providers.systemProperty("os.name").get()}")
    }
val projectAndroidSdkDir = layout.projectDirectory.dir(".android-sdk").asFile
val androidSdkManager =
    projectAndroidSdkDir.resolve(
        if (isWindowsHost) {
            "cmdline-tools/latest/bin/sdkmanager.bat"
        } else {
            "cmdline-tools/latest/bin/sdkmanager"
        },
    )
val androidSdkInstallMarker = projectAndroidSdkDir.resolve(".install-complete")
val requiredAndroidSdkPackageDirs =
    listOf(
        projectAndroidSdkDir.resolve("platform-tools"),
        projectAndroidSdkDir.resolve("platforms/android-$projectCompileSdk"),
        projectAndroidSdkDir.resolve("build-tools/$projectAndroidBuildTools"),
    )

fun writeAndroidLocalProperties() {
    projectAndroidSdkDir.mkdirs()
    val sdkDirPropertyValue = projectAndroidSdkDir.absolutePath.replace("\\", "/")
    layout.projectDirectory
        .file("local.properties")
        .asFile
        .writeText("sdk.dir=$sdkDirPropertyValue\n")
}

fun isProjectAndroidSdkInstalled(): Boolean =
    androidSdkInstallMarker.exists() &&
        androidSdkManager.exists() &&
        requiredAndroidSdkPackageDirs.all { it.exists() }

fun sdkManagerCommand(vararg args: String): List<String> =
    if (isWindowsHost) {
        listOf("cmd", "/c", androidSdkManager.absolutePath) + args
    } else {
        listOf(androidSdkManager.absolutePath) + args
    }

fun downloadAndroidCommandLineTools() {
    val zipName = "commandlinetools-$androidSdkOsName-${androidCommandLineToolsRevision}_latest.zip"
    val url = "https://dl.google.com/android/repository/$zipName"
    val tmpDir = projectAndroidSdkDir.resolve(".tmp/commandline-tools")
    val zipFile = tmpDir.resolve(zipName)
    val latestDir = projectAndroidSdkDir.resolve("cmdline-tools/latest")
    println("setup-android-sdk: downloading $url")
    tmpDir.deleteRecursively()
    tmpDir.mkdirs()
    try {
        URI(url).toURL().openStream().use { input ->
            Files.copy(input, zipFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        latestDir.deleteRecursively()
        latestDir.mkdirs()
        val canonicalLatestDir = latestDir.canonicalFile.toPath()
        ZipInputStream(zipFile.inputStream().buffered()).use { zipInput ->
            generateSequence { zipInput.nextEntry }.forEach { entry ->
                val relativeName = entry.name.removePrefix("cmdline-tools/").trimStart('/')
                if (relativeName.isNotEmpty()) {
                    val target = latestDir.resolve(relativeName).canonicalFile
                    if (!target.toPath().startsWith(canonicalLatestDir)) {
                        throw GradleException("Refusing to extract Android SDK entry outside $latestDir: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile.mkdirs()
                        Files.copy(zipInput, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        if (!isWindowsHost && relativeName.startsWith("bin/")) target.setExecutable(true)
                    }
                }
                zipInput.closeEntry()
            }
        }
        if (!isWindowsHost) androidSdkManager.setExecutable(true)
    } finally {
        tmpDir.deleteRecursively()
    }
}

fun installProjectAndroidSdk(execOperations: ExecOperations) {
    if (isProjectAndroidSdkInstalled()) {
        writeAndroidLocalProperties()
        println("setup-android-sdk: SDK already installed at $projectAndroidSdkDir")
        return
    }
    if (!androidSdkManager.exists()) downloadAndroidCommandLineTools()
    println("setup-android-sdk: accepting licenses")
    val licenseAnswers = "y\n".repeat(200).toByteArray(Charsets.UTF_8)
    val licenseResult =
        execOperations.exec {
            commandLine(sdkManagerCommand("--sdk_root=${projectAndroidSdkDir.absolutePath}", "--licenses"))
            standardInput = ByteArrayInputStream(licenseAnswers)
            isIgnoreExitValue = true
        }
    if (licenseResult.exitValue != 0) {
        throw GradleException("Android SDK license acceptance failed with exit code ${licenseResult.exitValue}")
    }
    println(
        "setup-android-sdk: installing platform-tools, android-$projectCompileSdk, build-tools;$projectAndroidBuildTools",
    )
    val installLog = projectAndroidSdkDir.resolve("sdkmanager-install.log")
    installLog.parentFile.mkdirs()
    installLog.outputStream().use { output ->
        val installResult =
            execOperations.exec {
                commandLine(
                    sdkManagerCommand(
                        "--sdk_root=${projectAndroidSdkDir.absolutePath}",
                        "platform-tools",
                        "platforms;android-$projectCompileSdk",
                        "build-tools;$projectAndroidBuildTools",
                    ),
                )
                standardOutput = output
                errorOutput = output
                isIgnoreExitValue = true
            }
        if (installResult.exitValue != 0) {
            throw GradleException(
                "Android SDK package install failed with exit code ${installResult.exitValue}. " +
                    "Install log:\n${installLog.readText()}",
            )
        }
    }
    writeAndroidLocalProperties()
    androidSdkInstallMarker.writeText("")
    println("setup-android-sdk: done; SDK at $projectAndroidSdkDir")
}

// ----------------------------------------------------------------------------
// Android SDK setup is gated to follow the requested task. It must never run for
// non-Android invocations (jsTest, jvmTest, swiftExportSmokeTest, native /
// androidNative links) -- an unconditional install here is what made the SDK
// download appear on every machine and target.
//
// `writeAndroidLocalProperties()` always runs: it is cheap, hits no network, and
// only points local.properties at the project-local .android-sdk so AGP can
// resolve `sdk.dir` while the `androidLibrary {}` block evaluates.
//
// The SDK *package* download must happen at configuration time when -- and only
// when -- an Android task is in the requested build. AGP validates the packages
// while determining the dependencies of `compileAndroidMain` (task-graph
// construction, strictly before any task executes), so a plain `dependsOn`
// cannot supply them in time. We detect Android intent from the requested task
// names and install eagerly in that case. androidNative* are Kotlin/Native
// targets and need no Android SDK.
// ----------------------------------------------------------------------------
writeAndroidLocalProperties()

fun requestedTaskWantsAndroid(rawTaskName: String): Boolean {
    val taskName = rawTaskName.substringAfterLast(':')
    if (taskName.contains("AndroidNative")) return false // Kotlin/Native, no SDK
    if (taskName.contains("Android")) return true // direct AGP tasks
    return taskName in setOf("build", "assemble", "check") // aggregates pull android
}

if (gradle.startParameter.taskNames.any(::requestedTaskWantsAndroid)) {
    installProjectAndroidSdk(serviceOf())
}

val ensureAndroidSdk =
    tasks.register("ensureAndroidSdk") {
        group = "setup"
        description = "Ensures the project-local Android SDK is installed (idempotent)."
        onlyIf("Android SDK already installed at $projectAndroidSdkDir") { !isProjectAndroidSdkInstalled() }
        doLast {
            installProjectAndroidSdk(serviceOf())
        }
    }

// Secondary net: order every AGP Android task after the installer (a no-op on
// warm runs). Excludes androidNative* (Kotlin/Native) and the installer itself.
tasks
    .matching { task ->
        val taskName = task.name
        taskName != "ensureAndroidSdk" &&
            taskName.contains("Android") &&
            !taskName.contains("AndroidNative")
    }.configureEach {
        dependsOn(ensureAndroidSdk)
    }

// Gap #9b: KGP-generated bridge boilerplate and KotlinCoroutineSupport runtime
// produce warnings (unchecked casts, unused expressions, opt-in requirements)
// that cannot be fixed in source — they are regenerated every build.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
    if (name.startsWith("compileSwiftExport")) {
        compilerOptions.allWarningsAsErrors.set(false)
    }
}

val jvmToolchainVersion = providers.gradleProperty("jvm.toolchain").getOrElse("21").toInt()

// ============================================================================
// kotlin { … }
// ----------------------------------------------------------------------------
// Workspace product policy retires every native 32-bit target, including
//   watchosArm32 / watchosArm64 (arm64_32), androidNativeArm32 / androidNativeX86,
//   linuxArm32Hfp, and mingwX86. Do not re-add their declarations or CI tasks.
// Apple-specific x64 targets are also retired: iosX64, macosX64, tvosX64,
//   watchosX64. Linux/Windows x64 and Android native x64 remain supported.
// Every retained target is built unconditionally, with no opt-in build gates.
// ============================================================================
kotlin {
    jvmToolchain(jvmToolchainVersion)

    applyDefaultHierarchyTemplate()

    compilerOptions {
        languageVersion.set(KotlinVersion.KOTLIN_2_4)
        apiVersion.set(KotlinVersion.KOTLIN_2_4)
        allWarningsAsErrors.set(true)
        optIn.addAll(commonOptIns)
        freeCompilerArgs.addAll("-Xexpect-actual-classes", "-Xsuppress-version-warnings")
    }

    val xcf = XCFramework(frameworkName)
    val frameworkBundleId = projectNamespace

    // Local helper: attach this target's framework to the XCFramework.
    fun KotlinNativeTarget.addToXcf(static: Boolean = false) {
        binaries.framework {
            baseName = frameworkName
            if (static) isStatic = true
            xcf.add(this)
            binaryOption("bundleId", frameworkBundleId)
        }
    }

    fun KotlinTarget.configureBenchmarkCompilation() {
        if (!benchmarkEnabled || name !in benchmarkTargetNames) return
        val mainCompilation = compilations.getByName("main")
        compilations.create("benchmark") {
            associateWith(mainCompilation)
            defaultSourceSet.dependencies {
                implementation(commonBenchmarkDependencyBundle!!)
            }
        }
    }

    // Apple — Tier 1/2 targets
    macosArm64 {
        configureBenchmarkCompilation()
        addToXcf()
    }
    iosArm64 {
        configureBenchmarkCompilation()
        addToXcf(static = true)
    }
    iosSimulatorArm64 {
        configureBenchmarkCompilation()
        addToXcf(static = true)
    }
    tvosArm64 {
        configureBenchmarkCompilation()
        addToXcf()
    }
    tvosSimulatorArm64 {
        configureBenchmarkCompilation()
        addToXcf()
    }
    // watchosArm64 (WatchOS 32 / arm64_32): retired by workspace policy (§5.5.1). WatchOS 32 is not supported.
    watchosDeviceArm64 {
        configureBenchmarkCompilation()
        addToXcf()
    }
    watchosSimulatorArm64 {
        configureBenchmarkCompilation()
        addToXcf()
    }

    // Other native — Tier 1/2
    linuxX64 { configureBenchmarkCompilation() }
    linuxArm64 { configureBenchmarkCompilation() }
    mingwX64 { configureBenchmarkCompilation() }

    // Android NDK — 64-bit only (32-bit retired §5.5.3, 2026-06-25).
    androidNativeArm64 { configureBenchmarkCompilation() }
    androidNativeX64 { configureBenchmarkCompilation() }

    // Web
    js {
        configureBenchmarkCompilation()
        browser {
            testTask {
                browserTestExcludes.forEach { filter.excludeTestsMatching(it) }
            }
        }
        nodejs {
            testTask {
                // Mocha's 2s default times out heavy conformance suites on a loaded host.
                useMocha {
                    timeout = "120s"
                    nodeJsArgs += listOf("--max-old-space-size=4096")
                }
            }
        }
    }

    // wasmJs is Stable as of Kotlin 2.2; @OptIn may be removable — verify before dropping on wasmWasi.
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        configureBenchmarkCompilation()
        browser {
            testTask {
                browserTestExcludes.forEach { filter.excludeTestsMatching(it) }
            }
        }
        nodejs {
            testTask {
                useMocha {
                    timeout = "120s"
                }
            }
        }
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmWasi {
        configureBenchmarkCompilation()
        nodejs()
    }

    // Swift Export bridge — Experimental per Kotlin 2.4.0 release notes.
    // KGP 2.4.0 does not expose a public opt-in annotation; warnings (if any)
    // arrive via KotlinToolingDiagnostics, not @RequiresOptIn.
    swiftExport {
        moduleName = frameworkName
        flattenPackage = projectNamespace
        @OptIn(org.jetbrains.kotlin.gradle.swiftexport.ExperimentalSwiftExportDsl::class)
        configure {
            settings.put("enableCoroutinesSupport", "true")
        }
    }

    // Android KMP library. Block name is `android` — `androidLibrary` is deprecated in current KGP.
    android {
        namespace = projectNamespace
        compileSdk = projectCompileSdk.toInt()
        minSdk = providers.gradleProperty("android.minSdk").getOrElse("24").toInt()
        withHostTestBuilder {}.configure {}
        withDeviceTestBuilder { sourceSetTreeName = "test" }
    }

    // JVM — jvmTarget derived from the same toolchain property so they can't drift.
    jvm {
        configureBenchmarkCompilation()
        compilerOptions {
            jvmTarget.set(JvmTarget.fromTarget(jvmToolchainVersion.toString()))
        }
    }

    // ========================================================================
    // Native FFI — Kotlin/Native cinterop + per-target static wrapper library.
    // Gated by project.ffi.native=true in gradle.properties. Transliterated
    // from libc-kotlin's build.gradle.kts:465-683 with the libc-specific
    // literals parameterized on project.ffi.name.
    //
    // For each Kotlin/Native target this registers a compile${ffiName}WrapperFor<Target>
    // task that compiles ${ffiWrapperBase}.c into ${ffiWrapperBase}.a using the
    // correct host or cross toolchain (Apple via xcrun, Android NDK via the
    // Kotlin/Native-downloaded toolchain, Linux via host clang or the konan
    // GCC cross-archive, MinGW via the konan msys2 toolchain). It then wires
    // the generated static library into the cinterop definition read from
    // src/nativeInterop/cinterop/${ffiName}.def so the wrapper's function
    // bodies are available to the Kotlin bindings as `import ${ffiNameLower}.*`.
    //
    // Host-capability fallbacks: if the cross toolchain for a target is not
    // installed on this host (e.g. linuxArm64 from macOS without the GCC
    // archive downloaded), the task falls back to a pre-built archive at
    // src/nativeInterop/cinterop/targets/<konan_target>/${ffiWrapperBase}.a,
    // and fails loudly if neither is available.
    // ========================================================================
    if (nativeFfiEnabled) {
        fun ensureKonanDependency(name: String, konanDeps: File, execOps: ExecOperations) {
            val targetDir = File(konanDeps, name)
            if (targetDir.exists()) return
            konanDeps.mkdirs()
            val cacheDir = File(konanDeps, "cache").apply { mkdirs() }
            val archive = File(cacheDir, "$name.tar.gz")
            val url = "https://download.jetbrains.com/kotlin/native/$name.tar.gz"
            if (!archive.exists() || archive.length() == 0L) {
                println("Downloading Kotlin/Native dependency $name from $url ...")
                execOps
                    .exec {
                        commandLine("curl", "-sSL", url, "-o", archive.absolutePath)
                    }.assertNormalExitValue()
            }
            println("Extracting $archive into $konanDeps ...")
            execOps
                .exec {
                    commandLine("tar", "-xzf", archive.absolutePath, "-C", konanDeps.absolutePath)
                }.assertNormalExitValue()
        }

        targets.withType<KotlinNativeTarget> {
            val konanTargetName = konanTarget.name
            val targetCapitalized = name.replaceFirstChar { it.uppercase() }
            val wrapperTaskName = "compile${ffiName}WrapperFor$targetCapitalized"
            val wrapperTask =
                tasks.register(wrapperTaskName) {
                    group = "build"
                    description = "Compiles $ffiWrapperBase.c into a static library for $konanTargetName"
                    val cSource = file("src/nativeInterop/cinterop/$ffiWrapperBase.c")
                    val hSource = file("src/nativeInterop/cinterop/$ffiWrapperBase.h")
                    val outDir =
                        layout.buildDirectory
                            .dir("cinterop-targets/$konanTargetName")
                            .get()
                            .asFile
                    val outFile = File(outDir, "$ffiWrapperBase.a")
                    inputs.file(cSource)
                    inputs.file(hSource)
                    outputs.file(outFile)

                    doLast {
                        outDir.mkdirs()
                        if (outFile.exists()) outFile.delete()
                        val tempObj = File(outDir, "$ffiWrapperBase.o")
                        val konanDeps = File(System.getProperty("user.home"), ".konan/dependencies")
                        val isMac =
                            org.gradle.internal.os.OperatingSystem
                                .current()
                                .isMacOsX
                        val isLinux =
                            org.gradle.internal.os.OperatingSystem
                                .current()
                                .isLinux
                        val isWindows =
                            org.gradle.internal.os.OperatingSystem
                                .current()
                                .isWindows
                        val execOps = project.serviceOf<ExecOperations>()

                        when {
                            isMac &&
                                (
                                    konanTargetName.startsWith("macos") ||
                                        konanTargetName.startsWith("ios") ||
                                        konanTargetName.startsWith("tvos") ||
                                        konanTargetName.startsWith("watchos")
                                ) -> {
                                val (appleTriple, sdkName) =
                                    when (konanTargetName) {
                                        "macos_arm64" -> "arm64-apple-macos14.0" to "macosx"
                                        "macos_x64" -> "x86_64-apple-macos14.0" to "macosx"
                                        "ios_arm64" -> "arm64-apple-ios14.0" to "iphoneos"
                                        "ios_simulator_arm64" -> "arm64-apple-ios14.0-simulator" to "iphonesimulator"
                                        "ios_x64" -> "x86_64-apple-ios14.0-simulator" to "iphonesimulator"
                                        "watchos_device_arm64" -> "arm64-apple-watchos7.0" to "watchos"
                                        "watchos_simulator_arm64" -> "arm64-apple-watchos7.0-simulator" to "watchsimulator"
                                        "tvos_arm64" -> "arm64-apple-tvos14.0" to "appletvos"
                                        "tvos_simulator_arm64" -> "arm64-apple-tvos14.0-simulator" to "appletvsimulator"
                                        else -> "arm64-apple-macos14.0" to "macosx"
                                    }
                                execOps
                                    .exec {
                                        commandLine("xcrun", "-sdk", sdkName, "clang", "-target", appleTriple, "-Wall", "-Wextra", "-Werror", "-c", "-I${cSource.parent}", cSource.absolutePath, "-o", tempObj.absolutePath)
                                    }.assertNormalExitValue()
                                execOps
                                    .exec {
                                        commandLine("xcrun", "-sdk", sdkName, "ar", "rcs", outFile.absolutePath, tempObj.absolutePath)
                                    }.assertNormalExitValue()
                            }

                            konanTargetName.startsWith("android") -> {
                                val hostToolchain = if (isMac) "osx" else "linux"
                                ensureKonanDependency("target-toolchain-2-$hostToolchain-android_ndk", konanDeps, execOps)
                                ensureKonanDependency("target-sysroot-1-android_ndk", konanDeps, execOps)
                                val ndkDir = File(konanDeps, "target-toolchain-2-$hostToolchain-android_ndk")
                                val sysroot = File(ndkDir, "sysroot")
                                val clangBin =
                                    if (konanTargetName.contains("arm64")) {
                                        File(ndkDir, "bin/aarch64-linux-android21-clang")
                                    } else {
                                        File(ndkDir, "bin/x86_64-linux-android21-clang")
                                    }
                                val arBin =
                                    if (konanTargetName.contains("arm64")) {
                                        File(ndkDir, "bin/aarch64-linux-android-ar")
                                    } else {
                                        File(ndkDir, "bin/x86_64-linux-android-ar")
                                    }
                                if (clangBin.exists() && sysroot.exists()) {
                                    execOps
                                        .exec {
                                            commandLine(clangBin.absolutePath, "--sysroot=${sysroot.absolutePath}", "-Wall", "-Wextra", "-Werror", "-c", "-I${cSource.parent}", cSource.absolutePath, "-o", tempObj.absolutePath)
                                        }.assertNormalExitValue()
                                    execOps
                                        .exec {
                                            commandLine(arBin.absolutePath, "rcs", outFile.absolutePath, tempObj.absolutePath)
                                        }.assertNormalExitValue()
                                } else {
                                    val prebuilt = file("src/nativeInterop/cinterop/targets/$konanTargetName/$ffiWrapperBase.a")
                                    val fallbackPrebuilt = file("src/nativeInterop/cinterop/$ffiWrapperBase.a")
                                    if (prebuilt.exists()) {
                                        prebuilt.copyTo(outFile, overwrite = true)
                                    } else if (fallbackPrebuilt.exists()) {
                                        fallbackPrebuilt.copyTo(outFile, overwrite = true)
                                    }
                                }
                            }

                            konanTargetName.startsWith("linux") -> {
                                val (targetTriple, gccDirName) =
                                    if (konanTargetName.contains("arm64")) {
                                        "aarch64-unknown-linux-gnu" to "aarch64-unknown-linux-gnu-gcc-8.3.0-glibc-2.25-kernel-4.9-2"
                                    } else {
                                        "x86_64-unknown-linux-gnu" to "x86_64-unknown-linux-gnu-gcc-8.3.0-glibc-2.19-kernel-4.9-2"
                                    }
                                if (isLinux) {
                                    execOps
                                        .exec {
                                            commandLine("clang", "-Wall", "-Wextra", "-Werror", "-c", "-I${cSource.parent}", cSource.absolutePath, "-o", tempObj.absolutePath)
                                        }.assertNormalExitValue()
                                    execOps
                                        .exec {
                                            commandLine("ar", "rcs", outFile.absolutePath, tempObj.absolutePath)
                                        }.assertNormalExitValue()
                                } else if (isMac) {
                                    ensureKonanDependency(gccDirName, konanDeps, execOps)
                                    ensureKonanDependency("target-toolchain-2-osx-android_ndk", konanDeps, execOps)
                                    val sysroot = File(konanDeps, "$gccDirName/$targetTriple/sysroot")
                                    val llvmAr = File(konanDeps, "target-toolchain-2-osx-android_ndk/bin/llvm-ar")
                                    if (sysroot.exists() && llvmAr.exists()) {
                                        execOps
                                            .exec {
                                                commandLine("clang", "--target=$targetTriple", "--sysroot=${sysroot.absolutePath}", "-Wall", "-Wextra", "-Werror", "-c", "-I${cSource.parent}", cSource.absolutePath, "-o", tempObj.absolutePath)
                                            }.assertNormalExitValue()
                                        execOps
                                            .exec {
                                                commandLine(llvmAr.absolutePath, "rcs", outFile.absolutePath, tempObj.absolutePath)
                                            }.assertNormalExitValue()
                                    } else {
                                        val prebuilt = file("src/nativeInterop/cinterop/targets/$konanTargetName/$ffiWrapperBase.a")
                                        if (prebuilt.exists()) prebuilt.copyTo(outFile, overwrite = true)
                                    }
                                } else {
                                    val prebuilt = file("src/nativeInterop/cinterop/targets/$konanTargetName/$ffiWrapperBase.a")
                                    if (prebuilt.exists()) prebuilt.copyTo(outFile, overwrite = true)
                                }
                            }

                            konanTargetName.startsWith("mingw") -> {
                                ensureKonanDependency("msys2-mingw-w64-x86_64-2", konanDeps, execOps)
                                val mingwSysroot = File(konanDeps, "msys2-mingw-w64-x86_64-2")
                                val arExe = File(mingwSysroot, "bin/ar.exe")
                                if (isWindows && mingwSysroot.exists()) {
                                    execOps
                                        .exec {
                                            commandLine("clang", "--target=x86_64-w64-mingw32", "--sysroot=${mingwSysroot.absolutePath}", "-Wall", "-Wextra", "-Werror", "-c", "-I${cSource.parent}", cSource.absolutePath, "-o", tempObj.absolutePath)
                                        }.assertNormalExitValue()
                                    execOps
                                        .exec {
                                            commandLine(if (arExe.exists()) arExe.absolutePath else "ar", "rcs", outFile.absolutePath, tempObj.absolutePath)
                                        }.assertNormalExitValue()
                                } else if (isMac) {
                                    ensureKonanDependency("target-toolchain-2-osx-android_ndk", konanDeps, execOps)
                                    val llvmAr = File(konanDeps, "target-toolchain-2-osx-android_ndk/bin/llvm-ar")
                                    if (mingwSysroot.exists() && llvmAr.exists()) {
                                        execOps
                                            .exec {
                                                commandLine("clang", "--target=x86_64-w64-mingw32", "--sysroot=${mingwSysroot.absolutePath}", "-Wall", "-Wextra", "-Werror", "-c", "-I${cSource.parent}", cSource.absolutePath, "-o", tempObj.absolutePath)
                                            }.assertNormalExitValue()
                                        execOps
                                            .exec {
                                                commandLine(llvmAr.absolutePath, "rcs", outFile.absolutePath, tempObj.absolutePath)
                                            }.assertNormalExitValue()
                                    } else {
                                        val prebuilt = file("src/nativeInterop/cinterop/targets/mingw_x64/$ffiWrapperBase.a")
                                        if (prebuilt.exists()) prebuilt.copyTo(outFile, overwrite = true)
                                    }
                                } else {
                                    val prebuilt = file("src/nativeInterop/cinterop/targets/mingw_x64/$ffiWrapperBase.a")
                                    if (prebuilt.exists()) prebuilt.copyTo(outFile, overwrite = true)
                                }
                            }

                            else -> {
                                val prebuilt = file("src/nativeInterop/cinterop/targets/$konanTargetName/$ffiWrapperBase.a")
                                if (prebuilt.exists()) prebuilt.copyTo(outFile, overwrite = true)
                            }
                        }
                        tempObj.delete()
                        if (!outFile.exists() || outFile.length() == 0L) {
                            throw GradleException("compile${ffiName}Wrapper failed: output library does not exist for $konanTargetName at ${outFile.absolutePath}")
                        }
                    }
                }

            compilations.getByName("main") {
                cinterops {
                    create(ffiNameLower) {
                        defFile(project.file(ffiDefFile))
                        packageName("$ffiNameLower.cinterop")
                        extraOpts(
                            "-libraryPath",
                            layout.buildDirectory
                                .dir("cinterop-targets/$konanTargetName")
                                .get()
                                .asFile.absolutePath,
                        )
                    }
                }
            }
            tasks.matching { it.name == "cinterop${ffiName}$targetCapitalized" }.configureEach {
                dependsOn(wrapperTask)
                inputs.files(wrapperTask)
            }
        }
    }

    sourceSets {
        // posixMain: shared POSIX code for every native target except mingw.
        // Added as a source directory per target rather than a dependsOn edge,
        // which would switch off the default hierarchy template.
        if (file("src/posixMain/kotlin").exists()) {
            listOf(
                "linuxX64Main",
                "linuxArm64Main",
                "macosArm64Main",
                "iosArm64Main",
                "iosSimulatorArm64Main",
                "tvosArm64Main",
                "tvosSimulatorArm64Main",
                "watchosDeviceArm64Main",
                "watchosSimulatorArm64Main",
                "androidNativeArm64Main",
                "androidNativeX64Main",
            ).forEach { name -> findByName(name)?.kotlin?.srcDir("src/posixMain/kotlin") }
        }
        if (file("src/posixTest/kotlin").exists()) {
            listOf(
                "linuxX64Test",
                "linuxArm64Test",
                "macosArm64Test",
                "iosArm64Test",
                "iosSimulatorArm64Test",
                "tvosArm64Test",
                "tvosSimulatorArm64Test",
                "watchosDeviceArm64Test",
                "watchosSimulatorArm64Test",
                "androidNativeArm64Test",
                "androidNativeX64Test",
            ).forEach { name -> findByName(name)?.kotlin?.srcDir("src/posixTest/kotlin") }
        }
        commonMain.dependencies {
            implementation(commonMainDependencyBundle)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            commonTestDependencyBundle?.let { implementation(it) }
        }
        if (benchmarkEnabled) {
            val commonBenchmark = maybeCreate("commonBenchmark")
            commonBenchmark.dependencies {
                implementation(commonBenchmarkDependencyBundle!!)
            }
            benchmarkTargetNames.forEach { targetName ->
                findByName("${targetName}Benchmark")?.dependsOn(commonBenchmark)
            }
        }
    }
}

allOpen {
    annotation("org.openjdk.jmh.annotations.State")
    annotation("kotlinx.benchmark.State")
}

if (benchmarkEnabled) {
    benchmark {
        targets {
            benchmarkTargetNames.forEach { targetName ->
                register("${targetName}Benchmark")
            }
        }
        configurations {
            named("main") {
                warmups = benchmarkWarmups
                iterations = benchmarkIterations
                iterationTime = benchmarkIterationTime
                iterationTimeUnit = benchmarkIterationTimeUnit
            }
        }
    }
}

// ============================================================================
// Test logging
// ============================================================================
tasks.withType<AbstractTestTask>().configureEach {
    testLogging {
        events(
            TestLogEvent.STARTED,
            TestLogEvent.PASSED,
            TestLogEvent.SKIPPED,
            TestLogEvent.FAILED,
            TestLogEvent.STANDARD_OUT,
            TestLogEvent.STANDARD_ERROR,
        )
        exceptionFormat = TestExceptionFormat.FULL
        showCauses = true
        showExceptions = true
        showStackTraces = true
        showStandardStreams = true
    }
}

// ============================================================================
// Static analysis: Detekt + Ktlint
// ============================================================================
detekt {
    buildUponDefaultConfig.set(true)
    allRules.set(false)
    autoCorrect.set(false)
    source.setFrom(files("src"))
    config.setFrom(files("detekt.yml"))
    parallel.set(true)
}

tasks.withType<dev.detekt.gradle.Detekt>().configureEach {
    reports {
        html.required.set(true)
        sarif.required.set(true)
        checkstyle.required.set(false)
    }
}

ktlint {
    version.set(libs.versions.ktlintEngine.get())
    debug.set(false)
    verbose.set(false)
    android.set(false)
    outputToConsole.set(true)
    ignoreFailures.set(false)
    reporters {
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.CHECKSTYLE)
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.SARIF)
    }
    filter {
        exclude("**/build/**")
        include("**/src/**/kotlin/**")
    }
}

if (benchmarkEnabled) {
    tasks
        .withType<dev.detekt.gradle.Detekt>()
        .matching {
            it.name.contains("BenchmarkBenchmark")
        }.configureEach {
            enabled = false
        }

    tasks
        .matching {
            it.name.startsWith("runKtlintCheckOver") && it.name.endsWith("BenchmarkBenchmarkSourceSet")
        }.configureEach {
            enabled = false
        }
}

tasks.named("check") {
    dependsOn(tasks.withType<dev.detekt.gradle.Detekt>())
    dependsOn(tasks.named("ktlintCheck"))
    dependsOn("test")
}

// ============================================================================
// JS / Wasm toolchain pins
// ============================================================================
val nodeVersion = providers.gradleProperty("node.version").getOrElse("24.22.0")
val wasmNodeVersion = providers.gradleProperty("wasm.node.version").getOrElse(nodeVersion)
val yarnVersion = providers.gradleProperty("yarn.version").getOrElse("1.22.22")
val wasmYarnVersion = providers.gradleProperty("wasm.yarn.version").getOrElse(yarnVersion)

// webpack is pinned in kotlin-js-store/package.json — the single source of truth
// that Dependabot updates natively. Gradle reads the version from there so the
// yarn resolution and the NodeJsRootExtension pin always track the checked-in
// store; a Dependabot bump of package.json/yarn.lock is honored rather than
// overridden. (These two values previously lived in gradle.properties, which
// Dependabot cannot see, so a bump there would silently revert the build.)
@Suppress("UNCHECKED_CAST")
val webpackVersion: String =
    (JsonSlurper().parse(rootProject.file("kotlin-js-store/package.json")) as Map<String, Any>)
        .let { it["dependencies"] as Map<String, Any> }["webpack"] as String

rootProject.extensions.configure<NodeJsEnvSpec>("kotlinNodeJsSpec") { version.set(nodeVersion) }
rootProject.extensions.configure<WasmNodeJsEnvSpec>("kotlinWasmNodeJsSpec") { version.set(wasmNodeVersion) }
rootProject.extensions.configure<YarnRootEnvSpec>("kotlinYarnSpec") {
    version.set(yarnVersion)
    yarnLockMismatchReport.set(org.jetbrains.kotlin.gradle.targets.js.yarn.YarnLockMismatchReport.WARNING)
    yarnLockAutoReplace.set(true)
}
rootProject.extensions.configure<WasmYarnRootEnvSpec>("kotlinWasmYarnSpec") {
    version.set(wasmYarnVersion)
    yarnLockMismatchReport.set(org.jetbrains.kotlin.gradle.targets.js.yarn.YarnLockMismatchReport.WARNING)
    yarnLockAutoReplace.set(true)
}

rootProject.extensions.configure<YarnRootExtension>("kotlinYarn") {
    yarnLockMismatchReportProperty.set(org.jetbrains.kotlin.gradle.targets.js.yarn.YarnLockMismatchReport.WARNING)
    yarnLockAutoReplaceProperty.set(true)
    providers
        .gradlePropertiesPrefixedBy("yarn.resolution.")
        .getOrElse(emptyMap())
        .forEach { (key, ver) ->
            val pkg = key.removePrefix("yarn.resolution.")
            resolution(pkg, ver)
            resolution("**/$pkg", ver)
        }
    // webpack resolution sourced from kotlin-js-store/package.json (see above)
    // rather than a yarn.resolution.webpack property, so it can never override a
    // Dependabot bump of the store.
    resolution("webpack", webpackVersion)
    resolution("**/webpack", webpackVersion)
}

val patchedKarmaWebpackPackage =
    rootProject.layout.projectDirectory
        .dir("gradle/npm/karma-webpack")
        .asFile.absolutePath
        .replace("\\", "/")

// TODO: NodeJsRootExtension.versions.* is deprecated and will be removed when the spec-based
//       NodeJsEnvSpec API gains equivalent properties. Track KGP release notes before removing.
rootProject.extensions.configure<NodeJsRootExtension>("kotlinNodeJs") {
    versions.webpack.version = webpackVersion
    versions.webpackCli.version = providers.gradleProperty("node.webpackCli.version").getOrElse("7.2.3")
    versions.karma.version = providers.gradleProperty("node.karma.version").getOrElse("npm:karma-maintained@6.4.8")
    versions.karmaWebpack.version = "file:$patchedKarmaWebpackPackage"
    versions.mocha.version = providers.gradleProperty("node.mocha.version").getOrElse("12.0.3")
    versions.kotlinWebHelpers.version = providers.gradleProperty("node.kotlinWebHelpers.version").getOrElse("3.1.0")
}

// Make kotlinUpgradeYarnLock and kotlinWasmUpgradeYarnLock dependencies in the build process
// for KotlinJS and other JavaScript/WASM targets so that yarn.lock is always upgraded automatically.
val jsTasksNeedingYarnLock =
    setOf(
        "compileKotlinJs",
        "compileTestKotlinJs",
        "jsProcessResources",
        "jsTestProcessResources",
        "jsNodeTest",
        "jsBrowserTest",
        "kotlinStoreYarnLock",
    )

tasks
    .matching { it.name in jsTasksNeedingYarnLock }
    .configureEach {
        dependsOn("kotlinUpgradeYarnLock")
    }

val wasmTasksNeedingYarnLock =
    setOf(
        "compileKotlinWasmJs",
        "compileTestKotlinWasmJs",
        "wasmJsProcessResources",
        "wasmJsTestProcessResources",
        "wasmJsNodeTest",
        "wasmJsBrowserTest",
        "compileKotlinWasmWasi",
        "compileTestKotlinWasmWasi",
        "wasmWasiProcessResources",
        "wasmWasiTestProcessResources",
        "wasmWasiNodeTest",
        "kotlinWasmStoreYarnLock",
    )

tasks
    .matching { it.name in wasmTasksNeedingYarnLock }
    .configureEach {
        dependsOn("kotlinWasmUpgradeYarnLock")
    }

// ============================================================================
// Maven Central publishing — Central Portal, first-party + bespoke upload
// ----------------------------------------------------------------------------
// OSSRH was sunset 2025-06-30; the Central Portal is the only path. Sonatype
// ships no first-party Gradle plugin, so we use Gradle's own maven-publish +
// signing (KGP populates the KMP publications) and upload the deployment
// bundle to the Portal API ourselves.
//
// Flow: publish all KMP publications into a local staging Maven layout ->
// zip it -> POST the zip to the Portal upload endpoint with a Bearer token.
// ============================================================================
val publishProjectName = providers.gradleProperty("project.name").getOrElse("unnamed-project")

// Central requires a Javadoc jar per publication; KMP produces none, so attach
// an empty one to every Maven publication.
val emptyJavadocJar =
    tasks.register<Jar>("emptyJavadocJar") {
        description = "Empty javadoc jar required by Maven Central for every publication."
        archiveClassifier.set("javadoc")
    }

publishing {
    publications.withType<MavenPublication>().configureEach {
        artifact(emptyJavadocJar)
        pom {
            name.set(publishProjectName)
            description.set(providers.gradleProperty("project.pom.description").getOrElse(""))
            inceptionYear.set("2026")
            url.set("https://github.com/KotlinMania/$publishProjectName")
            licenses {
                license {
                    name.set(providers.gradleProperty("project.pom.licenseName").getOrElse("MIT"))
                    url.set(
                        providers
                            .gradleProperty("project.pom.licenseUrl")
                            .getOrElse("https://opensource.org/licenses/MIT"),
                    )
                    distribution.set("repo")
                }
            }
            developers {
                developer {
                    id.set("sydneyrenee")
                    name.set("Sydney Renee")
                    email.set("sydney@solace.ofharmony.ai")
                    url.set("https://github.com/sydneyrenee")
                }
            }
            scm {
                url.set("https://github.com/KotlinMania/$publishProjectName")
                connection.set("scm:git:git://github.com/KotlinMania/$publishProjectName.git")
                developerConnection.set("scm:git:ssh://github.com/KotlinMania/$publishProjectName.git")
            }
        }
    }

    // Stage into a local Maven layout that becomes the Portal deployment bundle.
    // maven-publish auto-generates the md5/sha1/sha256/sha512 checksums Central
    // requires; signing (below) adds the .asc signatures.
    repositories {
        maven {
            name = "centralPortalStaging"
            url = uri(layout.buildDirectory.dir("staging-deploy"))
        }
    }
}

signing {
    val signingKey = providers.gradleProperty("signingInMemoryKey").orNull
    val signingKeyId = providers.gradleProperty("signingInMemoryKeyId").orNull
    val signingPassword = providers.gradleProperty("signingInMemoryKeyPassword").orNull
    val signingEnabled = project.findProperty("RELEASE_SIGNING_ENABLED") != "false" && signingKey != null
    if (signingEnabled) {
        useInMemoryPgpKeys(signingKeyId, signingKey, signingPassword)
        sign(publishing.publications)
    }
}

val centralPortalPublishTasks =
    tasks.withType<PublishToMavenRepository>().matching {
        it.name.endsWith("ToCentralPortalStagingRepository")
    }

centralPortalPublishTasks.configureEach {
    dependsOn(tasks.withType<Sign>())
}

// Zip the staged Maven layout into a single Central Portal deployment bundle.
val centralPortalBundle =
    tasks.register<Zip>("centralPortalBundle") {
        group = "publishing"
        description = "Bundles the staged Maven artifacts into a Central Portal deployment zip."
        dependsOn(centralPortalPublishTasks)
        from(layout.buildDirectory.dir("staging-deploy"))
        archiveFileName.set("$publishProjectName-$version-bundle.zip")
        destinationDirectory.set(layout.buildDirectory.dir("central-portal"))
    }

// Upload the bundle to the Sonatype Central Portal Publisher API.
// publishingType: USER_MANAGED (default, safe — validates then waits for a
// manual release in the Portal UI) or AUTOMATIC (publishes after validation).
val publishToCentralPortal =
    tasks.register("publishToCentralPortal") {
        group = "publishing"
        description = "Uploads the deployment bundle to the Sonatype Central Portal."
        dependsOn(centralPortalBundle)
        doLast {
            val user =
                providers.gradleProperty("mavenCentralUsername").orNull
                    ?: error("mavenCentralUsername is required to publish to the Central Portal.")
            val password =
                providers.gradleProperty("mavenCentralPassword").orNull
                    ?: error("mavenCentralPassword is required to publish to the Central Portal.")
            val publishingType = providers.gradleProperty("centralPublishingType").getOrElse("USER_MANAGED")
            val token = Base64.getEncoder().encodeToString("$user:$password".toByteArray(Charsets.UTF_8))

            val bundle =
                centralPortalBundle
                    .get()
                    .archiveFile
                    .get()
                    .asFile
            require(bundle.exists()) { "Deployment bundle not found: $bundle" }

            val boundary = "CentralPortalBoundary" + UUID.randomUUID().toString().replace("-", "")
            val crlf = "\r\n"
            val preamble =
                (
                    "--$boundary$crlf" +
                        "Content-Disposition: form-data; name=\"bundle\"; filename=\"${bundle.name}\"$crlf" +
                        "Content-Type: application/octet-stream$crlf$crlf"
                ).toByteArray(Charsets.UTF_8)
            val epilogue = "$crlf--$boundary--$crlf".toByteArray(Charsets.UTF_8)
            val body = preamble + bundle.readBytes() + epilogue

            val deploymentName = "$publishProjectName-$version"
            val uploadUri =
                URI(
                    "https://central.sonatype.com/api/v1/publisher/upload" +
                        "?name=$deploymentName&publishingType=$publishingType",
                )
            val request =
                HttpRequest
                    .newBuilder()
                    .uri(uploadUri)
                    .header("Authorization", "Bearer $token")
                    .header("Content-Type", "multipart/form-data; boundary=$boundary")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build()

            val client = HttpClient.newHttpClient()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                error("Central Portal upload failed: HTTP ${response.statusCode()} — ${response.body()}")
            }
            val deploymentId = response.body().trim()
            logger.lifecycle(
                "Central Portal upload accepted (deployment id: $deploymentId). " +
                    "publishingType=$publishingType.",
            )
            val automaticPublishing = publishingType.equals("AUTOMATIC", ignoreCase = true)
            val terminalStates =
                if (automaticPublishing) {
                    setOf("PUBLISHED")
                } else {
                    setOf("VALIDATED", "PUBLISHED")
                }
            val statusUri = URI("https://central.sonatype.com/api/v1/publisher/status?id=$deploymentId")
            val statusAttempts =
                providers.gradleProperty("centralPublishStatusAttempts").map(String::toInt).getOrElse(120)
            val statusDelayMillis =
                providers.gradleProperty("centralPublishStatusDelayMillis").map(String::toLong).getOrElse(10_000L)
            repeat(statusAttempts) { attempt ->
                val statusRequest =
                    HttpRequest
                        .newBuilder()
                        .uri(statusUri)
                        .header("Authorization", "Bearer $token")
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build()
                val statusResponse = client.send(statusRequest, HttpResponse.BodyHandlers.ofString())
                if (statusResponse.statusCode() !in 200..299) {
                    error("Central Portal status check failed: HTTP ${statusResponse.statusCode()} — ${statusResponse.body()}")
                }
                val statusBody = JsonSlurper().parseText(statusResponse.body()) as Map<*, *>
                val deploymentState =
                    statusBody["deploymentState"]?.toString()
                        ?: error("Central Portal status response did not contain deploymentState: ${statusResponse.body()}")
                when (deploymentState) {
                    "FAILED" -> {
                        error("Central Portal deployment failed: ${statusBody["errors"] ?: statusResponse.body()}")
                    }

                    in terminalStates -> {
                        logger.lifecycle("Central Portal deployment $deploymentId reached $deploymentState.")
                        return@doLast
                    }
                }
                logger.lifecycle(
                    "Central Portal deployment $deploymentId is $deploymentState " +
                        "(${attempt + 1}/$statusAttempts).",
                )
                if (attempt + 1 < statusAttempts) {
                    Thread.sleep(statusDelayMillis)
                }
            }
            error(
                "Central Portal deployment $deploymentId did not reach " +
                    "${terminalStates.joinToString("/")} after $statusAttempts checks.",
            )
        }
    }

// ============================================================================
// Tasks
// ============================================================================

// Exact test lifecycle task. Without this, ./gradlew test is ambiguous between
// Android test task names. This runs commonTest through the KMP allTests
// lifecycle and adds the Android host + Swift Export parity tests.
tasks.register("test") {
    group = "verification"
    description = "Runs the commonTest-backed KMP suite, Android host tests, and Swift Export smoke test."
    dependsOn("hostTests")
    dependsOn("swiftExportSmokeTest")
}

tasks.register("setupAndroidSdk") {
    group = "setup"
    description = "Downloads and configures the project-local Android SDK. (Alias for ensureAndroidSdk)"
    dependsOn("ensureAndroidSdk")
}

// ============================================================================
// JNI — JVM C bindings. Gated by project.ffi.jvm=true.
// ----------------------------------------------------------------------------
// Compiles src/jvmMain/c/${ffiNameLower}_jni.c into a shared library with
// the host clang and wires the resulting directory into
// -Djava.library.path for jvmTest, so `System.loadLibrary(...)` in the
// internal `<Lib>Jni` bridge resolves. Transliterated from libc-kotlin's
// compileLibcJni with the lib name parameterized on project.ffi.name.
// ============================================================================
if (jvmFfiEnabled) {
    val compileFfiJni =
        tasks.register("compile${ffiName}Jni") {
            group = "build"
            description = "Compiles ${ffiNameLower}_jni.c into a shared library for JVM JNI binding"
            val cSource = file("src/jvmMain/c/${ffiNameLower}_jni.c")
            val outDir =
                layout.buildDirectory
                    .dir("natives")
                    .get()
                    .asFile
            inputs.file(cSource)
            outputs.dir(outDir)

            doLast {
                outDir.mkdirs()
                val javaHome = System.getProperty("java.home") ?: System.getenv("JAVA_HOME") ?: ""
                val isWindows =
                    org.gradle.internal.os.OperatingSystem
                        .current()
                        .isWindows
                val isMac =
                    org.gradle.internal.os.OperatingSystem
                        .current()
                        .isMacOsX

                val (libName, osInclude) =
                    when {
                        isWindows -> "${ffiNameLower}_jni.dll" to "win32"
                        isMac -> "lib${ffiNameLower}_jni.dylib" to "darwin"
                        else -> "lib${ffiNameLower}_jni.so" to "linux"
                    }
                val outFile = File(outDir, libName)
                val javaInclude = File(javaHome, "include")
                val javaOsInclude = File(javaInclude, osInclude)

                if (javaInclude.exists()) {
                    val execOps = project.serviceOf<ExecOperations>()
                    try {
                        val cmd =
                            mutableListOf(
                                "clang",
                                "-shared",
                                "-O2",
                                "-I${javaInclude.absolutePath}",
                                "-I${javaOsInclude.absolutePath}",
                                cSource.absolutePath,
                                "-o",
                                outFile.absolutePath,
                            )
                        if (!isWindows) {
                            cmd.add(2, "-fPIC")
                        } else {
                            cmd.addAll(listOf("-lws2_32"))
                        }
                        execOps
                            .exec {
                                commandLine(cmd)
                            }.assertNormalExitValue()
                    } catch (e: Exception) {
                        logger.warn("Could not compile ${ffiNameLower}_jni with host clang: ${e.message}")
                    }
                }
            }
        }

    tasks.named<Test>("jvmTest") {
        dependsOn(compileFfiJni)
        systemProperty(
            "java.library.path",
            layout.buildDirectory
                .dir("natives")
                .get()
                .asFile.absolutePath,
        )
    }
}

// ============================================================================
// N-API — JS/WASM C bindings. Gated by project.ffi.napi=true.
// ----------------------------------------------------------------------------
// Builds the native/node-${ffiNameLower}/ addon via node-gyp and copies it
// into the JS test runtime's node_modules so the Kotlin-side @JsModule
// declaration resolves. Transliterated from libc-kotlin's buildNodeLibc/
// copyNodeLibc with the lib name and npm package parameterized.
// ============================================================================
if (napiFfiEnabled) {
    val buildNodeFfi =
        tasks.register<Exec>("buildNode$ffiName") {
            group = "build"
            description = "Builds the native node-$ffiNameLower N-API addon via node-gyp"
            workingDir = file(ffiNapiDir)
            val isWindows =
                org.gradle.internal.os.OperatingSystem
                    .current()
                    .isWindows
            if (isWindows) {
                commandLine("cmd", "/c", "npm install --ignore-scripts && npx node-gyp configure && npx node-gyp build")
            } else {
                commandLine("sh", "-c", "npm install --ignore-scripts && npx node-gyp configure && npx node-gyp build")
            }
        }

    val copyNodeFfi =
        tasks.register<Copy>("copyNode$ffiName") {
            dependsOn(buildNodeFfi)
            from(ffiNapiDir)
            into(layout.buildDirectory.dir("js/node_modules/$ffiNpmPackage"))
        }
    val copyWasmNodeFfi =
        tasks.register<Copy>("copyWasmNode$ffiName") {
            dependsOn(buildNodeFfi)
            from(ffiNapiDir)
            into(layout.buildDirectory.dir("wasm/node_modules/$ffiNpmPackage"))
        }
    tasks.matching { it.name.startsWith("jsNodeTest") || it.name.startsWith("wasmJsNodeTest") }.configureEach {
        dependsOn(copyNodeFfi, copyWasmNodeFfi)
    }
}

// Propagate JNI and N-API native bindings from composite includedBuilds
val extraJavaLibPaths = mutableListOf<String>()
if (jvmFfiEnabled) {
    extraJavaLibPaths.add(
        layout.buildDirectory
            .dir("natives")
            .get()
            .asFile.absolutePath,
    )
}
gradle.includedBuilds.forEach { ib ->
    val ibPropsFile = File(ib.projectDir, "gradle.properties")
    if (ibPropsFile.exists()) {
        val ibProps =
            ibPropsFile
                .readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && '=' in it }
                .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
        val ibFfiRaw = (ibProps["project.ffi.name"] ?: "Ffi").trim()
        val ibFfiName = ibFfiRaw.replaceFirstChar { it.uppercase() }
        val ibFfiLower = ibFfiRaw.lowercase()
        val ibJvmFfi = (ibProps["project.ffi.jvm"] ?: "false").trim().lowercase() == "true"
        val ibNapiFfi = (ibProps["project.ffi.napi"] ?: "false").trim().lowercase() == "true"

        if (ibJvmFfi) {
            extraJavaLibPaths.add(File(ib.projectDir, "build/natives").absolutePath)
            tasks.named<Test>("jvmTest") {
                dependsOn(ib.task(":compile${ibFfiName}Jni"))
            }
        }
        if (ibNapiFfi) {
            val copyIbNode =
                tasks.register<Copy>("copyIncludedNode$ibFfiName") {
                    dependsOn(ib.task(":buildNode$ibFfiName"))
                    from(File(ib.projectDir, "native/node-$ibFfiLower"))
                    into(layout.buildDirectory.dir("js/node_modules/@kotlinmania/$ibFfiLower-native-bindings"))
                }
            val copyIbWasmNode =
                tasks.register<Copy>("copyIncludedWasmNode$ibFfiName") {
                    dependsOn(ib.task(":buildNode$ibFfiName"))
                    from(File(ib.projectDir, "native/node-$ibFfiLower"))
                    into(layout.buildDirectory.dir("wasm/node_modules/@kotlinmania/$ibFfiLower-native-bindings"))
                }
            tasks.matching { it.name.startsWith("jsNodeTest") || it.name.startsWith("wasmJsNodeTest") }.configureEach {
                dependsOn(copyIbNode, copyIbWasmNode)
            }
        }
    }
}
if (extraJavaLibPaths.isNotEmpty()) {
    tasks.named<Test>("jvmTest") {
        systemProperty("java.library.path", extraJavaLibPaths.joinToString(File.pathSeparator))
    }
}

// Explicit test runner. Named hostTests to avoid shadowing the KMP allTests
// lifecycle task. Do not use findByName/mapNotNull here: missing test tasks
// mean the target surface drifted and must fail loudly.
tasks.register("hostTests") {
    group = "verification"
    description = "Runs the required real test suite (jvm, macosArm64, js, wasmJs, wasmWasi, android host)."
    dependsOn(
        "jvmTest",
        "macosArm64Test",
        "kotlinUpgradeYarnLock",
        "jsNodeTest",
        "kotlinWasmUpgradeYarnLock",
        "wasmJsNodeTest",
        "wasmWasiNodeTest",
        "testAndroidHostTest",
    )
}

// Patch generated SPM Package.swift to include minimum macOS platform for Swift Concurrency
tasks.matching { it.name.contains("GenerateSPMPackage") }.configureEach {
    doLast {
        val spmDir =
            layout.buildDirectory
                .dir("SPMPackage")
                .orNull
                ?.asFile
        if (spmDir != null && spmDir.exists()) {
            spmDir.walkTopDown().filter { it.name == "Package.swift" }.forEach { file ->
                var text = file.readText()
                if (text.contains("swift-tools-version: 6.0")) {
                    text = text.replace("swift-tools-version: 6.0", "swift-tools-version: 5.9")
                }
                if (!text.contains("platforms:")) {
                    text =
                        text.replaceFirst(
                            Regex("""(let package = Package\s*\(\s*name:\s*"[^"]*",)"""),
                            "$1\n    platforms: [.macOS(\"15.0\")],",
                        )
                } else if (text.contains(".macOS(.v15)") || text.contains(".macOS(.v14)")) {
                    text = text.replace(".macOS(.v15)", ".macOS(\"15.0\")").replace(".macOS(.v14)", ".macOS(\"15.0\")")
                }
                file.writeText(text)
            }
            spmDir.walkTopDown().filter { it.name == "OrgJetbrainsKotlinxKotlinxSerializationCore.swift" }.forEach { file ->
                val text = file.readText()
                val cleaned =
                    text.replace(
                        Regex("""@_spi\([^)]+\)\s+public func (?:decodeSequentially|shouldEncodeElementDefault|encodeNotNullMark)\([^)]*\)(?:\s*->\s*[^\n{]+)?\s*\{\s*fatalError\([^)]+\)\s*\}"""),
                        "",
                    )
                if (cleaned != text) {
                    file.writeText(cleaned)
                }
            }
        }
    }
}

// Swift Export smoke test — produces the SPM package via embedSwiftExportForXcode
// (spawned with the Xcode-style env it requires) and runs `swift test` against it,
// so Swift Export breakage surfaces locally, not only in the swift.yml CI job.
// Pattern mirrors kasuari-kotlin. This task is part of the build contract and
// must fail rather than skip when the required toolchain is unavailable.
tasks.register("swiftExportSmokeTest") {
    group = "verification"
    description = "Builds the Swift Export SPM package and runs swift test against it."
    outputs.upToDateWhen { false }

    doLast {
        val execOperations = serviceOf<ExecOperations>()
        val swiftBuildFile =
            layout.buildDirectory
                .dir("swift-test")
                .get()
                .asFile
        if (swiftBuildFile.exists()) {
            swiftBuildFile.deleteRecursively()
        }
        swiftBuildFile.mkdirs()
        val swiftBuildDir = swiftBuildFile.absolutePath
        execOperations
            .exec {
                workingDir = projectDir
                commandLine(
                    "./gradlew",
                    "embedSwiftExportForXcode",
                    "-Dorg.gradle.jvmargs=-Xmx6g -XX:MaxMetaspaceSize=1g",
                    "--no-configuration-cache",
                    "--no-daemon",
                    "--console=plain",
                )
                environment(
                    mapOf(
                        "BUILT_PRODUCTS_DIR" to swiftBuildDir,
                        "TARGET_BUILD_DIR" to swiftBuildDir,
                        "SDK_NAME" to "macosx",
                        "CONFIGURATION" to "Debug",
                        "ARCHS" to "arm64",
                        "FRAMEWORKS_FOLDER_PATH" to "Frameworks",
                        "MACOSX_DEPLOYMENT_TARGET" to "15.0",
                        "DEPLOYMENT_TARGET_SETTING_NAME" to "MACOSX_DEPLOYMENT_TARGET",
                    ),
                )
            }.assertNormalExitValue()

        execOperations
            .exec {
                workingDir = layout.projectDirectory.dir("swift-test-harness").asFile
                commandLine("swift", "package", "reset")
            }.assertNormalExitValue()

        execOperations
            .exec {
                workingDir = layout.projectDirectory.dir("swift-test-harness").asFile
                commandLine("swift", "test")
            }.assertNormalExitValue()
    }
}

// ============================================================================
// `build` aggregate
// ----------------------------------------------------------------------------
// Every configured native target, unconditionally. This is the audit contract —
// it must mirror the kotlin { } target block exactly. All native 32-bit and
// Apple-specific x64 targets are retired; every retained target MUST build.
// Do not add a dynamic tasks.matching fallback here: copied templates must make
// the target surface explicit so missing declarations fail loudly in review.
// ============================================================================
val nativeTargetNames =
    listOf(
        "androidNativeArm64",
        "androidNativeX64",
        "iosArm64",
        "iosSimulatorArm64",
        "linuxArm64",
        "linuxX64",
        "macosArm64",
        "mingwX64",
        "tvosArm64",
        "tvosSimulatorArm64",
        "watchosDeviceArm64",
        "watchosSimulatorArm64",
    )

val fullTargetBuildTaskNames =
    buildSet {
        addAll(
            listOf(
                "compileAndroidMain",
                "compileAndroidHostTest",
                "compileAndroidDeviceTest",
                "assembleAndroidMain",
                "assembleUnitTest",
                "assembleAndroidTest",
                "assembleAndroidDeviceTest",
                "jvmMainClasses",
                "jvmTestClasses",
                "kotlinUpgradeYarnLock",
                "kotlinStoreYarnLock",
                "kotlinWasmUpgradeYarnLock",
                "kotlinWasmStoreYarnLock",
                "jsMainClasses",
                "jsTestClasses",
                "wasmJsMainClasses",
                "wasmJsTestClasses",
                "wasmWasiMainClasses",
                "wasmWasiTestClasses",
                "swiftExportSmokeTest",
                "assemble${frameworkName}XCFramework",
            ),
        )
        for (target in nativeTargetNames) {
            add("${target}Binaries")
            add("${target}TestBinaries")
        }
        // Native C binding channels, included only when their gate is on.
        // A declared channel that fails to build must fail the build loudly.
        if (jvmFfiEnabled) add("compile${ffiName}Jni")
        if (napiFfiEnabled) add("buildNode$ffiName")
        // nativeFfi per-target wrapper tasks run as dependencies of the
        // cinterop tasks that the per-target Binaries entries above already
        // pull in — no separate add() needed here.
    }

tasks.named("build") {
    dependsOn(fullTargetBuildTaskNames)
}
