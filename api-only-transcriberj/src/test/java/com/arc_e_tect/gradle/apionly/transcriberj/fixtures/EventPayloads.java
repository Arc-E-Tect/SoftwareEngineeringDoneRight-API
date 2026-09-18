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
 * The recorded payloads of the reference implementation's hand-written event classes,
 * and the means to replay one.
 *
 * <p>An event class is a record with a canonical constructor and a {@code toPayload()},
 * rather than the static {@code body(...)} the HTTP schema classes have, so it is
 * recorded on its own terms: the constructor's arguments in, the payload out.
 */
public final class EventPayloads {

    /** Where the hand-written event classes live in this build. */
    public static final String HANDWRITTEN_PACKAGE = "com.arc_e_tect.book.sedr.events";

    static final Path FILE = Path.of("src/test/resources/fixtures/handwritten/event-payloads.json");

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private EventPayloads() {
    }

    /** One recorded event. {@code expected} is {@code null} until recorded. */
    public record Case(String id, String className, JsonNode args, String expected) {

        /** The payload the hand-written record produces from these arguments. */
        public String handwritten() {
            try {
                Class<?> type = Class.forName(HANDWRITTEN_PACKAGE + "." + className);
                Object event = constructor(type).newInstance(values(constructor(type).getParameterTypes()));
                Method toPayload = type.getMethod("toPayload");
                return (String) toPayload.invoke(event);
            } catch (ClassNotFoundException | IllegalAccessException | InstantiationException
                     | NoSuchMethodException e) {
                throw new IllegalStateException(id + ": " + e.getMessage(), e);
            } catch (InvocationTargetException e) {
                throw new IllegalStateException(id + ": the event threw", e.getCause());
            }
        }

        /** The body the generated class produces from the same arguments, as a class loader has it. */
        public String generated(String packageName, ClassLoader loader) {
            try {
                Class<?> type = Class.forName(packageName + "." + className, true, loader);
                Method body = Arrays.stream(type.getDeclaredMethods())
                        .filter(m -> m.getName().equals("body") && Modifier.isStatic(m.getModifiers())
                                && m.getParameterCount() == args.size())
                        .findFirst().orElseThrow(() -> new IllegalStateException(
                                id + ": no generated body(...) takes " + args.size() + " arguments"));
                body.setAccessible(true);
                return (String) body.invoke(null, values(body.getParameterTypes()));
            } catch (ClassNotFoundException | IllegalAccessException e) {
                throw new IllegalStateException(id + ": " + e.getMessage(), e);
            } catch (InvocationTargetException e) {
                throw new IllegalStateException(id + ": body(...) threw", e.getCause());
            }
        }

        private java.lang.reflect.Constructor<?> constructor(Class<?> type) {
            return Arrays.stream(type.getDeclaredConstructors())
                    .filter(c -> c.getParameterCount() == args.size())
                    .findFirst().orElseThrow(() -> new IllegalStateException(
                            id + ": no constructor takes " + args.size() + " arguments"));
        }

        private Object[] values(Class<?>[] types) {
            Object[] values = new Object[args.size()];
            for (int i = 0; i < values.length; i++) {
                String text = args.get(i).asText();
                values[i] = types[i] == java.time.Instant.class ? java.time.Instant.parse(text) : text;
            }
            return values;
        }
    }

    static ObjectNode read() {
        try {
            return (ObjectNode) JSON.readTree(Files.readString(FILE, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void write(ObjectNode document, Path file) {
        try {
            Files.writeString(file, JSON.writeValueAsString(document) + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Every recorded case of a document. */
    public static List<Case> cases(ObjectNode document) {
        List<Case> cases = new ArrayList<>();
        for (JsonNode node : document.withArray("cases")) {
            cases.add(new Case(node.get("id").asText(), node.get("class").asText(), node.get("args"),
                    node.hasNonNull("expected") ? node.get("expected").asText() : null));
        }
        return cases;
    }

    /** Every recorded case. */
    public static List<Case> cases() {
        return cases(read());
    }
}
