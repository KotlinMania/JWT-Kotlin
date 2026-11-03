package io.github.kotlinmania.build;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

/** Prepares a separate, fully inventoried Native SDK using immutable published inputs. */
public final class SwiftToolingPreparer {
    private SwiftToolingPreparer() {}

    private static final String HEADER = "konan/swift_export/kotlin_runtime/KotlinBase.h";
    private static final String NATIVE_ARCHIVE = "kotlin-native-prebuilt-2.4.20-macos-aarch64.tar.gz";
    private static final Pattern HUNK = Pattern.compile("@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@[^\\n]*(?:\\n)?");

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) throw new IllegalArgumentException("Expected JSON object");
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static List<?> list(Object value) {
        if (!(value instanceof List<?> source)) throw new IllegalArgumentException("Expected JSON array");
        return source;
    }

    private static String string(Object value) {
        if (!(value instanceof String text)) throw new IllegalArgumentException("Expected string: " + value);
        return text;
    }

    private static Map<String, Object> object(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }

    private static String sha(InputStream input) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = new byte[8192];
            for (int length; (length = input.read(bytes)) >= 0;) if (length != 0) digest.update(bytes, 0, length);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public static String sha(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) { return sha(input); }
    }

    /** Includes every regular SDK file, and rejects symlinks and special files. */
    public static Map<String, String> inventory(Path root) throws IOException {
        require(!Files.isSymbolicLink(root), "SDK root must not be a symlink");
        require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS), "SDK root must be a directory: " + root);
        Map<String, String> result = new TreeMap<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                require(!attributes.isSymbolicLink(), "SDK symlink is unsupported: " + file);
                require(attributes.isRegularFile(), "SDK special file is unsupported: " + file);
                result.put(root.relativize(file).toString().replace('\\', '/'), sha(file));
                return FileVisitResult.CONTINUE;
            }
        });
        return result;
    }

    private static Path archivePath(Path root, String name, String message) {
        require(!name.startsWith("/") && !name.contains("\\") && !name.matches("^[A-Za-z]:.*"), message);
        require(Arrays.stream(name.split("/", -1)).noneMatch(part -> part.equals("..")), message);
        Path destination = root.resolve(name).normalize();
        require(destination.startsWith(root.normalize()), message);
        return destination;
    }

    private static boolean archivedScript(String name) { return name.endsWith(".py") || name.endsWith(".pyc") || name.endsWith(".pyo"); }

    private static String helperArchiveName(Map<String, Object> lock) {
        List<String> names = map(lock.get("files")).keySet().stream().filter(name -> name.endsWith("-native-sdk-helper.zip")).toList();
        require(names.size() == 1, "Expected exactly one locked published Native SDK helper archive");
        return names.getFirst();
    }

    /** The published script remains inert archive data; only its bytes are checked. */
    static void extractHelper(Path archive, Path destination) throws IOException {
        require(!Files.exists(destination, LinkOption.NOFOLLOW_LINKS), "Helper destination must not exist");
        try (ZipFile zip = ZipFile.builder().setPath(archive).get()) {
            List<ZipArchiveEntry> entries = Collections.list(zip.getEntries());
            for (ZipArchiveEntry entry : entries) {
                archivePath(destination, entry.getName(), "Unsafe helper entry");
                require(((entry.getExternalAttributes() >>> 16) & 0170000) != 0120000, "Unsafe helper entry");
                require(zip.canReadEntryData(entry), "Unsupported helper entry");
            }
            Files.createDirectories(destination);
            for (ZipArchiveEntry entry : entries) {
                if (archivedScript(entry.getName())) continue;
                Path path = archivePath(destination, entry.getName(), "Unsafe helper entry");
                if (entry.isDirectory()) Files.createDirectories(path);
                else {
                    Files.createDirectories(path.getParent());
                    require(!Files.isSymbolicLink(path), "Unsafe helper entry");
                    try (InputStream input = zip.getInputStream(entry)) { Files.copy(input, path, StandardCopyOption.REPLACE_EXISTING); }
                }
            }
        }
    }

    private static void verifyHelper(Path root, Map<String, Object> lock) throws IOException {
        String helperName = helperArchiveName(lock);
        Path helper = root.resolve("helper"), archive = root.resolve(helperName);
        Map<String, Object> expected = map(lock.get("helper_source_sha256"));
        if (Files.isRegularFile(archive)) {
            require(sha(archive).equals(map(lock.get("files")).get(helperName)), "Published helper ZIP checksum mismatch");
            try (ZipFile zip = ZipFile.builder().setPath(archive).get()) {
                for (Map.Entry<String, Object> source : expected.entrySet()) {
                    ZipArchiveEntry entry = zip.getEntry(source.getKey());
                    require(entry != null && !entry.isDirectory(), "Published SDK helper changed: " + source.getKey());
                    try (InputStream input = zip.getInputStream(entry)) {
                        require(sha(input).equals(source.getValue()), "Published SDK helper changed: " + source.getKey());
                    }
                }
                // Bind the data used by the Java implementation to the exact published archive too.
                for (ZipArchiveEntry entry : Collections.list(zip.getEntries())) {
                    if (entry.isDirectory() || archivedScript(entry.getName())) continue;
                    Path path = archivePath(helper, entry.getName(), "Unsafe helper entry");
                    require(!Files.isSymbolicLink(path) && Files.isRegularFile(path), "Published SDK helper changed: " + entry.getName());
                    try (InputStream input = zip.getInputStream(entry)) {
                        require(sha(path).equals(sha(input)), "Published SDK helper changed: " + entry.getName());
                    }
                }
            }
        } else {
            // Existing prepared SDKs from the previous tooling remain verifiable without executing it.
            for (Map.Entry<String, Object> source : expected.entrySet()) {
                require(sha(archivePath(helper, source.getKey(), "Unsafe helper entry")).equals(source.getValue()), "Published SDK helper changed: " + source.getKey());
            }
        }
    }

    private record ArchiveAttributes(int mode, FileTime modified) {}

    static void extractSdkArchive(Path archive, Path destination) throws IOException {
        require(!Files.exists(destination, LinkOption.NOFOLLOW_LINKS), "Incomplete previous SDK preparation; use a fresh output directory");
        // Validate the entire archive before creating any output, just as the original helper did.
        try (TarArchiveInputStream tar = new TarArchiveInputStream(new GZIPInputStream(Files.newInputStream(archive)))) {
            for (TarArchiveEntry entry; (entry = tar.getNextEntry()) != null;) {
                archivePath(destination, entry.getName(), "Unsafe SDK archive entry");
                require(entry.isCheckSumOK() && (entry.isFile() || entry.isDirectory()) && !entry.isLink() && !entry.isSymbolicLink(), "Unsafe SDK archive entry");
            }
        }
        Files.createDirectories(destination);
        Map<Path, ArchiveAttributes> attributes = new LinkedHashMap<>();
        try (TarArchiveInputStream tar = new TarArchiveInputStream(new GZIPInputStream(Files.newInputStream(archive)))) {
            for (TarArchiveEntry entry; (entry = tar.getNextEntry()) != null;) {
                Path path = archivePath(destination, entry.getName(), "Unsafe SDK archive entry");
                if (entry.isDirectory()) Files.createDirectories(path);
                else {
                    Files.createDirectories(path.getParent());
                    Files.copy(tar, path, StandardCopyOption.REPLACE_EXISTING);
                }
                attributes.put(path, new ArchiveAttributes(entry.getMode(), entry.getLastModifiedTime()));
            }
        }
        for (Path path : attributes.keySet().stream().sorted(Comparator.reverseOrder()).toList()) {
            ArchiveAttributes saved = attributes.get(path);
            if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
                Set<PosixFilePermission> mode = EnumSet.noneOf(PosixFilePermission.class);
                PosixFilePermission[] permissions = {PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
                        PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
                        PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE};
                for (int i = 0; i < permissions.length; i++) if ((saved.mode() & (1 << (8 - i))) != 0) mode.add(permissions[i]);
                Files.setPosixFilePermissions(path, mode);
            }
            if (saved.modified() != null) Files.setLastModifiedTime(path, saved.modified());
        }
    }

    private static List<String> lines(String text) {
        List<String> result = new ArrayList<>(Arrays.asList(text.split("(?<=\\n)", -1)));
        if (!result.isEmpty() && result.getLast().isEmpty()) result.removeLast();
        return result;
    }

    /** Applies exact unified-diff hunks in memory; no offsets, fuzz, process or temporary patch file. */
    static byte[] applyPatch(byte[] source, byte[] patch) {
        List<String> original = lines(new String(source, StandardCharsets.ISO_8859_1));
        List<String> diff = lines(new String(patch, StandardCharsets.ISO_8859_1));
        require(diff.size() >= 3 && diff.get(0).startsWith("--- ") && diff.get(1).startsWith("+++ "), "Header patch failed: unsupported unified diff");
        List<String> result = new ArrayList<>();
        int cursor = 0, hunks = 0;
        for (int i = 2; i < diff.size();) {
            Matcher header = HUNK.matcher(diff.get(i++));
            require(header.matches(), "Header patch failed: unsupported hunk header");
            int oldCount = header.group(2) == null ? 1 : Integer.parseInt(header.group(2));
            int newCount = header.group(4) == null ? 1 : Integer.parseInt(header.group(4));
            int oldPosition = Integer.parseInt(header.group(1)) - (oldCount == 0 ? 0 : 1);
            int newPosition = Integer.parseInt(header.group(3)) - (newCount == 0 ? 0 : 1);
            require(oldPosition >= cursor && oldPosition <= original.size(), "Header patch failed: hunk location mismatch");
            result.addAll(original.subList(cursor, oldPosition));
            require(result.size() == newPosition, "Header patch failed: output hunk location mismatch");
            List<String> before = new ArrayList<>(), after = new ArrayList<>();
            while (i < diff.size() && !diff.get(i).startsWith("@@ ")) {
                String line = diff.get(i++);
                require(!line.isEmpty() && (line.charAt(0) == ' ' || line.charAt(0) == '-' || line.charAt(0) == '+'), "Header patch failed: unsupported hunk content");
                char kind = line.charAt(0);
                String payload = line.substring(1);
                if (i < diff.size() && diff.get(i).startsWith("\\ No newline at end of file")) {
                    if (payload.endsWith("\n")) payload = payload.substring(0, payload.length() - 1);
                    i++;
                }
                if (kind != '+') before.add(payload);
                if (kind != '-') after.add(payload);
            }
            require(before.size() == oldCount && after.size() == newCount, "Header patch failed: hunk line count mismatch");
            require(oldPosition + before.size() <= original.size() && original.subList(oldPosition, oldPosition + before.size()).equals(before), "Header patch failed: exact source context mismatch");
            result.addAll(after);
            cursor = oldPosition + before.size();
            hunks++;
        }
        require(hunks > 0, "Header patch failed: no hunks");
        result.addAll(original.subList(cursor, original.size()));
        return String.join("", result).getBytes(StandardCharsets.ISO_8859_1);
    }

    private static void copySdk(Path source, Path destination) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                Files.createDirectory(destination.resolve(source.relativize(directory)));
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                require(attributes.isRegularFile() && !attributes.isSymbolicLink(), "SDK special file is unsupported: " + file);
                Files.copy(file, destination.resolve(source.relativize(file)), StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Path target = destination.resolve(source.relativize(directory));
                if (Files.getFileStore(directory).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(target, Files.getPosixFilePermissions(directory));
                Files.setLastModifiedTime(target, Files.getLastModifiedTime(directory));
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public static Map<String, Object> prepareSdk(Path source, Path destination, Map<String, Object> lock, Path patch) throws IOException {
        require(!Files.isSymbolicLink(source), "SDK root must not be a symlink");
        source = source.toRealPath();
        destination = destination.toAbsolutePath();
        require(!Files.exists(destination, LinkOption.NOFOLLOW_LINKS), "Destination must not exist");
        destination = destination.getParent().toRealPath().resolve(destination.getFileName());
        require(!source.startsWith(destination) && !destination.startsWith(source), "Source and destination SDK trees must be separate");
        Map<String, Object> nativeHeader = map(lock.get("native_header"));
        String header = string(nativeHeader.get("relative_path"));
        Path headerPath = archivePath(destination, header, "Unsafe Native header path");
        Map<String, String> before = inventory(source);
        require(nativeHeader.get("baseline_sha256").equals(before.get(header)), "Unexpected Native header baseline");
        for (Map.Entry<String, Object> entry : map(lock.get("native_identity")).entrySet()) {
            require(entry.getValue().equals(before.get(entry.getKey())), "Wrong Kotlin/Native compiler input: " + entry.getKey());
        }
        Map<String, Object> patchEntry = list(lock.get("sources")).stream().map(SwiftToolingPreparer::map).filter(row -> "header".equals(row.get("kind"))).findFirst().orElseThrow(() -> new IllegalArgumentException("Missing Native header patch lock"));
        require(sha(patch).equals(patchEntry.get("patch_sha256")), "Header patch checksum mismatch");
        copySdk(source, destination);
        require(inventory(destination).equals(before), "SDK copy differs before patching");
        Files.write(headerPath, applyPatch(Files.readAllBytes(headerPath), Files.readAllBytes(patch)));
        Map<String, String> after = inventory(destination);
        require(before.keySet().equals(after.keySet()), "SDK patch added or removed files");
        List<String> changed = before.keySet().stream().filter(name -> !before.get(name).equals(after.get(name))).toList();
        require(changed.equals(List.of(header)) && nativeHeader.get("candidate_sha256").equals(after.get(header)), "SDK changes exceed the exact header repair");
        require(inventory(source).equals(before), "Source SDK changed during preparation");
        return object("kotlin_version", "2.4.20", "source_sdk", source.toString(), "prepared_sdk", destination.toString(), "source_files", before,
                "changed_files", changed, "candidate_header_sha256", after.get(header), "unchanged_file_count", before.size() - 1,
                "unchanged_native_binaries", true, "installed_source_modified", false);
    }

    private static Map<String, String> workerInputs(Object worker) throws IOException {
        Map<String, String> actual = new TreeMap<>();
        for (Object value : list(worker)) {
            Map<String, Object> row = map(value);
            String coordinate = string(row.get("coordinate"));
            require(!actual.containsKey(coordinate), "Duplicate exporter worker component");
            actual.put(coordinate, sha(Path.of(string(row.get("path")))));
        }
        return actual;
    }

    public static void verify(Path root, Path lockPath) throws IOException { verify(root, map(Json.read(lockPath))); }

    private static void verify(Path root, Map<String, Object> lock) throws IOException {
        Map<String, Object> receipt = map(Json.read(root.resolve("sdk-audit.json")));
        require(List.of(HEADER).equals(receipt.get("changed_files")) && Boolean.TRUE.equals(receipt.get("unchanged_native_binaries"))
                && Boolean.FALSE.equals(receipt.get("installed_source_modified")), "SDK helper scope mismatch");
        Map<String, Object> expected = map(receipt.get("source_files"));
        expected.put(HEADER, lock.get("native_header_candidate_sha256"));
        verifyHelper(root, lock);
        require(inventory(root.resolve("native-sdk")).equals(expected), "Prepared SDK changed");
        require(workerInputs(Json.read(root.resolve("resolved-worker.json"))).equals(lock.get("worker_components")), "Published exporter worker inputs changed");
        System.out.println("Verified complete prepared SDK inventory: " + expected.size() + " files");
    }

    static void download(String url, Path path, String expected) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            Path part = path.resolveSibling(path.getFileName() + ".partial");
            require(!Files.isSymbolicLink(part), "Unsafe partial download path");
            URLConnection connection = URI.create(url).toURL().openConnection();
            connection.setConnectTimeout(120_000);
            connection.setReadTimeout(120_000);
            try (InputStream input = connection.getInputStream()) { Files.copy(input, part, StandardCopyOption.REPLACE_EXISTING); }
            require(sha(part).equals(expected), "Download checksum mismatch: " + url);
            Files.move(part, path);
        }
        require(!Files.isSymbolicLink(path) && sha(path).equals(expected), "Cached input checksum mismatch: " + path);
    }

    public static void prepare(Path root, Path helperArchive, Path workerPath, Path lockPath, Path seed) throws IOException {
        Files.createDirectories(root);
        root = root.toRealPath();
        Map<String, Object> lock = map(Json.read(lockPath));
        Object worker = Json.read(workerPath);
        Map<String, String> actual = workerInputs(worker);
        require(actual.equals(lock.get("worker_components")), "Published exporter worker closure differs from verified release");
        Json.write(root.resolve("verified-worker.json"), actual);
        Json.write(root.resolve("resolved-worker.json"), worker);
        String helperName = helperArchiveName(lock);
        require(sha(helperArchive).equals(map(lock.get("files")).get(helperName)), "Published helper ZIP checksum mismatch");
        Path cachedHelper = root.resolve(helperName);
        if (!Files.exists(cachedHelper, LinkOption.NOFOLLOW_LINKS)) Files.copy(helperArchive, cachedHelper);
        require(!Files.isSymbolicLink(cachedHelper) && sha(cachedHelper).equals(map(lock.get("files")).get(helperName)), "Published helper ZIP checksum mismatch");
        Path helper = root.resolve("helper");
        if (!Files.exists(helper, LinkOption.NOFOLLOW_LINKS)) extractHelper(cachedHelper, helper);
        verifyHelper(root, lock);
        if (Files.exists(root.resolve("native-sdk"), LinkOption.NOFOLLOW_LINKS)) { verify(root, lock); return; }
        Path archive = root.resolve(NATIVE_ARCHIVE);
        if (seed != null && !Files.exists(archive, LinkOption.NOFOLLOW_LINKS)) {
            require(sha(seed).equals(lock.get("native_sdk_archive_sha256")), "Official SDK seed checksum mismatch");
            Files.copy(seed, archive);
        }
        download(string(lock.get("native_sdk_archive_url")), archive, string(lock.get("native_sdk_archive_sha256")));
        Path source = root.resolve("official-sdk");
        extractSdkArchive(archive, source);
        List<Path> children;
        try (var paths = Files.list(source)) { children = paths.toList(); }
        require(children.size() == 1 && Files.isDirectory(children.getFirst()), "Unexpected official SDK archive layout");
        Map<String, Object> helperLock = map(Json.read(helper.resolve("inputs.lock.json")));
        Map<String, Object> patch = list(helperLock.get("sources")).stream().map(SwiftToolingPreparer::map).filter(row -> "header".equals(row.get("kind"))).findFirst().orElseThrow(() -> new IllegalArgumentException("Missing Native header patch lock"));
        Map<String, Object> receipt = prepareSdk(children.getFirst(), root.resolve("native-sdk"), helperLock, archivePath(helper, string(patch.get("patch")), "Unsafe Native header patch path"));
        Json.write(root.resolve("sdk-audit.json"), receipt);
        verify(root, lock);
    }

    public static int run(String... args) throws IOException {
        Path output = null, helper = null, worker = null, lock = Path.of("verification/swift-tooling-lock.json");
        boolean verifyOnly = false;
        for (int i = 0; i < args.length; i++) {
            String option = args[i];
            if (option.equals("--verify-only")) { verifyOnly = true; continue; }
            if (i + 1 >= args.length) throw new IllegalArgumentException("Missing value for " + option);
            String value = args[++i];
            switch (option) {
                case "--output" -> output = Path.of(value).toAbsolutePath().normalize();
                case "--helper" -> helper = Path.of(value);
                case "--worker" -> worker = Path.of(value);
                case "--lock" -> lock = Path.of(value);
                default -> throw new IllegalArgumentException("Unknown argument: " + option);
            }
        }
        require(output != null, "--output is required");
        Files.createDirectories(output);
        if (verifyOnly) verify(output, lock);
        else {
            require(helper != null && worker != null, "Resolved Maven helper and worker manifest required");
            String seed = System.getenv("KOTLIN_NATIVE_ARCHIVE");
            prepare(output, helper, worker, lock, seed == null || seed.isEmpty() ? null : Path.of(seed));
        }
        return 0;
    }

    public static void main(String[] args) throws IOException { System.exit(run(args)); }
}
