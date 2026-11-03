/**
 * Native C wrapper — implementation.
 *
 * Transliterated from the libc-kotlin pattern
 * (kotlinmania/libc-kotlin/src/nativeInterop/cinterop/libc_wrapper.c),
 * generalized. See ffi_wrapper.h for the contract.
 *
 * Compiled by build.gradle.kts's compileFfiWrapperFor<Target> tasks into a
 * per-target static library (ffi_wrapper.a) linked into the cinterop
 * bindings. Keep wrapper bodies side-effect-free apart from calling the
 * wrapped C function — no allocation, no globals.
 */
#include "ffi_wrapper.h"

int* ffi_errno(void) {
    return &errno;
}
