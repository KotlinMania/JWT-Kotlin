/**
 * JNI bindings for the JVM target.
 *
 * Transliterated from the libc-kotlin pattern
 * (kotlinmania/libc-kotlin/src/jvmMain/c/libc_jni.c), generalized to a
 * single hello-world function that proves the wiring end-to-end: the
 * compiled dylib/so/dll exports one native method the Kotlin jvmMain side
 * can `external` declare against; build.gradle.kts's compileFfiJni task
 * builds it with the host clang and wires
 * -Djava.library.path into jvmTest so the runtime resolves.
 *
 * Symbol names follow the JNI mangling convention:
 *   Java_<package_with_underscores>_<ClassName>_<methodName>
 * For a project named png (project.ffi.name=Png, namespace
 * io.github.kotlinmania.png), the internal bridge class is
 * io.github.kotlinmania.png.internal.PngJni and the hello function becomes
 * Java_io_github_kotlinmania_png_internal_PngJni_getpid.
 *
 * Renaming a template copy involves ONE atomic change, not two: rename this
 * file to <lowername>_jni.c and change BOTH the namespace segment and the
 * class segment of the JNI symbols to match the Kotlin side. The coupling
 * is invisible to Gradle — if the Kotlin bridge class is
 * `internal object LibcJni` inside package `io.github.kotlinmania.libc.internal`,
 * the mangled C symbol must be `Java_io_github_kotlinmania_libc_internal_LibcJni_*`.
 * If they drift, `getpid()` compiles clean here and fails with
 * `java.lang.UnsatisfiedLinkError` only when jvmTest runs.
 *
 * For the template default the Kotlin bridge is
 * `internal object FfiJni` in `io.github.kotlinmania.template.internal`,
 * hence the symbol below.
 */
#include <jni.h>

#ifndef _WIN32
#include <unistd.h>
#else
#include <process.h>
#endif

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Returns the calling process ID. Hello-world JNI export — proves that the
 * shared library built by compileFfiJni loads and links into the JVM test
 * runtime. Real ports extend this file with the crate's own wrapper
 * functions; there is no registration step beyond adding the JNIEXPORT
 * definition here and a matching `external fun` on the Kotlin bridge class.
 *
 * The `template` + `Ffi` segments of the symbol name are placeholders —
 * re-mangle together with the Kotlin bridge class per project (see header
 * comment above).
 */
JNIEXPORT jint JNICALL
Java_io_github_kotlinmania_template_internal_FfiJni_getpid(
    JNIEnv* env, jclass clazz) {
    (void)env; (void)clazz;
#ifndef _WIN32
    return (jint)getpid();
#else
    return (jint)_getpid();
#endif
}

#ifdef __cplusplus
}
#endif
