# Native C Bindings — Kotlin/Native cinterop

This directory holds the project's Kotlin/Native FFI surface: the `.def`
cinterop definition and the C wrapper that backs it. It is only active when
`project.ffi.native=true` in `gradle.properties`.

## What's here

```
src/nativeInterop/cinterop/
├── <Lib>.def               — cinterop definition (headers, filters, options)
├── <lowername>_wrapper.h   — function declarations Kotlin binds to
├── <lowername>_wrapper.c   — implementations, compiled into a static .a
└── targets/<konan_target>/ — pre-built .a fallbacks per target (not created
                              by default; used when the cross toolchain for a
                              target is unavailable on the build host)
```

The template default ships `Ffi.def` + `ffi_wrapper.{h,c}` with one real
function (`ffi_errno`, a pointer-returning `errno` accessor) so the build
wiring is provably correct before any library surface is added.

## How the wiring works

`build.gradle.kts` (around the `if (nativeFfiEnabled)` block) does the work:

1. For every Kotlin/Native target, registers a
   `compile<Lib>WrapperFor<Target>` task that compiles
   `<lowername>_wrapper.c` into `<lowername>_wrapper.a` with the
   host-appropriate toolchain:
   - **Apple targets** (`macos*`, `ios*`, `tvos*`, `watchos*`) — `xcrun
     -sdk <sdk> clang` on a Mac host.
   - **Android native** — the Android NDK clang that Kotlin/Native's konan
     downloads (`~/.konan/dependencies/target-toolchain-*-android_ndk/`).
   - **Linux** — host `clang` on Linux; on a Mac host, a downloaded
     konan GCC cross-toolchain.
   - **MinGW** — konan-downloaded msys2 toolchain on Windows, or the
     osx-hosted llvm-ar path on a Mac host.
2. Creates a cinterop named after the lib (`create("<lowername>")`) whose
   `defFile` is `<Lib>.def`, whose `packageName` is `<lowername>.cinterop`
   (the package Kotlin imports from), and whose `extraOpts` includes
   `-libraryPath build/cinterop-targets/<konan_target>` so the just-built
   `.a` is linked into the Kotlin binary.
3. Marks each `cinterop<Lib><Target>` task as depending on the matching
   wrapper-compile task, so Gradle builds the static library before asking
   the cinterop tool to read it.
4. `kotlin.mpp.enableCInteropCommonization=true` is required for these
   bindings to be usable from the shared `nativeMain` source set — without
   it, `import <lowername>.*` fails on every non-nativeMain source set.

If a target's cross toolchain isn't installed on this host and no prebuilt
`.a` exists under `targets/<konan_target>/`, the wrapper task fails with a
clear message — it does not silently skip.

## Adding a surface

Per crate, add to `<lowername>_wrapper.h` the prototypes for every C
function the Rust crate's `extern "C" { pub fn ... }` block exposes, and
implement them in `<lowername>_wrapper.c`. Keep bodies side-effect-free and
one-line-per-function where possible; the goal is to give the cinterop
generator a stable, fixed-width-typed surface.

The pattern reference for scale (430 functions, all the edge cases: string
returns that alias inputs and must be freed, `noStringConversion`, opaque
struct wrappers, `char**` argv patterns, per-platform `#ifdef _WIN32`,
etc.) is the same directory in `/Volumes/stuff/Projects/kotlinmania/libc-kotlin/`.

## Fields that commonly need attention

- **`headers = ... stddef.h stdint.h <lowername>_wrapper.h`** — keep the
  wrapper header last so its prototypes see the fixed-width types.
- **`noStringConversion = ...`** — list here every wrapper returning a
  `char*` that aliases input or ownership. The cinterop tool's default is
  to convert `char*` returns to Kotlin `String`, which drops the pointer
  you need to `free()`. libc-kotlin's `.def` carries the canonical list
  for string-returning libc functions; crib the shape for your crate.
- **`staticLibraries = <lowername>_wrapper.a`** — must match the filename
  the wrapper tasks emit.
- **`compilerOpts = -Isrc/nativeInterop/cinterop`** — lets the wrapper
  header be `#include`d by relative path.

## Renaming for a real port

A single property change (`project.ffi.name=Png` in gradle.properties)
cascades through task names (`compilePngWrapperFor*`), cinterop name
(`create("png")`), package (`png.cinterop`), and def file lookup. You still
need to rename the three files in this directory to match:

- `Ffi.def` → `Png.def`
- `ffi_wrapper.h` → `png_wrapper.h`
- `ffi_wrapper.c` → `png_wrapper.c`

And update the `.def`'s `staticLibraries` line to `png_wrapper.a`.
