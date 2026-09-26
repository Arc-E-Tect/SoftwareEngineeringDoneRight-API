package com.arc_e_tect.gradle.apionly.transcriberj.core;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/** T13.5 to T13.11: the rules of derivation, each on the corpus contract written for it. */
class InvalidRequestRulesTest {

    @TempDir
    static Path directory;

    static final Map<String, ValidValueFixtures.Fixture> FIXTURES = new TreeMap<>();

    @BeforeAll
    static void generate() {
        for (InvalidRequestFixtures.Variant v : InvalidRequestFixtures.CORPUS) {
            FIXTURES.put(v.name(), InvalidRequestFixtures.corpus(v.name(), directory.resolve(v.name())));
        }
    }

    static List<JsonNode> cases(String fixture) {
        return InvalidRequestOracleTest.cases(FIXTURES.get(fixture));
    }

    static JsonNode caseWithId(String fixture, String id) {
        return cases(fixture).stream().filter(c -> c.get("id").stringValue().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("no case " + id + " in " + fixture));
    }

    static List<JsonNode> coverage(String fixture) {
        List<JsonNode> out = new ArrayList<>();
        FIXTURES.get(fixture).report().get("constraintCoverage").forEach(out::add);
        return out;
    }

    /** The one coverage entry of a keyword at a pointer. */
    static JsonNode entry(String fixture, String pointer, String keyword) {
        List<JsonNode> found = coverage(fixture).stream().filter(e -> e.get("pointer").stringValue().equals(pointer)
                && e.get("keyword").stringValue().equals(keyword)).toList();
        assertThat(found).as("%s %s in %s", keyword, pointer, fixture).hasSize(1);
        return found.get(0);
    }

    static String reason(JsonNode entry) {
        assertThat(entry.has("uncovered")).as("uncovered: %s", entry).isTrue();
        return entry.get("uncovered").get("code").stringValue();
    }

    /** An operation's entry in the report, by the class its cases are listed on. */
    static JsonNode operation(String fixture, String className) {
        for (JsonNode op : FIXTURES.get(fixture).report().get("invalidRequests")) {
            if (op.get("class").stringValue().equals(className)) return op;
        }
        throw new AssertionError("no " + className + " in " + fixture);
    }

    static List<String> warnings(String fixture) {
        return FIXTURES.get(fixture).sources().report.warnings();
    }

    @Nested
    @DisplayName("T13.5 Not isolatable")
    class NotIsolatable {

        @Test
        void aLengthThePatternForcesHasNoMinLengthCase() {
            assertThat(cases("isolation")).noneMatch(c -> c.get("id").stringValue().equals("body-code-minLength"));
            JsonNode e = entry("isolation", "/code", "minLength");
            assertThat(reason(e)).isEqualTo("NOT_ISOLATABLE");
            assertThat(e.get("uncovered").get("detail").stringValue()).startsWith("conflicting keyword pattern");
            assertThat(caseWithId("isolation", "body-code-pattern")).isNotNull();
        }

        @Test
        void furtherCombinationsNameTheirConflict() {
            for (String[] expected : List.of(new String[]{"/short", "maxLength", "enum"},
                    new String[]{"/level", "minimum", "enum"}, new String[]{"/pair", "maxItems", "uniqueItems"},
                    new String[]{"/short", "type", "enum"})) {
                JsonNode e = entry("isolation", expected[0], expected[1]);
                assertThat(reason(e)).isEqualTo("NOT_ISOLATABLE");
                assertThat(e.get("uncovered").get("detail").stringValue())
                        .startsWith("conflicting keyword " + expected[2] + ":");
            }
        }

        @Test
        void aKeywordTwoPartsDeclareNamesWhereTheOtherIs() {
            List<JsonNode> both = coverage("strictness").stream().filter(e -> e.get("pointer").stringValue()
                    .equals("/merged") && e.get("keyword").stringValue().equals("type")).toList();
            assertThat(both).hasSize(2);
            for (JsonNode e : both) {
                assertThat(reason(e)).isEqualTo("NOT_ISOLATABLE");
                assertThat(e.get("uncovered").get("detail").stringValue()).contains("type (at /components/schemas/");
            }
        }
    }

    @Nested
    @DisplayName("T13.6 Route-changing path values")
    class Routes {

