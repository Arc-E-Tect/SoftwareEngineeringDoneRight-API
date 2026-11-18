package com.arc_e_tect.gradle.apionly.transcriberj.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An operation names, in {@code x-transcriberj-selected-responses}, the success responses a
 * request header selects: {@code Prefer: respond-async} selects {@code 202}, and the
 * {@code Idempotency-Key} goes with it. Every success case of a selected response sends the
 * selecting value and the headers that go with it; every success case of another response
 * leaves the selecting header out. Every other case is derived as before. A marker that names
 * what the operation does not declare, or that no request could honour, fails the generation.
 *
 * <p>{@code with} is a hint: its headers are sent, and nothing more. {@code requires} is a rule:
 * its headers are sent too, and each gets an invalid-request case that leaves it out.
 */
class SelectedResponsesTest {

    private static final String MARKER = """
                  x-transcriberj-selected-responses:
                    '202': {in: header, name: Prefer, value: respond-async, with: [Idempotency-Key]}
            """;

    private static final String REQUIRES = """
                  x-transcriberj-selected-responses:
                    '202': {in: header, name: Prefer, value: respond-async, requires: [Idempotency-Key]}
            """;

    private static final String IDEMPOTENCY_KEY = "{name: Idempotency-Key, in: header, schema: {type: string, pattern: '^[0-9a-f]{8}$'}}";

    @TempDir
    Path directory;

    /**
     * The contract: a validation that answers {@code 200}, or {@code 202} when the client prefers
     * an asynchronous answer, with the marker and the {@code Prefer} parameter given.
     */
    private static String contract(String marker, String prefer) {
        return contract(marker, prefer, IDEMPOTENCY_KEY);
    }

    /** The contract, with the {@code Idempotency-Key} parameter given too. */
    private static String contract(String marker, String prefer, String idempotencyKey) {
        return """
                openapi: 3.1.0
                info: {title: Validations, version: 1.0.0}
                paths:
                  /validations:
                    post:
                      operationId: Validate
                %s      parameters:
                        - %s
                        - %s
                        - {name: Trace, in: header, schema: {type: string, minLength: 1}}
                      requestBody:
                        required: true
                        content:
                          application/json:
                            schema: {$ref: '#/components/schemas/Input'}
                      responses:
                        '200':
                          description: Validated.
                          content:
                            application/json:
                              schema: {$ref: '#/components/schemas/Result'}
                        '202': {description: Accepted.}
                        '400': {description: Invalid.}
                        '406': {description: Not acceptable.}
                        '415': {description: Unsupported media type.}
                components:
                  schemas:
                    Input:
                      x-fragment-path: openapi/components/Input.yaml
                      type: object
                      required: [name]
                      properties:
                        name: {type: string, minLength: 1}
                        note: {type: string}
                    Result:
                      x-fragment-path: openapi/components/Result.yaml
                      type: object
                      properties:
                        valid: {type: boolean}
                """.formatted(marker == null ? "" : marker, prefer, idempotencyKey);
    }

    private static final String PREFER = "{name: Prefer, in: header, schema: {type: string, minLength: 1}}";

    private static String marked(String selection) {
        return "      x-transcriberj-selected-responses:\n        " + selection + "\n";
    }

    private GeneratedSources generate(String yaml) {
        return GeneratedSources.generate(yaml, directory, List.of());
    }

    private static JsonNode report(GeneratedSources g) {
        return Oracle.JSON.readTree(g.report.renderValidValues("test-contract", "1.0.0"));
    }

    private static JsonNode find(JsonNode report, String id) {
        return ContractCaseFixtures.cases(report).stream().filter(c -> c.get("id").stringValue().equals(id))
                .findFirst().orElseThrow(() -> new AssertionError("no case " + id));
    }

    private static List<String> headers(JsonNode c) {
        List<String> out = new ArrayList<>();
        c.get("request").get("headers").forEach(h -> out.add(h.get("name").stringValue() + "="
                + h.get("value").stringValue()));
        return out;
    }

    private static List<String> headerNames(JsonNode c) {
        return headers(c).stream().map(h -> h.substring(0, h.indexOf('='))).toList();
    }

    private static List<String> ids(JsonNode report) {
        return ContractCaseFixtures.cases(report).stream().map(c -> c.get("id").stringValue()).toList();
    }

    // ------------------------------------------------- the selected response

    @Test
    void theSelectedResponsesRequiredCaseSendsTheSelectingValueAndWhatGoesWithIt() {
        JsonNode c = find(report(generate(contract(MARKER, PREFER))), "success-202-required");

        assertThat(headerNames(c)).containsExactly("Prefer", "Idempotency-Key", "Accept");
        assertThat(headers(c).get(0)).isEqualTo("Prefer=respond-async");
        assertThat(headers(c).get(1)).matches("Idempotency-Key=[0-9a-f]{8}");
        assertThat(c.get("request").get("body").has("note")).isFalse();
    }

