package io.github.kotlinmania.build;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlTable;

/** Publishes checked projects locally in dependency order, after their local checks pass. */
public final class LocalMavenPublisher {
    private static final Set<String> EXCLUDED = Set.of("thiserror-kotlin", "serderive-kotlin", "template");
    private static final String POLICY = "local-checks-and-current-artifacts-v2";
    private static final String ATTEMPT_PROPERTY = "kotlinmania.localPublish.attempt";
    private static final String PHASE_PROPERTY = "kotlinmania.localPublish.phase";
    private static final String REPORT_PROPERTY = "kotlinmania.localPublish.report";
    private LocalMavenPublisher() {}

    @FunctionalInterface
    interface CommandRunner {
        int execute(Path project, Path scratch, Path log, List<String> command) throws Exception;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    private static Map<String, Object> object(Object value) {
        require(value instanceof Map<?, ?>, "Expected a JSON object");
        Map<String, Object> result = new LinkedHashMap<>();
        ((Map<?, ?>) value).forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static List<String> names(Object value) {
        require(value instanceof Collection<?>, "Expected a JSON list of projects");
        return ((Collection<?>) value).stream().map(String::valueOf).toList();
    }

    public static void validateArguments(List<String> arguments) {
        for (String argument : arguments) {
            require(argument.startsWith("-"), "Only Gradle options may be passed; task list is fixed");
            require(!argument.equals("-x") && !argument.startsWith("-x") && !argument.startsWith("--exclude-task")
                    && !argument.equals("--dry-run") && !argument.equals("-m")
                    && !argument.toLowerCase().contains("ignorefailures") && !argument.toLowerCase().contains("skiptest"),
                    "The local publisher cannot bypass runnable checks: " + argument);
        }
    }

    private static List<String> command(Path output, Path receipt, String attempt, String task, List<String> extra) {
        List<String> result = new ArrayList<>(List.of(
                System.getProperty("os.name").startsWith("Windows") ? "gradlew.bat" : "./gradlew", task,
                "--continue", "--no-daemon", "--no-configuration-cache", "--console=plain", "--max-workers=2",
                "--init-script", output.resolve("receipt.init.gradle").toString()));
        result.addAll(extra);
        result.add("-D" + ATTEMPT_PROPERTY + "=" + attempt);
        result.add("-D" + PHASE_PROPERTY + "=" + (task.equals("check") ? "check" : "publish"));
        result.add("-D" + REPORT_PROPERTY + "=" + receipt);
        return result;
    }

    static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536]; int length;
            while ((length = input.read(buffer)) != -1) digest.update(buffer, 0, length);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    record ReceiptValidation(int expected, int installed, List<String> failures) {}

