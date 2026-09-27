package com.arc_e_tect.gradle.apionly.transcriberj.core;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T19.5: a walk over every operation of every fixture contract, independent of the generator,
 * finds each declared response in the response coverage exactly once, in declaration order:
 * either covered by cases that expect its status, or given exactly one reason from the closed
 * list. Every case is listed under the response it expects.
 */
@DisplayName("T19.5 Response coverage is exhaustive")
class ResponseCoverageTest {

    /** The closed list of reasons a declared response has no case. */
    static final Set<String> REASONS = Set.of("RANGE_OR_DEFAULT", "BEHAVIOUR_OR_STATE",
            "NOT_FOUND_WITHOUT_PATH_PARAMETER", "NOT_FOUND_WITHOUT_SECOND_VALUE", "NO_MEDIA_TYPE_LEFT",
            "UNSUPPORTED_WITHOUT_BODY", "NO_VALID_VALUE", "INSIDE_DEGRADED_CONSTRUCT", "KIND_SWITCHED_OFF",
            "INVALID_REQUEST_STATUS");

    static final List<String> METHODS = List.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

    @TempDir
    static Path directory;

    static List<ValidValueFixtures.Fixture> fixtures;

    @BeforeAll
    static void generate() {
        fixtures = ContractCaseFixtures.all(directory);
    }

    /** Every declared response, as {@code location status}, in declaration order. */
    static List<String> declared(JsonNode document) {
        List<String> out = new ArrayList<>();
        for (var path : document.path("paths").properties()) {
            for (String method : METHODS) {
                JsonNode operation = path.getValue().path(method);
                if (operation.isMissingNode()) continue;
                String location = "/paths/" + RequestValidation.escape(path.getKey()) + "/" + method;
                operation.path("responses").propertyNames().forEach(status -> out.add(location + " " + status));
            }
        }
        return out;
    }

    @TestFactory
    Stream<DynamicTest> everyDeclaredResponseIsCoveredOrGivenOneReason() {
        return fixtures.stream().map(f -> DynamicTest.dynamicTest(f.name(), () -> {
            List<String> listed = new ArrayList<>();
            List<JsonNode> cases = ContractCaseFixtures.cases(f.report());
            List<String> listedCases = new ArrayList<>();
            for (JsonNode e : f.report().get("responseCoverage")) {
                String location = e.get("operation").stringValue();
                String status = e.get("status").stringValue();
                listed.add(location + " " + status);
                assertThat(e.has("cases")).as("%s %s: covered or not, never both", location, status)
                        .isNotEqualTo(e.has("uncovered"));
                if (e.has("cases")) {
                    assertThat(e.get("cases")).as("%s %s", location, status).isNotEmpty();
                    for (JsonNode id : e.get("cases")) {
                        JsonNode c = cases.stream().filter(k -> k.get("location").stringValue().equals(location)
                                && k.get("id").equals(id)).findFirst().orElseThrow(
                                        () -> new AssertionError("no case " + id + " in " + location));
                        assertThat(String.valueOf(c.get("expectedStatus").intValue())).isEqualTo(status);
                        listedCases.add(location + " " + id.stringValue());
                    }
                } else {
                    assertThat(REASONS).contains(e.get("uncovered").get("code").stringValue());
                    assertThat(e.get("uncovered").get("detail").stringValue()).isNotBlank();
                }
            }
            assertThat(listed).isEqualTo(declared(f.oracle().document));
            assertThat(listedCases).containsExactlyInAnyOrderElementsOf(cases.stream()
                    .map(c -> c.get("location").stringValue() + " " + c.get("id").stringValue()).toList());
        }));
    }
}
