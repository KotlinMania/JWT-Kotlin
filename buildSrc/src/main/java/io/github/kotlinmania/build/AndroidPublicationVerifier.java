package io.github.kotlinmania.build;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.CRC32;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Validates the Android payload and metadata in a real Maven deployment ZIP. */
public final class AndroidPublicationVerifier {
    private AndroidPublicationVerifier() {}

    public static final Set<String> ASYNC_TRAIT_CLASSES = Set.of(
        "io/github/kotlinmania/asynctrait/AsyncTraitKt.class",
        "io/github/kotlinmania/asynctrait/Args.class",
        "io/github/kotlinmania/asynctrait/InferredBound.class");
    public static final List<ApiDependency> ASYNC_TRAIT_API_DEPENDENCIES = List.of(
        new ApiDependency("io.github.kotlinmania", "syn-kotlin", "syn-kotlin-android"),
        new ApiDependency("io.github.kotlinmania", "proc-macro2-kotlin", "proc-macro2-kotlin-android"),
        new ApiDependency("io.github.kotlinmania", "quote-kotlin", "quote-kotlin-android"));

    public record ApiDependency(String group, String rootModule, String androidModule) {
        public ApiDependency {
            PublicationSupport.require(
                group != null && !group.isEmpty()
                    && rootModule != null && !rootModule.isEmpty()
                    && androidModule != null && !androidModule.isEmpty(),
                "Invalid API edge");
        }

        public static ApiDependency parse(String value) {
            String[] parts = value.split(":", -1);
            PublicationSupport.require(parts.length == 3, "Invalid API edge");
            return new ApiDependency(parts[0], parts[1], parts[2]);
        }

        @Override
        public String toString() {
            return group + ":" + rootModule + ":" + androidModule;
        }
    }

    public static Map<String, Object> verify(
        Path bundle,
        String group,
        String module,
        String version,
        Set<String> requiredClasses,
        Collection<ApiDependency> apiDependencies
    ) throws IOException {
        return verify(bundle, group, module, version, requiredClasses, apiDependencies, false);
    }

