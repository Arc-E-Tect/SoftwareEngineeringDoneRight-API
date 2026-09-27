package com.arc_e_tect.gradle.apionly.transcriberj.core;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T19.8: the cases of every operation of every fixture are in canonical order: success cases by
 * status, then variant -- required, full, noBody --; then not found, not acceptable and
 * unsupported media type; then the invalid-request cases, in their own order, which T19.7
 * compares with the one recorded before. Ids are unique within the operation, and
 * {@code requiresState} is true exactly for success and not found.
 */
@DisplayName("T19.8 Canonical order")
class ContractCaseOrderTest {

    static final List<String> KINDS = List.of("SUCCESS", "NOT_FOUND", "NOT_ACCEPTABLE", "UNSUPPORTED_MEDIA_TYPE",
            "INVALID_REQUEST");
    static final List<String> VARIANTS = List.of("required", "full", "noBody");

    @TempDir
    static Path directory;

    static List<ValidValueFixtures.Fixture> fixtures;

    @BeforeAll
    static void generate() {
        fixtures = ContractCaseFixtures.all(directory);
    }

    private static int variant(JsonNode c) {
        return c.get("variant").isNull() ? -1 : VARIANTS.indexOf(c.get("variant").stringValue());
    }

    static final Comparator<JsonNode> CANONICAL = Comparator
            .comparingInt((JsonNode c) -> KINDS.indexOf(c.get("kind").stringValue()))
            .thenComparingInt(c -> c.get("kind").stringValue().equals("SUCCESS") ? c.get("expectedStatus").intValue() : 0)
            .thenComparingInt(ContractCaseOrderTest::variant);

    @TestFactory
    Stream<DynamicTest> everyOperationsCasesAreInCanonicalOrder() {
        List<DynamicTest> tests = new ArrayList<>();
        for (ValidValueFixtures.Fixture f : fixtures) {
            for (JsonNode operation : f.report().get("contractCases")) {
                List<JsonNode> cases = new ArrayList<>();
                operation.get("cases").forEach(cases::add);
                if (cases.isEmpty()) continue;
                tests.add(DynamicTest.dynamicTest(f.name() + " " + operation.get("class").stringValue(), () -> {
                    assertThat(cases).isSortedAccordingTo(CANONICAL);
                    assertThat(cases.stream().map(c -> c.get("id").stringValue())).doesNotHaveDuplicates();
                    for (JsonNode c : cases) {
                        String kind = c.get("kind").stringValue();
                        assertThat(c.get("requiresState").booleanValue())
                                .isEqualTo(kind.equals("SUCCESS") || kind.equals("NOT_FOUND"));
                        assertThat(c.get("variant").isNull()).isEqualTo(!kind.equals("SUCCESS"));
                        if (!kind.equals("INVALID_REQUEST")) {
                            for (String field : List.of("in", "name", "pointer", "keyword")) {
                                assertThat(c.get(field).isNull()).as(field).isTrue();
                            }
                        }
                    }
                }));
            }
        }
        return tests.stream();
    }
}
