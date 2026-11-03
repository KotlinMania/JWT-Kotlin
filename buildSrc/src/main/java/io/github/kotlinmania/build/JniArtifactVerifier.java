package io.github.kotlinmania.build;

import static io.github.kotlinmania.build.AndroidPublicationVerifier.PublicationSupport.require;
import io.github.kotlinmania.build.AndroidPublicationVerifier.PublicationSupport;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Checks the actual JNI payload packaged for a supported desktop host. */
public final class JniArtifactVerifier {
    private JniArtifactVerifier() {}
    record Profile(String namespace, String baseName, String entryPoint, List<String> classes) {}
    static Profile profile(String value) {
        return switch (value) {
            case "candle" -> new Profile("io/github/kotlinmania/candle", "candle_jni", "Java_io_github_kotlinmania_candle_internal_CandleJni_abiVersion", List.of("CandleJni"));
            case "libc" -> new Profile("io/github/kotlinmania/libc", "libc_jni", "Java_io_github_kotlinmania_libc_internal_RawLibc_abiVersion", List.of("RawLibc", "LibcJni"));
            default -> throw new IllegalArgumentException("Unknown JNI artifact profile: " + value);
        };
    }

    public static Map<String, Object> verify(Path jar, String host, String profileName) throws IOException {
        Profile profile = profile(profileName);
        String filename = switch (host) {
            case "macos-aarch64" -> "lib" + profile.baseName() + ".dylib";
            case "linux-x86_64", "linux-aarch64" -> "lib" + profile.baseName() + ".so";
            case "windows-x86_64" -> profile.baseName() + ".dll";
            default -> throw new IllegalArgumentException("Unsupported JNI host: " + host);
        };
        String entry = profile.namespace() + "/native/" + host + "/" + filename;
        Map<String, byte[]> archive = PublicationSupport.readZip(jar, "Duplicate JNI JAR entries");
        byte[] data = archive.get(entry);
        require(data != null, "Missing packaged JNI payload: " + entry);
        require(Binary.contains(data, profile.entryPoint()), "Missing raw JNI ABI entry point");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jar", jar.toString()); result.put("entry", entry); result.put("payload_sha256", PublicationSupport.hash("sha256", data));
        if (host.equals("macos-aarch64")) {
            require(Binary.starts(data, new byte[] {(byte) 0xcf, (byte) 0xfa, (byte) 0xed, (byte) 0xfe}), "Expected a 64-bit Mach-O");
            require(Binary.u32(data, 4) == 0x100000cL, "Expected ARM64");
            long count = Binary.u32(data, 16);
            require(count <= data.length / 8, "Invalid Mach-O command count");
            int offset = 32;
            Long minimum = null;
            String installName = null;
            for (long i = 0; i < count; i++) {
                long command = Binary.u32(data, offset);
                long size = Binary.u32(data, offset + 4);
                require(size >= 8 && size <= data.length - (long) offset, "Invalid Mach-O load command size");
                if (command == 0x32) {
                    require(size >= 24, "Truncated LC_BUILD_VERSION");
                    minimum = Binary.u32(data, offset + 12);
                }
                if (command == 0xd) {
                    require(size >= 24, "Truncated LC_ID_DYLIB");
                    long start = Binary.u32(data, offset + 8);
                    require(start >= 24 && start < size, "Invalid dylib install-name offset");
                    int end = offset + (int) start;
                    while (end < offset + size && data[end] != 0) end++;
                    installName = new String(data, offset + (int) start, end - offset - (int) start, StandardCharsets.UTF_8);
                }
                offset += (int) size;
            }
            require(minimum != null && minimum > 0 && minimum <= 15L << 16, "JNI minimum macOS exceeds 15.0: " + minimum);
            require(("@rpath/" + filename).equals(installName), "Non-relocatable install name: " + installName);
            result.put("macos_minimum", (minimum >> 16) + "." + ((minimum >> 8) & 255) + "." + (minimum & 255));
            result.put("install_name", installName);
        } else if (host.startsWith("linux")) {
            Binary.elf(data, host.endsWith("aarch64") ? 183 : 62, "JNI payload");
        } else {
            require(Binary.starts(data, new byte[] {'M', 'Z'}), "Expected a Windows PE DLL");
            long pe = Binary.u32(data, 0x3c);
            require(pe <= data.length - 26L, "Invalid PE header offset");
            int offset = (int) pe;
            require(Binary.startsAt(data, offset, new byte[] {'P', 'E', 0, 0}), "Expected a Windows PE signature");
            require(Binary.u16(data, offset + 4) == 0x8664, "Expected x86-64 PE machine");
            require(Binary.u16(data, offset + 24) == 0x20b, "Expected PE32+ optional header");
            require((Binary.u16(data, offset + 22) & 0x2000) != 0, "Expected a DLL");
        }
        return result;
    }

    public static int run(String... args) throws IOException {
        require(args.length == 5 && args[3].equals("--profile"), "Usage: jar host receipt --profile candle|libc");
        Map<String, Object> result = verify(Path.of(args[0]), args[1], args[4]);
        Json.write(Path.of(args[2]), result);
        System.out.println("Actual packaged JNI payload verified for " + args[1] + ": " + Json.stringify(result));
        return 0;
    }
    public static void main(String[] args) throws IOException { System.exit(run(args)); }

    static final class Binary {
        private Binary() {}
        static boolean starts(byte[] data, byte[] prefix) { return startsAt(data, 0, prefix); }
        static boolean startsAt(byte[] data, int offset, byte[] prefix) {
            return offset >= 0 && offset <= data.length - prefix.length && Arrays.equals(data, offset, offset + prefix.length, prefix, 0, prefix.length);
        }
        static boolean contains(byte[] data, String value) { return new String(data, StandardCharsets.ISO_8859_1).contains(value); }
        static ByteBuffer at(byte[] data, int offset, int size) {
            require(offset >= 0 && offset <= data.length - size, "Truncated native binary");
            return ByteBuffer.wrap(data, offset, size).order(ByteOrder.LITTLE_ENDIAN);
        }
        static int u16(byte[] data, int offset) { return Short.toUnsignedInt(at(data, offset, 2).getShort()); }
        static long u32(byte[] data, int offset) { return Integer.toUnsignedLong(at(data, offset, 4).getInt()); }
        static long i64(byte[] data, int offset) { return at(data, offset, 8).getLong(); }
        static void elf(byte[] data, int machine, String label) {
            require(starts(data, new byte[] {0x7f, 'E', 'L', 'F', 2, 1}), label + ": expected a little-endian 64-bit ELF");
            require(u16(data, 18) == machine, label + ": wrong instruction set");
        }
    }
}
