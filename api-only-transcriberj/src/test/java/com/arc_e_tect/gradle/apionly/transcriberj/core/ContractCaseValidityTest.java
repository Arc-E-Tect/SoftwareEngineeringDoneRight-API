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
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T19.1: the request of every success, not-found, not-acceptable and unsupported-media-type case
 * of every fixture contract is valid, as the independent validator sees it, strictified as for
 * invalid requests. The unsupported-media-type case's body is judged as the media type it is
 * valid for, as the only thing wrong with it is the {@code Content-Type} it is sent with.
 */
@DisplayName("T19.1 Every non-invalid request is valid")
class ContractCaseValidityTest {

    @TempDir
    static Path directory;

    static List<ValidValueFixtures.Fixture> fixtures;

    @BeforeAll
    static void generate() {
        fixtures = ContractCaseFixtures.all(directory);
    }

    @Test
    void theFixturesDeriveEveryKind() {
        Set<String> kinds = new TreeSet<>();
        fixtures.forEach(f -> ContractCaseFixtures.cases(f.report()).forEach(c -> kinds.add(c.get("kind").stringValue())));
        assertThat(kinds).containsExactlyInAnyOrder("SUCCESS", "NOT_FOUND", "NOT_ACCEPTABLE",
                "UNSUPPORTED_MEDIA_TYPE", "INVALID_REQUEST");
    }

    @TestFactory
    Stream<DynamicTest> everyNonInvalidRequestIsValid() {
        List<DynamicTest> tests = new ArrayList<>();
        for (ValidValueFixtures.Fixture f : fixtures) {
            RequestValidation validation = new RequestValidation(f, InvalidRequestFixtures.strict(f));
            for (JsonNode c : ContractCaseFixtures.cases(f.report())) {
                if (c.get("kind").stringValue().equals("INVALID_REQUEST")) continue;
                tests.add(DynamicTest.dynamicTest(f.name() + " " + c.get("class").stringValue() + " "
                        + c.get("id").stringValue(), () -> {
                    JsonNode request = c.get("request");
                    if (c.get("kind").stringValue().equals("UNSUPPORTED_MEDIA_TYPE")) {
                        JsonNode required = ContractCaseFixtures.request(f.report(), c, "requiredRequest");
                        assertThat(request.get("body")).isEqualTo(required.get("body"));
                        ObjectNode asDeclared = (ObjectNode) request.deepCopy();
                        asDeclared.set("contentType", required.get("contentType"));
                        request = asDeclared;
                    }
                    assertThat(validation.problems(request)).as("the faults of %s", request).isEmpty();
                }));
            }
        }
        return tests.stream();
    }
}
