# Android publication acceptance and focused sync

The Android KMP plugin owns the Android AAR publication. These checks validate that
publication; they do not attach another artifact, flatten platform targets, alter
versions, or expose all implementation dependencies.

## Template contract

Keep private dependencies in `project.dependencies.commonMainBundle`. Put only
reviewed public signature dependencies in a catalog bundle selected by the optional
`project.dependencies.commonMainApiBundle` property. Existing platform-specific
source sets and direct `api(...)` declarations remain valid.

`androidPublicationBundle` stages the existing root and Android publications into
`build/verification/android-publication.zip`. It produces an artifact; publication
acceptance is now an explicit standalone command:

```sh
../tools/java-build-tool.sh AndroidPublicationVerifier build/verification/android-publication.zip \
  --group io.github.example --module example --version ACTUAL_VERSION \
  --public-class io/github/example/EntryPoint.class \
  --receipt build/reports/publication/android.json
```

Supply actual coordinates and public JVM class paths. Repeat
`--api-dependency group:root-module:Android-POM-module` for public signature edges. The
optional `--profile async-trait` is a project fixture contract, not a template
default. Metadata, bytecode and hashes do not prove consumer/runtime behavior.
Neither publication nor the Android workflow automatically invokes this verifier.
The Android and publish-dry-run workflows only request the bundle producer.

See [standalone tool inventory and commands](../../tools/build-automation/STANDALONE_TOOLS.md)
for publication pins, JNI/native artifacts, runtime evidence and project fixtures.

## Focused synchronization

Use JDK 21 or newer. The standalone launcher compiles Java directly using already installed Gradle JSON/TOML/archive libraries; it does not run Gradle. The workspace launcher
`tools/sync_template.sh` routes to this implementation; its reviewable source is
`verification/workspace-sync-template.sh` (install location: workspace `tools/`).
The Java workspace diagnostic is available through `tools/shared-build-sync/run.sh diagnose`. Generic replacement remains disabled. The Android planner inventories hidden YAML and YML files and changes no destination workflows or pins.

Example read-only plan from the shared workspace:

```sh
tools/sync_template.sh \
  --repo /Volumes/stuff/Projects/kotlinmania/quote-kotlin \
  --api-alias libs.proc.macro2.kotlin \
  --plan tools/shared-build-sync/plan.json
```

Name one existing checkout and, for a plain multi-project build, an explicit
`--module library`. Direct catalog calls and the template's property-selected
commonMain bundle are supported. Only explicitly reviewed aliases become public;
the private bundle and platform dependencies are retained. Other DSL layouts,
custom verifier overrides, target migrations, and conditional dependency changes
require manual review and fail explicitly. No source trees are copied wholesale.

Application requires a clean feature branch and a current ownership grant JSON
containing exact `repo`, `head`, `owner`, and `source_write_granted: true`:

```sh
tools/sync_template.sh \
  --apply-plan tools/shared-build-sync/plan.json \
  --grant tools/shared-build-sync/source-grant.json
```

The grant must come from the coordinator after checking held projects. A source
grant does not allocate a build or permit fleet application. Apply recomputes the
plan and refuses changed HEAD, source inputs, catalogs, properties, workflows,
dirty files and conflicting overrides. After committing, a new plan is needed
because HEAD changed; an already synchronized transform produces no new diff.
The tool does not run builds, commit, push, create PRs, publish, or roll back work.

## Independent Android consumer

`verification/android-consumer` depends only on Async Trait and Android system
properties root coordinates. Public fixture signatures use Syn, quote and
proc-macro2 types transitively. No source/project substitutions or mavenLocal are
configured. Optional `asyncRepository` and `systemPropertiesRepository` properties
route only their exact root/Android publications to an existing verified producer
staging repository; all imported dependencies still resolve as publications.

After an explicit local build allocation, use the existing shared wrapper:

