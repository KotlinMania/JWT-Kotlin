# Android port notes — libc-kotlin

Reference material for binding libc functions to the Android platform inside
this repo. Verified facts only; "ABSENT" / "PRESENT" / "API ≥ N" are taken
from upstream sources, not inferred. Sources are linked inline.

This repo targets Android in two distinct ways and they must not be confused:

1. **`androidMain`** — the **JVM-based Android** target (`android { ... }` in
   `build.gradle.kts`). Apps compiled against the Android SDK / Android Gradle
   plugin pull this artifact. Bytecode runs on ART. Native code is reached via
   JNI into a packaged `.so`.
2. **`androidNativeMain`** — the **Android NDK / Kotlin/Native** target. The
   compiler emits ELF binaries directly for Android device CPUs. No JVM, no
   ART, just native code linked against bionic.

The two targets share the bionic libc surface at runtime, but the binding
layer is different: actuals in `androidMain` are JNI externals into a shipped
`.so`; actuals in `androidNativeMain` are direct `platform.posix.*` cinterop
calls into the NDK pthread / libm / etc. symbols at link time.

## Android ABIs and the K/N target mapping

Android NDK currently ships 4 ABIs (NDK r17 removed ARMv5 and 32/64-bit MIPS).
Each one is its own Kotlin/Native leaf target.

| Android ABI    | Kotlin/Native target      | Bitness | Notes                                                                                   |
|----------------|---------------------------|---------|-----------------------------------------------------------------------------------------|
| `armeabi-v7a`  | `androidNativeArm32`      | 32-bit  | ARMv7-A + Thumb-2 + Neon. Soft-float ABI (`-mfloat-abi=softfp`). 64-bit `long double`.  |
| `arm64-v8a`    | `androidNativeArm64`      | 64-bit  | ARMv8.0. x18 reserved for ShadowCallStack. 128-bit `long double`. PAC/BTI optional.     |
| `x86`          | `androidNativeX86`        | 32-bit  | IA-32 with MMX/SSE/SSE2/SSE3/SSSE3. No SSE4 or MOVBE in the baseline. 64-bit `long double`. |
| `x86_64`       | `androidNativeX64`        | 64-bit  | x86-64-v2: MMX/SSE/SSE2/SSE3/SSSE3/SSE4.1/SSE4.2/POPCNT/CMPXCHG16B/LAHF-SAHF. 128-bit `long double`. |

All four are little-endian. All four use ELF binaries. C++ name mangling is
the Itanium ABI. The Android NDK toolchain enforces 16-byte stack alignment
on x86 and x86-64.

> Source: https://developer.android.com/ndk/guides/abis

### What this means for the libc port

- A function whose Rust signature mentions `size_t` / `off_t` / `pthread_t`
  will have a **different Kotlin numeric width** on the two 32-bit targets
  (`androidNativeArm32`, `androidNativeX86`) versus the two 64-bit targets
  (`androidNativeArm64`, `androidNativeX64`).
- Use `kotlinx.cinterop.convert()` on a commonMain `Long` handle so the
  same actual body compiles for both 32- and 64-bit Android Native leaf
  targets:

  ```kotlin
  public actual fun pthreadKill(thread: PthreadT, sig: Int): Int =
      platform.posix.pthread_kill(thread.rawValue.toLong().convert(), sig)
  ```

  The bare `.toLong()` would compile only for 64-bit (`pthread_t = Long`)
  and break on 32-bit (`pthread_t = Int`). `.convert()` polymorphically
  selects.
- The 32-bit ABI bugs page at
  https://developer.android.com/ndk/guides/abis#32-bit-abi-bugs catalogs the
  cases where Android decided not to fix a 32-bit-only quirk; consult it
  before claiming a 32-bit behaviour is "the same as 64-bit".

## Bionic — Android's libc

> Source: https://android.googlesource.com/platform/bionic
> Clone (sparse, libc-only): `/Volumes/stuff/tools/bionic/libc/`

Bionic is not glibc and not musl. It is a small, Android-specific C library
plus math library plus dynamic linker. When porting a libc surface, the
question "does this symbol exist on Android" routes to the *bionic* headers,
not the upstream POSIX spec or some other libc.

### Bionic layout (the parts we care about)