    public static Map<String, Object> verify(
        Path bundle, String group, String module, String version, Set<String> requiredClasses,
        Collection<ApiDependency> apiDependencies, boolean apiFacade
    ) throws IOException {
        PublicationSupport.require(apiFacade || !requiredClasses.isEmpty(), "Declare publicClasses to verify real public bytecode");
        PublicationSupport.require(!apiFacade || requiredClasses.isEmpty(), "An API facade must not declare public bytecode classes");
        Set<String> apiModules = new LinkedHashSet<>();
        Set<String> pomModules = new LinkedHashSet<>();
        for (ApiDependency edge : apiDependencies) {
            apiModules.add(edge.group() + ":" + edge.rootModule());
            pomModules.add(edge.group() + ":" + edge.androidModule());
        }
        String prefix = group.replace('.', '/');
        String rootPath = prefix + "/" + module + "/" + version + "/" + module + "-" + version + ".module";
        String androidStem = prefix + "/" + module + "-android/" + version + "/" + module + "-android-" + version;
        String androidPath = androidStem + ".module";
        String aarPath = androidStem + ".aar";
        String pomPath = androidStem + ".pom";
        Map<String, byte[]> archive = PublicationSupport.readZip(bundle, "Duplicate deployment ZIP entries");
        for (String name : archive.keySet()) {
            PublicationSupport.require(
                !name.startsWith("/") && !List.of(name.split("/", -1)).contains(".."),
                "Unsafe deployment ZIP entry");
        }
        for (String path : List.of(rootPath, androidPath, aarPath, pomPath)) {
            PublicationSupport.require(archive.containsKey(path), "Missing Android publication entry: " + path);
        }
        Map<String, Object> root = PublicationSupport.jsonObject(archive.get(rootPath));
        Map<String, Object> android = PublicationSupport.jsonObject(archive.get(androidPath));
        for (Map.Entry<String, Map<String, Object>> entry : Map.of(rootPath, root, androidPath, android).entrySet()) {
            Map<String, Object> metadata = entry.getValue();
            PublicationSupport.require("1.1".equals(metadata.get("formatVersion")), "Unsupported metadata format: " + entry.getKey());
            Map<String, Object> component = PublicationSupport.object(metadata.get("component"));
            PublicationSupport.require(
                group.equals(component.get("group")) && module.equals(component.get("module")) && version.equals(component.get("version")),
                "Incorrect owning component: " + entry.getKey());
        }
        Map<String, Object> component = PublicationSupport.object(android.get("component"));
        PublicationSupport.require(
            PublicationSupport.relatedPath(androidPath, PublicationSupport.string(component, "url")).equals(rootPath),
            "Android metadata must reference its owning root component");
        List<Map<String, Object>> rootVariants = androidVariants(root);
        List<Map<String, Object>> variants = androidVariants(android);
        for (List<Map<String, Object>> selected : List.of(rootVariants, variants)) {
            Set<String> usages = new LinkedHashSet<>();
            for (Map<String, Object> variant : selected) {
                Map<String, Object> attributes = PublicationSupport.object(variant.get("attributes"));
                usages.add(PublicationSupport.string(attributes, "org.gradle.usage"));
            }
            PublicationSupport.require(
                selected.size() == 2 && usages.equals(Set.of("java-api", "java-runtime")),
                "Android API and runtime variants are required");
            for (Map<String, Object> variant : selected) {
                Map<String, Object> attributes = PublicationSupport.object(variant.get("attributes"));
                PublicationSupport.require(
                    "aar".equals(attributes.get("org.gradle.libraryelements"))
                        && "android".equals(attributes.get("org.gradle.jvm.environment")),
                    "Android variants must select Android AARs");
            }
        }
        for (Map<String, Object> variant : rootVariants) {
            Map<String, Object> redirect = PublicationSupport.object(variant.get("available-at"));
            PublicationSupport.require(
                group.equals(redirect.get("group")) && (module + "-android").equals(redirect.get("module"))
                    && version.equals(redirect.get("version"))
                    && PublicationSupport.relatedPath(rootPath, PublicationSupport.string(redirect, "url")).equals(androidPath),
                "Root Android variant redirects to the wrong publication");
        }

        byte[] payload = archive.get(aarPath);
        String payloadHash = PublicationSupport.hash("SHA-256", payload);
        for (Map<String, Object> variant : variants) {
            List<Object> files = PublicationSupport.array(variant.getOrDefault("files", List.of()));
            PublicationSupport.require(files.size() == 1, "Android variant must reference the packaged AAR");
            Map<String, Object> descriptor = PublicationSupport.object(files.get(0));
            PublicationSupport.require(
                PublicationSupport.relatedPath(androidPath, PublicationSupport.string(descriptor, "url")).equals(aarPath),
                "Android variant must reference the packaged AAR");
            PublicationSupport.require(
                PublicationSupport.sameSize(descriptor.get("size"), payload.length) && payloadHash.equals(descriptor.get("sha256")),
                "AAR size or SHA-256 disagrees with module metadata");
            for (String algorithm : List.of("sha512", "sha1", "md5")) {
                if (descriptor.containsKey(algorithm)) {
                    PublicationSupport.require(
                        PublicationSupport.hash(algorithm, payload).equals(descriptor.get(algorithm)),
                        "AAR " + algorithm + " disagrees with module metadata");
                }
            }
            Set<String> dependencies = new LinkedHashSet<>();
            for (Object raw : PublicationSupport.array(variant.getOrDefault("dependencies", List.of()))) {
                Map<String, Object> edge = PublicationSupport.object(raw);
                dependencies.add(PublicationSupport.string(edge, "group") + ":" + PublicationSupport.string(edge, "module"));
            }
            Set<String> missing = new TreeSet<>(apiModules);
            missing.removeAll(dependencies);
            PublicationSupport.require(
                missing.isEmpty(),
                "Public signature dependencies missing from " + PublicationSupport.string(variant, "name") + ": " + missing);
        }

        Element pom = PublicationSupport.xml(archive.get(pomPath));
        for (Map.Entry<String, String> field : Map.of(
            "groupId", group, "artifactId", module + "-android", "version", version, "packaging", "aar").entrySet()) {
            PublicationSupport.require(
                field.getValue().equals(PublicationSupport.childText(pom, field.getKey(), null)),
                "Incorrect Android POM " + field.getKey());
        }
        Set<String> compileDependencies = new LinkedHashSet<>();
        for (Element dependency : PublicationSupport.pomDependencies(pom)) {
            if ("compile".equals(PublicationSupport.childText(dependency, "scope", "compile"))) {
                compileDependencies.add(
                    PublicationSupport.childText(dependency, "groupId", null) + ":"
                        + PublicationSupport.childText(dependency, "artifactId", null));
            }
        }
        PublicationSupport.require(
            compileDependencies.containsAll(pomModules),
            "Android POM must expose public signature dependencies at compile scope");

        Map<String, byte[]> aar = PublicationSupport.readZip(payload, "Duplicate AAR entries");
        PublicationSupport.require(
            aar.containsKey("AndroidManifest.xml") && aar.get("AndroidManifest.xml").length > 0,
            "AAR has no Android manifest");
        PublicationSupport.require(aar.containsKey("classes.jar"), "AAR has no classes.jar");
        Map<String, byte[]> classes = PublicationSupport.readZip(aar.get("classes.jar"), "Duplicate classes.jar entries");
        PublicationSupport.require(!apiFacade || classes.keySet().stream().noneMatch(name -> name.endsWith(".class")),
            "API facade AAR contains bytecode classes");
        for (String name : requiredClasses) {
            PublicationSupport.require(classes.containsKey(name), "AAR is missing public API class: " + name);
            byte[] bytecode = classes.get(name);
            PublicationSupport.require(
                bytecode.length >= 4 && bytecode[0] == (byte) 0xca && bytecode[1] == (byte) 0xfe
                    && bytecode[2] == (byte) 0xba && bytecode[3] == (byte) 0xbe,
                "AAR entry is not JVM bytecode: " + name);
        }
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("status", "passed");
        receipt.put("bundle", bundle.toAbsolutePath().normalize().toString());
        receipt.put("bundle_sha256", PublicationSupport.fingerprint(bundle));
        receipt.put("coordinates", group + ":" + module + ":" + version);
        receipt.put("aar", aarPath);
        receipt.put("aar_sha256", payloadHash);
        receipt.put("aar_bytes", payload.length);
        receipt.put("required_classes", new TreeSet<>(requiredClasses));
        receipt.put("variants", variants.stream().map(v -> PublicationSupport.string(v, "name")).toList());
        receipt.put("api_dependencies", apiDependencies.stream().map(ApiDependency::toString).sorted().toList());
        if (apiFacade) receipt.put("api_facade", true);
        receipt.put("consumer_compilation", "Separate Gradle consumer must still resolve and compile this publication.");
        return receipt;
    }

