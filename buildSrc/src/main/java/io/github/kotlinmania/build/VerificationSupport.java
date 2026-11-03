package io.github.kotlinmania.build;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import javax.inject.Inject;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.process.ExecOperations;

/**
 * Shared verification and Wasm/WASI test fixture helpers for KotlinMania KMP library modules.
 */
public final class VerificationSupport {
    private VerificationSupport() {}

    public abstract static class InjectedExec {
        @Inject
        public abstract ExecOperations getExecOperations();
    }

    private static ExecOperations execOps(Project project) {
        return project.getObjects().newInstance(InjectedExec.class).getExecOperations();
    }

    public static String resolveKonanLlvmBin(File konanDataDir) {
        File dependencies = new File(konanDataDir, "dependencies");
        File receipt = new File(dependencies, ".extracted");
        if (!receipt.isFile()) {
            return "";
        }
        try {
            return Files.readAllLines(receipt.toPath(), StandardCharsets.UTF_8).stream()
                    .filter(line -> line.startsWith("llvm-"))
                    .distinct()
                    .sorted(Comparator.comparingInt((String name) -> {
                                String num = name.substring("llvm-".length());
                                int dash = num.indexOf('-');
                                if (dash >= 0) {
                                    num = num.substring(0, dash);
                                }
                                try {
                                    return Integer.parseInt(num);
                                } catch (NumberFormatException e) {
                                    return 0;
                                }
                            })
                            .reversed())
                    .map(name -> new File(dependencies, name + "/bin"))
                    .filter(bin -> new File(bin, "clang").canExecute() && new File(bin, "lld").canExecute())
                    .map(File::getAbsolutePath)
                    .findFirst()
                    .orElse("");
        } catch (IOException e) {
            return "";
        }
    }

    public static void compileMacroLayoutWasm(
            Project project,
            File macroLayoutCSource,
            File cinteropIncludeDir,
            File directory,
            File wasmFile,
            File scriptFile,
            String llvmBin) {
        directory.mkdirs();
        String clang = llvmBin.isEmpty() ? "clang" : new File(llvmBin, "clang").getAbsolutePath();
        String lld = llvmBin.isEmpty() ? "lld" : new File(llvmBin, "lld").getAbsolutePath();
        File objectFile = new File(directory, "macro-layout.o");
        ExecOperations exec = execOps(project);
        exec.exec(spec -> spec.commandLine(
                        clang,
                        "--target=wasm32",
                        "-ffreestanding",
                        "-nostdlib",
                        "-Wall",
                        "-Wextra",
                        "-Werror",
                        "-I" + cinteropIncludeDir.getAbsolutePath(),
                        "-c",
                        macroLayoutCSource.getAbsolutePath(),
                        "-o",
                        objectFile.getAbsolutePath()))
                .assertNormalExitValue();
        exec.exec(spec -> spec.commandLine(
                        lld,
                        "-flavor",
                        "wasm",
                        "--no-entry",
                        "--export=kotlinmania_macro_layout_value",
                        objectFile.getAbsolutePath(),
                        "-o",
                        wasmFile.getAbsolutePath()))
                .assertNormalExitValue();
        try {
            String encoded = Base64.getEncoder().encodeToString(Files.readAllBytes(wasmFile.toPath()));
            String script = "// Actual compiler output from macro_parity_fixture.h; keep one instance for all address comparisons.\n"
                    + "(() => {\n"
                    + "    const bytes = Uint8Array.from(atob('" + encoded + "'), c => c.charCodeAt(0));\n"
                    + "    const instance = new WebAssembly.Instance(new WebAssembly.Module(bytes), {});\n"
                    + "    globalThis.__kotlinmaniaMacroLayoutValue = index => instance.exports.kotlinmania_macro_layout_value(index).toString();\n"
                    + "})();\n";
            Files.writeString(scriptFile.toPath(), script, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new GradleException("Failed to write macro-layout.js", e);
        }
    }

    public static void patchWasmWasiTestPreopens(File driverDir, File hostRoot, List<String> preopens) {
        File[] candidates = driverDir.listFiles(file -> file.getName().endsWith("-test.mjs"));
        if (candidates == null || candidates.length != 1) {
            throw new GradleException("wasmWasi test driver not found in " + driverDir);
        }
        File driver = candidates[0];
        deleteRecursively(hostRoot);
        hostRoot.mkdirs();
        List<String> mappings = new ArrayList<>();
        for (int i = 0; i < preopens.size(); i++) {
            File host = new File(hostRoot, "preopen-" + i);
            host.mkdirs();
            String hostPath = host.getAbsolutePath().replace("\\", "\\\\");
            mappings.add("'" + preopens.get(i) + "': '" + hostPath + "'");
        }
        try {
            String text = Files.readString(driver.toPath(), StandardCharsets.UTF_8);
            if (!text.contains("preopens:")) {
                String replacement = "new WASI({ preopens: { " + String.join(", ", mappings) + " },";
                String patched = text.replaceFirst("new WASI\\(\\{", Matcher.quoteReplacement(replacement));
                if (patched.equals(text)) {
                    throw new GradleException("Could not add preopens to " + driver.getName());
                }
                Files.writeString(driver.toPath(), patched, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new GradleException("Failed to patch wasmWasi test driver " + driver, e);
        }
    }

    private static void deleteRecursively(File file) {
        if (!file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }
}
