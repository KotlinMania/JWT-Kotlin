package io.github.kotlinmania.build;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates the object format and architecture of a Linux wrapper archive. */
public final class NativeArchiveVerifier {
    private static final byte[] ARCHIVE_MAGIC = "!<arch>\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ELF_MAGIC = {0x7f, 'E', 'L', 'F', 2, 1, 1};
    private static final Map<String, Integer> MACHINES = Map.of("linux_x64", 62, "linux_arm64", 183);
    private static final Set<String> BSD_SYMBOL_TABLES = Set.of(
            "__.SYMDEF", "__.SYMDEF SORTED", "__.SYMDEF_64", "__.SYMDEF_64 SORTED");

    private NativeArchiveVerifier() {}

    public record ArchiveMember(String name, byte[] payload) {}

    public static Map<String, Object> elfIdentity(byte[] data, String label, int expectedMachine) {
        if (data.length < 20 || !startsWith(data, ELF_MAGIC)) {
            throw new IllegalArgumentException(label + ": expected ELF64 little-endian object");
        }
        ByteBuffer header = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int kind = Short.toUnsignedInt(header.getShort(16));
        int machine = Short.toUnsignedInt(header.getShort(18));
        if (kind != 1 || machine != expectedMachine) {
            throw new IllegalArgumentException(label + ": expected relocatable ELF machine "
                    + expectedMachine + ", got type " + kind + " machine " + machine);
        }
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("name", label);
        identity.put("machine", machine);
        identity.put("type", kind);
        identity.put("sha256", sha256(data));
        return identity;
    }

    public static List<ArchiveMember> archiveMembers(byte[] data) {
        if (!startsWith(data, ARCHIVE_MAGIC)) {
            throw new IllegalArgumentException("Expected regular ar archive, not a thin archive or renamed object");
        }
        int offset = ARCHIVE_MAGIC.length;
        byte[] names = new byte[0];
        List<ArchiveMember> members = new ArrayList<>();
        while (offset < data.length) {
            if (data.length - offset < 60 || data[offset + 58] != '`' || data[offset + 59] != '\n') {
                throw new IllegalArgumentException("Invalid ar member header");
            }
            long size = decimal(decode(data, offset + 48, 10, true).trim(), "Invalid ar member size");
            long end = offset + 60L + size;
            if (size < 0 || end > data.length) {
                throw new IllegalArgumentException("Truncated ar member");
            }
            String name = decode(data, offset, 16, true).trim();
            byte[] payload = Arrays.copyOfRange(data, offset + 60, (int) end);
            long alignedEnd = end + size % 2;
            if (alignedEnd > data.length) {
                throw new IllegalArgumentException("Missing ar alignment byte");
            }
            offset = (int) alignedEnd;
            if (name.equals("//")) {
                names = payload;
                continue;
            }
            if (name.equals("/") || name.equals("/SYM64/")) {
                continue;
            }
            if (name.startsWith("#1/")) {
                long nameLength = decimal(name.substring(3), "Invalid BSD extended member name");
                if (nameLength <= 0 || nameLength > payload.length) {
                    throw new IllegalArgumentException("Invalid BSD extended member name");
                }
                int textLength = (int) nameLength;
                while (textLength > 0 && payload[textLength - 1] == 0) {
                    textLength--;
                }
                name = decode(payload, 0, textLength, false);
                payload = Arrays.copyOfRange(payload, (int) nameLength, payload.length);
            } else if (name.matches("/\\d+")) {
                long nameIndex = decimal(name.substring(1), "Invalid GNU extended member name");
                if (nameIndex >= names.length) {
                    throw new IllegalArgumentException("Invalid GNU extended member name");
                }
                int endIndex = (int) nameIndex;
                while (endIndex < names.length && names[endIndex] != '\n') {
                    endIndex++;
                }
                while (endIndex > nameIndex && names[endIndex - 1] == '/') {
                    endIndex--;
                }
                name = decode(names, (int) nameIndex, endIndex - (int) nameIndex, false);
            } else {
                name = name.replaceFirst("/+$", "");
            }
            if (!BSD_SYMBOL_TABLES.contains(name)) {
                members.add(new ArchiveMember(name, payload));
            }
        }
        return List.copyOf(members);
    }

    public static Map<String, Object> verify(Path archive, String target, Path objectFile) throws IOException {
        Integer expected = MACHINES.get(target);
        if (expected == null) {
            throw new IllegalArgumentException("Unsupported archive target: " + target);
        }
        byte[] archiveBytes = Files.readAllBytes(archive);
        List<Map<String, Object>> identities = new ArrayList<>();
        for (ArchiveMember member : archiveMembers(archiveBytes)) {
            identities.add(elfIdentity(member.payload(), member.name(), expected));
        }
        if (identities.isEmpty()) {
            throw new IllegalArgumentException("Archive contains no ELF object members");
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("target", target);
        report.put("expected_machine", expected);
        report.put("archive_sha256", sha256(archiveBytes));
        report.put("members", identities);
        if (objectFile != null) {
            Map<String, Object> object = elfIdentity(Files.readAllBytes(objectFile), objectFile.toString(), expected);
            if (identities.stream().noneMatch(member -> member.get("sha256").equals(object.get("sha256")))) {
                throw new IllegalArgumentException("Compiled object bytes are absent from the archive");
            }
            report.put("compiled_object", object);
        }
        return report;
    }

    public static Map<String, Object> verify(Path archive, String target) throws IOException {
        return verify(archive, target, null);
    }

    public static int run(String... arguments) {
        try {
            Path archive = null;
            Path object = null;
            Path reportPath = null;
            String target = null;
            for (int index = 0; index < arguments.length; index++) {
                String argument = arguments[index];
                if (argument.equals("--help")) {
                    System.out.println("NativeArchiveVerifier ARCHIVE --target linux_x64|linux_arm64 [--object FILE] [--report FILE]");
                    return 0;
                }
                if (argument.startsWith("--")) {
                    if (++index == arguments.length) {
                        throw new IllegalArgumentException("Missing value for " + argument);
                    }
                    switch (argument) {
                        case "--target" -> target = arguments[index];
                        case "--object" -> object = Path.of(arguments[index]);
                        case "--report" -> reportPath = Path.of(arguments[index]);
                        default -> throw new IllegalArgumentException("Unknown option: " + argument);
                    }
                } else if (archive == null) {
                    archive = Path.of(argument);
                } else {
                    throw new IllegalArgumentException("Unexpected argument: " + argument);
                }
            }
            if (archive == null || target == null) {
                throw new IllegalArgumentException("An archive path and --target are required");
            }
            Map<String, Object> report = verify(archive, target, object);
            if (reportPath != null) {
                Json.write(reportPath, report);
            }
            System.out.println(Json.stringify(report));
            return 0;
        } catch (IOException | IllegalArgumentException exception) {
            System.err.println("Native archive verification failed: " + exception.getMessage());
            return 1;
        }
    }

    public static void main(String[] arguments) {
        System.exit(run(arguments));
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        return value.length >= prefix.length && Arrays.equals(value, 0, prefix.length, prefix, 0, prefix.length);
    }

    private static long decimal(String value, String message) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(message, exception);
        }
    }

    private static String decode(byte[] value, int offset, int length, boolean ascii) {
        try {
            return (ascii ? StandardCharsets.US_ASCII : StandardCharsets.UTF_8).newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(value, offset, length)).toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("Invalid archive member name or header encoding", exception);
        }
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK SHA-256 implementation is unavailable", exception);
        }
    }
}