| Subtree                               | What it contains                                                                   |
|---------------------------------------|------------------------------------------------------------------------------------|
| `libc/include/`                       | Public header files. This is the authoritative source for "is symbol X declared?". |
| `libc/include/sys/types.h`            | `pthread_t`, `size_t`, `ssize_t`, `off_t`, `pid_t`, `uid_t`, ...                    |
| `libc/include/bits/pthread_types.h`   | `pthread_t`, `pthread_attr_t`, `pthread_mutex_t`, etc. (the opaque type structs).  |
| `libc/include/android/api_level.h`    | Defines `__INTRODUCED_IN(N)` and the API-level constants.                          |
| `libc/kernel/uapi/`                   | Scrubbed Linux kernel UAPI headers (constants, structs Linux passes to/from kernel). |
| `libc/bionic/`                        | Bionic-owned C++ source (the parts not borrowed from BSD).                         |
| `libc/upstream-freebsd/`              | FreeBSD source borrowed unmodified.                                                |
| `libc/upstream-netbsd/`               | NetBSD source borrowed unmodified.                                                 |
| `libc/upstream-openbsd/`              | OpenBSD source borrowed unmodified.                                                |
| `libc/private/`                       | Internal-use headers, not for app code.                                            |
| `libc/libc.map.txt`                   | The exported symbol list per API level. Authoritative versioning record.           |

### `__INTRODUCED_IN(N)` annotations

Every bionic public declaration carries an `__INTRODUCED_IN(N)` attribute
naming the **minimum Android API level** at which the symbol exists. Example:

```c
int pthread_setschedprio(pthread_t __pthread, int __priority) __INTRODUCED_IN(28);
```

means: this symbol exists in `libc.so` on Android API 28+ (Android 9.0+).
On earlier devices the symbol is **not present**; calling it after a
`dlsym` would return null, and linking against a runtime-resolved binding
will fail on older devices.

Why this matters for the K/N port: Kotlin/Native's `androidNative*` cinterop
targets a **minimum** NDK API level (currently 21 for the K/N 2.3.x line).
Anything added to bionic at a higher API level **may or may not** appear in
`platform.posix.*` depending on how the NDK's `pthread.h` is conditionalised
at the K/N min-API ceiling.

### Three buckets a libc symbol falls into on Android

When deciding the actual for a function in `androidNativeMain`, classify it:

**Bucket 1 — Bionic has it at K/N's API level.**
Direct delegation:

```kotlin
public actual fun fooBar(...): Int = platform.posix.foo_bar(...)
```

**Bucket 2 — Bionic has it, but only at API > K/N's minimum.**
The symbol is NOT in `platform.posix`; binding requires runtime resolution:

```kotlin
import kotlinx.cinterop.*
import platform.posix.*

@OptIn(ExperimentalForeignApi::class)
private val pthread_setschedprio_p: CPointer<CFunction<(pthread_t, Int) -> Int>>? =
    dlsym(RTLD_DEFAULT, "pthread_setschedprio")
    ?.reinterpret()

@OptIn(ExperimentalForeignApi::class)
public actual fun pthreadSetschedprio(native: PthreadT, priority: Int): Int =
    pthread_setschedprio_p?.invoke(native.rawValue.toLong().convert(), priority) ?: 38
```

The `?: 38` (ENOSYS) is the fallback for devices below the introducing API
level — and that **is** the POSIX-spec response for an unsupported operation.

**Bucket 3 — Bionic genuinely lacks it, at every API level.**
ENOSYS is the right answer; there is nothing to bind to. The Android team
made a deliberate choice to not implement these:

```kotlin
public actual fun pthreadCancel(thread: PthreadT): Int = 38
```

### Verified pthread surface on bionic (as of 2026-05-26)

> Empirical sweep of `/apex/com.android.runtime/lib64/bionic/libc.so` on a
> running API 35 arm64-v8a Google Play emulator using
> `llvm-nm --dynamic --defined-only`. The raw dump is checked in at
> [`docs/bionic-libc-pthread-api35-arm64.txt`](docs/bionic-libc-pthread-api35-arm64.txt)
> (103 pthread symbols). The version suffix on each export marks the bionic
> "version set" the symbol entered: `LIBC` always; `LIBC_N` API 24+;
> `LIBC_O` API 26+; `LIBC_P` API 28+; `LIBC_R` API 30+.

