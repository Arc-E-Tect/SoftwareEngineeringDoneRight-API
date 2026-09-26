package com.arc_e_tect.gradle.apionly.transcriberj.core;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The classes generated from the reference API's user-account contract: which there are,
 * which are public, and what their constants hold.
 */
class GeneratedReferenceBodiesTest {

    @TempDir
    static Path directory;

    static GeneratedSources generated;

    @BeforeAll
    static void generate() {
        generated = GeneratedSources.generate(
                GeneratedSources.CONTRACTS.resolve("user-account/openapi.yaml"), "1.0.0", directory,
                GeneratedSources.settings("user-account"), List.of());
    }

    @Test
    void theGeneratedClassesAreTheComponentsTheOperationsTheInlineResponsesAndSupport() {
        assertThat(generated.classNames()).containsExactly(
                "CompleteUserRegistrationInvalidRequests", "CompleteUserRegistrationOperation", "ContractField",
                "ContractJson", "ContractManifest", "ContractRequest",
                "ErrorDetailV1", "GetHealthInvalidRequests", "GetHealthOperation", "GetHealthResponse200",
                "GetRootInvalidRequests", "GetRootOperation", "GetRootResponse200", "GetUserInvalidRequests",
                "GetUserOperation", "InitiateUserRegistrationInvalidRequests", "InitiateUserRegistrationOperation",
                "InternalServerProblemV1", "InvalidRequestCase", "InvalidRequestProblemV1", "ProblemDetailsV1",
                "RegisteredRequestCannotBeProcessedProblemV1", "RegistrationNotFoundProblemV1",
                "RegistrationProcessExpiredProblemV1", "ResendVerificationEmailInvalidRequests",
                "ResendVerificationEmailOperation", "UserAccountV1",
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
    void fieldsHaveThePathTypeAndOptionalityTheContractGivesThem() throws Throwable {
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
                + ", description=, subsection=false, open=false]";
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
