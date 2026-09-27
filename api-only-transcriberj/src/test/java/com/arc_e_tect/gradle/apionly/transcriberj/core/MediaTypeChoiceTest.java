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
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T19.3: a matcher written here, which knows ranges, wildcards and parameters, confirms that no
 * response the operation declares offers a not-acceptable case's {@code Accept}, and that its
 * request body accepts no unsupported-media-type case's {@code Content-Type}; each is the first
 * candidate of its documented list that nothing matches. Contracts that leave no candidate
 * derive no case and say so.
 */
@DisplayName("T19.3 Media-type choices")
class MediaTypeChoiceTest {

    static final List<String> NOT_ACCEPTABLE = List.of("application/vnd.apionly.not-acceptable",
            "text/vnd.apionly.not-acceptable");
    static final List<String> UNSUPPORTED = List.of("text/plain", "application/vnd.apionly.unsupported-media-type",
            "text/vnd.apionly.unsupported-media-type");

    @TempDir
    static Path directory;

    static List<ValidValueFixtures.Fixture> fixtures;

    @BeforeAll
    static void generate() {
        fixtures = ContractCaseFixtures.all(directory);
    }

    /** Whether a declared media type, or range, matches a concrete one: parameters and case ignored. */
    static boolean matches(String declared, String concrete) {
        String[] d = bare(declared).split("/", 2);
        String[] c = bare(concrete).split("/", 2);
        if (d[0].equals("*")) return true;
        return d[0].equals(c[0]) && (d[1].equals("*") || d[1].equals(c[1]));
    }

    private static String bare(String mediaType) {
        int semicolon = mediaType.indexOf(';');
        return (semicolon < 0 ? mediaType : mediaType.substring(0, semicolon)).strip().toLowerCase(Locale.ROOT);
    }

    @Test
    void theMatcherKnowsRangesWildcardsAndParameters() {
        assertThat(matches("*/*", "text/plain")).isTrue();
        assertThat(matches("text/*", "TEXT/Plain")).isTrue();
        assertThat(matches("text/plain; charset=utf-8", "text/plain")).isTrue();
        assertThat(matches("application/*", "text/plain")).isFalse();
        assertThat(matches("application/json", "application/problem+json")).isFalse();
    }

    private static JsonNode operation(ValidValueFixtures.Fixture f, JsonNode c) {
        return f.oracle().document.at(c.get("location").stringValue());
    }

    private static List<String> responseTypes(ValidValueFixtures.Fixture f, JsonNode operation) {
        List<String> out = new ArrayList<>();
        for (JsonNode response : operation.path("responses")) {
            JsonNode resolved = response.has("$ref") ? f.oracle().resolve(response.get("$ref").stringValue().substring(1))
                    : response;
            resolved.path("content").propertyNames().forEach(out::add);
        }
        return out;
    }

    private static List<String> requestTypes(ValidValueFixtures.Fixture f, JsonNode operation) {
        JsonNode body = operation.path("requestBody");
        if (body.has("$ref")) body = f.oracle().resolve(body.get("$ref").stringValue().substring(1));
        return new ArrayList<>(body.path("content").propertyNames());
    }

    private static String firstUnmatched(List<String> candidates, List<String> declared) {
        return candidates.stream().filter(c -> declared.stream().noneMatch(d -> matches(d, c))).findFirst()
                .orElse(null);
    }

    @TestFactory
    Stream<DynamicTest> noResponseOffersTheAcceptOfANotAcceptableCase() {
        List<DynamicTest> tests = new ArrayList<>();
        for (ValidValueFixtures.Fixture f : fixtures) {
            for (JsonNode c : ContractCaseFixtures.cases(f.report(), "NOT_ACCEPTABLE")) {
                tests.add(DynamicTest.dynamicTest(f.name() + " " + c.get("class").stringValue(), () -> {
                    List<String> declared = responseTypes(f, operation(f, c));
                    List<String> accept = ContractCaseFixtures.accept(c.get("request"));
                    assertThat(accept).hasSize(1);
                    assertThat(declared).noneMatch(d -> matches(d, accept.get(0)));
                    assertThat(accept.get(0)).isEqualTo(firstUnmatched(NOT_ACCEPTABLE, declared));
                }));
            }
        }
        assertThat(tests).isNotEmpty();
        return tests.stream();
    }

    @TestFactory
    Stream<DynamicTest> theRequestBodyAcceptsNoContentTypeOfAnUnsupportedMediaTypeCase() {
        List<DynamicTest> tests = new ArrayList<>();
        for (ValidValueFixtures.Fixture f : fixtures) {
            for (JsonNode c : ContractCaseFixtures.cases(f.report(), "UNSUPPORTED_MEDIA_TYPE")) {
                tests.add(DynamicTest.dynamicTest(f.name() + " " + c.get("class").stringValue(), () -> {
                    List<String> declared = requestTypes(f, operation(f, c));
                    String contentType = c.get("request").get("contentType").stringValue();
                    assertThat(declared).isNotEmpty().noneMatch(d -> matches(d, contentType));
                    assertThat(contentType).isEqualTo(firstUnmatched(UNSUPPORTED, declared));
                }));
            }
        }
        assertThat(tests).isNotEmpty();
        return tests.stream();
    }

    @Test
    void noCandidateLeftGivesNoCaseAndSaysSo(@TempDir Path into) {
        ValidValueFixtures.Fixture f = ContractCaseFixtures.corpus("negotiation", into);
        for (String operation : List.of("GetAnything", "GetTyped", "GetCandidates", "GetEmpty")) {
            assertThat(ContractCaseFixtures.coverage(f.report(), operation + "ContractCases"))
                    .contains(operation + "ContractCases 406 NO_MEDIA_TYPE_LEFT");
        }
        for (String operation : List.of("PostWide", "PostRanged", "PostCandidates")) {
            assertThat(ContractCaseFixtures.coverage(f.report(), operation + "ContractCases"))
                    .contains(operation + "ContractCases 415 NO_MEDIA_TYPE_LEFT");
        }
        assertThat(ContractCaseFixtures.cases(f.report(), "NOT_ACCEPTABLE")).extracting(c -> c.get("class").stringValue())
                .containsExactly("GetReportContractCases");
        assertThat(ContractCaseFixtures.cases(f.report(), "UNSUPPORTED_MEDIA_TYPE"))
                .extracting(c -> c.get("class").stringValue()).containsExactly("PostUploadContractCases");
    }
}
