package com.arc_e_tect.gradle.apionly.transcriberj.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A document the model cannot stand on is refused by name and place. These are
 * not unrepresentable constructs, which degrade; they are documents that are not
 * a bundled OpenAPI 3 contract at all.
 */
class ContractParserErrorsTest {

    private static void refused(String yaml, String message) {
        assertThatThrownBy(() -> ContractParser.parse(yaml, "broken.yaml"))
                .isInstanceOf(ContractModelException.class)
                .hasMessageStartingWith("broken.yaml: ")
                .hasMessageContaining(message);
    }

    @Test
    void notYaml() {
        refused("openapi: [unclosed", "is not valid YAML");
    }

    @Test
    void notAMapping() {
        refused("- a list\n", "/ must be a mapping");
    }

    @Test
    void notOpenApiThree() {
        refused("swagger: '2.0'\ninfo: {title: t, version: '1'}\npaths: {}\n",
                "not an OpenAPI 3 document (openapi: null)");
        refused("openapi: 2.0\ninfo: {title: t, version: '1'}\npaths: {}\n",
                "not an OpenAPI 3 document (openapi: 2.0)");
    }

    @Test
    void wrongShapes() {
        refused("openapi: 3.1.0\ninfo: {title: t, version: '1'}\npaths: []\n", "/paths must be a mapping");
        refused("openapi: 3.1.0\ninfo: {title: [t], version: '1'}\npaths: {}\n", "/info/title must be a string");
        refused("""
                openapi: 3.1.0
                info: {title: t, version: '1'}
                paths:
                  /a:
                    get:
                      parameters: {}
                """, "/paths/~1a/get/parameters must be a list");
        refused("""
                openapi: 3.1.0
                info: {title: t, version: '1'}
                paths:
                  /a:
                    get:
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema: 42
                """, "/paths/~1a/get/responses/200/content/application~1json/schema must be a mapping or a boolean");
    }

    @Test
    void aVersionWrittenAsANumberIsReadAsText() {
        ContractModel model = ContractParser.parse(
                "openapi: 3.1.0\ninfo: {title: t, version: 2}\npaths: {}\n", "numbered.yaml");
        assertThat(model.version()).isEqualTo("2");
    }

    @Test
    void aDocumentWithoutInfoOrPathsIsStillAContract() {
        ContractModel model = ContractParser.parse("openapi: 3.1.0\n", "bare.yaml");
        assertThat(model.title()).isNull();
        assertThat(model.version()).isNull();
        assertThat(model.paths()).isEmpty();
        assertThat(model.operations()).isEmpty();
    }

    @Test
    void aDanglingReferenceNamesItself() {
        refused("""
                openapi: 3.1.0
                components:
                  schemas:
                    A: {$ref: '#/components/schemas/Missing'}
                """, "/components/schemas/A: $ref '#/components/schemas/Missing' names no component");
    }

    @Test
    void aReferenceAnywhereButToASchemaComponentIsRefused() {
        refused("""
                openapi: 3.1.0
                components:
                  schemas:
                    A: {$ref: 'other.yaml#/B'}
                """, "/components/schemas/A: $ref 'other.yaml#/B' is not a reference to a schema component; "
                + "a bundled contract has no other kind");
    }

    @Test
    void aReferenceWithEscapedCharactersResolves() {
        ContractModel model = ContractParser.parse("""
                openapi: 3.1.0
                components:
                  schemas:
                    a/b~c: {type: string}
                    User: {$ref: '#/components/schemas/a~1b~0c'}
                """, "escaped.yaml");
        assertThat(model.component("User").orElseThrow().schema().ref()).isEqualTo("a/b~c");
    }

    @Test
    void anUnknownHttpMethodIsNotAnOperation() {
        ContractModel model = ContractParser.parse("""
                openapi: 3.1.0
                paths:
                  /a:
                    fetch: {operationId: nope}
                    get: {operationId: yes}
                """, "methods.yaml");
        assertThat(model.operations()).extracting(Operation::operationId).containsExactly("yes");
        assertThat(model.paths().get(0).other()).containsKey("fetch");
    }

    @Test
    void aFileIsReadAsUtf8AndNamedByItsPath(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("openapi.yaml");
        Files.writeString(file, "openapi: [");
        assertThatThrownBy(() -> ContractParser.parse(file))
                .isInstanceOf(ContractModelException.class)
                .hasMessageStartingWith(file + ": ");
    }

    @Test
    void everyHttpMethodMapsToItsKey() {
        for (HttpMethod method : HttpMethod.values()) {
            assertThat(HttpMethod.fromKey(method.key())).contains(method);
        }
        assertThat(HttpMethod.fromKey("GET")).isEmpty();
    }
}
