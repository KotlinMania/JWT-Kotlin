# Fixing Swift Export

This is the short, operational version of `SWIFT.md`. Use it when a
KotlinMania repo already has Swift Export wired in and CI or local
`embedSwiftExportForXcode` fails.

## First Split The Failure

Run the same task CI runs:

```bash
BUILT_PRODUCTS_DIR="$PWD/build/swift-test" \
TARGET_BUILD_DIR="$PWD/build/swift-test" \
SDK_NAME=macosx \
CONFIGURATION=Debug \
ARCHS=arm64 \
FRAMEWORKS_FOLDER_PATH=Frameworks \
MACOSX_DEPLOYMENT_TARGET=14.0 \
DEPLOYMENT_TARGET_SETTING_NAME=MACOSX_DEPLOYMENT_TARGET \
./gradlew embedSwiftExportForXcode --no-configuration-cache --console=plain
```

If Swift Export is configured, `./gradlew test --no-configuration-cache`
must also run the Swift smoke test.

## Swift Export Worker Classpath

This error can appear during `macosArm64DebugSwiftExport`:

```text
NoClassDefFoundError: kotlinx/coroutines/internal/intellij/IntellijCoroutines
```

It is not the downstream Swift build failure by itself: Gradle may still
finish `BUILD SUCCESSFUL`. But it is also not random noise. The missing
class is provided by JetBrains' IntelliJ-flavored coroutine artifact, not
by normal `org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm`.

Do not add jars to `buildscript` or the ordinary project dependency
classpath. KGP runs Swift Export in an isolated Gradle worker whose
classpath comes from the `swiftExportClasspath` configuration. The build
script must fill that configuration directly:

```kotlin
val intellijCoroutinesVersion =
    providers.gradleProperty("versions.intellij.coroutines").getOrElse("1.10.2-intellij-1")

val projectDependencyHandler = project.dependencies
configurations.configureEach {
    if (name == "swiftExportClasspath") {
        dependencies.add(projectDependencyHandler.create("org.jetbrains.kotlin:swift-export-embeddable:$kotlinVersion"))
        dependencies.add(
            projectDependencyHandler.create(
                "org.jetbrains.intellij.deps.kotlinx:kotlinx-coroutines-core-jvm:$intellijCoroutinesVersion",
            ),
        )
    }
}
```

`swift-export-embeddable` must be listed explicitly. KGP normally adds it
with Gradle `defaultDependencies`, but adding any dependency to
`swiftExportClasspath` disables that default population.

`gradle.properties` carries the IntelliJ coroutine patch version:

```properties
versions.intellij.coroutines=1.10.2-intellij-1
```

## Forbidden Fixes

Do not use these to make Swift Export pass:

- `@HiddenFromObjC`
- `@ObjCName`
- `@JvmName`
- `@JvmMultifileClass`
- compatibility `typealias` declarations that preserve a bad exported name
- disabling targets
- scoping `allWarningsAsErrors=false` to hide warnings from our own API bridge
- adding ordinary coroutines jars or buildscript jars for the
  `IntellijCoroutines` worker error

Annotation hiding is not an API repair. If something is not API, make it
`internal`. If it is API, make it bridgeable.

## The Five-Class Sweep

Before every Swift release, inspect `src/commonMain/kotlin` for these
public API hazards:

1. Public mutable collections: `MutableList`, `MutableMap`, `MutableSet`
2. Public unconstrained generic classes or wrappers
3. Public function types: `() -> X`, `(A) -> B`, `suspend (...) -> ...`
4. Public `Result`, custom generic result wrappers, or `Throwable` subclasses
5. Public `Pair` / `Triple`

Fix all exported shapes, not just the one CI happened to trip first.

## Structural Fixes

### Mutable Collections

Public surface:

```kotlin
val items: List<Item>
val byName: Map<String, Item>
```

Internal implementation may still use mutable collections. Expose
read-only views or copy-on-write helpers publicly:

```kotlin
internal val mutableItems: MutableList<Item> = ArrayList()
val items: List<Item>
    get() = mutableItems
```

This applies to public properties on mutable implementation classes too.
A class may still mutate internally, but `val configs: MutableList<Config>`
is a Swift Export ABI leak. In Kotlin/Native link output this can surface
as:

```text
Compilation failed: Global 'ktypew:kotlin.collections.MutableList' already exists
```

When that happens, inspect the generated Swift bridge for
`kotlin.collections.MutableList` / `MutableMap` / `MutableSet`, then fix
the source declaration that exported it.

### Generic Runtime Types

