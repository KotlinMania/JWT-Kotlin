package io.github.kotlinmania.build;

import java.util.List;
import java.util.Map;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPluginExtension;

/**
 * Configures hierarchical source-set directory paths across Kotlin and Java source sets
 * so IntelliJ IDEA's {@code GradleSourceSetModelBuilder} and {@code KotlinMppGradleProjectResolver}
 * resolve every source set to its directory in the {@code src/} hierarchy.
 */
public final class IdeSourceSetSupport {
    private IdeSourceSetSupport() {}

    public static final Map<String, String> HIERARCHICAL_PATHS = Map.ofEntries(
            Map.entry("androidTest", "src/android/jvm/androidTest"),
            Map.entry("apple64Main", "src/apple/apple64Main"),
            Map.entry("apple64Test", "src/apple/apple64Test"),
            Map.entry("appleLeafMain", "src/apple/appleLeafMain"),
            Map.entry("blockingMain", "src/common/blockingMain"),
            Map.entry("blockingTest", "src/common/blockingTest"),
            Map.entry("browserMain", "src/js/browserMain"),
            Map.entry("browserTest", "src/js/browserTest"),
            Map.entry("commonBenchmark", "src/common/commonBenchmark"),
            Map.entry("desktopMain", "src/common/desktopMain"),
            Map.entry("filesystemTest", "src/common/filesystemTest"),
            Map.entry("hostNativeTest", "src/native/hostNativeTest"),
            Map.entry("jvmAndAndroidMain", "src/common/jvmAndAndroidMain"),
            Map.entry("jvmAndAndroidTest", "src/common/jvmAndAndroidTest"),
            Map.entry("jvmBenchmark", "src/jvm/jvmBenchmark"),
            Map.entry("jvmConsumerTest", "src/jvm/jvmConsumerTest"),
            Map.entry("linuxLikeMain", "src/native/linux/linuxLikeMain"),
            Map.entry("nodeMain", "src/js/nodeMain"),
            Map.entry("nodeTest", "src/js/nodeTest"),
            Map.entry("nonPosixMain", "src/native/nonPosixMain"),
            Map.entry("nonWinMain", "src/native/nonWinMain"),
            Map.entry("commonMain", "src/common/commonMain"),
            Map.entry("commonTest", "src/common/commonTest"),
            Map.entry("hostSystemMain", "src/common/hostSystemMain"),
            Map.entry("hostSystemTest", "src/common/hostSystemTest"),
            Map.entry("jvmMain", "src/jvm/jvmMain"),
            Map.entry("jvmTest", "src/jvm/jvmTest"),
            Map.entry("androidMain", "src/android/jvm/androidMain"),
            Map.entry("androidNativeMain", "src/android/native/androidNativeMain"),
            Map.entry("androidNativeArm64Main", "src/android/native/androidNativeArm64Main"),
            Map.entry("androidNativeX64Main", "src/android/native/androidNativeX64Main"),
            Map.entry("androidHostTest", "src/android/jvm/androidHostTest"),
            Map.entry("androidDeviceTest", "src/android/jvm/androidDeviceTest"),
            Map.entry("androidNativeTest", "src/android/native/androidNativeTest"),
            Map.entry("androidNativeArm64Test", "src/android/native/androidNativeArm64Test"),
            Map.entry("androidNativeX64Test", "src/android/native/androidNativeX64Test"),
            Map.entry("wasmWasiMain", "src/js/wasm/wasmWasiMain"),
            Map.entry("wasmWasiTest", "src/js/wasm/wasmWasiTest"),
            Map.entry("webMain", "src/js/webMain"),
            Map.entry("webTest", "src/js/webTest"),
            Map.entry("jsMain", "src/js/jsMain"),
            Map.entry("jsTest", "src/js/jsTest"),
            Map.entry("wasmJsMain", "src/js/wasm/wasmJsMain"),
            Map.entry("wasmJsTest", "src/js/wasm/wasmJsTest"),
            Map.entry("nativeMain", "src/native/nativeMain"),
            Map.entry("nativeTest", "src/native/nativeTest"),
            Map.entry("posixMain", "src/native/posix/posixMain"),
            Map.entry("posixTest", "src/native/posix/posixTest"),
            Map.entry("linuxMain", "src/native/linux/linuxMain"),
            Map.entry("linuxTest", "src/native/linux/linuxTest"),
            Map.entry("linuxArm64Main", "src/native/linux/linuxArm64Main"),
            Map.entry("linuxArm64Test", "src/native/linux/linuxArm64Test"),
            Map.entry("linuxX64Main", "src/native/linux/linuxX64Main"),
            Map.entry("linuxX64Test", "src/native/linux/linuxX64Test"),
            Map.entry("mingwMain", "src/native/mingw/mingwMain"),
            Map.entry("mingwTest", "src/native/mingw/mingwTest"),
            Map.entry("mingwX64Main", "src/native/mingw/mingwX64Main"),
            Map.entry("mingwX64Test", "src/native/mingw/mingwX64Test"),
            Map.entry("appleMain", "src/apple/appleMain"),
            Map.entry("appleTest", "src/apple/appleTest"),
            Map.entry("iosMain", "src/apple/ios/iosMain"),
            Map.entry("iosTest", "src/apple/ios/iosTest"),
            Map.entry("iosArm64Main", "src/apple/ios/iosArm64Main"),
            Map.entry("iosArm64Test", "src/apple/ios/iosArm64Test"),
            Map.entry("iosSimulatorArm64Main", "src/apple/ios/iosSimulatorArm64Main"),
            Map.entry("iosSimulatorArm64Test", "src/apple/ios/iosSimulatorArm64Test"),
            Map.entry("macosMain", "src/apple/macos/macosMain"),
            Map.entry("macosTest", "src/apple/macos/macosTest"),
            Map.entry("macosArm64Main", "src/apple/macos/macosArm64Main"),
            Map.entry("macosArm64Test", "src/apple/macos/macosArm64Test"),
            Map.entry("tvosMain", "src/apple/tvos/tvosMain"),
            Map.entry("tvosTest", "src/apple/tvos/tvosTest"),
            Map.entry("tvosArm64Main", "src/apple/tvos/tvosArm64Main"),
            Map.entry("tvosArm64Test", "src/apple/tvos/tvosArm64Test"),
            Map.entry("tvosSimulatorArm64Main", "src/apple/tvos/tvosSimulatorArm64Main"),
            Map.entry("tvosSimulatorArm64Test", "src/apple/tvos/tvosSimulatorArm64Test"),
            Map.entry("watchosMain", "src/apple/watchos/watchosMain"),
            Map.entry("watchosTest", "src/apple/watchos/watchosTest"),
            Map.entry("watchosDeviceArm64Main", "src/apple/watchos/watchosDeviceArm64Main"),
            Map.entry("watchosDeviceArm64Test", "src/apple/watchos/watchosDeviceArm64Test"),
            Map.entry("watchosSimulatorArm64Main", "src/apple/watchos/watchosSimulatorArm64Main"),
            Map.entry("watchosSimulatorArm64Test", "src/apple/watchos/watchosSimulatorArm64Test"));

    public static String sourceSetDir(String name) {
        return HIERARCHICAL_PATHS.getOrDefault(name, "src/" + name);
    }

    public static void configure(Project project) {
        // Work around IntelliJ IDEA 2026.2 + Gradle 9.7+ resilient model fetch bug where
        // optional IDE plugin models (e.g. IdeaComposeHotReloadModel, XcodeCocoaPodsModel)
        // call fetchModelOrNull() without suppressFailures and report UnknownModelException
        // as a sync failure (ExternalSystemPartialResolutionException).
        System.setProperty("idea.use.resilient.model.fetch", "false");
        JavaPluginExtension javaExt = project.getExtensions().findByType(JavaPluginExtension.class);
        if (javaExt != null) {
            javaExt.getSourceSets().configureEach(ss -> {
                String relPath = sourceSetDir(ss.getName());
                ss.getJava().setSrcDirs(List.of(relPath + "/java"));
                ss.getResources().setSrcDirs(List.of(relPath + "/resources"));
            });
        }
    }
}
