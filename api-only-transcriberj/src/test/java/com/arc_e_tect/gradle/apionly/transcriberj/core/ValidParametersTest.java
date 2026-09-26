package com.arc_e_tect.gradle.apionly.transcriberj.core;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T12.8: path, query and header values as they travel, for integer, number, boolean,
 * patterned and enumerated parameters; T12.1 checks each against its schema once
 * converted as a server would. Array, object, cookie and non-default-style parameters
 * are reported as not supported yet and given no value; Accept, Content-Type and
 * Authorization headers are ignored.
 */
@DisplayName("T12.8 Parameters")
class ValidParametersTest {

    private static final String GET_ITEM = "/paths/~1items~1{id}~1{flag}~1{code}/get";

    @TempDir
    static Path directory;

    static ValidValueFixtures.Fixture parameters;

    @BeforeAll
    static void generate() {
        parameters = ValidValueFixtures.corpus("parameters", directory);
    }

    private static JsonNode request(String className, String variant) {
        return parameters.request(className).get(variant).get("value");
    }

    private static List<String> pairs(JsonNode list) {
        return ValidValueFixtures.list(list).stream()
                .map(p -> p.get("name").stringValue() + "=" + p.get("value").stringValue()).toList();
    }

    @Test
    void pathParametersTravelAsStringsInTemplateOrder() {
        JsonNode r = request("GetItemOperation", "requiredRequest");
        assertThat(r.get("pathTemplate").stringValue()).isEqualTo("/items/{id}/{flag}/{code}");
        // An integer (the operation's own id, not the path item's string one), a boolean, a pattern.
        assertThat(ValidValueFixtures.list(r.get("pathParameters")).stream().map(JsonNode::stringValue))
                .containsExactly("1", "false", "AAA");
    }

    @Test
    void theRequiredRequestHasTheRequiredQueryParametersAndHeaders() {
        JsonNode r = request("GetItemOperation", "requiredRequest");
        // A number with multipleOf, an enum, and a $ref parameter.
        assertThat(pairs(r.get("query"))).containsExactly("weight=1.5", "status=open", "page=1");
        // From the path item, with a pattern; Accept, Content-Type and Authorization ignored.
        assertThat(pairs(r.get("headers"))).containsExactly("X-Trace=t-aaaa");
    }

    @Test
    void theFullRequestAddsTheOptionalOnesThatAreSupported() {
        JsonNode r = request("GetItemOperation", "fullRequest");
        assertThat(pairs(r.get("query"))).containsExactly("weight=1.5", "status=open", "limit=10", "page=1");
        assertThat(pairs(r.get("headers"))).containsExactly("X-Trace=t-aaaa");
    }

    @Test
    void whatIsNotSupportedYetIsReportedAndGivenNoValue() {
        assertThat(parameters.sources().report.unsupportedParameters()).extracting(
                        GenerationReport.UnsupportedParameter::className, GenerationReport.UnsupportedParameter::name,
                        GenerationReport.UnsupportedParameter::location, GenerationReport.UnsupportedParameter::reason)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("GetItemOperation", "tags", GET_ITEM + "/parameters/10",
                                "a parameter whose schema is an array is not supported yet"),
                        org.assertj.core.groups.Tuple.tuple("GetItemOperation", "filter", GET_ITEM + "/parameters/11",
                                "a parameter whose schema is an object is not supported yet"),
                        org.assertj.core.groups.Tuple.tuple("GetItemOperation", "sort", GET_ITEM + "/parameters/12",
                                "style spaceDelimited is not supported yet; only form is, the default for a query "
                                        + "parameter"),
                        org.assertj.core.groups.Tuple.tuple("GetItemOperation", "single", GET_ITEM + "/parameters/13",
                                "explode: false is not supported yet; only the default, true, is"),
                        org.assertj.core.groups.Tuple.tuple("GetItemOperation", "session", GET_ITEM + "/parameters/14",
                                "a parameter in cookie is not supported yet"),
                        org.assertj.core.groups.Tuple.tuple("SearchOperation", "ids", "/paths/~1search/post/parameters/0",
                                "a parameter whose schema is an array is not supported yet"));
        assertThat(pairs(request("GetItemOperation", "fullRequest").get("query")))
                .noneMatch(p -> p.startsWith("tags=") || p.startsWith("filter=") || p.startsWith("sort=")
                        || p.startsWith("single=") || p.startsWith("session="));
    }

    @Test
    void theIgnoredHeadersAreNeitherGivenAValueNorReported() {
        for (String variant : new String[]{"requiredRequest", "fullRequest"}) {
            assertThat(pairs(request("GetItemOperation", variant).get("headers")))
                    .noneMatch(h -> h.startsWith("Accept=") || h.startsWith("Content-Type=")
                            || h.startsWith("Authorization="));
        }
        assertThat(parameters.sources().report.unsupportedParameters())
                .noneMatch(u -> List.of("Accept", "Content-Type", "Authorization").contains(u.name()));
    }

    @Test
    void aRequiredParameterThatIsNotSupportedLeavesTheRequestWithoutAValue() {
        for (String variant : new String[]{"requiredRequest", "fullRequest"}) {
            assertThat(parameters.request("SearchOperation").get(variant).get("unsatisfiable").get("reason")
                    .stringValue()).isEqualTo("required parameter ids: a parameter whose schema is an array is not "
                    + "supported yet");
        }
        assertThatThrownBy(() -> parameters.sources().call("SearchOperation", "requiredRequest", new Class<?>[0]))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("required parameter ids");
    }

    @Test
    void aBodyIsSentAsTheFirstJsonContentTypeAndOnlyAsJson() {
        JsonNode note = request("PostNoteOperation", "requiredRequest");
        assertThat(note.get("contentType").stringValue()).isEqualTo("application/merge-patch+json");
        assertThat(note.get("body")).isEqualTo(Oracle.JSON.readTree("{\"text\":\"aa\"}"));
        assertThat(request("PostNoteOperation", "fullRequest").get("body"))
                .isEqualTo(Oracle.JSON.readTree("{\"text\":\"aa\",\"exact\":false}"));
        assertThat(parameters.request("PutDocumentOperation").get("requiredRequest").get("unsatisfiable")
                .get("reason").stringValue())
                .isEqualTo("no JSON content type among [application/xml]; this generator writes JSON bodies only");
    }

    @Test
    void onlyAnOptionalBodyGivesARequestWithoutOne() {
        assertThat(parameters.request("PostNoteOperation").has("noBodyRequest")).isTrue();
        assertThat(parameters.request("SearchOperation").has("noBodyRequest")).isFalse();
        assertThat(parameters.request("GetItemOperation").has("noBodyRequest")).isFalse();
        assertThat(parameters.sources().source("SearchOperation")).doesNotContain("noBodyRequest");
    }
}
