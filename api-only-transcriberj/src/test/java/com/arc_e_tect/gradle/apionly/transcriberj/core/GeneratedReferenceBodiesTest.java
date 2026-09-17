package com.arc_e_tect.gradle.apionly.transcriberj.core;

import com.arc_e_tect.gradle.apionly.transcriberj.fixtures.BodyFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3's acceptance: the classes generated from the user-account contract write,
 * for every recorded input, the body the hand-written classes write.
 *
 * <p>Byte for byte, except UserV1, which is held to JSON equality by decision. Two
 * signatures differ from the hand-written ones by decision, and are called through
 * the adapters below: InvalidRequestProblemV1's list of errors, and
 * ProblemDetailsV1's optional members.
 */
class GeneratedReferenceBodiesTest {

    @TempDir
    static Path directory;

    static GeneratedSources generated;

    private static final ObjectMapper JSON = new ObjectMapper();

    @BeforeAll
    static void generate() {
        generated = GeneratedSources.generate(
                GeneratedSources.FIXTURES.resolve("contracts/user-account/openapi.yaml"), "1.0.0", directory,
                GeneratedSources.settings("user-account"), List.of());
    }

    @TestFactory
    Stream<DynamicTest> everyRecordedBodyIsWrittenByTheGeneratedClasses() {
        return BodyFixtures.cases(BodyFixtures.read()).stream().map(c -> DynamicTest.dynamicTest(c.id(), () -> {
            String actual = replay(c);
            if (c.comparison().equals("json-equal")) {
                assertThat(JSON.readTree(actual)).isEqualTo(JSON.readTree(c.expected()));
                assertThat(actual).endsWith("\n");
            } else {
                assertThat(actual).isEqualTo(c.expected());
            }
        }));
    }

    private static String replay(BodyFixtures.Case c) throws Throwable {
        JsonNode args = c.args();
        if (c.className().equals("InvalidRequestProblemV1") && args.size() == 3) {
            List<String> errors = new ArrayList<>();
            for (JsonNode message : args.get(1)) {
                errors.add((String) generated.call("ErrorDetailV1", "body",
                        new Class<?>[]{String.class, String.class}, message.textValue(), args.get(2).textValue()));
            }
            return (String) generated.call("InvalidRequestProblemV1", "body",
                    new Class<?>[]{String.class, String.class, List.class}, args.get(0).textValue(), null, errors);
        }
        if (c.className().equals("ProblemDetailsV1")) {
            return (String) generated.call("ProblemDetailsV1", "body",
                    new Class<?>[]{String.class, String.class, Integer.class, String.class, String.class},
                    args.get(0).textValue(), args.get(1).textValue(), args.get(2).intValue(),
                    args.get(3).textValue(), null);
        }
        return c.replay(GeneratedSources.PACKAGE, generated.compile());
    }

    @Test
    void theGeneratedClassesAreTheHandWrittenOnesPlusTheInlineResponsesAndSupport() {
        assertThat(generated.classNames()).containsExactly(
                "CompleteUserRegistrationOperation", "ContractField", "ContractJson", "ContractManifest",
                "ErrorDetailV1", "GetHealthOperation", "GetHealthResponse200", "GetRootOperation",
                "GetRootResponse200", "GetUserOperation", "InitiateUserRegistrationOperation",
                "InternalServerProblemV1", "InvalidRequestProblemV1", "ProblemDetailsV1",
                "RegisteredRequestCannotBeProcessedProblemV1", "RegistrationNotFoundProblemV1",
                "RegistrationProcessExpiredProblemV1", "ResendVerificationEmailOperation", "UserAccountV1",
                "UserNotFoundProblemV1", "UserRegistrationRequestV1", "UserRegistrationResendEmailRequestV1",
                "UserRegistrationResponseV1", "UserV1", "UsernameAlreadyRegisteredProblemV1", "UsernameV1",
                "VerificationLinkExpiredProblemV1", "VerificationLinkSupersededProblemV1");
    }