        @Test
        void anEmptyPathValueIsNotDerived() {
            assertThat(cases("routes")).noneMatch(c -> "name".equals(c.get("name").stringValue())
                    && c.get("keyword").stringValue().equals("minLength"));
            JsonNode e = entry("routes", "", "minLength");
            assertThat(reason(e)).isEqualTo("CHANGES_ROUTE");
        }

        @Test
        void aPathValueThatStaysOnTheRouteIs() {
            JsonNode c = caseWithId("routes", "path-slug-pattern");
            String value = c.get("request").get("pathParameters").get(1).stringValue();
            assertThat(value).isNotEmpty().doesNotContain("/", "?", "#");
        }

        @Test
        void noPathParameterEverGetsARequiredCase() {
            for (String fixture : FIXTURES.keySet()) {
                assertThat(cases(fixture)).noneMatch(c -> c.get("in").stringValue().equals("path")
                        && c.get("keyword").stringValue().equals("required"));
                coverage(fixture).stream().filter(e -> e.get("in").stringValue().equals("path")
                                && e.get("keyword").stringValue().equals("required"))
                        .forEach(e -> assertThat(reason(e)).isIn("CHANGES_ROUTE", "NO_INVALID_REQUEST_STATUS"));
            }
        }
    }

    @Nested
    @DisplayName("T13.7 Strictness")
    class Strictness {

        @Test
        void anObjectWithoutAdditionalPropertiesGetsAnUnknownMemberCase() {
            JsonNode c = caseWithId("strictness", "body-closed-unknown-member");
            assertThat(c.get("keyword").stringValue()).isEqualTo("additionalProperties");
            assertThat(c.get("request").get("body").get("closed").has(InvalidRequests.UNKNOWN_MEMBER)).isTrue();
        }

        @Test
        void anExplicitAdditionalPropertiesOrPatternPropertiesIsRespected() {
            for (String member : List.of("open", "typed", "patterned")) {
                assertThat(cases("strictness")).noneMatch(c -> c.get("id").stringValue()
                        .equals("body-" + member + "-unknown-member"));
                assertThat(coverage("strictness")).noneMatch(e -> e.get("pointer").stringValue().equals("/" + member)
                        && e.get("keyword").stringValue().equals("additionalProperties"));
            }
        }

        @Test
        void anExplicitFalseBesidePatternPropertiesGetsANameNoPatternMatches() {
            JsonNode c = caseWithId("strictness", "body-forbidden-unknown-member");
            String name = c.get("pointer").stringValue().substring("/forbidden/".length());
            assertThat(name).doesNotStartWith("x-");
        }

        @Test
        void strictnessIsJudgedAgainstTheMergedAllOf() {
            JsonNode c = caseWithId("strictness", "body-merged-unknown-member");
            JsonNode merged = c.get("baseline").get("body").get("merged");
            assertThat(merged.has("first")).isTrue();
            assertThat(merged.has("second")).isTrue();
            String name = c.get("pointer").stringValue().substring("/merged/".length());
            assertThat(name).isNotIn("first", "second");
            // The baseline, with members from both branches, has no fault of its own: T13.1 checks
            // it against the strictified document; here, the case is about the one added member.
            assertThat(c.get("request").get("body").get("merged").size()).isEqualTo(merged.size() + 1);
        }

        @Test
        void strictnessOffDerivesNoImpliedUnknownMemberCase() {
            assertThat(cases("strictness-off")).noneMatch(c -> c.get("id").stringValue().equals("body-closed-unknown-member")
                    || c.get("id").stringValue().equals("body-merged-unknown-member")
                    || c.get("id").stringValue().equals("body-unknown-member"));
            assertThat(reason(entry("strictness-off", "/closed", "additionalProperties"))).isEqualTo("STRICTNESS_OFF");
            // An explicit additionalProperties: false is the contract's own, and still has its case.
            assertThat(caseWithId("strictness-off", "body-forbidden-unknown-member")).isNotNull();
        }

        @Test
        void strictnessOffIsWarnedAboutInTheReportCitingOwasp() {
            String warning = CoreEmitter.strictnessOffWarning("strictness");
            assertThat(warnings("strictness-off")).contains(warning);
            assertThat(warning).contains("OWASP API3:2023", "OWASP API10:2023", "'strictness'");
            assertThat(FIXTURES.get("strictness-off").text()).contains(warning);
            assertThat(warnings("strictness")).doesNotContain(warning);
        }

