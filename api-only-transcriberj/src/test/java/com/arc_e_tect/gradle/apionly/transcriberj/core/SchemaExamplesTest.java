package com.arc_e_tect.gradle.apionly.transcriberj.core;

import com.arc_e_tect.gradle.apionly.transcriberj.model.ContractModel;
import com.arc_e_tect.gradle.apionly.transcriberj.model.ContractParser;
import com.arc_e_tect.gradle.apionly.transcriberj.model.Operation;
import com.arc_e_tect.gradle.apionly.transcriberj.model.Schema;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A leaf value takes the first example the contract gives for it -- a parameter's own, then a
 * property's beside its {@code $ref}, then the schema's -- and is generated from the constraints
 * only where there is none. An example that breaks the schema it is written on fails the
 * generation rather than being replaced; one that is valid there but does not fit a narrower
 * place, where an {@code allOf} adds to it, does not apply there.
 */
class SchemaExamplesTest {

    private static final String CONTRACT = """
            openapi: 3.1.0
            info: {title: Examples, version: 1.0.0}
            paths:
              /things/{id}:
                get:
                  parameters:
                    - name: id
                      in: path
                      required: true
                      schema: {type: string, examples: [s1]}
                      examples:
                        First: {value: p1}
                        Second: {value: p2}
                    - {name: legacy, in: query, required: true, schema: {type: string, examples: [s2]}, example: q1}
                    - {name: plain, in: query, required: true, schema: {type: string, examples: [s3]}}
                    - {name: none, in: query, required: true, schema: {type: string, minLength: 2}}
                  responses:
                    '204': {description: Found.}
              /external/{id}:
                get:
                  parameters:
                    - name: id
                      in: path
                      required: true
                      schema: {type: string}
                      examples:
                        Elsewhere: {externalValue: 'https://example.com/id.txt'}
                  responses:
                    '204': {description: Found.}
              /referred/{id}:
                get:
                  parameters:
                    - name: id
                      in: path
                      required: true
                      schema: {type: string}
                      examples:
                        Shared: {$ref: '#/components/examples/SharedId'}
                  responses:
                    '204': {description: Found.}
              /fallback/{id}:
                get:
                  parameters:
                    - {name: id, in: path, required: true, schema: {type: string}, examples: {}, example: f1}
                  responses:
                    '204': {description: Found.}
              /bare/{id}:
                get:
                  parameters:
                    - {name: id, in: path, required: true, schema: {type: string}, examples: {Bare: plain}}
                  responses:
                    '204': {description: Found.}
              /valueless/{id}:
                get:
                  parameters:
                    - {name: id, in: path, required: true, schema: {type: string}, examples: {Empty: {summary: none}}}
                  responses:
                    '204': {description: Found.}
              /elsewhere/{id}:
                get:
                  parameters:
                    - name: id
                      in: path
                      required: true
                      schema: {type: string}
                      examples: {Remote: {$ref: 'other.yaml#/components/examples/SharedId'}}
                  responses:
                    '204': {description: Found.}
              /missing/{id}:
                get:
                  parameters:
                    - name: id
                      in: path
                      required: true
                      schema: {type: string}
                      examples: {Gone: {$ref: '#/components/examples/Gone'}}
                  responses:
                    '204': {description: Found.}
              /mistyped/{id}:
                get:
                  parameters:
                    - {name: id, in: path, required: true, schema: {type: integer}, example: abc}
                  responses:
                    '204': {description: Found.}
            components:
              schemas:
                Name: {type: string, minLength: 3, maxLength: 12, examples: [alice_1, bob]}
                Pair:
                  type: object
                  required: [a, b]
                  properties:
                    a: {$ref: '#/components/schemas/Name'}
                    b: {$ref: '#/components/schemas/Name', examples: [carol]}
                Legacy: {type: integer, minimum: 1, example: 42}
                Choice: {type: string, enum: [x, y, z], examples: [y]}
                Flag: {type: boolean, examples: [true]}
                Price: {type: number, examples: [9.5]}
                Plain: {type: string, minLength: 5}
                Account:
                  type: object
                  required: [k]
                  properties: {k: {type: string}}
                  examples: [{k: z}]
                Tags: {type: array, minItems: 1, items: {type: string}, examples: [[q]]}
                UniqueTags: {type: array, minItems: 3, uniqueItems: true, items: {type: string, examples: [x, y]}}
                RepeatedTags: {type: array, minItems: 3, items: {type: string, examples: [x, y]}}
                Optional:
                  type: object
                  properties: {o: {type: string, examples: [opt]}}
                TooLong: {type: string, maxLength: 3, examples: [toolong]}
                SecondTooLong: {type: string, maxLength: 3, examples: [abc, toolong]}
                Narrowed: {allOf: [{type: string}, {maxLength: 2}], examples: [abc]}
                BrokenBesideReference:
                  type: object
                  required: [n]
                  properties:
                    n: {$ref: '#/components/schemas/Name', examples: [x]}
                Base:
                  type: object
                  required: [kind]
                  properties:
                    kind: {type: string, examples: [general]}
                Special:
                  allOf:
                    - $ref: '#/components/schemas/Base'
                    - type: object
                      properties:
                        kind: {const: special, examples: [special]}
                Unexampled:
                  allOf:
                    - $ref: '#/components/schemas/Base'
                    - type: object
                      properties:
                        kind: {const: other}
                UntypedLeaf: {examples: [5]}
                UntypedObject: {examples: [{k: z}]}
                UntypedArray: {examples: [[1]]}
                EmptyExamples: {type: integer, examples: [], example: 7}
                BrokenLegacy: {type: integer, example: seven}
                EmptyThenBroken: {type: integer, examples: [], example: seven}
                Twice: {allOf: [{$ref: '#/components/schemas/Name'}, {$ref: '#/components/schemas/Name'}]}
                PlentyTags: {type: array, minItems: 2, items: {type: string, examples: [x, y, z]}}
                ChoiceTags:
                  type: array
                  minItems: 2
                  uniqueItems: true
                  items:
                    oneOf:
                      - $ref: '#/components/schemas/Short'
                      - $ref: '#/components/schemas/Long'
                    examples: [xyz]
                Short: {type: string, maxLength: 1}
                Long: {type: string, minLength: 3}
              examples:
                SharedId: {value: r1}
            """;

