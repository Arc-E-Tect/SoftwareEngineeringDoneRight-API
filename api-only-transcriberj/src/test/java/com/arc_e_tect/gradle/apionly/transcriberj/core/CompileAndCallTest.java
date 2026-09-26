package com.arc_e_tect.gradle.apionly.transcriberj.core;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T12.9: every fixture contract's sources compile with {@code -Xlint:all} and no diagnostic
 * but notes; every {@code requiredBody()}, {@code fullBody()}, {@code requiredRequest()},
 * {@code fullRequest()} and {@code noBodyRequest()} returns what the machine-readable
 * report records, or throws the reason it records.
 */
@DisplayName("T12.9 Compile and call")
class CompileAndCallTest {

    @TempDir
    static Path directory;

    static List<ValidValueFixtures.Fixture> fixtures;

    @BeforeAll
    static void generate() {
        fixtures = ValidValueFixtures.all(directory);
        fixtures.forEach(f -> f.sources().compile());
    }

    @TestFactory
    Stream<DynamicTest> everyBodyMethodReturnsWhatTheReportRecords() {
        List<DynamicTest> tests = new ArrayList<>();
        for (ValidValueFixtures.Fixture f : fixtures) {
            for (JsonNode entry : f.bodies()) {
                String className = entry.get("class").stringValue();
                for (String method : List.of("requiredBody", "fullBody")) {
                    tests.add(DynamicTest.dynamicTest(f.name() + " " + className + "." + method + "()", () -> {
                        JsonNode recorded = entry.get(method);
                        if (recorded.has("value")) {
                            String body = (String) f.sources().call(className, method, new Class<?>[0]);
                            // Compact JSON and a newline, as body(...) writes.
                            assertThat(body).isEqualTo(Oracle.JSON.writeValueAsString(Oracle.JSON.readTree(body)) + "\n");
                            assertThat(Oracle.JSON.readTree(body)).isEqualTo(recorded.get("value"));
                        } else {
                            throwsRecorded(f, className, method, recorded);
                        }
                    }));
                }
            }
        }
        return tests.stream();
    }

    @TestFactory
    Stream<DynamicTest> everyRequestMethodReturnsWhatTheReportRecords() {
        List<DynamicTest> tests = new ArrayList<>();
        for (ValidValueFixtures.Fixture f : fixtures) {
            for (JsonNode entry : f.requests()) {
                String className = entry.get("class").stringValue();
                for (String method : List.of("requiredRequest", "fullRequest", "noBodyRequest")) {
                    JsonNode recorded = entry.get(method);
                    tests.add(DynamicTest.dynamicTest(f.name() + " " + className + "." + method + "()", () -> {
                        if (recorded == null) {
                            assertThat(declares(f, className, method)).as("%s declares %s", className, method)
                                    .isFalse();
                        } else if (recorded.has("value")) {
                            Object request = f.sources().call(className, method, new Class<?>[0]);
                            assertThat(request.getClass().getSimpleName()).isEqualTo("ContractRequest");
                            assertEquivalent(f, className, request, recorded.get("value"));
                        } else {
                            throwsRecorded(f, className, method, recorded);
                        }
                    }));
                }
            }
        }
        return tests.stream();
    }

    private static boolean declares(ValidValueFixtures.Fixture f, String className, String method) {
        for (Method m : f.sources().type(className).getDeclaredMethods()) {
            if (m.getName().equals(method)) return true;
        }
        return false;
    }

    private static void throwsRecorded(ValidValueFixtures.Fixture f, String className, String method,
                                       JsonNode recorded) {
        JsonNode why = recorded.get("unsatisfiable");
        assertThatThrownBy(() -> f.sources().call(className, method, new Class<?>[0]))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining(className + "." + method + "() has no valid value")
                .hasMessageContaining(why.get("reason").stringValue())
                .hasMessageContaining(" at " + why.get("location").stringValue());
    }

    private static void assertEquivalent(ValidValueFixtures.Fixture f, String className, Object request,
                                         JsonNode recorded) throws Exception {
        assertThat(component(request, "method")).isEqualTo(recorded.get("method").stringValue())
                .isEqualTo(f.sources().constant(className, "METHOD"));
        assertThat(component(request, "pathTemplate")).isEqualTo(recorded.get("pathTemplate").stringValue())
                .isEqualTo(f.sources().constant(className, "PATH"));
        assertThat(component(request, "pathParameters")).isEqualTo(ValidValueFixtures.list(
                recorded.get("pathParameters")).stream().map(JsonNode::stringValue).toList());
        assertThat(pairs((List<?>) component(request, "query"))).isEqualTo(pairs(recorded.get("query")));
        assertThat(pairs((List<?>) component(request, "headers"))).isEqualTo(pairs(recorded.get("headers")));
        JsonNode contentType = recorded.get("contentType");
        assertThat(component(request, "contentType")).isEqualTo(contentType.isNull() ? null : contentType.stringValue());
        String body = (String) component(request, "body");
        if (contentType.isNull()) {
            assertThat(body).isNull();
        } else {
            assertThat(body).endsWith("\n");
            assertThat(Oracle.JSON.readTree(body)).isEqualTo(recorded.get("body"));
        }
    }

    private static Object component(Object record, String name) throws Exception {
        for (RecordComponent c : record.getClass().getRecordComponents()) {
            if (c.getName().equals(name)) return c.getAccessor().invoke(record);
        }
        throw new AssertionError(record.getClass() + " has no component " + name);
    }

    private static List<String> pairs(List<?> pairs) throws Exception {
        List<String> out = new ArrayList<>();
        for (Object p : pairs) out.add(component(p, "name") + "=" + component(p, "value"));
        return out;
    }

    private static List<String> pairs(JsonNode pairs) {
        return ValidValueFixtures.list(pairs).stream()
                .map(p -> p.get("name").stringValue() + "=" + p.get("value").stringValue()).toList();
    }
}