```sh
./gradlew -p verification/android-consumer verifyAndroidConsumer \
  -PasyncVersion=<verified-version> \
  -PsystemPropertiesVersion=<verified-version> \
  -PconsumerCompileSdk=<allocated-sdk> \
  -PconsumerBuildRoot=<artifact-directory>/consumer-build \
  --project-cache-dir <artifact-directory>/consumer-project-cache \
  --no-daemon --max-workers=2 --no-configuration-cache
```

The caller must supply the existing SDK location and allocated Gradle/cache paths;
the fixture does not install an SDK or modify producer checkouts. Three named
host tests must actually execute and pass. Selection receipts require both roots
to select Android AARs. Raw compile/runtime paths are recorded after host tests,
including when an executed test fails. Invoke `AndroidClasspathVerifier` with that
`raw-classpaths.json`, `--configurations` and an explicit `--receipt` separately.
It checks each configuration independently, including nested AAR `libs/*.jar`.
The fixture no longer runs that check automatically.
An unexpected Android configuration/artifact layout fails for review. These host
tests do not replace Android device, Android Native, Linux ARM64, or Swift gates.

## Historical validation status

The following records predate standalone extraction and are not a new execution
claim. The current template has no discoverable Git repository at its path.
Current extraction validation is reported separately.

The initial verifier passed eight tests against Async Trait's existing verified
0.1.3 staging ZIP. Ten sync/classpath tests passed; a later conditional-override
rejection case and three affected preservation/idempotence tests also passed.
Actual cached proc-macro Android 0.1.3 plus annotations 23.0.0 has three duplicate
ApiStatus classes; 0.1.4 has none. The quote dry run is a proposal only.

The allocated exact-7e76298 consumer invocation (session 79670) configured and
resolved real Android AARs, then failed at the duplicate guard before compilation
or the three host tests (zero executed). It caught the genuine proc-macro 0.1.3
annotation collision. It also exposed a fixture error: combining compile and
runtime artifacts falsely compared annotations 13.0 with 23.0.0. The source-only
repair checks configurations independently and schedules diagnostics after tests;
its targeted regression passes. The repaired fixture has not executed in Gradle.
The slot was explicitly returned with terminal/daemon shutdown evidence for Anyhow;
no immediate retry was performed. New Gradle execution requires fresh allocation.
The earlier run described a local Git checkout without a remote; no remote was invented.
A tested source commit is distinct from tested propagation, exact-final CI, and
verified Maven publication. Those gates must complete before any rollout/release.


The complete target/runner contract and existing CI gaps are recorded in
[ALL_TARGET_VALIDATION.md](ALL_TARGET_VALIDATION.md). Warning relaxations are rejected by the scoped sync before any write.


## Shared Swift known-problem scan

`SwiftProblemScanner.java` and `swift-known-problems.json` provide a read-only, versioned
scan of the existing build, workflows (both `.yml` and `.yaml`), manifests,
consumer tests and generated bridge/runtime output. Run the central template
copy against one existing project; no source synchronization or build occurs:

```sh
./tools/java-build-tool.sh SwiftProblemScanner \
  --root "$PWD/libc-kotlin" \
  --generated-root "$PWD/libc-kotlin/build/SPMPackage/macosArm64/Debug" \
  --bridge-root "$PWD/libc-kotlin/build/SwiftExport/macosArm64/Debug" \
  --output "$PWD/template/build/reports/swift-scan.json"
```

Paths above are workspace-relative. Invoke the workspace launcher with an explicit
`--root` for a project generated from this template; `runBuildTool` is no longer a
buildSrc task. The scanner's Swift 6 manifest policy still needs review for generated
Swift 5 dependency packages. Do not blanket-copy build
scripts or apply isolated compiler patches to satisfy the scan. Each finding has
a stable rule ID, severity, canonical `SWIFT.md` recipe reference and next action.
The registry records warning overrides, weaker Swift language modes, generated
API deletion, implementation-only imports, known KotlinError/bridge patterns,
unverified exporter/bypass settings and unconditional consumer assertions.
Pattern findings require review against real compiler diagnostics. The scanner
never rewrites source, generated declarations, manifests or compiler artifacts.

