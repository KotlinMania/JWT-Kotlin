package io.github.kotlinmania.build;

import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;

/** Command-line entry point for the same Java tools imported by Gradle builds. */
public final class BuildToolMain {
    private BuildToolMain() {}

    public static void main(String[] arguments) throws Exception {
        if (arguments.length == 0 || !arguments[0].matches("[A-Z][A-Za-z0-9]+")) {
            throw new IllegalArgumentException("Expected a Java build-tool class followed by its arguments");
        }
        Class<?> tool = Class.forName("io.github.kotlinmania.build." + arguments[0]);
        try {
            Object result = tool.getMethod("run", String[].class)
                    .invoke(null, (Object) Arrays.copyOfRange(arguments, 1, arguments.length));
            if (result instanceof Number number && number.intValue() != 0) {
                throw new IllegalStateException(arguments[0] + " failed with exit code " + number.intValue());
            }
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof Exception cause) throw cause;
            if (error.getCause() instanceof Error cause) throw cause;
            throw error;
        }
    }
}
