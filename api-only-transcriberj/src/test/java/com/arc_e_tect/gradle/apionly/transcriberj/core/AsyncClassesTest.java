package com.arc_e_tect.gradle.apionly.transcriberj.core;

import com.arc_e_tect.gradle.apionly.transcriberj.spi.Settings;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a contract's AsyncAPI document generates: a class per event payload, one per
 * channel, and one per operation.
 */
class AsyncClassesTest {

    @TempDir
    static Path directory;

    static GeneratedSources generated;

    @BeforeAll
    static void generate() {
        generated = GeneratedSources.generate(
                GeneratedSources.FIXTURES.resolve("contracts/user-account/openapi.yaml"),
                GeneratedSources.FIXTURES.resolve("contracts/user-account/asyncapi.yaml"),
                "1.0.0", directory, GeneratedSources.settings("user-account"), List.of());
    }

    @Test
    void everyEventPayloadIsAClassLikeAnyOtherSchemaComponent() throws Throwable {
        assertThat(generated.source("RegistrationInitiatedEventV1"))
                .contains("public final class RegistrationInitiatedEventV1")
                .contains("FRAGMENT_PATH = \"asyncapi/components/iff/useraccount/schemas/"
                        + "RegistrationInitiatedEventV1.yaml\"");

        assertThat(generated.call("RegistrationInitiatedEventV1", "body", new Class<?>[]{String.class, String.class,
                String.class}, "alice", "alice@wonderland.com", "2026-01-15T10:30:00Z"))
                .isEqualTo("{\"username\":\"alice\",\"emailAddress\":\"alice@wonderland.com\","
                        + "\"occurredAt\":\"2026-01-15T10:30:00Z\"}\n");
    }

    @Test
    void theSchemaBothDocumentsShareIsOneClass() {
        assertThat(generated.source("UsernameV1")).contains("FRAGMENT_PATH = "
                + "\"openapi/components/common/schemas/UsernameV1.yaml\"");
        assertThat(generated.names()).filteredOn(name -> name.equals("UsernameV1")).hasSize(1);
        // The event payload's username field is the shared class, not a second copy of it.
        assertThat(generated.names()).noneMatch(name -> name.startsWith("UsernameV1") && !name.equals("UsernameV1"));
    }

    @Test
    void aChannelSaysWhereItsMessagesTravel() {
        assertThat(generated.constant("AuditV1Channel", "ADDRESS")).isEqualTo("iff.useraccount.audit.v1");
        assertThat(generated.constant("AuditV1Channel", "LOCATION")).isEqualTo("/channels/auditV1");
        assertThat(generated.constant("AuditV1Channel", "FRAGMENT_PATH"))
                .isEqualTo("asyncapi/channels/iff/useraccount/AuditV1.yaml");
        assertThat(generated.constant("AuditV1Channel", "CONTRACT_VERSION")).isEqualTo("1.0.0");
        assertThat(generated.source("AuditV1Channel")).contains("Audit events for the user-account domain");
    }

    @Test
    void anOperationSaysWhatItDoesAndWithWhichMessage() {
        assertThat(generated.constant("PublishRegistrationInitiatedOperation", "OPERATION_ID"))
                .isEqualTo("publishRegistrationInitiated");
        assertThat(generated.constant("PublishRegistrationInitiatedOperation", "ACTION")).isEqualTo("send");
        assertThat(generated.constant("PublishRegistrationInitiatedOperation", "CHANNEL_ADDRESS"))
                .isEqualTo("iff.useraccount.audit.v1");
        assertThat(generated.constant("PublishRegistrationInitiatedOperation", "CONTENT_TYPE"))
                .isEqualTo("application/json");
        assertThat(generated.constant("PublishRegistrationInitiatedOperation", "MESSAGE_NAME"))
                .isEqualTo("RegistrationInitiatedV1");
        assertThat(generated.constant("PublishRegistrationInitiatedOperation", "LOCATION"))
                .isEqualTo("/operations/publishRegistrationInitiated");
    }

