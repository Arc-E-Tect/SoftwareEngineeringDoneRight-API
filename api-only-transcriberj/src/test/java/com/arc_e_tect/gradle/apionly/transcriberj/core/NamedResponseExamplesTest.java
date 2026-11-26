package com.arc_e_tect.gradle.apionly.transcriberj.core;

import com.arc_e_tect.gradle.apionly.transcriberj.model.Construct;
import com.arc_e_tect.gradle.apionly.transcriberj.model.Operation;
import com.arc_e_tect.gradle.apionly.transcriberj.model.Response;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.Emitter;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.EmitterContext;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.ManagedDependency;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.ResponseExample;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An emitter is given the named examples a response declares, through
 * {@link EmitterContext#responseExamples(String, String)}: in declaration order, each with its
 * name, its content type, its value as JSON text and where it is written, a reference to
 * {@code #/components/examples} resolved, and which of them a double answers with by default --
 * the one {@code x-transcriberj-examples} names as {@code default}, else the first. Every named
 * response example must satisfy its schema, as a named request example must.
 */
@DisplayName("Named response examples")
class NamedResponseExamplesTest {

    private static final String CHECK = "/paths/~1orders~1checks/post";
    private static final String ACCEPTED = "{check: order-check, outcome: accepted, findings: []}";
    private static final String OUT_OF_STOCK = "{check: order-check, outcome: rejected, findings: "
            + "[{rule: OUT_OF_STOCK, message: The item is out of stock.}]}";

    @TempDir
    Path directory;

    /** Records what the context gives for every declared response, and for responses that do not exist. */
    static class Capturing implements Emitter {

        final Map<String, List<ResponseExample>> seen = new LinkedHashMap<>();
        final List<List<ResponseExample>> unknown = new ArrayList<>();

        @Override
        public String id() {
            return "capturing";
        }

        @Override
        public List<ManagedDependency> dependencies() {
            return List.of();
        }

        @Override
        public Set<Construct> represents() {
            return Set.of();
        }

        @Override
        public void emit(EmitterContext context) {
            for (Operation operation : context.model().operations()) {
                String location = CoreClassNames.operationLocation(operation);
                for (Response response : operation.responses()) {
                    seen.put(location + " " + response.status(), context.responseExamples(location, response.status()));
                }
                unknown.add(context.responseExamples(location, "599"));
            }
            unknown.add(context.responseExamples("/paths/~1no~1such~1operation/get", "200"));
        }
    }

    /** A contract whose {@code 200} declares the given examples and extension, and whose {@code 422} refers to a component. */
    private static String contract(String examples, String extension) {
        return """
                openapi: 3.1.0
                info: {title: Orders, version: 1.0.0}
                paths:
                  /orders/checks:
                    post:
                      operationId: CheckOrder
                      requestBody:
                        required: true
                        content:
                          application/json:
                            schema: {$ref: '#/components/schemas/Order'}
                      responses:
                        '200':
                          description: The check's verdict.
                          content:
                            application/json:
                              schema: {$ref: '#/components/schemas/OrderCheck'}
                %s%s
                        '400': {description: Invalid.}
                        '422': {$ref: '#/components/responses/Unprocessable'}
                  /orders:
                    get:
                      operationId: ListOrders
                      responses:
                        '200':
                          description: The orders.
                          content:
                            application/json:
                              schema: {type: array, items: {$ref: '#/components/schemas/Order'}}
                components:
                  schemas:
                    Order:
                      x-fragment-path: openapi/components/Order.yaml
                      type: object
                      required: [item]
                      properties: {item: {type: string, minLength: 1}}
                    OrderCheck:
                      x-fragment-path: openapi/components/OrderCheck.yaml
                      type: object
                      required: [check, outcome]
                      properties:
                        check: {type: string, const: order-check}
                        outcome: {type: string, enum: [accepted, rejected]}
                        findings:
                          type: array
                          items:
                            type: object
                            required: [rule]
                            properties: {rule: {type: string}, message: {type: string}}
                    Problem:
                      x-fragment-path: openapi/components/Problem.yaml
                      type: object
                      required: [title]
                      properties: {title: {type: string}}
                  responses:
                    Unprocessable:
                      x-fragment-path: openapi/components/Unprocessable.yaml
                      description: The order cannot be checked.
                      content:
                        application/problem+json:
                          schema: {$ref: '#/components/schemas/Problem'}
                          examples:
                            Closed: {value: {title: The shop is closed.}}
                  examples:
                    OutOfStock:
                      value: %s
                """.formatted(examples == null ? "" : "              examples:\n" + examples.indent(16),
                extension == null ? "" : "              x-transcriberj-examples: " + extension + "\n", OUT_OF_STOCK);
    }

    private static String examples() {
        return "Accepted: {value: " + ACCEPTED + "}\nOutOfStock: {$ref: '#/components/examples/OutOfStock'}\n";
    }

    private Capturing generate(String yaml) {
        Capturing capturing = new Capturing();
        GeneratedSources.generate(yaml, directory, List.of(capturing));
        return capturing;
    }

    private static JsonNode json(String yamlOrJson) {
        return Oracle.JSON.readTree(Oracle.JSON.writeValueAsString(
                new org.snakeyaml.engine.v2.api.Load(org.snakeyaml.engine.v2.api.LoadSettings.builder().build())
                        .loadFromString(yamlOrJson)));
    }

    // ------------------------------------------------------------ what an emitter is given

    @Test
    void anEmitterIsGivenEveryNamedExampleInDeclarationOrderWithItsReferenceResolved() {
        List<ResponseExample> examples = generate(contract(examples(), null)).seen.get(CHECK + " 200");

        assertThat(examples).extracting(ResponseExample::name).containsExactly("Accepted", "OutOfStock");
        assertThat(examples).extracting(ResponseExample::contentType).containsOnly("application/json");
        assertThat(json(examples.get(0).text())).isEqualTo(json(ACCEPTED));
        assertThat(json(examples.get(1).text())).isEqualTo(json(OUT_OF_STOCK));
        assertThat(examples).extracting(ResponseExample::location).containsExactly(
                CHECK + "/responses/200/content/application~1json/examples/Accepted",
                CHECK + "/responses/200/content/application~1json/examples/OutOfStock");
    }

    @Test
    void withoutTheExtensionTheFirstExampleIsTheDefault() {
        List<ResponseExample> examples = generate(contract(examples(), null)).seen.get(CHECK + " 200");

        assertThat(examples).extracting(ResponseExample::isDefault).containsExactly(true, false);
    }

    @Test
    void theExtensionNamesTheDefault() {
        List<ResponseExample> examples = generate(contract(examples(), "{default: OutOfStock}")).seen.get(CHECK + " 200");

        assertThat(examples).extracting(ResponseExample::isDefault).containsExactly(false, true);
    }

    @Test
    void theExamplesOfAReferencedResponseAreGivenWhereTheComponentWritesThem() {
        List<ResponseExample> examples = generate(contract(examples(), null)).seen.get(CHECK + " 422");

        assertThat(examples).singleElement().satisfies(e -> {
            assertThat(e.name()).isEqualTo("Closed");
            assertThat(e.contentType()).isEqualTo("application/problem+json");
            assertThat(json(e.text())).isEqualTo(json("{title: The shop is closed.}"));
            assertThat(e.location()).isEqualTo("/components/responses/Unprocessable/content/application~1problem+json/examples/Closed");
            assertThat(e.isDefault()).isTrue();
        });
    }

    @Test
    void aResponseWithoutExamplesAnUnknownResponseAndAnUnknownOperationGiveNone() {
        Capturing capturing = generate(contract(null, null));

        assertThat(capturing.seen.get(CHECK + " 200")).isEmpty();
        assertThat(capturing.seen.get(CHECK + " 400")).isEmpty();
        assertThat(capturing.seen.get("/paths/~1orders/get 200")).isEmpty();
        assertThat(capturing.unknown).allSatisfy(u -> assertThat(u).isEmpty());
    }

    @Test
    void theTextIsJsonWithAFinalNewline() {
        List<ResponseExample> examples = generate(contract(examples(), null)).seen.get(CHECK + " 200");

        assertThat(examples.get(0).text()).startsWith("{").endsWith("}\n");
    }

    // ------------------------------------------------------------ what fails the generation

    @Test
    void anExampleThatDoesNotSatisfyItsSchemaFailsTheGeneration() {
        String invalid = "Accepted: {value: {check: order-check, outcome: maybe}}\n";

        assertThatThrownBy(() -> generate(contract(invalid, null)))
                .isInstanceOf(ValidValues.InvalidExample.class)
                .hasMessageContaining("The example Accepted at " + CHECK + "/responses/200/content/application~1json/examples/Accepted")
                .hasMessageContaining("does not satisfy the schema at " + CHECK + "/responses/200/content/application~1json/schema");
    }

    @Test
    void theExtensionNamingAnExampleTheMediaTypeDoesNotDeclareFailsTheGeneration() {
        assertThatThrownBy(() -> generate(contract(examples(), "{default: Missing}")))
                .isInstanceOf(ValidValues.InvalidExample.class)
                .hasMessageContaining(CHECK + "/responses/200/content/application~1json/x-transcriberj-examples/default names the "
                        + "example Missing, but the media type declares no example of that name.");
    }

    @Test
    void anotherKeyThanDefaultFailsTheGeneration() {
        assertThatThrownBy(() -> generate(contract(examples(), "{full: Accepted}")))
                .isInstanceOf(ValidValues.InvalidExample.class)
                .hasMessageContaining("x-transcriberj-examples/full names a key this generator does not know for a "
                        + "response; the only one is default.");
    }

    @Test
    void anExtensionThatIsNoMapOrNamesNoStringFailsTheGeneration() {
        assertThatThrownBy(() -> generate(contract(examples(), "Accepted")))
                .isInstanceOf(ValidValues.InvalidExample.class)
                .hasMessageContaining("x-transcriberj-examples must map default to the name of one of the media type's examples.");
        assertThatThrownBy(() -> generate(contract(examples(), "{default: [Accepted]}")))
                .isInstanceOf(ValidValues.InvalidExample.class)
                .hasMessageContaining("x-transcriberj-examples/default must be the name of one of the media type's examples.");
    }

    @Test
    void anExternalValueOrAReferenceThatNamesNoExampleFailsTheGeneration() {
        assertThatThrownBy(() -> generate(contract("Remote: {externalValue: 'https://example.com/a.json'}\n", null)))
                .isInstanceOf(ValidValues.InvalidExample.class)
                .hasMessageContaining("gives an externalValue, which this generator does not fetch");
        assertThatThrownBy(() -> generate(contract("Lost: {$ref: '#/components/examples/Nowhere'}\n", null)))
                .isInstanceOf(ValidValues.InvalidExample.class)
                .hasMessageContaining("refers to #/components/examples/Nowhere, which names no example under #/components/examples.");
    }

    // ------------------------------------------------------------ the report

    @Test
    void theReportNamesTheExampleADoubleAnswersWithPerResponse() {
        GeneratedSources g = GeneratedSources.generate(contract(examples(), "{default: OutOfStock}"), directory, List.of());

        JsonNode report = Oracle.JSON.readTree(g.report.renderValidValues("test-contract", "1.0.0"));
        assertThat(report.get("schemaVersion").intValue()).isEqualTo(4);
        List<JsonNode> entries = ValidValueFixtures.list(report.get("responseExamples"));
        assertThat(entries).anySatisfy(e -> {
            assertThat(e.get("class").stringValue()).isEqualTo("CheckOrderOperation");
            assertThat(e.get("status").stringValue()).isEqualTo("200");
            assertThat(e.get("default").stringValue()).isEqualTo("OutOfStock");
            assertThat(ValidValueFixtures.list(e.get("examples"))).extracting(JsonNode::stringValue)
                    .containsExactly("Accepted", "OutOfStock");
        });
        assertThat(entries).anySatisfy(e -> {
            assertThat(e.get("class").stringValue()).isEqualTo("ListOrdersOperation");
            assertThat(e.get("default").isNull()).isTrue();
            assertThat(e.get("examples")).isEmpty();
        });

        assertThat(g.report.render("test-contract", "1.0.0"))
                .contains("Response examples -- the body a double answers with:")
                .contains("  CheckOrderOperation 200: example OutOfStock, of Accepted, OutOfStock")
                .contains("  CheckOrderOperation 422: example Closed, of Closed")
                .contains("  ListOrdersOperation 200: fullBody(), no named example");
    }
}
