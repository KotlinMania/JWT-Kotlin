/**
 * N-API native bindings for JS/WASM targets — Kotlin side.
 *
 * Loads the shared C++ N-API addon built from `native/node-ffi/` (see that
 * directory's README.md for the architecture). Both Kotlin/JS (nodeMain)
 * and Kotlin/WASM (wasmJsMain via Node.js) import the same compiled
 * `.node` binary.
 *
 * Transliterated from the libc-kotlin pattern
 * (kotlinmania/libc-kotlin/src/jsMain/kotlin/.../LibcNative.kt),
 * generalized.
 *
 * The interface is `public` because Kotlin/JS `external` declarations are
 * bridged on the Kotlin side, not through Swift Export — Swift Export only
 * reads the Apple framework surface, and external JS declarations are not
 * part of that surface. Keep the declarations on this interface, not on
 * the package namespace, so the module's public API surface in Swift is
 * unaffected.
 *
 * For a real port: replace `TemplateNativeBindings` / `@JsModule` name with
 * the per-project npm package (e.g. `@kotlinmania/png-native-bindings`) and
 * declare the crate's N-API functions. The N-API function signatures on the
 * C++ side (native/node-ffi/src/) use fixed-width int32/int64 — there is
 * no pointer-sized type in the N-API ABI.
 */
package io.github.kotlinmania.template

@JsModule("@kotlinmania/template-native-bindings")
public external interface TemplateNativeBindings {
    /** Returns the calling process ID. Hello-world export matching
     * the C++ `getpid` registration in `native/node-ffi/src/ffi_bindings.cpp`.
     */
    public fun getpid(): Int
}
