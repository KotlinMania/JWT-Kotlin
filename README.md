JWT-Kotlin (Kotlin Multiplatform fork of auth0/java-jwt)
=========================================================

This repository is a Kotlin Multiplatform adaptation of the excellent auth0/java-jwt library. It is designed **strictly for Kotlin Multiplatform projects**. If you are looking for a library to use in a pure Java project, please use the original [java-jwt](https://github.com/auth0/java-jwt).

Key differences from upstream:
- Kotlin Multiplatform/Common code first. No Android- or JVM-only APIs are required to use the core features.
- Strictly for Kotlin consumers; Java interoperability is not a goal and is not supported.
- Currently supports HMAC-based algorithms (HS256/384/512) and `none` for testing. RSA/ECDSA are not yet provided in this fork.
- Published to Maven Central as `io.github.kotlinmania:jwt-kmp`.

Contents
- Getting Started
- Installation
- Supported algorithms
- Usage examples (Kotlin)
- FAQ / Notes
- Contributing and License

Getting Started
---------------

Requirements
- Kotlin 2.3.0
- Kotlin Multiplatform with the following targets:
    - **Native**: macOS (arm64/x64), linuxX64, mingwX64
    - **Mobile**: Android, iOS (arm64, x64, simulatorArm64) — providing Swift compatibility via XCFramework
        - *Note: The build automatically pulls the necessary Android SDK and accepts licenses.*
    - **Web**: JS (Browser, Node.js), WASM (Browser, Node.js)

Swift Support
-------------

This library is compatible with Swift via XCFramework. To build the XCFramework, run:

```bash
./gradlew assembleJWTKMPXCFramework
```

The output will be available at `build/XCFrameworks/release/JWTKMP.xcframework` (or `debug` for debug builds).

You can then add this XCFramework to your Xcode project.

Dependencies:
- Ktor 3.0.0
- kotlinx.serialization 1.7.3
- kotlinx.datetime 0.6.1
- kotlinx.coroutines 1.9.0

If you need a JVM- or Android-focused library, prefer the original upstream projects:
- Java/JVM: https://github.com/auth0/java-jwt
- Android decode-only: https://github.com/auth0/JWTDecode.Android

Installation
------------

The library is published to Maven Central. You can add it to your Kotlin Multiplatform project as a dependency.

**Note: This project is strictly for Kotlin Multiplatform consumers and should not be used in pure Java projects.**

### Gradle (Kotlin DSL)

Add the dependency to your `commonMain` source set:

```kotlin
kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("io.github.kotlinmania:jwt-kmp:0.2.0")
            }
        }
    }
}
```

Other Installation Options
--------------------------

If you prefer to use it as a source dependency or via JitPack:

Option A — Subproject include
1) Add this repository as a Git submodule (or copy it into your repo), e.g. under `external/JWT-Kotlin`.
2) In your root `settings.gradle.kts`:
```kotlin
include(":external:JWT-Kotlin")
project(":external:JWT-Kotlin").projectDir = file("external/JWT-Kotlin")
```
3) In your module `build.gradle.kts`:
```kotlin
dependencies {
    implementation(project(":external:JWT-Kotlin"))
}
```

Option B — Composite build
1) Place/clone the repo next to your project.
2) In your root `settings.gradle.kts`:
```kotlin
includeBuild("../JWT-Kotlin")
```
1) Then depend on the included build’s project where appropriate. If you keep the default project name, you can also re-map it using dependency substitution in `settings.gradle.kts`.

Option C — Use Maven via Git (JitPack)
If you prefer to consume this repository directly from Git without submodules/composite builds, you can use JitPack. JitPack builds the project from the GitHub URL and serves artifacts from a Maven repository.

**Note: This is intended for Kotlin projects only.**

Gradle (Kotlin DSL):
1) Add JitPack to your repositories (usually in `settings.gradle.kts` under `dependencyResolutionManagement`):
```kotlin
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven(url = "https://jitpack.io")
    }
}
```
2) Add the dependency using the GitHub coordinates. Replace `<tag-or-commit>` with a release tag (recommended) or a commit SHA:
```kotlin
dependencies {
    implementation("com.github.KotlinMania:JWT-Kotlin:<tag-or-commit>")
}
```

(Note: While the repository name remains `JWT-Kotlin`, the published artifact name for Kotlin Multiplatform is `jwt-kmp`).

Maven (pom.xml):
```xml
<repositories>
  <repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
  </repository>
  <repository>
    <id>central</id>
    <url>https://repo1.maven.org/maven2/</url>
  </repository>
  <!-- Add any other repositories you use -->
  
</repositories>

<dependencies>
  <dependency>
    <groupId>com.github.KotlinMania</groupId>
    <artifactId>JWT-Kotlin</artifactId>
    <version>&lt;tag-or-commit&gt;</version>
  </dependency>
</dependencies>
```

Notes:
- Use a Git tag (for example, `v0.2.0`) or a specific commit SHA to ensure reproducible builds.
- JitPack reads the Gradle build in this repo and publishes the multiplatform artifacts on demand. The URL for the Git repo is: https://github.com/KotlinMania/JWT-Kotlin.git

Supported algorithms
--------------------

Currently implemented in this fork for all targets (Native, Android, iOS, JS, WASM):

|  JWS  | Algorithm | Description                      |
|:-----:|:---------:|:---------------------------------|
| HS256 |  HMAC256  | HMAC with SHA-256                |
| HS384 |  HMAC384  | HMAC with SHA-384                |
| HS512 |  HMAC512  | HMAC with SHA-512                |
| none  |   None    | Unsecured JWT (for testing only) |

Not yet available here (planned):
- RSA (RS256/384/512)
- ECDSA (ES256/384/512)

Usage examples (Kotlin)
-----------------------

Creating a JWT (HS256)
```kotlin
val algorithm = Algorithm.hmac256("super-secret")
val token = JWT.create()
    .withIssuer("example")
    .withClaim("role", "admin")
    .sign(algorithm)
```

Verifying a JWT
```kotlin
val token: String = "your.jwt.token"
val algorithm = Algorithm.hmac256("super-secret")

try {
    val verifier = JWT.require(algorithm)
        .withIssuer("example")
        .build()

    val decoded = verifier.verify(token)
    println("Subject: ${decoded.subject}")
} catch (ex: JWTVerificationException) {
    // invalid signature or claims
}
```

Decoding without verification
```kotlin
try {
    val decoded = JWT.decode(token)
    println(decoded.header)
    println(decoded.payload)
} catch (ex: JWTDecodeException) {
    // malformed token
}
```

FAQ / Notes
-----------

- Why no RSA/ECDSA yet?
  This fork currently focuses on common functionality that works across native targets. RSA/ECDSA require platform-specific crypto backends; contributions are welcome.

- Is `none` supported in production?
  No. `none` is intended only for testing. Do not use in production.

- JVM support?
  This library is designed for Kotlin Multiplatform and Android. Pure JVM usage is not a priority; use the original `java-jwt` for standard Java/JVM applications.

Contributing
------------

Issues and PRs are welcome. Please use clear, minimal reproductions and include target/platform details. By contributing you agree to the terms of the MIT license.

License
-------

This project is licensed under the MIT license. See the [LICENSE](./LICENSE) file for more info.