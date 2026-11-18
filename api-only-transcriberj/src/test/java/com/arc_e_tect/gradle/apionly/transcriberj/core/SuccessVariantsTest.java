package com.arc_e_tect.gradle.apionly.transcriberj.core;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T19.4: each success case sends its variant's valid request, with the {@code Accept} every case
 * sends; the full variant only where it differs from the required one, with the reason recorded
 * where it does not; every exact 2xx declared; a noBody variant where the body is optional; and
 * nothing, with the reason, where there is no valid value. Where a request header selects a
 * response, its cases send the selecting value and the headers that go with it, and every other
 * success case of the operation leaves the selecting header out; apart from those headers, each
 * still sends its variant's request.
 */
@DisplayName("T19.4 Success variants")
class SuccessVariantsTest {

    static final Map<String, String> REQUESTS = Map.of("required", "requiredRequest", "full", "fullRequest",
            "noBody", "noBodyRequest");

    @TempDir
    static Path directory;

    static ValidValueFixtures.Fixture corpus;
    static List<ValidValueFixtures.Fixture> fixtures;

    @BeforeAll
    static void generate() {
        fixtures = ContractCaseFixtures.all(directory);
        corpus = fixtures.stream().filter(f -> f.name().equals("success")).findFirst().orElseThrow();
    }

    @TestFactory
    Stream<DynamicTest> everySuccessCaseSendsItsVariantsRequest() {
        List<DynamicTest> tests = new ArrayList<>();
        for (ValidValueFixtures.Fixture f : fixtures) {
            for (JsonNode c : ContractCaseFixtures.cases(f.report(), "SUCCESS")) {
                tests.add(DynamicTest.dynamicTest(f.name() + " " + c.get("class").stringValue() + " "
                        + c.get("id").stringValue(), () -> {
                    String variant = c.get("variant").stringValue();
                    int status = c.get("expectedStatus").intValue();
                    assertThat(status).isBetween(200, 299);
                    assertThat(c.get("requiresState").booleanValue()).isTrue();
                    if (!c.get("branch").isNull()) {
                        // A further oneOf branch: its own required request, named after the branch.
                        assertThat(variant).isEqualTo("required");
                        assertThat(c.get("id").stringValue()).isEqualTo("success-" + status + "-"
                                + InvalidRequests.kebab(c.get("branch").stringValue()));
                        assertThat(ContractCaseFixtures.withoutAccept(c.get("request")))
                                .isNotEqualTo(ContractCaseFixtures.request(f.report(), c, "requiredRequest"));
                        return;
                    }
                    assertThat(c.get("id").stringValue()).isEqualTo("success-" + status + "-"
                            + (variant.equals("noBody") ? "no-body" : variant));
                    JsonNode expected = ContractCaseFixtures.request(f.report(), c, REQUESTS.get(variant));
                    Set<String> selecting = selectingHeaders(f.report(), c);
                    assertThat(without(ContractCaseFixtures.withoutAccept(c.get("request")), selecting))
                            .isEqualTo(without(expected, selecting));
                    assertSelection(f.report(), c);
                    if (variant.equals("full") && selecting.isEmpty()) {
                        assertThat(expected).isNotEqualTo(ContractCaseFixtures.request(f.report(), c, "requiredRequest"));
                    }
                }));
            }
        }
        return tests.stream();
    }

    /** The headers that select a response of a case's operation, and those that go with them, lower case. */
    private static Set<String> selectingHeaders(JsonNode report, JsonNode c) {
        Set<String> out = new HashSet<>();
        for (JsonNode other : ContractCaseFixtures.cases(report, "SUCCESS")) {
            JsonNode by = other.get("selectedBy");
            if (!other.get("class").equals(c.get("class")) || by.isNull()) continue;
            out.add(by.get("name").stringValue().toLowerCase(Locale.ROOT));
            by.get("with").forEach(w -> out.add(w.stringValue().toLowerCase(Locale.ROOT)));
        }
        return out;
    }

    /** A request without the given headers. */
    private static JsonNode without(JsonNode request, Set<String> headers) {
        tools.jackson.databind.node.ObjectNode out = (tools.jackson.databind.node.ObjectNode) request.deepCopy();
        tools.jackson.databind.node.ArrayNode list = (tools.jackson.databind.node.ArrayNode) out.get("headers");
        for (int i = list.size() - 1; i >= 0; i--) {
            if (headers.contains(list.get(i).get("name").stringValue().toLowerCase(Locale.ROOT))) list.remove(i);
        }
        return out;
    }

