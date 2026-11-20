import groovy.json.JsonOutput
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.tasks.testing.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp)
}
// Isolate outputs when allocated; this fixture never writes into producer checkouts.
providers.gradleProperty("consumerBuildRoot").orNull?.let { layout.buildDirectory.set(file(it)) }
val expectedVersions = mapOf(
    "async-trait-kotlin" to providers.gradleProperty("asyncVersion").get(),
    "android-system-properties-kotlin" to providers.gradleProperty("systemPropertiesVersion").get(),
)
kotlin {
    jvmToolchain(21)
    compilerOptions { allWarningsAsErrors.set(true) }
    android {
        namespace = "io.github.kotlinmania.template.publicationconsumer"
        compileSdk = providers.gradleProperty("consumerCompileSdk").get().toInt()
        minSdk = 24
        withHostTestBuilder { sourceSetTreeName = "test" }
    }
    sourceSets {
        commonMain.dependencies {
            expectedVersions.forEach { (module, version) -> implementation("io.github.kotlinmania:$module:$version") }
        }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
val hostTests = tasks.withType<Test>().matching { it.name == "testAndroidHostTest" }
hostTests.configureEach { outputs.upToDateWhen { false } }

kotlin.targets.named("android") {
    compilations.named("main") {
        val compileDependencies = configurations.getByName(compileDependencyConfigurationName)
        // Fail explicitly if the Android plugin changes its resolvable configuration layout.
        val runtimeName = compileDependencyConfigurationName.replace("CompileClasspath", "RuntimeClasspath")
        val runtimeDependencies = configurations.named(runtimeName)
        val classpathReceipt = layout.buildDirectory.file("reports/publication/raw-classpaths.json")
        val writeClasspath = tasks.register("writeAndroidPublicationClasspath") {
            outputs.file(classpathReceipt)
            outputs.upToDateWhen { false }
            doLast {
                val artifactsByConfiguration = mapOf(
                    "compile" to compileDependencies,
                    "runtime" to runtimeDependencies.get(),
                ).mapValues { (scope, configuration) ->
                    val artifacts = configuration.incoming.artifacts.artifacts.map { it.file }.distinct()
                    check(artifacts.isNotEmpty() && artifacts.all { it.extension in setOf("aar", "jar") }) {
                        "Unsupported raw Android $scope classpath artifact layout: $artifacts"
                    }
                    artifacts.sortedBy { it.absolutePath }.map { it.absolutePath }
                }
                classpathReceipt.get().asFile.apply {
                    parentFile.mkdirs()
                    writeText(JsonOutput.prettyPrint(JsonOutput.toJson(artifactsByConfiguration)) + "\n")
                }
            }
        }
        // Preserve raw dependency evidence even when a host test fails. Duplicate
        // bytecode inspection is an explicit standalone AndroidClasspathVerifier call.
        hostTests.configureEach { finalizedBy(writeClasspath) }
        tasks.register("verifyAndroidConsumer") {
            dependsOn(compileTaskProvider, hostTests, writeClasspath)
            doLast {
                val selections = mutableListOf<String>()
                compileDependencies.incoming.resolutionResult.rootComponent.get().dependencies
                    .filterIsInstance<ResolvedDependencyResult>().forEach { dependency ->
                        val requested = dependency.requested as? ModuleComponentSelector ?: return@forEach
                        val version = expectedVersions[requested.module] ?: return@forEach
                        val variant = dependency.resolvedVariant.externalVariant.orElse(dependency.resolvedVariant)
                        val owner = variant.owner as ModuleComponentIdentifier
                        val attributes = variant.attributes.keySet().associate { it.name to variant.attributes.getAttribute(it).toString() }
                        check(owner.group == "io.github.kotlinmania" && owner.module == "${requested.module}-android" && owner.version == version)
                        check(attributes["org.jetbrains.kotlin.platform.type"] == "androidJvm" &&
                            attributes["org.gradle.libraryelements"] == "aar" && attributes["org.gradle.jvm.environment"] == "android")
                        selections += "${owner.group}:${owner.module}:${owner.version} $attributes"
                    }
                check(selections.size == expectedVersions.size) { "Not every root dependency selected an Android AAR" }
                val expected = setOf("publishedAsyncResultAndQuoteApi", "publishedThrowingApi", "publishedAndroidPropertyInputValidation")
                val executed = mutableSetOf<String>()
                check(hostTests.size == 1)
                val parser = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                hostTests.single().reports.junitXml.outputLocation.get().asFile.listFiles()
                    .orEmpty().filter { it.extension == "xml" }.forEach { xml ->
                        val cases = parser.parse(xml).getElementsByTagName("testcase")
                        for (index in 0 until cases.length) {
                            val case = cases.item(index) as Element
                            if (case.getAttribute("classname") == "consumer.PublicationConsumerTest") {
                                check(listOf("failure", "error", "skipped").none { case.getElementsByTagName(it).length > 0 })
                                check(executed.add(case.getAttribute("name")))
                            }
                        }
                    }
                check(executed == expected) { "Missing/extra/skipped consumer tests: $executed" }
                layout.buildDirectory.file("reports/publication/consumer.txt").get().asFile.apply {
                    parentFile.mkdirs()
                    writeText((selections + executed.sorted()).joinToString("\n", postfix = "\n"))
                }
            }
        }
    }
}
