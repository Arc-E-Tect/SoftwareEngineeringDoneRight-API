package com.arc_e_tect.gradle.apionly.transcriberj.fixtures;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The recorded {@code body(...)} calls in {@code fixtures/handwritten/bodies.json},
 * and the means to replay one against the classes in a given package.
 */
public final class BodyFixtures {

    public static final String HANDWRITTEN_PACKAGE = "com.arc_e_tect.book.sedr.schema";
    static final Path FILE = Path.of("src/test/resources/fixtures/handwritten/bodies.json");

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private BodyFixtures() {
    }

    /** One recorded call. {@code expected} is {@code null} until recorded. */
    public record Case(String id, String className, String method, JsonNode args, String comparison, String expected) {

        public String replay(String packageName) {
            return replay(packageName, getClass().getClassLoader());
        }

        /** Replays this call against the classes of a package, as a class loader has them. */
        public String replay(String packageName, ClassLoader loader) {
            try {
                Class<?> type = Class.forName(packageName + "." + className, true, loader);
                Method target = method(type);
                target.setAccessible(true);
                Object[] values = new Object[args.size()];
                for (int i = 0; i < values.length; i++) {
                    values[i] = convert(args.get(i), target.getParameterTypes()[i]);
                }
                return (String) target.invoke(null, values);
            } catch (ClassNotFoundException | IllegalAccessException e) {
                throw new IllegalStateException(id + ": " + e.getMessage(), e);
            } catch (InvocationTargetException e) {
                throw new IllegalStateException(id + ": body(...) threw", e.getCause());
            }
        }

        private Method method(Class<?> type) {
            return Arrays.stream(type.getDeclaredMethods())
                    .filter(m -> m.getName().equals(method) && Modifier.isStatic(m.getModifiers()))
                    .filter(m -> m.getParameterCount() == args.size())
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            id + ": no static " + method + " with " + args.size() + " parameters on " + type.getName()));
        }

        private static Object convert(JsonNode value, Class<?> parameterType) {
            if (parameterType == String.class) {
                return value.textValue();
            }
            if (parameterType == int.class) {
                return value.intValue();
            }
            if (parameterType == List.class) {
                List<String> items = new ArrayList<>();
                value.forEach(item -> items.add(item.textValue()));
                return List.copyOf(items);
            }
            throw new IllegalStateException("No fixture conversion for parameter type " + parameterType.getName());
        }
    }

    public static ObjectNode read() {
        try {
            return (ObjectNode) JSON.readTree(Files.readString(FILE, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static List<Case> cases(ObjectNode document) {
        List<Case> cases = new ArrayList<>();
        for (JsonNode c : document.withArray("cases")) {
            JsonNode expected = c.get("expected");
            cases.add(new Case(
                    c.get("id").textValue(),
                    c.get("class").textValue(),
                    c.get("method").textValue(),
                    c.get("args"),
                    c.get("comparison").textValue(),
                    expected == null || expected.isNull() ? null : expected.textValue()));
        }
        return cases;
    }

    static void write(ObjectNode document, Path file) {
        try {
            Files.writeString(file, JSON.writeValueAsString(document) + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