        @Test
        void strictnessOffIsWarnedAboutOnEveryGeneration(@TempDir Path again) {
            for (int run = 1; run <= 2; run++) {
                ValidValueFixtures.Fixture f = InvalidRequestFixtures.corpus("strictness-off", again.resolve("run" + run));
                assertThat(f.sources().report.warnings()).as("run %d", run)
                        .contains(CoreEmitter.strictnessOffWarning("strictness"));
            }
        }
    }

    @Nested
    @DisplayName("T13.8 Format and pattern")
    class FormatAndPattern {

        @Test
        void aFormatAloneWithValidationOffGetsNoCaseButARecommendation() {
            assertThat(cases("formats")).noneMatch(c -> c.get("keyword").stringValue().equals("format"));
            assertThat(reason(entry("formats", "/plain", "format"))).isEqualTo("FORMAT_VALIDATION_OFF");
            assertThat(recommended("formats")).contains("/components/schemas/FormatsV1/properties/plain")
                    .doesNotContain("/components/schemas/FormatsV1/properties/patterned");
        }

        @Test
        void aFormatWithValidationOnGetsACase() {
            JsonNode c = caseWithId("formats-validated", "body-plain-format");
            assertThat(c.get("keyword").stringValue()).isEqualTo("format");
            assertThat(recommended("formats-validated")).contains("/components/schemas/FormatsV1/properties/plain");
        }

        @Test
        void aPatternOverridesAFormatWithValidationOn() {
            assertThat(caseWithId("formats-validated", "body-patterned-pattern")).isNotNull();
            assertThat(cases("formats-validated")).noneMatch(c -> c.get("id").stringValue().equals("body-patterned-format"));
            assertThat(reason(entry("formats-validated", "/patterned", "format"))).isEqualTo("PATTERN_OVERRIDES_FORMAT");
            assertThat(warnings("formats-validated")).anyMatch(w -> w.contains("format email is ignored"));
            assertThat(warnings("formats")).noneMatch(w -> w.contains("is ignored"));
        }

        @Test
        void anUnsupportedFormatIsReportedNeverGuessed() {
            assertThat(cases("formats-validated")).noneMatch(c -> c.get("pointer").stringValue().startsWith("/period")
                    && c.get("keyword").stringValue().equals("format"));
            assertThat(reason(entry("formats-validated", "/period", "format"))).isEqualTo("FORMAT_NOT_SUPPORTED");
            assertThat(warnings("formats-validated")).anyMatch(w -> w.contains("validateFormats names format duration"));
            // A value of it cannot be vouched for, so no case starts from a request holding one.
            assertThat(reason(entry("formats-validated", "/period", "type"))).isEqualTo("NO_VALID_BASELINE");
        }

        private List<String> recommended(String fixture) {
            List<String> out = new ArrayList<>();
            FIXTURES.get(fixture).report().get("formatRecommendations").forEach(r -> out.add(r.get("location").stringValue()));
            return out;
        }
    }

    @Nested
    @DisplayName("T13.9 Expected responses")
    class ExpectedResponses {

        @Test
        void aDeclared400IsExpectedWithItsContentTypeAndBodyClass() {
            JsonNode op = operation("responses", "PostDeclaredInvalidRequests");
            assertThat(op.get("cases")).isNotEmpty();
            for (JsonNode c : op.get("cases")) {
                assertThat(c.get("expectedStatus").intValue()).isEqualTo(400);
                assertThat(c.get("expectedContentTypes").toString()).isEqualTo("[\"application/problem+json\"]");
                assertThat(c.get("responseBodyClass").stringValue()).isEqualTo("ProblemV1");
            }
        }

        @Test
        void anUndeclaredStatusIsAGapAnd4xxOrDefaultDoNotCount() {
            JsonNode op = operation("responses", "PostUndeclaredInvalidRequests");
            assertThat(op.get("cases")).isEmpty();
            assertThat(op.get("declaresInvalidRequestStatus").booleanValue()).isFalse();
            assertThat(FIXTURES.get("responses").sources().report.gaps()).contains("/paths/~1undeclared/post");
            assertThat(FIXTURES.get("responses").text()).contains("Gaps --");
        }