The optional `--evidence receipt.json --required-test Class.behavior` interface
compares exact source/generated/toolchain hashes with a fresh generation receipt,
actual frontend command logs for **every generated dependency and consumer**
(`-swift-version 6 -warnings-as-errors`), and named test XML with no failures,
errors or skips. Repeat `--required-test` for all required behaviors. Add
`--require-acceptance` to fail when this evidence is absent or inconsistent.
Without that flag a successful source scan still reports runtime acceptance as
`unverified`. A consistent scoped receipt does not establish all-target coverage,
full-library behavior, publication or permission to adopt an isolated toolchain.

The strict defaults and manifest configuration enforcer are source policy only.
The empty template's unconditional Swift test assertions intentionally fail the
scanner until replaced with real project behavior. Current real full Libc CI
39499364932 at f4d72712 failed strict Swift compilation in generated
KotlinRuntimeSupport: implementation-only import without library evolution and
non-Sendable KotlinBase storage in KotlinError. Swift consumer execution was zero.
The isolated enum/cancellation/runtime candidates remain gated; no fleet adoption
is implied.


## Android publication host routing

The Android job verifies the real plugin-owned AAR and KMP root metadata together.
It does not initiate the root publication on Linux when Apple targets are present:
shared Native/cinterop metadata may require an Apple-capable host. The existing
macOS job stages the real root publication into the filesystem Maven repository,
records `git rev-parse HEAD` in `build/staging-deploy.source-head`, and uploads both
under a same-commit artifact name. The Android job depends on that job, downloads
the artifact into `build/root-publication`, and sets:

```
-Pproject.publication.android.rootStagingDirectory=build/root-publication/staging-deploy
-Pproject.publication.android.expectedRootHead=<exact 40-character commit>
```

The bundle takes only the root coordinate from this input and the Android coordinate
from local staging. A missing/stale receipt or missing root module fails; the existing
verifier still inspects actual root redirects, POM, GMM and AAR contents. This is
filesystem staging and same-run transport, not Central Portal upload. Android-only
projects without Apple targets may stage both publications locally. Mac hosts retain
the local-root route. Existing full-target build/test aggregates are unchanged.

Focused sync inserts the same guarded fragment, but deliberately does not overwrite
project workflows: existing publication overrides and workflow specialization require
review and a matching host route before propagation. The default template's Mac/Android
workflow route is source-only until its actual Gradle task graph, transported root
metadata and independent Android consumer have been executed at the same source head.
Linux ARM64 runtime routing is a separate concern and is not repaired by changing
publication prerequisites.


## Rust workspace and behavior parity

Run the declaration scanner through the Java build tools (Java 21):

```sh
tools/java-build-tool.sh RustParityScanner --manifest-index tools/shared-build-sync/manifest-index.json --kotlin-root libc-kotlin --mapping tools/shared-build-sync/mapping.json --output tools/shared-build-sync/cargo-parity.json --strict
```

The index pins the upstream repository and 40-character revision. Each manifest
entry supplies `upstream_path`, `local_path`, `sha256` and `git_blob_sha1`.
The optional `tree` entry supplies the complete Git tree JSON's `local_path`,
`sha256` and matching `revision`. Include root and relevant member manifests;
a missing tree, declared member, path dependency or target source is a finding.
A virtual root is workspace context, not an excluded crate. Explicit exclusions
remain visible; other excluded manifests require a scope rationale.

The report inventories workspace inheritance, dependency aliases, normal/build/dev
and target-specific edges, declared and implicit optional features, explicit and
automatically discovered tests, benches, examples, binaries, libraries and build
scripts. Preserve `harness=false`: those executables need their actual assertions
mapped. Review inherited default-feature ambiguity against the pinned Cargo
version. The scanner does not run Cargo, resolve transitive features, select
published variants, fetch source, or change catalogs and build scripts.

