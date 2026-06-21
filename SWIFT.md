# Swift Export Rollout — Recipe for the Claude GitHub Agent

> **Self-contained instruction set.** This file lives at the root of every
> repository that has the canonical KotlinMania Swift Export rollout
> committed to its `automation/swift-export-rollout` branch. The Claude
> GitHub Agent will be assigned a per-repo issue that asks it to walk this
> recipe top-to-bottom and finish the rollout by pushing the branch and
> opening the pull request. Nothing in this recipe depends on context from
> outside the current repository.

## TL;DR

A workspace-wide rollout already produced a local branch named
`automation/swift-export-rollout` with a single canonical commit (the
"rollout commit") that wires the Kotlin → Swift Export → SPM → swift-test
loop into the repository. **Your job, Claude, is to:**

1. **Push** the local `automation/swift-export-rollout` branch to `origin`.
2. **Open a pull request** against the base branch the rollout was made
   from (NOT necessarily `main`), using the title and body template in
   [§ PR template](#pr-template) below.
3. **Verify** the PR landed cleanly and CI was kicked off.
4. **Comment on the assigned issue** linking the new PR and close the
   issue with a one-line summary.

Do **NOT** modify any source files. Do **NOT** rebase, squash, or amend
the rollout commit. Do **NOT** change the branch base. If anything in
the precondition checks fails, [stop and report](#what-to-do-when-the-recipe-can-not-be-followed)
rather than improvising.

---

## Background — what's already on this branch

The rollout commit applies a strict, idempotent set of five changes
that are byte-identical across every KotlinMania repository it touched.
These were produced by `automation-artifacts/swift-export-rollout/apply.sh`
in the workspace, mirroring the canonical pattern that landed in
[`libc-kotlin`](https://github.com/KotlinMania/libc-kotlin) and was
proven end-to-end with a working `swift test` invocation against the
`embedSwiftExportForXcode`-produced Swift Package Manager package.

### The five changes

1. **`build.gradle.kts`** — **both** iOS Simulator framework binaries
   (`iosSimulatorArm64` *and* `iosX64`) now have `isStatic = true`.
   The Swift Export SPM bridge expects a static framework for the iOS
   Simulator so that the SPM linker can pull in the symbol
   implementations directly. Both simulator slices must match — if
   `iosSimulatorArm64` is static and `iosX64` is left dynamic, the
   `assembleDebugIosSimulatorFatFrameworkFor<Name>XCFramework` task
   fails with:

   ```
   Cannot create a fat framework from:
     <Name> - arm64 - static
     <Name> - x64 - dynamic
     All input frameworks must be either static or dynamic
   ```

   The other Apple targets (`iosArm64`, `tvosArm64`,
   `tvosSimulatorArm64`, `watchos*`, `macosArm64`) stay dynamic.

   > **Note**: earlier versions of this recipe and of `apply.sh` flipped
   > only `iosSimulatorArm64`. Repos that landed the rollout before this
   > correction need a follow-up commit adding `isStatic = true` to
   > `iosX64`. http-kotlin's `build.gradle.kts:218-231` is the working
   > reference shape.

2. **`.github/workflows/swift.yml`** — a new platform workflow with a
   `workflow_call:` trigger, matching the shape of the existing
   `macos.yml` / `ios.yml` / `tvos.yml` / etc. workflows. It:
     - Sets every Xcode-style environment variable that the Kotlin
       Multiplatform plugin's `BuildSPMSwiftExportPackage` task reads
       as input. The list includes `BUILT_PRODUCTS_DIR`,
       `TARGET_BUILD_DIR`, `SDK_NAME=macosx`, `CONFIGURATION=Debug`,
       `ARCHS=arm64`, `FRAMEWORKS_FOLDER_PATH=Frameworks`,
       `MACOSX_DEPLOYMENT_TARGET=14.0`, and crucially
       `DEPLOYMENT_TARGET_SETTING_NAME=MACOSX_DEPLOYMENT_TARGET` —
       which Xcode normally injects per-target but the Kotlin plugin
       has no default for. Without it,
       `BuildSPMSwiftExportPackage` fails its property validation
       with `property 'deploymentTargetSettingName' doesn't have a
       configured value`.
     - Runs `./gradlew embedSwiftExportForXcode --no-configuration-cache`
       to populate `build/swift-test/` with a `lib<Module>.a` static
       archive containing every `@ExportedBridge`-annotated function
       generated from the upstream Kotlin source, plus four
       `.swiftmodule/` bundles named `<Module>`, `KotlinRuntimeSupport`,
       `ExportedKotlinPackages`, and `KotlinRuntime`.
     - Changes directory into `swift-test-harness/` and runs
       `swift test`, which compiles and runs the smoke test against
       the just-produced Swift module.
     - Uploads the static archive, swiftmodule bundles, and the full
       generated SPM package tree as a `swift-export-artifacts` build
       artifact on both success and failure so a broken run is
       debuggable from the GitHub Actions run summary.

3. **`.github/workflows/ci.yml`** — a four-line `swift:` job is wired
   in next to the existing `wasm:` and `js:` orchestrator entries with
   the same `permissions: contents: read` ceiling. This makes the
   `swift.yml` workflow run as part of the regular CI orchestration.

4. **`swift-test-harness/`** — a new top-level directory containing:
     - `Package.swift` — a Swift Package Manager package depending on
       the Kotlin-generated SPM at
       `../build/SPMPackage/macosArm64/Debug` with one `testTarget`
       (`SwiftTestHarnessTests`). The testTarget carries a
       `linkerSettings` block with
       `unsafeFlags(["-L", "../build/swift-test", "-l<Module>"])` so
       that `swift test` (run outside an `xcodebuild`-driven
       invocation) can resolve the `__root____*` and KotlinError
       symbols from the static archive that `embedSwiftExportForXcode`
       drops next to the Sources/ tree. This linker hint is necessary
       because the Kotlin plugin's generated `Package.swift` does NOT
       reference its own `lib<Module>.a` as a binary target (one of
       the two upstream gaps noted in [§ Known upstream gaps](#known-upstream-gaps)
       below).
     - `Tests/SwiftTestHarnessTests/<Module>ExportTests.swift` — a
       smoke test that does `import <Module>` and asserts a single
       `XCTAssertTrue(true, ...)`. Because the import is at the
       module level, the file's mere ability to compile proves that
       `<Module>.swiftmodule` exists and is correctly placed; the
       fact that the test executable links proves the static archive
       is reachable; the fact that the test runs proves the full
       loop is green for this repo.
     - `.gitignore` — ignores the SPM `.build/` output directory.

5. **`.gitignore`** — appended with a Swift/Xcode workspace state
   section: `.swiftpm/`, `DerivedData/`, `*.xcuserstate`,
   `*.xcworkspace/xcuserdata/`, `*.xcodeproj/xcuserdata/`. The
   Kotlin Swift Export staging directory `build/swift-test/` is
   already covered by the existing `build/` rule.

### Background — what Swift Export does

Kotlin 2.2+ ships an experimental Swift Export feature
(`kotlin.experimental.swift-export.enabled`) that emits idiomatic
Swift bindings for a Kotlin Multiplatform module's exported API.
Instead of consuming the Kotlin output through Objective-C interop
(the legacy approach that produces `KotlinInt`, mangled names, and
`underscore_prefixed_calls`), Swift consumers get a real
`.swiftmodule` they can `import <Module>` against, with Kotlin
typealiases mapped to native Swift types, generic parameters
preserved, and nullability flowing through correctly.

The KotlinMania workspace standardized on Swift Export across every
repository that ships an XCFramework so that downstream Swift / Xcode
consumers don't have to opt into the legacy interop. The rollout
this recipe describes is the mechanical part — wiring up the build,
the test harness, and the CI workflow.

---

## Preconditions to verify before acting

Run these checks in order. If any of them fails, stop and report —
**do NOT attempt to fix the repository state**; the workspace-level
rollout will be re-run instead.

### 1. The rollout branch exists locally

```sh
git rev-parse --verify automation/swift-export-rollout
```

Must print a commit SHA. If this errors with "unknown revision", the
rollout never landed in this repo and there's nothing to push.

### 2. The rollout commit is the tip of the branch

```sh
git log -1 --format='%s' automation/swift-export-rollout
```

Must print exactly:

```
Wire Kotlin → Swift Export → swift test loop into CI
```

If the tip commit's subject is something else, someone has amended,
rebased, or added commits on top — stop and report.

### 3. The rollout files are present at the tip

```sh
git show --stat automation/swift-export-rollout
```

Must list at minimum:

- `build.gradle.kts` modified (1 insertion if multi-line iosSimulatorArm64 form, 4 insertions and 1 deletion if single-line form)
- `.github/workflows/swift.yml` new
- `.github/workflows/ci.yml` modified (4-line addition)
- `swift-test-harness/Package.swift` new
- `swift-test-harness/.gitignore` new
- `swift-test-harness/Tests/SwiftTestHarnessTests/<Module>ExportTests.swift` new
- `.gitignore` modified

It may also include this `SWIFT_EXPORT_ROLLOUT.md` file at the repo
root — that's the recipe being committed alongside the rollout.

If any of the seven (or eight, counting this recipe) files are missing
from the diff, stop and report.

### 4. The base branch is known

The rollout branch was created off the repo's HEAD at the time of the
rollout — that's the **base branch**, and that's where the PR should
target. Determine it by walking back through the local reflog:

```sh
git reflog show automation/swift-export-rollout | tail -5
```

The earliest entry's "from" branch is the base. In most repos this
will be `main`. In a small handful it will be a feature branch like
`setup-ci`, `automation/kotlinmania-ci-h14-4`, or
`port/drop-bignum-add-build-gate` (these are repos that had
in-progress work at rollout time; the rollout was branched off the
feature branch to keep scopes clean).

If you cannot determine the base from the reflog, default to `main`
**but** flag this in your final summary so the human reviewer can
verify.

### 5. Origin remote exists and points at GitHub

```sh
git remote get-url origin
```

Must be a `github.com/KotlinMania/<repo>` URL (HTTPS or SSH).

---

## The work

### Step 1 — push the rollout branch

```sh
git push -u origin automation/swift-export-rollout
```

Expected outcome: the remote ref is created (or fast-forwarded if
it already existed from a previous attempt). If the push is rejected
because the remote ref exists and has diverged, **do not force-push**;
stop and report.

### Step 2 — open the pull request

Use `gh pr create` with the title and body from
[§ PR template](#pr-template) below. The base branch is the one you
determined in precondition #4.

```sh
gh pr create \
    --base "<base-branch>" \
    --head "automation/swift-export-rollout" \
    --title "Wire Kotlin → Swift Export → swift test loop into CI" \
    --body-file <(cat <<'BODY'
... see § PR template ...
BODY
)
```

Capture the returned PR URL — you'll need it for the issue comment.

### Step 3 — verify CI kicked off

```sh
gh pr view <pr-number> --json statusCheckRollup
```

If the field is empty or shows "queued" / "in_progress", that's
expected — the workflow_call orchestration may take a minute to fan
out. Don't wait for completion; just verify a run was registered.

If the PR was created against a base branch that does NOT have CI
configured to run on PRs (e.g., a `chore/*` feature branch with no
PR triggers), there will be no check rollup. That's still fine —
note it in the summary.

### Step 4 — close the assigned issue

Post a comment on the assigned issue (the one that asked you to do
this work) with the format below, then close the issue.

```
Pushed `automation/swift-export-rollout` and opened PR:
<pr-url>

PR base: <base-branch>
CI status: <queued | in_progress | n/a — no PR triggers on base>
Rollout commit: <commit-sha>

Done.
```

---

## PR template

### Title

```
Wire Kotlin → Swift Export → swift test loop into CI
```

### Body

```markdown
## Summary

This PR wires the Kotlin → Swift Export → SPM → `swift test` loop into
this repository's CI, mirroring the canonical pattern established by
[libc-kotlin](https://github.com/KotlinMania/libc-kotlin) and proven
end-to-end with a passing `swift test` invocation against the
`embedSwiftExportForXcode`-produced Swift Package Manager package.

The full recipe for the rollout — including the rationale for each
change, the two upstream Kotlin Multiplatform plugin gaps it works
around, and the precondition / verification checks the Claude agent
ran before opening this PR — is checked in at the repo root as
[`SWIFT_EXPORT_ROLLOUT.md`](./SWIFT_EXPORT_ROLLOUT.md).

## What this PR changes

- `build.gradle.kts` — `iosSimulatorArm64` framework binary now has
  `isStatic = true` so the Swift Export SPM bridge can link against
  it.
- `.github/workflows/swift.yml` (new) — `workflow_call:` platform
  workflow that sets the full Xcode-style environment, runs
  `./gradlew embedSwiftExportForXcode`, then runs `swift test` from
  `swift-test-harness/`. Uploads the static archive and `.swiftmodule`
  bundles as artifacts on success-or-failure.
- `.github/workflows/ci.yml` — adds the `swift:` job alongside
  `wasm:` and `js:`.
- `swift-test-harness/` (new) — a Swift Package Manager package with
  one smoke test that asserts the Swift module imports cleanly.
- `.gitignore` — Swift/SPM workspace state section.

## Test plan

- [ ] The `Build (Swift)` job in CI lands BUILD SUCCESSFUL.
- [ ] The `Run swift test against Kotlin-exported module` step prints
      1 test executed, 0 failures.
- [ ] The uploaded `swift-export-artifacts` archive contains
      `lib<Module>.a` and the four `.swiftmodule/` directories.

## Known upstream gaps

Two issues live in the upstream Kotlin Multiplatform plugin and are
worked around per-repo in this PR. Both would, if fixed upstream,
allow every Kotlin Swift Export consumer to drop the local workaround
at once:

1. **`Package.swift` doesn't reference its own static archive.** The
   Kotlin plugin emits Swift source files for the SPM package but
   does not declare `lib<Module>.a` as a binary target or expose its
   link path. `swift-test-harness/Package.swift` compensates with
   `unsafeFlags(["-L", "../build/swift-test", "-l<Module>"])`.
2. **`BuildSPMSwiftExportPackage` validates
   `deploymentTargetSettingName` as a non-optional input** but
   provides no default. Outside Xcode the env var
   `DEPLOYMENT_TARGET_SETTING_NAME` isn't set, so the task fails
   property validation. `swift.yml` sets it explicitly.

🤖 Opened by the Claude GitHub Agent following
[`SWIFT_EXPORT_ROLLOUT.md`](./SWIFT_EXPORT_ROLLOUT.md).
```

---

## What to do when the recipe can NOT be followed

If any precondition check fails, or if any step errors out in a way
not anticipated by the recipe, **do NOT improvise**. Specifically:

- Do NOT force-push.
- Do NOT amend, rebase, or squash the rollout commit.
- Do NOT modify any source files.
- Do NOT change the PR base.
- Do NOT close the issue without a PR link, unless the rollout truly
  doesn't apply to this repo (e.g., no XCFramework declaration, no
  Apple targets).

Instead, post a comment on the assigned issue with the format:

```
Cannot complete rollout — <one-line summary>.

What I observed:
<copy/paste the relevant `git`, `gh`, or shell output>

What this implies:
<short interpretation>

Leaving the issue open for a human to follow up. The local
`automation/swift-export-rollout` branch (if it exists) has not
been modified.
```

Then stop. A human will pick it up.

---

## Known upstream gaps

1. **Kotlin-plugin-generated `Package.swift` doesn't reference its
   static archive.** Workaround in `swift-test-harness/Package.swift`:
   `unsafeFlags(["-L", "../build/swift-test", "-l<Module>"])`.

2. **`BuildSPMSwiftExportPackage.deploymentTargetSettingName` has no
   default.** Workaround in `.github/workflows/swift.yml`:
   `DEPLOYMENT_TARGET_SETTING_NAME=MACOSX_DEPLOYMENT_TARGET` in the
   env block for the macOS build.

3. **`KotlinStdlib.kt` unchecked-cast bridge** (the big one). When a
   declaration that reaches the Swift boundary exposes
   `kotlin.Result<T>` or `kotlin.Throwable` / `kotlin.Exception`, the
   Kotlin plugin generates
   `build/SwiftExport/<target>/<config>/files/KotlinStdlib/KotlinStdlib.kt`
   containing `Any?` → `Array<Any?>` unchecked casts. Under the
   workspace-canonical `allWarningsAsErrors.set(true)` these become
   compile errors.

   **First-choice fix:** keep the Apple-facing public API usable. Replace
   `kotlin.Result<T>` / `Throwable` surfaces with Swift-exportable outcome
   and error types, or keep the Swift-hostile implementation internal and
   expose a concrete facade.

   ```kotlin
   package io.github.kotlinmania.example

   public sealed class ParseOutcome {
       public data class Ok(public val value: Value) : ParseOutcome()
       public data class Err(public val error: ParseError) : ParseOutcome()
   }

   public data class ParseError(public val message: String)

   public class Parser {
       public fun tryParse(input: String): ParseOutcome =
           when (val parsed = parseValue(input)) {
               is InternalParseResult.Ok -> ParseOutcome.Ok(parsed.value)
               is InternalParseResult.Err -> ParseOutcome.Err(ParseError(parsed.message))
           }
   }
   ```

   `@HiddenFromObjC` is not a fix. It only removes the declaration from
   the Objective-C / Swift framework surface; Kotlin callers still see it,
   but Apple consumers do not. Repair the public API by renaming colliding
   declarations and replacing Swift-hostile shapes with strongly-typed,
   bridgeable Kotlin declarations. See
   [`triage-kotlin-stdlib-in-public-api.md`](./triage-kotlin-stdlib-in-public-api.md)
   for the workspace-wide hit list.

4. **Swift Export does NOT preserve Kotlin sealed-subclass casts.**
   Discovered empirically while making http-kotlin's `swift test`
   actually link. A `sealed class Foo { class Ok(...) : Foo(); class
   Err(...) : Foo() }` does not expose `Http.FooOk` / `Http.FooErr`
   as Swift types that consumers can reach with `as? Http.FooOk`.
   The Swift consumer can `import` `Foo` but cannot pattern-match on
   its sealed subclasses. **Use a flat class with nullable fields
   and boolean predicates instead** — see "Recipe for replacing
   `kotlin.Result<T>` in a public API" below.

5. **Swift accesses Kotlin companion objects as `Type.Companion.shared.member`**,
   not `Type.companion.member`. Common gotcha when writing the smoke
   test in `swift-test-harness/`. The Swift binding generated by the
   plugin uses a capital `Companion` struct type with a `shared`
   singleton accessor, not a lowercase `companion` property.

6. **Mutable generic collections in public APIs crash the bridging code.**
   `MutableList<T>` / `MutableSet<T>` / `MutableMap<K, V>` as public
   property types or return types cause the Swift Export compiler
   plugin to crash while emitting Swift-to-Kotlin bridging code,
   typically with type mismatch errors between `MutableList<Any?>`
   and `MutableList<T>`. The plugin's generic erasure to upper bound
   (`Any?`) collides with the mutability covariance constraints.
   **Per-repo workaround**: change public properties and return types
   to their immutable equivalents (`List<T>`, `Set<T>`, `Map<K, V>`).
   Where mutability is genuinely needed internally, hide the mutable
   collection with `internal` and expose the read-only view publicly.

7. **`macos-latest` ships an SDK older than the Kotlin/Native platform
   caches reference.** The Kotlin/Native 2.3.x distribution embeds
   prebuilt caches —
   `liborg.jetbrains.kotlin.native.platform.{CoreFoundation,Foundation,darwin}-cache.a`
   — that reference macOS 26 / iOS 26 SDK symbols
   (`_NSCalendarIdentifier{Vikram,Bangla,Dangi,Gujarati,…}`,
   `_NSURLUbiquitousItem{IsSyncPaused,SupportedSyncControls}Key`,
   `_mach_vm_reclaim_update_kernel_accounting_trap`,
   `_xpc_{connection,session,listener}_set_peer_requirement`,
   `_kCF{Bangla,Dangi,…}Calendar`). On GitHub Actions, `macos-latest`
   currently resolves to `macos-15` with Xcode 16 / macOS 15 SDK, so
   `swift test` link fails with "Undefined symbols for architecture
   arm64" on every symbol the cache references but the SDK doesn't
   carry. A consumer whose Kotlin code never reaches into those APIs
   may not notice (libc-kotlin happens to be in this lucky bucket),
   but any consumer that does (schemars-kotlin et al.) link-fails.
   **Workaround**: in `.github/workflows/swift.yml` set
   `runs-on: macos-26`. The macos-26 image's default Xcode is in the
   26.x line (carries 26.0.1 / 26.1.1 / 26.2 / 26.3 / 26.4.1 / 26.5;
   26.4.1 is the image default at the time of writing) and supplies
   the SDK stubs the platform cache expects.

   > **Do NOT add a `maxim-lobanov/setup-xcode@v1` step.** Earlier
   > revisions of this recipe pinned the Xcode version explicitly via
   > that third-party action. KotlinMania's enterprise GitHub Actions
   > policy does not whitelist third-party actions, and any workflow
   > that references one returns `startup_failure` at dispatch time
   > with zero jobs spawned — confirmed across two consecutive
   > dispatches on itertools-kotlin/automation/swift-export-rollout
   > (runs `26326086457`, `26326121639`). The pin step was redundant
   > anyway: `swift --version` from a working run on macos-26 prints
   > `Target: arm64-apple-macosx26.0` and `Xcode 26.4.1` straight from
   > the image default. Keep `runs-on: macos-26`; omit the pin step.

These first seven gaps are filable upstream against the Kotlin Multiplatform plugin
(or, for #7, against the Kotlin/Native distribution's platform-cache
build pipeline). When they're fixed, the workarounds in this recipe
become deletable.

8. **The Kotlin Swift Export bridge file casts `Any` / `Any?` to typed
   Kotlin values under `allWarningsAsErrors=true`.** The plugin-generated
   bridge at `build/SwiftExport/<target>/<config>/files/<Module>/<Module>.kt`
   pushes every typed Kotlin value through an untyped `Any` channel
   and then casts back to the concrete type on the way out. Under the
   workspace-canonical `allWarningsAsErrors.set(true)` every such cast
   trips `Unchecked cast` and `compileSwiftExportMainKotlin<Target>`
   fails. There are **two distinct API shapes** that provoke this; the
   bridge file looks similar in both cases but the source-side fix is
   different.

   **Trigger 8a — unconstrained generic public classes.** When a
   public Kotlin API exposes a generic class whose type parameter is
   unconstrained (`WithPosition<T>`, `ZipEq<L, R>`, `Unfold<St, T>`,
   etc.), the plugin erases `T` to `kotlin.Any?` in the bridge and
   emits:

   ```kotlin
   val ____self = dereferenceExternalRCRef(self) as io.github.kotlinmania.<pkg>.ZipEq<kotlin.Any?, kotlin.Any?>
   // w: Unchecked cast: 'Any?' to 'ZipEq<Any?, Any?>'.
   ```

   Same shape as gap #3 but for generic public types rather than
   `kotlin.Result`. The flat-class workaround in [§ Recipe for replacing
   `kotlin.Result<T>` in a public API](#recipe-for-replacing-kotlinresultt-in-a-public-api)
   does NOT apply — these are iterator/wrapper types that cannot be
   flattened without unfaithful translation.

   *Per-repo fix:* stop exposing the unconstrained generic classes.
   Swift Export only emits bridge code for symbols visible in the
   public API surface — internal types are skipped, so no
   `as <UserClass><Any?>` cast gets generated for them. The pattern
   from itertools-kotlin commit `40513a5`:

   - Mark every public unconstrained generic iterator/wrapper class
     `internal class` (the class itself, its companion factories, and
     any nested sealed subclasses).
   - Public factory functions keep their generic type parameters but
     declare their return type as a stdlib interface
     (`Iterator<T>`, `Sequence<T>`, `Iterable<T>`, `List<T>`, etc.)
     instead of the concrete internal class. The internal class still
     implements that interface, so callers get the same behavior with
     no API change visible to Kotlin or Swift consumers.
   - Tests in `commonTest` of the same module retain visibility of the
     internal classes via Kotlin's module-private rules, so
     type-specific assertions (`sizeHint`, internal `state`, `putBack`,
     etc.) keep working by constructing the internal class directly.
     Iteration-shape assertions can continue to use the public
     factory.

   **Trigger 8b — Kotlin function types in public API positions.** A
   public field, parameter, or return type of `() -> R` / `(A) -> R` /
   `(A, B) -> R` triggers the same `Unchecked cast` shape, even when
   the *enclosing* class has no generic parameter. The Kotlin source
   has no `<T>` in sight, but Kotlin function types compile to
   `Function0<R>` / `Function1<A, R>` / ... under the hood — same
   erasure surface, same untyped bridge channel. The bridge emits:

   ```kotlin
   val ____self = dereferenceExternalRCRef(self) as kotlin.Function0<kotlin.Long>
   // w: Unchecked cast of 'Any' to '() -> Long'.
   ```

   Live in-the-wild example from tree-sitter-language-kotlin CI on
   2026-05-23 (`compileSwiftExportMainKotlinMacosArm64` failed on
   `build/SwiftExport/macosArm64/Debug/files/TreeSitterLanguage/TreeSitterLanguage.kt:12:37`
   with `Unchecked cast of 'Any' to '() -> Long'.`):

   ```kotlin
   // Source (Language.kt) — no visible generic, but () -> Long erases
   class LanguageFn private constructor(private val raw: () -> Long) {
       companion object { fun fromRaw(f: () -> Long): LanguageFn = LanguageFn(f) }
       fun intoRaw(): () -> Long = raw
   }
   ```

   *Per-repo fix:* replace the public function-type with a named
   `fun interface` (single-abstract-method interface). SAM conversion
   lets every existing lambda call site stay unchanged
   (`LanguageFn.fromRaw { 42L }` still compiles), but the bridge sees a
   stable nominal type instead of an erased `FunctionN<...>`. The
   pattern:

   ```kotlin
   // Named SAM interface — bridge-friendly, lambda-friendly
   fun interface LanguageProvider {
       fun call(): Long
   }

   class LanguageFn private constructor(private val raw: LanguageProvider) {
       companion object {
           fun fromRaw(f: LanguageProvider): LanguageFn = LanguageFn(f)
       }
       fun intoRaw(): LanguageProvider = raw
   }
   ```

   `fun interface` is the right knob — not a plain `interface` (loses
   SAM conversion at call sites) and not `Function0<Long>` (same
   erasure problem in the bridge). If the function-type value is
   internal-only, marking the field `internal` is also valid.

   **Primary solution when Kotlin compatibility must stay public.** Keep
   the public Kotlin API source-compatible by changing the surface to
   bridgeable nominal types, not by hiding declarations. For trigger 8a,
   public factories keep their Kotlin generic parameters but return
   stdlib interfaces (`Iterator<T>`, `Sequence<T>`, `Iterable<T>`,
   `List<T>`, etc.) while the concrete implementation class becomes
   internal. For trigger 8b, replace public function-type positions with
   named `fun interface` SAMs so existing lambda call sites still compile
   and Swift Export sees a stable type name. For Swift and Java emitted
   name collisions, rename the Kotlin declaration or file itself and
   migrate callers; do not preserve the old colliding spelling with a
   typealias or platform naming annotation. The compatibility path is a
   real bridgeable API, not an annotation tag.

   **DO NOT** scope `allWarningsAsErrors=false` to the
   `compileSwiftExportMain*` task family for either trigger. That was
   tried first (itertools-kotlin PR #22 for trigger 8a) and is symptom
   suppression: it leaves the unchecked casts in the bridge and only
   mutes the compiler. The `tasks.matching { ... }.withType<KotlinCompilationTask<*>>()`
   block (and any `KotlinCompilationTask` import that accompanies it)
   should be deleted whenever found.

   With either fix in place, `allWarningsAsErrors=true` applies
   uniformly. The bridge surface shrinks accordingly — in itertools-kotlin
   it went from ~20 `@ExportedBridge` functions and 12 generic
   `@file:BindClassToObjCName(... :: class, ...)` annotations down to
   6 `@ExportedBridge` functions and zero generic class bindings,
   leaving only the genuinely-public concrete types (e.g. `Position`)
   bridged to Swift. itertools-kotlin commit `40513a5` is the canonical
   reference for trigger 8a; tree-sitter-language-kotlin (the
   `LanguageFn` / `LanguageProvider` rewrite) is the canonical reference
   for trigger 8b. tree-sitter-kotlin's batch SAM-ification of seven
   public function-type surfaces (preemptive, not reactive — done from
   the bridge-clean source before CI surfaced the warnings) is the
   canonical reference for the audit-and-sweep pattern below.

   *Audit grep (run once per repo after a fresh `embedSwiftExportForXcode`):*

   ```sh
   # Combined audit — both triggers, both shapes the bridge writes
   grep -nE "as kotlin\\.Function[0-9]+<|as io\\.github\\.kotlinmania\\.[^<]+<kotlin\\.Any" \
       build/SwiftExport/macosArm64/Debug/files/*/*.kt
   ```

   Every match in a `kotlin.FunctionN<...>` is trigger 8b (function
   type in public surface); every match in
   `io.github.kotlinmania.<pkg>.<Type><kotlin.Any` is trigger 8a
   (unconstrained generic). Both are fixable per-repo via the
   recipes below. If grep returns no results, gap #8 doesn't apply to
   this repo and no API change is needed.

9. **Exporting `kotlinx.coroutines.Flow` (or suspend functions) needs a
   four-part setup that the base rollout does NOT cover.** First repo to
   hit this end-to-end: `crossterm-kotlin` (public `Flow<InternalEvent>`
   event stream). The failures cascade one after another; all four fixes
   are required together. Symptom that starts it: the generated
   `OrgJetbrainsKotlinxKotlinxCoroutinesCore.swift` references a
   `KotlinCoroutineSupport` module that is neither imported nor emitted
   (`cannot find type 'KotlinCoroutineSupport' in scope`).

   **9a — Turn on coroutine support in the `swiftExport {}` block.** KGP
   defaults it OFF (`SwiftExportAction.kt`:
   `userDefinedSettings.getOrElse("enableCoroutinesSupport") { "false" }`),
   so `Flow` gets half-exported with references to a `KotlinCoroutineSupport`
   module the build never generates. Turn it on (the DSL method is
   `@ExperimentalSwiftExportDsl`, so opt in on the call expression):

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

   **9b — Relax `allWarningsAsErrors` for the `compileSwiftExport*` task
   family — and ONLY for the coroutine-runtime case.** Once 9a is on, the
   plugin emits a generated `build/SwiftExport/<t>/<c>/KotlinCoroutineSupport/
   KotlinCoroutineSupport.kt` runtime module that is itself not
   warning-clean (kotlinx.coroutines inheritance opt-in, useless-elvis,
   unchecked `SwiftFlowIterator` casts). That is **plugin-generated
   runtime we do not author and cannot edit** (it is regenerated every
   build), so there is no source fix.

   ```kotlin
   tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
       if (name.startsWith("compileSwiftExport")) {
           compilerOptions.allWarningsAsErrors.set(false)
       }
   }
   ```

   > **This is NOT the gap-#8 anti-pattern.** Gap #8 forbids this exact
   > block when it is used to silence unchecked-cast warnings in the
   > bridge for *your own* public API — because there you fix the source
   > (rename it, make it `internal`, or use a named `fun interface`). The
   > distinction is *whose code emits the warning*: gap #8 = your bridge
   > (fix source, never relax); gap #9 = the generated
   > `KotlinCoroutineSupport.kt` coroutine runtime (no source exists,
   > relax is the only option). Before relying on 9b, confirm with the
   > gap-#8 audit grep that your own bridge files have zero unchecked
   > casts; if they do, fix those at the source first. Do NOT delete this
   > block as "the forbidden gap-#8 block" — it is load-bearing for any
   > Flow-exporting repo.

   **9c — Disable the Kotlin/Native incremental cache.** Linking the
   Swift Export binary builds a K/N cache for `…_swiftExportMain.klib`,
   and the generated `SwiftFlowIterator` (a `NativePtr` continuation)
   hits an internal compiler error in C-bridge lowering
   (`doesn't correspond to any C type: kotlin.native.internal.NativePtr`,
   in `CBridgeGen.convertBlockPtrToKotlinFunction`). The compiler's own
   message recommends the workaround; set it in `gradle.properties`:

   ```properties
   kotlin.incremental.native=false
   ```

   Correctness-neutral (cache only). Revisit when the K/N
   Swift-Export-plus-coroutines cache path is fixed upstream.

   **9d — Inject a `platforms` declaration into the generated SPM
   `Package.swift`.** The Kotlin-generated `Package.swift` omits a
   `platforms:` clause, so a standalone `swift test` builds it below
   macOS 10.15 and every Swift Concurrency symbol in the coroutine
   bridge (`Task`, `AsyncSequence`, `withUnsafeThrowingContinuation`, …)
   fails with *"is only available in macOS 10.15 or newer."* Setting
   `MACOSX_DEPLOYMENT_TARGET` in the embed env (gap-#7 / the workflow)
   does NOT propagate into the generated manifest. Patch it in the
   `swiftExportSmokeTest` task, between `embedSwiftExportForXcode` and
   `swift test` (`name:` must precede `platforms:` in the initializer):

   ```kotlin
   val generatedPackageSwift =
       layout.buildDirectory.file("SPMPackage/macosArm64/Debug/Package.swift").get().asFile
   if (generatedPackageSwift.exists()) {
       val text = generatedPackageSwift.readText()
       if (!text.contains("platforms:")) {
           generatedPackageSwift.writeText(
               text.replaceFirst(
                   Regex("(name:\\s*\"[^\"]*\",)"),
                   "$1\n    platforms: [.macOS(.v14)],",
               ),
           )
       }
   }
   ```

   Also give `swift-test-harness/Package.swift` the same
   `platforms: [.macOS(.v14)]`. If a prior failed run cached a bad
   manifest, clear `swift-test-harness/.build` once.

   **Where Flow-exporting symbols live in the generated package.** With
   9a on, the SPM package gains `KotlinCoroutineSupport` and
   `OrgJetbrainsKotlinxKotlinxCoroutinesCore` targets and
   `CrosstermLibrary` lists both — confirmed working end-to-end on
   `crossterm-kotlin` (`swift test` → 1 test, 0 failures). Canonical
   reference: `crossterm-kotlin/build.gradle.kts` (the four fixes) +
   `swift-test-harness/Package.swift`.

## Recipe for replacing `kotlin.Result<T>` in a public API

The canonical pattern from http-kotlin commits `a179143` and the
follow-up that removed `!!`. Two principles guide the shape:

  * **The class invariant ("exactly one of value/error is non-null")
    is encoded explicitly with `init { require(...) }`.** That makes
    the invariant a real fact the type-checker can rely on after
    construction.
  * **No `!!` and no `@Suppress("UNCHECKED_CAST")`.** Both are
    workspace-forbidden per every repo's `CLAUDE.md`. `!!` is the
    runtime cousin of `@Suppress`: it suppresses the null-safety
    type-check at the cost of silencing a real bug class. The
    `when { value != null -> ...; error != null -> ...; else ->
    error("...") }` form below reads only the field whose
    non-null-ness is guaranteed in that branch, with the unreachable
    `else` as a defensive belt-and-suspenders for invariant
    violations.

```kotlin
/**
 * Result of [SomeApi.someFactoryThatCanFail].
 *
 * Flat-class shape (rather than sealed Ok / Err variants) — the
 * Kotlin Swift Export plugin does not currently emit Swift bindings
 * that let consumers pattern-match on sealed subclasses. A flat
 * class with `isSuccess()` / `isFailure()` predicates and nullable
 * `value` / `error` accessors bridges cleanly to Swift, where the
 * consumer uses `if result.isSuccess() { result.value?.thing() }`.
 *
 * The class invariant — exactly one of [value] / [error] is non-null —
 * is enforced at construction by [init]. That makes the otherwise
 * unreachable branch in [getOrThrow] expressible without `!!`.
 */
class SomeResult internal constructor(
    val value: SomeType?,
    val error: SomeConcreteError?,
) {
    init {
        require((value == null) != (error == null)) {
            "SomeResult must carry exactly one of value or error (got value=$value, error=$error)"
        }
    }

    companion object {
        internal fun ok(value: SomeType) = SomeResult(value, null)
        internal fun err(error: SomeConcreteError) = SomeResult(null, error)
    }

    fun getOrThrow(): SomeType = when {
        value != null -> value
        error != null -> throw SomeException(error)
        else -> error("SomeResult class invariant violated: both value and error are null")
    }

    fun getOrNull(): SomeType? = value

    fun isSuccess(): Boolean = value != null
    fun isFailure(): Boolean = error != null
}
```

### What NOT to do

These patterns look like they work but quietly suppress bugs:

```kotlin
// WRONG: !! suppresses the null check, hiding any future bug where
// the class invariant is violated. The `error` field "should never"
// be null when `value` is null, but the type system can't see that.
fun getOrThrow(): SomeType = value ?: throw SomeException(error!!)

// WRONG: `internal val error` hides the field from the public Swift
// surface to dodge an export problem, but Swift consumers legitimately
// need to inspect the failure. The fix is to make the Swift Export
// boundary handle the type, not to hide the API.
class SomeResult internal constructor(
    val value: SomeType?,
    internal val error: SomeConcreteError?,
)

// WRONG: sealed Ok/Err variants don't survive Swift Export — see
// upstream gap #4 above. Compiles in Kotlin but the Swift consumer
// can't do `as? SomeResultOk`.
sealed class SomeResult {
    class Ok(val value: SomeType) : SomeResult()
    class Err(val error: SomeConcreteError) : SomeResult()
}
```

---

## Recipe for hiding unconstrained generic iterator classes

The canonical pattern from itertools-kotlin commit `40513a5`. Applies
whenever a public Kotlin API exposes a generic class whose type
parameter is unconstrained (`class Foo<T>`, `class Bar<L, R>`, etc.)
and the class is reachable through the module's exported surface.

The principle: **public factories, internal implementations.** Swift
Export only emits bridge code for symbols that are part of the
public API — `internal` types are skipped. So the offending unchecked
`Any?` casts disappear if the concrete class is hidden behind a stdlib
interface return type.

### Before

```kotlin
package io.github.kotlinmania.itertools

class ZipEq<A, B>(
    private val left: Iterator<A>,
    private val right: Iterator<B>,
) : Iterator<Pair<A, B>> {
    override fun hasNext(): Boolean = TODO()
    override fun next(): Pair<A, B> = TODO()
}

fun <A, B> zipEq(left: Iterator<A>, right: Iterator<B>): ZipEq<A, B> =
    ZipEq(left, right)
```

Plugin-generated bridge file (`build/SwiftExport/.../Itertools.kt`) ends up with:

```kotlin
@ExportedBridge
fun __root___ZipEq_next(self: kotlin.native.internal.NativePtr): kotlin.native.internal.NativePtr {
    val ____self = dereferenceExternalRCRef(self) as io.github.kotlinmania.itertools.ZipEq<kotlin.Any?, kotlin.Any?>
    // ^^^ Unchecked cast: 'Any?' to 'ZipEq<Any?, Any?>'
    ...
}
```

Under `allWarningsAsErrors.set(true)` that warning becomes an error
and `compileSwiftExportMainKotlin<Target>` fails.

### After

```kotlin
package io.github.kotlinmania.itertools

internal class ZipEq<A, B>(
    private val left: Iterator<A>,
    private val right: Iterator<B>,
) : Iterator<Pair<A, B>> {
    override fun hasNext(): Boolean = TODO()
    override fun next(): Pair<A, B> = TODO()
}

fun <A, B> zipEq(left: Iterator<A>, right: Iterator<B>): Iterator<Pair<A, B>> =
    ZipEq(left, right)
```

The class is `internal`, so it never enters the Swift Export bridge.
The factory's return type changed from `ZipEq<A, B>` (the concrete
class) to `Iterator<Pair<A, B>>` (the stdlib interface). Kotlin
consumers in other modules can still call `zipEq(...)` and iterate
the result; Swift consumers get a bridge for the factory function
alone, with no `<Any?>` cast.

### Tests

Tests in `commonTest` of the same module retain visibility of
`internal` classes (Kotlin's module-private rules). When a test
asserts on a type-specific member that isn't part of `Iterator<T>` —
e.g. a custom `sizeHint`, `state`, `putBack`, internal counter —
construct the internal class directly instead of going through the
factory:

```kotlin
// commonTest can still see ZipEq's internal members
@Test
fun zipEqSizeHint() {
    val iter = ZipEq(listOf(1).iterator(), listOf("a", "b").iterator())
    assertEquals(1, iter.sizeHint())
}

// Iteration-shape assertions can still use the public factory
@Test
fun zipEqIterationShape() {
    val seq = zipEq(listOf(1, 2).iterator(), listOf("a", "b").iterator()).asSequence().toList()
    assertEquals(listOf(1 to "a", 2 to "b"), seq)
}
```

### What NOT to do

```kotlin
// WRONG: silencing the warning leaves the unchecked Any? cast in the
// bridge file. The plugin's runtime cast remains and the type-erased
// shape of the bridge stays the same — only the compiler is muted.
// Itertools-kotlin PR #22 tried this and was reverted by PR after.
tasks.matching { it.name.startsWith("compileSwiftExportMain") }
    .withType<KotlinCompilationTask<*>>()
    .configureEach {
        compilerOptions.allWarningsAsErrors.set(false)
    }

// WRONG: making the class `public abstract` and exposing only a
// public interface doesn't help — Swift Export still bridges the
// abstract class and emits the same `Any?` cast.
abstract class ZipEq<A, B> : Iterator<Pair<A, B>>

// WRONG: a `typealias` to a stdlib interface doesn't hide the
// underlying class from Swift Export. The original class still
// participates in bridging.
typealias ZipEqAlias<A, B> = ZipEq<A, B>
```

### Scope check

For each repo running Swift Export, grep the bridge file once after
a fresh `embedSwiftExportForXcode` run:

```sh
grep -nE "as io\\.github\\.kotlinmania\\.<pkg>\\.[A-Z][A-Za-z0-9_]+<kotlin\\.Any" \
    build/SwiftExport/macosArm64/Debug/files/*/*.kt
```

Every match is a candidate for the `internal class` + interface-return
treatment. If grep returns no results, this trigger (8a) doesn't apply
to this repo and no API change is needed.

---

## Recipe for hiding Kotlin function types behind a `fun interface`

The canonical pattern from the tree-sitter-language-kotlin
`LanguageFn` / `LanguageProvider` rewrite (2026-05-23). Applies
whenever a public Kotlin API uses a Kotlin function type
(`() -> R`, `(A) -> R`, `(A, B) -> R`, `suspend (A) -> R`, etc.) in a
**public** field, parameter, or return position.

The principle: **named SAM interface, not anonymous function type.**
Swift Export bridges named types as stable nominal symbols; it bridges
function types by erasing them through `Function0<R>` / `Function1<A, R>`
/ ... which collide with the `Any` channel in the bridge. A `fun interface`
gives the bridge a real symbol, and Kotlin's SAM conversion keeps lambda
call sites unchanged.

### Before

```kotlin
package io.github.kotlinmania.treesitterlanguage

class LanguageFn private constructor(private val raw: () -> Long) {
    companion object {
        fun fromRaw(f: () -> Long): LanguageFn = LanguageFn(f)
    }
    fun intoRaw(): () -> Long = raw
}
```

Plugin-generated bridge file (`build/SwiftExport/.../TreeSitterLanguage.kt`)
ends up with:

```kotlin
@ExportedBridge
fun __root___LanguageFn_intoRaw(self: kotlin.native.internal.NativePtr): kotlin.native.internal.NativePtr {
    val ____self = dereferenceExternalRCRef(self) as kotlin.Function0<kotlin.Long>
    // ^^^ Unchecked cast of 'Any' to '() -> Long'
    ...
}
```

Under `allWarningsAsErrors.set(true)` the warning becomes an error
and `compileSwiftExportMainKotlin<Target>` fails.

### After

```kotlin
package io.github.kotlinmania.treesitterlanguage

/**
 * Single-abstract-method interface that stands in for the upstream
 * Rust `unsafe extern "C" fn() -> *const c_void` function pointer.
 * Named so Swift Export can bridge a nominal symbol instead of an
 * erased `FunctionN<...>`.
 */
fun interface LanguageProvider {
    fun call(): Long
}

class LanguageFn private constructor(private val raw: LanguageProvider) {
    companion object {
        fun fromRaw(f: LanguageProvider): LanguageFn = LanguageFn(f)
    }
    fun intoRaw(): LanguageProvider = raw
}
```

`LanguageProvider` is `fun interface`, so Kotlin's SAM conversion still
accepts a lambda at the call site:

```kotlin
val fn = LanguageFn.fromRaw { tree_sitter_language() }
// Equivalent to: LanguageFn.fromRaw(LanguageProvider { tree_sitter_language() })
```

The bridge file's exported function now references `LanguageProvider`
(a nominal symbol Swift Export emits a binding for) instead of the
erased `Function0<Long>`. No `Any` cast.

### After (migration variant: name the SAM method `invoke`)

The recipe above uses `fun call()` as the SAM method, which is right
for new APIs. For repos *migrating* from a public typealias whose
call sites already use call-syntax (`callback(arg)` instead of
`callback.call(arg)`), name the SAM method `operator fun invoke(...)`
so Kotlin's invoke-operator convention keeps every existing call site
working without an edit:

```kotlin
// Before (typealias):
typealias ParseProgressCallback = (currentByteOffset: UInt, hasError: Boolean) -> Boolean

// After (fun interface with invoke):
fun interface ParseProgressCallback {
    operator fun invoke(currentByteOffset: UInt, hasError: Boolean): Boolean
}
```

Every prior caller of `callback(byte, hasError)` continues to compile
unchanged — `callback(...)` is now `callback.invoke(...)` via the
operator convention. `StableRef<ParseProgressCallback>` references in
nativeMain actuals also compile unchanged because the type name is
the same; only its kind changed (typealias → fun interface). Evidence:
tree-sitter-kotlin batch SAM-ification of seven public typealiases
and inline callback parameters, where this naming choice meant the
`jvmMain`, `androidMain`, and `nativeMain` actuals needed zero edits.

### Kotlin compatibility through named SAMs

The `fun interface` rewrite preserves the normal Kotlin call shape:
callers that pass lambdas keep writing the same lambda expression.
Callers that wrote out `(UInt, Boolean) -> Boolean` as an explicit type
must move to the named SAM type. That is the intended compatibility
boundary because the published API is now a bridgeable nominal type
instead of an erased `FunctionN`.

```kotlin
package io.github.kotlinmania.treesitter

fun interface ParseProgressCallback {
    fun invoke(
        byteOffset: UInt,
        hasError: Boolean,
    ): Boolean
}

public class Parser {
    public fun parseWithProgress(callback: ParseProgressCallback): Tree =
        parseInternal { offset, hasError -> callback.invoke(offset, hasError) }
}
```

When most or all of a subpackage is implementation runtime code that
should never reach Swift or Java consumers, make it `internal` and keep
public entry points in a small strongly-typed API layer. Do not ship a
public API and then hide it from one platform.

### Tests

SAM-convertible interfaces work the same as function types in tests
— pass a lambda or an explicit implementation, your choice:

```kotlin
@Test
fun fromRawAcceptsLambda() {
    val fn = LanguageFn.fromRaw { 42L }
    assertEquals(42L, fn.intoRaw().call())
}

@Test
fun fromRawAcceptsExplicitImpl() {
    val provider = object : LanguageProvider {
        override fun call(): Long = 99L
    }
    val fn = LanguageFn.fromRaw(provider)
    assertSame(provider, fn.intoRaw())
}
```

### What NOT to do

```kotlin
// WRONG: regular `interface` loses SAM conversion. Every existing
// `LanguageFn.fromRaw { ... }` call site breaks because Kotlin won't
// auto-wrap the lambda. Use `fun interface` instead.
interface LanguageProvider {
    fun call(): Long
}

// WRONG: switching to Function0<Long> is the same erasure problem.
// The bridge still writes `as kotlin.Function0<kotlin.Long>` because
// Function0 IS the underlying type of `() -> Long`.
class LanguageFn private constructor(private val raw: Function0<Long>)

// WRONG: silencing the warning leaves the unchecked cast in the
// bridge file. The plugin's runtime cast remains and the bridge
// shape is unchanged — only the compiler is muted.
tasks.matching { it.name.startsWith("compileSwiftExportMain") }
    .withType<KotlinCompilationTask<*>>()
    .configureEach {
        compilerOptions.allWarningsAsErrors.set(false)
    }

// WRONG: typealias to a Function type does NOT hide it from the
// bridge. The underlying function type still participates.
typealias LanguageProvider = () -> Long
```

### Scope check

For each repo running Swift Export, grep the bridge file once after
a fresh `embedSwiftExportForXcode` run:

```sh
grep -nE "as kotlin\\.Function[0-9]+<" \
    build/SwiftExport/macosArm64/Debug/files/*/*.kt
```

Every match is a candidate for the `fun interface` treatment. As a
source-side audit (preferred — it also catches surfaces the bridge
hasn't been generated for yet, e.g. new ports or new public
typealiases added to existing ports), grep for public function-type
signatures:

```sh
grep -nE "^(public |internal |private )?(class |object |val |var |fun )[^=]*: \\(.*\\) -> " \
    src/commonMain/kotlin/**/*.kt
grep -nE "^(public |internal |private )?(class |object |val |var |fun )[^=]*\\(.* \\(.*\\) -> " \
    src/commonMain/kotlin/**/*.kt
grep -rnE "^typealias [A-Z][A-Za-z0-9_]+ = \\(.*\\) -> " \
    src/commonMain/kotlin/
```

If all three passes return no results, trigger 8b doesn't apply and
no API change is needed.

### Preemptive sweep (run on every fresh port)

A new repo's public function-type surfaces should be SAM-ified
**before** Swift Export ever runs against them, not after CI surfaces
the warning. The audit greps above are cheap to run from a clean
checkout. The pattern from tree-sitter-kotlin (seven SAMs landed in
a single PR ahead of CI evidence):

1. Run the three source-side greps. Catalog every public
   function-type surface.
2. For each surface, decide:
   - **typealias whose call sites use `callback(arg)` syntax** →
     replace with `fun interface X { operator fun invoke(...) }`
     so call sites compile unchanged.
   - **new SAM, no existing call-syntax constraint** → name the SAM
     method semantically (`fun call()`, `fun read(byte: UInt)`, etc.).
   - **inline `((State) -> Boolean)?` parameter on a builder class**
     → introduce a co-located named SAM and replace the parameter
     type.
   - **subpackage that is internal-by-convention runtime port** →
     make it `internal` and expose only the strongly-typed public entry
     points that Swift and Java consumers should actually use.
3. Compile the three highest-coverage targets (`macosArm64`, `jvm`,
   `androidMain`) locally to confirm the SAM conversion didn't break
   any call site. Lambda call sites at invocation positions
   SAM-convert automatically; `StableRef<TypeName>` references stay
   the same because the type name didn't change, only its kind.

---

## Gaps discovered during the crossterm-kotlin port (2026-05-26)

Three previously undocumented issues surfaced during the crossterm-kotlin
Swift Export rollout that are general enough to hit other repos. All
three produced hard link failures (`ld: symbol(s) not found for
architecture arm64`) rather than compiler warnings, making them
impossible to miss — but also impossible to diagnose from the Swift
side alone.

### 9. Public `internal expect fun` symbols leak into the Swift Export bridge

**Symptom.** `swift test` link fails with `Undefined symbols for
architecture arm64: _io_github_kotlinmania_<pkg>_<symbol>` for a
function that *is* declared `internal` in commonMain but is also the
`actual` implementation of an `expect fun` that was itself `internal`.

**Root cause.** When commonMain declares `internal expect fun foo()` and
a platform source set provides `internal actual fun foo()`, the Kotlin
Swift Export plugin may still generate an `@ExportedBridge` entry for
the symbol because the `expect`/`actual` mechanism creates a public
entry point during metadata compilation even though the original
declaration is `internal`. The macOS native binary then doesn't export
the symbol (it's truly internal), and the bridge reference dangles.

**Fix.** Make the function truly invisible to the bridge by ensuring it
doesn't participate in the exported surface. For utility functions that
only exist as internal implementation details (like `getTtyFd()` which
returns a raw fd for termios calls), the fix was to remove `expect`
entirely and make the function a plain `internal fun` in the platform
source set that needs it. The function was never part of the public API
— it was only `expect` so that multiple platform source sets could share
the signature, but since each platform that calls it already has its
own copy, the `expect` was unnecessary overhead that leaked into the
bridge.

```kotlin
// BEFORE (leaks into bridge):
// commonMain:
internal expect fun getTtyFd(): Int

// posixMain:
internal actual fun getTtyFd(): Int { ... }

// AFTER (no bridge leak, no expect needed):
// posixMain only (no commonMain declaration):
internal fun getTtyFd(): Int { ... }
```

**When to use this fix.** Only for `internal expect fun` declarations
where every platform actual is already in a source set that's a
dependency of the Apple target. If the `expect` exists so that
`otherMain` / `jvmMain` / `wasmJsMain` can provide a no-op or
JVM-specific actual, you still need the `expect`/`actual` pair. For
Apple export, keep it `internal` and expose the public operation through
a strongly-typed bridgeable declaration.

### 10. JVM class name clashes between commonMain and platform source sets

**Symptom.** `compileAndroidMain` (or `jvmMainClasses`) fails with:

```
Duplicate JVM class name 'io/github/kotlinmania/<pkg>/FooKt'
generated from: FooKt, FooKt
```

**Root cause.** Kotlin compiles each source set's top-level functions
into a class named after the source file (`Foo.kt` → `FooKt`). When
`commonMain/src/Foo.kt` defines `expect fun bar()` and
`jvmMain/src/Foo.kt` defines `actual fun bar()`, both compile to
`FooKt.class` on JVM targets. The JVM class loader can't resolve which
one to use.

This is a JVM-only issue — Kotlin/Native and Kotlin/JS don't have this
problem because they don't use the same class-file naming scheme. But
androidMain, jvmMain, and any JVM-based source set all hit it.

**Fix.** Rename the Kotlin file or declaration that emits the duplicate
JVM class. The actual-implementation file gets a descriptive name that
reflects what it contains, not the same name as the commonMain expect
file:

```kotlin
// commonMain/src/AnsiSupport.kt -> defines expect fun enableVtProcessing()
// jvmMain/src/VtProcessing.kt   -> defines actual fun enableVtProcessing()
// androidMain/src/VtProcessing.kt -> defines actual fun enableVtProcessing()
```

The JVM class names become `AnsiSupportKt` (commonMain) and
`VtProcessingKt` (jvmMain + androidMain) — no clash.

Do not use `@file:JvmName` or `@file:JvmMultifileClass` to paper over
the collision. Those annotations change the Java interop surface and
keep the underlying naming mistake in place. The project rule for Java
matches the Swift rule: rename the Kotlin source element that emits the
bad name.

**Prevention.** After porting `expect`/`actual` declarations, check
for JVM class name clashes by compiling `compileAndroidMain` or
`jvmMainClasses` before declaring victory.

### 11. Display name constants (`internal const val`) referenced as
     function calls in test files

**Symptom.** `compileTestKotlin<Platform>` fails with `Unresolved
reference 'keyCodeBackspaceDisplayName'` (or similar).

**Root cause.** When `expect fun keyCodeBackspaceDisplayName(): String`
 declarations across all platform source sets are consolidated into
 `internal const val KEY_CODE_BACKSPACE_DISPLAY_NAME: String = "Backspace"`
 in commonMain (part of reducing the expect surface), the test files
 still reference them as function calls: `keyCodeBackspaceDisplayName()`.
 The `const val` form is not callable — it's a value, not a function.

**Fix.** Update test files to reference the constant directly:

```kotlin
// BEFORE (function call syntax from the expect fun era):
assertEquals(keyCodeBackspaceDisplayName(), KeyCode.Backspace.toString())

// AFTER (constant reference):
assertEquals(KEY_CODE_BACKSPACE_DISPLAY_NAME, KeyCode.Backspace.toString())
```

Since these are `internal` constants in the same module, `commonTest`
can access them. The naming convention stays SCREAMING_SNAKE_CASE for
constants per Kotlin convention.

## Gaps discovered during the build-gate / test-wiring pass (2026-05-30)

Two general defects in how `build`, `check`, and the per-platform CI
workflows wire test *execution* vs. *compilation*. Neither is a Swift
Export bridge bug — they decide whether each platform's `actual`s ever
actually run, including the Swift smoke test.

### 12. The build gate must BUILD every target but must not single out one platform's tests to RUN

**Symptom.** `fullTargetBuildTaskNames` (the all-target build gate) lists
`testAndroidHostTest` — an actual test *run* — alongside the compile/link
tasks, while every other target contributes only `*MainClasses` /
`*TestClasses` / `${target}Binaries` / `${target}TestBinaries` (compile
and link, no execution). So `./gradlew build` *runs* the Android unit
tests but for every other target merely links the `test.kexe` without
ever executing it. The gate looks symmetric but silently tests exactly
one platform.

**Root cause.** A "build must compile every target" contract and "run the
tests" are two different jobs. The build gate is about *compilation
coverage*; test *execution* is host-dependent (you can't run a Linux or
mingw test on a macOS runner, and device / Android-Native targets have no
host-runnable test task at all). Burying one runnable test in the build
set conflates the two.

**Fix.** Keep `fullTargetBuildTaskNames` pure-build (compile + link every
target, including each target's test binary — that proves the test code
*compiles*). Move test *execution* to `check`, where KMP's `allTests`
already runs every host-runnable test (`jvmTest`, `macosArm64Test`, the
Apple simulator tests, `jsNodeTest`, `wasmJsNodeTest`, ...). Add the
Android host test and the Swift smoke test there too:

```kotlin
tasks.named("check") {
    dependsOn(tasks.withType<io.gitlab.arturbosch.detekt.Detekt>())
    dependsOn("ktlintCheck")
    dependsOn("testAndroidHostTest")     // not in the build set
    dependsOn("swiftExportSmokeTest")
}
```

Do **not** try to make tests depend on the `build` lifecycle task to
force this ordering: `build → check → allTests` already exists, so
`anyTest.dependsOn("build")` forms the cycle
`build → check → allTests → build` and Gradle rejects it at
configuration time. Depend on the underlying compile/link tasks (or just
let `check` own execution) instead.

### 13. Every per-platform CI workflow must RUN its platform's test, not just compile it

**Symptom.** A platform's reusable workflow compiles and assembles but
never invokes a test-*run* task, so that platform's `actual`s are never
exercised in CI. Observed: `android.yml` ran
`compileAndroidMain assembleUnitTest assembleAndroidTest` with **no**
`testAndroidHostTest` — Android built green forever while its tests
never ran. (`watchos.yml` separately still listed the retired
`compileKotlinWatchosArm32`, which fails "task not found" once the target
is scrubbed — audit for retired targets in the same pass.)

**Fix.** Each `<platform>.yml` runs the test task that executes on its
runner: `macosArm64Test`, `linuxX64Test`, `mingwX64Test`,
`iosSimulatorArm64Test`, `tvosSimulatorArm64Test`,
`watchosSimulatorArm64Test`, `jsNodeTest`/`jsBrowserTest`,
`wasmJsNodeTest`/`wasmJsBrowserTest`/`wasmWasiNodeTest`,
`testAndroidHostTest`, and `swift test` (swift.yml). Audit rule: a
workflow whose Gradle task list contains only `compile*` / `assemble*`
and no `*Test` run is a platform building-but-not-testing.

**Honest limits (compile-only, by physics — not laziness).**
`androidNative*` (ELF for Android ABIs, needs an emulator per ABI),
`linuxArm64` on an x64 runner, and the device Apple slices (`iosArm64`,
`tvosArm64`, `watchosArm64`, `watchosDeviceArm64`, which have no
host-runnable test task) can only be compiled/linked. Their `actual`s
share the `appleMain`/`iosMain`/etc. source sets that the corresponding
**simulator** test exercises, so the same code is covered — state this
in the workflow rather than implying the binary was tested.

The local `swiftExportSmokeTest` (kasuari-kotlin is the reference shape)
runs `swift test` against the `embedSwiftExportForXcode` output and must
be wired into `check` so Swift Export breakage surfaces on
`./gradlew check` locally, not only in `swift.yml`. When aligning a repo,
confirm `project.frameworkName` (→ `swiftExport.moduleName`) matches the
harness's `import <Module>` — a mismatched module name fails `swift test`
with "no such module" even though the bridge compiled cleanly.

---

## Why the recipe is in every repo

Two reasons:

1. **The Claude agent operates per-repo** in the GitHub App context.
   Each repo's agent invocation only sees the repo it's working in.
   Pulling instructions from a central source would require the agent
   to make cross-repo API calls; embedding the recipe in the repo
   makes the work fully self-contained.

2. **The recipe ages with the rollout commit.** When the workarounds
   in [§ Known upstream gaps](#known-upstream-gaps) eventually become
   unnecessary, the recipe's removal (or amendment) will land in the
   same commit as the workaround removal. Future spelunkers reading
   `git log -- SWIFT_EXPORT_ROLLOUT.md` will see the full history of
   what was tried and why.

— Sydney Renee, KotlinMania

---

## Additional Workspace Guidelines and Hazard Classes

### The mandatory infrastructure pins

- **`swift.yml` runs on `runs-on: macos-26`** — not `macos-latest`. The Kotlin/Native 2.3.x SDK cache requires it.
- **Never add `maxim-lobanov/setup-xcode@v1`** or any other third-party Xcode setup action. Third-party actions cause `startup_failure` on the KotlinMania allowlist.
- **`swift test` is wired into the Kotlin `test` task.** A Swift Export failure must surface on `./gradlew test` locally. Do not push and let remote CI find it — by that point you've burned a CI run.
- **iOS Simulator XCFramework fat-stage rule.** Both `iosSimulatorArm64` AND `iosX64` must be `isStatic = true`. Mixing one static / one dynamic fails `assembleDebugIosSimulatorFatFramework<Name>` with:
  ```
  Cannot create a fat framework from:
    <Name> - arm64 - static
    <Name> - x64 - dynamic
    All input frameworks must be either static or dynamic
  ```
  This is **gap #7** in the rollout. Earlier rollouts flipped only `iosSimulatorArm64`; if a repo still has the asymmetric shape, fix `iosX64` too in the same commit.

### The four hazard classes (what makes Swift Export fail under `-Werror`)

Swift Export's generated bridge fails `allWarningsAsErrors=true` in four distinct ways. **Do not scope `allWarningsAsErrors=false` to `compileSwiftExportMain*` to silence them — that's symptom suppression and a forbidden fix.** Apply the structural remedy below.

**Class A — Unchecked cast `Any?` → `Foo<T>`.** Public generic types with unconstrained type parameters force the bridge to erase to `Any?` and cast back. Fix: wrap the generic with an `internal` implementation + a non-generic façade for Swift, or use a stdlib type the bridge already handles.

**Class B — `Cannot infer T`.** Public generic functions Swift can't project a single Swift type for. Fix: `internal` factory that fixes the type parameter at the boundary; the public Kotlin function stays generic for Kotlin callers.

**Class C — `Array`/`List`/`Iterator` mismatch.** Public collection surfaces that Swift would bridge to non-equivalent types. Fix: non-generic collection at the boundary, or wrap in a thin Swift-friendly type.

**Class Stdlib (the fourth class) — warnings in *auto-generated* `KotlinStdlib.kt`.** Public `kotlin.Result<X>`, classes extending `RuntimeException` / `Exception` / any `Throwable` subclass, public `MutableList<X>`, public `MutableMap<K, V>` each drag the `Throwable.getStackTrace()` → `Array` bridge or the `MutableList`/`MutableMap` bridge into the generated `KotlinStdlib.kt`, which then fails Unchecked-cast under `-Werror`. Fixes:

- **Public `kotlin.Result<T>`** → sealed `Outcome.Ok(value: T) | Outcome.Err(error: SomeError)`, where `SomeError` does NOT extend `Throwable`.
- **`class FooError : RuntimeException(...)`** (or `Exception`) → data class / sealed class that does NOT extend any `Throwable`. Kotlin callers that need to throw it wrap in their own throw site.
- **Public `MutableList<X>` / `MutableMap<K, V>`** → internal-backed `List<X>` / a custom read-only Map wrapper; internal helpers do copy-and-replace to keep the read-only public surface.
- **Public `Pair<A, B>`** → named record class.
- **Public `() -> X` / `(A) -> B`** function types → `fun interface` SAM type. Swift Export can't bridge raw Kotlin function types.

### Annotation hiding is not an API repair

`@HiddenFromObjC` hides a declaration from Objective-C / Swift export.
That is not a repair for a published Apple framework. If a declaration
should not be exported, make it `internal`. If a declaration is part of
the public API, make it bridgeable by construction.

For Swift name collisions, the fix is to rename the Kotlin type itself.
Do not hide the declaration, do not rely on `@ObjCName`, and do not keep a
backward-compatible `typealias` with the old colliding name. Swift Export
emits the Kotlin declaration surface, including typealiases, so the old
name keeps colliding.

The concrete reference is `syn-kotlin`: commit `e34775e` renamed
`Type` / `Error` / `Result` to `SynType` / `SynError` / `SynResult`, and
commit `afbebfe` removed the aliases after CI showed
`typealias Type = SynType` was still exported and rejected by Swift.

```kotlin
// Before: collides with Swift's `foo.Type` metatype expression.
public sealed class Type

// Correct: rename the Kotlin API and migrate callers.
public sealed class SynType

public data class BareFnArg(
    public val ty: SynType,
)

// Wrong: aliases are exported too, so this keeps the Swift collision.
public typealias Type = SynType
```

The reviewed API-hiding failure case is `lru-kotlin` PR #18. The PR
annotated the main cache type:

```kotlin
class LruCache<K : Any, V : Any> private constructor(...)
```

The generated framework could still import, but Swift / Objective-C
consumers could not construct or use the cache. The correct direction is
one of:

- keep `LruCache` exported and make its public surface bridgeable;
- when the failure is a Swift name collision, rename the Kotlin type to a
  Swift-safe name and update Kotlin callers to that name;
- when the failure is generic bridge shape, keep a generic implementation
  internal and provide exported concrete types with Swift-safe names;
- make unsupported iterator/view/helper types `internal` while the
  primary cache API remains exported and usable.

Do not accept an import-only smoke test as proof that Swift Export is
healthy. The Swift harness must instantiate at least one primary exported
type and call a representative method.

**Fallback** (when faithfulness isn't a constraint): mark `internal` + expose a factory returning the public stdlib interface.

### Project goal: strongly-typed public APIs — generics only where design requires

After a Swift Export compatibility pass lands and a repo's gate is green,
the **follow-up pass is to de-generify**. The project goal across the
workspace is **strongly-typed public APIs**: no unconstrained generics, no
`<T : Any>` exposed to Swift, no `<T, E>` Result-style wrappers — unless
the design genuinely requires the type parameter (parser combinators,
typed builders, container types where the element type carries real
meaning).

Why this is a real rule and not a style preference:
- Annotation hiding only removes the Swift bridge problem from view. The Swift side sees no API for that surface at all, which is worse than a non-generic Swift API. Swift callers either cannot use the type or have to drop into Objective-C interop.
- The Class A "unchecked cast `Any?` → `Foo<T>`" failure is generated by the same erased-bridge code path no matter what you do; hiding it means the next type the bridge tries to express through that path hits the same wall.
- Strongly-typed public APIs survive Kotlin/Native compiler upgrades. The generic-bridge handling in Swift Export is the most volatile surface of the K/N toolchain and keeps changing between 2.3.x patches; non-generic APIs don't care.

**The strong-typing checklist:**
1. Is the generic parameter actually doing structural work for callers?
   If **no**, replace it with the concrete type or a small set of
   concrete named subtypes (`IntFoo`, `StringFoo`, `DoubleFoo`).
2. If the parameter is doing structural work but public use only
   instantiates one or two concrete types, hoist those instantiations
   into named non-generic subclasses (`class IntCell : Cell<Int>()`),
   expose those publicly, and make the generic base `internal`.
3. If the parameter is genuinely required by design (for example, a
   typed builder DSL or a generic container callers instantiate at
   arbitrary types), leave the generic and document why in a one-line
   `// generic by design:` comment so the next sweep does not mistake it
   for unfinished work.
4. `Pair<A, B>` -> named record class. Always. There is no design
   justification for leaking `kotlin.Pair` into a public API.
5. `Result<T, E>` / generic Outcome wrappers -> per-error-domain sealed
   classes. `AddConstraintOutcome` not `Outcome<Unit, AddConstraintError>`.
6. Function-type surfaces (`(A) -> B`) -> `fun interface` SAM with a
   meaningful name.

**Forbidden phrasing** in commits and PR descriptions:
- "Hide `Foo` from Swift" as the *only* change for that API. → Not acceptable when `Foo` is the only useful Apple-facing API; provide an exported facade or redesign the surface.
- "Generic API kept for flexibility." → Specify *which caller* needs the flexibility, or de-generify.

Memory hook: `feedback_swift_export_three_patterns.md`,
`feedback_swift_export_throwable_result_array.md`, and
`feedback_swift_export_gap8_internal_generics.md` predate the stricter
public-API rule. Treat them as bridge-failure diagnostics, not as
permission to hide the only Swift-facing API.

### The 5-class sweep (per-repo, run before every Swift release)

Inspect commonMain for **all** of these, not just whatever last tripped CI:
1. `runs-on:` on `swift.yml` is `macos-26`; no `setup-xcode` action.
2. Public mutable collection surfaces (`MutableList`, `MutableMap`, `MutableSet`) → read-only Kotlin types + internal copy-and-replace helpers.
3. Public generic types / SAMs / function-type surfaces -> **first choice: de-generify** (concrete named subtypes, `fun interface` SAM with a real name, internal generic base). See "Project goal: strongly-typed public APIs" above.
4. Public `kotlin.Result<X>` / `Throwable` subclasses → sealed Outcome types + non-Throwable error classes.
5. Public `Pair<A, B>` helpers → named record class.

Run the gate after every repair, not just at the end:
```bash
./gradlew embedSwiftExportForXcode --no-daemon
./gradlew test --no-daemon          # must include swift test
```

### 12. Kotlin names that conflict with Swift or Java emitted names

**Symptom.** `compileSwiftExportMainKotlinMacosArm64` or the
`xcodebuild` step inside `macosArm64DebugBuildSPMPackage` fails with:

```
error: type member must not be named 'Type', since it would conflict with the 'foo.Type' expression
error: type member must not be named 'Error'
error: type member must not be named 'Result'
```

**Root cause.** Swift Export emits Kotlin declaration names into the
generated Swift module, and JVM compilation emits Kotlin files and
declarations as Java-visible class and method names. If a Kotlin public
type or typealias is named like a Swift keyword, metatype expression,
protocol, or built-in type (`Type`, `Error`, `Result`, etc.), the Swift
compiler rejects the module. If common/platform Kotlin files or
declarations emit the same JVM class or method signature, Java/JVM
compilation fails.

`@HiddenFromObjC` only removes the API from Apple consumers. `@ObjCName`,
`@JvmName`, and `@JvmMultifileClass` are not fixes. A Kotlin `typealias`
does not preserve compatibility either because Swift Export emits aliases
too: `typealias Type = SynType` still exports `Type` and still collides.

**Fix.** Rename the Kotlin declaration or file itself to an emitted-safe
name and migrate every Kotlin caller to that new name. Remove any
compatibility typealias or platform naming annotation that preserves the
old colliding name. If downstream `*-kotlin` repos use the old name,
update them in the same compatibility pass; there is no bridge that
preserves the old source spelling without reintroducing the collision.

Concrete pattern from `syn-kotlin`:

```kotlin
// Before
public sealed class Type {
    public data class Tuple(
        val elems: Punctuated<Type, Comma>,
    ) : Type()
}

// After
public sealed class SynType {
    public data class Tuple(
        val elems: Punctuated<SynType, Comma>,
    ) : SynType()
}

public data class BareFnArg(
    public val ty: SynType,
)

// Forbidden: this is exported and collides in Swift.
public typealias Type = SynType
```

**Evidence.** `syn-kotlin` commit `e34775e` renamed
`Type` / `Error` / `Result` to `SynType` / `SynError` / `SynResult`.
Commit `afbebfe` then removed `typealias Type = SynType`,
`typealias Error = SynError`, and `typealias Result<T> = SynResult<T>`
after CI confirmed the aliases were exported and rejected by Swift.

### 13. `NoClassDefFoundError: kotlinx/coroutines/internal/intellij/IntellijCoroutines` during Swift Export

**Symptom.** The `macosArm64DebugSwiftExport` Gradle task logs an
`ERROR: Worker exited due to exception` with
`NoClassDefFoundError: kotlinx/coroutines/internal/intellij/IntellijCoroutines`.
GitHub Actions annotates the step with a red `##[error]` marker.

**Root cause.** The Kotlin compiler embeds parts of the IntelliJ platform
for its worker processes. The embedded platform expects
`kotlinx.coroutines.internal.intellij.IntellijCoroutines` on the worker
classpath, but the Swift Export task's worker process doesn't include
the `kotlinx-coroutines-core` JAR.

**Impact.** The error is **non-fatal** — the Swift Export and SPM
package generation continue past it. The actual build failure (if any)
comes from downstream compilation errors, not from this worker exception.
If the only `##[error]` annotations in the Swift CI job are
`IntellijCoroutines`-related, the Swift Export itself may have succeeded.

**Current workaround.** Adding `kotlinx-coroutines-core` to the
`buildscript` classpath does **not** fix it — the worker process has its
own classpath that doesn't inherit the buildscript classpath. There is
no known per-repo fix at this time; the error is filed upstream against
the Kotlin Gradle plugin.

### 14. Custom sealed generic result types in public API cause argument type mismatch in Swift Export bridge

**Symptom.** `compileSwiftExportMainKotlinMacosArm64` fails with:

```
Argument type mismatch: actual type is '(ParseNestedMeta) -> SynResult<Any?>',
  but '(ParseNestedMeta) -> SynResult<Unit>' was expected.
Cannot infer type for type parameter 'R'. Specify it explicitly.
```

**Root cause.** Swift Export generates bridge code for public API
declarations. When a function takes or returns a custom generic sealed
class (e.g. `SynResult<T>`), the bridge erases the type parameter to
`Any?` because it doesn't understand the sealed-class type hierarchy.
This produces Kotlin-level argument type mismatches in the generated
bridge file that fail compilation.

**Fix.** If these are Kotlin-internal parser APIs that Swift consumers do
not need, make them `internal`. If they are public API, replace the
custom generic sealed result with a Swift-exportable concrete outcome
type.

The flat-class pattern from [§ Recipe for replacing `kotlin.Result<T>`
in a public API](#recipe-for-replacing-kotlinresultt-in-a-public-api) is
the correct shape for exported result APIs. The flat class with
`value`/`error` nullable fields and `isSuccess()`/`isFailure()`
predicates bridges cleanly.

**Evidence.** syn-kotlin's durable fix was the type-rename pass:
`Type` / `Error` / `Result` became `SynType` / `SynError` / `SynResult`,
and the compatibility aliases were removed after CI proved aliases are
exported too.
