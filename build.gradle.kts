@file:OptIn(
    ExperimentalKotlinGradlePluginApi::class,
    KotlinNativeCacheApi::class,
    org.jetbrains.kotlin.gradle.swiftexport.ExperimentalSwiftExportDsl::class,
)

import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension
import com.android.build.api.withAndroid
import dev.detekt.gradle.Detekt
import groovy.json.JsonSlurper
import io.github.kotlinmania.build.AndroidDeviceTestVerifier
import io.github.kotlinmania.build.AndroidSdkSupport
import io.github.kotlinmania.build.BuildToolTask
import io.github.kotlinmania.build.CentralPortalSupport
import io.github.kotlinmania.build.FfiBuildSupport
import io.github.kotlinmania.build.IdeSourceSetSupport
import io.github.kotlinmania.build.NativeTestRunner
import io.github.kotlinmania.build.SwiftExportSupport
import io.github.kotlinmania.build.VerificationSupport
import org.gradle.api.GradleException
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.AbstractPublishToMaven
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.testing.AbstractTestTask
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.plugin.KotlinTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.AbstractExecutable
import org.jetbrains.kotlin.gradle.plugin.mpp.DisableCacheInKotlinVersion
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeCacheApi
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.swiftexport.SwiftExportedModuleMetadata
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootEnvSpec
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnRootEnvSpec
import org.jetbrains.kotlin.gradle.targets.web.nodejs.BaseNodeJsRootExtension
import org.jetbrains.kotlin.gradle.targets.web.yarn.BaseYarnRootExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask
import org.jlleitschuh.gradle.ktlint.reporter.ReporterType
import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.kotlinx.benchmark)
    alias(libs.plugins.kotlin.allopen)
    `maven-publish`
    signing
}

group = providers.gradleProperty("project.group").getOrElse("io.github.kotlinmania")
version = providers.gradleProperty("project.version").getOrElse("0.1.0-SNAPSHOT")
val publishProjectName = providers.gradleProperty("project.name").getOrElse("unnamed-project")
val frameworkName = providers.gradleProperty("project.frameworkName").getOrElse("Unnamed")
val projectNamespace = providers.gradleProperty("project.namespace").getOrElse("io.github.kotlinmania")
val projectCompileSdk = providers.gradleProperty("android.compileSdk").getOrElse("34")
val jvmToolchainVersion = providers.gradleProperty("jvm.toolchain").getOrElse("21").toInt()

val versionCatalog =
    extensions
        .getByType(VersionCatalogsExtension::class.java)
        .named("libs")

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

fun boolProperty(name: String, default: Boolean = false): Boolean =
    providers.gradleProperty(name).map { it.trim().lowercase() == "true" }.getOrElse(default)

// kotlinmania:common-api-bundle:v1 begin
val commonMainApiDependencyBundle =
    optionalTrimmedProperty("project.dependencies.commonMainApiBundle")
        ?.let { bundleName ->
            versionCatalog
                .findBundle(bundleName)
                .orElseThrow { GradleException("Missing public API bundle '$bundleName'") }
        }
// kotlinmania:common-api-bundle:v1 end

val commonMainDependencyBundle =
    optionalTrimmedProperty("project.dependencies.commonMainBundle")
        ?.let { bundleName ->
            versionCatalog
                .findBundle(bundleName)
                .orElseThrow { GradleException("Missing libs bundle '$bundleName'") }
        }
val commonBenchmarkDependencyBundle =
    optionalTrimmedProperty("project.dependencies.commonBenchmarkBundle")
        ?.let { bundleName ->
            versionCatalog
                .findBundle(bundleName)
                .orElseThrow { GradleException("Missing libs bundle '$bundleName'") }
        }
val commonTestDependencyBundle =
    optionalTrimmedProperty("project.dependencies.commonTestBundle")
        ?.let { bundleName ->
            versionCatalog
                .findBundle(bundleName)
                .orElseThrow { GradleException("Missing libs bundle '$bundleName'") }
        }

val enabledFeatureNames = csvProperty("project.features")
val extraTestTaskNames = csvProperty("project.test.extraTasks")

val allSupportedTargets =
    setOf(
        "jvm",
        "android",
        "macosArm64",
        "iosArm64",
        "iosSimulatorArm64",
        "tvosArm64",
        "tvosSimulatorArm64",
        "watchosDeviceArm64",
        "watchosSimulatorArm64",
        "linuxX64",
        "linuxArm64",
        "mingwX64",
        "androidNativeArm64",
        "androidNativeX64",
        "js",
        "wasmJs",
        "wasmWasi",
    )
if (optionalTrimmedProperty("project.targets") != null || optionalTrimmedProperty("project.targets.exclude") != null) {
    throw GradleException(
        "Allowlist/CSV target properties (project.targets / project.targets.exclude) are forbidden because omitted targets silently go unbuilt; " +
            "every target defaults to true and requires an explicit project.target.<name>=false override.",
    )
}
val targetOverrideProps =
    providers
        .gradlePropertiesPrefixedBy("project.target.")
        .getOrElse(emptyMap())
targetOverrideProps.forEach { (propKey, rawValue) ->
    val targetName = propKey.removePrefix("project.target.").trim()
    if (targetName !in allSupportedTargets) {
        throw GradleException("Unknown target property '$propKey' (allowed targets: $allSupportedTargets)")
    }
    val normalized = rawValue.trim().lowercase()
    if (normalized != "true" && normalized != "false") {
        throw GradleException("Property '$propKey' must be explicitly 'true' or 'false', got '$rawValue'")
    }
}
val enabledTargets: Set<String> =
    allSupportedTargets.filterTo(linkedSetOf()) { targetName ->
        // All retained targets are enabled unless gradle.properties explicitly opts out.
        targetOverrideProps["project.target.$targetName"]?.trim()?.lowercase()?.toBooleanStrict() ?: true
    }

fun targetEnabled(name: String): Boolean = name in enabledTargets

val jvmTargetEnabled = targetEnabled("jvm")
val androidTargetEnabled = targetEnabled("android")
val jsTargetEnabled = targetEnabled("js")
val wasmJsTargetEnabled = targetEnabled("wasmJs")
val wasmWasiTargetEnabled = targetEnabled("wasmWasi")
val anyWasmTargetEnabled = wasmJsTargetEnabled || wasmWasiTargetEnabled
val anyWebTargetEnabled = jsTargetEnabled || wasmJsTargetEnabled

val allNativeTargetsOrdered =
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
val enabledNativeTargets = allNativeTargetsOrdered.filter(::targetEnabled)
val enabledAppleTargets =
    listOf(
        "macosArm64",
        "iosArm64",
        "iosSimulatorArm64",
        "tvosArm64",
        "tvosSimulatorArm64",
        "watchosDeviceArm64",
        "watchosSimulatorArm64",
    ).filter(::targetEnabled)
val enabledForeignNativeTargets = listOf("linuxArm64", "linuxX64", "mingwX64").filter(::targetEnabled)
val enabledAndroidNativeTargets = listOf("androidNativeArm64", "androidNativeX64").filter(::targetEnabled)
val benchmarkEnabled = "benchmark" in enabledFeatureNames
val benchmarkTargetNames = csvProperty("project.benchmark.targets")
if (benchmarkEnabled && commonBenchmarkDependencyBundle == null) {
    throw GradleException("Feature 'benchmark' requires project.dependencies.commonBenchmarkBundle")
}
val benchmarkWarmups = providers.gradleProperty("project.benchmark.warmups").map { it.toInt() }.getOrElse(3)
val benchmarkIterations = providers.gradleProperty("project.benchmark.iterations").map { it.toInt() }.getOrElse(5)
val benchmarkIterationTime = providers.gradleProperty("project.benchmark.iterationTime").map { it.toLong() }.getOrElse(1L)
val benchmarkIterationTimeUnit = providers.gradleProperty("project.benchmark.iterationTimeUnit").getOrElse("s")

val nativeFfiEnabled = boolProperty("project.ffi.native")
val nativeFfiTargetNames = csvProperty("project.ffi.native.targets")
val nativeFfiCompileWrapper = boolProperty("project.ffi.native.compileWrapper", true)
val jvmFfiEnabled = boolProperty("project.ffi.jvm")
val napiFfiEnabled = boolProperty("project.ffi.napi")

val ffiNameRaw = providers.gradleProperty("project.ffi.name").getOrElse("Ffi").trim()
val ffiName = ffiNameRaw.replaceFirstChar { it.uppercase() }
val ffiNameLower = ffiNameRaw.lowercase()
val ffiDefFile = "src/native/interop/cinterop/$ffiName.def"
val ffiWrapperBase = "${ffiNameLower}_wrapper"
val ffiNapiDir = "src/js/node-$ffiNameLower/dist"
val ffiNpmPackage = "@kotlinmania/$ffiNameLower-native-bindings"

if (nativeFfiEnabled || jvmFfiEnabled || napiFfiEnabled) {
    println("FFI channels enabled: native=$nativeFfiEnabled jvm=$jvmFfiEnabled napi=$napiFfiEnabled name=$ffiName")
}

// ============================================================================
// Swift Export & Native Binary Configuration (gradle.properties)
// ----------------------------------------------------------------------------
// Kotlin 2.4.x (2.4.21) vs. Kotlin 2.5.0 (`Kotlin/swift-export-sample`):
// - KGP 2.4.21 uses `kotlin { swiftExport {} }` (`SwiftExportedModuleMetadata.flattenPackage`,
//   forwarded by `SwiftExportAction` as `SwiftModuleConfig.rootPackage`).
// - KGP 2.5.0 renames `flattenPackage` to `rootPackage` under `kotlin { export { swift {} } }`.
// - We alias `SwiftExportedModuleMetadata.rootPackage` to `flattenPackage` below.
// ============================================================================
val swiftExportModuleName = optionalTrimmedProperty("project.swiftExport.moduleName") ?: frameworkName
val swiftExportRootPackage =
    optionalTrimmedProperty("project.swiftExport.rootPackage")
        ?: optionalTrimmedProperty("project.swiftExport.flattenPackage")
        ?: projectNamespace

