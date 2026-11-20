package io.github.kotlinmania.build;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/// Shards oversized Swift Export generated Kotlin, C header, and Swift bridge files into bounded
/// compilation units (`<= 1 MiB` by default).
///
/// Large FFI crates such as `windows-sys-kotlin` generate single-file Swift Export outputs over
/// `66 MiB` (`.h`), `159 MiB` (`.swift`), and `260 MiB` (`.kt`). When Clang diagnostics fire inside
/// `swift-frontend -emit-pcm`, Swift's `DiagnosticBridge` reparses the entire C header buffer per
/// diagnostic, turning sub-minute builds into multi-hour hangs. Splitting generated text outputs at
/// clean declaration boundaries keeps every file under the byte cap without changing exported API
/// declarations or signatures.
public final class SwiftExportSharder {
    /// Default per-shard ceiling (`1 MiB`).
    public static final long DEFAULT_MAX_SHARD_BYTES = 1_048_576L;

    private static final String OBJC_BIND_PREFIX = "@file:kotlin.native.internal.objc.BindClassToObjCName(";
    private static final Pattern SHARDED_NAME_PATTERN =
            Pattern.compile(".*_(?:(?:objc|bridge)_)?\\d{4}\\.(?:kt|h|swift)$");
    private static final Pattern SWIFT_IMPORT_PATTERN =
            Pattern.compile("^(?:(?:public|package|internal|fileprivate|private)\\s+)?import\\s+.+$"
                    + "|^@_[A-Za-z0-9_]+(?:\\([^)]*\\))?\\s+(?:(?:public|package|internal|fileprivate|private)\\s+)?import\\s+.+$");
    private static final Pattern SPLITTABLE_SWIFT_NAMESPACE_EXT =
            Pattern.compile("^extension\\s+ExportedKotlinPackages(?:\\.[A-Za-z0-9_`]+)+\\s*\\{\\s*$");
    private static final Pattern KOTLIN_IMPORT_ALIAS_PATTERN =
            Pattern.compile("^import\\s+\\S+\\s+as\\s+([A-Za-z0-9_`]+)\\s*$");
    private static final Pattern KOTLIN_IMPORT_SIMPLE_PATTERN =
            Pattern.compile("^import\\s+(?:[A-Za-z0-9_`]+\\.)*([A-Za-z0-9_`]+)\\s*$");

    private SwiftExportSharder() {}

    private record NamedImport(String line, String identifier, int utf8Bytes) {}

    private static final class LexState {
        int blockCommentDepth;
        boolean inTripleQuote;
        int braceDepth;
        int parenDepth;

        void scanLine(String line) {
            int n = line.length();
            int i = 0;
            while (i < n) {
                if (inTripleQuote) {
                    int end = line.indexOf("\"\"\"", i);
                    if (end < 0) {
                        return;
                    }
                    inTripleQuote = false;
                    i = end + 3;
                    continue;
                }
                if (blockCommentDepth > 0) {
                    if (line.startsWith("/*", i)) {
                        blockCommentDepth++;
                        i += 2;
                    } else if (line.startsWith("*/", i)) {
                        blockCommentDepth--;
                        i += 2;
                    } else {
                        i++;
                    }
                    continue;
                }
                char ch = line.charAt(i);
                if (ch == '/' && i + 1 < n) {
                    char next = line.charAt(i + 1);
                    if (next == '/') {
                        return;
                    }
                    if (next == '*') {
                        blockCommentDepth++;
                        i += 2;
                        continue;
                    }
                }
                if (line.startsWith("\"\"\"", i)) {
                    inTripleQuote = true;
                    i += 3;
                    continue;
                }
                if (ch == '"' || ch == '\'' || ch == '`') {
                    char quote = ch;
                    i++;
                    while (i < n) {
                        char c = line.charAt(i);
                        if (c == '\\' && quote != '`') {
                            i += 2;
                            continue;
                        }
                        i++;
                        if (c == quote) {
                            break;
                        }
                    }
                    continue;
                }
                if (ch == '{') {
                    braceDepth++;
                } else if (ch == '}') {
                    braceDepth = Math.max(0, braceDepth - 1);
                } else if (ch == '(') {
                    parenDepth++;
                } else if (ch == ')') {
                    parenDepth = Math.max(0, parenDepth - 1);
                }
                i++;
            }
        }
    }

