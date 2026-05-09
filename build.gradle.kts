import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest

plugins {
    kotlin("multiplatform") version "2.3.20"
    kotlin("plugin.serialization") version "2.3.20"
    id("com.android.kotlin.multiplatform.library") version "8.6.0"
    id("com.vanniktech.maven.publish") version "0.30.0"
}

group = "io.github.kotlinmania"

// NOTE: 0.2.0 was already released; bump to allow republish after CI fixes.
version = "0.2.2"

// fleeksoft publishes both `io` and `io-core`, but linking both causes duplicate symbols on Kotlin/Native.
// `charset` depends on `io-core`, so forbid `io` globally to keep linuxX64Test linking clean.
configurations.configureEach {
    exclude(group = "com.fleeksoft.io", module = "io")
}

// Setup Android SDK location and licenses automatically
val sdkDir = file(".android-sdk")
val licensesDir = sdkDir.resolve("licenses")
if (!licensesDir.exists()) licensesDir.mkdirs()
val licenseFile = licensesDir.resolve("android-sdk-license")
if (!licenseFile.exists()) {
    licenseFile.writeText(
        """
        8933bad161af4178b1185d1a37fbf41ea5269c55
        d56f5187479451eabf01fb74abc367c344559d7b
        24333f8a63b6825ea9c5514f83c2829b004d1fee
        """.trimIndent()
    )
}
val localProperties: File? = rootProject.file("local.properties")
if (!localProperties?.exists()!!) {
    val sdkDirPropertyValue = sdkDir.absolutePath.replace("\\", "/")
    localProperties.writeText("sdk.dir=$sdkDirPropertyValue")
}

kotlin {
    applyDefaultHierarchyTemplate()

    compilerOptions {
        allWarningsAsErrors.set(true)
    }

    sourceSets.all { languageSettings.optIn("kotlin.time.ExperimentalTime") }

    val xcf = XCFramework("JWTKMP")

    macosArm64 {
        binaries.framework {
            baseName = "JWTKMP"
            xcf.add(this)
        }
    }
    linuxX64()
    mingwX64()
    iosArm64 {
        binaries.framework {
            baseName = "JWTKMP"
            xcf.add(this)
        }
    }
    iosSimulatorArm64 {
        binaries.framework {
            baseName = "JWTKMP"
            xcf.add(this)
        }
    }
    js {
        browser()
        nodejs()
    }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        nodejs()
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.11.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
                implementation("org.jetbrains.kotlinx:kotlinx-io-core:0.5.4")

                // Ktor HTTP client for multiplatform
                implementation("io.ktor:ktor-client-core:3.0.0")
                implementation("io.ktor:ktor-client-content-negotiation:3.0.0")
                implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.0")
                implementation("io.ktor:ktor-client-auth:3.0.0")

                // File I/O
                implementation("com.squareup.okio:okio:3.9.1")

                // Character encoding support (for legacy codepage conversion)
                implementation("com.fleeksoft.charset:charset:0.0.5")
                implementation("com.fleeksoft.charset:charset-ext:0.0.5")
            }
        }

        val nativeMain by getting {
            dependencies {
            }
        }

        val appleMain by getting {
            dependencies {
                implementation("io.ktor:ktor-client-darwin:3.0.0")
            }
        }

        val linuxMain by getting {
            dependencies {
                implementation("io.ktor:ktor-client-curl:3.0.0")
            }
        }

        val mingwMain by getting {
            dependencies {
                implementation("io.ktor:ktor-client-curl:3.0.0")
            }
        }

        val jsMain by getting {
            dependencies {
                implementation("io.ktor:ktor-client-js:3.0.0")
            }
        }

        val wasmJsMain by getting {
            dependencies {
                implementation("io.ktor:ktor-client-js:3.0.0")
            }
        }

        val androidMain by getting {
            dependencies {
                implementation("io.ktor:ktor-client-okhttp:3.0.0")
            }
        }

        val commonTest by getting { dependencies { implementation(kotlin("test")) } }
    }
    jvmToolchain(21)
}

kotlin {
    android {
        namespace = "io.github.kotlinmania.jwt"
        compileSdk = 34
        minSdk = 24
        withHostTestBuilder {}.configure {}
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }
    }
}

val enableIosSimulatorTests =
    providers.gradleProperty("enableIosSimulatorTests").map { it.toBoolean() }.orElse(false)

tasks.withType<KotlinNativeTest>().configureEach {
    if (!enableIosSimulatorTests.get() && name == "iosSimulatorArm64Test") {
        enabled = false
    }
}

val enableIosSimulatorTests =
    providers.gradleProperty("enableIosSimulatorTests").map { it.toBoolean() }.orElse(false)

tasks.withType<KotlinNativeTest>().configureEach {
    if (!enableIosSimulatorTests.get() && (name == "iosX64Test" || name == "iosSimulatorArm64Test")) {
        enabled = false
    }
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    coordinates(group.toString(), "jwt-kmp", version.toString())

    pom {
        name.set("JWT-KMP")
        description.set("Kotlin Multiplatform JWT (JSON Web Token) library. Strictly for Kotlin Multiplatform projects, not intended for pure Java usage.")
        inceptionYear.set("2024")
        url.set("https://github.com/KotlinMania/JWT-Kotlin")

        licenses {
            license {
                name.set("MIT")
                url.set("https://opensource.org/licenses/MIT")
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
            url.set("https://github.com/KotlinMania/JWT-Kotlin")
            connection.set("scm:git:git://github.com/KotlinMania/JWT-Kotlin.git")
            developerConnection.set("scm:git:ssh://github.com/KotlinMania/JWT-Kotlin.git")
        }
    }
}

// CodeQL's Gradle autobuild invokes `./gradlew testClasses`, which is a
// JVM-convention task that Kotlin Multiplatform projects without a JVM
// target do not provide. Without it, CodeQL aborts with
// `Task 'testClasses' not found in root project` and skips the scan.
// Register an aggregate task that depends on every per-target
// test-compile task (jsTestClasses, wasmJsTestClasses, and the
// compileTestKotlin<Target> tasks for native targets) so the convention
// call resolves.
tasks.register("testClasses") {
    description = "Aggregate test-compile task for CodeQL and other JVM-convention callers."
    group = "verification"
    dependsOn(tasks.matching { other ->
        val n = other.name
        n != "testClasses" &&
            (n.endsWith("TestClasses") || n.startsWith("compileTestKotlin"))
    })
}
