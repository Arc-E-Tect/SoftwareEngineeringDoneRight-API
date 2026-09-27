package com.arc_e_tect.gradle.apionly.transcriberj.core;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T19.7: the invalid-request cases of every fixture contract are what they were before contract
 * cases of every kind were derived, as {@code fixtures/invalid-requests/before} records them:
 * the same ids, relative order, requests, expected responses and fault locations. Only what came
 * with the unified list differs: the class name, the kind, {@code requiresState} and
 * {@code variant}, the {@code Accept} every case now sends, the index, and {@code representative},
 * which is gone.
 */
@DisplayName("T19.7 Invalid-request cases unchanged")
class InvalidRequestsUnchangedTest {

    static final Path BEFORE = GeneratedSources.FIXTURES.resolve("invalid-requests/before");

    @TempDir
    static Path directory;

    static List<ValidValueFixtures.Fixture> fixtures;

    @BeforeAll
    static void generate() {
        fixtures = InvalidRequestFixtures.all(directory);
    }

    /** The file a fixture's cases were recorded in: the valid-value corpus's keywords contract shares its name. */
    static Path before(ValidValueFixtures.Fixture f) {
        boolean validValues = f.document().startsWith(ValidValueFixtures.CORPUS_DIRECTORY);
        return BEFORE.resolve((validValues && f.name().equals("keywords") ? "valid-values-" : "") + f.name() + ".json");
    }

    @TestFactory
    Stream<DynamicTest> theInvalidRequestCasesAreTheOnesRecordedBefore() {
        List<DynamicTest> tests = new ArrayList<>();
        for (ValidValueFixtures.Fixture f : fixtures) {
            tests.add(DynamicTest.dynamicTest(f.name() + " (" + before(f).getFileName() + ")", () -> {
                JsonNode recorded = Oracle.JSON.readTree(Files.readString(before(f), StandardCharsets.UTF_8))
                        .get("invalidRequests");
                JsonNode now = InvalidRequestFixtures.invalidRequests(f.report());
                assertThat(now).hasSameSizeAs(recorded);
                for (int i = 0; i < now.size(); i++) {
                    ObjectNode was = (ObjectNode) recorded.get(i).deepCopy();
                    was.put("class", was.get("class").stringValue().replaceAll("InvalidRequests$", "ContractCases"));
                    was.get("cases").forEach(c -> ((ObjectNode) c).remove("representative"));
                    assertThat(now.get(i)).as(was.get("class").stringValue()).isEqualTo(was);
                }
            }));
        }
        return tests.stream();
    }

    @Test
    void everyFixtureHasItsRecordAndEveryRecordItsFixture() throws Exception {
        List<String> files = new ArrayList<>();
        try (Stream<Path> list = Files.list(BEFORE)) {
            list.forEach(p -> files.add(p.getFileName().toString()));
        }
        assertThat(files).containsExactlyInAnyOrderElementsOf(fixtures.stream()
                .map(f -> before(f).getFileName().toString()).toList());
    }
}
