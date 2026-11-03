package io.github.kotlinmania.build;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;

/// Gradle adapter for invoking product helpers in the build's classloader.
///
/// A helper exposes a public static `run(String...)` method. This adapter passes arguments
/// directly, without a shell or a separate JVM. A numeric result whose `intValue()` is nonzero
/// fails the task; other return values are ignored. Helper filesystem, process and network
/// effects happen in the Gradle process's environment.
///
/// The template registers this task type for `NodeFfiBuilder` and calls `invoke` directly when
/// preparing generated Swift manifests. Callers still declare task dependencies and outputs;
/// this adapter only declares the class name and arguments as task inputs. It neither makes a
/// helper cacheable nor supplies a sandbox. Extracted verifiers use the standalone tools launcher.
public abstract class BuildToolTask extends DefaultTask {
    /// Returns the required fully qualified helper class name.
    ///
    /// @return the Gradle input property, normally set from a retained helper's class name
    @Input
    public abstract Property<String> getToolClass();

    /// Returns the ordered argument list passed to the helper.
    ///
    /// @return the optional Gradle input property; an unset value means no arguments at execution
    @Input
    @Optional
    public abstract ListProperty<String> getArguments();

    /// Replaces the argument property with tokens parsed from Gradle's `--args` option.
    ///
    /// Parsing groups quoted text but performs no shell expansion. Prefer setting `arguments`
    /// directly in Kotlin DSL when arguments already exist as separate values.
    ///
    /// @param value the complete option value, after the invoking shell has processed its quotes
    /// @throws GradleException if a single or double quote is left open
    @Option(option = "args", description = "Arguments for the Java build tool")
    public void setCommandArguments(String value) {
        getArguments().set(splitArguments(value));
    }

    /// Invokes the configured helper when Gradle executes the task.
    ///
    /// The required class property must be set. An absent argument property becomes an empty
    /// array. Runtime exceptions and errors from the helper propagate without being hidden.
    ///
    /// @throws GradleException if dispatch fails, the helper returns a nonzero numeric result,
    ///   or it throws a checked exception
    @TaskAction
    public void runTool() {
        invoke(getToolClass().get(), getArguments().getOrElse(List.of()).toArray(String[]::new));
    }

    /// Loads and invokes a helper using this adapter's classloader.
    ///
    /// Class initialization is enabled. The package-prefix check limits naming, not privileges.
    /// Checked helper exceptions are wrapped with their cause; runtime exceptions and errors are
    /// rethrown unchanged. Returning `null` or a nonnumeric object is treated as success.
    ///
    /// @param name the fully qualified class name under `io.github.kotlinmania.build`
    /// @param arguments the argument array for the public static `run(String[])` method
    /// @throws GradleException if the name is outside the package, reflection fails, the numeric
    ///   result is nonzero, or the helper throws a checked exception
    public static void invoke(String name, String... arguments) {
        if (!name.startsWith("io.github.kotlinmania.build.")) {
            throw new GradleException("Unknown build tool: " + name);
        }
        try {
            Object result = Class.forName(name, true, BuildToolTask.class.getClassLoader())
                    .getMethod("run", String[].class).invoke(null, (Object) arguments);
            if (result instanceof Number number && number.intValue() != 0) {
                throw new GradleException(name + " failed with exit code " + number.intValue());
            }
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error failure) throw failure;
            throw new GradleException(name + " failed: " + cause.getMessage(), cause);
        } catch (ReflectiveOperationException error) {
            throw new GradleException("Cannot load Java build tool " + name, error);
        }
    }

    /// Converts argument values to strings and invokes the selected helper.
    ///
    /// @param type the helper class, subject to the same package and method contract as `invoke`
    /// @param arguments values converted with `String.valueOf`; a null element becomes `"null"`
    /// @throws GradleException if helper dispatch or execution fails as described by `invoke`
    public static void invokeValues(Class<?> type, Object... arguments) {
        invoke(type.getName(), java.util.Arrays.stream(arguments).map(String::valueOf).toArray(String[]::new));
    }

    /// Splits an option value while preserving quoted arguments and ordinary backslashes.
    ///
    /// Unquoted whitespace separates values. Single and double quotes group text and are removed;
    /// empty quoted values are retained. Inside double quotes only a quote or backslash can be
    /// backslash-escaped. Elsewhere a backslash is literal, which preserves Windows paths.
    /// Variables, wildcards, backticks and command substitutions are never evaluated.
    ///
    /// @param text the option value to tokenize
    /// @return a new mutable list of arguments in input order
    /// @throws GradleException if a quoted section is not terminated
    public static List<String> splitArguments(String text) {
        List<String> result = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        char quote = 0;
        boolean started = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
                else if (c == '\\' && quote == '"' && i + 1 < text.length()
                        && (text.charAt(i + 1) == '"' || text.charAt(i + 1) == '\\')) {
                    value.append(text.charAt(++i));
                } else value.append(c);
            } else if (c == '\'' || c == '"') {
                quote = c;
                started = true;
            } else if (Character.isWhitespace(c)) {
                if (started) {
                    result.add(value.toString());
                    value.setLength(0);
                    started = false;
                }
            } else {
                value.append(c);
                started = true;
            }
        }
        if (quote != 0) throw new GradleException("Unterminated quoted build-tool argument");
        if (started) result.add(value.toString());
        return result;
    }
}