    @Test
    void everyEventOfTheContractIsGenerated() {
        assertThat(generated.names()).contains(
                "RegistrationInitiatedEventV1", "VerificationEmailSentEventV1",
                "VerificationEmailResendRequestedEventV1", "NewVerificationEmailSentEventV1",
                "UserAccountCreatedEventV1", "ConfirmationEmailSentEventV1", "PersonDeletedEventV1",
                "RegistrationAttemptedWithInvalidLinkEventV1", "RegistrationAttemptedWithExpiredLinkEventV1",
                "RegistrationAttemptedWithExpiredRegistrationProcessEventV1",
                "RegistrationAttemptedWithReservedUsernameEventV1",
                "RegistrationAttemptedWithTakenUsernameEventV1",
                "RegistrationAttemptedWithTakenEmailAddressEventV1",
                "RegistrationAttemptedWithInitiatedEmailAddressEventV1");
        assertThat(generated.names()).filteredOn(name -> name.endsWith("Operation"))
                .hasSizeGreaterThanOrEqualTo(14);
        assertThat(generated.report.degraded()).isEmpty();
    }

    @Test
    void generateDocsDescribesAnEventsFieldsFromTheContract(@TempDir Path into) throws Throwable {
        GeneratedSources docs = GeneratedSources.generate(
                GeneratedSources.FIXTURES.resolve("contracts/user-account/openapi.yaml"),
                GeneratedSources.FIXTURES.resolve("contracts/user-account/asyncapi.yaml"), "1.0.0", into,
                new Settings("user-account", GeneratedSources.PACKAGE, true, "PLACEHOLDER", 3), List.of());

        List<?> fields = (List<?>) docs.call("RegistrationInitiatedEventV1", "fields",
                new Class<?>[]{String.class}, "");

        assertThat(fields).extracting(Object::toString)
                .anySatisfy(field -> assertThat(field).contains("path=emailAddress,")
                        .contains("description=The email address of the user whose registration was initiated."))
                // The username is the shared schema, so it describes itself as the OpenAPI side does.
                .anySatisfy(field -> assertThat(field).contains("path=username,")
                        .contains("description=The unique username of the account."));
        assertThat(docs.call("RegistrationInitiatedEventV1", "description", new Class<?>[]{}))
                .isEqualTo("Audit event published when a user registration is initiated.");
    }

    @Test
    void anEventConstructNoRuleRepresentsDegradesTheMethodRatherThanTheBuild(@TempDir Path into) throws Exception {
        Path openapi = into.resolve("openapi.yaml");
        Files.writeString(openapi, """
                openapi: 3.1.0
                info: {title: T, version: 1.0.0}
                paths: {}
                """);
        Path asyncapi = into.resolve("asyncapi.yaml");
        Files.writeString(asyncapi, """
                asyncapi: 3.0.0
                info: {title: T, version: 1.0.0}
                channels:
                  auditV1:
                    x-fragment-path: asyncapi/channels/Audit.yaml
                    address: audit.v1
                    messages:
                      odd:
                        x-fragment-path: asyncapi/messages/Odd.yaml
                        contentType: application/json
                        payload:
                          x-fragment-path: asyncapi/schemas/OddEventV1.yaml
                          type: object
                          properties:
                            either: {type: [string, 'null']}
                """);

        GeneratedSources degraded = GeneratedSources.generate(openapi, asyncapi, "1.0.0", into,
                GeneratedSources.settings("events"), List.of());

        assertThat(degraded.report.degraded()).isNotEmpty();
        assertThat(degraded.source("OddEventV1")).contains("UnsupportedOperationException").contains("MULTIPLE_TYPES");
        // The channel's class is generated all the same, named after its own fragment.
        assertThat(degraded.names()).contains("AuditChannel");
    }

    @Test
    void theManifestKnowsTheAsyncClassesToo() {
        assertThat(generated.source("ContractManifest"))
                .contains("RegistrationInitiatedEventV1")
                .contains("AuditV1Channel")
                .contains("PublishRegistrationInitiatedOperation");
    }
}
