package com.arc_e_tect.gradle.apionly.transcriberj.core;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T19.2: each not-found request is the operation's required request with other path values, each
 * of them valid and different from the required request's; a path parameter with one valid value
 * derives no case, and says so.
 */
@DisplayName("T19.2 Not found differs only in path values")
class NotFoundCaseTest {

    @TempDir
    static Path directory;

    static List<ValidValueFixtures.Fixture> fixtures;

    @BeforeAll
    static void generate() {
        fixtures = ContractCaseFixtures.all(directory);
    }

    @TestFactory
    Stream<DynamicTest> onlyThePathValuesDiffer() {
        List<DynamicTest> tests = new ArrayList<>();
        for (ValidValueFixtures.Fixture f : fixtures) {
            RequestValidation validation = new RequestValidation(f, InvalidRequestFixtures.strict(f));
            for (JsonNode c : ContractCaseFixtures.cases(f.report(), "NOT_FOUND")) {
                tests.add(DynamicTest.dynamicTest(f.name() + " " + c.get("class").stringValue(), () -> {
                    JsonNode required = ContractCaseFixtures.request(f.report(), c, "requiredRequest");
                    JsonNode request = ContractCaseFixtures.withoutAccept(c.get("request"));
                    JsonNode path = request.get("pathParameters");
                    assertThat(path).isNotEmpty().hasSameSizeAs(required.get("pathParameters"));
                    for (int i = 0; i < path.size(); i++) {
                        assertThat(path.get(i)).as("path value %d", i).isNotEqualTo(required.get("pathParameters").get(i));
                    }
                    ObjectNode restored = (ObjectNode) request.deepCopy();
                    restored.set("pathParameters", required.get("pathParameters"));
                    assertThat(restored).isEqualTo(required);
                    assertThat(validation.problems(request)).isEmpty();
                    assertThat(c.get("expectedStatus").intValue()).isEqualTo(404);
                    assertThat(c.get("requiresState").booleanValue()).isTrue();
                }));
            }
        }
        assertThat(tests).hasSizeGreaterThanOrEqualTo(3);
        return tests.stream();
    }

    @Test
    void aSingleEnumValueGivesNoCaseAndSaysSo(@TempDir Path into) {
        ValidValueFixtures.Fixture f = ContractCaseFixtures.corpus("not-found", into);
        assertThat(ContractCaseFixtures.cases(f.report(), "NOT_FOUND"))
                .noneMatch(c -> c.get("class").stringValue().equals("GetSingleContractCases"));
        assertThat(ContractCaseFixtures.coverage(f.report(), "GetSingleContractCases"))
                .contains("GetSingleContractCases 404 NOT_FOUND_WITHOUT_SECOND_VALUE");
        assertThat(f.text()).contains("GetSingleContractCases 404: uncovered, 404 without a second valid path value");
    }
}
