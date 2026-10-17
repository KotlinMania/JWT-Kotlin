// Entry point for the project's N-API native module.
// Shared between Kotlin/JS (nodeMain) and Kotlin/WASM (wasmJsMain via Node.js).
//
// Transliterated from the libc-kotlin pattern
// (kotlinmania/libc-kotlin/native/node-libc/index.js), generalized.
//
// The addon wraps direct C library calls — no insecure NPM dependencies.
// Both JS and WASM targets load the same compiled .node binary.
//
// Note on filename: node-gyp strips a "lib" prefix from target_name on
// Unix (Unix linker convention). The default `target_name="ffi_native"` in
// binding.gyp produces `ffi_native.node` (no stripping — does not start
// with "lib"). If a project renames the target to start with "lib"
// (e.g. `libpng_native`), the emitted filename loses the prefix
// (`png_native.node`) and this loader must be updated to match. The
// libc-kotlin README documents the quirk.

try {
    module.exports = require('./build/Release/ffi_native.node');
} catch (err) {
    try {
        module.exports = require('./build/Debug/ffi_native.node');
    } catch (err2) {
        throw new Error(
            'Could not load ffi_native addon. ' +
            'Run `npm install` or `node-gyp rebuild` in native/node-ffi directory.\n' +
            'Original error: ' + err.message + '\n' +
            'Debug error: ' + err2.message
        );
    }
}
