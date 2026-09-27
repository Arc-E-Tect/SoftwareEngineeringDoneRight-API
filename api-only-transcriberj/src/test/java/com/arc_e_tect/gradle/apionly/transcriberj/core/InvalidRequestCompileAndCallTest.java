package com.arc_e_tect.gradle.apionly.transcriberj.core;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T13.13 and T19.12: the case classes of every fixture contract compile with {@code -Xlint:all}
 * and no diagnostic but notes, and every case they hold, of every kind, equals, field for field,
 * what the machine-readable report records.
 */
@DisplayName("T13.13, T19.12 Compile and call")
class InvalidRequestCompileAndCallTest {

    @TempDir
    static Path directory;

    static List<ValidValueFixtures.Fixture> fixtures;

    @BeforeAll
    static void generate() {
        fixtures = ContractCaseFixtures.all(directory);
        fixtures.forEach(f -> f.sources().compile());
    }

    @TestFactory
    Stream<DynamicTest> everyCaseIsWhatTheReportRecords() {
        List<DynamicTest> tests = new ArrayList<>();
        for (ValidValueFixtures.Fixture f : fixtures) {
            for (JsonNode entry : f.report().get("contractCases")) {
                String className = entry.get("class").stringValue();
                tests.add(DynamicTest.dynamicTest(f.name() + " " + className, () -> {
                    List<?> cases = (List<?>) f.sources().constant(className, "CASES");
                    assertThat(f.sources().constant(className, "CASE_COUNT")).isEqualTo(cases.size());
                    assertThat(cases).hasSize(entry.get("cases").size());
                    for (int i = 0; i < cases.size(); i++) assertEquivalent(cases.get(i), entry.get("cases").get(i));
                    assertThat(f.sources().type(className).getModifiers() & java.lang.reflect.Modifier.PUBLIC).isNotZero();
                }));
            }
        }
        return tests.stream();
    }

    @TestFactory
    Stream<DynamicTest> everyOperationHasItsCaseClassNamedThroughClassNames() {
        return fixtures.stream().map(f -> DynamicTest.dynamicTest(f.name(), () -> {
            List<String> classes = new ArrayList<>();
            f.report().get("contractCases").forEach(e -> classes.add(e.get("class").stringValue()));
            assertThat(f.sources().names().stream().filter(n -> n.endsWith("ContractCases")).toList())
                    .containsExactlyInAnyOrderElementsOf(classes);
            assertThat(f.sources().names()).contains("ContractCase", "CaseKind");
        }));
    }

    private static void assertEquivalent(Object c, JsonNode recorded) throws Exception {
        assertThat(c.getClass().getSimpleName()).isEqualTo("ContractCase");
        assertThat(component(c, "kind").toString()).isEqualTo(recorded.get("kind").stringValue());
        assertThat(component(c, "requiresState")).isEqualTo(recorded.get("requiresState").booleanValue());
        for (String field : List.of("id", "variant", "description", "in", "name", "pointer", "keyword",
                "responseBodyClass")) {
            JsonNode value = recorded.get(field);
            assertThat(component(c, field)).as(field).isEqualTo(value.isNull() ? null : value.stringValue());
        }
        assertThat(component(c, "expectedStatus")).isEqualTo(recorded.get("expectedStatus").intValue());
        List<String> types = new ArrayList<>();
        recorded.get("expectedContentTypes").forEach(t -> types.add(t.stringValue()));
        assertThat(component(c, "expectedContentTypes")).isEqualTo(types);

        Object request = component(c, "request");
        JsonNode r = recorded.get("request");
        assertThat(request.getClass().getSimpleName()).isEqualTo("ContractRequest");
        assertThat(component(request, "method")).isEqualTo(r.get("method").stringValue());
        assertThat(component(request, "pathTemplate")).isEqualTo(r.get("pathTemplate").stringValue());
        List<String> path = new ArrayList<>();
        r.get("pathParameters").forEach(p -> path.add(p.stringValue()));
        assertThat(component(request, "pathParameters")).isEqualTo(path);
        assertThat(pairs((List<?>) component(request, "query"))).isEqualTo(pairs(r.get("query")));
        assertThat(pairs((List<?>) component(request, "headers"))).isEqualTo(pairs(r.get("headers")));
        JsonNode contentType = r.get("contentType");
        assertThat(component(request, "contentType")).isEqualTo(contentType.isNull() ? null : contentType.stringValue());
        String body = (String) component(request, "body");
        if (r.get("body").isNull() && contentType.isNull()) {
            assertThat(body).isNull();
        } else {
            assertThat(body).endsWith("\n");
            assertThat(Oracle.JSON.readTree(body)).isEqualTo(r.get("body"));
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
        List<String> out = new ArrayList<>();
        pairs.forEach(p -> out.add(p.get("name").stringValue() + "=" + p.get("value").stringValue()));
        return out;
    }
}
