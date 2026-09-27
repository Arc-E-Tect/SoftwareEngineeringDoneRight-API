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
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T19.4: each success case sends its variant's valid request, with the {@code Accept} every case
 * sends; the full variant only where it differs from the required one, with the reason recorded
 * where it does not; every exact 2xx declared; a noBody variant where the body is optional; and
 * nothing, with the reason, where there is no valid value.
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
                    assertThat(c.get("id").stringValue()).isEqualTo("success-" + status + "-"
                            + (variant.equals("noBody") ? "no-body" : variant));
                    JsonNode expected = ContractCaseFixtures.request(f.report(), c, REQUESTS.get(variant));
                    assertThat(ContractCaseFixtures.withoutAccept(c.get("request"))).isEqualTo(expected);
                    if (variant.equals("full")) {
                        assertThat(expected).isNotEqualTo(ContractCaseFixtures.request(f.report(), c, "requiredRequest"));
                    }
                }));
            }
        }
        return tests.stream();
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