| Symbol                       | Bucket | Notes                                                                                |
|------------------------------|--------|--------------------------------------------------------------------------------------|
| `pthread_create`             | 1      | Always present.                                                                       |
| `pthread_join`               | 1      | Always present.                                                                       |
| `pthread_detach`             | 1      | Always present.                                                                       |
| `pthread_self`               | 1      | Always present.                                                                       |
| `pthread_equal`              | 1      | Always present.                                                                       |
| `pthread_exit`               | 1      | Always present (`__noreturn`).                                                        |
| `pthread_kill`               | 1      | Present. `pthread_t` width follows the ABI bitness — use `.convert()`.               |
| `pthread_mutex_*`            | 1      | Init/destroy/lock/trylock/timedlock/unlock all present.                              |
| `pthread_mutex_clocklock`    | 1      | Present (bionic-specific, Linux-like with explicit clockid).                          |
| `pthread_cond_*`             | 1      | Init/destroy/signal/broadcast/wait/timedwait all present.                             |
| `pthread_cond_clockwait`     | 1      | Present (bionic-specific).                                                            |
| `pthread_cond_timedwait_monotonic_np` | 1 | Present.                                                                            |
| `pthread_rwlock_*`           | 1      | Full surface present including `pthread_rwlock_clockrdlock` / `clockwrlock`.         |
| `pthread_attr_*`             | 1      | init/destroy/getdetachstate/getguardsize/getschedparam/getstack/getstacksize present. |
| `pthread_attr_getinheritsched` / `setinheritsched`     | 2 | API 28+. |
| `pthread_mutexattr_getprotocol` / `setprotocol`        | 2 | API 28+. |
| `pthread_setschedprio`       | 2      | `LIBC_P` (API 28+). Empirically present on API 35 emulator libc.so.                   |
| `pthread_setname_np`         | 1      | Always present.                                                                       |
| `pthread_getname_np`         | 2      | API 26+ (Android 8).                                                                  |
| `pthread_setaffinity_np` / `getaffinity_np` | 2 | API 26+.                                                              |
| `pthread_barrier_*`          | 2      | API 24+ (Android 7).                                                                  |
| `pthread_barrierattr_*`      | 2      | API 24+.                                                                              |
| `pthread_atfork`             | 1      | Always present.                                                                       |
| `pthread_key_create`         | 1      | Always present.                                                                       |
| `pthread_key_delete`         | 1      | Always present.                                                                       |
| `pthread_setspecific`        | 1      | Always present.                                                                       |
| `pthread_cancel`             | 3      | Intentionally not implemented; Android considers `pthread_cancel` unsafe.            |
| `pthread_spin_*`             | 2      | `LIBC_N` (API 24+). Empirically present on API 35 emulator. *Previously misclassified as Bucket 3 based on header-only inspection.* |
| `pthread_mutex_consistent`   | 3      | Bionic does not implement robust mutexes.                                            |

The substitutes for Bucket 3 (when downstream code really needs the
semantics):

- **Cancellation**: cooperative flag + `pthread_testcancel`-style check in the
  worker loop. `pthread_exit` from the worker thread once the flag is seen.
  This is what Android documentation recommends.
- **Spinlocks**: `__atomic_compare_exchange` / `__atomic_load` / `__atomic_store`
  builtins, or `std::atomic` from `<stdatomic.h>` on the C side. Both are
  exposed by bionic.
- **Robust mutexes**: no equivalent. Document the gap at the public-API layer.

### Other libc surfaces worth knowing

- **`libc/stdio/`** is being phased out and replaced with `upstream-freebsd`
  / `upstream-openbsd` source. Behaviour can shift between Android versions.
- **`libc/dns/`** is NetBSD-derived. The DNS resolver does not follow Android
  Connectivity Manager rules and should not be used directly from app code —
  go through Java APIs.
- **`libc/upstream-*`** subtrees are unmodified BSD code, so the LICENSE
  considerations match the source-BSD project (typically BSD-2/BSD-3, which
  is MIT-compatible).

### When bionic intentionally diverges from POSIX

Bionic's maintainer document explicitly says:

> The first question you should ask is "should I add a libc wrapper for this
> system call?". The answer is usually "no". The answer is "yes" if the
> system call is part of the POSIX standard.

So bionic's coverage is roughly "POSIX + Linux extensions actually needed by
the platform". Symbols like `pthread_cancel` deliberately fall outside the
"actually needed" set even though they are POSIX. This is the source of the
Bucket 3 gaps above.

## Looking up a libc symbol on bionic before claiming a gap

Process when you need to confirm whether a symbol is present:

