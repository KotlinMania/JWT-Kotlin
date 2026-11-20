package io.github.kotlinmania.build;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import javax.inject.Inject;
import javax.xml.parsers.DocumentBuilderFactory;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.process.ExecOperations;
import org.w3c.dom.Element;

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

    public static void verifyJunitXmlReports(String taskPath, Set<File> reports) {
        if (reports.isEmpty()) {
            throw new GradleException("Missing runtime reports for " + taskPath);
        }
        int tests = 0;
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            for (File report : reports) {
                Element suite = builder.parse(report).getDocumentElement();
                int count = Integer.parseInt(suite.getAttribute("tests"));
                if (suite.getElementsByTagName("testcase").getLength() != count) {
                    throw new GradleException(
                            taskPath + ": test count does not match the recorded cases in " + report.getName());
                }
                tests += count;
                for (String attr : Arrays.asList("failures", "errors", "skipped")) {
                    String val = suite.getAttribute(attr);
                    int n = (val == null || val.isEmpty()) ? 0 : Integer.parseInt(val);
                    if (n != 0) {
                        throw new GradleException(taskPath + ": " + attr + " in " + report.getName());
                    }
                }
                for (String elem : Arrays.asList("failure", "error", "skipped")) {
                    if (suite.getElementsByTagName(elem).getLength() != 0) {
                        throw new GradleException(taskPath + ": " + elem + " result in " + report.getName());
                    }
                }
            }
        } catch (GradleException e) {
            throw e;
        } catch (Exception e) {
            throw new GradleException("Failed to parse JUnit XML reports for " + taskPath, e);
        }
        if (tests <= 0) {
            throw new GradleException("Required runtime ran no tests: " + taskPath);
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

    public static void patchWasmWasiTestFfiImports(
            File driverDir, File wasmNodeModulesDir, File workspaceRootDir) {
        File[] candidates = driverDir.listFiles(file -> file.getName().endsWith("-test.mjs"));
        if (candidates == null || candidates.length != 1) {
            return;
        }
        File driver = candidates[0];
        String nodeModulesEscaped = wasmNodeModulesDir.getAbsolutePath()
                .replace("\\", "\\\\")
                .replace("'", "\\'");
        String workspaceEscaped = workspaceRootDir.getAbsolutePath()
                .replace("\\", "\\\\")
                .replace("'", "\\'");
        String target = "const wasmInstance = new WebAssembly.Instance(wasmModule, wasi.getImportObject());";
        String replacement = "const __kmBindWasmWasiImports = async (mod, baseImports) => {\n"
                + "    const { createRequire } = await import('node:module');\n"
                + "    const path = await import('node:path');\n"
                + "    const wasmNodeModules = '" + nodeModulesEscaped + "';\n"
                + "    const workspaceRoot = '" + workspaceEscaped + "';\n"
                + "    const req = createRequire(path.join(wasmNodeModules, '_km_resolver.cjs'));\n"
                + "    const addons = new Map();\n"
                + "    const loadAddon = (modName) => {\n"
                + "        if (addons.has(modName)) return addons.get(modName);\n"
                + "        let addon = null;\n"
                + "        try {\n"
                + "            addon = req(`@kotlinmania/${modName}-native-bindings`);\n"
                + "        } catch (_) {\n"
                + "            const candidates = [\n"
                + "                path.join(workspaceRoot, `${modName}-kotlin`, 'src', 'js', `node-${modName}`, 'dist', 'index.js'),\n"
                + "                path.join(workspaceRoot, modName === 'windowssys' ? 'windows-sys-kotlin' : `${modName}-kotlin`, 'src', 'js', `node-${modName}`, 'dist', 'index.js'),\n"
                + "            ];\n"
                + "            for (const cand of candidates) {\n"
                + "                if (fs.existsSync(cand)) {\n"
                + "                    try { addon = req(cand); break; } catch (_) {}\n"
                + "                }\n"
                + "            }\n"
                + "        }\n"
                + "        addons.set(modName, addon);\n"
                + "        return addon;\n"
                + "    };\n"
                + "    for (const imp of WebAssembly.Module.imports(mod)) {\n"
                + "        if (imp.kind !== 'function' || (baseImports[imp.module] && baseImports[imp.module][imp.name])) continue;\n"
                + "        if (!baseImports[imp.module]) baseImports[imp.module] = {};\n"
                + "        const addon = loadAddon(imp.module);\n"
                + "        const fn = addon ? addon[imp.name] : null;\n"
                + "        if (typeof fn === 'function') {\n"
                + "            baseImports[imp.module][imp.name] = (...args) => {\n"
                + "                const res = fn(...args.map(a => typeof a === 'bigint' ? a.toString() : a));\n"
                + "                return typeof res === 'string' ? BigInt(res) : res;\n"
                + "            };\n"
                + "        } else {\n"
                + "            baseImports[imp.module][imp.name] = () => {\n"
                + "                throw new Error(`Unresolved WasmImport ${imp.module}.${imp.name}`);\n"
                + "            };\n"
                + "        }\n"
                + "    }\n"
                + "    return baseImports;\n"
                + "};\n"
                + "const wasmInstance = new WebAssembly.Instance(wasmModule, await __kmBindWasmWasiImports(wasmModule, wasi.getImportObject()));";
        try {
            String text = Files.readString(driver.toPath(), StandardCharsets.UTF_8);
            if (!text.contains("__kmBindWasmWasiImports") && text.contains(target)) {
                String patched = text.replace(target, replacement);
                Files.writeString(driver.toPath(), patched, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new GradleException("Failed to patch wasmWasi FFI imports in " + driver, e);
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