    @Test
    void visibilityFollowsWhatOutsideCodeNeeds() {
        assertThat(Modifier.isPublic(generated.type("ProblemDetailsV1").getModifiers())).isFalse();
        for (String exposed : List.of("UserV1", "UsernameV1", "ErrorDetailV1", "InvalidRequestProblemV1",
                "VerificationLinkExpiredProblemV1", "GetRootResponse200")) {
            assertThat(Modifier.isPublic(generated.type(exposed).getModifiers())).as(exposed).isTrue();
        }
    }

    @Test
    void constantsCarryProvenanceAndConstraints() {
        assertThat(generated.constant("UsernameV1", "FRAGMENT_PATH"))
                .isEqualTo("openapi/components/common/schemas/UsernameV1.yaml");
        assertThat(generated.constant("UsernameV1", "CONTRACT_VERSION")).isEqualTo("1.0.0");
        assertThat(generated.constant("UsernameV1", "MIN_LENGTH")).isEqualTo(5);
        assertThat(generated.constant("UsernameV1", "MAX_LENGTH")).isEqualTo(12);
        assertThat(generated.constant("UsernameV1", "PATTERN")).isEqualTo("^[a-z][a-z0-9_-]*$");
        assertThat(generated.constant("UserV1", "EMAIL_ADDRESS_MIN_LENGTH")).isEqualTo(6);
        assertThat(generated.constant("UserV1", "EMAIL_ADDRESS_FORMAT")).isEqualTo("email");
        assertThat(generated.constant("UserNotFoundProblemV1", "STATUS")).isEqualTo(404);
        assertThat(generated.constant("ProblemDetailsV1", "OPEN")).isEqualTo(true);
        assertThat(generated.constant("UserV1", "OPEN")).isEqualTo(false);
        assertThat(generated.constant("GetRootResponse200", "STATUS")).isEqualTo("200");
        assertThat(generated.constant("GetRootResponse200", "CONTENT_TYPE")).isEqualTo("application/json");
        assertThat(generated.constant("GetRootResponse200", "OPERATION_ID")).isEqualTo("GetRoot");
        assertThat(generated.constant("ContractManifest", "CONTRACT")).isEqualTo("user-account");
        assertThat(generated.constant("ContractManifest", "CONTRACT_VERSION")).isEqualTo("1.0.0");
        assertThat((List<?>) generated.constant("ContractManifest", "ENTRIES")).hasSize(25);
    }

    @Test
    void fieldsMatchTheHandWrittenDescriptorsInPathTypeAndOptionality() throws Throwable {
        List<?> fields = (List<?>) generated.call("InvalidRequestProblemV1", "fields",
                new Class<?>[]{String.class}, "");
        assertThat(fields).extracting(Object::toString).containsExactly(
                field("type", "string", false), field("title", "string", false),
                field("status", "number", false), field("detail", "string", false),
                field("instance", "string", true), field("errors", "array", true),
                field("errors[].message", "string", true), field("errors[].context", "string", true));

        List<?> user = (List<?>) generated.call("UserV1", "fields", new Class<?>[]{String.class}, "");
        assertThat(user).extracting(Object::toString).containsExactly(
                field("username", "string", false), field("emailAddress", "string", false));
    }

    private static String field(String path, String type, boolean optional) {
        return "ContractField[path=" + path + ", type=" + type + ", optional=" + optional
                + ", description=PLACEHOLDER, subsection=false, open=false]";
    }

    @Test
    void theReportRecommendsComponentsForTheInlineResponses() {
        assertThat(generated.report.degraded()).isEmpty();
        assertThat(generated.report.recommendations()).extracting(GenerationReport.Recommendation::location)
                .containsExactly("/paths/~1v1/get/responses/200/content/application~1json/schema",
                        "/paths/~1v1~1actuator~1health/get/responses/200/content/application~1json/schema");
        assertThat(generated.report.undecided()).hasSize(2);
    }
}
