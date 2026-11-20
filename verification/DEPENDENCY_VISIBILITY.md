# Dependency and Native binding visibility

Run the Gradle init script in an existing Kotlin Multiplatform checkout. Select
configuration names explicitly; use the resolvable `swiftExportClasspathResolvable`
for compiler tooling. `swiftExportClasspath` is a declaration bucket.

```sh
./gradlew -I verification/dependency-visibility.init.gradle \
  verifyDependencyVisibility --offline --no-daemon --max-workers=2 \
  --no-configuration-cache \
  -Pkotlin.native.home=<existing-sdk-directory> \
  -Pverification.dependency.configurations=jvmCompileClasspath,macosArm64CompileKlibraries,linuxArm64CompileKlibraries,swiftExportClasspathResolvable \
  -Pverification.dependency.swiftTask=macosArm64DebugSwiftExport
```

Run with the normal Xcode slice environment used by the existing Swift workflow:
`SDK_NAME=macosx`, `CONFIGURATION=Debug`, `ARCHS=arm64`, `BUILT_PRODUCTS_DIR`,
`TARGET_BUILD_DIR`, `FRAMEWORKS_FOLDER_PATH`, `MACOSX_DEPLOYMENT_TARGET` and
`DEPLOYMENT_TARGET_SETTING_NAME`. KGP registers concrete Swift export tasks only
for that context. Supply existing, appropriately isolated output/cache paths
through the normal validation recipe. An absent selected task or unresolvable
configuration fails with a recorded gap. Project-specific task/configuration names
require explicit selection; no target or source-set rewrite occurs.

The default report is `build/reports/dependency-visibility.json`.
`-Pverification.dependency.report=<absolute-report-path>` selects another output.
When inspecting a retained build directory, route Kotlin build reports through
`-Pkotlin.build.report.file.output_dir=<private-directory>` and account for Gradle
HTML problems reports separately; task output declarations alone do not enumerate
all build-service report writes.

The JSON records common dependency buckets, direct source-set edges, effective
`allKotlinSourceSets`, selected resolved coordinates/variant attributes/file or
directory hashes, Native cinterop definitions/includes/options and actual producer
edges, configured binary linker options, SDK properties, publication artifacts and
actual Swift task input files. It identifies compiler-tooling JARs separately from
the Native library KLIB. Native SDK standard libraries and platform libraries are
compiler inputs, so an empty external Native dependency configuration is not a
missing-library diagnosis. Plugin-populated default dependencies can appear after
resolution; source declarations and effective resolved artifacts are separate data.

The task reads configuration and artifacts. Real compile/link commands, archive
machine checks, semantic KLIB inspection, fresh generated Swift6/Werror frontends,
named runtime tests and publication-consumer tests remain separate acceptance
receipts. Absent publication artifacts are reported as unbuilt. Resolving a tooling
JAR does not turn the Native library into a JVM artifact or execute Swift export.

The same script was exercised against Libc's retained current-source macOS Native
KLIB and selected JVM/macOS/Linux ARM64/tooling configurations. The audit-only run
executed one task, retained all product/SDK/dependency inputs unchanged, and produced
zero audit errors. Its companion product run passed unfiltered lint, six JVM macro
and twelve macOS Native macro/filesystem tests, and linked an ELF183 Linux ARM64
test binary. Linux runtime and full-target/Swift release gates are independent.
