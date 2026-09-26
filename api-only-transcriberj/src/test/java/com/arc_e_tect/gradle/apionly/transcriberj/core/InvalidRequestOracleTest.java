package com.arc_e_tect.gradle.apionly.transcriberj.core;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T13.1: every case of every fixture contract has exactly one fault, as an independent
 * validator sees it -- the case's keyword at the case's pointer -- and its valid baseline
 * has none. With the one changed value restored, the request is the baseline again: the
 * fault is the only difference.
 */
@DisplayName("T13.1 The single-fault oracle")
class InvalidRequestOracleTest {

    @TempDir
    static Path directory;

    static List<ValidValueFixtures.Fixture> fixtures;

    @BeforeAll
    static void generate() {
        fixtures = InvalidRequestFixtures.all(directory);
    }

    @Test
    void theFixturesDeriveCases() {
        long cases = fixtures.stream().mapToLong(f -> cases(f).size()).sum();
        assertThat(cases).isGreaterThan(300);
        assertThat(fixtures).hasSize(ValidValueFixtures.CORPUS.size() + InvalidRequestFixtures.CORPUS.size()
                + ValidValueFixtures.REFERENCE.size());
    }

    @TestFactory
    Stream<DynamicTest> everyCaseHasExactlyItsOneFault() {
        List<DynamicTest> tests = new ArrayList<>();
        for (ValidValueFixtures.Fixture f : fixtures) {
            RequestValidation validation = new RequestValidation(f, InvalidRequestFixtures.strict(f));
            for (JsonNode c : cases(f)) {
                tests.add(DynamicTest.dynamicTest(f.name() + " " + c.get("id").stringValue(), () -> {
                    List<RequestValidation.Problem> problems = withoutDroppedAnnotations(
                            validation.problems(c.get("request")), c);
                    assertThat(problems).as("the faults of %s", c.get("request")).hasSize(1);
                    assertMatches(c, problems.get(0));
                    assertThat(validation.problems(c.get("baseline"))).as("the faults of the baseline").isEmpty();
                    assertThat(restored(c)).as("the request with its one change undone")
                            .isEqualTo(c.get("baseline"));
                }));
            }
        }
        return tests.stream();
    }

    /**
     * The problems without the one artefact of validating against the strictified document:
     * under 2020-12, a subschema that fails drops its annotations, so a member whose value
     * breaks a keyword inside an allOf branch is also reported by unevaluatedProperties as if
     * it were unknown. Only that report is dropped: the member the fault is inside.
     */
    static List<RequestValidation.Problem> withoutDroppedAnnotations(List<RequestValidation.Problem> problems,
                                                                    JsonNode c) {
        if (!c.get("in").stringValue().equals("body") || c.get("pointer").isNull()) return problems;
        String pointer = c.get("pointer").stringValue();
        return problems.stream().filter(p -> !(p.keyword().equals("unevaluatedProperties") && p.property() != null
                && !c.get("keyword").stringValue().equals("additionalProperties")
                && (pointer + "/").startsWith(p.pointer() + "/" + RequestValidation.escape(p.property()) + "/")))
                .toList();
    }

    static List<JsonNode> cases(ValidValueFixtures.Fixture f) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode entry : f.report().get("invalidRequests")) entry.get("cases").forEach(out::add);
        return out;
    }

    private static void assertMatches(JsonNode c, RequestValidation.Problem p) {
        String keyword = c.get("keyword").stringValue();
        String in = c.get("in").stringValue();
        assertThat(p.in()).isEqualTo(in);
        if (!in.equals("body")) {
            assertThat(p.name()).isEqualTo(c.get("name").stringValue());
            assertThat(p.keyword()).isEqualTo(keyword);
            assertThat(p.pointer()).isEmpty();
            return;
        }
        String pointer = c.get("pointer").stringValue();
        if (keyword.equals("required") || keyword.equals("additionalProperties")) {
            if (pointer.isEmpty()) {
                assertThat(p.keyword()).isEqualTo("required");
                return;
            }
            assertThat(p.keyword()).isIn(keyword.equals("required") ? List.of("required")
                    : List.of("additionalProperties", "unevaluatedProperties"));
            String member = pointer.substring(pointer.lastIndexOf('/') + 1).replace("~1", "/").replace("~0", "~");
            // A member error names the object and the member, or the member itself.
            if (p.pointer().equals(pointer)) return;
            assertThat(p.pointer()).isEqualTo(pointer.substring(0, pointer.lastIndexOf('/')));
            assertThat(p.property()).isEqualTo(member);
            return;
        }
        assertThat(p.keyword()).isEqualTo(keyword);
        assertThat(p.pointer()).isEqualTo(pointer);
    }

    /** The request with the value at the case's pointer, or its parameter, put back as the baseline has it. */
    private static JsonNode restored(JsonNode c) {
        ObjectNode request = (ObjectNode) c.get("request").deepCopy();
        JsonNode baseline = c.get("baseline");
        String in = c.get("in").stringValue();
        switch (in) {
            case "path" -> request.set("pathParameters", baseline.get("pathParameters"));
            case "query" -> request.set("query", baseline.get("query"));
            case "header" -> request.set("headers", baseline.get("headers"));
            default -> {
                String pointer = c.get("pointer").stringValue();
                if (pointer.isEmpty()) {
                    request.set("contentType", baseline.get("contentType"));
                    request.set("body", baseline.get("body"));
                } else {
                    request.set("body", put(request.get("body"), pointer, baseline.get("body").at(pointer)));
                }
            }
        }
        return request;
    }

    /** A copy of a JSON value with the value at a pointer replaced, added, or removed when the new value is missing. */
    private static JsonNode put(JsonNode root, String pointer, JsonNode value) {
        JsonNode copy = root.deepCopy();
        String parentPointer = pointer.substring(0, pointer.lastIndexOf('/'));
        String last = pointer.substring(pointer.lastIndexOf('/') + 1).replace("~1", "/").replace("~0", "~");
        JsonNode parent = copy.at(parentPointer);
        if (parent instanceof ObjectNode o) {
            if (value.isMissingNode()) {
                o.remove(last);
            } else {
                o.set(last, value);
            }
        } else if (parent instanceof ArrayNode a) {
            a.set(Integer.parseInt(last), value);
        }
        return copy;
    }
}
