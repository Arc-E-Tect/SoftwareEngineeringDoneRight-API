package com.arc_e_tect.gradle.apionly.transcriberj.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A request body's media type names, in {@code x-transcriberj-examples}, the example each
 * variant's valid request sends. The named example replaces the generated body of that
 * operation's request only; the body class keeps its generated values. A named example must be
 * valid and hold exactly the members its variant stands for: only the required ones for
 * {@code required}, every declared one for {@code full}, at every level, a choice by the branch
 * the example matches.
 */
class NamedRequestExamplesTest {

    private static final String MINIMAL = "{name: alice, email: alice@example.com}";
    private static final String EVERYTHING = "{name: bob, email: bob@example.com, referrer: alice, "
            + "address: {city: Paris, zip: '75001'}, contact: {phone: '+33 1 23 45 67 89', hours: daytime}}";
    private static final String POINTER = "{required: Minimal, full: Everything}";

    @TempDir
    Path directory;

    /** The contract, with the media type's {@code examples} and its {@code x-transcriberj-examples}. */
    private static String contract(String examples, String pointer) {
        return """
                openapi: 3.1.0
                info: {title: People, version: 1.0.0}
                paths:
                  /people:
                    post:
                      operationId: CreatePerson
                      requestBody:
                        required: true
                        content:
                          application/json:
                            schema: {$ref: '#/components/schemas/Person'}
                %s%s
                      responses:
                        '201': {description: Created.}
                        '400': {description: Invalid.}
                components:
                  schemas:
                    Person:
                      x-fragment-path: openapi/components/Person.yaml
                      type: object
                      required: [name, email]
                      properties:
                        name: {type: string, minLength: 1}
                        email: {type: string, minLength: 3}
                        referrer: {type: string}
                        address: {$ref: '#/components/schemas/Address'}
                        contact: {$ref: '#/components/schemas/Contact'}
                    Address:
                      x-fragment-path: openapi/components/Address.yaml
                      type: object
                      required: [city]
                      properties:
                        city: {type: string}
                        zip: {type: string}
                    Contact:
                      x-fragment-path: openapi/components/Contact.yaml
                      oneOf:
                        - $ref: '#/components/schemas/Phone'
                        - $ref: '#/components/schemas/Mail'
                    Phone:
                      x-fragment-path: openapi/components/Phone.yaml
                      type: object
                      required: [phone]
                      properties: {phone: {type: string, minLength: 5}, hours: {type: string}}
                      additionalProperties: false
                    Mail:
                      x-fragment-path: openapi/components/Mail.yaml
                      type: object
                      required: [email]
                      properties: {email: {type: string, minLength: 3}, language: {type: string}}
                      additionalProperties: false
                """.formatted(examples == null ? "" : "            examples:\n" + examples.indent(14),
                pointer == null ? "" : "            x-transcriberj-examples: " + pointer + "\n");
    }

    private static String examples(String minimal, String everything) {
        return "Minimal: {value: " + minimal + "}\nEverything: {value: " + everything + "}\n";
    }

    private GeneratedSources generate(String yaml) {
        return GeneratedSources.generate(yaml, directory, List.of());
    }

    private static JsonNode report(GeneratedSources g) {
        return Oracle.JSON.readTree(g.report.renderValidValues("test-contract", "1.0.0"));
    }

    private static JsonNode successCase(JsonNode report, String variant) {
        return ContractCaseFixtures.cases(report, "SUCCESS").stream()
                .filter(c -> c.get("variant").stringValue().equals(variant)).findFirst().orElseThrow();
    }

    private static JsonNode json(String yaml) {
        return Oracle.JSON.readTree(Oracle.JSON.writeValueAsString(
                new org.snakeyaml.engine.v2.api.Load(org.snakeyaml.engine.v2.api.LoadSettings.builder().build())
                        .loadFromString(yaml)));
    }

    // ------------------------------------------------------------ the bodies