    /** Rehashes both sides; an old file, saved hash, or one successful publication is not sufficient. */
    static ReceiptValidation validateReceipt(Object raw, String attempt) {
        List<String> failures = new ArrayList<>();
        int expectedCount = 0, installed = 0;
        try {
            Map<String, Object> receipt = object(raw);
            require(Integer.valueOf(2).equals(receipt.get("schema")) || Long.valueOf(2).equals(receipt.get("schema")), "Unsupported publication receipt schema");
            require(attempt.equals(receipt.get("attempt_id")), "Publication receipt belongs to another attempt");
            require("publish".equals(receipt.get("phase")), "Publication receipt is not from the publish phase");
            Map<String, Map<String, Object>> expected = keyed(receipt.get("expected_publications"), "task");
            Map<String, Map<String, Object>> actual = keyed(receipt.get("publications"), "task");
            expectedCount = expected.size();
            require(!expected.isEmpty(), "No intended local Maven publication tasks were recorded");
            require(expected.keySet().containsAll(actual.keySet()), "Receipt contains unexpected publication tasks");
            for (var entry : expected.entrySet()) {
                String task = entry.getKey();
                try {
                    Map<String, Object> planned = entry.getValue(), publication = actual.get(task);
                    require(publication != null, "Missing publication task receipt");
                    require(Boolean.TRUE.equals(planned.get("in_graph")), "Intended publication task was omitted from the task graph");
                    require(Boolean.TRUE.equals(publication.get("executed")) && Boolean.TRUE.equals(publication.get("did_work"))
                                    && Boolean.FALSE.equals(publication.get("skipped")) && Boolean.TRUE.equals(publication.get("succeeded")),
                            "Publication task did not execute successfully: " + publication.get("skip_message"));
                    require(planned.get("coordinate") instanceof String && planned.get("coordinate").equals(publication.get("coordinate")), "Publication coordinates changed");
                    Map<String, Map<String, Object>> sources = keyed(planned.get("files"), "path");
                    Map<String, Map<String, Object>> files = keyed(publication.get("files"), "path");
                    require(!sources.isEmpty() && sources.values().stream().anyMatch(file -> "pom".equals(file.get("kind"))), "Missing current publication POM manifest");
                    require(sources.keySet().equals(files.keySet()), "Published file set differs from the current artifact/POM/module manifest");
                    for (var sourceEntry : sources.entrySet()) {
                        Map<String, Object> source = sourceEntry.getValue(), file = files.get(sourceEntry.getKey());
                        String hash = String.valueOf(source.get("source_sha256"));
                        require(hash.matches("[0-9a-f]{64}"), "Missing current source SHA256: " + source.get("source"));
                        require(source.get("source") instanceof String && source.get("source").equals(file.get("source")), "Source path changed in publication receipt");
                        require(hash.equals(file.get("source_sha256")) && hash.equals(file.get("installed_sha256")), "Installed artifact differs from current source: " + sourceEntry.getKey());
                        Path input = Path.of(String.valueOf(source.get("source"))), output = Path.of(sourceEntry.getKey());
                        require(Files.isRegularFile(input) && hash.equals(sha256(input)), "Current artifact changed or disappeared: " + input);
                        require(Files.isRegularFile(output) && hash.equals(sha256(output)), "Installed artifact changed or disappeared: " + output);
                    }
                    installed++;
                } catch (Exception error) { failures.add(task + ": " + error.getMessage()); }
            }
        } catch (Exception error) { failures.add(error.getMessage()); }
        return new ReceiptValidation(expectedCount, installed, List.copyOf(failures));
    }

    private static Map<String, Map<String, Object>> keyed(Object raw, String key) {
        require(raw instanceof Collection<?>, "Missing receipt list: " + key);
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Object value : (Collection<?>) raw) {
            Map<String, Object> entry = object(value);
            require(entry.get(key) instanceof String && !String.valueOf(entry.get(key)).isBlank(), "Missing receipt key: " + key);
            require(result.put(String.valueOf(entry.get(key)), entry) == null, "Duplicate receipt key: " + entry.get(key));
        }
        return result;
    }

    private static int execute(Path project, Path scratch, Path log, List<String> command) throws Exception {
        ProcessBuilder process = new ProcessBuilder(command).directory(project.toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile());
        process.environment().putAll(Map.of("TMPDIR", scratch.toString(), "TMP", scratch.toString(), "TEMP", scratch.toString()));
        process.environment().put("JAVA_TOOL_OPTIONS",
                (process.environment().getOrDefault("JAVA_TOOL_OPTIONS", "") + " -Djava.io.tmpdir=" + scratch).trim());
        Process child = process.start();
        try { return child.waitFor(); }
        catch (InterruptedException error) {
            child.descendants().forEach(ProcessHandle::destroy);
            child.destroy(); Thread.currentThread().interrupt(); throw error;
        }
    }

    public static Map<String, Object> publish(Path root, String name, List<String> arguments) throws Exception {
        return publish(root, name, arguments, LocalMavenPublisher::execute);
    }