    public static Map<String, Object> verifyAsyncTrait(Path bundle, String group, String module, String version) throws IOException {
        return verify(bundle, group, module, version, ASYNC_TRAIT_CLASSES, ASYNC_TRAIT_API_DEPENDENCIES);
    }

    private static List<Map<String, Object>> androidVariants(Map<String, Object> metadata) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object raw : PublicationSupport.array(metadata.get("variants"))) {
            Map<String, Object> variant = PublicationSupport.object(raw);
            Map<String, Object> attributes = PublicationSupport.object(variant.getOrDefault("attributes", Map.of()));
            if ("androidJvm".equals(attributes.get("org.jetbrains.kotlin.platform.type"))
                && "library".equals(attributes.get("org.gradle.category"))) {
                result.add(variant);
            }
        }
        return result;
    }

    public static int run(String... args) throws IOException {
        PublicationSupport.Options options = PublicationSupport.Options.parse(
            args, Set.of("group", "module", "version", "receipt", "public-class", "api-dependency", "profile"), Set.of("api-facade"));
        Path receiptPath = Path.of(options.one("receipt"));
        Map<String, Object> receipt;
        try {
            Set<String> classes = new LinkedHashSet<>(options.many("public-class"));
            List<ApiDependency> edges = new ArrayList<>(options.many("api-dependency").stream().map(ApiDependency::parse).toList());
            if (!options.many("profile").isEmpty()) {
                PublicationSupport.require(options.one("profile").equals("async-trait"), "Unknown Android publication profile");
                classes.addAll(ASYNC_TRAIT_CLASSES);
                for (ApiDependency dependency : ASYNC_TRAIT_API_DEPENDENCIES) if (!edges.contains(dependency)) edges.add(dependency);
            }
            PublicationSupport.require(options.flag("api-facade") || !classes.isEmpty(), "--public-class is required");
            receipt = verify(
                options.path(), options.one("group"), options.one("module"), options.one("version"),
                classes, edges, options.flag("api-facade"));
        } catch (Exception error) {
            receipt = PublicationSupport.failure(error);
        }
        Json.write(receiptPath, receipt);
        System.out.println(Json.stringify(receipt));
        return "passed".equals(receipt.get("status")) ? 0 : 1;
    }

    public static void main(String[] args) throws IOException {
        System.exit(run(args));
    }
