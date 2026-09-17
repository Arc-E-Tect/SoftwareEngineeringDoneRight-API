package com.arc_e_tect.gradle.apionly.transcriberj.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 5: a class per operation, with the constants a test needs to call it, so that
 * a wrong endpoint name fails at compile time; and an index of every path for tools
 * that read test sources without a classpath.
 */
class OperationClassesTest {

    @TempDir
    Path directory;

    private GeneratedSources userAccount() {
        return GeneratedSources.generate(
                GeneratedSources.FIXTURES.resolve("contracts/user-account/openapi.yaml"), "1.0.0", directory,
                GeneratedSources.settings("user-account"), List.of());
    }

    @Test
    void everyOperationOfTheUserAccountContractHasAClass() throws Throwable {
        GeneratedSources g = userAccount();

        assertThat(g.classNames()).contains("GetRootOperation", "GetHealthOperation", "GetUserOperation",
                "InitiateUserRegistrationOperation", "ResendVerificationEmailOperation",
                "CompleteUserRegistrationOperation");
        assertThat(Modifier.isPublic(g.type("GetUserOperation").getModifiers())).isTrue();

        assertThat(g.constant("GetRootOperation", "PATH")).isEqualTo("/v1");
        assertThat(g.constant("GetHealthOperation", "PATH")).isEqualTo("/v1/actuator/health");
        assertThat(g.constant("GetUserOperation", "OPERATION_ID")).isEqualTo("GetUser");
        assertThat(g.constant("GetUserOperation", "METHOD")).isEqualTo("GET");
        assertThat(g.constant("GetUserOperation", "PATH")).isEqualTo("/v1/users/{username}");
        assertThat(g.constant("GetUserOperation", "STATUS_200")).isEqualTo(200);
        assertThat(g.constant("GetUserOperation", "CONTENT_TYPE_200")).isEqualTo("application/json");
        assertThat(g.constant("GetUserOperation", "STATUS_404")).isEqualTo(404);
        assertThat(g.constant("GetUserOperation", "CONTENT_TYPE_404")).isEqualTo("application/problem+json");
        assertThat(g.constant("GetUserOperation", "LOCATION")).isEqualTo("/paths/~1v1~1users~1{username}/get");
        assertThat(g.constant("GetUserOperation", "CONTRACT_VERSION")).isEqualTo("1.0.0");
        assertThat((String) g.constant("GetUserOperation", "OPERATION_SHA256")).hasSize(64);
        assertThat(g.call("GetUserOperation", "path", new Class<?>[]{String.class}, "alice"))
                .isEqualTo("/v1/users/alice");

        assertThat(g.constant("InitiateUserRegistrationOperation", "METHOD")).isEqualTo("POST");
        assertThat(g.constant("InitiateUserRegistrationOperation", "REQUEST_CONTENT_TYPE"))
                .isEqualTo("application/json");
        assertThat(g.constant("InitiateUserRegistrationOperation", "STATUS_202")).isEqualTo(202);
        assertThat(g.call("CompleteUserRegistrationOperation", "path", new Class<?>[]{String.class}, ".*"))
                .isEqualTo("/v1/users/registrations/completion/.*");

        // A path without placeholders has only its PATH.
        assertThat(Arrays.stream(g.type("GetRootOperation").getDeclaredMethods()).map(m -> m.getName()))
                .doesNotContain("path");
        assertThat((List<?>) g.constant("ContractManifest", "ENTRIES")).hasSize(25);
    }

    @Test
    void theEndpointIndexListsEveryGeneratedPath() throws Exception {
        Path index = directory.resolve("index/contract-endpoints.properties");
        GeneratedSources.generate(GeneratedSources.FIXTURES.resolve("contracts/user-account/openapi.yaml"),
                "1.0.0", directory, GeneratedSources.settings("user-account"), List.of(), index);

        assertThat(Files.readAllLines(index)).containsExactly(
                "# The path of every class the API-Only TranscriberJ generated from contract user-account 1.0.0,",
                "# keyed ClassName.PATH, for tools that read test sources without a classpath.",
                "GetRootOperation.PATH=/v1",
                "GetHealthOperation.PATH=/v1/actuator/health",
                "GetUserOperation.PATH=/v1/users/{username}",
                "InitiateUserRegistrationOperation.PATH=/v1/users/registrations",
                "ResendVerificationEmailOperation.PATH=/v1/users/registrations/verification-email",
                "CompleteUserRegistrationOperation.PATH=/v1/users/registrations/completion/{verificationLink}",
                "GetRootResponse200.PATH=/v1",
                "GetHealthResponse200.PATH=/v1/actuator/health");
    }