Mapping groups are `members`, `dependencies`, `features` and `targets`, keyed by
the report's `id` (or member `manifest`). Each reviewed entry needs `status`,
`rationale`, and `evidence` entries containing an existing file `path` and `sha256`.
Recognized classifications are `implemented`, `language-specific` and
`upstream-release-tooling`. A classification records a review, not acceptance.
Keep the actual resolved Kotlin runtime/test/tooling graph and published sibling
capability evidence alongside it; catalog declarations alone cannot establish
those requirements. Missing mappings fail `--strict`.

Use the established repository `tools/ast_distance` for the companion comparison,
or the existing workspace `bin/ast_distance` when the local tool is unavailable:

```sh
# Execute from the existing library checkout with explicit source/target paths.
tools/ast_distance --deep tmp/libc/src rust src/common/commonMain/kotlin/io/github/kotlinmania/libc kotlin
tools/ast_distance --symbol-parity tmp/libc/src src/common/commonMain/kotlin/io/github/kotlinmania/libc --kind function
tools/ast_distance --compare-functions tmp/libc/src/macros.rs rust src/common/commonTest/kotlin/io/github/kotlinmania/libc/MacrosTest.kt kotlin
```

Record the binary/configuration hashes, exact commands, upstream revision,
Kotlin HEAD and input hashes, raw reports and exit status. Preserve the tool's
`deep_symbol_inventory.txt` report under the artifact directory. Review missing
and divergent functions against real declarations, bodies, platform actuals and
upstream assertions. Name matching, file counts and similarity scores do not prove
behavior. For example, an acronym split may cause a false missing-name report;
a matched `expect` declaration still needs working `actual` implementations.
Do not rename idiomatic Kotlin or weaken tests to increase a score.

Cover member harnesses and inline tests as well as the root crate. Map each
retained platform to the same common assertions through its actual boundary;
require named real tests with no failures, errors or skips and preserve language
semantics that cannot be expressed as explicit review gaps. Scanner unit tests
exercise parsing and evidence integrity; they are not library runtime tests.

## Linux Native wrapper identity

The template compiles Linux C wrappers for the requested Native target and its
sysroot on both Linux and macOS hosts. Archive inspection is now an explicit
`tools/java-build-tool.sh NativeArchiveVerifier ARCHIVE --target linux_arm64
--report REPORT` command. It checks actual ELF relocatable members, architecture
and archive structure; optional `--object OBJECT` also requires that object digest.
It writes only the requested report and is no longer called by wrapper compilation.

Libc's representative ARM64 cross-link is recorded under workspace
`template/build/reports/java-native-verifiers/`;
it does not establish Linux ARM64 runtime behavior or a template build pass.

The focused Android sync does not rewrite Native wrapper blocks or install this
Native guard into existing projects. Those source ranges require a separately
reviewed owner patch; no fleet propagation is implied.

## Effective dependency and Native binding receipts

[DEPENDENCY_VISIBILITY.md](DEPENDENCY_VISIBILITY.md) documents the opt-in public-API
Gradle init script, explicit target configurations and Xcode context, artifact
identities and cinterop producer edges. Its representative Libc audit ran against
retained real build outputs; no template build or fleet propagation is implied.
The focused Android sync does not install this Native/Swift audit automatically.

## Native wrapper dependency directories

The template honors `KONAN_DATA_DIR` for compiler-owned inputs. Wrapper tasks reuse
an existing nonempty dependency directory only when the compiler's `.extracted`
receipt names it. Wrapper-owned downloads and extraction use
`build/native-wrapper-dependencies`; they never manufacture compiler receipts.
Offline execution rejects a missing archive before invoking curl. Target branches
use the helper's returned directory, which can be either the registered compiler
input or the private extraction, while retaining target/sysroot and architecture
checks.

The shared helper matches Libc PR135's tested implementation; exact-head compiler/link/runtime
receipts remain necessary for each project and target. The focused Android sync
route does not rewrite existing FFI specialization or apply this change to a fleet.