/** Shared parsing operations for the three publication validators. */
static final class PublicationSupport {
    private static final String POM_NAMESPACE = "http://maven.apache.org/POM/4.0.0";
    private static final Charset ZIP_LEGACY_CHARSET = Charset.forName("IBM437");

    private PublicationSupport() {}

    static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    static Map<String, Object> object(Object value) {
        require(value instanceof Map<?, ?>, "Expected a JSON object");
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            require(entry.getKey() instanceof String, "Expected a JSON object with string keys");
            result.put((String) entry.getKey(), entry.getValue());
        }
        return result;
    }

    static List<Object> array(Object value) {
        require(value instanceof List<?>, "Expected a JSON array");
        return new ArrayList<>((List<?>) value);
    }

    static String string(Map<String, Object> object, String key) {
        Object value = object.get(key);
        require(value instanceof String, "Missing or invalid string field: " + key);
        return (String) value;
    }

    static Map<String, Object> jsonObject(byte[] bytes) {
        return object(Json.parse(new String(bytes, StandardCharsets.UTF_8)));
    }

    static String relatedPath(String origin, String relative) {
        require(!relative.startsWith("/"), "Absolute metadata URL: " + relative);
        int slash = origin.lastIndexOf('/');
        String path = (slash < 0 ? "" : origin.substring(0, slash + 1)) + relative;
        List<String> parts = new ArrayList<>();
        for (String part : path.split("/", -1)) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                require(!parts.isEmpty(), "Metadata URL escapes repository: " + relative);
                parts.remove(parts.size() - 1);
            } else {
                parts.add(part);
            }
        }
        return String.join("/", parts);
    }

    static Map<String, byte[]> readZip(Path path, String duplicateMessage) throws IOException {
        return readZip(Files.readAllBytes(path), duplicateMessage);
    }

    static Map<String, byte[]> readZip(byte[] bytes, String duplicateMessage) throws IOException {
        Map<String, byte[]> contents = new LinkedHashMap<>();
        for (ArchiveEntry entry : zipEntries(bytes)) {
            require(!contents.containsKey(entry.name()), duplicateMessage);
            contents.put(entry.name(), entry.bytes());
        }
        return contents;
    }

    record ArchiveEntry(String name, byte[] bytes) {}

    /** Read indexed entries, checking the central directory as well as each local record. */
    static List<ArchiveEntry> zipEntries(byte[] bytes) throws IOException {
        int end = -1;
        for (int i = bytes.length - 22; i >= Math.max(0, bytes.length - 65557); i--) {
            if (bytes[i] == 'P' && bytes[i + 1] == 'K' && bytes[i + 2] == 5 && bytes[i + 3] == 6) {
                if (i + 22 + u16(bytes, i + 20) <= bytes.length) {
                    end = i;
                    break;
                }
            }
        }
        require(end >= 0, "Invalid ZIP archive: missing central directory");
        require(u16(bytes, end + 4) == 0 && u16(bytes, end + 6) == 0, "Multi-disk ZIP archives are unsupported");
        long count = u16(bytes, end + 10);
        require(u16(bytes, end + 8) == count, "Inconsistent ZIP directory entry count");
        long size = u32(bytes, end + 12);
        long declaredOffset = u32(bytes, end + 16);
        int centralEnd = end;
        if (end >= 20 && u32(bytes, end - 20) == 0x07064b50L) {
            require(u32(bytes, end - 16) == 0 && u32(bytes, end - 4) == 1, "Multi-disk ZIP64 archives are unsupported");
            int zip64 = -1;
            long declaredZip64 = u64(bytes, end - 12);
            if (declaredZip64 <= end - 76 && u32(bytes, (int) declaredZip64) == 0x06064b50L) {
                zip64 = (int) declaredZip64;
            } else {
                // A ZIP may be appended to a prefix; offsets in its records remain relative to the ZIP.
                for (int i = end - 76; i >= 0; i--) {
                    if (u32(bytes, i) == 0x06064b50L && u64(bytes, i + 4) == end - 20L - i - 12) {
                        zip64 = i;
                        break;
                    }
                }
            }
            require(zip64 >= 0 && u64(bytes, zip64 + 4) >= 44
                && zip64 + 12L + u64(bytes, zip64 + 4) == end - 20L, "Invalid ZIP64 directory record");
            require(u32(bytes, zip64 + 16) == 0 && u32(bytes, zip64 + 20) == 0, "Multi-disk ZIP64 archives are unsupported");
            count = u64(bytes, zip64 + 32);
            require(u64(bytes, zip64 + 24) == count, "Inconsistent ZIP64 directory entry count");
            size = u64(bytes, zip64 + 40);
            declaredOffset = u64(bytes, zip64 + 48);
            centralEnd = zip64;
        }
        require(size <= centralEnd && count <= bytes.length / 46L, "Invalid ZIP directory size or count");
        int cursor = Math.toIntExact(centralEnd - size);
        long prefix = cursor - declaredOffset;
        require(prefix >= 0, "Invalid ZIP central-directory offset");
        List<ArchiveEntry> entries = new ArrayList<>();
        for (long index = 0; index < count; index++) {
            require(cursor <= centralEnd - 46 && u32(bytes, cursor) == 0x02014b50L, "Invalid ZIP central-directory entry");
            int flags = u16(bytes, cursor + 8);
            int method = u16(bytes, cursor + 10);
            long crc = u32(bytes, cursor + 16);
            long compressed = u32(bytes, cursor + 20);
            long uncompressed = u32(bytes, cursor + 24);
            int nameLength = u16(bytes, cursor + 28);
            int extraLength = u16(bytes, cursor + 30);
            int commentLength = u16(bytes, cursor + 32);
            int disk = u16(bytes, cursor + 34);
            long local = u32(bytes, cursor + 42);
            int next = cursor + 46 + nameLength + extraLength + commentLength;
            require(next <= centralEnd, "Truncated ZIP central-directory entry");
            Charset charset = (flags & 0x800) == 0 ? ZIP_LEGACY_CHARSET : StandardCharsets.UTF_8;
            String name = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, cursor + 46, nameLength)).toString();
            require(name.indexOf('\0') < 0, "Invalid ZIP entry name");
            boolean needsZip64 = uncompressed == 0xffffffffL || compressed == 0xffffffffL || local == 0xffffffffL || disk == 0xffff;
            boolean foundZip64 = false;
            for (int extra = cursor + 46 + nameLength, limit = extra + extraLength; extra < limit;) {
                require(extra <= limit - 4, "Truncated ZIP extra field");
                int extraEnd = extra + 4 + u16(bytes, extra + 2);
                require(extraEnd <= limit, "Truncated ZIP extra field");
                if (u16(bytes, extra) == 1 && needsZip64) {
                    int value = extra + 4;
                    if (uncompressed == 0xffffffffL) { require(value <= extraEnd - 8, "Missing ZIP64 size"); uncompressed = u64(bytes, value); value += 8; }
                    if (compressed == 0xffffffffL) { require(value <= extraEnd - 8, "Missing ZIP64 compressed size"); compressed = u64(bytes, value); value += 8; }
                    if (local == 0xffffffffL) { require(value <= extraEnd - 8, "Missing ZIP64 offset"); local = u64(bytes, value); value += 8; }
                    if (disk == 0xffff) { require(value <= extraEnd - 4, "Missing ZIP64 disk"); disk = Math.toIntExact(u32(bytes, value)); }
                    foundZip64 = true;
                }
                extra = extraEnd;
            }
            require(!needsZip64 || foundZip64, "Missing ZIP64 extra field");
            require(disk == 0 && (flags & 1) == 0, "Split or encrypted ZIP entries are unsupported");
            long actualOffset = local + prefix;
            require(actualOffset >= 0 && actualOffset <= cursor - 30L && compressed <= cursor - actualOffset,
                "Invalid ZIP local-entry offset or size");
            byte[] payload;
            try (ZipInputStream input = new ZipInputStream(
                new ByteArrayInputStream(bytes, (int) actualOffset, cursor - (int) actualOffset), ZIP_LEGACY_CHARSET)) {
                ZipEntry entry = input.getNextEntry();
                require(entry != null && name.equals(entry.getName()) && method == entry.getMethod(),
                    "ZIP local entry disagrees with central directory");
                payload = input.readAllBytes();
                require(entry.getCompressedSize() == compressed, "ZIP compressed size disagrees with central directory");
            }
            CRC32 checksum = new CRC32();
            checksum.update(payload);
            require(payload.length == uncompressed && checksum.getValue() == crc, "ZIP entry size or CRC disagrees with central directory");
            entries.add(new ArchiveEntry(name, payload));
            cursor = next;
        }
        require(cursor == centralEnd, "Inconsistent ZIP central-directory length");
        return entries;
    }

    private static int u16(byte[] bytes, int offset) {
        require(offset >= 0 && offset <= bytes.length - 2, "Truncated ZIP structure");
        return (bytes[offset] & 255) | ((bytes[offset + 1] & 255) << 8);
    }

    private static long u32(byte[] bytes, int offset) {
        return Integer.toUnsignedLong(u16(bytes, offset) | u16(bytes, offset + 2) << 16);
    }

    private static long u64(byte[] bytes, int offset) {
        long value = u32(bytes, offset) | u32(bytes, offset + 4) << 32;
        require(value >= 0, "ZIP64 values exceed the supported address space");
        return value;
    }

    static String hash(String algorithm, byte[] bytes) {
        String digest = switch (algorithm.toLowerCase()) {
            case "sha256", "sha-256" -> "SHA-256";
            case "sha512", "sha-512" -> "SHA-512";
            case "sha1", "sha-1" -> "SHA-1";
            case "md5" -> "MD5";
            default -> throw new IllegalArgumentException("Unsupported hash algorithm: " + algorithm);
        };
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(digest).digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("JDK lacks required digest " + digest, error);
        }
    }

    static String fingerprint(Path path) throws IOException {
        return hash("SHA-256", Files.readAllBytes(path));
    }

    static boolean sameSize(Object size, long expected) {
        return size instanceof Number && new BigDecimal(size.toString()).compareTo(BigDecimal.valueOf(expected)) == 0;
    }

    static Element xml(byte[] bytes) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes)).getDocumentElement();
        } catch (Exception error) {
            throw new IOException("Invalid publication POM: " + error.getMessage(), error);
        }
    }

    static String childText(Element parent, String name, String fallback) {
        Element child = child(parent, name);
        return child == null ? fallback : child.getTextContent();
    }

    static Element child(Element parent, String name) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && POM_NAMESPACE.equals(element.getNamespaceURI()) && name.equals(element.getLocalName())) {
                return element;
            }
        }
        return null;
    }

    static List<Element> pomDependencies(Element pom) {
        List<Element> result = new ArrayList<>();
        Element dependencies = child(pom, "dependencies");
        if (dependencies != null) {
            for (Node node = dependencies.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (node instanceof Element element && POM_NAMESPACE.equals(element.getNamespaceURI()) && "dependency".equals(element.getLocalName())) {
                    result.add(element);
                }
            }
        }
        return result;
    }

    static Map<String, Object> failure(Exception error) {
        return Map.of("status", "failed", "error", error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
    }

    static final class Options {
        private final Map<String, List<String>> values = new LinkedHashMap<>();
        private final Set<String> flags = new LinkedHashSet<>();
        private String positional;

        static Options parse(String[] args, Set<String> valued, Set<String> switches) {
            Options options = new Options();
            for (int i = 0; i < args.length; i++) {
                String argument = args[i];
                if (argument.startsWith("--")) {
                    String key = argument.substring(2);
                    if (switches.contains(key)) {
                        options.flags.add(key);
                    } else {
                        require(valued.contains(key), "Unknown option: " + argument);
                        require(i + 1 < args.length, "Missing value for " + argument);
                        options.values.computeIfAbsent(key, ignored -> new ArrayList<>()).add(args[++i]);
                    }
                } else {
                    require(options.positional == null, "Expected one input path");
                    options.positional = argument;
                }
            }
            require(options.positional != null, "An input path is required");
            return options;
        }

        Path path() {
            return Path.of(positional);
        }

        String one(String name) {
            List<String> valuesForName = values.getOrDefault(name, List.of());
            require(valuesForName.size() == 1, "Expected one --" + name);
            return valuesForName.get(0);
        }

        List<String> many(String name) {
            return Collections.unmodifiableList(values.getOrDefault(name, List.of()));
        }

        boolean flag(String name) {
            return flags.contains(name);
        }
    }
}
}