    @Test
    void responsesContentTypesPlaceholdersAndNamesOfEveryShape() throws Throwable {
        GeneratedSources g = GeneratedSources.generate("""
                openapi: 3.1.0
                info: {title: T, version: 1.0.0}
                paths:
                  /orgs/{org-id}/items/{class}:
                    put:
                      requestBody:
                        content:
                          application/json: {}
                          application/xml: {}
                      responses:
                        '204': {description: none}
                        4XX:
                          description: client
                          content:
                            application/problem+json: {}
                        default:
                          description: other
                          content:
                            application/json: {}
                            text/plain: {}
                    delete:
                      operationId: remove
                      requestBody:
                        description: nothing
                      responses: {}
                  /é:
                    get:
                      operationId: accented
                """, directory, List.of());

        String put = "PutOrgsByOrgIdItemsByClassOperation";
        assertThat(g.constant(put, "METHOD")).isEqualTo("PUT");
        assertThat(g.source(put)).doesNotContain("OPERATION_ID").doesNotContain("REQUEST_CONTENT_TYPE =");
        assertThat(g.constant(put, "REQUEST_CONTENT_TYPES")).isEqualTo(List.of("application/json", "application/xml"));
        assertThat(g.constant(put, "STATUS_204")).isEqualTo(204);
        assertThat(g.source(put)).doesNotContain("CONTENT_TYPE_204").doesNotContain("STATUS_4XX =")
                .doesNotContain("STATUS_DEFAULT");
        assertThat(g.constant(put, "CONTENT_TYPE_4XX")).isEqualTo("application/problem+json");
        assertThat(g.constant(put, "CONTENT_TYPES_DEFAULT")).isEqualTo(List.of("application/json", "text/plain"));
        assertThat(g.call(put, "path", new Class<?>[]{String.class, String.class}, "o1", "c2"))
                .isEqualTo("/orgs/o1/items/c2");
        assertThat(g.source(put)).contains("path(String orgId, String classValue)");
        assertThatThrownBy(() -> g.call(put, "path", new Class<?>[]{String.class, String.class}, null, "c"))
                .isInstanceOf(NullPointerException.class).hasMessage("orgId");

        assertThat(g.constant("RemoveOperation", "METHOD")).isEqualTo("DELETE");
        assertThat(g.source("RemoveOperation")).doesNotContain("REQUEST_CONTENT_TYPE").doesNotContain("STATUS_");
        assertThat(g.constant("AccentedOperation", "PATH")).isEqualTo("/é");
        assertThat(g.report.recommendations()).extracting(GenerationReport.Recommendation::advice)
                .noneMatch(a -> a.contains("operationId"));
    }

    @Test
    void anOperationClassThatCollidesWithAComponentIsRefused() {
        assertThatThrownBy(() -> GeneratedSources.generate("""
                openapi: 3.1.0
                info: {title: T, version: 1.0.0}
                paths:
                  /a:
                    get:
                      operationId: getA
                components:
                  schemas:
                    X:
                      x-fragment-path: openapi/GetAOperation.yaml
                      type: string
                """, directory, List.of()))
                .isInstanceOf(GenerationException.class)
                .hasMessageContaining("openapi/GetAOperation.yaml and /paths/~1a/get would all be named GetAOperation");
    }

    @Test
    void anIndexValueIsEscapedAsAPropertiesFileNeedsIt() throws Exception {
        Path index = directory.resolve("index.properties");
        Path contract = directory.resolve("openapi.yaml");
        Files.writeString(contract, """
                openapi: 3.1.0
                info: {title: T, version: 1.0.0}
                paths:
                  '/a\\b/é':
                    get:
                      operationId: odd
                """);
        GeneratedSources.generate(contract, "1.0.0", directory, GeneratedSources.settings("c"), List.of(), index);
        assertThat(Files.readAllLines(index)).contains("OddOperation.PATH=/a" + (char) 92 + (char) 92 + "b/"
                + (char) 92 + "u00e9");
    }
}
