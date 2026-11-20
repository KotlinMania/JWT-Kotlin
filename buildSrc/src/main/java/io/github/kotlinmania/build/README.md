# Product build helpers

This directory contains the four Java helpers used to build the template's product
artifacts. Their source documentation uses Java 23+ Markdown JavaDoc (`///`).
The implementation still targets the Java 21 toolchain configured in
`buildSrc/build.gradle.kts`; changing documentation syntax does not change that target.

| Helper | Responsibility | Integration |
| --- | --- | --- |
| [NodeFfiBuilder](NodeFfiBuilder.java) | Compiles and links N-API addon binaries from supported binding settings. | The root build registers `buildNode…` tasks using `BuildToolTask`. |
| [SwiftManifestEnforcer](SwiftManifestEnforcer.java) | Adds a missing macOS platform declaration to a generated Swift package manifest. | The root build invokes it after tasks ending in `generateSPMPackage`. |
| [SwiftExportSharder](SwiftExportSharder.java) | Shards oversized generated Swift Export `.kt`, `.h`, and `.swift` files into `<= 1 MiB` shards. | Invoked after `SwiftExportTask` and `generateSPMPackage`. |
| [BuildToolTask](BuildToolTask.java) | Adapts a helper's `run(String...)` method to Gradle task execution. | Supplies class/argument inputs and propagates failures. |
| [Json](Json.java) | Reads JSON inputs and writes JSON receipts using Gradle's Groovy libraries. | Used by both product builders. |

These sources are compiled automatically as Gradle `buildSrc`. Their dependencies
are the Gradle API and `localGroovy()`. No independently versioned helper library
is downloaded. Workspace tools also compile `Json` and `SwiftManifestEnforcer`
directly from these source locations; the product build does not import their tools.

## NodeFfiBuilder

The existing Gradle integration constructs a task named `buildNode` plus the
configured FFI name. It depends on the npm header-install task, supplies the
project/addon/build directories and selected Node version/executable, and forwards
Gradle's offline setting. The helper itself does not install npm packages or compilers.

The argument contract is:

```text
--project-dir DIR --addon-dir DIR --build-dir DIR
[--node EXECUTABLE] [--node-version VERSION] [--headers-dir DIR]
[--cc EXECUTABLE] [--cxx EXECUTABLE] [--linker EXECUTABLE] [--offline]
```

`--help` prints this contract without building. Directory arguments resolve relative
to the calling process. `--project-dir` is the ownership boundary, not a base against
which other relative directory arguments are interpreted. Real addon, scratch,
native-source and output paths are checked against the owning project. Explicit
header/include/library inputs are not a complete hermetic dependency inventory.

The installed Node executable reports its version, platform and architecture and
resolves `node-addon-api`. Only x64 and ARM64 host runtimes are accepted. Supported
binding settings cover target/default lists, selected compiler/linker options and
simple `OS`/`target_arch` equality or inequality conditions. Unknown settings fail;
this is not a complete GYP evaluator. Recognized node-addon-api expansion markers
are handled internally rather than executed as arbitrary binding scripts.

Header selection uses an explicit directory first, then matching headers beside
the installed Node executable, then a release cache/download. Official release
files are checked against `SHASUMS256.txt`. Offline mode requires existing usable
inputs. Windows also needs the matching Node import library. Compiler executable
overrides are individual executable values, not shell command lines.

Outputs and side effects:

- Addon binaries replace `<addon>/build/Release/<target>.node`; on non-Windows
  hosts a leading `lib` is removed from the target's output filename.
- Release archives, checksum files, extracted headers and Windows import libraries
  are cached under `<build>/node-ffi/`.
- Temporary objects and logs use `<build>/tmp/node-ffi/` and are cleaned per call.
- The build receipt replaces `<build>/reports/node-ffi/<addon-directory-name>.json`.
  Errors before its path is established produce a diagnostic without a receipt.
- The ordinary command wait is five minutes, followed on timeout by forced process
  termination; the final forced-exit wait has no independent deadline.

`run` normally returns 0 or 1, restoring interrupt status when interrupted.
Cleanup/receipt failure throws `IllegalStateException`. Caches and output binaries,
including partial outputs on failure, are not all rolled back. `main` exits the JVM
with the returned status; the Gradle adapter calls `run` instead.

Public parsing, checksum, extraction and version-check methods are documented in
the source. `acquireReleaseHeaders` writes to its caller-selected build directory;
unlike `run`, it cannot establish an owning-project boundary by itself.

## SwiftManifestEnforcer

The current root build scans generated `Package.swift` files after package
generation and calls:

```kotlin
BuildToolTask.invoke(
    SwiftManifestEnforcer::class.java.name,
    manifest.absolutePath,
    "--receipt",
    manifest.parentFile.resolve("swift5-manifest-receipt.json").absolutePath,
)
```

`enforce(String)` is the pure transformation. It requires a supported tools header,
one `Package(...)` initializer and a `targets` argument. Explicit Swift language
overrides are rejected. When `platforms` is absent, it inserts
`platforms: [.macOS("15.0")]` while retaining the surrounding text.

An existing platform declaration is left unchanged, even if it names another
deployment target. The tools-version header is also left unchanged. The helper
does not compile Swift or prove effective language modes. Its intended policy is
generated dependency Swift 5 via tools version 5.9, separate from the authored
Swift 6 consumer harness; the receipt records that policy rather than measuring it.