1. `grep -n '<symbol>' /Volumes/stuff/tools/bionic/libc/include/**/*.h`
   (or use `git -C /Volumes/stuff/tools/bionic grep`).
2. Read the declaration. Note any `__INTRODUCED_IN(N)`.
3. If `__INTRODUCED_IN(N)` puts it above the K/N min-API ceiling, the symbol
   needs the **dlsym pattern** (Bucket 2). It is not "missing"; it is
   "absent on old devices, present on new ones".
4. If there is no declaration at all in any bionic header, it is Bucket 3.
5. Only then write ENOSYS.

Never write ENOSYS without doing steps 1–4.

## androidMain (JVM) vs androidNativeMain

The `androidMain` source set's actuals are different in shape:

- The actual is `external fun jniSymbolName(...)` declared on a Kotlin class
  marked `@JvmStatic`, with `System.loadLibrary("libc_kotlin_pthread")` (or
  the right shared library name).
- The shared library is a C/C++ file under `src/androidMain/jni/` that calls
  into bionic, with a `JNI_OnLoad` and a JNIEXPORT-tagged C entry point per
  exported Kotlin symbol.
- The Android Gradle plugin's `externalNativeBuild { ndkBuild { ... } }` or
  `cmake { ... }` block compiles the JNI source into per-ABI `.so`s and packs
  them under `lib/<abi>/` in the AAR.

Because the JNI shim is itself running inside an Android process, it reaches
the same bionic at runtime as `androidNativeMain` does. The Bucket 1 / 2 / 3
classification above applies to *what symbols the JNI shim can call*; it does
not change between the two targets. What changes is *who is doing the
binding* — Kotlin/Native cinterop in `androidNativeMain`, hand-written JNI
in `androidMain`.

For functions that K/N's cinterop already exposes on `androidNativeMain`, the
`androidMain` JNI shim is straight boilerplate (one C function per Kotlin
external, marshalling primitive arguments). For Bucket 2 functions the JNI
shim has the same dlsym-with-fallback shape as the K/N actual.

## arm64-v8a hardening — PAC and BTI

ARM v9 devices support Pointer Authentication (PAC) and Branch Target
Identification (BTI). They are no-ops on ARMv8 devices, so the same binary
runs on both. They only apply to 64-bit code.

If we ship JNI source for `androidMain`, the recommended build settings are:

- `ndk-build`: `LOCAL_BRANCH_PROTECTION := standard` in `Android.mk`.
- `CMake`: `-mbranch-protection=standard` on AArch64 targets.

Cost is roughly 1% code size. The protection is partial (some attack vectors
are mitigated, not all), so PAC/BTI complements rather than replaces CFI.

If you mix a BTI-built object with a non-BTI object at link time, the
resulting library has BTI disabled — check with
`llvm-readelf --notes LIBRARY.so` and look for `aarch64 feature: BTI, PAC`.

## Android SDK normalization (Gradle-backed, no shell)

When a repo still has `setup-android-sdk.sh` / `setup-android-sdk.bat`, normalize it to the Gradle-backed pattern. Reference implementations:
- `serial-test-kotlin` `dc29a78` — "Move Android SDK setup into Gradle"
- `anyhow-kotlin` `0fd90ee` — "Move Android SDK setup into Gradle"

The full pattern (not just the task name):

1. Delete tracked `setup-android-sdk.sh` / `setup-android-sdk.bat`.
2. Add imports to `build.gradle.kts`: `ByteArrayInputStream`, `URI`, `Files`, `StandardCopyOption`, `ZipInputStream`, `GradleException`.
3. Pin SDK versions exactly:
   ```kotlin
   val androidCommandLineToolsRevision = "14742923"
   val projectCompileSdk = "34"
   val projectAndroidBuildTools = "36.0.0"
   ```
4. Keep the SDK project-local under `.android-sdk/`; write/refresh `local.properties` with `sdk.dir=<repo>/.android-sdk` on every run.
5. Host OS detection covers macOS, Linux, Windows (with `sdkmanager.bat`).
6. Add an `.install-complete` marker **plus** a real package-presence predicate:
   ```kotlin
   val requiredAndroidSdkPackageDirs = listOf(
       projectAndroidSdkDir.resolve("platform-tools"),
       projectAndroidSdkDir.resolve("platforms/android-$projectCompileSdk"),
       projectAndroidSdkDir.resolve("build-tools/$projectAndroidBuildTools"),
   )
   fun isProjectAndroidSdkInstalled(): Boolean =
       androidSdkInstallMarker.exists() &&
           androidSdkManager.exists() &&
           requiredAndroidSdkPackageDirs.all { it.exists() }
   ```
   A marker alone is not enough — if a package directory is missing, reinstall.
