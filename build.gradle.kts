import org.gradle.api.GradleException
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.tasks.ClasspathNormalizer
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.AbstractTestTask
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.gradle.kotlin.dsl.support.serviceOf
import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
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
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kmp)
    alias(libs.plugins.vanniktech)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
}

group = providers.gradleProperty("project.group").getOrElse("io.github.kotlinmania")
version = providers.gradleProperty("project.version").getOrElse("0.1.0-SNAPSHOT")
val frameworkName = providers.gradleProperty("project.frameworkName").getOrElse("Unnamed")
val projectNamespace = providers.gradleProperty("project.namespace").getOrElse("io.github.kotlinmania")
val kotlinVersion = providers.gradleProperty("versions.kotlin").getOrElse("2.3.21")
val isCodeqlBuild = providers.gradleProperty("kotlinmania.codeql").map(String::toBoolean).getOrElse(false)
val commonMainBundleName = providers.gradleProperty("project.dependencies.commonMainBundle").get()
val commonMainDependencyBundle =
    extensions
        .getByType(VersionCatalogsExtension::class.java)
        .named("libs")
        .findBundle(commonMainBundleName)
        .orElseThrow { GradleException("Missing libs bundle '$commonMainBundleName'") }

// Opt-ins shared between the top-level compilerOptions and the codeqlCompileJvm kotlinc invocation.
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

writeAndroidLocalProperties()

val ensureAndroidSdk by tasks.registering {
    group = "setup"
    description = "Ensures the project-local Android SDK is installed (idempotent)."
    onlyIf("Android SDK already installed at $projectAndroidSdkDir") { !isProjectAndroidSdkInstalled() }
    doLast {
        installProjectAndroidSdk(serviceOf())
    }
}

tasks.matching { it.name == "compileAndroidMain" }.configureEach {
    dependsOn(ensureAndroidSdk)
}

val jvmToolchainVersion = providers.gradleProperty("jvm.toolchain").getOrElse("21").toInt()

