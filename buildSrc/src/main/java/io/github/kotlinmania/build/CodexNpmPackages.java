package io.github.kotlinmania.build;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipFile;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

/** Local staging and packaging of Codex CLI, proxy and SDK npm distributions. */
public final class CodexNpmPackages {
    static final Map<String, List<String>> COMPONENTS = Map.of("codex", List.of("codex", "rg"), "codex-responses-api-proxy", List.of("codex-responses-api-proxy"), "codex-sdk", List.of("codex"));
    static final Map<String, String> DESTINATIONS = Map.of("codex", "codex", "codex-responses-api-proxy", "codex-responses-api-proxy", "rg", "path");
    static final List<String> TARGETS = List.of("x86_64-unknown-linux-musl", "aarch64-unknown-linux-musl", "x86_64-apple-darwin", "aarch64-apple-darwin", "x86_64-pc-windows-msvc", "aarch64-pc-windows-msvc");
    static final List<String> PLATFORMS = List.of("linux-x86_64", "linux-aarch64", "macos-x86_64", "macos-aarch64", "windows-x86_64", "windows-aarch64");
    static final String DEFAULT_WORKFLOW = "https://github.com/openai/codex/actions/runs/17952349351";
    @FunctionalInterface interface Commands { String run(List<String> command, Path directory) throws Exception; }
    final Path root, scratch;
    private final Commands commands;
    CodexNpmPackages(Path root, Commands commands) throws IOException {
        this.root = root.toRealPath(); this.scratch = this.root.resolve("build/tmp/npm-packages"); checkInside(this.scratch); Files.createDirectories(this.scratch); this.commands = commands == null ? this::process : commands;
    }
    private String process(List<String> command, Path directory) throws Exception {
        System.out.println("+ " + String.join(" ", command)); Path invocation = Files.createTempDirectory(scratch, "command-");
        try {
            ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile()).redirectOutput(invocation.resolve("stdout").toFile()).redirectError(ProcessBuilder.Redirect.INHERIT);
            for (String key : List.of("TMPDIR", "TMP", "TEMP")) builder.environment().put(key, scratch.toString());
            builder.environment().put("npm_config_cache", scratch.resolve("npm-cache").toString());
            Process process = builder.start(); boolean finished = false;
            try { finished = process.waitFor(3600, TimeUnit.SECONDS); }
            finally { if (!finished) { try { process.descendants().forEach(ProcessHandle::destroyForcibly); } catch (RuntimeException unavailable) { System.err.println("Cannot enumerate subprocess descendants: " + unavailable.getMessage()); } process.destroyForcibly(); process.waitFor(); } }
            if (!finished || process.exitValue() != 0) throw new IOException("Command failed " + (!finished ? "(timeout)" : "(exit " + process.exitValue() + ")") + ": " + command);
            return Files.readString(invocation.resolve("stdout"));
        } finally { remove(invocation); }
    }
    Path path(String value) throws IOException { Path result = Path.of(value); if (!result.isAbsolute()) result = root.resolve(result); result = result.toAbsolutePath().normalize(); checkInside(result); return result; }
    private void checkInside(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize(); if (!normalized.startsWith(root)) throw new IOException("Path must remain inside the repository: " + path);
        for (Path cursor = normalized; cursor != null && cursor.startsWith(root); cursor = cursor.getParent()) if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS) && !cursor.toRealPath().startsWith(root)) throw new IOException("Path traverses a symlink outside the repository: " + path);
    }
    static void remove(Path path) throws IOException { if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return; try (var paths = Files.walk(path)) { for (Path item : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(item); } }
    private static void copy(Path source, Path dest) throws IOException {
        if (Files.isSymbolicLink(source)) throw new IOException("Refusing a staged symbolic link: " + source); Files.createDirectories(dest.getParent()); Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
    }
    private static void copyTree(Path source, Path dest) throws IOException {
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Missing source directory: " + source);
        try (var files = Files.walk(source)) { for (Path file : files.sorted().toList()) { if (Files.isSymbolicLink(file)) throw new IOException("Refusing a staged symbolic link: " + file); Path target = dest.resolve(source.relativize(file)); if (Files.isDirectory(file)) Files.createDirectories(target); else copy(file, target); } }
    }
    @SuppressWarnings("unchecked") static Map<String, Object> object(Object value) { if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException("Expected a JSON object"); return (Map<String, Object>) value; }
    @SuppressWarnings("unchecked") static List<Object> array(Object value) { if (!(value instanceof List<?>)) throw new IllegalArgumentException("Expected a JSON array"); return (List<Object>) value; }
    private static String string(Object value) { if (!(value instanceof String text) || text.isBlank()) throw new IllegalArgumentException("Expected a nonempty string"); return text; }
    static Set<String> components(List<String> packages) { Set<String> result = new LinkedHashSet<>(); for (String name : packages) { List<String> values = COMPONENTS.get(name); if (values == null) throw new IllegalArgumentException("Unknown package: " + name); result.addAll(values); } return result; }
    static void version(String version) { if (version == null || version.isBlank() || version.indexOf('/') >= 0 || version.indexOf('\\') >= 0 || version.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("A version without path separators or control characters is required"); }
    Path staging(String value) throws IOException {
        if (value == null) return Files.createTempDirectory(scratch, "codex-npm-stage-"); Path stage = path(value); if (stage.equals(root)) throw new IOException("Repository root cannot be a staging directory"); Files.createDirectories(stage);
        try (var children = Files.list(stage)) { if (children.findAny().isPresent()) throw new IOException("Staging directory is not empty: " + stage); } return stage;
    }
    void stageSources(Path stage, String version, String name) throws Exception {
        version(version); if (!COMPONENTS.containsKey(name)) throw new IllegalArgumentException("Unknown package: " + name); Path manifest;
        switch (name) {
            case "codex" -> {
                copy(root.resolve("codex-cli/bin/codex.js"), stage.resolve("bin/codex.js")); optional(root.resolve("codex-cli/bin/rg"), stage.resolve("bin/rg")); optional(root.resolve("README.md"), stage.resolve("README.md")); manifest = root.resolve("codex-cli/package.json");
            }
            case "codex-responses-api-proxy" -> {
                Path source = root.resolve("codex-rs/responses-api-proxy/npm"); copy(source.resolve("bin/codex-responses-api-proxy.js"), stage.resolve("bin/codex-responses-api-proxy.js")); optional(source.resolve("README.md"), stage.resolve("README.md")); manifest = source.resolve("package.json");
            }
            case "codex-sdk" -> {
                Path source = root.resolve("sdk/typescript"); commands.run(List.of("pnpm", "install", "--frozen-lockfile"), source); commands.run(List.of("pnpm", "run", "build"), source); copyTree(source.resolve("dist"), stage.resolve("dist")); optional(source.resolve("README.md"), stage.resolve("README.md")); optional(root.resolve("LICENSE"), stage.resolve("LICENSE")); manifest = source.resolve("package.json");
            }
            default -> throw new IllegalArgumentException("Unknown package: " + name);
        }
        Map<String, Object> json = object(Json.read(manifest)); json.put("version", version);
        if (name.equals("codex-sdk")) { if (json.get("scripts") instanceof Map<?, ?> scripts) scripts.remove("prepare"); if (json.get("files") instanceof List<?>) { List<Object> files = array(json.get("files")); if (!files.contains("vendor")) files.add("vendor"); } else json.put("files", List.of("dist", "vendor")); }
        Json.write(stage.resolve("package.json"), json);
    }
    private static void optional(Path source, Path dest) throws IOException { if (Files.exists(source)) copy(source, dest); }
    void copyNative(Path vendor, Path stage, List<String> components) throws IOException {
        checkInside(vendor); if (!Files.isDirectory(vendor)) throw new IOException("Vendor source directory not found: " + vendor); Path destination = stage.resolve("vendor");
        if (vendor.toAbsolutePath().normalize().startsWith(destination.toAbsolutePath().normalize()) || destination.toAbsolutePath().normalize().startsWith(vendor.toAbsolutePath().normalize())) throw new IOException("Vendor source overlaps destination: " + vendor);
        remove(destination); Files.createDirectories(destination); List<Path> targets; try (var stream = Files.list(vendor)) { targets = stream.sorted().toList(); }
        int copied = 0;
        for (Path target : targets) if (Files.isDirectory(target)) { if (Files.isSymbolicLink(target)) throw new IOException("Symbolic link vendor target: " + target); Path out = destination.resolve(target.getFileName()); Files.createDirectories(out);
            for (String component : new LinkedHashSet<>(components)) { String directory = DESTINATIONS.get(component); if (directory == null) throw new IllegalArgumentException("Unknown component: " + component); Path source = target.resolve(directory); if (!Files.isDirectory(source)) throw new IOException("Missing native component '" + component + "': " + source); copyTree(source, out.resolve(directory)); copied++; }
        }
        if (copied == 0 && !components.isEmpty()) throw new IOException("Vendor source contains no native component directories: " + vendor);
    }
    Path pack(Path stage, Path output) throws Exception {
        checkInside(output); Path directory = Files.createTempDirectory(scratch, "npm-pack-");
        try {
            List<Object> result = array(Json.parse(commands.run(List.of("npm", "pack", "--json", "--pack-destination", directory.toString()), stage))); if (result.isEmpty()) throw new IOException("npm pack did not produce an output tarball"); Map<String,Object> first = object(result.getFirst()); String filename = string(first.getOrDefault("filename", first.get("name")));
            if (!Path.of(filename).getFileName().toString().equals(filename) || filename.equals(".") || filename.equals("..")) throw new IOException("Invalid npm pack filename: " + filename); Path archive = directory.resolve(filename); if (!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Expected npm pack output not found: " + archive); Files.createDirectories(output.getParent()); Files.move(archive, output, StandardCopyOption.REPLACE_EXISTING); return output;
        } finally { remove(directory); }
    }
    Path build(String name, String version, String stageValue, String vendorValue, String packValue) throws Exception {
        version(version); if (!COMPONENTS.containsKey(name)) throw new IllegalArgumentException("Unknown package: " + name); if (vendorValue == null) throw new IllegalArgumentException("Native components " + COMPONENTS.get(name) + " require --vendor-src");
        Path stage = staging(stageValue); stageSources(stage, version, name); copyNative(path(vendorValue), stage, COMPONENTS.get(name)); System.out.println("Staged " + name + " version " + version + " in " + stage); if (packValue != null) System.out.println("npm pack output written to " + pack(stage, path(packValue))); return stage;
    }
    static String archiveName(String component, String target) { return component + "-" + target + (target.contains("windows") ? ".exe" : "") + ".zst"; }
    private static String member(String value) throws IOException {
        if (value == null || value.isBlank() || value.startsWith("/") || value.contains("\\") || value.indexOf('\0') >= 0) throw new IOException("Missing or unsafe archive member path: " + value);
        for (String part : value.split("/", -1)) if (part.equals("..") || part.equals(".")) throw new IOException("Unsafe archive member: " + value); return value;
    }
    void extract(Path archive, String format, String member, Path dest) throws Exception {
        checkInside(dest); Files.createDirectories(dest.getParent());
        try {
            switch (format) {
                case "zst" -> commands.run(List.of("zstd", "-f", "-d", archive.toString(), "-o", dest.toString()), root);
                case "tar.gz" -> {
                    String wanted = member(member); boolean found = false;
                    try (var input = new TarArchiveInputStream(new GZIPInputStream(Files.newInputStream(archive)))) {
                        for (var entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) if (entry.getName().equals(wanted)) { if (found || !entry.isFile() || entry.isSymbolicLink() || entry.isLink() || !input.canReadEntryData(entry)) throw new IOException("Ambiguous or non-regular archive member: " + wanted); try (var output = Files.newOutputStream(dest)) { long bytes = input.transferTo(output); if (bytes != entry.getSize()) throw new IOException("Truncated archive member: " + wanted); } found = true; }
                    }
                    if (!found) throw new IOException("Entry '" + wanted + "' not found in " + archive);
                }
                case "zip" -> {
                    String wanted = member(member); try (ZipFile zip = new ZipFile(archive.toFile())) { var selected = zip.stream().filter(entry -> entry.getName().equals(wanted)).toList(); if (selected.size() != 1 || selected.getFirst().isDirectory()) throw new IOException("Missing or ambiguous archive member: " + wanted); var entry = selected.getFirst(); CRC32 crc = new CRC32(); long count = 0;
                        try (InputStream input = zip.getInputStream(entry); var output = Files.newOutputStream(dest)) { byte[] buffer = new byte[65536]; for (int size; (size = input.read(buffer)) >= 0;) { output.write(buffer, 0, size); crc.update(buffer, 0, size); count += size; } }
                        if (count != entry.getSize() || crc.getValue() != entry.getCrc()) throw new IOException("Corrupt archive member: " + wanted);
                    }
                }
                default -> throw new IOException("Unsupported archive format: " + format);
            }
            if (!Files.isRegularFile(dest, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Extraction did not produce a regular binary: " + dest);
        } catch (Exception error) { Files.deleteIfExists(dest); throw error; }
    }
    private static void executable(Path path) throws IOException { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x")); }
    void installBinaries(Path artifacts, Path vendor, List<String> targets, Set<String> components) throws Exception {
        for (String component : components) if (!component.equals("rg")) { if (!DESTINATIONS.containsKey(component)) throw new IllegalArgumentException("Unknown component: " + component);
            for (String target : targets) { if (!TARGETS.contains(target)) throw new IllegalArgumentException("Unsupported target: " + target); Path archive = artifacts.resolve(target).resolve(archiveName(component, target)); if (!Files.isRegularFile(archive)) throw new IOException("Expected artifact not found: " + archive); Path dest = vendor.resolve(target).resolve(component).resolve(component + (target.contains("windows") ? ".exe" : "")); extract(archive, "zst", null, dest); if (!target.contains("windows")) executable(dest); }
        }
    }
    static Map<String,Object> manifest(Path path) throws IOException {
        String text = Files.readString(path); if (text.startsWith("#!")) { int newline = text.indexOf('\n'); if (newline < 0) throw new IOException("DotSlash manifest has no JSON body: " + path); text = text.substring(newline + 1); } return object(Json.parse(text));
    }
    private void download(String url, Path output) throws IOException {
        URI uri = URI.create(url); if (!Set.of("https", "http", "file").contains(uri.getScheme())) throw new IOException("Unsupported download URI scheme: " + uri.getScheme()); if (uri.getScheme().equals("file")) checkInside(Path.of(uri));
        var connection = uri.toURL().openConnection(); connection.setConnectTimeout(30000); connection.setReadTimeout(120000); try (InputStream input = connection.getInputStream()) { Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING); }
    }
    List<Path> fetchRg(Path vendor, List<String> targets, Path manifestPath) throws Exception {
        Map<String,Object> platforms = object(manifest(manifestPath).get("platforms")); List<Path> result = new ArrayList<>();
        for (String target : targets) { int index = TARGETS.indexOf(target); if (index < 0) throw new IllegalArgumentException("Unsupported ripgrep target: " + target); String platform = PLATFORMS.get(index); Map<String,Object> data = object(platforms.get(platform)); List<Object> providers = array(data.get("providers")); if (providers.isEmpty()) throw new IOException("No providers for " + platform); String url = string(object(providers.getFirst()).get("url")); Path directory = Files.createTempDirectory(scratch, "ripgrep-");
            Path dest = vendor.resolve(target).resolve("path").resolve(platform.startsWith("windows") ? "rg.exe" : "rg");
            try { Path archive = directory.resolve("download"); download(url, archive); extract(archive, data.getOrDefault("format", "zst").toString(), (String) data.get("path"), dest); }
            finally { remove(directory); }
            if (!platform.startsWith("windows")) executable(dest); result.add(dest);
        } return result;
    }
    void install(Path destination, String workflowUrl, Set<String> components) throws Exception {
        checkInside(destination); if (components.isEmpty()) components = Set.of("codex", "rg"); for (String component : components) if (!DESTINATIONS.containsKey(component)) throw new IllegalArgumentException("Unknown component: " + component);
        String workflow = workflowUrl == null || workflowUrl.isBlank() ? DEFAULT_WORKFLOW : workflowUrl.strip(); String trimmed = workflow.replaceAll("/+$", ""), id = trimmed.substring(trimmed.lastIndexOf('/') + 1); if (!id.matches("[0-9]+")) throw new IllegalArgumentException("Workflow URL must end with its numeric run ID"); Path vendor = destination.resolve("vendor"); Files.createDirectories(vendor); Path artifacts = Files.createTempDirectory(scratch, "native-artifacts-");
        try { commands.run(List.of("gh", "run", "download", "--dir", artifacts.toString(), "--repo", "openai/codex", id), root); installBinaries(artifacts, vendor, TARGETS, components); } finally { remove(artifacts); }
        if (components.contains("rg")) fetchRg(vendor, TARGETS, root.resolve("codex-cli/bin/rg")); System.out.println("Installed native dependencies into " + vendor);
    }
    void stage(List<String> packages, String version, String workflowUrl, Path output, boolean keep) throws Exception {
        version(version); Set<String> components = components(packages); if (packages.isEmpty()) throw new IllegalArgumentException("At least one --package is required"); checkInside(output); Files.createDirectories(output); String workflow = workflowUrl;
        if (workflow == null || workflow.isBlank()) { Object result = Json.parse(commands.run(List.of("gh", "run", "list", "--branch", "rust-v" + version, "--json", "workflowName,url,headSha", "--workflow", ".github/workflows/rust-release.yml", "--jq", "first(.[])"), root)); if (result == null) throw new IOException("Unable to find rust-release workflow for " + version); Map<String,Object> metadata = object(result); workflow = string(metadata.get("url")); if (metadata.get("headSha") != null) System.out.println("Native artifacts were built from " + metadata.get("headSha") + "; source checkout is unchanged"); }
        Path nativeRoot = Files.createTempDirectory(scratch, "npm-native-"); List<Path> stages = new ArrayList<>();
        try { install(nativeRoot, workflow, components); for (String name : packages) { Path stage = Files.createTempDirectory(scratch, "npm-stage-" + name + "-"); stages.add(stage); Path archive = output.resolve(name + "-npm-" + version + ".tgz"); build(name, version, stage.toString(), nativeRoot.resolve("vendor").toString(), archive.toString()); if (!keep) remove(stage); } }
        finally { if (!keep) { for (Path stage : stages) remove(stage); remove(nativeRoot); } else { System.out.println("Retained package staging under " + scratch); } }
    }
    static Map<String,List<String>> options(String... args) {
        Set<String> values = Set.of("root", "package", "version", "release-version", "staging-dir", "tmp", "pack-output", "vendor-src", "workflow-url", "output-dir", "component"); Map<String,List<String>> result = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) { String option = args[i]; if (option.equals("--keep-staging-dirs")) result.put("keep-staging-dirs", List.of("true")); else if (option.startsWith("--")) { String key = option.substring(2); if (!values.contains(key) || i + 1 >= args.length || args[i + 1].startsWith("--")) throw new IllegalArgumentException("Invalid option or missing value: " + option); if (key.equals("tmp")) key = "staging-dir"; result.computeIfAbsent(key, ignored -> new ArrayList<>()).add(args[++i]); } else result.computeIfAbsent("positional", ignored -> new ArrayList<>()).add(option); } return result;
    }
    private static String last(Map<String,List<String>> options, String key, String fallback) { var values = options.get(key); return values == null ? fallback : values.getLast(); }
    public static int run(String... args) throws Exception {
        if (args.length == 0 || List.of(args).contains("--help") || List.of(args).contains("-h")) { System.out.println("CodexNpmPackages stage|build|install [--root REPOSITORY] [--release-version VERSION] [--package codex|codex-responses-api-proxy|codex-sdk] [--workflow-url URL] [--output-dir DIR] [--keep-staging-dirs] [--version VERSION] [--staging-dir DIR|--tmp DIR] [--vendor-src DIR] [--pack-output FILE] [--component codex|codex-responses-api-proxy|rg] [install-root]"); return 0; }
        String command = args[0]; Map<String,List<String>> options = options(java.util.Arrays.copyOfRange(args, 1, args.length)); CodexNpmPackages context = new CodexNpmPackages(Path.of(last(options, "root", ".")), null); List<String> positional = options.getOrDefault("positional", List.of());
        Set<String> allowed = switch (command) {
            case "stage" -> Set.of("root", "release-version", "package", "workflow-url", "output-dir", "keep-staging-dirs");
            case "build" -> Set.of("root", "package", "version", "release-version", "staging-dir", "vendor-src", "pack-output");
            case "install" -> Set.of("root", "workflow-url", "component", "positional");
            default -> throw new IllegalArgumentException("Unknown npm command: " + command);
        };
        for (String key : options.keySet()) if (!allowed.contains(key)) throw new IllegalArgumentException("Option --" + key + " is not valid for " + command);
        if (!command.equals("install") && !positional.isEmpty() || positional.size() > 1) throw new IllegalArgumentException("Unexpected positional arguments: " + positional);
        switch (command) {
            case "stage" -> context.stage(options.getOrDefault("package", List.of()), last(options, "release-version", null), last(options, "workflow-url", null), context.path(last(options, "output-dir", "dist/npm")), options.containsKey("keep-staging-dirs"));
            case "build" -> { String version = last(options, "version", null), release = last(options, "release-version", null); if (version != null && release != null && !version.equals(release)) throw new IllegalArgumentException("--version and --release-version must match"); context.build(last(options, "package", "codex"), release == null ? version : release, last(options, "staging-dir", null), last(options, "vendor-src", null), last(options, "pack-output", null)); }
            case "install" -> context.install(context.path(positional.isEmpty() ? "codex-cli" : positional.getFirst()), last(options, "workflow-url", null), new LinkedHashSet<>(options.getOrDefault("component", List.of("codex", "rg"))));
            default -> throw new IllegalArgumentException("Unknown npm command: " + command);
        } return 0;
    }
    public static void main(String[] args) throws Exception { System.exit(run(args)); }
}
