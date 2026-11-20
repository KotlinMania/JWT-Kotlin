# Full local build and test: target/runner contract

This is the template's 17-target matrix, derived from its actual target declarations,
`nativeTargetNames`, `fullTargetBuildTaskNames`, `hostTests`, and checked-in workflows.
It is a plan, not an execution report. Every runtime below is currently unverified
for this template revision. Preserve legitimate platform-specific projects: an
Android-only library is not made portable by copying extra target declarations.

## Aggregate command and evidence

On the allocated Apple Silicon macOS/Xcode host, the aggregate invocation is:

```sh
./gradlew test build --continue --rerun-tasks \
  --no-daemon --max-workers=2 --no-configuration-cache --console=plain
```

Use the coordinator-approved output/cache isolation recipe. This command attempts
all declared build dependencies and all host-runnable tests. It cannot execute
Linux/Windows/Android/device-only binaries on macOS. Any disabled target or skipped
runtime remains unverified. A successful aggregate exit is not an all-target pass.

Each runner below must save the exact source/dependency revisions, executed task
log, actual nonempty library and test binaries, named test results/counts with no
unexplained skips, and failures. Use `--rerun-tasks` for the final runtime gate so
cached task success cannot substitute for actual test execution. Archive artifacts
and compiler commands; a task name, file extension or aggregate status is insufficient.
Run serialized under coordinator allocation; do not dispatch duplicate CI.

## Exact existing task matrix

Append the common flags above (including `--continue --rerun-tasks`) to each Gradle
command. Task names here are source-declared or existing KMP workflow task names;
this revision's task graphs have not yet been validated by an allocated invocation.

| Target | Build/link command | Runtime command and runner | Current CI coverage/gap |
|---|---|---|---|
| JVM | `./gradlew jvmMainClasses jvmTestClasses jvmJar` | `./gradlew jvmTest` on JDK 21 | `jvm.yml` exists and executes tests; **ci.yml does not call it**. |
| Android JVM | `./gradlew compileAndroidMain compileAndroidHostTest compileAndroidDeviceTest assembleAndroidMain assembleAndroidDeviceTest` | `./gradlew testAndroidHostTest` on host JVM, **and** `./gradlew connectedAndroidDeviceTest` with an owned supported emulator/device and recorded serial/API/ABI | android.yml runs host tests and assembles device tests; **does not boot a device or execute device tests**. |
| JS | `./gradlew jsMainClasses jsTestClasses` | `./gradlew jsNodeTest jsBrowserTest` with pinned Node and real Chrome runner | js.yml invokes both runtimes; execution/results at this revision unverified. |
| Wasm JS | `./gradlew wasmJsMainClasses wasmJsTestClasses` | `./gradlew wasmJsNodeTest wasmJsBrowserTest` with pinned Node and Chrome | wasm.yml invokes both; execution/results unverified. |
| Wasm WASI | `./gradlew wasmWasiMainClasses wasmWasiTestClasses` | `./gradlew wasmWasiNodeTest` with the pinned supported WASI runtime | wasm.yml invokes Node/WASI; execution/results unverified. |
| Linux x64 | `./gradlew linuxX64Binaries linuxX64TestBinaries` on Linux x64 | `./gradlew linuxX64Test` on Linux x64 | linux.yml runs x64 tests. |
| Linux ARM64 | `./gradlew linuxArm64Binaries linuxArm64TestBinaries` on supported Linux ARM64 build runner | `./gradlew linuxArm64Test` on ARM64 Linux or a verified equivalent runtime | linux.yml **only cross-compiles ARM64 main**, no ARM64 test execution. |
| Windows x64 | `./gradlew mingwX64Binaries mingwX64TestBinaries` on Windows x64 | `./gradlew mingwX64Test` on Windows x64 | windows.yml invokes tests; final artifact/execution proof unverified. |
| Android Native ARM64 | `./gradlew androidNativeArm64Binaries androidNativeArm64TestBinaries` | **No template device execution harness exists.** Must execute the produced native test binary on owned ARM64 Android and preserve exit/test output. | android-native.yml **compiles main only**; no link/test-binary/device execution proof. |
| Android Native x64 | `./gradlew androidNativeX64Binaries androidNativeX64TestBinaries` | **No template device execution harness exists.** Must execute native test binary on owned x64 Android emulator/device. | android-native.yml compiles main only. Android system-properties PR88 has a project-specific x64 harness, not a generic template runner. |
| macOS ARM64 | `./gradlew macosArm64Binaries macosArm64TestBinaries` on Apple Silicon macOS/Xcode | `./gradlew macosArm64Test` on Apple Silicon macOS | macos.yml invokes tests; correct host architecture/results require proof. |
| iOS ARM64 device | `./gradlew iosArm64Binaries iosArm64TestBinaries` on macOS/Xcode | **No template device execution harness exists.** Needs an owned, signed/provisioned iOS device harness; simulator success does not execute this target. | ios.yml compiles device main and assembles XCFramework; device runtime absent. |
| iOS simulator ARM64 | `./gradlew iosSimulatorArm64Binaries iosSimulatorArm64TestBinaries` | `./gradlew iosSimulatorArm64Test` on Apple Silicon macOS with installed/bootable iOS simulator | ios.yml invokes simulator tests; execution/results unverified. |
| tvOS ARM64 device | `./gradlew tvosArm64Binaries tvosArm64TestBinaries` on macOS/Xcode | **No template device execution harness exists.** Owned tvOS device runner required. | tvos.yml compiles main; device runtime absent. |
| tvOS simulator ARM64 | `./gradlew tvosSimulatorArm64Binaries tvosSimulatorArm64TestBinaries` | `./gradlew tvosSimulatorArm64Test` on Apple Silicon macOS with tvOS simulator runtime | tvos.yml invokes simulator tests; execution/results unverified. |
| watchOS ARM64 device | `./gradlew watchosDeviceArm64Binaries watchosDeviceArm64TestBinaries` on macOS/Xcode | **No template device execution harness exists.** Owned watchOS device runner required. | watchos.yml compiles main; device runtime absent. |
| watchOS simulator ARM64 | `./gradlew watchosSimulatorArm64Binaries watchosSimulatorArm64TestBinaries` | `./gradlew watchosSimulatorArm64Test` with installed compatible watchOS simulator runtime | watchos.yml invokes simulator tests; execution/results unverified. |