    @Test
    void eachVariantsSuccessCaseSendsTheExampleItNames() {
        JsonNode report = report(generate(contract(examples(MINIMAL, EVERYTHING), POINTER)));

        assertThat(successCase(report, "required").get("request").get("body")).isEqualTo(json(MINIMAL));
        assertThat(successCase(report, "full").get("request").get("body")).isEqualTo(json(EVERYTHING));
    }

    @Test
    void theOperationsValidRequestsSendTheNamedExamplesButTheBodyClassKeepsItsGeneratedBodies() {
        GeneratedSources g = generate(contract(examples(MINIMAL, EVERYTHING), POINTER));
        JsonNode report = report(g);

        JsonNode request = ValidValueFixtures.list(report.get("requests")).stream()
                .filter(r -> r.get("class").stringValue().equals("CreatePersonOperation")).findFirst().orElseThrow();
        assertThat(request.get("requiredRequest").get("value").get("body")).isEqualTo(json(MINIMAL));
        assertThat(request.get("fullRequest").get("value").get("body")).isEqualTo(json(EVERYTHING));

        JsonNode body = ValidValueFixtures.list(report.get("bodies")).stream()
                .filter(b -> b.get("class").stringValue().equals("Person")).findFirst().orElseThrow();
        assertThat(body.get("requiredBody").get("value").get("name").stringValue()).isEqualTo("a");
    }

    @Test
    void aVariantTheExtensionDoesNotNameIsGeneratedAsBefore() {
        JsonNode report = report(generate(contract(examples(MINIMAL, EVERYTHING), "{full: Everything}")));

        assertThat(successCase(report, "required").get("request").get("body").get("name").stringValue())
                .isEqualTo("a");
        assertThat(successCase(report, "full").get("request").get("body")).isEqualTo(json(EVERYTHING));
    }

    @Test
    void theInvalidRequestCasesChangeOneThingAboutTheRequiredExample() {
        JsonNode report = report(generate(contract(examples(MINIMAL, EVERYTHING), POINTER)));

        JsonNode missingName = ContractCaseFixtures.cases(report, "INVALID_REQUEST").stream()
                .filter(c -> c.get("id").stringValue().equals("body-name-required")).findFirst().orElseThrow();
        assertThat(missingName.get("request").get("body")).isEqualTo(json("{email: alice@example.com}"));
    }

    @Test
    void aNamedExampleMayReferToAnExampleComponent() {
        String yaml = contract("Minimal: {value: " + MINIMAL + "}\n"
                + "Everything: {$ref: '#/components/examples/Everything'}\n", POINTER)
                + "  examples:\n    Everything: {value: " + EVERYTHING + "}\n";
        JsonNode report = report(generate(yaml));

        assertThat(successCase(report, "full").get("request").get("body")).isEqualTo(json(EVERYTHING));
    }

    /** A contract whose one operation declares nothing optional, with two examples and a pointer to both. */
    private static final String NOTHING_OPTIONAL = """
            openapi: 3.1.0
            info: {title: Tokens, version: 1.0.0}
            paths:
              /tokens:
                post:
                  operationId: CreateToken
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema: {$ref: '#/components/schemas/Token'}
                        examples:
                          One: {value: {value: abc}}
                          Two: {value: {value: xyz}}
                        x-transcriberj-examples: {required: One, full: Two}
                  responses:
                    '201': {description: Created.}
            components:
              schemas:
                Token:
                  x-fragment-path: openapi/components/Token.yaml
                  type: object
                  required: [value]
                  properties: {value: {type: string}}
            """;

    @Test
    void anOperationThatDeclaresNothingOptionalGetsNoFullCaseWhateverItsExamples() {
        GeneratedSources g = generate(NOTHING_OPTIONAL);
        JsonNode report = report(g);

        assertThat(ContractCaseFixtures.cases(report, "SUCCESS")).extracting(c -> c.get("id").stringValue())
                .containsExactly("success-201-required");
        assertThat(successCase(report, "required").get("request").get("body")).isEqualTo(json("{value: abc}"));
        assertThat(g.report.notes()).contains("CreateTokenContractCases: success-201-full is not derived: the full "
                + "request is the required one, as the operation declares no optional parameter or member; "
                + "the example Two that x-transcriberj-examples names for it is not sent");
    }