    @Test
    void theSelectedResponsesFullCaseSendsEveryParameterWithTheSelectingValue() {
        JsonNode c = find(report(generate(contract(MARKER, PREFER))), "success-202-full");

        assertThat(headerNames(c)).containsExactly("Prefer", "Idempotency-Key", "Trace", "Accept");
        assertThat(headers(c).get(0)).isEqualTo("Prefer=respond-async");
        assertThat(c.get("request").get("body").has("note")).isTrue();
    }

    @Test
    void theSelectedResponsesCasesSayWhatSelectsThem() {
        JsonNode report = report(generate(contract(MARKER, PREFER)));

        for (String id : List.of("success-202-required", "success-202-full")) {
            JsonNode selectedBy = find(report, id).get("selectedBy");
            assertThat(selectedBy.get("in").stringValue()).isEqualTo("header");
            assertThat(selectedBy.get("name").stringValue()).isEqualTo("Prefer");
            assertThat(selectedBy.get("value").stringValue()).isEqualTo("respond-async");
            assertThat(selectedBy.get("with").get(0).stringValue()).isEqualTo("Idempotency-Key");
            assertThat(find(report, id).get("description").stringValue())
                    .endsWith(", with Prefer: respond-async, which selects 202");
        }
    }

    // ------------------------------------------------- the other responses

    @Test
    void anotherResponsesRequiredCaseLeavesTheSelectingHeaderOut() {
        JsonNode c = find(report(generate(contract(MARKER, PREFER))), "success-200-required");

        assertThat(headerNames(c)).containsExactly("Accept");
        assertThat(c.get("selectedBy").isNull()).isTrue();
    }

    @Test
    void anotherResponsesFullCaseSendsEveryOtherParameter() {
        JsonNode c = find(report(generate(contract(MARKER, PREFER))), "success-200-full");

        assertThat(headerNames(c)).containsExactly("Idempotency-Key", "Trace", "Accept");
        assertThat(c.get("description").stringValue()).endsWith(", but Prefer, which selects 202");
    }

    @Test
    void theOtherKindsAreDerivedFromTheRequestThatSelectsNothing() {
        JsonNode report = report(generate(contract(MARKER, PREFER)));

        for (String id : List.of("not-acceptable", "unsupported-media-type")) {
            assertThat(headerNames(find(report, id))).doesNotContain("Prefer");
            assertThat(find(report, id).get("selectedBy").isNull()).isTrue();
        }
    }

    @Test
    void theMarkerChangesNoCaseButTheSelectedResponsesAndTheOthersSuccessCases() {
        JsonNode unmarked = report(generate(contract(null, PREFER)));
        JsonNode marked = report(generate(contract(MARKER, PREFER)));

        assertThat(ids(marked)).isEqualTo(ids(unmarked));
        for (String id : ids(unmarked)) {
            if (id.startsWith("success-")) continue;
            assertThat(find(marked, id).get("request")).as(id).isEqualTo(find(unmarked, id).get("request"));
        }
    }

    @Test
    void withoutTheMarkerEverySuccessCaseIsTheOperationsOwnRequest() {
        JsonNode report = report(generate(contract(null, PREFER)));

        assertThat(headerNames(find(report, "success-202-required"))).containsExactly("Accept");
        assertThat(headerNames(find(report, "success-202-full"))).containsExactly("Prefer", "Idempotency-Key",
                "Trace", "Accept");
        assertThat(find(report, "success-202-full").get("selectedBy").isNull()).isTrue();
    }

    // --------------------------------------------- what the selection requires

    @Test
    void aRequiredHeaderIsSentByEverySuccessCaseOfTheSelectedResponse() {
        JsonNode report = report(generate(contract(REQUIRES, PREFER)));

        assertThat(headerNames(find(report, "success-202-required"))).containsExactly("Prefer", "Idempotency-Key", "Accept");
        assertThat(headerNames(find(report, "success-200-required"))).containsExactly("Accept");
        JsonNode selectedBy = find(report, "success-202-required").get("selectedBy");
        assertThat(selectedBy.get("requires").get(0).stringValue()).isEqualTo("Idempotency-Key");
        assertThat(selectedBy.get("with")).isEmpty();
    }

    @Test
    void aRequiredHeaderLeftOutIsAnInvalidRequest() {
        JsonNode report = report(generate(contract(REQUIRES, PREFER)));
        JsonNode c = find(report, "header-Idempotency-Key-required");

        assertThat(c.get("kind").stringValue()).isEqualTo("INVALID_REQUEST");
        assertThat(c.get("description").stringValue())
                .isEqualTo("header parameter `Idempotency-Key` missing, though `Prefer: respond-async` requires it");
        assertThat(List.of(c.get("in").stringValue(), c.get("name").stringValue(), c.get("keyword").stringValue()))
                .containsExactly("header", "Idempotency-Key", "required");
        assertThat(headers(c)).containsExactly("Prefer=respond-async", "Accept=application/json");
        assertThat(c.get("baseline"))
                .isEqualTo(ContractCaseFixtures.withoutAccept(find(report, "success-202-required").get("request")));
        assertThat(c.get("expectedStatus").intValue()).isEqualTo(400);
    }