// ============================================================================
// kotlin { … }
// ----------------------------------------------------------------------------
// watchosArm32: retired by workspace product policy (kmp-watchosarm32-retirement
//   per AGENTS.md §5.5.1, effective 2026-05-24). Upstream KGP still ships it as
//   Tier 2 — this is a deliberate product decision, not a framework deprecation.
// Deprecated by KGP since 2.3.20 (never re-add): macosX64, tvosX64, watchosX64.
// Every other target is built unconditionally — KotlinMania supports the full
//   target surface, so there are NO opt-in build gates. The build gate is the
//   contract that forces every current KotlinMania target to compile.
// ============================================================================
kotlin {
    jvmToolchain(jvmToolchainVersion)

    applyDefaultHierarchyTemplate()

    compilerOptions {
        languageVersion.set(KotlinVersion.KOTLIN_2_3)
        apiVersion.set(KotlinVersion.KOTLIN_2_3)
        allWarningsAsErrors.set(!isCodeqlBuild)
        optIn.addAll(commonOptIns)
        freeCompilerArgs.add("-Xexpect-actual-classes")
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

    // Apple — Tier 1/2 targets
    macosArm64 { addToXcf() }
    iosArm64 { addToXcf(static = true) }
    iosSimulatorArm64 { addToXcf(static = true) }
    tvosArm64 { addToXcf() }
    tvosSimulatorArm64 { addToXcf() }
    watchosArm64 { addToXcf() }
    watchosDeviceArm64 { addToXcf() }
    watchosSimulatorArm64 { addToXcf() }

    // iosX64: Intel Mac simulator. Tier 3 in Kotlin/Native but NOT deprecated —
    // Apple still ships x86_64 iOS simulator runtimes, so it is always built.
    iosX64 { addToXcf(static = true) }

    // Other native — Tier 1/2
    linuxX64()
    linuxArm64()
    mingwX64()

    // Android NDK — always built (full target surface, no opt-in gate).
    androidNativeArm32()
    androidNativeArm64()
    androidNativeX86()
    androidNativeX64()

    // Web
    js {
        browser()
        nodejs()
    }

    // wasmJs is Stable as of Kotlin 2.2; @OptIn may be removable — verify before dropping on wasmWasi.
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        nodejs()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmWasi {
        nodejs()
    }

    // Swift Export bridge — Experimental per Kotlin 2.3.0 release notes.
    // KGP 2.3.21 does not expose a public opt-in annotation; warnings (if any)
    // arrive via KotlinToolingDiagnostics, not @RequiresOptIn.
    swiftExport {
        moduleName = frameworkName
        flattenPackage = projectNamespace
        @OptIn(org.jetbrains.kotlin.gradle.swiftexport.ExperimentalSwiftExportDsl::class)
        configure {
            settings.put("enableCoroutinesSupport", "true")
        }
    }

    // Android KMP library. Block name is `android` — `androidLibrary` is deprecated in KGP 2.3.x.
    android {
        namespace = projectNamespace
        compileSdk = projectCompileSdk.toInt()
        minSdk = providers.gradleProperty("android.minSdk").getOrElse("24").toInt()
        withHostTestBuilder {}.configure {}
        withDeviceTestBuilder { sourceSetTreeName = "test" }
    }

    // JVM — jvmTarget derived from the same toolchain property so they can't drift.
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.fromTarget(jvmToolchainVersion.toString()))
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(commonMainDependencyBundle)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
    if (name.startsWith("compileSwiftExport")) {
        compilerOptions.allWarningsAsErrors.set(false)
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
    buildUponDefaultConfig = true
    allRules = false
    autoCorrect = false
    source.setFrom(files("src"))
    config.setFrom(files("detekt.yml"))
    parallel = true
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    reports {
        html.required.set(true)
        sarif.required.set(true)
        txt.required.set(false)
        xml.required.set(false)
    }
}

ktlint {
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

tasks.named("check") {
    dependsOn(tasks.withType<io.gitlab.arturbosch.detekt.Detekt>())
    dependsOn(tasks.named("ktlintCheck"))
    // Android host unit tests run here alongside the tests that check -> allTests
    // already executes (jvm, macosArm64, the Apple simulators, js, wasmJs,
    // wasmWasi). Test EXECUTION belongs to check; target BUILD coverage belongs
    // to the explicit all-target build set below.
    dependsOn("testAndroidHostTest")
    // Swift Export smoke test is required; it must not self-skip.
    dependsOn("swiftExportSmokeTest")
}

// ============================================================================
// JS / Wasm toolchain pins
// ============================================================================
val nodeVersion = providers.gradleProperty("node.version").getOrElse("24.15.0")
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
    (groovy.json.JsonSlurper().parse(rootProject.file("kotlin-js-store/package.json")) as Map<String, Any>)
        .let { it["dependencies"] as Map<String, Any> }["webpack"] as String

rootProject.extensions.configure<NodeJsEnvSpec>("kotlinNodeJsSpec") { version.set(nodeVersion) }
rootProject.extensions.configure<WasmNodeJsEnvSpec>("kotlinWasmNodeJsSpec") { version.set(wasmNodeVersion) }
rootProject.extensions.configure<YarnRootEnvSpec>("kotlinYarnSpec") { version.set(yarnVersion) }
rootProject.extensions.configure<WasmYarnRootEnvSpec>("kotlinWasmYarnSpec") { version.set(wasmYarnVersion) }

rootProject.extensions.configure<YarnRootExtension>("kotlinYarn") {
    project.properties
        .filterKeys { it.startsWith("yarn.resolution.") }
        .forEach { (key, value) ->
            val pkg = key.removePrefix("yarn.resolution.")
            val ver = value as? String ?: return@forEach
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
    versions.webpackCli.version = providers.gradleProperty("node.webpackCli.version").getOrElse("7.0.2")
    versions.karma.version = providers.gradleProperty("node.karma.version").getOrElse("npm:karma-maintained@6.4.7")
    versions.karmaWebpack.version = "file:$patchedKarmaWebpackPackage"
    versions.mocha.version = providers.gradleProperty("node.mocha.version").getOrElse("12.0.0-beta-10")
    versions.kotlinWebHelpers.version = providers.gradleProperty("node.kotlinWebHelpers.version").getOrElse("3.1.0")
}

// ============================================================================
// Maven Central publishing
// ============================================================================
mavenPublishing {
    publishToMavenCentral()
    if (project.findProperty("RELEASE_SIGNING_ENABLED") != "false") {
        signAllPublications()
    }
    val projectName = providers.gradleProperty("project.name").getOrElse("unnamed-project")
    coordinates(group.toString(), projectName, version.toString())
    pom {
        name.set(projectName)
        description.set(providers.gradleProperty("project.pom.description").getOrElse(""))
        inceptionYear.set("2026")
        url.set("https://github.com/KotlinMania/$projectName")
        licenses {
            license {
                name.set(providers.gradleProperty("project.pom.licenseName").getOrElse("MIT"))
                url.set(
                    providers.gradleProperty("project.pom.licenseUrl").getOrElse("https://opensource.org/licenses/MIT"),
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
            url.set("https://github.com/KotlinMania/$projectName")
            connection.set("scm:git:git://github.com/KotlinMania/$projectName.git")
            developerConnection.set("scm:git:ssh://github.com/KotlinMania/$projectName.git")
        }
    }
}

// ============================================================================
// CodeQL extraction
// ============================================================================
val codeqlKotlincScope =
    configurations.dependencyScope("codeqlKotlinc") {
        description = "Kotlin compiler (CodeQL extraction target only)"
    }
val codeqlSourceScope =
    configurations.dependencyScope("codeqlSourceClasspath") {
        description = "Runtime classpath for CodeQL extraction of commonMain sources"
    }
val codeqlAarScope =
    configurations.dependencyScope("codeqlAndroidAar") {
        description = "Android AAR artifacts for CodeQL dependency classpath extraction"
    }
val codeqlKotlincFiles =
    configurations.resolvable("codeqlKotlincFiles") {
        extendsFrom(codeqlKotlincScope.get())
    }
val codeqlSourceFiles =
    configurations.resolvable("codeqlSourceFiles") {
        extendsFrom(codeqlSourceScope.get())
    }
val codeqlAarFiles =
    configurations.resolvable("codeqlAarFiles") {
        extendsFrom(codeqlAarScope.get())
    }

val codeqlLanguageVersion =
    providers
        .gradleProperty("kotlin.languageVersion")
        .getOrElse(kotlinVersion.split('.').take(2).joinToString("."))
val codeqlApiVersion = providers.gradleProperty("kotlin.apiVersion").getOrElse(codeqlLanguageVersion)
val codeqlKotlinSourceSetNames =
    providers
        .gradleProperty("project.codeql.kotlinSourceSets")
        .getOrElse("commonMain")
        .splitToSequence(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toList()
val codeqlKotlinCommonSourceSetNames =
    providers
        .gradleProperty("project.codeql.kotlinCommonSourceSets")
        .getOrElse("commonMain")
        .splitToSequence(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toList()

dependencies {
    val codeqlKotlinVersion = providers.gradleProperty("codeql.kotlin.version").getOrElse(kotlinVersion)
    add("codeqlKotlinc", "org.jetbrains.kotlin:kotlin-compiler-embeddable:$codeqlKotlinVersion")

    providers
        .gradleProperty("project.dependencies.codeqlSourceClasspath")
        .getOrElse("")
        .splitToSequence(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .forEach { add("codeqlSourceClasspath", it) }

    providers
        .gradleProperty("project.dependencies.codeqlAndroidAar")
        .getOrElse("")
        .splitToSequence(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .forEach { add("codeqlAndroidAar", it) }
}

val codeqlCompileJvm =
    tasks.register<JavaExec>("codeqlCompileJvm") {
        description =
            "Compile ${codeqlKotlinSourceSetNames.joinToString(",")} Kotlin sources " +
            "with kotlinc $codeqlLanguageVersion for CodeQL Java/Kotlin extraction."
        group = "verification"
        classpath(codeqlKotlincFiles)
        mainClass.set("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler")
        // Inject services at config time — config-cache safe; project.copy/zipTree in a task
        // action would violate https://docs.gradle.org/9.5.1/userguide/configuration_cache.html
        val fs = serviceOf<FileSystemOperations>()
        val archives = serviceOf<ArchiveOperations>()
        val outDir = layout.buildDirectory.dir("classes/kotlin/codeql-jvm")
        val aarExtractDir = layout.buildDirectory.dir("codeql/android-aar")
        val commonSources =
            files(
                codeqlKotlinCommonSourceSetNames.map { sourceSetName ->
                    fileTree("src/$sourceSetName/kotlin") { include("**/*.kt") }
                },
            )
        val sources =
            files(
                codeqlKotlinSourceSetNames.map { sourceSetName ->
                    fileTree("src/$sourceSetName/kotlin") { include("**/*.kt") }
                },
            )
        inputs.files(sources).withPathSensitivity(PathSensitivity.RELATIVE)
        inputs.files(commonSources).withPathSensitivity(PathSensitivity.RELATIVE)
        inputs.files(codeqlSourceFiles).withNormalizer(ClasspathNormalizer::class.java)
        inputs.files(codeqlAarFiles).withNormalizer(ClasspathNormalizer::class.java)
        outputs.dir(outDir)
        outputs.dir(aarExtractDir)
        doFirst {
            outDir.get().asFile.mkdirs()
            val extractedJars =
                codeqlAarFiles.get().resolve().mapNotNull { aar ->
                    val extractTarget = aarExtractDir.get().asFile.resolve(aar.nameWithoutExtension)
                    extractTarget.mkdirs()
                    fs.copy {
                        from(archives.zipTree(aar))
                        include("classes.jar")
                        into(extractTarget)
                    }
                    extractTarget.resolve("classes.jar").takeIf { it.exists() }
                }
            val fullClasspath =
                (codeqlSourceFiles.get().resolve() + extractedJars)
                    .joinToString(File.pathSeparator) { it.absolutePath }
            val commonSourceFiles = commonSources.files.toMutableList()
            require(commonSourceFiles.isNotEmpty()) {
                "project.codeql.kotlinCommonSourceSets must resolve to at least one Kotlin source file"
            }
            val sourceFiles = sources.files.toMutableList()
            require(sourceFiles.isNotEmpty()) {
                "project.codeql.kotlinSourceSets must resolve to at least one Kotlin source file"
            }
            args = listOf(
                "-d",
                outDir.get().asFile.absolutePath,
                "-classpath",
                fullClasspath,
                "-jvm-target",
                jvmToolchainVersion.toString(),
                "-no-stdlib",
                "-no-reflect",
                "-language-version",
                codeqlLanguageVersion,
                "-api-version",
                codeqlApiVersion,
                "-Xmulti-platform",
                "-Xcommon-sources=${commonSourceFiles.joinToString(",") { it.absolutePath }}",
                "-Xexpect-actual-classes",
            ) + commonOptIns.flatMap { listOf("-opt-in", it) } + sourceFiles.map { it.absolutePath }
        }
    }

// ============================================================================
// Tasks
// ============================================================================

tasks.register("setupAndroidSdk") {
    group = "setup"
    description = "Downloads and configures the project-local Android SDK. (Alias for ensureAndroidSdk)"
    dependsOn("ensureAndroidSdk")
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
        "jsNodeTest",
        "wasmJsNodeTest",
        "wasmWasiNodeTest",
        "testAndroidHostTest",
    )
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
        val swiftBuildDir =
            layout.buildDirectory
                .dir("swift-test")
                .get()
                .asFile
                .absolutePath
        execOperations
            .exec {
                workingDir = projectDir
                commandLine(
                    "./gradlew",
                    "embedSwiftExportForXcode",
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
                        "MACOSX_DEPLOYMENT_TARGET" to "14.0",
                        "DEPLOYMENT_TARGET_SETTING_NAME" to "MACOSX_DEPLOYMENT_TARGET",
                    ),
                )
            }.assertNormalExitValue()

        val generatedPackageSwift =
            layout.buildDirectory
                .file("SPMPackage/macosArm64/Debug/Package.swift")
                .get()
                .asFile
        if (generatedPackageSwift.exists()) {
            val text = generatedPackageSwift.readText()
            if (!text.contains("platforms:")) {
                generatedPackageSwift.writeText(
                    text.replaceFirst(
                        Regex("(name:\\s*\"[^\"]*\",)"),
                        "\$1\n    platforms: [.macOS(.v14)],",
                    ),
                )
            }
        }

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
// it must mirror the kotlin { } target block exactly. watchosArm32 is the only
// retired native target (see §5.5.1); everything else MUST build.
// Do not add a dynamic tasks.matching fallback here: copied templates must make
// the target surface explicit so missing declarations fail loudly in review.
// ============================================================================
val nativeTargetNames =
    listOf(
        "androidNativeArm32",
        "androidNativeArm64",
        "androidNativeX64",
        "androidNativeX86",
        "iosArm64",
        "iosSimulatorArm64",
        "iosX64",
        "linuxArm64",
        "linuxX64",
        "macosArm64",
        "mingwX64",
        "tvosArm64",
        "tvosSimulatorArm64",
        "watchosArm64",
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
    }

tasks.named("build") {
    dependsOn(fullTargetBuildTaskNames)
}