    /// Shards oversized generated outputs under a `build/SwiftExport` root and updates any module
    /// descriptor JSON files (`modules/*.json`) whose `.h` or `.swift` files were replaced by shard
    /// directories.
    ///
    /// @param swiftExportRoot the `build/SwiftExport` directory
    /// @param maxBytes maximum UTF-8 byte size per generated shard
    /// @return the number of oversized source files sharded
    /// @throws IOException if reading, sharding, or updating descriptors fails
    public static int shardSwiftExportRoot(Path swiftExportRoot, long maxBytes) throws IOException {
        if (maxBytes <= 0L) {
            throw new IllegalArgumentException("maxBytes must be positive: " + maxBytes);
        }
        if (swiftExportRoot == null || !Files.isDirectory(swiftExportRoot)) {
            return 0;
        }
        int shardedCount = 0;
        List<Path> moduleJsons = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(swiftExportRoot)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".json")
                            && p.getParent() != null
                            && "modules".equals(p.getParent().getFileName().toString()))
                    .sorted()
                    .forEach(moduleJsons::add);
        }

        for (Path jsonFile : moduleJsons) {
            Object parsed = Json.read(jsonFile);
            if (!(parsed instanceof Map<?, ?> rootRaw)) {
                continue;
            }
            Map<String, Object> root = copyStringKeyedMap(rootRaw);
            Object modulesObj = root.get("modules");
            if (!(modulesObj instanceof List<?> modulesRaw)) {
                continue;
            }
            boolean modified = false;
            List<Object> updatedModules = new ArrayList<>(modulesRaw.size());
            for (Object item : modulesRaw) {
                if (!(item instanceof Map<?, ?> modRaw)) {
                    updatedModules.add(item);
                    continue;
                }
                Map<String, Object> mod = copyStringKeyedMap(modRaw);
                Object filesObj = mod.get("files");
                if (filesObj instanceof Map<?, ?> filesRaw) {
                    Map<String, Object> files = copyStringKeyedMap(filesRaw);
                    Object ktObj = files.get("kotlinBridges");
                    if (ktObj instanceof String ktStr) {
                        Path ktPath = Path.of(ktStr);
                        if (isOversizedUnsharded(ktPath, maxBytes)) {
                            shardKotlinFile(ktPath, ktPath.getParent(), maxBytes);
                            shardedCount++;
                        }
                    }
                    Object headerObj = files.get("cHeaderBridges");
                    if (headerObj instanceof String headerStr) {
                        Path headerPath = Path.of(headerStr);
                        if (isOversizedUnsharded(headerPath, maxBytes)) {
                            Path shardDir = headerPath.getParent().resolve(stripExtension(headerPath) + "_headers");
                            recreateDirectory(shardDir);
                            shardHeaderFile(headerPath, shardDir, maxBytes);
                            files.put("cHeaderBridges", shardDir.toAbsolutePath().normalize().toString());
                            modified = true;
                            shardedCount++;
                        }
                    }
                    Object swiftObj = files.get("swiftApi");
                    if (swiftObj instanceof String swiftStr) {
                        Path swiftPath = Path.of(swiftStr);
                        if (isOversizedUnsharded(swiftPath, maxBytes)) {
                            Path shardDir = swiftPath.getParent().resolve(stripExtension(swiftPath) + "_swift");
                            recreateDirectory(shardDir);
                            shardSwiftFile(swiftPath, shardDir, maxBytes);
                            files.put("swiftApi", shardDir.toAbsolutePath().normalize().toString());
                            modified = true;
                            shardedCount++;
                        }
                    }
                    mod.put("files", files);
                } else {
                    Object swiftObj = mod.get("swiftApi");
                    if (swiftObj instanceof String swiftStr) {
                        Path swiftPath = Path.of(swiftStr);
                        if (isOversizedUnsharded(swiftPath, maxBytes)) {
                            Path shardDir = swiftPath.getParent().resolve(stripExtension(swiftPath) + "_swift");
                            recreateDirectory(shardDir);
                            shardSwiftFile(swiftPath, shardDir, maxBytes);
                            mod.put("swiftApi", shardDir.toAbsolutePath().normalize().toString());
                            modified = true;
                            shardedCount++;
                        }
                    }
                }
                updatedModules.add(mod);
            }
            if (modified) {
                root.put("modules", updatedModules);
                Json.write(jsonFile, root);
            }
        }

        // Also shard any remaining oversized .kt/.h/.swift files not referenced by modules/*.json.
        shardedCount += shardDirectoryInPlace(swiftExportRoot, maxBytes);
        return shardedCount;
    }

    /// Shards any oversized `.kt`, `.h`, or `.swift` files in-place under `rootDir`.
    ///
    /// @param rootDir root directory to scan (such as `build/SPMPackage`)
    /// @param maxBytes maximum UTF-8 byte size per generated shard
    /// @return the number of oversized source files sharded
    /// @throws IOException if scanning or sharding fails
    public static int shardDirectoryInPlace(Path rootDir, long maxBytes) throws IOException {
        if (maxBytes <= 0L) {
            throw new IllegalArgumentException("maxBytes must be positive: " + maxBytes);
        }
        if (rootDir == null || !Files.isDirectory(rootDir)) {
            return 0;
        }
        List<Path> candidates = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(rootDir)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> isOversizedUnsharded(p, maxBytes))
                    .sorted()
                    .forEach(candidates::add);
        }
        int sharded = 0;
        for (Path file : candidates) {
            String name = file.getFileName().toString();
            if (name.endsWith(".kt")) {
                shardKotlinFile(file, file.getParent(), maxBytes);
                sharded++;
            } else if (name.endsWith(".h")) {
                shardHeaderFile(file, file.getParent(), maxBytes);
                sharded++;
            } else if (name.endsWith(".swift")) {
                shardSwiftFile(file, file.getParent(), maxBytes);
                sharded++;
            }
        }
        return sharded;
    }

    /// Shards a single generated Kotlin bridge file into `<= maxBytes` shards and deletes the
    /// original file.
    ///
    /// @param ktFile source `.kt` file
    /// @param outDir destination directory for generated shards
    /// @param maxBytes maximum UTF-8 byte size per shard
    /// @throws IOException if reading or writing fails
    public static void shardKotlinFile(Path ktFile, Path outDir, long maxBytes) throws IOException {
        String baseName = stripExtension(ktFile);
        deleteExistingShards(outDir, baseName, ".kt");

        List<String> sharedFileAnnotations = new ArrayList<>();
        List<String> objcBindAnnotations = new ArrayList<>();
        String packageLine = null;
        List<String> wildcardImports = new ArrayList<>();
        List<NamedImport> namedImports = new ArrayList<>();
        String firstBodyLine = null;

        try (BufferedReader reader = Files.newBufferedReader(ktFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (trimmed.startsWith(OBJC_BIND_PREFIX)) {
                    objcBindAnnotations.add(line);
                } else if (trimmed.startsWith("@file:")) {
                    sharedFileAnnotations.add(line);
                } else if (packageLine == null && trimmed.startsWith("package ")) {
                    packageLine = line;
                } else if (trimmed.startsWith("import ")) {
                    if (trimmed.endsWith(".*")) {
                        wildcardImports.add(line);
                    } else {
                        String ident = extractKotlinImportedIdent(trimmed);
                        if (ident != null) {
                            namedImports.add(new NamedImport(line, ident, utf8Len(line) + 1));
                        } else {
                            wildcardImports.add(line);
                        }
                    }
                } else {
                    firstBodyLine = line;
                    break;
                }
            }

            long fixedPreludeBytes = sumLineBytes(sharedFileAnnotations)
                    + (packageLine != null ? utf8Len(packageLine) + 2 : 0)
                    + sumLineBytes(wildcardImports)
                    + 4;
            long remainingObjcBytes = sumLineBytes(objcBindAnnotations);

            int[] objcCursor = new int[] { 0 };
            int[] shardIndex = new int[] { 0 };
            List<String> currentDecls = new ArrayList<>();
            long currentDeclBytes = 0L;
            BitSet usedImports = new BitSet(namedImports.size());
            long currentNamedImportBytes = 0L;

            if (firstBodyLine != null) {
                LexState lex = new LexState();
                List<String> unitLines = new ArrayList<>();
                boolean hasDeclStart = false;

                String cur = firstBodyLine;
                while (cur != null) {
                    String trimmed = cur.trim();
                    if (!hasDeclStart && trimmed.isEmpty() && unitLines.isEmpty()) {
                        cur = reader.readLine();
                        continue;
                    }
                    String normalizedLine = cur;
                    if (lex.braceDepth == 0 && lex.parenDepth == 0 && !lex.inTripleQuote && lex.blockCommentDepth == 0) {
                        if (trimmed.startsWith("private class ")) {
                            int idx = cur.indexOf("private class ");
                            normalizedLine = cur.substring(0, idx) + "internal class "
                                    + cur.substring(idx + "private class ".length());
                        }
                    }
                    unitLines.add(normalizedLine);
                    lex.scanLine(normalizedLine);

                    if (!trimmed.isEmpty()
                            && !trimmed.startsWith("//")
                            && !trimmed.startsWith("/*")
                            && !trimmed.startsWith("*")
                            && !trimmed.startsWith("@")) {
                        hasDeclStart = true;
                    }

                    boolean unitComplete = hasDeclStart
                            && lex.braceDepth == 0
                            && lex.parenDepth == 0
                            && !lex.inTripleQuote
                            && lex.blockCommentDepth == 0
                            && !trimmed.endsWith("=")
                            && !trimmed.endsWith(",");

                    if (unitComplete) {
                        String unitText = String.join("\n", unitLines) + "\n\n";
                        long unitBytes = utf8Len(unitText);
                        long addedImportBytes = 0L;
                        for (int idx = 0; idx < namedImports.size(); idx++) {
                            if (!usedImports.get(idx) && unitText.contains(namedImports.get(idx).identifier())) {
                                addedImportBytes += namedImports.get(idx).utf8Bytes();
                            }
                        }
                        long objcReserve = Math.min(maxBytes / 8L, remainingObjcBytes);
                        long projectedBytes = fixedPreludeBytes
                                + currentNamedImportBytes
                                + addedImportBytes
                                + objcReserve
                                + currentDeclBytes
                                + unitBytes;
                        if (!currentDecls.isEmpty() && projectedBytes > maxBytes) {
                            shardIndex[0]++;
                            long consumedObjc = writeKotlinBridgeShard(
                                    outDir.resolve(String.format("%s_bridge_%04d.kt", baseName, shardIndex[0])),
                                    sharedFileAnnotations,
                                    objcBindAnnotations,
                                    objcCursor,
                                    packageLine,
                                    wildcardImports,
                                    namedImports,
                                    usedImports,
                                    currentDecls,
                                    maxBytes);
                            remainingObjcBytes = Math.max(0L, remainingObjcBytes - consumedObjc);
                            currentDecls.clear();
                            currentDeclBytes = 0L;
                            usedImports.clear();
                            currentNamedImportBytes = 0L;
                        }
                        for (int idx = 0; idx < namedImports.size(); idx++) {
                            if (!usedImports.get(idx) && unitText.contains(namedImports.get(idx).identifier())) {
                                usedImports.set(idx);
                                currentNamedImportBytes += namedImports.get(idx).utf8Bytes();
                            }
                        }
                        currentDecls.add(unitText);
                        currentDeclBytes += unitBytes;
                        unitLines.clear();
                        hasDeclStart = false;
                    }
                    cur = reader.readLine();
                }

                if (!unitLines.isEmpty()) {
                    String unitText = String.join("\n", unitLines) + "\n";
                    for (int idx = 0; idx < namedImports.size(); idx++) {
                        if (!usedImports.get(idx) && unitText.contains(namedImports.get(idx).identifier())) {
                            usedImports.set(idx);
                        }
                    }
                    currentDecls.add(unitText);
                }
            }

            if (!currentDecls.isEmpty()) {
                shardIndex[0]++;
                writeKotlinBridgeShard(
                        outDir.resolve(String.format("%s_bridge_%04d.kt", baseName, shardIndex[0])),
                        sharedFileAnnotations,
                        objcBindAnnotations,
                        objcCursor,
                        packageLine,
                        wildcardImports,
                        namedImports,
                        usedImports,
                        currentDecls,
                        maxBytes);
            }

            int objcShardIdx = 0;
            while (objcCursor[0] < objcBindAnnotations.size()) {
                objcShardIdx++;
                Path objcShard = outDir.resolve(String.format("%s_objc_%04d.kt", baseName, objcShardIdx));
                try (BufferedWriter writer = Files.newBufferedWriter(objcShard, StandardCharsets.UTF_8)) {
                    long written = 0L;
                    for (String ann : sharedFileAnnotations) {
                        writer.write(ann);
                        writer.write('\n');
                        written += utf8Len(ann) + 1;
                    }
                    while (objcCursor[0] < objcBindAnnotations.size()) {
                        String bind = objcBindAnnotations.get(objcCursor[0]);
                        long len = utf8Len(bind) + 1;
                        if (written > sumLineBytes(sharedFileAnnotations) && written + len > maxBytes) {
                            break;
                        }
                        writer.write(bind);
                        writer.write('\n');
                        written += len;
                        objcCursor[0]++;
                    }
                    if (packageLine != null) {
                        writer.write('\n');
                        writer.write(packageLine);
                        writer.write('\n');
                    }
                }
            }
        }

        Files.deleteIfExists(ktFile);
    }

    private static long writeKotlinBridgeShard(
            Path shardPath,
            List<String> sharedFileAnnotations,
            List<String> objcBindAnnotations,
            int[] objcCursor,
            String packageLine,
            List<String> wildcardImports,
            List<NamedImport> namedImports,
            BitSet usedImports,
            List<String> declarations,
            long maxBytes) throws IOException {
        String body = String.join("", declarations);
        long bodyBytes = utf8Len(body);

        List<String> selectedImports = new ArrayList<>(wildcardImports);
        for (int idx = usedImports.nextSetBit(0); idx >= 0; idx = usedImports.nextSetBit(idx + 1)) {
            selectedImports.add(namedImports.get(idx).line());
        }

        long baseBytes = sumLineBytes(sharedFileAnnotations)
                + (packageLine != null ? utf8Len(packageLine) + 2 : 0)
                + sumLineBytes(selectedImports)
                + bodyBytes
                + 4;

        long objcBudget = Math.min(maxBytes / 4L, Math.max(0L, maxBytes - baseBytes));
        List<String> shardObjcBinds = new ArrayList<>();
        long usedObjcBytes = 0L;
        while (objcCursor[0] < objcBindAnnotations.size()) {
            String bind = objcBindAnnotations.get(objcCursor[0]);
            long len = utf8Len(bind) + 1;
            if (usedObjcBytes + len > objcBudget) {
                break;
            }
            shardObjcBinds.add(bind);
            usedObjcBytes += len;
            objcCursor[0]++;
        }

        try (BufferedWriter writer = Files.newBufferedWriter(shardPath, StandardCharsets.UTF_8)) {
            for (String ann : sharedFileAnnotations) {
                writer.write(ann);
                writer.write('\n');
            }
            for (String bind : shardObjcBinds) {
                writer.write(bind);
                writer.write('\n');
            }
            if (!sharedFileAnnotations.isEmpty() || !shardObjcBinds.isEmpty()) {
                writer.write('\n');
            }
            if (packageLine != null) {
                writer.write(packageLine);
                writer.write("\n\n");
            }
            for (String imp : selectedImports) {
                writer.write(imp);
                writer.write('\n');
            }
            if (!selectedImports.isEmpty()) {
                writer.write('\n');
            }
            writer.write(body);
        }
        return usedObjcBytes;
    }

    /// Shards a single generated C bridge header into `<= maxBytes` self-contained headers and
    /// deletes the original file.
    ///
    /// @param headerFile source `.h` file
    /// @param outDir destination directory for generated `.h` shards
    /// @param maxBytes maximum UTF-8 byte size per shard
    /// @throws IOException if reading or writing fails
    public static void shardHeaderFile(Path headerFile, Path outDir, long maxBytes) throws IOException {
        String baseName = stripExtension(headerFile);
        deleteExistingShards(outDir, baseName, ".h");

        List<String> preludeLines = new ArrayList<>();
        boolean hasNonnullWrapper = false;
        String firstBodyLine = null;

        try (BufferedReader reader = Files.newBufferedReader(headerFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#include ") || trimmed.startsWith("#import ")
                        || trimmed.startsWith("#pragma ")) {
                    preludeLines.add(line);
                } else if ("NS_ASSUME_NONNULL_BEGIN".equals(trimmed)) {
                    preludeLines.add(line);
                    hasNonnullWrapper = true;
                    break;
                } else {
                    firstBodyLine = line;
                    break;
                }
            }

            String prelude = String.join("\n", preludeLines) + "\n\n";
            String footer = hasNonnullWrapper ? "NS_ASSUME_NONNULL_END\n" : "";
            long framingBytes = utf8Len(prelude) + utf8Len(footer);

            int shardIdx = 0;
            BufferedWriter writer = null;
            long currentBytes = 0L;
            boolean shardHasDecls = false;

            LexState lex = new LexState();
            List<String> unitLines = new ArrayList<>();

            String cur = firstBodyLine != null ? firstBodyLine : reader.readLine();
            while (cur != null) {
                String trimmed = cur.trim();
                if (unitLines.isEmpty() && (trimmed.isEmpty() || "NS_ASSUME_NONNULL_END".equals(trimmed))) {
                    cur = reader.readLine();
                    continue;
                }
                unitLines.add(cur);
                lex.scanLine(cur);
                if (lex.braceDepth == 0 && lex.parenDepth == 0 && lex.blockCommentDepth == 0
                        && (trimmed.endsWith(";") || trimmed.startsWith("#"))) {
                    String decl = String.join("\n", unitLines) + "\n\n";
                    long declBytes = utf8Len(decl);
                    if (writer != null && shardHasDecls && currentBytes + declBytes + utf8Len(footer) > maxBytes) {
                        writer.write(footer);
                        writer.close();
                        writer = null;
                        shardHasDecls = false;
                    }
                    if (writer == null) {
                        shardIdx++;
                        Path shardPath = outDir.resolve(String.format("%s_%04d.h", baseName, shardIdx));
                        writer = Files.newBufferedWriter(shardPath, StandardCharsets.UTF_8);
                        writer.write(prelude);
                        currentBytes = framingBytes - utf8Len(footer);
                    }
                    writer.write(decl);
                    currentBytes += declBytes;
                    shardHasDecls = true;
                    unitLines.clear();
                }
                cur = reader.readLine();
            }

            if (!unitLines.isEmpty()) {
                String decl = String.join("\n", unitLines) + "\n\n";
                if (writer == null) {
                    shardIdx++;
                    Path shardPath = outDir.resolve(String.format("%s_%04d.h", baseName, shardIdx));
                    writer = Files.newBufferedWriter(shardPath, StandardCharsets.UTF_8);
                    writer.write(prelude);
                }
                writer.write(decl);
            }
            if (writer != null) {
                writer.write(footer);
                writer.close();
            }
        }

        Files.deleteIfExists(headerFile);
    }

    /// Shards a single generated Swift API file into `<= maxBytes` Swift files, splitting both at
    /// top-level boundaries and between member declarations inside plain `ExportedKotlinPackages`
    /// namespace extensions, then deletes the original file.
    ///
    /// @param swiftFile source `.swift` file
    /// @param outDir destination directory for generated `.swift` shards
    /// @param maxBytes maximum UTF-8 byte size per shard
    /// @throws IOException if reading or writing fails
    public static void shardSwiftFile(Path swiftFile, Path outDir, long maxBytes) throws IOException {
        String baseName = stripExtension(swiftFile);
        deleteExistingShards(outDir, baseName, ".swift");

        List<String> preludeLines = new ArrayList<>();
        String firstBodyLine = null;

        try (BufferedReader reader = Files.newBufferedReader(swiftFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("//")) {
                    if (!preludeLines.isEmpty()) {
                        preludeLines.add(line);
                    }
                } else if (SWIFT_IMPORT_PATTERN.matcher(trimmed).matches()) {
                    preludeLines.add(line);
                } else {
                    firstBodyLine = line;
                    break;
                }
            }
            while (!preludeLines.isEmpty() && preludeLines.getLast().trim().isEmpty()) {
                preludeLines.removeLast();
            }

            String prelude = preludeLines.isEmpty() ? "" : String.join("\n", preludeLines) + "\n\n";
            long preludeBytes = utf8Len(prelude);

            int shardIdx = 0;
            BufferedWriter writer = null;
            long currentBytes = 0L;
            boolean shardHasUnits = false;

            LexState lex = new LexState();
            String activeNamespaceHeader = null;
            boolean activeNamespaceOpenInShard = false;
            boolean activeNamespaceSawMember = false;

            List<String> unitLines = new ArrayList<>();
            boolean hasDeclBody = false;
            boolean sawBraceAboveTarget = false;

            String cur = firstBodyLine != null ? firstBodyLine : reader.readLine();
            while (cur != null) {
                String trimmed = cur.trim();

                if (activeNamespaceHeader == null
                        && unitLines.isEmpty()
                        && lex.braceDepth == 0
                        && lex.parenDepth == 0
                        && !lex.inTripleQuote
                        && lex.blockCommentDepth == 0
                        && SPLITTABLE_SWIFT_NAMESPACE_EXT.matcher(trimmed).matches()) {
                    activeNamespaceHeader = cur;
                    activeNamespaceOpenInShard = false;
                    activeNamespaceSawMember = false;
                    lex.scanLine(cur);
                    cur = reader.readLine();
                    continue;
                }

                if (activeNamespaceHeader != null
                        && unitLines.isEmpty()
                        && lex.braceDepth == 1
                        && lex.parenDepth == 0
                        && !lex.inTripleQuote
                        && lex.blockCommentDepth == 0
                        && "}".equals(trimmed)) {
                    lex.scanLine(cur);
                    if (activeNamespaceOpenInShard) {
                        writer.write("}\n");
                        currentBytes += 2L;
                        activeNamespaceOpenInShard = false;
                    } else if (!activeNamespaceSawMember) {
                        String emptyExt = activeNamespaceHeader + "\n}\n";
                        long emptyBytes = utf8Len(emptyExt);
                        if (writer != null && shardHasUnits && currentBytes + emptyBytes > maxBytes) {
                            writer.close();
                            writer = null;
                            shardHasUnits = false;
                        }
                        if (writer == null) {
                            shardIdx++;
                            writer = openSwiftShard(outDir, baseName, shardIdx, prelude);
                            currentBytes = preludeBytes;
                        }
                        writer.write(emptyExt);
                        currentBytes += emptyBytes;
                        shardHasUnits = true;
                    }
                    activeNamespaceHeader = null;
                    cur = reader.readLine();
                    continue;
                }

                int targetDepth = activeNamespaceHeader != null ? 1 : 0;
                if (unitLines.isEmpty() && trimmed.isEmpty()) {
                    cur = reader.readLine();
                    continue;
                }

                unitLines.add(cur);
                lex.scanLine(cur);
                if (lex.braceDepth > targetDepth) {
                    sawBraceAboveTarget = true;
                }
                if (!trimmed.isEmpty()
                        && !trimmed.startsWith("//")
                        && !trimmed.startsWith("/*")
                        && !trimmed.startsWith("*")
                        && !trimmed.startsWith("@")) {
                    hasDeclBody = true;
                }

                boolean isSingleLineNonBraced = hasDeclBody
                        && !sawBraceAboveTarget
                        && isBracelessSwiftDeclaration(unitLines);

                boolean complete = hasDeclBody
                        && lex.braceDepth == targetDepth
                        && lex.parenDepth == 0
                        && !lex.inTripleQuote
                        && lex.blockCommentDepth == 0
                        && (sawBraceAboveTarget || isSingleLineNonBraced);

                if (complete) {
                    String unitText = String.join("\n", unitLines) + "\n";
                    long unitBytes = utf8Len(unitText);

                    if (activeNamespaceHeader != null) {
                        long headerIfNeeded = activeNamespaceOpenInShard ? 0L : utf8Len(activeNamespaceHeader) + 1L;
                        long closingBytes = 2L; // "}\n"
                        if (writer != null && shardHasUnits
                                && currentBytes + headerIfNeeded + unitBytes + closingBytes > maxBytes) {
                            if (activeNamespaceOpenInShard) {
                                writer.write("}\n");
                                activeNamespaceOpenInShard = false;
                            }
                            writer.close();
                            writer = null;
                            shardHasUnits = false;
                        }
                        if (writer == null) {
                            shardIdx++;
                            writer = openSwiftShard(outDir, baseName, shardIdx, prelude);
                            currentBytes = preludeBytes;
                        }
                        if (!activeNamespaceOpenInShard) {
                            writer.write(activeNamespaceHeader);
                            writer.write('\n');
                            currentBytes += utf8Len(activeNamespaceHeader) + 1L;
                            activeNamespaceOpenInShard = true;
                        }
                        writer.write(unitText);
                        currentBytes += unitBytes;
                        shardHasUnits = true;
                        activeNamespaceSawMember = true;
                    } else {
                        if (writer != null && shardHasUnits && currentBytes + unitBytes > maxBytes) {
                            writer.close();
                            writer = null;
                            shardHasUnits = false;
                        }
                        if (writer == null) {
                            shardIdx++;
                            writer = openSwiftShard(outDir, baseName, shardIdx, prelude);
                            currentBytes = preludeBytes;
                        }
                        writer.write(unitText);
                        currentBytes += unitBytes;
                        shardHasUnits = true;
                    }

                    unitLines.clear();
                    hasDeclBody = false;
                    sawBraceAboveTarget = false;
                }

                cur = reader.readLine();
            }

            if (!unitLines.isEmpty()) {
                if (writer == null) {
                    shardIdx++;
                    writer = openSwiftShard(outDir, baseName, shardIdx, prelude);
                }
                if (activeNamespaceHeader != null && !activeNamespaceOpenInShard) {
                    writer.write(activeNamespaceHeader);
                    writer.write('\n');
                    activeNamespaceOpenInShard = true;
                }
                writer.write(String.join("\n", unitLines));
                writer.write('\n');
            }
            if (writer != null) {
                if (activeNamespaceOpenInShard) {
                    writer.write("}\n");
                }
                writer.close();
            }
        }

        Files.deleteIfExists(swiftFile);
    }

    private static BufferedWriter openSwiftShard(Path outDir, String baseName, int shardIdx, String prelude)
            throws IOException {
        Path shardPath = outDir.resolve(String.format("%s_%04d.swift", baseName, shardIdx));
        BufferedWriter writer = Files.newBufferedWriter(shardPath, StandardCharsets.UTF_8);
        writer.write(prelude);
        return writer;
    }

    private static boolean isBracelessSwiftDeclaration(List<String> unitLines) {
        for (String line : unitLines) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("@") || t.startsWith("//") || t.startsWith("/*") || t.startsWith("*")) {
                continue;
            }
            return t.contains("typealias ")
                    || ((t.contains("let ") || t.contains("var ")) && t.contains("="));
        }
        return false;
    }

    private static String extractKotlinImportedIdent(String trimmedImport) {
        Matcher aliasMatcher = KOTLIN_IMPORT_ALIAS_PATTERN.matcher(trimmedImport);
        if (aliasMatcher.matches()) {
            return aliasMatcher.group(1).replace("`", "");
        }
        Matcher simpleMatcher = KOTLIN_IMPORT_SIMPLE_PATTERN.matcher(trimmedImport);
        if (simpleMatcher.matches()) {
            return simpleMatcher.group(1).replace("`", "");
        }
        return null;
    }

    private static boolean isOversizedUnsharded(Path path, long maxBytes) {
        try {
            if (!Files.isRegularFile(path)) {
                return false;
            }
            String name = path.getFileName().toString();
            if (!(name.endsWith(".kt") || name.endsWith(".h") || name.endsWith(".swift"))) {
                return false;
            }
            if (SHARDED_NAME_PATTERN.matcher(name).matches()) {
                return false;
            }
            return Files.size(path) > maxBytes;
        } catch (IOException e) {
            return false;
        }
    }

    private static void deleteExistingShards(Path dir, String baseName, String ext) throws IOException {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, baseName + "_*" + ext)) {
            for (Path p : stream) {
                if (SHARDED_NAME_PATTERN.matcher(p.getFileName().toString()).matches()) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    private static void recreateDirectory(Path dir) throws IOException {
        if (Files.exists(dir)) {
            try (Stream<Path> walk = Files.walk(dir)) {
                List<Path> toDelete = walk.sorted(Comparator.reverseOrder()).toList();
                for (Path p : toDelete) {
                    Files.deleteIfExists(p);
                }
            }
        }
        Files.createDirectories(dir);
    }

    private static Map<String, Object> copyStringKeyedMap(Map<?, ?> raw) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            copy.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return copy;
    }

    private static String stripExtension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static long sumLineBytes(List<String> lines) {
        long sum = 0L;
        for (String line : lines) {
            sum += utf8Len(line) + 1L;
        }
        return sum;
    }

    private static int utf8Len(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    /// CLI and `BuildToolTask` entrypoint:
    /// `SwiftExportSharder [--swift-export-root DIR] [--dir DIR] [--max-bytes BYTES]`.
    ///
    /// @param args command-line arguments
    /// @return zero on success
    /// @throws IOException if sharding fails
    public static int run(String... args) throws IOException {
        Path swiftExportRoot = null;
        Path dir = null;
        long maxBytes = DEFAULT_MAX_SHARD_BYTES;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--swift-export-root" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("Missing argument for --swift-export-root");
                    }
                    swiftExportRoot = Path.of(args[++i]);
                }
                case "--dir" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("Missing argument for --dir");
                    }
                    dir = Path.of(args[++i]);
                }
                case "--max-bytes" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("Missing argument for --max-bytes");
                    }
                    maxBytes = Long.parseLong(args[++i]);
                }
                default -> throw new IllegalArgumentException(
                        "Usage: SwiftExportSharder [--swift-export-root <dir>] [--dir <dir>] [--max-bytes <bytes>]");
            }
        }
        if (swiftExportRoot == null && dir == null) {
            throw new IllegalArgumentException(
                    "Usage: SwiftExportSharder [--swift-export-root <dir>] [--dir <dir>] [--max-bytes <bytes>]");
        }
        if (swiftExportRoot != null) {
            shardSwiftExportRoot(swiftExportRoot, maxBytes);
        }
        if (dir != null) {
            shardDirectoryInPlace(dir, maxBytes);
        }
        return 0;
    }

    /// Standalone CLI entrypoint.
    ///
    /// @param args command-line arguments forwarded to {@link #run(String...)}
    /// @throws IOException if sharding fails
    public static void main(String[] args) throws IOException {
        System.exit(run(args));
    }
}
