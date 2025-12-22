plugins {
    kotlin("multiplatform") version "2.2.21"
    kotlin("plugin.serialization") version "2.2.21"
    id("com.vanniktech.maven.publish") version "0.30.0"
}

group = "io.github.kotlinmania"
version = "0.1.0"

kotlin {
    applyDefaultHierarchyTemplate()

    sourceSets.all {
        languageSettings.optIn("kotlin.time.ExperimentalTime")
    }

    // Configure native targets as library-only (no executables)
    // This prevents "Could not find main function" errors since this is a library
    val configureNativeTarget: org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget.() -> Unit = {
        binaries {
            sharedLib()
            staticLib()
        }
    }
    
    macosArm64(configure = configureNativeTarget)
    macosX64(configure = configureNativeTarget)
    linuxX64(configure = configureNativeTarget)
    mingwX64(configure = configureNativeTarget)

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.9.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
                implementation("org.jetbrains.kotlinx:kotlinx-io-core:0.8.2")

                // Ktor HTTP client for native platforms
                implementation("io.ktor:ktor-client-core:2.3.7")
                implementation("io.ktor:ktor-client-curl:2.3.7")
                implementation("io.ktor:ktor-client-content-negotiation:2.3.7")
                implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.7")
                implementation("io.ktor:ktor-client-auth:2.3.7")

                // File I/O
                implementation("com.squareup.okio:okio:3.16.4")

                // Character encoding support (for legacy codepage conversion)
                // fleeksoft-io provides JDK-like IO classes for Kotlin Multiplatform
                implementation("com.fleeksoft.io:io-core:0.0.4")
                implementation("com.fleeksoft.io:io:0.0.4")
                implementation("com.fleeksoft.charset:charset:0.0.5")
                implementation("com.fleeksoft.charset:charset-ext:0.0.5")
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
    jvmToolchain(21)
}

mavenPublishing {
    publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()

    coordinates(group.toString(), "jwt-kotlin", version.toString())

    pom {
        name.set("jwt-kotlin")
        description.set("Kotlin Multiplatform JWT (JSON Web Token) library")
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