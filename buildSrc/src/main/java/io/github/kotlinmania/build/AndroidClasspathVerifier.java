package io.github.kotlinmania.build;

import io.github.kotlinmania.build.AndroidPublicationVerifier.PublicationSupport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Detects duplicate classes independently in each resolved Android classpath. */
public final class AndroidClasspathVerifier {
    private AndroidClasspathVerifier() {}

    public static Map<String, List<String>> duplicates(Collection<Path> paths) throws IOException {
        Set<Path> unique = new TreeSet<>();
        for (Path path : paths) {
            unique.add(path.toRealPath());
        }
        Map<String, List<String>> owners = new TreeMap<>();
        for (Path path : unique) {
            if (path.toString().endsWith(".aar")) {
                for (PublicationSupport.ArchiveEntry entry : PublicationSupport.zipEntries(Files.readAllBytes(path))) {
                    String name = entry.name();
                    if (name.equals("classes.jar") || name.startsWith("libs/") && name.endsWith(".jar")) {
                        collectClasses(path + "!/" + name, entry.bytes(), owners);
                    }
                }
            } else if (path.toString().endsWith(".jar")) {
                collectClasses(path.toString(), Files.readAllBytes(path), owners);
            } else {
                throw new IllegalArgumentException("Unsupported Android classpath artifact: " + path);
            }
        }
        Map<String, List<String>> found = new TreeMap<>();
        owners.forEach((name, sources) -> {
            if (sources.size() > 1) {
                found.put(name, List.copyOf(sources));
            }
        });
        return found;
    }

    private static void collectClasses(String owner, byte[] bytes, Map<String, List<String>> owners) throws IOException {
        for (PublicationSupport.ArchiveEntry entry : PublicationSupport.zipEntries(bytes)) {
            String name = entry.name();
            if (name.endsWith(".class") && !name.endsWith("module-info.class")) {
                owners.computeIfAbsent(name, ignored -> new ArrayList<>()).add(owner);
            }
        }
    }

    public static Map<String, Map<String, List<String>>> inspectConfigurations(
        Map<String, ? extends Collection<Path>> configurations
    ) throws IOException {
        PublicationSupport.require(
            !configurations.isEmpty() && configurations.values().stream().noneMatch(Collection::isEmpty),
            "Each resolved configuration must contain classpath artifacts");
        Map<String, Map<String, List<String>>> found = new LinkedHashMap<>();
        for (Map.Entry<String, ? extends Collection<Path>> configuration : configurations.entrySet()) {
            found.put(configuration.getKey(), duplicates(configuration.getValue()));
        }
        return found;
    }

    public static int run(String... args) throws IOException {
        PublicationSupport.Options options = PublicationSupport.Options.parse(args, Set.of("receipt"), Set.of("configurations"));
        Path receiptPath = Path.of(options.one("receipt"));
        Map<String, Object> receipt;
        try {
            Map<String, List<Path>> configurations = new LinkedHashMap<>();
            Map<String, List<String>> artifactNames = new LinkedHashMap<>();
            if (options.flag("configurations")) {
                Map<String, Object> input = PublicationSupport.object(Json.read(options.path()));
                for (Map.Entry<String, Object> entry : input.entrySet()) {
                    List<String> names = new ArrayList<>();
                    for (Object value : PublicationSupport.array(entry.getValue())) {
                        PublicationSupport.require(value instanceof String, "Classpath artifact path must be a string");
                        names.add((String) value);
                    }
                    artifactNames.put(entry.getKey(), names);
                }
            } else {
                artifactNames.put("classpath", Files.readAllLines(options.path()).stream().map(String::trim).filter(line -> !line.isEmpty()).toList());
            }
            artifactNames.forEach((name, values) -> configurations.put(name, values.stream().map(Path::of).toList()));
            Map<String, Map<String, List<String>>> found = inspectConfigurations(configurations);
            boolean failed = found.values().stream().anyMatch(value -> !value.isEmpty());
            receipt = Map.of(
                "status", failed ? "failed" : "passed",
                "artifacts_by_configuration", artifactNames,
                "duplicates_by_configuration", found);
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
}