        @Test
        void aConfiguredStatusIsExpected() {
            assertThat(operation("responses", "PostUnprocessableInvalidRequests").get("cases")).isEmpty();
            JsonNode op = operation("responses-422", "PostUnprocessableInvalidRequests");
            assertThat(op.get("cases")).isNotEmpty();
            op.get("cases").forEach(c -> assertThat(c.get("expectedStatus").intValue()).isEqualTo(422));
            assertThat(operation("responses-422", "PostDeclaredInvalidRequests").get("cases")).isEmpty();
        }

        @Test
        void aDeclaredStatusWithoutContentIsTheStatusOnly() {
            for (JsonNode c : operation("responses", "PostBareInvalidRequests").get("cases")) {
                assertThat(c.get("expectedContentTypes")).isEmpty();
                assertThat(c.get("responseBodyClass").isNull()).isTrue();
            }
        }

        @Test
        void aReferencedResponseIsResolved() {
            JsonNode c = operation("responses", "PostReferencedInvalidRequests").get("cases").get(0);
            assertThat(c.get("expectedContentTypes").toString()).isEqualTo("[\"application/problem+json\"]");
            assertThat(c.get("responseBodyClass").stringValue()).isEqualTo("ProblemV1");
        }

        @Test
        void everyDeclaredContentTypeIsRecorded() {
            JsonNode c = operation("responses", "PostSeveralInvalidRequests").get("cases").get(0);
            assertThat(c.get("expectedContentTypes").toString())
                    .isEqualTo("[\"application/problem+json\",\"application/json\"]");
        }

        @Test
        void anOperationWithoutConstrainedInputIsNoGap() {
            JsonNode op = operation("responses", "GetUnconstrainedInvalidRequests");
            assertThat(op.get("cases")).isEmpty();
            assertThat(FIXTURES.get("responses").sources().report.gaps()).doesNotContain("/paths/~1unconstrained/get");
        }
    }

    @Nested
    @DisplayName("T13.10 Canonical order, ids and representatives")
    class OrderAndIds {

        @Test
        void theOrderIsLocationThenKeywordThenNameOrPointer() {
            for (String fixture : FIXTURES.keySet()) {
                for (JsonNode op : FIXTURES.get(fixture).report().get("invalidRequests")) {
                    List<JsonNode> list = new ArrayList<>();
                    op.get("cases").forEach(list::add);
                    for (int i = 1; i < list.size(); i++) {
                        assertThat(compare(list.get(i - 1), list.get(i))).as("%s before %s",
                                list.get(i - 1).get("id"), list.get(i).get("id")).isLessThanOrEqualTo(0);
                    }
                }
            }
        }

        @Test
        void idsAreUniqueIdentifiersOnceConvertedAndExactlyTheFirstIsTheRepresentative() {
            for (String fixture : FIXTURES.keySet()) {
                for (JsonNode op : FIXTURES.get(fixture).report().get("invalidRequests")) {
                    List<String> ids = new ArrayList<>();
                    List<String> identifiers = new ArrayList<>();
                    int representatives = 0;
                    for (int i = 0; i < op.get("cases").size(); i++) {
                        JsonNode c = op.get("cases").get(i);
                        ids.add(c.get("id").stringValue());
                        String identifier = JavaText.variableName(c.get("id").stringValue());
                        assertThat(javax.lang.model.SourceVersion.isName(identifier)).as(identifier).isTrue();
                        identifiers.add(identifier);
                        if (c.get("representative").booleanValue()) {
                            representatives++;
                            assertThat(i).isZero();
                        }
                    }
                    assertThat(ids).doesNotHaveDuplicates();
                    assertThat(identifiers).doesNotHaveDuplicates();
                    assertThat(representatives).isEqualTo(op.get("cases").isEmpty() ? 0 : 1);
                }
            }
        }

        @Test
        void idsAreStableAcrossRuns(@TempDir Path again) {
            JsonNode first = FIXTURES.get("keywords").report().get("invalidRequests");
            JsonNode second = InvalidRequestFixtures.corpus("keywords", again).report().get("invalidRequests");
            assertThat(second).isEqualTo(first);
        }