`run(MANIFEST, "--receipt", RECEIPT)` reads UTF-8, updates the manifest only when
the transformation changes it, and always replaces the receipt. These writes are
not transactional: a failed receipt write does not undo a successful manifest edit.
The tokenizer is a limited structural scanner, not a full Swift/Kotlin parser.

## SwiftExportSharder

`SwiftExportSupport` invokes `SwiftExportSharder` immediately after `SwiftExportTask`
(`--swift-export-root <build/SwiftExport> --max-bytes <N>`, defaulting to `1048576`
bytes via `project.swiftExport.maxShardBytes`) and defensively on `build/SPMPackage`
after `generateSPMPackage`.

Any generated `.kt`, `.h`, or `.swift` file larger than `--max-bytes` is split at
declaration boundaries into `<= 1 MiB` shards (`_bridge_NNNN.kt` / `_objc_NNNN.kt`,
`_NNNN.h`, and `_NNNN.swift`), module descriptor JSON pointers (`cHeaderBridges` and
`swiftApi`) are updated to point to the generated shard directories, and the oversized
monolithic file is deleted immediately so Clang/Swift `DiagnosticBridge` and
Kotlin/Native never ingest multi-megabyte single-file translation units.

## BuildToolTask

Configure `toolClass` with a fully qualified helper class and `arguments` with an
ordered list. The existing template uses this pattern for Node builds:

```kotlin
toolClass.set(NodeFfiBuilder::class.java.name)
arguments.addAll(
    "--project-dir", projectDir.absolutePath,
    "--addon-dir", addonDirectory.absolutePath,
    "--build-dir", layout.buildDirectory.get().asFile.absolutePath,
)
```

The caller declares real task dependencies, files and outputs. The adapter's input
annotations alone do not make arbitrary helper operations cacheable. Invocation
occurs in Gradle's JVM/classloader; no shell or separate JVM is introduced.

Helpers expose a public static `run(String[])` method. A numeric return whose
`intValue()` is nonzero fails; null and nonnumeric returns are ignored. Checked
exceptions are wrapped in `GradleException`; runtime exceptions and errors propagate.
The package-prefix check is a dispatch convention, not a security sandbox.

The optional Gradle `--args` interface replaces the argument list after quote-aware
tokenization. It preserves empty quoted values and ordinary Windows backslashes.
It does not expand variables, wildcards or command substitutions. Setting the list
directly is preferable when arguments already exist as separate values.

Gradle creates instances of this abstract task type. The source declares no explicit
constructor; its implicit default constructor causes the Javadoc warning noted below.

## Json

`parse` and `read` return Groovy map/list/scalar/null values without imposing a schema.
An outer array lets the parser accept a single scalar root and reject multiple
comma-separated roots. `read` uses UTF-8. `stringify` returns compact JSON without I/O.

`write` creates parent directories and replaces a file with formatted UTF-8 JSON
and a trailing newline. It is neither append-only nor atomic; an I/O failure may
leave an incomplete destination. Parsing/serialization errors from Groovy propagate.
A receipt records a caller-supplied value and does not independently validate it.

## Documentation style and generation

The [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html#s7-javadoc)
was consulted. New JavaDoc uses `///`, summary fragments, blank comment lines between
paragraphs, and `@param`, `@return`, `@throws` order. Continued Markdown block tags
use two extra spaces. Documentation lines fit within 100 columns. Existing executable
code formatting is preserved rather than reformatted as part of a documentation edit.

Use Javadoc 23 or newer for Markdown comments. The installed tool checked here is
**Javadoc 25.0.4**. The build's Java 21 setting is unchanged; selecting `--release 21`
for the modern documentation tool does not select a Java 21 Javadoc executable.
Older Javadoc tools were not run, and the Gradle `javadoc` task was not used.

From the template directory, this command uses existing Gradle 9.8.2 library JARs.
Set `GRADLE_HOME` to that installed distribution before running it. If the cached
Gradle API JAR is absent, this command does not install or generate it.

```sh
doc_classpath="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/9.8.2/generated-gradle-jars/gradle-api-9.8.2.jar"
for dependency in "$GRADLE_HOME"/lib/groovy-[0-9]*.jar \
  "$GRADLE_HOME"/lib/groovy-json-*.jar "$GRADLE_HOME"/lib/jspecify-*.jar; do
  doc_classpath="$doc_classpath:$dependency"
done
source_dir=buildSrc/src/main/java/io/github/kotlinmania/build
javadoc --release 21 -Xdoclint:all --no-platform-links -notimestamp \
  -encoding UTF-8 -docencoding UTF-8 -charset UTF-8 \
  -classpath "$doc_classpath" -d buildSrc/build/docs/retained-helpers-javadoc \
  "$source_dir/NodeFfiBuilder.java" "$source_dir/SwiftManifestEnforcer.java" \
  "$source_dir/BuildToolTask.java" "$source_dir/Json.java"
```

Output is `buildSrc/build/docs/retained-helpers-javadoc/index.html`. The narrow
generation reads only these four source files and installed libraries; it does not
run Gradle, compile the product, start native tools or use platform tests.

The strict invocation with an additional `-Werror` reports one missing-comment
warning for `BuildToolTask`'s implicit default constructor and exits nonzero. That
constructor has no source declaration to annotate. The documented command retains
full doclint and reports the warning without converting it into a command failure.
No compiler warning policy, constructor or implementation is changed to avoid it.
