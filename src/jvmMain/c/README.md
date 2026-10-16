# JVM JNI Bindings

This directory holds the C source for the JVM-native bridge. It is only
active when `project.ffi.jvm=true` in `gradle.properties`.

## What's here

```
src/jvmMain/c/
└── <lowername>_jni.c   — JNI implementations, built into lib<lowername>_jni.{dylib,so,dll}
```

The matching Kotlin bridge class is
`src/jvmMain/kotlin/<namespace>/internal/<Lib>Jni.kt`, an `internal object`
declaring `external fun`s with `@JvmStatic`. The build wires the compiled
library into `jvmTest`'s `java.library.path`, so `System.loadLibrary(...)`
in the bridge's `init` resolves without any environment setup.

## How the wiring works

`build.gradle.kts`'s `if (jvmFfiEnabled)` block registers
`compile<Lib>Jni`, which:

1. Compiles `<lowername>_jni.c` with the host `clang` (`-shared -O2`,
   plus `-fPIC` on non-Windows) against the JDK's JNI headers
   (`$JAVA_HOME/include` plus the platform-specific subdirectory).
2. Emits `build/natives/lib<lowername>_jni.{dylib,so,dll}` with the
   platform-correct prefix and extension.
3. `tasks.named<Test>("jvmTest")` adds a dependency on the compile task and
   sets `-Djava.library.path=build/natives`, so the library resolves at
   runtime without `DYLD_LIBRARY_PATH`/`LD_LIBRARY_PATH` edits.

The task is included in `fullTargetBuildTaskNames` when the gate is on, so
a broken JNI compile fails `./gradlew build` loudly.

## The JNI symbol-name coupling (read this before renaming anything)

The C function names here must match the JVM's JNI mangling convention:
`Java_<package_with_underscores>_<ClassName>_<methodName>`. The Kotlin
bridge class and the C symbols are therefore coupled across two dimensions
that Gradle cannot validate:

- If you rename the Kotlin bridge class (`FfiJni` → `LibcJni`), every
  JNIEXPORT symbol here must change the class segment to match.
- If you change the Kotlin package (e.g. from `...template.internal` to
  `...libc.internal`), the package segment of every symbol must change.

Getting either wrong compiles cleanly on both sides and fails only at
`jvmTest` with `java.lang.UnsatisfiedLinkError: ...FfiJni.getpid()`. When
you see that error, this file's symbol names vs the Kotlin bridge class
are the first place to look.

The template default pairs:

- C symbol: `Java_io_github_kotlinmania_template_internal_FfiJni_getpid`
- Kotlin: `internal object FfiJni` in `io.github.kotlinmania.template.internal`
  declaring `@JvmStatic external fun getpid(): Int`

## Keeping the bridge invisible to Swift Export

The `${Lib}Jni` object is `internal` deliberately. Per the SWIFT.md §9
note ("internal expect fun symbols leak into the Swift Export bridge"),
public external surfaces on the JVM side can be bridged into the Apple
framework's Swift output, which is wrong — Swift consumers never need JNI.
`internal` keeps the bridge Kotlin/JVM-only.

For the same reason, do NOT name this file with the same stem as a
commonMain expect file (SWIFT.md §"JVM class name clashes"): both source
sets would emit `FooKt` on JVM and the build would fail with a duplicate
class error. `<Lib>Jni.kt` inside `internal/` is enough separation.

## Adding a function

1. Add the `JNIEXPORT` definition here, mangled to match the Kotlin side.
2. Add `@JvmStatic external fun <name>(...)` to the `<Lib>Jni` object.
3. No registration step — the JVM resolves `Java_..._<name>` by name on
   first call.

Use fixed-width JNI types for parameters and returns (`jint`, `jlong`,
`jstring`, `jbyteArray`). There is no pointer-sized primitive in the JNI
type set, so C pointers cross as `jlong` handles. The libc-kotlin version
of this file
(`kotlinmania/libc-kotlin/src/jvmMain/c/libc_jni.c`) is the worked example
for every common edge case: string conversion with `GetStringUTFChars` /
`ReleaseStringUTFChars`, `ByteArray` ↔ `void*` bridging, Windows-specific
replacements, and the per-OS `#ifdef _WIN32` pattern.