    @TempDir
    static Path directory;

    static ContractModel model;
    static ValidValues values;
    static ValidRequests requests;

    @BeforeAll
    static void parse() throws IOException {
        Path contract = directory.resolve("openapi.yaml");
        Files.writeString(contract, CONTRACT);
        model = ContractParser.parse(contract, null);
        Shapes shapes = new Shapes(model);
        values = new ValidValues(shapes, 2);
        requests = new ValidRequests(model, shapes, values);
    }

    private static Schema schema(String component) {
        return model.component(component).orElseThrow().schema();
    }

    private static String location(String component) {
        return "/components/schemas/" + component;
    }

    private static String json(String component, ValidValues.Variant variant) {
        return ValueJson.write(values.value(schema(component), location(component), variant));
    }

    private static String json(String component) {
        return json(component, ValidValues.Variant.REQUIRED);
    }

    private static Operation operation(String path) {
        return model.operations().stream().filter(o -> o.path().equals(path)).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------- leaves

    @Test
    void aStringTakesItsSchemasFirstExample() {
        assertThat(json("Name")).isEqualTo("\"alice_1\"");
    }

    @Test
    void anExampleBesideAReferenceComesBeforeTheReferencedSchemasOwn() {
        assertThat(json("Pair")).isEqualTo("{\"a\":\"alice_1\",\"b\":\"carol\"}");
    }

    @Test
    void theOpenApi30ExampleKeywordCountsToo() {
        assertThat(json("Legacy")).isEqualTo("42");
    }

    @Test
    void anExampleComesBeforeTheFirstEnumValue() {
        assertThat(json("Choice")).isEqualTo("\"y\"");
    }

    @Test
    void booleansAndNumbersTakeTheirExamples() {
        assertThat(json("Flag")).isEqualTo("true");
        assertThat(json("Price")).isEqualTo("9.5");
    }

    @Test
    void withoutAnExampleTheValueIsGeneratedAsBefore() {
        assertThat(json("Plain")).isEqualTo("\"aaaaa\"");
    }

    @Test
    void anOptionalMemberTakesItsExampleInTheFullValueOnly() {
        assertThat(json("Optional", ValidValues.Variant.REQUIRED)).isEqualTo("{}");
        assertThat(json("Optional", ValidValues.Variant.FULL)).isEqualTo("{\"o\":\"opt\"}");
    }

    // --------------------------------------------------------- composites

    @Test
    void anObjectsOwnExampleIsNotUsedItsMembersAreBuiltOneByOne() {
        assertThat(json("Account")).isEqualTo("{\"k\":\"a\"}");
    }

    @Test
    void anArraysOwnExampleIsNotUsedItsItemsAreBuiltOneByOne() {
        assertThat(json("Tags")).isEqualTo("[\"a\"]");
    }

    @Test
    void uniqueItemsTakeTheExamplesInOrderThenDistinctGeneratedValues() {
        assertThat(json("UniqueTags")).isEqualTo("[\"x\",\"y\",\"a\"]");
    }

    @Test
    void repeatableItemsTakeTheExamplesInOrderThenTheFirstGeneratedValueRepeated() {
        assertThat(json("RepeatedTags")).isEqualTo("[\"x\",\"y\",\"a\"]");
    }

    @Test
    void anUntypedSchemaTakesALeafExampleButBuildsAnythingElse() {
        assertThat(json("UntypedLeaf")).isEqualTo("5");
        assertThat(json("UntypedObject")).isEqualTo("null");
        assertThat(json("UntypedArray")).isEqualTo("null");
    }

    @Test
    void anEmptyExamplesListGivesWayToTheExampleKeyword() {
        assertThat(json("EmptyExamples")).isEqualTo("7");
    }

    @Test
    void aComponentReferredToTwiceOffersItsExamplesOnce() {
        assertThat(json("Twice")).isEqualTo("\"alice_1\"");
    }

    @Test
    void itemsTakeNoMoreExamplesThanTheArrayNeeds() {
        assertThat(json("PlentyTags")).isEqualTo("[\"x\",\"y\"]");
    }

    @Test
    void aChoicesOwnExamplesAreNotOfferedAgainByItsBranches() {
        assertThat(json("ChoiceTags")).isEqualTo("[\"xyz\",\"a\"]");
    }

    // ----------------------------------------------------- invalid examples

    @Test
    void anOpenApi30ExampleThatBreaksTheSchemaFailsTheGeneration() {
        assertThatThrownBy(() -> json("BrokenLegacy"))
                .isInstanceOf(ValidValues.InvalidExample.class)
                .hasMessageContaining("/components/schemas/BrokenLegacy/example");
        assertThatThrownBy(() -> json("EmptyThenBroken"))
                .isInstanceOf(ValidValues.InvalidExample.class)
                .hasMessageContaining("/components/schemas/EmptyThenBroken/example");
    }

    @Test
    void aUsedExampleThatBreaksTheSchemaFailsTheGeneration() {
        assertThatThrownBy(() -> json("TooLong"))
                .isInstanceOf(ValidValues.InvalidExample.class)
                .isInstanceOf(GenerationException.class)
                .hasMessageContaining("/components/schemas/TooLong")
                .hasMessageContaining("\"toolong\"");
    }

    @Test
    void anExampleThatIsNotUsedIsNotChecked() {
        assertThat(json("SecondTooLong")).isEqualTo("\"abc\"");
    }

    @Test
    void anExampleMustSatisfyEveryPartOfAnAllOf() {
        assertThatThrownBy(() -> json("Narrowed"))
                .isInstanceOf(ValidValues.InvalidExample.class)
                .hasMessageContaining("/components/schemas/Narrowed")
                .hasMessageContaining("\"abc\"");
    }

    @Test
    void anExampleBesideAReferenceMustSatisfyTheReferencedSchema() {
        assertThatThrownBy(() -> json("BrokenBesideReference"))
                .isInstanceOf(ValidValues.InvalidExample.class)
                .hasMessageContaining("/components/schemas/BrokenBesideReference/properties/n")
                .hasMessageContaining("\"x\"");
    }

    @Test
    void anExampleThatDoesNotFitANarrowerPlaceGivesWayToThatPlacesOwn() {
        assertThat(json("Special")).isEqualTo("{\"kind\":\"special\"}");
    }

    @Test
    void anExampleThatDoesNotFitANarrowerPlaceWithoutOneOfItsOwnGivesWayToAGeneratedValue() {
        assertThat(json("Unexampled")).isEqualTo("{\"kind\":\"other\"}");
    }

    // ---------------------------------------------------------- provenance

    @Test
    void theLocationsOfTheExamplesAValueTookAreWhereTheExamplesAreWritten() {
        Object pair = values.value(schema("Pair"), location("Pair"), ValidValues.Variant.REQUIRED);
        assertThat(values.exampleLocations(pair, schema("Pair"), location("Pair")))
                .containsExactly("/components/schemas/Name", "/components/schemas/Pair/properties/b");
    }

    @Test
    void theLocationOfAnExampleIsThatOfTheSchemaWhoseExampleTheValueIs() {
        Object special = values.value(schema("Special"), location("Special"), ValidValues.Variant.REQUIRED);
        assertThat(values.exampleLocations(special, schema("Special"), location("Special")))
                .containsExactly("/components/schemas/Special/allOf/1/properties/kind");
    }

    @Test
    void aGeneratedValueTookNoExample() {
        Object plain = values.value(schema("Plain"), location("Plain"), ValidValues.Variant.REQUIRED);
        assertThat(values.exampleLocations(plain, schema("Plain"), location("Plain"))).isEmpty();
    }

    @Test
    void anArrayItemThatTookAnExampleIsReportedOnceForItsSchema() {
        Object tags = values.value(schema("UniqueTags"), location("UniqueTags"), ValidValues.Variant.REQUIRED);
        assertThat(values.exampleLocations(tags, schema("UniqueTags"), location("UniqueTags")))
                .containsExactly("/components/schemas/UniqueTags/items");
    }

    // ---------------------------------------------------------- parameters

    @Test
    void aParameterTakesItsOwnFirstNamedExampleThenItsExampleThenItsSchemas() {
        ValidRequests.Request request = requests.request(operation("/things/{id}"), ValidRequests.Kind.REQUIRED);
        assertThat(request.pathValues()).containsExactly("p1");
        assertThat(request.query()).containsExactly(new ValidRequests.Pair("legacy", "q1"),
                new ValidRequests.Pair("plain", "s3"), new ValidRequests.Pair("none", "aa"));
    }

    @Test
    void aRequestNamesWhereEveryExampleItTookIsWritten() {
        ValidRequests.Request request = requests.request(operation("/things/{id}"), ValidRequests.Kind.REQUIRED);
        assertThat(request.namedExample()).isNull();
        assertThat(request.examples()).containsExactly(
                "/paths/~1things~1{id}/get/parameters/0",
                "/paths/~1things~1{id}/get/parameters/1",
                "/paths/~1things~1{id}/get/parameters/2/schema");
    }

    @Test
    void aParametersNamedExampleMayReferToAnExampleComponent() {
        ValidRequests.Request request = requests.request(operation("/referred/{id}"), ValidRequests.Kind.REQUIRED);
        assertThat(request.pathValues()).containsExactly("r1");
    }

    @Test
    void aParameterWithAnEmptyExamplesMapTakesItsExample() {
        ValidRequests.Request request = requests.request(operation("/fallback/{id}"), ValidRequests.Kind.REQUIRED);
        assertThat(request.pathValues()).containsExactly("f1");
    }

    @Test
    void aParameterExampleThatIsNotAnExampleObjectFailsTheGeneration() {
        assertThatThrownBy(() -> requests.request(operation("/bare/{id}"), ValidRequests.Kind.REQUIRED))
                .isInstanceOf(ValidValues.InvalidExample.class)
                .hasMessageContaining("/paths/~1bare~1{id}/get/parameters/0/examples/Bare")
                .hasMessageContaining("gives no value");
    }

    @Test
    void aParameterExampleWithoutAValueFailsTheGeneration() {
        assertThatThrownBy(() -> requests.request(operation("/valueless/{id}"), ValidRequests.Kind.REQUIRED))
                .isInstanceOf(ValidValues.InvalidExample.class)
                .hasMessageContaining("/paths/~1valueless~1{id}/get/parameters/0/examples/Empty")
                .hasMessageContaining("gives no value");
    }

    @Test
    void aReferenceToAnExampleOutsideTheDocumentFailsTheGeneration() {
        assertThatThrownBy(() -> requests.request(operation("/elsewhere/{id}"), ValidRequests.Kind.REQUIRED))
                .isInstanceOf(ValidValues.InvalidExample.class)
                .hasMessageContaining("other.yaml#/components/examples/SharedId");
    }

    @Test
    void aReferenceToAnExampleComponentThatDoesNotExistFailsTheGeneration() {
        assertThatThrownBy(() -> requests.request(operation("/missing/{id}"), ValidRequests.Kind.REQUIRED))
                .isInstanceOf(ValidValues.InvalidExample.class)
                .hasMessageContaining("#/components/examples/Gone");
    }

    @Test
    void aParameterExampleGivenAsAnExternalValueFailsTheGeneration() {
        assertThatThrownBy(() -> requests.request(operation("/external/{id}"), ValidRequests.Kind.REQUIRED))
                .isInstanceOf(ValidValues.InvalidExample.class)
                .hasMessageContaining("/paths/~1external~1{id}/get/parameters/0/examples/Elsewhere")
                .hasMessageContaining("externalValue");
    }

    @Test
    void aParameterExampleThatBreaksItsSchemaFailsTheGeneration() {
        assertThatThrownBy(() -> requests.request(operation("/mistyped/{id}"), ValidRequests.Kind.REQUIRED))
                .isInstanceOf(ValidValues.InvalidExample.class)
                .hasMessageContaining("/paths/~1mistyped~1{id}/get/parameters/0")
                .hasMessageContaining("\"abc\"");
    }
}
