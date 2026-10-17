/**
 * N-API addon — module entry.
 *
 * Transliterated from the libc-kotlin pattern
 * (kotlinmania/libc-kotlin/native/node-libc/src/libc_bindings.cpp),
 * generalized to a single hello-world function that proves the wiring:
 * node-gyp builds the addon, the Kotlin/JS (and WASM) side imports the
 * compiled .node via `@JsModule`, and this file's `Init` function is what
 * actually registers exported C++ functions onto the JS-visible object.
 *
 * For a real port: split the function registrations into per-header .cpp
 * modules the way libc-kotlin does (`stdio_bindings.cpp`,
 * `unistd_bindings.cpp`, etc.), and add each module's Init to the exports
 * chain below. Keep the module itself side-effect free apart from
 * populating `exports`.
 *
 * Note: node-gyp's Unix linker strips a "lib" prefix from `target_name`
 * (Unix convention), so `target_name="ffi_native"` in binding.gyp produces
 * `ffi_native.node` (no stripping, since it does not start with "lib").
 * For a project starting with "lib" (libc, libpng), either accept the
 * stripped filename (`c_native.node`, `png_native.node`) in index.js or
 * name the target without the prefix. The libc-kotlin README covers the
 * quirk in detail.
 */
#include <napi.h>

#include <unistd.h>

static Napi::Value GetPid(const Napi::CallbackInfo& info) {
    return Napi::Number::New(info.Env(), static_cast<int>(::getpid()));
}

static Napi::Object Init(Napi::Env env, Napi::Object exports) {
    exports.Set("getpid", Napi::Function::New(env, GetPid));
    return exports;
}

NODE_API_MODULE(ffi_native, Init)
