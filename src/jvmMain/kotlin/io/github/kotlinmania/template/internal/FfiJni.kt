/**
 * JVM JNI bridge for the project's native C bindings.
 *
 * Transliterated from the libc-kotlin pattern
 * (kotlinmania/libc-kotlin/src/jvmMain/kotlin/.../internal/LibcJni.kt),
 * generalized and deliberately kept small.
 *
 * Three deliberate deviations from libc-kotlin's version:
 * - This bridge is `internal`. Per SWIFT.md §"internal expect fun symbols
 *   leak into the Swift Export bridge," public external surfaces on the JVM
 *   side can leak into generated Swift output. `internal` keeps Swift Export
 *   from emitting a bridge for it; Swift consumers never need JNI.
 * - No fallback "compile the C source at runtime from a Kotlin string"
 *   path. If the dylib is not on `java.library.path`, that is a build
 *   failure (the compileFfiJni task or the jvmTest wiring is broken) and the
 *   test should fail loudly, not silently produce a recompiled copy with
 *   different contents. A Kotlin string copy of C source is also a second
 *   source of truth.
 * - File name is `<Lib>Jni.kt`, unique per source set, per SWIFT.md §"JVM
 *   class name clashes between commonMain and platform source sets." Do
 *   NOT name this the same as a commonMain expect file.
 *
 * Renaming for a real port: change the object name and the library name
 * together, and re-mangle every JNI symbol in the matching
 * `src/jvmMain/c/<lowername>_jni.c` to
 * `Java_<namespace_with_underscores>_internal_<Lib>Jni_<method>`. The two
 * sides must move atomically — drift fails only at jvmTest time.
 */
package io.github.kotlinmania.template.internal

internal object FfiJni {
    init {
        // The build wires -Djava.library.path=build/natives into jvmTest,
        // where the compileFfiJni task drops the compiled dylib/so/dll.
        // No fallback: a missing binding is a build defect, not a runtime
        // condition to paper over.
        System.loadLibrary("ffi_jni")
    }

    /**
     * Returns the calling process ID. Hello-world export matching
     * `Java_io_github_kotlinmania_template_internal_FfiJni_getpid` in
     * `src/jvmMain/c/ffi_jni.c`.
     */
    @JvmStatic
    external fun getpid(): Int
}