    /** A selected case sends its selecting value and what goes with it; any other leaves every selecting header out. */
    private static void assertSelection(JsonNode report, JsonNode c) {
        Map<String, String> sent = new HashMap<>();
        c.get("request").get("headers").forEach(h -> sent.put(h.get("name").stringValue().toLowerCase(Locale.ROOT),
                h.get("value").stringValue()));
        JsonNode by = c.get("selectedBy");
        Set<String> mine = new HashSet<>();
        if (!by.isNull()) {
            String name = by.get("name").stringValue().toLowerCase(Locale.ROOT);
            assertThat(sent).containsEntry(name, by.get("value").stringValue());
            mine.add(name);
            by.get("with").forEach(w -> {
                assertThat(sent).containsKey(w.stringValue().toLowerCase(Locale.ROOT));
                mine.add(w.stringValue().toLowerCase(Locale.ROOT));
            });
        }
        for (JsonNode other : ContractCaseFixtures.cases(report, "SUCCESS")) {
            JsonNode otherBy = other.get("selectedBy");
            if (!other.get("class").equals(c.get("class")) || otherBy.isNull()) continue;
            String name = otherBy.get("name").stringValue().toLowerCase(Locale.ROOT);
            if (!mine.contains(name)) assertThat(sent).doesNotContainKey(name);
        }
    }

    private static List<String> ids(String className) {
        return ContractCaseFixtures.cases(corpus.report(), "SUCCESS").stream()
                .filter(c -> c.get("class").stringValue().equals(className)).map(c -> c.get("id").stringValue())
                .toList();
    }

    @Test
    void optionalMembersGiveAFullRequestBesideTheRequiredOne() {
        assertThat(ids("PostOptionalContractCases")).containsExactly("success-201-required", "success-201-full");
    }

    @Test
    void noOptionalMemberGivesTheRequiredRequestOnlyAndSaysWhy() {
        assertThat(ids("GetPlainContractCases")).containsExactly("success-200-required");
        assertThat(corpus.sources().report.notes()).contains("GetPlainContractCases: success-200-full is not derived: "
                + "the full request is the required one, as the operation declares no optional parameter or member");
    }

    @Test
    void everyExact2xxGetsItsCases() {
        assertThat(ids("PutEitherContractCases")).containsExactly("success-200-required", "success-200-full",
                "success-201-required", "success-201-full");
    }

    @Test
    void anOptionalBodyGetsANoBodyCase() {
        assertThat(ids("PostMaybeContractCases")).containsExactly("success-204-required", "success-204-full",
                "success-204-no-body");
        JsonNode noBody = ContractCaseFixtures.cases(corpus.report(), "SUCCESS").stream()
                .filter(c -> c.get("id").stringValue().equals("success-204-no-body")).findFirst().orElseThrow();
        assertThat(noBody.get("request").get("contentType").isNull()).isTrue();
        assertThat(noBody.get("request").get("body").isNull()).isTrue();
        assertThat(ids("PostOptionalContractCases")).noneMatch(id -> id.endsWith("no-body"));
    }

    @Test
    void aValueNoCheckExistsForGivesNoCaseItIsIn() {
        assertThat(ids("PostPeriodContractCases")).containsExactly("success-201-required");
        assertThat(corpus.sources().report.notes()).contains("PostPeriodContractCases: success-201-full is not "
                + "derived: the valid request holds a value at /period for the format declared at "
                + "/components/schemas/PeriodV1/properties/period, which no check exists for, so it cannot be vouched for");
        assertThat(ids("PostDurationContractCases")).isEmpty();
        assertThat(ContractCaseFixtures.coverage(corpus.report(), "PostDurationContractCases"))
                .containsExactly("PostDurationContractCases 201 NO_VALID_VALUE");
    }

    @Test
    void aPatternBesideTheFormatDecidesAndTheFullCaseIsDerived() {
        assertThat(ids("PostPatternedPeriodContractCases")).containsExactly("success-201-required",
                "success-201-full");
        JsonNode full = ContractCaseFixtures.cases(corpus.report(), "SUCCESS").stream()
                .filter(c -> c.get("class").stringValue().equals("PostPatternedPeriodContractCases")
                        && c.get("variant").stringValue().equals("full")).findFirst().orElseThrow();
        assertThat(full.get("request").get("body").get("period").stringValue()).matches("P[0-9]+D");
    }

    @Test
    void noValidValueGivesNoCaseAndSaysSo() {
        assertThat(ids("PostImpossibleContractCases")).isEmpty();
        assertThat(ContractCaseFixtures.coverage(corpus.report(), "PostImpossibleContractCases"))
                .containsExactly("PostImpossibleContractCases 201 NO_VALID_VALUE");
    }
}
