plugins {
    java
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

// Only product-building helpers belong here. Standalone verification and workspace
// automation are compiled by tools/java-build-tool.sh, outside the product build.
dependencies {
    implementation(gradleApi())
    implementation(localGroovy())
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

// Project-owned helpers use the parsers and archive libraries bundled with Gradle.
dependencies {
    implementation(fileTree(requireNotNull(gradle.gradleHomeDir).resolve("lib")) {
        include("tomlj-*.jar", "antlr4-runtime-*.jar", "commons-compress-*.jar", "commons-io-*.jar", "commons-codec-*.jar", "commons-lang3-*.jar")
    })
}

tasks.register<JavaExec>("runBuildTool") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.kotlinmania.build.BuildToolMain")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    workingDir(projectDir.parentFile)
    val scratch = projectDir.parentFile.resolve("build/tmp/java-build-tools")
    doFirst { scratch.mkdirs() }
    systemProperty("java.io.tmpdir", scratch.absolutePath)
    environment("TMPDIR", scratch.absolutePath)
    environment("TMP", scratch.absolutePath)
    environment("TEMP", scratch.absolutePath)
}
