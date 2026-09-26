package com.arc_e_tect.gradle.apionly.transcriberj.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.schema.CoreSchema;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2's acceptance: the model round-trips every component and operation of
 * the reference library's published contracts, and classifies what it finds.
 */
class ReferenceContractsTest {

    private static final String ROOT_DEPENDENCIES =
            "/paths/~1v1/get/responses/200/content/application~1json/schema/properties/dependencies";

    private static final Path CONTRACTS = Path.of(System.getProperty("transcriberj.referenceApi"));

    private static ContractModel model(String target) throws IOException {
        return ContractParser.parse(CONTRACTS.resolve(target).resolve("openapi.yaml"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> raw(String target) throws IOException {
        Load load = new Load(LoadSettings.builder().setSchema(new CoreSchema()).build());
        return (Map<String, Object>) load.loadFromString(
                Files.readString(CONTRACTS.resolve(target).resolve("openapi.yaml")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @ParameterizedTest
    @ValueSource(strings = {"system-admin", "user-account"})
    void everyComponentAndOperationRoundTrips(String target) throws IOException {
        ContractModel model = model(target);
        Map<String, Object> document = raw(target);
        Map<String, Object> components = map(document.get("components"));

        assertThat(ModelWriter.schemas(model)).isEqualTo(components.get("schemas"));
        assertThat(ModelWriter.otherComponents(model)).isEmpty();
        assertThat(model.responses()).isEmpty();
        assertThat(model.parameters()).isEmpty();
        assertThat(model.requestBodies()).isEmpty();
        assertThat(ModelWriter.paths(model)).isEqualTo(document.get("paths"));
        assertThat(model.openapi()).isEqualTo(document.get("openapi"));
        assertThat(model.title()).isEqualTo(map(document.get("info")).get("title"));
        assertThat(model.version()).isEqualTo(map(document.get("info")).get("version"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"system-admin", "user-account"})
    void everyComponentKnowsItsFragmentAndHash(String target) throws IOException {
        ContractModel model = model(target);
        Map<String, Object> schemas = map(map(raw(target).get("components")).get("schemas"));

        assertThat(model.components()).isNotEmpty().allSatisfy(component -> {
            assertThat(component.provenance().fragmentPath()).startsWith("openapi/components/").endsWith(".yaml");
            assertThat(component.provenance().sha256())
                    .isEqualTo(CanonicalJson.sha256(schemas.get(component.name())));
        });
    }

    @Test
    void theUserAccountContractHasItsSeventeenComponentsInBundleOrder() throws IOException {
        ContractModel model = model("user-account");

        assertThat(model.components()).extracting(Component::name).containsExactly(
                "ProblemDetailsV1", "InternalServerProblemV1", "UsernameV1", "UserV1", "UserNotFoundProblemV1",
                "UserRegistrationRequestV1", "UserRegistrationResponseV1", "ErrorDetailV1",
                "InvalidRequestProblemV1", "UsernameAlreadyRegisteredProblemV1",
                "RegisteredRequestCannotBeProcessedProblemV1", "UserRegistrationResendEmailRequestV1",
                "RegistrationNotFoundProblemV1", "UserAccountV1", "VerificationLinkSupersededProblemV1",
                "VerificationLinkExpiredProblemV1", "RegistrationProcessExpiredProblemV1");
        assertThat(model.component("UsernameV1")).get()
                .extracting(c -> c.provenance().fragmentPath())
                .isEqualTo("openapi/components/common/schemas/UsernameV1.yaml");
        assertThat(model.component("NoSuchSchema")).isEmpty();
    }

    @Test
    void propertiesKeepTheirOrderAndReferencesResolveToComponentNames() throws IOException {
        Schema user = model("user-account").component("UserV1").orElseThrow().schema();

        assertThat(user.properties()).containsOnlyKeys("username", "emailAddress");
        assertThat(List.copyOf(user.properties().keySet())).containsExactly("username", "emailAddress");
        assertThat(user.required()).containsExactly("username", "emailAddress");
        assertThat(user.properties().get("username").ref()).isEqualTo("UsernameV1");
        Schema email = user.properties().get("emailAddress");
        assertThat(email.types()).containsExactly("string");
        assertThat(email.format()).isEqualTo("email");
        assertThat(email.constraints().minLength()).isEqualTo(6);
        assertThat(email.constraints().maxLength()).isEqualTo(254);
        assertThat(email.description()).isEqualTo("The email address associated with the account.");
    }

    @Test
    void constantsAndConstraintsAreTyped() throws IOException {
        ContractModel model = model("user-account");
        Schema username = model.component("UsernameV1").orElseThrow().schema();
        assertThat(username.constraints().pattern()).isEqualTo("^[a-z][a-z0-9_-]*$");
        assertThat(username.annotations()).containsKey("examples");

        Schema problem = model.component("UserNotFoundProblemV1").orElseThrow().schema();
        assertThat(problem.allOf()).hasSize(2);
        assertThat(problem.allOf().get(0).ref()).isEqualTo("ProblemDetailsV1");
        Map<String, Schema> fixed = problem.allOf().get(1).properties();
        assertThat(fixed.get("status").constValue()).isEqualTo(new Const(404));
        assertThat(fixed.get("title").constValue()).isEqualTo(new Const("User Not Found"));
        assertThat(fixed.get("detail").constValue()).isNull();
    }

    @Test
    void operationsCarryWhatTheEndpointConstantsNeed() throws IOException {
        ContractModel model = model("user-account");

        assertThat(model.operations()).extracting(o -> o.method() + " " + o.path() + " " + o.operationId())
                .containsExactly(
                        "GET /v1 GetRoot",
                        "GET /v1/actuator/health GetHealth",
                        "GET /v1/users/{username} GetUser",
                        "POST /v1/users/registrations InitiateUserRegistration",
                        "POST /v1/users/registrations/verification-email ResendVerificationEmail",
                        "GET /v1/users/registrations/completion/{verificationLink} CompleteUserRegistration");

        Operation getUser = model.operation("GetUser").orElseThrow();
        assertThat(getUser.parameters()).singleElement().satisfies(p -> {
            assertThat(p.name()).isEqualTo("username");
            assertThat(p.in()).isEqualTo("path");
            assertThat(p.required()).isTrue();
            assertThat(p.schema().ref()).isEqualTo("UsernameV1");
        });
        assertThat(getUser.responses()).extracting(Response::status).containsExactly("200", "404", "500");
        assertThat(getUser.responses().get(1).content()).singleElement().satisfies(m -> {
            assertThat(m.contentType()).isEqualTo("application/problem+json");
            assertThat(m.schema().ref()).isEqualTo("UserNotFoundProblemV1");
        });

        Operation initiate = model.operation("InitiateUserRegistration").orElseThrow();
        assertThat(initiate.method()).isEqualTo(HttpMethod.POST);
        assertThat(initiate.requestBody().required()).isTrue();
        assertThat(initiate.requestBody().content()).singleElement()
                .extracting(m -> m.schema().ref()).isEqualTo("UserRegistrationRequestV1");
        assertThat(model.operation("NoSuchOperation")).isEmpty();
    }

    @Test
    void theClassifiedConstructsAreExactlyTheOnesTheContractUses() throws IOException {
        assertThat(model("user-account").findings()).containsExactlyInAnyOrder(
                new Finding("/components/schemas/ProblemDetailsV1",
                        Construct.ADDITIONAL_PROPERTIES, Treatment.REPRESENTED, "additionalProperties: true"),
                new Finding(ROOT_DEPENDENCIES,
                        Construct.ADDITIONAL_PROPERTIES, Treatment.REPRESENTED, "additionalProperties: a schema"),
                new Finding(ROOT_DEPENDENCIES + "/additionalProperties/properties/versionName",
                        Construct.MULTIPLE_TYPES, Treatment.UNDECIDED, "type: string, null"),
                new Finding(ROOT_DEPENDENCIES + "/additionalProperties/properties/versionCode",
                        Construct.MULTIPLE_TYPES, Treatment.UNDECIDED, "type: number, null"),
                new Finding("/paths/~1v1~1users~1registrations~1completion~1{verificationLink}/get/responses/422"
                        + "/content/application~1problem+json/schema",
                        Construct.ONE_OF_REF_BRANCHES, Treatment.REPRESENTED,
                        "oneOf: VerificationLinkExpiredProblemV1, RegistrationProcessExpiredProblemV1"));
        assertThat(model("system-admin").findings()).extracting(Finding::construct)
                .containsExactlyInAnyOrder(Construct.ADDITIONAL_PROPERTIES, Construct.ADDITIONAL_PROPERTIES,
                        Construct.MULTIPLE_TYPES, Construct.MULTIPLE_TYPES);
    }
}