val SwiftExportedModuleMetadata.rootPackage: org.gradle.api.provider.Property<String>
    get() = flattenPackage

val swiftExportEnableCoroutinesSupport =
    providers
        .gradleProperty("project.swiftExport.enableCoroutinesSupport")
        .getOrElse("true")
        .trim()
val swiftExportSettings =
    providers
        .gradlePropertiesPrefixedBy("project.swiftExport.settings.")
        .getOrElse(emptyMap())
val swiftExportFreeCompilerArgs = csvProperty("project.swiftExport.freeCompilerArgs")
val swiftExportLinkFreeCompilerArgs = csvProperty("project.swiftExport.linkTask.freeCompilerArgs")
val nativeReleaseLinkFreeCompilerArgs = csvProperty("project.native.releaseLinkTask.freeCompilerArgs")
val swiftExportExportApiBundle = boolProperty("project.swiftExport.exportApiBundle", false)
val swiftExportHiddenDependencies = csvProperty("project.swiftExport.hidden")
val swiftExportEnabled =
    targetEnabled("macosArm64") &&
        (optionalTrimmedProperty("project.swiftExport.enabled")?.let { it.lowercase() == "true" } ?: true)
val swiftExportEmbeddableVersion =
    providers
        .gradleProperty("versions.swiftExportEmbeddable")
        .getOrElse("2.4.20-km.5")
        .trim()
val appleFrameworkEnabled = boolProperty("project.apple.framework.enabled", true)
val appleFrameworkStatic = boolProperty("project.apple.framework.static", false)
val nativeSharedLibEnabled = boolProperty("project.native.sharedLib", true)
val nativeExecutableEntryPoint = optionalTrimmedProperty("project.native.executable.entryPoint")
val nativeExecutableBaseName = optionalTrimmedProperty("project.native.executable.baseName") ?: publishProjectName
val nativeExecutableTargetNames =
    csvProperty("project.native.executable.targets").ifEmpty {
        setOf("macosArm64", "linuxX64", "linuxArm64", "mingwX64")
    }

enum class SwiftExportModuleVisibility {
    EXPOSED,
    HIDDEN,
}

data class SwiftExportModuleSpec(
    val key: String,
    val dependency: Any,
    val moduleName: String,
    val rootPackage: String,
    val visibility: SwiftExportModuleVisibility,
)

fun deriveSwiftModuleName(rawName: String): String =
    rawName
        .removePrefix(":")
        .substringAfterLast(":")
        .removeSuffix("-kotlin")
        .split('-', '_', '.')
        .filter { it.isNotEmpty() }
        .joinToString("") { part -> part.replaceFirstChar { it.uppercase() } }

fun parseSwiftExportModuleSpec(rawEntry: String): SwiftExportModuleSpec {
    val parts = rawEntry.split("|").map { it.trim() }
    val rawDep = parts.firstOrNull().orEmpty()
    if (rawDep.isEmpty()) {
        throw GradleException("Malformed project.swiftExport.exports entry: '$rawEntry'")
    }
    val resolvedDep: Any =
        when {
            rawDep.startsWith(":") -> {
                project(rawDep)
            }

            ":" in rawDep -> {
                rawDep
            }

            else -> {
                val dep =
                    versionCatalog
                        .findLibrary(rawDep)
                        .orElseThrow {
                            GradleException(
                                "Unknown Swift Export library alias '$rawDep' in libs.versions.toml " +
                                    "(use a catalog alias, 'group:artifact:version', or ':subproject')",
                            )
                        }.get()
                listOfNotNull(
                    dep.module.group,
                    dep.module.name,
                    dep.versionConstraint.requiredVersion.takeIf { it.isNotEmpty() },
                ).joinToString(":")
            }
        }
    val explicitVisibility =
        when (val rawVis = parts.getOrNull(3)?.uppercase().orEmpty()) {
            "", "EXPOSED" -> SwiftExportModuleVisibility.EXPOSED
            "HIDDEN" -> SwiftExportModuleVisibility.HIDDEN
            else -> throw GradleException("Unknown Swift Export visibility '$rawVis' in '$rawEntry' (expected EXPOSED or HIDDEN)")
        }
    val visibility =
        if (rawDep in swiftExportHiddenDependencies) {
            SwiftExportModuleVisibility.HIDDEN
        } else {
            explicitVisibility
        }
    val moduleName = parts.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: deriveSwiftModuleName(rawDep)
    return SwiftExportModuleSpec(
        key = rawDep,
        dependency = resolvedDep,
        moduleName = moduleName,
        rootPackage = parts.getOrNull(2).orEmpty(),
        visibility = visibility,
    )
}

val swiftExportModuleExports: List<SwiftExportModuleSpec> =
    buildList {
        val explicitSpecs = LinkedHashMap<String, SwiftExportModuleSpec>()
        csvProperty("project.swiftExport.exports").forEach { entry ->
            val spec = parseSwiftExportModuleSpec(entry)
            explicitSpecs[spec.key] = spec
        }
        providers
            .gradlePropertiesPrefixedBy("project.swiftExport.export.")
            .getOrElse(emptyMap())
            .toSortedMap()
            .forEach { (_, entry) ->
                val trimmed = entry.trim()
                if (trimmed.isNotEmpty()) {
                    val spec = parseSwiftExportModuleSpec(trimmed)
                    explicitSpecs[spec.key] = spec
                }
            }
        if (swiftExportExportApiBundle && commonMainApiDependencyBundle != null) {
            commonMainApiDependencyBundle.get().forEach { dep ->
                val coordKey = "${dep.module.group}:${dep.module.name}"
                val artifactKey = dep.module.name
                if (coordKey !in explicitSpecs && artifactKey !in explicitSpecs) {
                    val isHidden = coordKey in swiftExportHiddenDependencies || artifactKey in swiftExportHiddenDependencies
                    val derivedModule = deriveSwiftModuleName(artifactKey)
                    val derivedPackage = "${dep.module.group}.${artifactKey.removeSuffix("-kotlin").replace("-", "").replace("_", "")}"
                    val coordinate =
                        listOfNotNull(
                            dep.module.group,
                            dep.module.name,
                            dep.versionConstraint.requiredVersion.takeIf { it.isNotEmpty() },
                        ).joinToString(":")
                    explicitSpecs[coordKey] =
                        SwiftExportModuleSpec(
                            key = coordKey,
                            dependency = coordinate,
                            moduleName = derivedModule,
                            rootPackage = derivedPackage,
                            visibility = if (isHidden) SwiftExportModuleVisibility.HIDDEN else SwiftExportModuleVisibility.EXPOSED,
                        )
                }
            }
        }
        addAll(explicitSpecs.values)
    }

val projectKonanDataDirectory =
    providers
        .environmentVariable("KONAN_DATA_DIR")
        .map {
            require(it.isNotBlank()) { "KONAN_DATA_DIR must not be blank" }
            File(it).absoluteFile
        }.orElse(providers.systemProperty("user.home").map { File(it, ".konan") })

val projectDependencyHandler = project.dependencies
configurations.configureEach {
    if (name.contains("androidDeviceTest", ignoreCase = true)) {
        exclude(group = "org.jetbrains", module = "annotations")
    }
    if (name == "swiftExportClasspath") {
        dependencies.add(projectDependencyHandler.create("io.github.kotlinmania:swift-export-embeddable:$swiftExportEmbeddableVersion"))
    }
}

val browserTestExcludes =
    providers
        .gradleProperty("project.js.browserTestExcludes")
        .getOrElse("")
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }

val wasmWasiWindowsTestExcludes =
    providers
        .gradleProperty("project.wasmWasi.windowsTestExcludes")
        .getOrElse("")
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }

val extraKotlinCompilerArgs =
    providers
        .gradleProperty("project.kotlin.freeCompilerArgs")
        .getOrElse("")
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }

val commonOptIns =
    listOf(
        "kotlin.time.ExperimentalTime",
        "kotlin.concurrent.atomics.ExperimentalAtomicApi",
        "kotlin.ExperimentalUnsignedTypes",
    )

// Install and configure project-local Android SDK before evaluating `kotlin { android {} }`.
if (androidTargetEnabled) {
    apply(
        plugin =
            libs.plugins.android.kotlin.multiplatform.library
                .get()
                .pluginId,
    )
}
if (androidTargetEnabled || enabledAndroidNativeTargets.isNotEmpty()) {
    AndroidSdkSupport.configure(project)
}

tasks.withType<KotlinCompilationTask<*>>().configureEach {
    compilerOptions.allWarningsAsErrors.set(true)
    doFirst {
        check(compilerOptions.allWarningsAsErrors.get()) { "Warnings-as-errors disabled for $path" }
    }
}

