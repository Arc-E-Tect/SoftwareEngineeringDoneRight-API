package com.arc_e_tect.gradle.apionly.transcriberj.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Both of a contract's documents, read into one model. */
class AsyncContractParserTest {

    private static final Path CONTRACTS = Path.of(System.getProperty("transcriberj.referenceApi"));
    private static final Path OPENAPI = CONTRACTS.resolve("user-account/openapi.yaml");
    private static final Path ASYNCAPI = CONTRACTS.resolve("user-account/asyncapi.yaml");

    private static ContractModel userAccount() throws IOException {
        return ContractParser.parse(OPENAPI, ASYNCAPI);
    }

    @Test
    void withoutAnAsyncapiDocumentTheModelIsTheOpenapiOne() throws IOException {
        ContractModel model = ContractParser.parse(OPENAPI, null);

        assertThat(model.channels()).isEmpty();
        assertThat(model.asyncOperations()).isEmpty();
        assertThat(model.messages()).isEmpty();
        assertThat(model.components()).isEqualTo(ContractParser.parse(OPENAPI).components());
    }

    @Test
    void theAsyncapiDocumentsOwnTitleIsCarriedSeparatelyFromTheOpenapiOne() throws IOException {
        ContractModel model = userAccount();

        assertThat(model.asyncTitle()).isEqualTo("API-Only Async API");
        assertThat(model.title()).isNotEqualTo(model.asyncTitle());
        assertThat(ContractParser.parse(OPENAPI, null).asyncTitle()).isNull();
    }

    @Test
    void everyChannelCarriesItsAddressAndItsMessages() throws IOException {
        ContractModel model = userAccount();

        assertThat(model.channels()).singleElement().satisfies(channel -> {
            assertThat(channel.key()).isEqualTo("auditV1");
            assertThat(channel.address()).isEqualTo("apionly.useraccount.audit.v1");
            assertThat(channel.description()).isNotBlank();
            assertThat(channel.location()).isEqualTo("/channels/auditV1");
            assertThat(channel.provenance().fragmentPath())
                    .isEqualTo("asyncapi/channels/apionly/useraccount/AuditV1.yaml");
            assertThat(channel.messages()).hasSize(14);
        });
    }

    @Test
    void everyMessageCarriesItsContentTypeAndItsPayload() throws IOException {
        ContractModel model = userAccount();

        assertThat(model.messages()).allSatisfy(message -> {
            assertThat(message.contentType()).isEqualTo("application/json");
            assertThat(message.payload()).isNotNull();
            assertThat(message.provenance().fragmentPath()).startsWith("asyncapi/messages/");
        });
        assertThat(model.messages()).filteredOn(m -> m.key().equals("registrationInitiatedMessage"))
                .singleElement().satisfies(message -> {
                    assertThat(message.name()).isEqualTo("RegistrationInitiatedV1");
                    assertThat(message.title()).isEqualTo("Registration Initiated");
                    assertThat(message.payload().provenance().fragmentPath())
                            .isEqualTo("asyncapi/components/apionly/useraccount/schemas/RegistrationInitiatedEventV1.yaml");
                    assertThat(message.payload().location())
                            .isEqualTo("/channels/auditV1/messages/registrationInitiatedMessage/payload");
                });
    }

    @Test
    void everyOperationSaysWhatItDoesAndOnWhichChannel() throws IOException {
        ContractModel model = userAccount();

        assertThat(model.asyncOperations()).hasSize(14).allSatisfy(operation -> {
            assertThat(operation.action()).isEqualTo("send");
            assertThat(operation.channelKey()).isEqualTo("auditV1");
            assertThat(operation.messageKeys()).hasSize(1);
        });
        assertThat(model.asyncOperations()).filteredOn(o -> o.operationId().equals("publishRegistrationInitiated"))
                .singleElement().satisfies(operation -> {
                    assertThat(operation.messageKeys()).containsExactly("registrationInitiatedMessage");
                    assertThat(operation.summary()).isNotBlank();
                    assertThat(operation.location()).isEqualTo("/operations/publishRegistrationInitiated");
                });
    }

    @Test
    void aFragmentBothDocumentsUseIsOneComponentOfTheModel() throws IOException {
        ContractModel model = userAccount();

        String shared = "openapi/components/common/schemas/UsernameV1.yaml";
        assertThat(model.components()).filteredOn(c -> shared.equals(c.provenance().fragmentPath()))
                .hasSize(1);
        // Every payload is a component of the model, and the shared schema is not counted twice.
        assertThat(model.components()).filteredOn(c -> c.location().startsWith("/channels/")).hasSize(14);
        assertThat(model.components().stream().map(c -> c.provenance().fragmentPath()).distinct().count())
                .isEqualTo(model.components().size());
        assertThat(model.findings()).noneMatch(f -> f.construct() == Construct.FRAGMENT_BUNDLED_DIFFERENTLY);
    }

