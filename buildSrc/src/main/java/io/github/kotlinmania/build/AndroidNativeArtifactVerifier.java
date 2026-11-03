package io.github.kotlinmania.build;

import static io.github.kotlinmania.build.AndroidPublicationVerifier.PublicationSupport.require;
import io.github.kotlinmania.build.AndroidPublicationVerifier.PublicationSupport;
import io.github.kotlinmania.build.JniArtifactVerifier.Binary;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Verifies Kotlin classes, both Android JNI ABIs, and 16 KB load segments in a real AAR. */
public final class AndroidNativeArtifactVerifier {
    private AndroidNativeArtifactVerifier() {}

    public static Map<String, Object> verify(Path aar, Path metadataPath, Path pom, String profileName) throws IOException {
        JniArtifactVerifier.Profile profile = JniArtifactVerifier.profile(profileName);
        String digest = PublicationSupport.fingerprint(aar);
        Map<String, byte[]> archive = PublicationSupport.readZip(aar, "Duplicate Android AAR entries");
        require(archive.containsKey("classes.jar"), "Android AAR has no classes.jar");
        Map<String, byte[]> classes = PublicationSupport.readZip(archive.get("classes.jar"), "Duplicate JNI class entries");
        for (String name : profile.classes()) require(classes.containsKey(profile.namespace() + "/internal/" + name + ".class"), "Missing JNI class: " + name);
        Map<String, Object> abis = new LinkedHashMap<>();
        for (String abi : List.of("arm64-v8a", "x86_64")) {
            int machine = abi.equals("arm64-v8a") ? 183 : 62;
            byte[] data = archive.get("jni/" + abi + "/lib" + profile.baseName() + ".so");
            require(data != null, "Missing JNI payload: " + abi);
            Binary.elf(data, machine, abi);
            long program = Binary.i64(data, 32);
            int size = Binary.u16(data, 54);
            int count = Binary.u16(data, 56);
            require(program >= 0 && size >= 56 && program <= data.length - (long) size * count, "Invalid ELF program-header table");
            List<Long> loads = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                int offset = (int) (program + (long) index * size);
                if (Binary.u32(data, offset) == 1) {
                    long alignment = Binary.i64(data, offset + 48);
                    long address = Binary.i64(data, offset + 16);
                    long fileOffset = Binary.i64(data, offset + 8);
                    require(Long.compareUnsigned(alignment, 16384) >= 0 && ((address - fileOffset) & 16383) == 0,
                        abi + ": segment is not 16KB-page compatible");
                    loads.add(alignment);
                }
            }
            require(!loads.isEmpty(), abi + ": no loadable segments");
            require(Binary.contains(data, profile.entryPoint()), abi + ": missing raw JNI ABI entry point");
            abis.put(abi, Map.of("machine", machine, "load_alignments", loads, "sha256", PublicationSupport.hash("sha256", data)));
        }
        Map<String, Object> metadata = PublicationSupport.object(Json.read(metadataPath));
        for (String usage : List.of("java-api", "java-runtime")) {
            List<Map<String, Object>> matches = new ArrayList<>();
            for (Object raw : PublicationSupport.array(metadata.get("variants"))) {
                Map<String, Object> variant = PublicationSupport.object(raw);
                Map<String, Object> attributes = PublicationSupport.object(variant.getOrDefault("attributes", Map.of()));
                if (usage.equals(attributes.get("org.gradle.usage")) && "aar".equals(attributes.get("org.gradle.libraryelements"))) matches.add(variant);
            }
            require(matches.size() == 1, "Missing or ambiguous Android " + usage + " AAR variant");
            Map<String, Object> variant = matches.getFirst();
            Map<String, Object> attributes = PublicationSupport.object(variant.get("attributes"));
            require("androidJvm".equals(attributes.get("org.jetbrains.kotlin.platform.type")), "Incorrect Android platform type");
            require("android".equals(attributes.get("org.gradle.jvm.environment")), "Incorrect Android JVM environment");
            List<Object> files = PublicationSupport.array(variant.get("files"));
            require(!files.isEmpty() && digest.equals(PublicationSupport.object(files.getFirst()).get("sha256")), "Metadata does not describe this actual AAR");
        }
        require("aar".equals(PublicationSupport.childText(PublicationSupport.xml(Files.readAllBytes(pom)), "packaging", null)), "Android POM packaging must be aar");
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("aar", aar.toString()); receipt.put("sha256", digest); receipt.put("abis", abis); receipt.put("component", metadata.get("component"));
        return receipt;
    }

    public static int run(String... args) throws IOException {
        require(args.length == 6 && args[4].equals("--profile"), "Usage: aar metadata pom receipt --profile candle|libc");
        Json.write(Path.of(args[3]), verify(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]), args[5]));
        System.out.println("Actual Android AAR: Kotlin classes, ARM64/x86-64 JNI, 16KB segments, POM and both metadata variants verified");
        return 0;
    }
    public static void main(String[] args) throws IOException { System.exit(run(args)); }
}