    // --------------------------------------------------------- refusals

    @Test
    void aReferenceToAnExampleComponentThatDoesNotExistFailsTheGeneration() {
        String yaml = contract("Minimal: {value: " + MINIMAL + "}\n"
                + "Everything: {$ref: '#/components/examples/Gone'}\n", POINTER);

        assertThatThrownBy(() -> generate(yaml)).isInstanceOf(GenerationException.class)
                .hasMessageContaining("examples/Everything").hasMessageContaining("#/components/examples/Gone");
    }

    @Test
    void aPointerToAnExampleTheMediaTypeDoesNotDeclareFailsTheGeneration() {
        String yaml = contract(examples(MINIMAL, EVERYTHING), "{required: Minimal, full: Missing}");

        assertThatThrownBy(() -> generate(yaml)).isInstanceOf(GenerationException.class)
                .hasMessageContaining("/paths/~1people/post/requestBody/content/application~1json/x-transcriberj-examples/full")
                .hasMessageContaining("Missing");
    }

    @Test
    void aVariantTheGeneratorDoesNotKnowFailsTheGeneration() {
        String yaml = contract(examples(MINIMAL, EVERYTHING), "{required: Minimal, everything: Everything}");

        assertThatThrownBy(() -> generate(yaml)).isInstanceOf(GenerationException.class)
                .hasMessageContaining("x-transcriberj-examples/everything")
                .hasMessageContaining("required, full");
    }

    @Test
    void anExtensionThatIsNotAMappingOfNamesFailsTheGeneration() {
        assertThatThrownBy(() -> generate(contract(examples(MINIMAL, EVERYTHING), "Everything")))
                .isInstanceOf(GenerationException.class).hasMessageContaining("x-transcriberj-examples");
        assertThatThrownBy(() -> generate(contract(examples(MINIMAL, EVERYTHING), "{full: [Everything]}")))
                .isInstanceOf(GenerationException.class).hasMessageContaining("x-transcriberj-examples/full");
    }

    @Test
    void aPointerWithoutAnyExamplesFailsTheGeneration() {
        assertThatThrownBy(() -> generate(contract(null, POINTER))).isInstanceOf(GenerationException.class)
                .hasMessageContaining("x-transcriberj-examples/required").hasMessageContaining("Minimal");
    }

    @Test
    void aMediaTypeWithoutASchemaSendsItsNamedExampleAsItIs() {
        String yaml = contract(examples(MINIMAL, EVERYTHING), POINTER)
                .replace("            schema: {$ref: '#/components/schemas/Person'}\n", "");
        JsonNode report = report(generate(yaml));

        assertThat(successCase(report, "required").get("request").get("body")).isEqualTo(json(MINIMAL));
    }

    @Test
    void aNamedExampleGivenAsAnExternalValueFailsTheGeneration() {
        String yaml = contract("Minimal: {value: " + MINIMAL + "}\n"
                + "Everything: {externalValue: 'https://example.com/everything.json'}\n", POINTER);

        assertThatThrownBy(() -> generate(yaml)).isInstanceOf(GenerationException.class)
                .hasMessageContaining("examples/Everything").hasMessageContaining("externalValue");
    }

    @Test
    void aNamedExampleThatBreaksTheSchemaFailsTheGeneration() {
        String yaml = contract(examples("{name: '', email: alice@example.com}", EVERYTHING), POINTER);

        assertThatThrownBy(() -> generate(yaml)).isInstanceOf(GenerationException.class)
                .hasMessageContaining("examples/Minimal").hasMessageContaining("does not satisfy");
    }