        @Test
        void aCollisionResolvesDeterministically() {
            JsonNode flat = caseWithId("collisions", "body-a-b-type");
            JsonNode nested = caseWithId("collisions", "body-a-b-type-2");
            assertThat(flat.get("pointer").stringValue()).isEqualTo("/a-b");
            assertThat(nested.get("pointer").stringValue()).isEqualTo("/a/b");
            assertThat(caseWithId("collisions", "body-a-b-null-2").get("pointer").stringValue()).isEqualTo("/a/b");
            // The constraint names its case by the id it ends up with.
            assertThat(entry("collisions", "/a/b", "type").get("cases").toString()).contains("body-a-b-type-2");
        }

        private int compare(JsonNode a, JsonNode b) {
            List<String> locations = List.of("path", "query", "header", "body");
            int c = Integer.compare(locations.indexOf(a.get("in").stringValue()), locations.indexOf(b.get("in").stringValue()));
            if (c != 0) return c;
            c = Integer.compare(rank(a), rank(b));
            if (c != 0) return c;
            return where(a).compareTo(where(b));
        }

        private int rank(JsonNode c) {
            String keyword = c.get("id").stringValue().matches(".*-null(-[0-9]+)?") ? "null" : c.get("keyword").stringValue();
            return InvalidRequests.KEYWORD_ORDER.indexOf(keyword);
        }

        private String where(JsonNode c) {
            return c.get("name").isNull() ? c.get("pointer").stringValue() : c.get("name").stringValue();
        }
    }

    @Nested
    @DisplayName("T13.11 Degraded constructs")
    class Degraded {

        @Test
        void theOtherConstraintsStillGetCases() {
            assertThat(caseWithId("degraded", "body-name-minLength")).isNotNull();
            assertThat(caseWithId("degraded", "body-unknown-member")).isNotNull();
        }

        @Test
        void whatIsInsideTheDegradedConstructIsReported() {
            List<JsonNode> inside = coverage("degraded").stream()
                    .filter(e -> e.get("pointer").stringValue().startsWith("/shape")).toList();
            assertThat(inside).hasSizeGreaterThan(4);
            inside.forEach(e -> assertThat(reason(e)).isEqualTo("DEGRADED_CONSTRUCT"));
            assertThat(cases("degraded")).noneMatch(c -> c.get("pointer").stringValue().startsWith("/shape"));
        }
    }

    @Nested
    @DisplayName("Media types and structure")
    class MediaTypesAndStructure {

        @Test
        void eachJsonMediaTypeGetsItsOwnCasesNamedAfterIt() {
            assertThat(caseWithId("media", "body-application-json-name-maxLength").get("request").get("contentType")
                    .stringValue()).isEqualTo("application/json");
            assertThat(caseWithId("media", "body-application-merge-patch-json-name-maxLength").get("request")
                    .get("contentType").stringValue()).isEqualTo("application/merge-patch+json");
            coverage("media").stream().filter(e -> "application/xml".equals(e.get("mediaType").stringValue()))
                    .forEach(e -> assertThat(reason(e)).isEqualTo("MEDIA_TYPE_NOT_USED"));
        }

        @Test
        void whatTheWalkReportsRatherThanDerives() {
            assertThat(reason(entry("structure", "/negated", "not"))).isEqualTo("KEYWORD_NOT_MODELLED");
            assertThat(reason(entry("structure", "/negated", "type"))).isEqualTo("KEYWORD_NOT_MODELLED");
            assertThat(reason(entry("structure", "/pet", "oneOf"))).isEqualTo("NO_VIOLATION_RULE");
            assertThat(reason(entry("structure", "/name", "minLength"))).isEqualTo("ASSERTS_NOTHING");
            assertThat(reason(entry("structure", "/list", "uniqueItems"))).isEqualTo("ASSERTS_NOTHING");
            assertThat(coverage("structure").stream().filter(e -> e.get("pointer").stringValue().startsWith("/pet/"))
                    .map(InvalidRequestRulesTest::reason).distinct().toList()).containsExactly("NOT_ISOLATABLE");
            assertThat(coverage("structure").stream().filter(e -> e.has("uncovered")
                    && e.get("uncovered").get("code").stringValue().equals("BEYOND_RECURSION_DEPTH"))).isNotEmpty();
            assertThat(coverage("structure").stream().filter(e -> "tags".equals(e.get("name").stringValue()))
                    .map(InvalidRequestRulesTest::reason).distinct().toList())
                    .containsExactly("PARAMETER_STYLE_NOT_SUPPORTED");
        }
    }
}