If the generic implementation is not the actual public design, make the
implementation `internal` and expose a bridgeable public return type:

```kotlin
internal class ZipEq<A, B>(...) : Iterator<Pair<A, B>>

fun <A, B> zipEq(left: Iterator<A>, right: Iterator<B>): Iterator<Pair<A, B>> =
    ZipEq(left, right)
```

If the generic is genuinely required by public design, leave a one-line
comment explaining why:

```kotlin
// generic by design: callers choose the parser output type.
```

### Function Types

Replace public function-type positions with named SAMs:

```kotlin
fun interface ParseProgressCallback {
    operator fun invoke(byteOffset: UInt, hasError: Boolean): Boolean
}
```

Use `operator fun invoke` when existing call sites already use
`callback(...)`.

### Result, Throwable, And Errors

Do not export `kotlin.Result<T>`, custom generic sealed results, or
classes extending `Throwable`, `Exception`, or `RuntimeException`.

Use concrete outcome/error types:

```kotlin
class ParseResult internal constructor(
    val value: ParseTree?,
    val error: ParseError?,
) {
    init {
        require((value == null) != (error == null)) {
            "ParseResult must carry exactly one of value or error"
        }
    }

    fun isSuccess(): Boolean = value != null
    fun isFailure(): Boolean = error != null
}

data class ParseError(val message: String)
```

No `!!`, no `@Suppress("UNCHECKED_CAST")`, and no sealed Ok/Err variants
for Swift-facing result APIs.

### Pair And Triple

Replace public `Pair<A, B>` or `Triple<A, B, C>` with named record
classes:

```kotlin
data class TokenRange(val start: Int, val stop: Int)
```

## Names That Must Be Renamed

If Swift or Java rejects an emitted name, rename the Kotlin declaration or
file and migrate callers. Do not preserve the old exported spelling with
a typealias or platform naming annotation.

Common Swift-problem names include:

- `Type`
- `Error`
- `Result`

Pattern:

```kotlin
// Bad exported name
class Type

// Good exported name
class SynType
```

## Inspect Generated Swift

When `macosArm64DebugSwiftExport` succeeds but
`macosArm64DebugBuildSPMPackage` or direct `xcodebuild` fails, inspect the
generated package:

```bash
rg -n "error:|warning:" build/xcodebuild-swift-export.log
rg --files build/SPMPackage/macosArm64/Debug/Sources
```

The module source is usually under:

```text
build/SPMPackage/macosArm64/Debug/Sources/<ModuleName>/<ModuleName>.swift
```

Map each Swift diagnostic back to Kotlin source shape. Common examples:

- `type member must not be named 'Type'` -> rename the Kotlin enum/class.
- property `label` plus method `label()` -> rename the Kotlin property.
- `init(fileName:)` overriding `init(input:)` -> align constructor
  parameter labels in the Kotlin subclass and superclass.
- exported collection implementation subclasses Swift's stdlib wrappers ->
  make the implementation internal or expose a simpler public type.

Do not edit generated Swift. Fix the Kotlin declarations that generated it.

## Expect/Actual Leaks

If an `internal expect fun` leaks into the Swift bridge and produces an
undefined symbol, remove the unnecessary `expect` and keep the function
as a plain platform-local `internal fun` in the source set that needs it.

Only do this when every caller is already platform-local. If common code
really needs the contract, keep `expect`/`actual` and expose behavior
through a bridgeable public API.

## Coroutine Export

Only repos that export `Flow` or suspend functions need the coroutine
runtime setup:

```kotlin
swiftExport {
    moduleName = frameworkName
    flattenPackage = projectNamespace
    @OptIn(org.jetbrains.kotlin.gradle.swiftexport.ExperimentalSwiftExportDsl::class)
    configure {
        settings.put("enableCoroutinesSupport", "true")
    }
}
```

For coroutine/runtime Swift Export generated-code warnings only, `SWIFT.md`
allows relaxing `allWarningsAsErrors` for generated `compileSwiftExport*`
and `linkSwiftExportBinary*` tasks. Before using that exception, confirm
our own bridge has no source-shape errors.

## Verification

After each structural repair:

```bash
./gradlew embedSwiftExportForXcode --no-configuration-cache --console=plain
./gradlew test --no-configuration-cache --console=plain
```

If the bridge file is generated, audit it:

```bash
grep -nE "as kotlin\\.Function[0-9]+<|as io\\.github\\.kotlinmania\\.[^<]+<kotlin\\.Any" \
    build/SwiftExport/macosArm64/Debug/files/*/*.kt
```

Every match from our module is a source API defect. Fix the source; do
not silence the compiler.