    @Test
    void theRequiredHeaderIsAConstraintAtTheMarkerThatItsCaseCovers() {
        JsonNode report = report(generate(contract(REQUIRES, PREFER)));
        List<JsonNode> entries = new ArrayList<>();
        report.get("constraintCoverage").forEach(e -> {
            if (e.get("schemaLocation").stringValue().contains("x-transcriberj-selected-responses")) entries.add(e);
        });

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).get("schemaLocation").stringValue())
                .isEqualTo("/paths/~1validations/post/x-transcriberj-selected-responses/202/requires/0");
        assertThat(entries.get(0).get("cases").get(0).stringValue()).isEqualTo("header-Idempotency-Key-required");
    }

    @Test
    void aHeaderThatOnlyGoesWithItIsNoRule() {
        assertThat(ids(report(generate(contract(MARKER, PREFER))))).doesNotContain("header-Idempotency-Key-required");
    }

    @Test
    void aHeaderBothRequiredAndGoingWithItFails() {
        failsWith(marked("'202': {in: header, name: Prefer, value: respond-async, with: [Idempotency-Key], "
                + "requires: [Idempotency-Key]}"), PREFER, "Idempotency-Key", "with", "requires");
    }

    @Test
    void aRequiredHeaderTheOperationDoesNotDeclareFails() {
        failsWith(marked("'202': {in: header, name: Prefer, value: respond-async, requires: [Wait]}"), PREFER,
                "Wait", "header parameter");
    }

    @Test
    void requiringTheSelectingHeaderItselfFails() {
        failsWith(marked("'202': {in: header, name: Prefer, value: respond-async, requires: [Prefer]}"), PREFER,
                "Prefer", "selects");
    }

    @Test
    void requiringAHeaderTheOperationRequiresAnywayFails() {
        assertThatThrownBy(() -> generate(contract(REQUIRES, PREFER,
                "{name: Idempotency-Key, in: header, required: true, schema: {type: string, pattern: '^[0-9a-f]{8}$'}}")))
                .isInstanceOf(GenerationException.class)
                .hasMessageContaining("Idempotency-Key")
                .hasMessageContaining("requires it already");
    }

    // --------------------------------------------------- a malformed marker

    private void failsWith(String marker, String prefer, String... fragments) {
        assertThatThrownBy(() -> generate(contract(marker, prefer)))
                .isInstanceOf(GenerationException.class)
                .hasMessageContaining("x-transcriberj-selected-responses")
                .hasMessageContaining("/paths/~1validations/post")
                .satisfies(e -> {
                    for (String fragment : fragments) assertThat(e.getMessage()).contains(fragment);
                });
    }

    @Test
    void aStatusTheOperationDoesNotDeclareFails() {
        failsWith(marked("'201': {in: header, name: Prefer, value: respond-async}"), PREFER,
                "201", "does not declare");
    }

    @Test
    void aStatusThatIsNoSuccessFails() {
        failsWith(marked("'406': {in: header, name: Prefer, value: respond-async}"), PREFER,
                "406", "2xx");
    }

    @Test
    void aHeaderTheOperationDoesNotDeclareFails() {
        failsWith(marked("'202': {in: header, name: Wait, value: respond-async}"), PREFER,
                "Wait", "header parameter");
    }

    @Test
    void anotherLocationThanAHeaderFails() {
        failsWith(marked("'202': {in: query, name: Prefer, value: respond-async}"), PREFER,
                "query", "only header");
    }

    @Test
    void aValueTheHeadersSchemaRefusesFails() {
        failsWith(marked("'202': {in: header, name: Idempotency-Key, value: respond-async}"), PREFER,
                "respond-async", "schema");
    }

    @Test
    void aHeaderThatGoesWithItButIsNotDeclaredFails() {
        failsWith(marked("'202': {in: header, name: Prefer, value: respond-async, with: [Wait]}"), PREFER,
                "Wait", "header parameter");
    }

    @Test
    void aSelectingHeaderTheContractRequiresFails() {
        failsWith(MARKER, "{name: Prefer, in: header, required: true, schema: {type: string, minLength: 1}}",
                "Prefer", "required");
    }

    @Test
    void aKeyTheMarkerDoesNotKnowFails() {
        failsWith(marked("'202': {in: header, name: Prefer, value: respond-async, wiht: [Idempotency-Key]}"), PREFER,
                "wiht");
    }

    @Test
    void aMissingValueFails() {
        failsWith(marked("'202': {in: header, name: Prefer}"), PREFER, "value");
    }

    @Test
    void aMarkerThatIsNoMapFails() {
        failsWith("      x-transcriberj-selected-responses: [202]\n", PREFER, "map");
    }

    @Test
    void twoResponsesSelectedTheSameWayFail() {
        failsWith(marked("'200': {in: header, name: Prefer, value: respond-async}\n"
                        + "        '202': {in: header, name: Prefer, value: respond-async}"),
                PREFER, "200", "202", "same");
    }
}
