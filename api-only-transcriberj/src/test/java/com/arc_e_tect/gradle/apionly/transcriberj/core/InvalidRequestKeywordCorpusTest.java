package com.arc_e_tect.gradle.apionly.transcriberj.core;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T13.3: the keyword corpus has a case for every row of the derivation table, on a body
 * property, a nested property, an array item, and each parameter location where the
 * keyword applies; and one case per row has exactly the id, keyword, pointer and
 * description expected.
 */
@DisplayName("T13.3 The keyword corpus")
class InvalidRequestKeywordCorpusTest {

    @TempDir
    static Path directory;

    static List<JsonNode> cases;

    /** The rows, by name, each with the keyword its cases record. */
    static final Map<String, String> ROWS = new LinkedHashMap<>();

    static {
        for (String row : List.of("required", "type", "null", "enum", "const", "minLength", "maxLength", "pattern",
                "format", "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf", "minItems",
                "maxItems", "uniqueItems", "minProperties", "maxProperties")) {
            ROWS.put(row, row.equals("null") ? "type" : row);
        }
        ROWS.put("unknown member", "additionalProperties");
    }

    private static final List<String> PARAMETER_ROWS = List.of("type", "enum", "const", "minLength", "maxLength",
            "pattern", "format", "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf");

    @BeforeAll
    static void generate() {
        cases = InvalidRequestOracleTest.cases(InvalidRequestFixtures.corpus("keywords", directory));
    }

    /** Where a case's fault is: path, query, header, body, nested or item. */
    static String location(JsonNode c) {
        String in = c.get("in").stringValue();
        if (!in.equals("body")) return in;
        String pointer = c.get("pointer").stringValue();
        if (List.of(pointer.split("/")).contains("0")) return "item";
        if (pointer.startsWith("/nested")) return "nested";
        return "body";
    }

    @TestFactory
    Stream<DynamicTest> everyRowHasACaseWhereverItsKeywordApplies() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Map.Entry<String, String> row : ROWS.entrySet()) {
            List<String> where = new ArrayList<>(List.of("body", "nested", "item"));
            if (PARAMETER_ROWS.contains(row.getKey())) where.addAll(List.of("path", "query", "header"));
            if (row.getKey().equals("required")) where.addAll(List.of("query", "header"));
            for (String location : where) {
                tests.add(DynamicTest.dynamicTest(row.getKey() + " in " + location, () ->
                        assertThat(cases).as("a %s case in %s", row.getKey(), location).anyMatch(c ->
                                c.get("keyword").stringValue().equals(row.getValue()) && location(c).equals(location)
                                        && c.get("id").stringValue().endsWith("-null") == row.getKey().equals("null"))));
            }
        }
        tests.add(DynamicTest.dynamicTest("required, the request body", () -> assertThat(cases).anyMatch(c ->
                c.get("id").stringValue().equals("body-required") && c.get("request").get("body").isNull())));
        return tests.stream();
    }

    @TestFactory
    Stream<DynamicTest> oneCasePerRowIsExactlyAsExpected() {
        List<String[]> expected = List.of(
                new String[]{"body-required", "required", "", "no body, though the body is required"},
                new String[]{"body-text-required", "required", "/text", "`text` missing, though it is required"},
                new String[]{"query-limit-required", "required", null,
                        "query parameter `limit` missing, though it is required"},
                new String[]{"body-count-type", "type", "/count",
                        "`count` sent as the string \"not-a-number\", where the contract requires an integer"},
                new String[]{"path-count-type", "type", null,
                        "`count` sent as the string \"not-a-number\", where the contract requires an integer"},
                new String[]{"body-items-0-quantity-null", "type", "/items/0/quantity",
                        "`items[0].quantity` null, which its type does not allow"},
                new String[]{"body-mode-enum", "enum", "/mode", "`mode` not one of its enum values"},
                new String[]{"header-X-Fixed-const", "const", null, "`X-Fixed` not its const value"},
                new String[]{"body-nested-city-minLength", "minLength", "/nested/city",
                        "`nested.city` shorter than its minLength of 2"},
                new String[]{"body-tags-0-maxLength", "maxLength", "/tags/0", "`tags[0]` longer than its maxLength of 5"},
                new String[]{"body-nested-postalCode-pattern", "pattern", "/nested/postalCode",
                        "`nested.postalCode` not matching its pattern ^[0-9]{4}[A-Z]{2}$"},
                new String[]{"query-since-format", "format", null, "`since` not a valid date"},
                new String[]{"body-count-minimum", "minimum", "/count", "`count` below its minimum of 1"},
                new String[]{"body-items-0-quantity-maximum", "maximum", "/items/0/quantity",
                        "`items[0].quantity` above its maximum of 9"},
                new String[]{"path-ratio-exclusiveMinimum", "exclusiveMinimum", null,
                        "`ratio` equal to its exclusive minimum of 0"},
                new String[]{"header-X-Share-exclusiveMaximum", "exclusiveMaximum", null,
                        "`X-Share` equal to its exclusive maximum of 1"},
                new String[]{"body-step-multipleOf", "multipleOf", "/step", "`step` not a multiple of 5"},
                new String[]{"body-items-minItems", "minItems", "/items", "`items` with fewer items than its minItems of 1"},
                new String[]{"body-matrix-0-maxItems", "maxItems", "/matrix/0",
                        "`matrix[0]` with more items than its maxItems of 2"},
                new String[]{"body-items-uniqueItems", "uniqueItems", "/items",
                        "`items` with two equal items, though its items must be unique"},
                new String[]{"body-props-minProperties", "minProperties", "/props",
                        "`props` with fewer members than its minProperties of 2"},
                new String[]{"body-records-0-maxProperties", "maxProperties", "/records/0",
                        "`records[0]` with more members than its maxProperties of 2"},
                new String[]{"body-unknown-member", "additionalProperties", "/unexpectedMember",
                        "the body with a member it does not declare, `unexpectedMember`"});
        return expected.stream().map(e -> DynamicTest.dynamicTest(e[0], () -> {
            JsonNode c = cases.stream().filter(x -> x.get("id").stringValue().equals(e[0])).findFirst()
                    .orElseThrow(() -> new AssertionError("no case " + e[0]));
            assertThat(c.get("keyword").stringValue()).isEqualTo(e[1]);
            if (e[2] == null) {
                assertThat(c.get("pointer").isNull()).isTrue();
                assertThat(c.get("name").isNull()).isFalse();
            } else {
                assertThat(c.get("pointer").stringValue()).isEqualTo(e[2]);
            }
            assertThat(c.get("description").stringValue()).isEqualTo(e[3]);
        }));
    }
}