// ============================================================================
// kotlin { … } — Modeled on Kotlin/multiplatform-library-template
// ============================================================================
kotlin {
    jvmToolchain(jvmToolchainVersion)

    // Default KMP hierarchy template augmented with custom intermediate groups:
    //   common -> hostSystem -> jvm / android / web (js, wasmJs) / native (mingw, posix -> apple / linux / androidNative)
    //   common -> wasmWasi
    applyDefaultHierarchyTemplate {
        common {
            group("hostSystem") {
                withJvm()
                if (androidTargetEnabled) {
                    withAndroid()
                }
                group("web") {
                    withJs()
                    withWasmJs()
                }
                group("native") {
                    group("posix") {
                        group("apple")
                        group("linux")
                        group("androidNative")
                    }
                }
            }
        }
    }

    compilerOptions {
        languageVersion.set(KotlinVersion.KOTLIN_2_4)
        apiVersion.set(KotlinVersion.KOTLIN_2_4)
        allWarningsAsErrors.set(true)
        optIn.addAll(commonOptIns)
        freeCompilerArgs.addAll("-Xexpect-actual-classes", "-Xsuppress-version-warnings")
        freeCompilerArgs.addAll(extraKotlinCompilerArgs)
    }

    val xcf = if (appleFrameworkEnabled && enabledAppleTargets.isNotEmpty()) XCFramework(frameworkName) else null
    val frameworkBundleId = projectNamespace

    fun KotlinNativeTarget.addToXcf(static: Boolean = appleFrameworkStatic) {
        if (!appleFrameworkEnabled) return
        binaries.framework {
            baseName = frameworkName
            if (static) isStatic = true
            xcf?.add(this)
            binaryOption("bundleId", frameworkBundleId)
        }
    }

    fun KotlinNativeTarget.addSharedLib() {
        if (!nativeSharedLibEnabled) return
        binaries.sharedLib {
            baseName = frameworkName
        }
    }

    fun KotlinNativeTarget.addNativeCliExecutable() {
        val entry = nativeExecutableEntryPoint ?: return
        if (name !in nativeExecutableTargetNames) return
        binaries.executable {
            baseName = nativeExecutableBaseName
            entryPoint = entry
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

    // JVM
    if (jvmTargetEnabled) {
        jvm {
            configureBenchmarkCompilation()
            compilerOptions {
                jvmTarget.set(JvmTarget.fromTarget(jvmToolchainVersion.toString()))
            }
        }
    }

    // Android KMP library
    if (androidTargetEnabled) {
        (this as org.gradle.api.plugins.ExtensionAware)
            .extensions
            .configure<KotlinMultiplatformAndroidLibraryTarget>("android") {
                namespace = projectNamespace
                compileSdk = projectCompileSdk.toInt()
                minSdk = providers.gradleProperty("android.minSdk").getOrElse("24").toInt()
                withHostTest {}
                withDeviceTestBuilder { sourceSetTreeName = "test" }.configure {}
            }
    }

    // Apple — Tier 1/2 targets
    if (targetEnabled("macosArm64")) {
        macosArm64 {
            configureBenchmarkCompilation()
            addToXcf()
            addSharedLib()
            addNativeCliExecutable()
        }
    }
    if (targetEnabled("iosArm64")) {
        iosArm64 {
            configureBenchmarkCompilation()
            addToXcf()
            addNativeCliExecutable()
        }
    }
    if (targetEnabled("iosSimulatorArm64")) {
        iosSimulatorArm64 {
            configureBenchmarkCompilation()
            addToXcf()
            addNativeCliExecutable()
        }
    }
    if (targetEnabled("tvosArm64")) {
        tvosArm64 {
            configureBenchmarkCompilation()
            addToXcf()
            addNativeCliExecutable()
        }
    }
    if (targetEnabled("tvosSimulatorArm64")) {
        tvosSimulatorArm64 {
            configureBenchmarkCompilation()
            addToXcf()
            addNativeCliExecutable()
        }
    }
    if (targetEnabled("watchosDeviceArm64")) {
        watchosDeviceArm64 {
            configureBenchmarkCompilation()
            addToXcf()
            addNativeCliExecutable()
        }
    }
    if (targetEnabled("watchosSimulatorArm64")) {
        watchosSimulatorArm64 {
            configureBenchmarkCompilation()
            addToXcf()
            addNativeCliExecutable()
        }
    }

    // Other native — Tier 1/2
    if (targetEnabled("linuxX64")) {
        linuxX64 {
            configureBenchmarkCompilation()
            addSharedLib()
            addNativeCliExecutable()
        }
    }
    if (targetEnabled("linuxArm64")) {
        linuxArm64 {
            configureBenchmarkCompilation()
            addSharedLib()
            addNativeCliExecutable()
        }
    }
    if (targetEnabled("mingwX64")) {
        mingwX64 {
            configureBenchmarkCompilation()
            addSharedLib()
            addNativeCliExecutable()
            binaries.withType<AbstractExecutable>().configureEach {
                // Match Linux, macOS, and Rust libtest 8 MiB stack reserve instead of MinGW's 1 MiB PE default.
                linkerOpts("-Wl,--stack,8388608")
            }
        }
    }

    // Android NDK — 64-bit only
    if (targetEnabled("androidNativeArm64")) {
        androidNativeArm64 {
            configureBenchmarkCompilation()
            addSharedLib()
            addNativeCliExecutable()
        }
    }
    if (targetEnabled("androidNativeX64")) {
        androidNativeX64 {
            configureBenchmarkCompilation()
            addSharedLib()
            addNativeCliExecutable()
        }
    }

    // Web (Development + Production library distributions)
    if (jsTargetEnabled) {
        js {
            configureBenchmarkCompilation()
            binaries.library()
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
    }

    if (wasmJsTargetEnabled) {
        @OptIn(ExperimentalWasmDsl::class)
        wasmJs {
            configureBenchmarkCompilation()
            binaries.library()
            browser {
                testTask {
                    browserTestExcludes.forEach { filter.excludeTestsMatching(it) }
                }
            }
            nodejs()
        }
    }

    if (wasmWasiTargetEnabled) {
        @OptIn(ExperimentalWasmDsl::class)
        wasmWasi {
            configureBenchmarkCompilation()
            binaries.library()
            nodejs {
                testTask {
                    if (System.getProperty("os.name", "").startsWith("Windows")) {
                        wasmWasiWindowsTestExcludes.forEach { filter.excludeTestsMatching(it) }
                    }
                }
            }
        }
    }

    if (swiftExportEnabled) {
        swiftExport {
            moduleName = swiftExportModuleName
            rootPackage = swiftExportRootPackage
            configure {
                settings.put("enableCoroutinesSupport", swiftExportEnableCoroutinesSupport)
                swiftExportSettings.forEach { (key, value) ->
                    settings.put(key, value)
                }
                if (swiftExportFreeCompilerArgs.isNotEmpty()) {
                    freeCompilerArgs.addAll(swiftExportFreeCompilerArgs)
                }
            }
            linkTask {
                if (swiftExportLinkFreeCompilerArgs.isNotEmpty()) {
                    toolOptions.freeCompilerArgs.addAll(swiftExportLinkFreeCompilerArgs)
                }
                if ((optimized || name.contains("Release", ignoreCase = true)) && nativeReleaseLinkFreeCompilerArgs.isNotEmpty()) {
                    toolOptions.freeCompilerArgs.addAll(nativeReleaseLinkFreeCompilerArgs)
                }
            }
            swiftExportModuleExports
                .filter { it.visibility == SwiftExportModuleVisibility.EXPOSED }
                .forEach { exported ->
                    export(exported.dependency) {
                        if (exported.moduleName.isNotEmpty()) {
                            moduleName = exported.moduleName
                        }
                        if (exported.rootPackage.isNotEmpty()) {
                            rootPackage = exported.rootPackage
                        }
                    }
                }
        }

        tasks
            .withType<org.jetbrains.kotlin.gradle.tasks.KotlinNativeCompile>()
            .matching { it.name.startsWith("compileSwiftExportMain") }
            .configureEach {
                // KGP's generated SwiftExport bridge files emit type-erased generic casts
                // (dereferenceExternalRCRef(self) as GenericType<Any?>) without @Suppress("UNCHECKED_CAST").
                // Disable UNCHECKED_CAST on KGP's generated bridge only while keeping
                // allWarningsAsErrors = true for all source compilations and all other bridge diagnostics.
                compilerOptions.freeCompilerArgs.add("-Xwarning-level=UNCHECKED_CAST:disabled")
            }
    }

    targets.withType<KotlinNativeTarget>().configureEach {
        if (konanTarget.family.isAppleFamily && konanTarget.name != "watchos_device_arm64") {
            binaries.configureEach {
                val isStaticArchive =
                    this is org.jetbrains.kotlin.gradle.plugin.mpp.StaticLibrary ||
                        (this is org.jetbrains.kotlin.gradle.plugin.mpp.Framework && isStatic)
                if (buildType == org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType.DEBUG && !isStaticArchive) {
                    // Kotlin/Native 2.4.21's stdlib-per-file-cache archives emit Mach-O N_AST stabs
                    // ('...a(...a.o)') under Xcode 26 ld, causing dsymutil 'could not open' warnings
                    // on every Debug link while retaining full DWARF symbols when cache is disabled.
                    disableNativeCache(
                        version = DisableCacheInKotlinVersion.`2_4_21`,
                        reason = "Prevent Xcode 26 dsymutil N_AST 'could not open' warnings from stdlib-per-file-cache",
                    )
                }
            }
        }
    }

    if (nativeReleaseLinkFreeCompilerArgs.isNotEmpty()) {
        tasks
            .withType<org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink>()
            .matching { it.optimized && !it.name.startsWith("linkSwiftExportBinary") }
            .configureEach {
                toolOptions.freeCompilerArgs.addAll(nativeReleaseLinkFreeCompilerArgs)
            }
    }

    // Native FFI — Kotlin/Native cinterop + per-target static wrapper library
    if (nativeFfiEnabled) {
        val configuredNativeTargets = targets.withType<KotlinNativeTarget>().map { it.name }.toSet()
        check(nativeFfiTargetNames.all { it in configuredNativeTargets }) {
            "Unknown project.ffi.native.targets: ${nativeFfiTargetNames - configuredNativeTargets}"
        }
        val discoveredDefFiles =
            project
                .fileTree("src/native/interop/cinterop") { include("*.def") }
                .files
                .sortedBy { it.name }
                .ifEmpty { listOf(project.file(ffiDefFile)) }
        targets
            .withType<KotlinNativeTarget>()
            .matching {
                nativeFfiTargetNames.isEmpty() || it.name in nativeFfiTargetNames
            }.configureEach {
                val konanTargetName = konanTarget.name
                val targetCapitalized = name.replaceFirstChar { it.uppercase() }
                val wrapperTask =
                    if (nativeFfiCompileWrapper) {
                        FfiBuildSupport.registerNativeWrapperTask(
                            project,
                            ffiName,
                            ffiWrapperBase,
                            konanTargetName,
                            targetCapitalized,
                            projectKonanDataDirectory.get(),
                        )
                    } else {
                        null
                    }

                compilations.getByName("main") {
                    cinterops {
                        discoveredDefFiles.forEach { currentDefFile ->
                            val defBaseName = currentDefFile.nameWithoutExtension
                            val cinteropName =
                                if (discoveredDefFiles.size == 1 && currentDefFile == project.file(ffiDefFile)) {
                                    ffiNameLower
                                } else {
                                    defBaseName.replaceFirstChar { it.lowercase() }
                                }
                            val explicitPkg =
                                if (currentDefFile.isFile) {
                                    currentDefFile
                                        .useLines { lines ->
                                            lines
                                                .map { it.trim() }
                                                .firstOrNull { it.startsWith("package") && '=' in it }
                                                ?.substringAfter('=')
                                                ?.trim()
                                                ?.takeIf { it.isNotEmpty() }
                                        }
                                } else {
                                    null
                                }
                            create(cinteropName) {
                                defFile(currentDefFile)
                                packageName(explicitPkg ?: "$ffiNameLower.cinterop")
                                if (wrapperTask != null) {
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
                    }
                }
                if (wrapperTask != null) {
                    tasks
                        .matching { it.name.startsWith("cinterop") && it.name.endsWith(targetCapitalized) }
                        .configureEach {
                            dependsOn(wrapperTask)
                            inputs.files(wrapperTask)
                        }
                }
            }
    }

    // Source set hierarchy and per-source-set dependency declarations aligned with official Kotlin docs:
    //   https://kotlinlang.org/docs/multiplatform/multiplatform-hierarchy.html
    //   https://kotlinlang.org/docs/multiplatform/multiplatform-advanced-project-structure.html#dependson-and-source-set-hierarchies
    sourceSets {
        configureEach {
            val relPath = IdeSourceSetSupport.sourceSetDir(name)
            kotlin.setSrcDirs(listOf("$relPath/kotlin"))
            resources.setSrcDirs(listOf("$relPath/resources"))
        }
        val projectNsPath = projectNamespace.replace(".", "/")
        commonMain.dependencies {
            commonMainDependencyBundle?.let { implementation(it) }
            commonMainApiDependencyBundle?.let { api(it) }
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            commonTestDependencyBundle?.let { bundle ->
                bundle.get().forEach { dep ->
                    val coordinate =
                        listOfNotNull(
                            dep.module.group,
                            dep.module.name,
                            dep.versionConstraint.requiredVersion.takeIf { it.isNotEmpty() },
                        ).joinToString(":")
                    implementation(coordinate) {
                        exclude(group = project.group.toString(), module = publishProjectName)
                    }
                }
            }
        }

        findByName("hostSystemMain")?.dependencies {}
        findByName("hostSystemTest")?.dependencies {}

        if (jvmTargetEnabled) {
            jvmMain {
                if (!jvmFfiEnabled) {
                    csvProperty("project.ffi.jvm.kotlinExcludes").forEach { kotlin.exclude(it) }
                    kotlin.exclude("$projectNsPath/internal/FfiJni.kt")
                }
                dependencies {}
            }
            jvmTest.dependencies {}
        }

        if (androidTargetEnabled) {
            androidMain.dependencies {}
            named("androidHostTest").dependencies {}
            named("androidDeviceTest").dependencies {
                implementation(libs.androidx.test.runner)
            }
        }

        if (anyWebTargetEnabled) {
            findByName("webMain")?.dependencies {}
            findByName("webTest")?.dependencies {}
        }
        if (jsTargetEnabled) {
            jsMain {
                if (!napiFfiEnabled) {
                    csvProperty("project.ffi.napi.kotlinExcludes").forEach { kotlin.exclude(it) }
                    val bindingSources = csvProperty("project.ffi.napi.kotlinSources")
                    if (bindingSources.isEmpty()) {
                        kotlin.exclude("$projectNsPath/${ffiName}NativeBindings.kt")
                    } else {
                        bindingSources.forEach { kotlin.exclude(it) }
                    }
                }
                dependencies {}
            }
            jsTest.dependencies {}
        }
        if (wasmJsTargetEnabled) {
            wasmJsMain.dependencies {}
            wasmJsTest.dependencies {}
        }
        if (wasmWasiTargetEnabled) {
            wasmWasiMain.dependencies {}
            wasmWasiTest.dependencies {}
        }

        listOf(
            "nativeMain",
            "nativeTest",
            "posixMain",
            "posixTest",
            "appleMain",
            "appleTest",
            "iosMain",
            "iosTest",
            "iosArm64Main",
            "iosArm64Test",
            "iosSimulatorArm64Main",
            "iosSimulatorArm64Test",
            "macosMain",
            "macosTest",
            "macosArm64Main",
            "macosArm64Test",
            "tvosMain",
            "tvosTest",
            "tvosArm64Main",
            "tvosArm64Test",
            "tvosSimulatorArm64Main",
            "tvosSimulatorArm64Test",
            "watchosMain",
            "watchosTest",
            "watchosDeviceArm64Main",
            "watchosDeviceArm64Test",
            "watchosSimulatorArm64Main",
            "watchosSimulatorArm64Test",
            "linuxMain",
            "linuxTest",
            "linuxX64Main",
            "linuxX64Test",
            "linuxArm64Main",
            "linuxArm64Test",
            "androidNativeMain",
            "androidNativeTest",
            "androidNativeArm64Main",
            "androidNativeArm64Test",
            "androidNativeX64Main",
            "androidNativeX64Test",
            "mingwMain",
            "mingwTest",
            "mingwX64Main",
            "mingwX64Test",
        ).forEach { ssName ->
            findByName(ssName)?.dependencies {}
        }

        providers
            .gradlePropertiesPrefixedBy("project.dependencies.sourceSet.")
            .getOrElse(emptyMap())
            .forEach { (propKey, rawAliases) ->
                val ssName = propKey.removePrefix("project.dependencies.sourceSet.").trim()
                val aliases =
                    rawAliases
                        .split(",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                if (aliases.isNotEmpty()) {
                    findByName(ssName)?.dependencies {
                        aliases.forEach { aliasName ->
                            val dep =
                                versionCatalog
                                    .findLibrary(aliasName)
                                    .orElseThrow {
                                        GradleException(
                                            "Unknown library alias '$aliasName' in $propKey (check libs.versions.toml)",
                                        )
                                    }
                            implementation(dep)
                        }
                    }
                }
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

IdeSourceSetSupport.configure(project)

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
// Test logging, Detekt & Ktlint
// ============================================================================
tasks.withType<AbstractTestTask>().configureEach {
    ignoreFailures = false
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

detekt {
    buildUponDefaultConfig.set(true)
    allRules.set(false)
    autoCorrect.set(false)
    source.setFrom(files("src"))
    config.setFrom(files("detekt.yml"))
    parallel.set(true)
}

tasks.withType<Detekt>().configureEach {
    mustRunAfter(tasks.withType<KotlinCompilationTask<*>>())
    reports {
        html.required.set(true)
        sarif.required.set(true)
        checkstyle.required.set(false)
    }
}

tasks
    .matching { it.name.startsWith("runKtlint") || it.name.startsWith("ktlint") }
    .configureEach {
        mustRunAfter(tasks.withType<KotlinCompilationTask<*>>())
    }

ktlint {
    version.set(libs.versions.ktlintEngine.get())
    debug.set(false)
    verbose.set(false)
    android.set(false)
    outputToConsole.set(true)
    ignoreFailures.set(false)
    reporters {
        reporter(ReporterType.CHECKSTYLE)
        reporter(ReporterType.SARIF)
    }
    filter {
        exclude("**/build/**")
        include("**/src/**/kotlin/**")
    }
}

if (benchmarkEnabled) {
    tasks
        .withType<Detekt>()
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

// ============================================================================
// JS / Wasm / WASI Toolchain Pins & Test Runners
// ============================================================================
val nodeMaxOldSpaceSizeMb = providers.gradleProperty("node.maxOldSpaceSizeMb").getOrElse("4096")
val nodeHeapArg = "--max-old-space-size=$nodeMaxOldSpaceSizeMb"
tasks.withType<org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest>().configureEach {
    nodeJsArgs += nodeHeapArg
}
tasks.withType<org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpack>().configureEach {
    nodeArgs += nodeHeapArg
}

val nodeVersion = providers.gradleProperty("node.version").getOrElse("24.22.0")
val wasmNodeVersion = providers.gradleProperty("wasm.node.version").getOrElse(nodeVersion)
val yarnVersion = providers.gradleProperty("yarn.version").getOrElse("1.22.22")
val wasmYarnVersion = providers.gradleProperty("wasm.yarn.version").getOrElse(yarnVersion)

@Suppress("UNCHECKED_CAST")
val webpackVersion: String =
    if (jsTargetEnabled || anyWasmTargetEnabled) {
        (JsonSlurper().parse(rootProject.file("kotlin-js-store/package.json")) as Map<String, Any>)
            .let { it["dependencies"] as Map<String, Any> }["webpack"] as String
    } else {
        ""
    }

if (jsTargetEnabled) {
    rootProject.extensions.configure<NodeJsEnvSpec>("kotlinNodeJsSpec") { version.set(nodeVersion) }
    rootProject.extensions.configure<YarnRootEnvSpec>("kotlinYarnSpec") {
        version.set(yarnVersion)
        yarnLockMismatchReport.set(org.jetbrains.kotlin.gradle.targets.js.yarn.YarnLockMismatchReport.WARNING)
        yarnLockAutoReplace.set(true)
    }
}
if (anyWasmTargetEnabled) {
    rootProject.extensions.configure<WasmNodeJsEnvSpec>("kotlinWasmNodeJsSpec") { version.set(wasmNodeVersion) }
    rootProject.extensions.configure<WasmYarnRootEnvSpec>("kotlinWasmYarnSpec") {
        version.set(wasmYarnVersion)
        yarnLockMismatchReport.set(org.jetbrains.kotlin.gradle.targets.js.yarn.YarnLockMismatchReport.WARNING)
        yarnLockAutoReplace.set(true)
    }
}

val wasmNpmVersionPins: Map<*, *> =
    if (anyWasmTargetEnabled) {
        (JsonSlurper().parse(rootProject.file("kotlin-js-store/wasm/package.json")) as Map<*, *>)
            .let { it["dependencies"] as Map<*, *> }
    } else {
        emptyMap<String, String>()
    }

val activeYarnExtensionNames =
    buildList {
        if (jsTargetEnabled) add("kotlinYarn")
        if (anyWasmTargetEnabled) add("kotlinWasmYarn")
    }

activeYarnExtensionNames.forEach { extensionName ->
    rootProject.extensions.configure<BaseYarnRootExtension>(extensionName) {
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
        resolution("webpack", webpackVersion)
        resolution("**/webpack", webpackVersion)
        if (extensionName == "kotlinWasmYarn") {
            wasmNpmVersionPins.forEach { (pkg, version) ->
                resolution(pkg as String, version as String)
                resolution("**/$pkg", version)
            }
        }
    }
}

if (jsTargetEnabled) {
    rootProject.tasks.named("rootPackageJson") {
        inputs.property("yarnResolutions", providers.gradlePropertiesPrefixedBy("yarn.resolution."))
        inputs.property("webpackVersion", webpackVersion)
    }
}
if (anyWasmTargetEnabled) {
    rootProject.tasks.named("wasmRootPackageJson") {
        inputs.property("yarnResolutions", providers.gradlePropertiesPrefixedBy("yarn.resolution."))
        inputs.property("webpackVersion", webpackVersion)
        inputs.file(rootProject.file("kotlin-js-store/wasm/package.json"))
    }
}

// Disable npm package-lock tasks since this project uses Yarn; prevents IntelliJ IDEA's
// post-sync lockfile update hook from failing on missing build/js/package-lock.json.
tasks
    .matching {
        it.name == "kotlinUpgradePackageLock" ||
            it.name == "kotlinStorePackageLock" ||
            it.name == "kotlinWasmUpgradePackageLock" ||
            it.name == "kotlinWasmStorePackageLock"
    }.configureEach {
        enabled = false
    }

val patchedKarmaWebpackPackage =
    rootProject.layout.projectDirectory
        .dir("gradle/npm/karma-webpack")
        .asFile.absolutePath
        .replace("\\", "/")

val activeNodeExtensionNames =
    buildList {
        if (jsTargetEnabled) add("kotlinNodeJs")
        if (anyWasmTargetEnabled) add("kotlinWasmNodeJs")
    }

activeNodeExtensionNames.forEach { extensionName ->
    rootProject.extensions.configure<BaseNodeJsRootExtension>(extensionName) {
        versions.webpack.version = webpackVersion
        versions.webpackCli.version = providers.gradleProperty("node.webpackCli.version").getOrElse("7.2.3")
        versions.karma.version = providers.gradleProperty("node.karma.version").getOrElse("npm:karma-maintained@6.4.8")
        versions.karmaWebpack.version = "file:$patchedKarmaWebpackPackage"
        versions.mocha.version = providers.gradleProperty("node.mocha.version").getOrElse("12.0.0-beta-10")
        versions.kotlinWebHelpers.version = providers.gradleProperty("node.kotlinWebHelpers.version").getOrElse("3.1.0")
    }
}

val jsTasksNeedingYarnLock =
    setOf(
        "compileKotlinJs",
        "compileTestKotlinJs",
        "compileDevelopmentLibraryKotlinJs",
        "compileProductionLibraryKotlinJs",
        "jsProcessResources",
        "jsTestProcessResources",
        "jsNodeTest",
        "jsBrowserTest",
    )

if (jsTargetEnabled) {
    tasks.matching { it.name in jsTasksNeedingYarnLock }.configureEach {
        dependsOn(rootProject.tasks.named("kotlinUpgradeYarnLock"))
    }
    rootProject.tasks.matching { it.name == "kotlinStoreYarnLock" }.configureEach {
        dependsOn(rootProject.tasks.named("kotlinUpgradeYarnLock"))
    }
}

val wasmTasksNeedingYarnLock =
    setOf(
        "compileKotlinWasmJs",
        "compileTestKotlinWasmJs",
        "compileDevelopmentLibraryKotlinWasmJs",
        "compileProductionLibraryKotlinWasmJs",
        "wasmJsProcessResources",
        "wasmJsTestProcessResources",
        "wasmJsNodeTest",
        "wasmJsBrowserTest",
        "compileKotlinWasmWasi",
        "compileTestKotlinWasmWasi",
        "compileDevelopmentLibraryKotlinWasmWasi",
        "compileProductionLibraryKotlinWasmWasi",
        "wasmWasiProcessResources",
        "wasmWasiTestProcessResources",
        "wasmWasiNodeTest",
    )

if (anyWasmTargetEnabled) {
    tasks.matching { it.name in wasmTasksNeedingYarnLock }.configureEach {
        dependsOn(rootProject.tasks.named("kotlinWasmUpgradeYarnLock"))
    }
    rootProject.tasks.matching { it.name == "kotlinWasmStoreYarnLock" }.configureEach {
        dependsOn(rootProject.tasks.named("kotlinWasmUpgradeYarnLock"))
    }
}

// KGP's DevelopmentLibrary and ProductionLibrary sync into the same package directory;
// order ProductionLibrary tasks after DevelopmentLibrary tasks to satisfy Gradle 9 task validation.
listOf("js", "wasmJs", "wasmWasi").filter(::targetEnabled).forEach { targetPrefix ->
    val devSyncTasks =
        tasks.matching {
            it.name == "${targetPrefix}DevelopmentLibraryCompileSync" ||
                it.name.startsWith("${targetPrefix}NodeDevelopmentLibrary") ||
                it.name.startsWith("${targetPrefix}BrowserDevelopmentLibrary")
        }
    tasks
        .matching {
            it.name == "${targetPrefix}ProductionLibraryCompileSync" ||
                it.name.startsWith("${targetPrefix}NodeProductionLibrary") ||
                it.name.startsWith("${targetPrefix}BrowserProductionLibrary")
        }.configureEach {
            mustRunAfter(devSyncTasks)
        }
}

// ============================================================================
// JNI & N-API FFI Tasks
// ============================================================================
FfiBuildSupport.configureHostTestDependencyNatives(project, jvmFfiEnabled, ffiName)

if (jvmFfiEnabled && jvmTargetEnabled) {
    val packageFfiJni = FfiBuildSupport.registerJniTasks(project, ffiName, ffiNameLower, projectNamespace)
    kotlin.sourceSets.named("jvmMain") { resources.srcDir(packageFfiJni) }

    val packagedJniTest =
        tasks.register<Test>("packagedJniTest") {
            dependsOn("jvmJar", "jvmTestClasses")
            val jvmTest = tasks.named<Test>("jvmTest")
            testClassesDirs = jvmTest.get().testClassesDirs
            val mainOutput =
                kotlin.targets
                    .getByName("jvm")
                    .compilations
                    .getByName("main")
                    .output.allOutputs
            classpath = files(tasks.named<Jar>("jvmJar").flatMap { it.archiveFile }) + jvmTest.get().classpath.minus(mainOutput)
            systemProperty(
                "java.library.path",
                layout.buildDirectory
                    .dir("empty-native-search-path")
                    .get()
                    .asFile.absolutePath,
            )
            filter.isFailOnNoMatchingTests = false
            filter.includeTestsMatching("*.HostIoTest")
        }
    tasks.named("check") { dependsOn(packagedJniTest) }

    if (androidTargetEnabled) {
        val compileAndroidJni =
            FfiBuildSupport.registerAndroidJniTask(
                project,
                ffiName,
                ffiNameLower,
                nativeFfiEnabled,
                nativeFfiCompileWrapper,
                projectKonanDataDirectory.get(),
            )
        extensions.getByType<KotlinMultiplatformAndroidComponentsExtension>().onVariants { variant ->
            requireNotNull(variant.sources.jniLibs).addGeneratedSourceDirectory(compileAndroidJni) { it.nativeDirectory }
        }
    }
}

if (napiFfiEnabled && jsTargetEnabled) {
    val nodeExecutable = rootProject.extensions.getByType<NodeJsEnvSpec>().executable
    FfiBuildSupport.registerNapiTasks(
        project,
        ffiName,
        ffiNameLower,
        ffiNapiDir,
        ffiNpmPackage,
        nodeVersion,
        nodeExecutable,
    )
}

FfiBuildSupport.configureIncludedBuildsFfi(project, jvmFfiEnabled)

// ============================================================================
// Swift Export & Maven Central Portal Support
// ============================================================================
if (swiftExportEnabled) {
    SwiftExportSupport.configure(project, swiftExportModuleName)
}

val hasAppleTargets =
    kotlin.targets
        .withType<KotlinNativeTarget>()
        .any { it.konanTarget.family.isAppleFamily }
CentralPortalSupport.configure(project, publishProjectName, jvmFfiEnabled, hasAppleTargets)

// ============================================================================
// Verification, Build Aggregates & Runtime Validation
// ============================================================================
val rootYarnLockTasks =
    buildList {
        if (jsTargetEnabled) {
            add(rootProject.tasks.named("kotlinUpgradeYarnLock"))
            add(rootProject.tasks.named("kotlinStoreYarnLock"))
        }
        if (anyWasmTargetEnabled) {
            add(rootProject.tasks.named("kotlinWasmUpgradeYarnLock"))
            add(rootProject.tasks.named("kotlinWasmStoreYarnLock"))
        }
    }

val nativeTargetNames = enabledNativeTargets

abstract class StageNativeExecutableTask : org.gradle.api.DefaultTask() {
    @get:org.gradle.api.tasks.InputFile
    @get:org.gradle.api.tasks.PathSensitive(org.gradle.api.tasks.PathSensitivity.NONE)
    abstract val sourceBinary: org.gradle.api.file.RegularFileProperty

    @get:org.gradle.api.tasks.OutputFile
    abstract val targetBinary: org.gradle.api.file.RegularFileProperty

    @org.gradle.api.tasks.TaskAction
    fun stage() {
        val src = sourceBinary.get().asFile
        val dst = targetBinary.get().asFile
        dst.parentFile.mkdirs()
        src.copyTo(dst, overwrite = true)
        check(dst.setExecutable(true, false)) {
            "Failed to mark staged native CLI executable: ${dst.absolutePath}"
        }
    }
}

val isMacOsHost = System.getProperty("os.name").lowercase().contains("mac")
val stageMacOsCliBinaryEnabled =
    nativeExecutableEntryPoint != null &&
        isMacOsHost &&
        targetEnabled("macosArm64") &&
        "macosArm64" in nativeExecutableTargetNames
if (stageMacOsCliBinaryEnabled) {
    tasks.register<StageNativeExecutableTask>("stageNativeCliBinary") {
        dependsOn("linkReleaseExecutableMacosArm64")
        sourceBinary.set(layout.buildDirectory.file("bin/macosArm64/releaseExecutable/$nativeExecutableBaseName.kexe"))
        targetBinary.set(layout.buildDirectory.file("bin/$nativeExecutableBaseName"))
    }
    tasks.named("assemble") {
        dependsOn("stageNativeCliBinary")
    }
}

val hasIdePluginSubproject = findProject(":ide-plugin") != null
if (hasIdePluginSubproject) {
    listOf("buildPlugin", "runIde", "verifyPlugin").forEach { taskName ->
        tasks.register(taskName) {
            dependsOn(":ide-plugin:$taskName")
        }
    }
}

val fullTargetBuildTaskNames =
    buildSet {
        if (androidTargetEnabled) {
            addAll(
                listOf(
                    "compileAndroidMain",
                    "compileAndroidHostTest",
                    "compileAndroidDeviceTest",
                    "assembleAndroidMain",
                    "assembleAndroidHostTest",
                    "assembleAndroidDeviceTest",
                    "assembleUnitTest",
                    "assembleAndroidTest",
                ),
            )
        }
        if (jvmTargetEnabled) {
            addAll(listOf("jvmMainClasses", "jvmTestClasses", "jvmJar"))
        }
        if (jsTargetEnabled) {
            addAll(
                listOf(
                    "compileDevelopmentLibraryKotlinJs",
                    "compileProductionLibraryKotlinJs",
                    "jsMainClasses",
                    "jsTestClasses",
                ),
            )
        }
        if (wasmJsTargetEnabled) {
            addAll(
                listOf(
                    "compileDevelopmentLibraryKotlinWasmJs",
                    "compileProductionLibraryKotlinWasmJs",
                    "wasmJsMainClasses",
                    "wasmJsTestClasses",
                ),
            )
        }
        if (wasmWasiTargetEnabled) {
            addAll(
                listOf(
                    "compileDevelopmentLibraryKotlinWasmWasi",
                    "compileProductionLibraryKotlinWasmWasi",
                    "wasmWasiMainClasses",
                    "wasmWasiTestClasses",
                ),
            )
        }
        if (swiftExportEnabled) {
            add("swiftExportSmokeTest")
        }
        if (appleFrameworkEnabled && hasAppleTargets) {
            add("assemble${frameworkName}XCFramework")
        }
        if (stageMacOsCliBinaryEnabled) {
            add("stageNativeCliBinary")
        }
        if (hasIdePluginSubproject) {
            add(":ide-plugin:buildPlugin")
        }
        for (target in nativeTargetNames) {
            add("${target}Binaries")
            add("${target}TestBinaries")
        }
        if (jvmFfiEnabled && jvmTargetEnabled) {
            add("compile${ffiName}JniDebug")
            add("compile${ffiName}JniRelease")
            add("compile${ffiName}Jni")
        }
        if (napiFfiEnabled && jsTargetEnabled) {
            add("buildNode${ffiName}Debug")
            add("buildNode${ffiName}Release")
            add("buildNode$ffiName")
        }
    }

val debugBuildTaskNames =
    buildSet {
        if (androidTargetEnabled) {
            addAll(
                listOf(
                    "compileAndroidMain",
                    "compileAndroidHostTest",
                    "compileAndroidDeviceTest",
                    "assembleAndroidMain",
                ),
            )
        }
        if (jvmTargetEnabled) {
            addAll(listOf("jvmMainClasses", "jvmTestClasses"))
        }
        if (jsTargetEnabled) {
            addAll(listOf("compileDevelopmentLibraryKotlinJs", "jsMainClasses", "jsTestClasses"))
        }
        if (wasmJsTargetEnabled) {
            addAll(listOf("compileDevelopmentLibraryKotlinWasmJs", "wasmJsMainClasses", "wasmJsTestClasses"))
        }
        if (wasmWasiTargetEnabled) {
            addAll(listOf("compileDevelopmentLibraryKotlinWasmWasi", "wasmWasiMainClasses", "wasmWasiTestClasses"))
        }
        if (appleFrameworkEnabled && hasAppleTargets) {
            add("assemble${frameworkName}DebugXCFramework")
        }
        for (target in nativeTargetNames) {
            val targetCap = target.replaceFirstChar { it.uppercase() }
            add("compileKotlin$targetCap")
            add("${target}TestBinaries")
            if (nativeFfiEnabled && nativeFfiCompileWrapper && (nativeFfiTargetNames.isEmpty() || target in nativeFfiTargetNames)) {
                add("compile${ffiName}WrapperDebugFor$targetCap")
            }
        }
        if (jvmFfiEnabled && jvmTargetEnabled) add("compile${ffiName}JniDebug")
        if (napiFfiEnabled && jsTargetEnabled) add("buildNode${ffiName}Debug")
    }

val releaseBuildTaskNames =
    buildSet {
        if (androidTargetEnabled) {
            addAll(listOf("compileAndroidMain", "assembleAndroidMain"))
        }
        if (jvmTargetEnabled) {
            addAll(listOf("jvmMainClasses", "jvmJar"))
        }
        if (jsTargetEnabled) {
            addAll(listOf("compileProductionLibraryKotlinJs", "jsMainClasses"))
        }
        if (wasmJsTargetEnabled) {
            addAll(listOf("compileProductionLibraryKotlinWasmJs", "wasmJsMainClasses"))
        }
        if (wasmWasiTargetEnabled) {
            addAll(listOf("compileProductionLibraryKotlinWasmWasi", "wasmWasiMainClasses"))
        }
        if (appleFrameworkEnabled && hasAppleTargets) {
            add("assemble${frameworkName}ReleaseXCFramework")
        }
        for (target in nativeTargetNames) {
            val targetCap = target.replaceFirstChar { it.uppercase() }
            add("compileKotlin$targetCap")
            if (nativeFfiEnabled && nativeFfiCompileWrapper && (nativeFfiTargetNames.isEmpty() || target in nativeFfiTargetNames)) {
                add("compile${ffiName}WrapperReleaseFor$targetCap")
            }
        }
        if (jvmFfiEnabled && jvmTargetEnabled) add("compile${ffiName}JniRelease")
        if (napiFfiEnabled && jsTargetEnabled) add("buildNode${ffiName}Release")
    }

val verifierProperties =
    Properties().apply {
        val verifierFile = rootProject.file("verifier.properties")
        if (verifierFile.isFile) {
            verifierFile.inputStream().use { load(it) }
        }
    }

val foreignNativeRuntimeTasks =
    enabledForeignNativeTargets.map { target ->
        tasks.register<BuildToolTask>("validate${target.replaceFirstChar { it.uppercase() }}Runtime") {
            group = "verification"
            description = "Runs linked tests on a local native host or existing Parallels guest; records unavailable hosts."
            dependsOn("linkDebugTest${target.replaceFirstChar { it.uppercase() }}")
            toolClass.set(NativeTestRunner::class.java.name)
            val executable = if (target == "mingwX64") "test.exe" else "test.kexe"
            val binaryFile = layout.buildDirectory.file("bin/$target/debugTest/$executable")
            onlyIf { binaryFile.get().asFile.exists() }
            val platform = if (target == "mingwX64") "windows" else "linux"
            arguments.addAll(
                "--target",
                target,
                "--binary",
                binaryFile.get().asFile.absolutePath,
                "--report-dir",
                layout.buildDirectory
                    .dir("reports/local-native-tests")
                    .get()
                    .asFile.absolutePath,
                "--allow-unavailable",
            )
            val vmSelector =
                providers
                    .gradleProperty("project.validation.$platform.vm")
                    .orNull
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: verifierProperties
                        .getProperty("project.validation.$platform.vm")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
            if (vmSelector != null) {
                arguments.addAll("--vm", vmSelector)
            }
        }
    }

if ("linuxX64" in enabledForeignNativeTargets && "linuxArm64" in enabledForeignNativeTargets) {
    tasks.named("validateLinuxX64Runtime") {
        mustRunAfter("validateLinuxArm64Runtime")
    }
}
if ("mingwX64" in enabledForeignNativeTargets && "linuxX64" in enabledForeignNativeTargets) {
    tasks.named("validateMingwX64Runtime") {
        mustRunAfter("validateLinuxX64Runtime")
    }
}

val hostIsArm64ForAndroidNative =
    System
        .getProperty("os.arch", "")
        .lowercase()
        .let { it == "aarch64" || it == "arm64" }

val macHasHypervisorSupport =
    !System.getProperty("os.name", "").lowercase().contains("mac") ||
        ProcessBuilder("/usr/sbin/sysctl", "-n", "kern.hv_support")
            .start()
            .let { proc ->
                val reader = proc.inputStream.bufferedReader()
                val out = reader.readText().trim()
                proc.waitFor() == 0 && out == "1"
            }

val androidNativeAdbExecutable =
    rootProject.file(
        ".android-sdk/platform-tools/" +
            if (System.getProperty("os.name", "").startsWith("Windows")) "adb.exe" else "adb",
    )

val androidNativeRuntimeTasks =
    enabledAndroidNativeTargets.map { target ->
        tasks.register<BuildToolTask>("validate${target.replaceFirstChar { it.uppercase() }}Runtime") {
            group = "verification"
            description = "Runs linked Android Native tests on a connected Android device or emulator via adb."
            dependsOn("linkDebugTest${target.replaceFirstChar { it.uppercase() }}")
            toolClass.set(NativeTestRunner::class.java.name)
            val binaryFile = layout.buildDirectory.file("bin/$target/debugTest/test.kexe")
            onlyIf { binaryFile.get().asFile.exists() }
            outputs.upToDateWhen { false }
            arguments.addAll(
                "--target",
                target,
                "--binary",
                binaryFile.get().asFile.absolutePath,
                "--adb",
                androidNativeAdbExecutable.absolutePath,
                "--report-dir",
                layout.buildDirectory
                    .dir("reports/local-native-tests")
                    .get()
                    .asFile.absolutePath,
            )
            val abiMatchesHost =
                (target == "androidNativeArm64" && hostIsArm64ForAndroidNative) ||
                    (target == "androidNativeX64" && !hostIsArm64ForAndroidNative)
            val isHostMatchingAbi = abiMatchesHost && macHasHypervisorSupport
            if (!isHostMatchingAbi) {
                arguments.add("--allow-unavailable")
            }
            val vmSelector =
                providers
                    .gradleProperty("project.validation.android.vm")
                    .orNull
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: verifierProperties
                        .getProperty("project.validation.android.vm")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
            if (vmSelector != null) {
                arguments.addAll("--vm", vmSelector)
            }
        }
    }

tasks.matching { it.name == "connectedAndroidDeviceTest" }.configureEach {
    outputs.upToDateWhen { false }
}

if (androidTargetEnabled) {
    tasks.register("verifyAndroidDeviceTestResults") {
        group = "verification"
        description = "Requires nonempty, passing Android device-test results."
        dependsOn("connectedAndroidDeviceTest")
        doLast {
            AndroidDeviceTestVerifier.verify(
                layout.buildDirectory
                    .dir("outputs/androidTest-results/connected")
                    .get()
                    .asFile,
            )
        }
    }
}

if ("androidNativeArm64" in enabledAndroidNativeTargets && androidTargetEnabled) {
    tasks.named("validateAndroidNativeArm64Runtime") {
        mustRunAfter("verifyAndroidDeviceTestResults")
    }
}
if ("androidNativeX64" in enabledAndroidNativeTargets && "androidNativeArm64" in enabledAndroidNativeTargets) {
    tasks.named("validateAndroidNativeX64Runtime") {
        mustRunAfter("validateAndroidNativeArm64Runtime")
    }
}

tasks.register("hostTests") {
    group = "verification"
    description = "Runs every enabled target's tests that this host can execute, foreign Parallels guest tests, and Android host and connected-device tests."
    if (jsTargetEnabled) {
        dependsOn(rootProject.tasks.named("kotlinUpgradeYarnLock"))
    }
    if (anyWasmTargetEnabled) {
        dependsOn(rootProject.tasks.named("kotlinWasmUpgradeYarnLock"))
    }
    dependsOn("allTests")
    if (androidTargetEnabled) {
        dependsOn("testAndroidHostTest", "verifyAndroidDeviceTestResults")
    }
    dependsOn(androidNativeRuntimeTasks, foreignNativeRuntimeTasks)
}

tasks.register("test") {
    group = "verification"
    description = "Runs tests for every enabled target this host can run, foreign Parallels guest tests, Android host and connected-device tests, and the Swift Export smoke test."
    dependsOn("hostTests")
    if (swiftExportEnabled) {
        dependsOn("swiftExportSmokeTest")
    }
    if (hasIdePluginSubproject) {
        dependsOn(":ide-plugin:test")
    }
    extraTestTaskNames.forEach { dependsOn(it) }
}

tasks.named("check") {
    dependsOn(tasks.withType<Detekt>())
    dependsOn(tasks.named("ktlintCheck"))
    dependsOn("test")
}

tasks.register("debug") {
    group = "build"
    description = "Builds debug artifacts and FFI bindings across all enabled targets."
    dependsOn(rootYarnLockTasks)
    dependsOn(debugBuildTaskNames)
}

tasks.register("release") {
    group = "build"
    description = "Builds release artifacts and symbol-hidden FFI bindings across all enabled targets."
    dependsOn(rootYarnLockTasks)
    dependsOn(releaseBuildTaskNames)
}

tasks.named("build") {
    dependsOn(rootYarnLockTasks)
    dependsOn("debug", "release")
    dependsOn(fullTargetBuildTaskNames)
}

val macroLayoutCSource = rootProject.file("verification/macro-layout-wasm.c")
if (macroLayoutCSource.isFile) {
    val macroLayoutDirectory = layout.buildDirectory.dir("macro-layout-wasm")
    val macroLayoutWasm = macroLayoutDirectory.map { it.file("macro-layout.wasm") }
    val macroLayoutScript = macroLayoutDirectory.map { it.file("macro-layout.js") }
    val macroFixtureLlvmBin =
        providers.gradleProperty("project.macroFixture.llvmBin").orElse(
            projectKonanDataDirectory.map { VerificationSupport.resolveKonanLlvmBin(it) },
        )
    val cinteropIncludeDir = file("src/native/interop/cinterop")
    val compileMacroLayoutWasm =
        tasks.register("compileMacroLayoutWasm") {
            inputs.files(macroLayoutCSource, cinteropIncludeDir.resolve("macro_parity_fixture.h"))
            inputs.property("llvmBin", macroFixtureLlvmBin)
            outputs.dir(macroLayoutDirectory)
            doLast {
                VerificationSupport.compileMacroLayoutWasm(
                    project,
                    macroLayoutCSource,
                    cinteropIncludeDir,
                    macroLayoutDirectory.get().asFile,
                    macroLayoutWasm.get().asFile,
                    macroLayoutScript.get().asFile,
                    macroFixtureLlvmBin.get(),
                )
            }
        }
    tasks
        .withType<org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest>()
        .matching {
            it.name == "jsBrowserTest" || it.name == "wasmJsBrowserTest"
        }.configureEach {
            dependsOn(compileMacroLayoutWasm)
            inputs.file(macroLayoutScript)
            environment("LIBC_MACRO_LAYOUT_SCRIPT", macroLayoutScript.get().asFile.absolutePath)
        }
    if (wasmWasiTargetEnabled) {
        val macroWasiWasm = objects.fileProperty()
        val prepareMacroWasiTestLoader =
            tasks.register<Copy>("prepareMacroWasiTestLoader") {
                from(rootProject.file("verification/macro-wasi-test-loader.mjs"))
                into(layout.buildDirectory.dir("macro-layout-wasi"))
            }
        tasks.named<org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest>("wasmWasiNodeTest") {
            @OptIn(ExperimentalWasmDsl::class)
            val binary =
                compilation.binaries
                    .filterIsInstance<org.jetbrains.kotlin.gradle.targets.js.ir.ExecutableWasm>()
                    .single { it.mode == org.jetbrains.kotlin.gradle.targets.js.dsl.KotlinJsBinaryMode.DEVELOPMENT }

            @OptIn(ExperimentalWasmDsl::class)
            val kotlinWasmFile = binary.mainWasmFile

            @OptIn(ExperimentalWasmDsl::class)
            val kotlinWasmLinkTask = binary.linkTask
            macroWasiWasm.set(kotlinWasmFile)
            dependsOn(compileMacroLayoutWasm, prepareMacroWasiTestLoader, kotlinWasmLinkTask)
            inputs.file(macroLayoutWasm)
            inputs.file(kotlinWasmFile)
            inputFileProperty.set(layout.buildDirectory.file("macro-layout-wasi/macro-wasi-test-loader.mjs"))
            doFirst {
                environment("LIBC_WASI_TEST_MODULE", kotlinWasmFile.get().asFile.absolutePath)
            }
            environment("LIBC_MACRO_WASM", macroLayoutWasm.get().asFile.absolutePath)
        }

        val wasmWasiPreopenTest =
            tasks.register<Exec>("wasmWasiPreopenTest") {
                group = "verification"
                description = "Executes public WASI FILE IO against a real preopened host directory."
                dependsOn("wasmWasiNodeTest")
                inputs.file(macroWasiWasm)
                inputs.file(macroLayoutWasm)
                doFirst {
                    commandLine(
                        rootProject.extensions
                            .getByType<WasmNodeJsEnvSpec>()
                            .executable
                            .get(),
                        rootProject.file("verification/wasi-preopen-test.mjs").absolutePath,
                        macroWasiWasm.get().asFile,
                        layout.buildDirectory
                            .dir("test-results/wasmWasiPreopenTest")
                            .get()
                            .asFile,
                        macroLayoutWasm.get().asFile,
                    )
                }
            }
        tasks.named("test") { dependsOn(wasmWasiPreopenTest) }
        tasks.named("check") { dependsOn(wasmWasiPreopenTest) }
    }
}

val publicationCompilationTasks =
    kotlin.targets
        .filter { it.name != "metadata" }
        .flatMap { target -> target.compilations.map { it.compileTaskProvider } }

val compileAllTargets =
    tasks.register("compileAllTargets") {
        group = "verification"
        description = "Compiles main and test sources for every declared target."
        dependsOn(publicationCompilationTasks)
        doLast {
            val skippedCompilations =
                publicationCompilationTasks.map { it.get() }.filter { task ->
                    !task.state.executed ||
                        (task.state.skipped && !task.state.upToDate && task.state.skipMessage != "FROM-CACHE")
                }
            check(skippedCompilations.isEmpty()) {
                "Required target compilations did not complete: " +
                    skippedCompilations.joinToString { "${it.path} (${it.state.skipMessage ?: "not executed"})" }
            }
        }
    }

val buildAllTargets =
    tasks.register("buildAllTargets") {
        group = "verification"
        description = "Compiles and links every declared target before runtime validation."
        dependsOn(
            rootYarnLockTasks,
            compileAllTargets,
            fullTargetBuildTaskNames.filter { it != "swiftExportSmokeTest" },
        )
    }

foreignNativeRuntimeTasks.forEach { taskProvider ->
    taskProvider.configure { mustRunAfter(buildAllTargets) }
}
androidNativeRuntimeTasks.forEach { taskProvider ->
    taskProvider.configure { mustRunAfter(buildAllTargets) }
}
tasks.withType<AbstractTestTask>().configureEach {
    mustRunAfter(buildAllTargets)
}
tasks.matching { it.name == "connectedAndroidDeviceTest" }.configureEach {
    mustRunAfter(buildAllTargets)
}
if (swiftExportEnabled) {
    tasks.named("swiftExportSmokeTest") { mustRunAfter(buildAllTargets) }
}

val publicationPomChecks =
    publishing.publications.withType<MavenPublication>().map { publication ->
        tasks.named("checkPomFileFor${publication.name.replaceFirstChar { it.uppercase() }}Publication")
    }

val checkPublicationPoms =
    tasks.register("checkPublicationPoms") {
        group = "verification"
        description = "Checks the generated POM for every Maven publication."
        dependsOn(publicationPomChecks)
        doLast {
            for (provider in publicationPomChecks) {
                val task = provider.get()
                task.state.rethrowFailure()
                check(
                    task.state.executed &&
                        (!task.state.skipped || task.state.upToDate || task.state.skipMessage == "FROM-CACHE"),
                ) {
                    "Required POM check did not execute: ${task.path} (${task.state.skipMessage})"
                }
            }
        }
    }

val publicationRuntimeTaskNames =
    buildList {
        if (jvmTargetEnabled) add("jvmTest")
        if (androidTargetEnabled) add("testAndroidHostTest")
        if (jsTargetEnabled) {
            add("jsBrowserTest")
            add("jsNodeTest")
        }
        if (wasmJsTargetEnabled) {
            add("wasmJsBrowserTest")
            add("wasmJsNodeTest")
        }
        if (wasmWasiTargetEnabled) add("wasmWasiNodeTest")
        if (targetEnabled("macosArm64")) add("macosArm64Test")
        if (targetEnabled("iosSimulatorArm64")) add("iosSimulatorArm64Test")
        if (targetEnabled("tvosSimulatorArm64")) add("tvosSimulatorArm64Test")
        if (targetEnabled("watchosSimulatorArm64")) add("watchosSimulatorArm64Test")
    }

tasks.matching { it.name in publicationRuntimeTaskNames }.configureEach {
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("Publication requires actual runtime execution") { true }
}

val verifyPublicationRuntimes =
    tasks.register("verifyPublicationRuntimes") {
        group = "verification"
        description = "Rejects skipped required test tasks and missing, empty or failing runtime reports."
        dependsOn(
            buildAllTargets,
            publicationRuntimeTaskNames,
            androidNativeRuntimeTasks,
            foreignNativeRuntimeTasks,
        )
        if (androidTargetEnabled) {
            dependsOn("verifyAndroidDeviceTestResults")
        }
        doLast {
            for (provider in foreignNativeRuntimeTasks + androidNativeRuntimeTasks) {
                val task = provider.get()
                task.state.rethrowFailure()
                check(task.state.executed && !task.state.skipped) {
                    "Required native runtime did not execute: ${task.path} (${task.state.skipMessage})"
                }
            }
            if (androidTargetEnabled) {
                val androidVerifyTask = tasks.named("verifyAndroidDeviceTestResults").get()
                androidVerifyTask.state.rethrowFailure()
                check(androidVerifyTask.state.executed && !androidVerifyTask.state.skipped) {
                    "Required Android device runtime did not execute: ${androidVerifyTask.path} (${androidVerifyTask.state.skipMessage})"
                }
            }
            for (name in publicationRuntimeTaskNames) {
                val task = tasks.named<AbstractTestTask>(name).get()
                task.state.rethrowFailure()
                check(task.state.executed && !task.state.skipped) {
                    "Required runtime did not execute: ${task.path} (${task.state.skipMessage})"
                }
                val reports =
                    fileTree(
                        task.reports.junitXml.outputLocation
                            .get()
                            .asFile,
                    ) { include("*.xml") }.files
                VerificationSupport.verifyJunitXmlReports(task.path, reports)
            }
        }
    }

val publicationCheck =
    tasks.register("publicationCheck") {
        group = "verification"
        description = "Builds every target, runs tests and checks every POM before Maven publication."
        dependsOn("build", compileAllTargets, checkPublicationPoms, verifyPublicationRuntimes)
        doLast {
            for (name in fullTargetBuildTaskNames + "build") {
                val task = tasks.named(name).get()
                task.state.rethrowFailure()
                check(
                    task.state.executed &&
                        (!task.state.skipped || task.state.upToDate || task.state.skipMessage == "FROM-CACHE"),
                ) {
                    "Required build task did not execute: ${task.path} (${task.state.skipMessage})"
                }
            }
        }
    }

tasks.withType<AbstractPublishToMaven>().configureEach {
    dependsOn(publicationCheck)
}

tasks.register("classes") {
    if (androidTargetEnabled) dependsOn("compileAndroidMain")
    if (jvmTargetEnabled) dependsOn("jvmMainClasses")
    if (jsTargetEnabled) dependsOn("jsMainClasses")
    if (wasmJsTargetEnabled) dependsOn("wasmJsMainClasses")
    if (wasmWasiTargetEnabled) dependsOn("wasmWasiMainClasses")
}

tasks.register("testClasses") {
    if (androidTargetEnabled) dependsOn("compileAndroidHostTest")
    if (jvmTargetEnabled) dependsOn("jvmTestClasses")
    if (jsTargetEnabled) dependsOn("jsTestClasses")
    if (wasmJsTargetEnabled) dependsOn("wasmJsTestClasses")
    if (wasmWasiTargetEnabled) dependsOn("wasmWasiTestClasses")
}

val wasmWasiTestPreopens =
    providers
        .gradleProperty("project.wasmWasi.testPreopens")
        .getOrElse("")
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }

tasks.matching { it.name == "wasmWasiNodeTest" }.configureEach {
    val driverDir = layout.buildDirectory.dir("compileSync/wasmWasi/test/testDevelopmentExecutable/kotlin")
    val wasmNodeModulesDir = rootProject.layout.buildDirectory.dir("wasm/node_modules")
    val workspaceRootDir = rootProject.projectDir.parentFile ?: rootProject.projectDir
    val hostRoot = layout.buildDirectory.dir("tmp/wasmWasiTestPreopens")
    doFirst {
        if (wasmWasiTestPreopens.isNotEmpty()) {
            VerificationSupport.patchWasmWasiTestPreopens(
                driverDir.get().asFile,
                hostRoot.get().asFile,
                wasmWasiTestPreopens,
            )
        }
        VerificationSupport.patchWasmWasiTestFfiImports(
            driverDir.get().asFile,
            wasmNodeModulesDir.get().asFile,
            workspaceRootDir,
        )
    }
}
