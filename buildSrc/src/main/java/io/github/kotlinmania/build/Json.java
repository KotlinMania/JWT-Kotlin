package io.github.kotlinmania.build;

import groovy.json.JsonOutput;
import groovy.json.JsonSlurperClassic;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/// JSON input and receipt output using Groovy libraries already supplied by Gradle.
///
/// Values retain Groovy's ordinary map, list, scalar and null representations. This class adds
/// single-value checking and UTF-8 file operations, not a schema or a separate JSON grammar.
/// Parsing and serialization failures from Groovy propagate to the caller.
///
/// `NodeFfiBuilder` reads binding settings and writes build receipts through this adapter;
/// `SwiftManifestEnforcer` writes its manifest receipt through it. Standalone tools compile this
/// same source file directly, so there is no second JSON implementation to maintain.
public final class Json {
    private Json() {}

    /// Parses one JSON value using Groovy's classic parser.
    ///
    /// Wrapping the text in an array admits scalar roots as well as objects and arrays. Requiring
    /// one wrapper element rejects empty input and multiple comma-separated root values. Java
    /// string concatenation turns a null argument into the JSON text `null`.
    ///
    /// @param text the JSON source to parse
    /// @return the parsed map, list or scalar value, or null for JSON null
    /// @throws IllegalArgumentException if the parsed wrapper does not contain exactly one value
    /// @throws groovy.json.JsonException if Groovy rejects the JSON syntax
    public static Object parse(String text) {
        Object parsed = new JsonSlurperClassic().parseText("[" + text + "]");
        if (!(parsed instanceof java.util.List<?> values) || values.size() != 1) {
            throw new IllegalArgumentException("Expected exactly one JSON value");
        }
        return values.getFirst();
    }

    /// Reads a UTF-8 file and parses its single JSON value.
    ///
    /// @param path the existing input file
    /// @return the value returned by `parse`, including null for JSON null
    /// @throws IOException if the file cannot be read or decoded as UTF-8
    /// @throws IllegalArgumentException if the input does not contain exactly one root value
    /// @throws groovy.json.JsonException if Groovy rejects the JSON syntax
    public static Object read(Path path) throws IOException {
        return parse(Files.readString(path, StandardCharsets.UTF_8));
    }

    /// Serializes a value to compact JSON using Groovy's conversion rules.
    ///
    /// No files are created. The caller is responsible for selecting serializable values and
    /// interpreting any unchecked serialization failure reported by Groovy.
    ///
    /// @param value the value to serialize; null is represented as JSON null
    /// @return the compact JSON representation
    public static String stringify(Object value) {
        return JsonOutput.toJson(value);
    }

    /// Writes formatted UTF-8 JSON with a trailing newline, creating parent directories.
    ///
    /// An existing file is replaced. This is a direct write, not an atomic replacement or an
    /// append operation; an I/O failure can leave an incomplete file. A receipt describes the
    /// value supplied by its caller and does not independently verify the recorded operation.
    ///
    /// @param path the destination file
    /// @param value the value to serialize, including null
    /// @throws IOException if a directory cannot be created or the file cannot be written
    public static void write(Path path, Object value) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(path, JsonOutput.prettyPrint(stringify(value)) + "\n", StandardCharsets.UTF_8);
    }
}