The unavailable device commands are deliberately not fabricated. Until a real
runner/harness exists and executes, those cells prevent an all-target pass.
For the configured XCFramework, also execute
`./gradlew assemble<project.frameworkName>XCFramework` using the actual property.
Enabled JNI, C interop and Node N-API channels require their real build and runtime
tests in addition to the Kotlin target tests.

## Strict Swift export: project, generated dependencies, and tests

Swift export is an additional language boundary, not an eighteenth KMP target.
The template source correction now:

- Sets Kotlin warnings-as-errors for all compilation tasks, including generated
  Swift bridge/runtime Kotlin, and rejects a task-time relaxation.
- Keeps generated dependency/project packages at Swift tools 6 or higher with
  explicit `swiftLanguageModes: [.v6]`; weaker package/target overrides fail for
  source review. Existing platform and C settings are preserved, with the prior
  macOS 15 deployment default added only when no platform settings exist.
- Supplies `verification/strict-swift.xcconfig` to KGP's Xcode subprocess. It sets
  `SWIFT_VERSION = 6.0`, `SWIFT_TREAT_WARNINGS_AS_ERRORS = YES`, and C warnings as errors.
  The target/configuration-prefixed SPM task hooks match the actual KGP suffixes.
- Runs the harness with explicit `-swift-version 6` and `-warnings-as-errors` and
  rejects zero executed Swift Testing tests. The harness also declares `.v6`.
- Stops rewriting generated Swift API bodies or deleting generated declarations.
  Actual generator diagnostics must surface; no patched exporter is adopted here.

The existing host aggregate uses `swiftExportSmokeTest`, which supplies the Xcode
variables to its owned nested embed invocation. The direct equivalent requires:

```sh
XCODE_XCCONFIG_FILE="$PWD/verification/strict-swift.xcconfig" \
BUILT_PRODUCTS_DIR="$PWD/build/swift-test/Debug" TARGET_BUILD_DIR="$PWD/build/swift-test/Debug" \
SDK_NAME=macosx CONFIGURATION=Debug ARCHS=arm64 FRAMEWORKS_FOLDER_PATH=Frameworks \
MACOSX_DEPLOYMENT_TARGET=15.0 DEPLOYMENT_TARGET_SETTING_NAME=MACOSX_DEPLOYMENT_TARGET \
./gradlew embedSwiftExportForXcode --no-daemon --max-workers=2 --no-configuration-cache

swift test --package-path src/swift/swift-test-harness -v \
  -Xswiftc -swift-version -Xswiftc 6 -Xswiftc -warnings-as-errors
```

These are review commands, not permission to start an unallocated nested build.
The existing aggregate's nested invocation still needs recipe review for artifact
isolation and the shared worker limit before full validation. Do not run it
concurrently with another allocated command.

Required evidence includes actual compiler command lines for each generated Swift
module (KotlinRuntimeSupport, KotlinCoroutineSupport, exported project/dependency
modules) and test modules showing Swift 6 plus warnings-as-errors. A Swift 6 tool
version, a manifest declaration, or a Java policy regression alone is not proof
of compilation. The current template harness is an import-only placeholder with
`#expect(true)`/`XCTAssertTrue(true)`; projects need meaningful API/runtime tests
and explicit expected test names/counts before a real-library Swift pass.

## Remaining source/CI gaps and current result

- The CI orchestrator omits its existing JVM reusable workflow.
- Device tests, both Android Native runtimes, Linux ARM64 runtime, and Apple device
  runtimes are absent from the template CI execution surface.
- The template still has an inherited Android device-test annotations exclusion.
  This can mask a producer packaging defect and requires separate source review;
  it is not accepted as duplicate-free evidence.
- This strict-Swift correction and the repaired Android consumer fixture are
  source-only at this revision. The earlier allocated Android consumer run
  configured/resolved AARs but failed at the old guard with **zero host tests**.
- Ratatui's independent four Android-host plus four JVM tests substantiate the
  reused AAR/API/per-configuration checker in that representative project only.
- No unsupported runtime, full KMP/Swift gate, exact-final CI, fleet rollout, or new
  Maven publication is claimed. Each missing runner remains explicitly unverified.