    @Test
    void oneFragmentBundledDifferentlyIntoTheTwoDocumentsIsReported(@TempDir Path directory) throws IOException {
        Path openapi = directory.resolve("openapi.yaml");
        Files.writeString(openapi, """
                openapi: 3.1.0
                info: {title: T, version: 1.0.0}
                paths: {}
                components:
                  schemas:
                    UsernameV1:
                      x-fragment-path: openapi/schemas/UsernameV1.yaml
                      type: string
                      minLength: 5
                """);
        Path asyncapi = directory.resolve("asyncapi.yaml");
        Files.writeString(asyncapi, """
                asyncapi: 3.0.0
                info: {title: T, version: 1.0.0}
                channels:
                  auditV1:
                    x-fragment-path: asyncapi/channels/Audit.yaml
                    address: audit.v1
                    messages:
                      registered:
                        x-fragment-path: asyncapi/messages/Registered.yaml
                        payload:
                          x-fragment-path: openapi/schemas/UsernameV1.yaml
                          type: string
                          minLength: 6
                """);

        ContractModel model = ContractParser.parse(openapi, asyncapi);

        assertThat(model.findings()).filteredOn(f -> f.construct() == Construct.FRAGMENT_BUNDLED_DIFFERENTLY)
                .singleElement().satisfies(finding -> {
                    assertThat(finding.location()).isEqualTo("/channels/auditV1/messages/registered/payload");
                    assertThat(finding.detail()).isEqualTo("openapi/schemas/UsernameV1.yaml");
                    assertThat(finding.treatment()).isEqualTo(Treatment.UNDECIDED);
                });
        // The component the OpenAPI document declares is the one that is generated.
        assertThat(model.components()).singleElement()
                .satisfies(c -> assertThat(c.location()).isEqualTo("/components/schemas/UsernameV1"));
    }

    @Test
    void twoDocumentsThatDisagreeAboutTheContractsVersionAreRefused(@TempDir Path directory) throws IOException {
        Path openapi = directory.resolve("openapi.yaml");
        Files.writeString(openapi, "openapi: 3.1.0\ninfo: {title: T, version: 1.0.0}\npaths: {}\n");
        Path asyncapi = directory.resolve("asyncapi.yaml");
        Files.writeString(asyncapi, "asyncapi: 3.0.0\ninfo: {title: T, version: 1.1.0}\nchannels: {}\n");

        assertThatThrownBy(() -> ContractParser.parse(openapi, asyncapi))
                .isInstanceOf(ContractModelException.class)
                .hasMessageContaining("says the contract is version 1.1.0, but its OpenAPI document says 1.0.0");
    }

    @Test
    void aDocumentThatIsNotAsyncapiThreeIsRefused(@TempDir Path directory) throws IOException {
        Path openapi = directory.resolve("openapi.yaml");
        Files.writeString(openapi, "openapi: 3.1.0\ninfo: {title: T, version: 1.0.0}\npaths: {}\n");
        Path asyncapi = directory.resolve("asyncapi.yaml");
        Files.writeString(asyncapi, "asyncapi: 2.6.0\ninfo: {title: T, version: 1.0.0}\nchannels: {}\n");

        assertThatThrownBy(() -> ContractParser.parse(openapi, asyncapi))
                .isInstanceOf(ContractModelException.class)
                .hasMessageContaining("not an AsyncAPI 3 document (asyncapi: 2.6.0)");
    }

    @Test
    void anOperationPointingSomewhereThisModelDoesNotUnderstandIsReportedRatherThanGuessed(@TempDir Path directory)
            throws IOException {
        Path openapi = directory.resolve("openapi.yaml");
        Files.writeString(openapi, "openapi: 3.1.0\ninfo: {title: T, version: 1.0.0}\npaths: {}\n");
        Path asyncapi = directory.resolve("asyncapi.yaml");
        Files.writeString(asyncapi, """
                asyncapi: 3.0.0
                info: {title: T, version: 1.0.0}
                channels: {}
                operations:
                  publishSomething:
                    action: send
                    channel:
                      $ref: 'https://elsewhere.invalid/channels/audit'
                """);

        ContractModel model = ContractParser.parse(openapi, asyncapi);

        assertThat(model.asyncOperations()).singleElement().satisfies(operation -> {
            assertThat(operation.channelKey()).isNull();
            assertThat(operation.messageKeys()).isEmpty();
        });
        assertThat(model.findings()).filteredOn(f -> f.location().equals("/operations/publishSomething/channel"))
                .singleElement().satisfies(f -> assertThat(f.treatment()).isEqualTo(Treatment.UNDECIDED));
    }

    @Test
    void theSharedSchemaIsTheSameBytesInBothDocuments() throws IOException {
        ContractModel model = userAccount();
        List<Component> shared = model.components().stream()
                .filter(c -> "openapi/components/common/schemas/UsernameV1.yaml"
                        .equals(c.provenance().fragmentPath())).toList();

        assertThat(shared).singleElement().satisfies(component ->
                assertThat(component.provenance().sha256()).isNotBlank());
    }
}