    /** Gradle shutdown may remove temporary entries while this traversal is running. */
    static List<String> cleanupScratch(Path scratch) {
        List<String> failures = new ArrayList<>();
        try {
            Files.walkFileTree(scratch, new SimpleFileVisitor<>() {
                private void record(Path path, Exception error) {
                    if (!(error instanceof NoSuchFileException)) failures.add(path + ": " + error);
                }

                private void remove(Path path) {
                    try { Files.deleteIfExists(path); }
                    catch (IOException | SecurityException error) { record(path, error); }
                }

                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    remove(file); return FileVisitResult.CONTINUE;
                }

                @Override public FileVisitResult visitFileFailed(Path file, IOException error) {
                    record(file, error); return FileVisitResult.CONTINUE;
                }

                @Override public FileVisitResult postVisitDirectory(Path directory, IOException error) {
                    if (error != null) record(directory, error);
                    remove(directory); return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | SecurityException error) {
            if (!(error instanceof NoSuchFileException)) failures.add(scratch + ": " + error);
        }
        return List.copyOf(failures);
    }

    static Map<String, Object> publish(Path root, String name, List<String> arguments, CommandRunner runner) throws Exception {
        require(!EXCLUDED.contains(name) && name.matches("[A-Za-z0-9_.-]+") && !name.equals(".."), "Project outside publish scope: " + name);
        validateArguments(arguments);
        Path project = root.resolve(name).toRealPath();
        require(project.getParent().equals(root.toRealPath()), "Project resolves outside workspace: " + project);
        Path output = root.resolve("tools/local-maven-publish");
        Path reports = project.resolve("build/reports/local-maven-publish");
        Path temporary = project.resolve("build/tmp/local-maven-publish");
        Files.createDirectories(reports); Files.createDirectories(temporary);
        Path scratch = Files.createTempDirectory(temporary, "attempt-");
        int attempt = 1;
        while (Files.exists(reports.resolve("result-" + attempt + ".json")) || Files.exists(reports.resolve("publish-" + attempt + ".log"))) attempt++;
        Path receipt = reports.resolve("publications-" + attempt + ".json");
        String attemptId = UUID.randomUUID().toString();
        Json.write(receipt, Map.of());
        Path checkLog = reports.resolve("check-" + attempt + ".log"), publishLog = reports.resolve("publish-" + attempt + ".log");
        Instant started = Instant.now();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("repo", name); result.put("attempt", attempt); result.put("policy", POLICY); result.put("attempt_id", attemptId);
        result.put("receipt", root.relativize(receipt).toString());
        result.put("started_at", started.toString());
        System.out.println("START " + name);
        try {
            List<String> checkCommand = command(output, receipt, attemptId, "check", arguments);
            int checkExit = runner.execute(project, scratch, checkLog, checkCommand);
            result.put("check_command", checkCommand); result.put("check_exit_code", checkExit);
            result.put("check_log", root.relativize(checkLog).toString());
            int exit = checkExit;
            String content = Files.readString(checkLog);
            if (checkExit == 0) {
                Json.write(receipt, Map.of());
                List<String> publishCommand = command(output, receipt, attemptId, "publishToMavenLocal", arguments);
                result.put("command", publishCommand);
                exit = runner.execute(project, scratch, publishLog, publishCommand);
                result.put("log", root.relativize(publishLog).toString());
                content += "\n" + Files.readString(publishLog);
            } else result.put("publication_skipped", "Local check task failed");
            Object receipts = Files.exists(receipt) ? Json.read(receipt) : Map.of();
            ReceiptValidation validation = checkExit == 0 ? validateReceipt(receipts, attemptId) : new ReceiptValidation(0, 0, List.of());
            result.put("exit_code", exit); result.put("publication_receipt", receipts);
            result.put("publications", object(receipts).getOrDefault("publications", List.of()));
            result.put("publications_expected", validation.expected()); result.put("publications_installed", validation.installed());
            result.put("receipt_failures", validation.failures());
            result.put("passed", checkExit == 0 && exit == 0 && validation.failures().isEmpty() && validation.expected() > 0 && validation.installed() == validation.expected());
            result.put("status", Boolean.TRUE.equals(result.get("passed")) ? "passed" : "failed");
            result.put("failed_tasks", Pattern.compile("^> Task (\\S+) FAILED$", Pattern.MULTILINE).matcher(content).results().map(m -> m.group(1)).toList());
            if (exit != 0) result.put("failure_reasons", Pattern.compile("\\* What went wrong:\\n(.*?)(?=\\n\\* Try:|\\z)", Pattern.DOTALL)
                    .matcher(content).results().map(m -> m.group(1).strip()).map(s -> s.substring(0, Math.min(s.length(), 4000))).toList());
        } catch (Exception error) {
            result.put("passed", false); result.put("status", "failed"); result.put("runner_error", error.toString());
            if (error instanceof InterruptedException) throw error;
        } finally {
            result.put("seconds", (System.currentTimeMillis() - started.toEpochMilli()) / 1000.0);
            List<String> cleanupFailures = cleanupScratch(scratch);
            if (!cleanupFailures.isEmpty()) {
                result.put("cleanup_failures", cleanupFailures);
                System.out.println("CLEANUP " + name + ": " + String.join("; ", cleanupFailures));
            }
            Json.write(reports.resolve("result-" + attempt + ".json"), result);
        }
        System.out.println((Boolean.TRUE.equals(result.get("passed")) ? "PASS " : "FAIL ") + name + " (" + result.get("publications_installed") + " publications)");
        return result;
    }

    public static int run(String... arguments) throws Exception {
        return run(LocalMavenPublisher::execute, arguments);
    }

    static int run(CommandRunner runner, String... arguments) throws Exception {
        Path root = Path.of("..").toAbsolutePath().normalize();
        int jobs = 2;
        List<String> selected = new ArrayList<>(), extra = new ArrayList<>();
        for (int i = 0; i < arguments.length; i++) {
            switch (arguments[i]) {
                case "--root" -> root = Path.of(arguments[++i]).toAbsolutePath().normalize();
                case "--jobs" -> jobs = Integer.parseInt(arguments[++i]);
                case "--repos" -> { while (i + 1 < arguments.length && !arguments[i + 1].startsWith("--")) selected.add(arguments[++i]); }
                case "--gradle-argument" -> extra.add(arguments[++i]);
                // Retained for callers of the old launcher. Every selected project now reruns.
                case "--retry-passed" -> { }
                default -> throw new IllegalArgumentException("Unknown option: " + arguments[i]);
            }
        }
        require(jobs > 0 && jobs <= 32, "--jobs must be 1..32"); validateArguments(extra);
        Path output = root.resolve("tools/local-maven-publish");
        Set<String> allowed = new HashSet<>();
        List<?> inventory = (List<?>) Json.read(output.resolve("inventory.json"));
        for (Object entry : inventory) allowed.add(String.valueOf(object(entry).get("repo")));
        if (selected.isEmpty()) selected.addAll(names(Json.read(output.resolve("order.json"))));
        require(allowed.containsAll(selected) && selected.stream().noneMatch(EXCLUDED::contains), "Requested project outside reviewed inventory");
        require(selected.size() == new HashSet<>(selected).size(), "Duplicate project selection");
        Map<String, Object> results = Files.exists(output.resolve("results.json")) ? object(Json.read(output.resolve("results.json"))) : new LinkedHashMap<>();
        Map<String, Object> graph = object(Json.read(output.resolve("dependency-order.json")));
        Map<String, Object> graphDependencies = object(graph.get("dependencies"));
        Map<String, Set<String>> required = new LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> dependencyAudit = new LinkedHashMap<>();
        Map<String, List<String>> unconfirmed = new LinkedHashMap<>();
        for (String name : selected) {
            Set<String> dependencies = new HashSet<>(names(graphDependencies.getOrDefault(name, List.of())));
            dependencies.retainAll(selected);
            Set<String> current = new LinkedHashSet<>(); List<Map<String, Object>> audit = new ArrayList<>();
            for (String producer : dependencies.stream().sorted().toList()) {
                List<Map<String, String>> evidence;
                try { evidence = edgeEvidence(root, name, producer, inventory, extra); }
                catch (Exception error) { evidence = List.of(Map.of("error", error.toString())); }
                boolean known = !evidence.isEmpty() && evidence.stream().allMatch(item -> exactVersion(item.get("requested_version")) && exactVersion(item.get("producer_version")));
                boolean sameVersion = known && evidence.stream().anyMatch(item -> item.get("requested_version").equals(item.get("producer_version")));
                String disposition = !known ? "unconfirmed" : sameVersion ? "current_producer" : "external_pinned_version";
                audit.add(Map.of("consumer", name, "producer", producer, "disposition", disposition, "evidence", evidence));
                if (sameVersion) current.add(producer);
                if (!known) unconfirmed.computeIfAbsent(name, ignored -> new ArrayList<>()).add(producer);
            }
            required.put(name, current); dependencyAudit.put(name, audit);
        }
        Set<String> successful = new HashSet<>(), unsuccessful = new HashSet<>();
        Set<String> remaining = new LinkedHashSet<>(selected);
        for (var entry : unconfirmed.entrySet()) {
            String name = entry.getKey();
            Map<String, Object> blocked = blocked(name, "blocked_dependency_graph", "Historical graph dependency or exact requested/current version could not be confirmed; review the graph instead of assuming a current-producer edge", results.get(name));
            blocked.put("unconfirmed_dependencies", entry.getValue()); blocked.put("dependency_audit", dependencyAudit.get(name));
            results.put(name, blocked); unsuccessful.add(name); remaining.remove(name);
        }
        for (List<String> cycle : cycles(required)) {
            List<Map<String, Object>> edges = new ArrayList<>();
            for (String consumer : cycle) for (String producer : required.get(consumer).stream().sorted().toList()) if (cycle.contains(producer)) {
                edges.add(dependencyAudit.get(consumer).stream().filter(edge -> producer.equals(edge.get("producer"))).findFirst().orElseThrow());
            }
            for (String name : cycle) {
                Map<String, Object> blocked = blocked(name, "blocked_cycle", "Unresolved dependency cycle: checks and publication require current peer artifacts; no bootstrap or test bypass is permitted", results.get(name));
                blocked.put("cycle_members", cycle); blocked.put("cycle_edges", edges); blocked.put("cycle_edges_verified", true);
                blocked.put("dependency_audit", dependencyAudit.get(name));
                results.put(name, blocked); unsuccessful.add(name); remaining.remove(name);
            }
        }
        Json.write(output.resolve("results.json"), results);
        final Path workspace = root; final List<String> gradleArguments = List.copyOf(extra);
        try (var pool = Executors.newFixedThreadPool(jobs)) {
            var completion = new ExecutorCompletionService<Map<String, Object>>(pool);
            int pending = 0;
            while (!remaining.isEmpty() || pending != 0) {
                for (String name : List.copyOf(remaining)) {
                    List<String> failed = required.get(name).stream().filter(unsuccessful::contains).sorted().toList();
                    if (!failed.isEmpty()) {
                        Map<String, Object> blocked = blocked(name, "blocked_dependency", "Selected producer did not pass checks and publish current artifacts", results.get(name));
                        blocked.put("failed_dependencies", failed); blocked.put("dependency_audit", dependencyAudit.get(name));
                        results.put(name, blocked); unsuccessful.add(name); remaining.remove(name);
                        Json.write(output.resolve("results.json"), results);
                    } else if (pending < jobs && successful.containsAll(required.get(name))) {
                        completion.submit(() -> {
                            try { return publish(workspace, name, gradleArguments, runner); }
                            catch (Exception error) {
                                if (error instanceof InterruptedException) throw error;
                                Map<String, Object> failedResult = blocked(name, "failed", "Publication runner failed before completing this project", null);
                                failedResult.put("runner_error", error.toString()); return failedResult;
                            }
                        });
                        remaining.remove(name); pending++;
                    }
                }
                if (pending == 0 && !remaining.isEmpty() && remaining.stream().anyMatch(name -> required.get(name).stream().anyMatch(unsuccessful::contains))) continue;
                if (pending == 0 && remaining.isEmpty()) break;
                require(pending > 0, "Dependency order has no ready project");
                Map<String, Object> result = completion.take().get(); pending--;
                String name = String.valueOf(result.get("repo"));
                result.put("dependency_audit", dependencyAudit.get(name));
                (Boolean.TRUE.equals(result.get("passed")) ? successful : unsuccessful).add(name); results.put(name, result);
                Json.write(output.resolve("results.json"), results);
            }
        }
        long passed = selected.stream().filter(name -> Boolean.TRUE.equals(object(results.get(name)).get("passed"))).count();
        System.out.println("DONE " + passed + "/" + selected.size() + " selected projects passed checks and published");
        return passed == selected.size() ? 0 : 1;
    }

    private static Map<String, Object> blocked(String name, String status, String reason, Object previous) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("repo", name); result.put("policy", POLICY); result.put("passed", false); result.put("status", status);
        result.put("started_at", Instant.now().toString()); result.put("publication_skipped", reason); result.put("checks_executed", false);
        result.put("publications_installed", 0);
        if (previous instanceof Map<?, ?> old && (old.get("check_exit_code") instanceof Number code && code.intValue() != 0)) {
            Map<String, Object> failure = new LinkedHashMap<>();
            for (String field : List.of("started_at", "attempt", "check_exit_code", "check_log", "failed_tasks", "failure_reasons")) if (old.containsKey(field)) failure.put(field, old.get(field));
            result.put("prior_check_failure", failure);
        } else if (previous instanceof Map<?, ?> old && old.containsKey("prior_check_failure")) result.put("prior_check_failure", old.get("prior_check_failure"));
        System.out.println("BLOCK " + name + ": " + reason);
        return result;
    }

    /** Recompute components from the selected graph; saved component labels may be stale. */
    static List<List<String>> cycles(Map<String, Set<String>> graph) {
        List<List<String>> result = new ArrayList<>(); Set<String> assigned = new HashSet<>();
        for (String start : graph.keySet()) if (!assigned.contains(start)) {
            Set<String> forward = reachable(graph, start);
            List<String> component = forward.stream().filter(node -> reachable(graph, node).contains(start)).sorted().toList();
            assigned.addAll(component);
            if (component.size() > 1 || graph.get(start).contains(start)) result.add(component);
        }
        return result;
    }

    private static Set<String> reachable(Map<String, Set<String>> graph, String start) {
        Set<String> seen = new LinkedHashSet<>(); List<String> queue = new ArrayList<>(); queue.add(start);
        for (int i = 0; i < queue.size(); i++) if (seen.add(queue.get(i))) queue.addAll(graph.getOrDefault(queue.get(i), Set.of()));
        return seen;
    }

    private static boolean exactVersion(String value) {
        return value != null && value.matches("[0-9][A-Za-z0-9_.-]*");
    }

    private static Properties projectProperties(Path project, List<String> extra) throws Exception {
        Properties properties = new Properties(); Path path = project.resolve("gradle.properties");
        if (Files.isRegularFile(path)) try (var reader = Files.newBufferedReader(path)) { properties.load(reader); }
        for (String option : extra) {
            String property = option.startsWith("-P") ? option.substring(2) : option.startsWith("-Dorg.gradle.project.") ? option.substring(21) : "";
            int equals = property.indexOf('='); if (equals > 0) properties.setProperty(property.substring(0, equals), property.substring(equals + 1));
        }
        return properties;
    }

    private static String producerVersion(Path project, List<String> extra) throws Exception {
        String property = projectProperties(project, extra).getProperty("project.version", "").trim();
        if (!property.isEmpty()) return property;
        for (String name : List.of("build.gradle.kts", "build.gradle")) if (Files.isRegularFile(project.resolve(name))) {
            var matcher = Pattern.compile("(?m)^\\s*version\\s*=\\s*[\"']([^\"']+)[\"']").matcher(Files.readString(project.resolve(name)));
            if (matcher.find()) return matcher.group(1);
        }
        return "";
    }

    private static String libraryVersion(Object value, TomlTable catalog) {
        if (value instanceof String text) { String[] parts = text.split(":"); return parts.length >= 3 ? parts[2] : ""; }
        if (!(value instanceof TomlTable table)) return "";
        Object version = table.get("version");
        if (version instanceof TomlTable reference && reference.contains("ref")) {
            TomlTable versions = catalog.getTable("versions"); version = versions == null ? null : versions.get(reference.getString("ref"));
        }
        if (version instanceof String text) return text;
        if (version instanceof TomlTable constraint) {
            for (String key : List.of("strictly", "require")) if (constraint.get(key) instanceof String text) return text;
        }
        return "";
    }

    /** Cycle evidence comes from selected bundles/direct build references, never unused library aliases. */
    static List<Map<String, String>> edgeEvidence(Path root, String consumer, String producer, List<?> inventory, List<String> extra) throws Exception {
        Path project = root.resolve(consumer); Properties properties = projectProperties(project, extra);
        String producerVersion = producerVersion(root.resolve(producer), extra);
        Properties producerProperties = projectProperties(root.resolve(producer), extra);
        String producerSource = "";
        for (String build : List.of("build.gradle.kts", "build.gradle")) if (Files.isRegularFile(root.resolve(producer).resolve(build))) producerSource += Files.readString(root.resolve(producer).resolve(build)) + "\n";
        String currentGroup = producerProperties.getProperty("project.group", "").trim();
        var groupLiteral = Pattern.compile("(?m)^\\s*group\\s*=\\s*[\"']([^\"']+)[\"']").matcher(producerSource);
        if (currentGroup.isEmpty() && groupLiteral.find()) currentGroup = groupLiteral.group(1);
        Set<String> modules = new HashSet<>();
        for (Object item : inventory) {
            Map<String, Object> row = object(item); if (!producer.equals(row.get("repo"))) continue;
            String group = currentGroup.isEmpty() ? String.valueOf(row.getOrDefault("group", "")) : currentGroup;
            modules.add(group + ":" + producerProperties.getProperty("project.name", String.valueOf(row.getOrDefault("name", producer)))); modules.add(group + ":" + producer);
            var artifactIds = Pattern.compile("\\bartifactId\\s*=\\s*[\"']([^\"']+)[\"']").matcher(producerSource);
            while (artifactIds.find()) modules.add(group + ":" + artifactIds.group(1));
            for (String include : names(row.getOrDefault("modules", List.of()))) {
                var matcher = Pattern.compile("[\"']:(?:[^\"']*:)?([^:\"']+)[\"']").matcher(include);
                while (matcher.find()) modules.add(group + ":" + matcher.group(1));
            }
        }
        Path catalogFile = project.resolve("gradle/libs.versions.toml");
        TomlTable catalog = null;
        if (Files.isRegularFile(catalogFile)) { var parsed = Toml.parse(catalogFile); require(!parsed.hasErrors(), "Invalid catalog for cycle evidence: " + catalogFile); catalog = parsed; }
        TomlTable libraries = catalog == null ? null : catalog.getTable("libraries"), bundles = catalog == null ? null : catalog.getTable("bundles");
        List<Map<String, String>> result = new ArrayList<>();
        List<Path> builds = new ArrayList<>();
        for (String file : List.of("build.gradle.kts", "build.gradle")) if (Files.isRegularFile(project.resolve(file))) builds.add(project.resolve(file));
        Path settings = project.resolve("settings.gradle.kts");
        if (Files.isRegularFile(settings)) {
            var includes = Pattern.compile("include\\s*\\(\\s*[\"'](:[^\"']+)[\"']\\s*\\)").matcher(Files.readString(settings));
            while (includes.find()) for (String file : List.of("build.gradle.kts", "build.gradle")) {
                Path child = project.resolve(includes.group(1).substring(1).replace(':', '/')).resolve(file).normalize();
                if (child.startsWith(project) && Files.isRegularFile(child)) builds.add(child);
            }
        }
        for (Path build : builds) {
            String source = Files.readString(build);
            for (String module : modules) {
                var matcher = Pattern.compile("[\"']" + Pattern.quote(module) + ":([^:\"']+)(?::[^\"']+)?[\"']").matcher(source);
                while (matcher.find()) result.add(Map.of("build", root.relativize(build).toString(), "build_sha256", sha256(build), "declaration", module,
                        "requested_version", matcher.group(1), "producer_version", producerVersion));
            }
            if (libraries == null) continue;
            Set<String> selectedAliases = new LinkedHashSet<>(); Map<String, String> origin = new HashMap<>();
            if (bundles != null) for (String alias : bundles.keySet()) {
                String selectedBy = null;
                for (String feature : List.of("commonMain", "commonMainApi", "commonTest", "commonBenchmark")) {
                    String property = "project.dependencies." + feature + "Bundle";
                    if (alias.equals(properties.getProperty(property, "").trim()) && source.contains(feature + "DependencyBundle")
                            && (!feature.equals("commonBenchmark") || !properties.getProperty("project.benchmark.targets", "").isBlank())) selectedBy = property;
                }
                String accessor = alias.replace('-', '.').replace('_', '.');
                if (source.contains("libs.bundles." + accessor) || source.contains("findBundle(\"" + alias + "\")")) selectedBy = "direct bundle reference";
                if (selectedBy != null) {
                    TomlArray values = bundles.getArray(alias);
                    for (int i = 0; values != null && i < values.size(); i++) { String library = values.getString(i); selectedAliases.add(library); origin.put(library, selectedBy + "=" + alias); }
                }
            }
            for (String alias : libraries.keySet()) {
                String accessor = alias.replace('-', '.').replace('_', '.');
                if (Pattern.compile("\\blibs\\." + Pattern.quote(accessor) + "(?![A-Za-z0-9_.])").matcher(source).find() || source.contains("findLibrary(\"" + alias + "\")")) {
                    selectedAliases.add(alias); origin.put(alias, "direct library reference");
                }
            }
            for (String alias : selectedAliases) {
                Object value = libraries.get(alias); String module = "";
                if (value instanceof String text) { String[] parts = text.split(":"); if (parts.length >= 2) module = parts[0] + ":" + parts[1]; }
                else if (value instanceof TomlTable table) module = table.contains("module") ? table.getString("module") : table.getString("group") + ":" + table.getString("name");
                if (modules.contains(module)) result.add(Map.of("build", root.relativize(build).toString(), "build_sha256", sha256(build), "catalog", root.relativize(catalogFile).toString(), "catalog_sha256", sha256(catalogFile), "selection", origin.get(alias), "library", alias, "module", module,
                        "requested_version", libraryVersion(value, catalog), "producer_version", producerVersion));
            }
        }
        return List.copyOf(result);
    }

    public static void main(String[] args) throws Exception { System.exit(run(args)); }
}
