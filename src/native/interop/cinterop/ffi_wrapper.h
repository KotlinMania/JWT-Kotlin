/**
 * Native C wrapper — Kotlin/Native cinterop surface.
 *
 * Transliterated from the libc-kotlin pattern
 * (kotlinmania/libc-kotlin/src/nativeInterop/cinterop/libc_wrapper.h),
 * generalized to a single hello-world function that proves the wiring:
 * a project declares the functions it wants to expose to Kotlin here and
 * implements their bodies in ffi_wrapper.c; the build.gradle.kts
 * compileFfiWrapperFor<Target> tasks compile this into a per-target static
 * library that the cinterop-generated Kotlin bindings link against.
 *
 * The one function below is real: ffi_errno returns a pointer to the
 * process's errno so Kotlin can read the C error code after any FFI call
 * that reports failure through errno. It is enough to prove the
 * .a → cinterop → expect/actual chain without carrying a library-specific
 * surface.
 *
 * Rename both files to <lowername>_wrapper.{h,c} to match project.ffi.name
 * (lowercased) when filling in a real port.
 */
#ifndef FFI_WRAPPER_H
#define FFI_WRAPPER_H

#include <errno.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Returns a pointer to the calling thread's errno. Exposed via cinterop so
 * Kotlin/Native callers can read the C error code after an FFI call that
 * reports failure through errno. The pointer aliases thread-local state —
 * declared in .def's noStringConversion so cinterop does not try to convert
 * it.
 */
int* ffi_errno(void);

#ifdef __cplusplus
}
#endif

#endif /* FFI_WRAPPER_H */
