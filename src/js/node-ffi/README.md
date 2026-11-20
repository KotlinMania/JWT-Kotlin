# N-API Native Bindings (template)

Shared C++ N-API addon providing direct C library bindings for the project's
Kotlin/JS and Kotlin/WASM targets. Transliterated from the libc-kotlin pattern
(`src/js/node-libc/dist/` in [libc-kotlin](https://github.com/KotlinMania/libc-kotlin)) and
generalized: the addon compiles into a reusable `.node` binary that both
targets load.

## Architecture

```
src/js/node-ffi/dist/
├── binding.gyp            — source/link specification for the Java builder (one target, ffi_native)
├── package.json           — npm package (@kotlinmania/template-native-bindings)
├── index.js               — loader (Release → Debug fallback)
└── src/
    └── ffi_bindings.cpp   — module init + hello-world getpid export
```

The template ships exactly one exported function (`getpid()`) so that the
Gradle wiring — build, copy into the Kotlin test runtime, `@JsModule` import,
call from a test — is verifiable end-to-end without carrying a library API
surface. Real ports split the registrations into per-header modules the way
libc-kotlin does (`stdio_bindings.cpp`, `unistd_bindings.cpp`, etc.).

## Building

Run from the repository root using the Java N-API builder:

```sh
./gradlew buildNodeFfi -Pproject.ffi.napi=true -Pproject.ffi.name=Ffi
```

The Gradle task installs the required Node addon headers with package lifecycle scripts disabled, then `NodeFfiBuilder` compiles the declared C/C++ sources against the pinned Node headers. The addon is written to this directory's `build/Release/`. The corresponding Gradle copy tasks place it in the Kotlin JS/Wasm test runtime. Build failures and compiler diagnostics remain failures.

## Output filename compatibility

On Unix hosts, the Java builder preserves the existing convention of stripping a `lib` prefix from
`target_name`, so `target_name = "libc_native"` in libc-kotlin's binding.gyp
produces `c_native.node`, not `libc_native.node`. The template's default
target is `ffi_native` — no stripping, the emitted file is `ffi_native.node`.
If you rename the target to start with "lib" (e.g. `libpng_native`), update
`index.js` to match the stripped filename.

## Usage from Kotlin

The Kotlin jsMain side declares the module as an external interface:

```kotlin
@JsModule("@kotlinmania/template-native-bindings")
public external interface TemplateNativeBindings {
    public fun getpid(): Int
}
```

The Gradle `copyNodeFfi` task copies the built addon into
`build/js/node_modules/@kotlinmania/template-native-bindings/` so Node's
resolver finds it when the `@JsModule` annotation is consulted during
`jsNodeTest` / `wasmJsNodeTest`.

## Renames for a real port

To generalize this directory for a crate (e.g. a `png` port with
`project.ffi.name=Png`):

1. `src/js/node-ffi/dist/` → `src/js/node-png/dist/`
2. `binding.gyp`: `target_name` → `png_native`
3. `package.json`: `name` → `@kotlinmania/png-native-bindings`
4. `index.js`: both filename references → `png_native.node`
5. `src/ffi_bindings.cpp`: rename + replace the `getpid` registration; the
   `NODE_API_MODULE(<target>, Init)` first argument must match binding.gyp's
   `target_name`
6. Kotlin: `TemplateNativeBindings.kt` → `PngNativeBindings.kt`, interface
   renamed, `@JsModule` string → `@kotlinmania/png-native-bindings`
7. `build.gradle.kts`: task names carry the capitalized lib name
   (`buildNodeFfi` → `buildNodePng`); they derive from `project.ffi.name`,
   so a single property change cascades.