7. `sdkManagerCommand(...)` helper (direct on Unix, `cmd /c` on Windows).
8. Download command-line tools via `URI(...).toURL().openStream()` (no `curl`).
9. Extract with `ZipInputStream`; guard against zip-slip by resolving entries against canonical `cmdline-tools/latest/`.
10. Preserve executable permissions on non-Windows SDK binaries.
11. Accept licenses non-interactively from Gradle via a finite `ByteArrayInputStream` of `y\n` answers.
12. Install `platform-tools`, `platforms;android-34`, `build-tools;36.0.0`.
13. Preserve SDK manager output in `.android-sdk/sdkmanager-install.log`.
14. Fail with clear `GradleException` including log contents.
15. Call `installProjectAndroidSdk(...)` at configuration time **before** `kotlin { android { … } }` — the Android Gradle plugin resolves SDK location during configuration.
16. `tasks.register<Exec>("setupAndroidSdk")` → Kotlin-backed task that calls `installProjectAndroidSdk(...)`; do **not** shell out.

### Workflow integration
Every workflow command that touches Android must invoke `setupAndroidSdk` first:
- `android.yml`: before `compileAndroidMain`, `assembleUnitTest`, `assembleAndroidTest`.
- `codeql.yml`: before `compileAndroidMain`, `compileKotlinJs`, `compileKotlinWasmJs`.
- `publish.yml`: before the dry-run `compileAndroidMain androidSourcesJar` and before `publishAndReleaseToMavenCentral`.
- `windows.yml`: even for `mingwX64Test`, run `setupAndroidSdk` first — it's the Windows-runner proof that `setupAndroidSdk` is callable through Gradle alone. No WSL, no bash, no batch, no PowerShell duplicate installer.

### Verification of SDK Setup
```bash
rg -n "setup-android-sdk\.sh|setup-android-sdk\.bat|androidSdkSetupCommand|bash.*setup-android-sdk|cmd.*setup-android-sdk" \
  . -g '!build/**' -g '!.gradle/**' -g '!.android-sdk/**'
rg -n "setupAndroidSdk|compileAndroidMain|assembleUnitTest|androidSourcesJar|assembleAndroidTest|publishAndReleaseToMavenCentral" \
  .github/workflows -g '*.yml'
./gradlew setupAndroidSdk --no-daemon --console=plain --no-configuration-cache
./gradlew setupAndroidSdk --no-daemon --console=plain --no-configuration-cache  # must hit cached path
./gradlew compileAndroidMain --no-daemon --console=plain --no-configuration-cache
./gradlew build --dry-run --no-daemon --console=plain --no-configuration-cache
./gradlew test --no-daemon --console=plain --no-configuration-cache
git diff --check
```

### CodeQL `java-kotlin` extraction
KMP repos with an `android` target use `compileAndroidMain` as the CodeQL extraction command (the Android target emits real `.class` files the LD_PRELOAD tracer can hook). `compileKotlinMetadata` extracts zero TRAP. The full pattern + the required `--rerun-tasks --no-build-cache -Pkotlin.compiler.execution.strategy=out-of-process --no-configuration-cache --no-daemon` flags must be preserved when porting workflow YAML.

`codeqlAndroidAar` extraction must use the dedicated configuration pattern from `syn-kotlin`, not mix AARs into `codeqlSourceClasspath`. If no real published Android AAR dependency exists yet, do not add fake coordinates — note in the PR/run report that no published AAR dependency exists.

---

## Sources

- Android NDK ABIs: https://developer.android.com/ndk/guides/abis
- Bionic README + maintainer overview: https://android.googlesource.com/platform/bionic
- Bionic pthread.h: https://android.googlesource.com/platform/bionic/+/HEAD/libc/include/pthread.h
- Bionic libc.map.txt (per-API symbol versioning): https://android.googlesource.com/platform/bionic/+/HEAD/libc/libc.map.txt
- Android API level constants: https://android.googlesource.com/platform/bionic/+/HEAD/libc/include/android/api_level.h
- Local sparse clone: `/Volumes/stuff/tools/bionic/libc/`
