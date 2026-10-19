# JWT-Kotlin

[![GitHub](https://img.shields.io/badge/GitHub-KotlinMania%2FJWT--Kotlin-blue.svg)](https://github.com/KotlinMania/JWT-Kotlin)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.kotlinmania/jwt-kmp)](https://central.sonatype.com/artifact/io.github.kotlinmania/jwt-kmp)
[![Build status](https://img.shields.io/github/actions/workflow/status/KotlinMania/JWT-Kotlin/ci.yml?branch=main)](https://github.com/KotlinMania/JWT-Kotlin/actions)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](./LICENSE)
[![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-7F52FF.svg?logo=kotlin)](https://kotlinlang.org/docs/multiplatform.html)

Create, sign, verify and decode JSON Web Tokens from common Kotlin code on every
Kotlin Multiplatform target.

JWT-Kotlin is a Kotlin Multiplatform port of Auth0's
[java-jwt](https://github.com/auth0/java-jwt). It is pure Kotlin: no JVM-only
APIs and no Java libraries. Hashing and HMAC come from the KotlinMania ports of
the RustCrypto crates, [hmac-kotlin](https://github.com/KotlinMania/hmac-kotlin)
and [sha2-kotlin](https://github.com/KotlinMania/sha2-kotlin).

## Platforms

| Family | Targets |
|---|---|
| JVM | `jvm` |
| Android | Android library, `androidNativeArm64`, `androidNativeX64` |
| Apple | `macosArm64`, `iosArm64`, `iosSimulatorArm64`, `tvosArm64`, `tvosSimulatorArm64`, `watchosDeviceArm64`, `watchosSimulatorArm64` |
| Linux and Windows | `linuxX64`, `linuxArm64`, `mingwX64` |
| Web | `js` (browser and Node.js), `wasmJs` (browser and Node.js), `wasmWasi` |

Apple x86_64 and 32-bit targets are not published.

## Installation

The library is on Maven Central as `io.github.kotlinmania:jwt-kmp`.

```kotlin
// build.gradle.kts
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.kotlinmania:jwt-kmp:0.3.1")
        }
    }
}
```

Or with a version catalog:

```toml
# gradle/libs.versions.toml
[versions]
jwtKmp = "0.3.1"

[libraries]
jwt-kmp = { module = "io.github.kotlinmania:jwt-kmp", version.ref = "jwtKmp" }
```

```kotlin
commonMain.dependencies {
    implementation(libs.jwt.kmp)
}
```

## Supported algorithms

| JWS | Factory | Description |
|---|---|---|
| HS256 | `Algorithm.hmac256(secret)` | HMAC with SHA-256 |
| HS384 | `Algorithm.hmac384(secret)` | HMAC with SHA-384 |
| HS512 | `Algorithm.hmac512(secret)` | HMAC with SHA-512 |
| none | `Algorithm.none()` | Unsecured token, for tests only |

Secrets can be a `String` or a `ByteArray`. RSA (RS256/384/512) and ECDSA
(ES256/384/512) are not available yet.

## Usage

### Create and sign a token

```kotlin
import io.github.kotlinmania.jwt.JWT
import io.github.kotlinmania.jwt.algorithms.Algorithm
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

val algorithm = Algorithm.hmac256("super-secret")
val now = Clock.System.now()

val token: String =
    JWT.create()
        .withIssuer("auth.example.com")
        .withSubject("user-42")
        .withAudience("api.example.com")
        .withIssuedAt(now)
        .withExpiresAt(now + 1.hours)
        .withClaim("role", "admin")
        .sign(algorithm)
```

### Verify a token

`verify` checks the signature and every claim you require, and throws a
`JWTVerificationException` subtype when anything does not match.

```kotlin
import io.github.kotlinmania.jwt.exceptions.JWTVerificationException
import io.github.kotlinmania.jwt.exceptions.TokenExpiredException

val verifier =
    JWT.require(algorithm)
        .withIssuer("auth.example.com")
        .withAudience("api.example.com")
        .withClaim("role", "admin")
        .acceptLeeway(5) // seconds of clock skew allowed for exp, nbf and iat
        .build()

try {
    val decoded = verifier.verify(token)
    println("subject = ${decoded.subject}")
} catch (e: TokenExpiredException) {
    // the token is past its exp claim
} catch (e: JWTVerificationException) {
    // bad signature, wrong issuer or audience, missing claim, ...
}
```

Custom checks take a predicate:

```kotlin
JWT.require(algorithm)
    .withClaim("role") { claim, _ -> claim.asString() in setOf("admin", "owner") }
    .build()
```

### Decode without verifying

Decoding only parses the token. Never trust the result for authorization.

```kotlin
import io.github.kotlinmania.jwt.exceptions.JWTDecodeException

try {
    val decoded = JWT.decode(token)
    println(decoded.algorithm) // "HS256"
    println(decoded.getClaim("role").asString()) // "admin"
} catch (e: JWTDecodeException) {
    // not a well-formed JWT
}
```

### Read claims

| Registered claim | Accessor |
|---|---|
| `iss` | `decoded.issuer` |
| `sub` | `decoded.subject` |
| `aud` | `decoded.audience` |
| `exp` / `nbf` / `iat` | `decoded.expiresAt` / `decoded.notBefore` / `decoded.issuedAt` (`kotlin.time.Instant`) |
| `jti` | `decoded.id` |

Any claim is available through `decoded.getClaim(name)`, with `asString()`,
`asInt()`, `asLong()`, `asDouble()`, `asBoolean()`, `asDate()`, `asList(...)`,
`asMap()` and `isNull()`.

## Swift

Apple consumers can use the XCFramework:

```bash
./gradlew assembleJWTKMPXCFramework
```

The framework is written to `build/XCFrameworks/release/JWTKMP.xcframework`.

## Building from source

```bash
./gradlew build   # every target
./gradlew test    # every test this host can run, plus the Swift Export smoke test
```

## Contributing

Issues and pull requests are welcome. Please include a minimal reproduction and
the target where the problem appears.

## License

MIT. See [LICENSE](./LICENSE).

## Acknowledgments

This Kotlin Multiplatform port was created by **Sydney Renee** of
[The Solace Project](mailto:sydney@solace.ofharmony.ai) for
[KotlinMania](https://github.com/KotlinMania).

Thanks to [Auth0](https://github.com/auth0) for the original
[java-jwt](https://github.com/auth0/java-jwt) library.