    @Test
    void aRequiredExampleWithAnOptionalMemberFailsTheGeneration() {
        String yaml = contract(examples("{name: alice, email: alice@example.com, referrer: carol}", EVERYTHING),
                POINTER);

        assertThatThrownBy(() -> generate(yaml)).isInstanceOf(GenerationException.class)
                .hasMessageContaining("examples/Minimal").hasMessageContaining("referrer")
                .hasMessageContaining("required");
    }

    @Test
    void aFullExampleWithoutAnOptionalMemberFailsTheGeneration() {
        String yaml = contract(examples(MINIMAL, EVERYTHING.replace("referrer: alice, ", "")), POINTER);

        assertThatThrownBy(() -> generate(yaml)).isInstanceOf(GenerationException.class)
                .hasMessageContaining("examples/Everything").hasMessageContaining("referrer");
    }

    @Test
    void theMemberRulesApplyInsideNestedObjects() {
        String fullWithoutZip = EVERYTHING.replace(", zip: '75001'", "");

        assertThatThrownBy(() -> generate(contract(examples(MINIMAL, fullWithoutZip), POINTER)))
                .isInstanceOf(GenerationException.class)
                .hasMessageContaining("examples/Everything").hasMessageContaining("/address/zip");
    }

    @Test
    void aRequiredExampleWithAnOptionalMemberInANestedObjectFailsTheGeneration() {
        String yaml = contract(examples("{name: alice, email: alice@example.com, address: {city: Paris, zip: '75001'}}",
                EVERYTHING), POINTER).replace("required: [name, email]", "required: [name, email, address]");

        assertThatThrownBy(() -> generate(yaml)).isInstanceOf(GenerationException.class)
                .hasMessageContaining("examples/Minimal").hasMessageContaining("/address/zip");
    }

    @Test
    void aChoiceIsCheckedAgainstTheBranchTheExampleMatches() {
        String otherBranchWithoutLanguage = EVERYTHING.replace(
                "contact: {phone: '+33 1 23 45 67 89', hours: daytime}", "contact: {email: bob@example.com}");

        assertThatThrownBy(() -> generate(contract(examples(MINIMAL, otherBranchWithoutLanguage), POINTER)))
                .isInstanceOf(GenerationException.class)
                .hasMessageContaining("examples/Everything").hasMessageContaining("/contact/language");
    }

    // ---------------------------------------------------------- the report

    @Test
    void theReportIsAtSchemaVersion3() {
        JsonNode report = report(generate(contract(examples(MINIMAL, EVERYTHING), POINTER)));

        assertThat(report.get("schemaVersion").intValue()).isEqualTo(3);
    }

    @Test
    void aCaseBuiltOnANamedExampleSaysWhichOne() {
        JsonNode report = report(generate(contract(examples(MINIMAL, EVERYTHING), POINTER)));

        assertThat(successCase(report, "full").get("source").get("namedExample").stringValue())
                .isEqualTo("Everything");
        assertThat(successCase(report, "required").get("source").get("namedExample").stringValue())
                .isEqualTo("Minimal");
    }

    @Test
    void aGeneratedCaseHasNoNamedExampleAndListsWhereItsExamplesAreWritten() {
        String yaml = contract(null, null).replace("referrer: {type: string}",
                "referrer: {type: string, examples: [alice]}");
        JsonNode report = report(generate(yaml));

        JsonNode source = successCase(report, "full").get("source");
        assertThat(source.get("namedExample").isNull()).isTrue();
        assertThat(ValidValueFixtures.list(source.get("examples"))).extracting(JsonNode::stringValue)
                .containsExactly("/components/schemas/Person/properties/referrer");
    }

    @Test
    void theTextReportSaysWhereEachCasesBodyCameFrom() {
        GeneratedSources g = generate(contract(examples(MINIMAL, EVERYTHING), "{full: Everything}"));

        assertThat(g.report.render("test-contract", "1.0.0"))
                .contains("CreatePersonContractCases success-201-full: named example Everything")
                .contains("CreatePersonContractCases success-201-required: generated");
    }
}
