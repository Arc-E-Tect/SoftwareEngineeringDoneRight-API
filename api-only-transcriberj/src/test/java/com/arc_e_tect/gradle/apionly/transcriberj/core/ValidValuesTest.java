package com.arc_e_tect.gradle.apionly.transcriberj.core;

import com.arc_e_tect.gradle.apionly.transcriberj.model.ContractModel;
import com.arc_e_tect.gradle.apionly.transcriberj.model.ContractParser;
import com.arc_e_tect.gradle.apionly.transcriberj.model.Operation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The generator's rules at their edges, and the validator it checks its own candidates
 * with -- which decides {@code oneOf} exclusivity -- held to the independent oracle.
 */
class ValidValuesTest {

    private static final String COMPONENTS = """
            openapi: 3.1.0
            info: {title: Edges, version: 1.0.0}
            paths:
              /things/{id}:
                post:
                  parameters:
                    - {name: id, in: path, required: true, schema: {minItems: 1}}
                    - {name: described, in: query, content: {application/json: {schema: {type: string}}}}
                  responses:
                    '204': {description: Accepted.}
              /free:
                post:
                  parameters:
                    - {name: free, in: query, required: true, schema: {description: anything}}
                  requestBody:
                    content:
                      application/json: {}
                  responses:
                    '204': {description: Accepted.}
            components:
              schemas:
                Inclusive: {type: number, minimum: 2.5}
                UpperInclusive: {type: number, maximum: -2.5}
                UpperExclusive: {type: number, exclusiveMaximum: -2.5}
                Equal: {type: integer, minimum: 3, exclusiveMaximum: 3}
                UniqueNegatives: {type: array, minItems: 3, uniqueItems: true, items: {type: integer, maximum: -3}}
                ManyStrings: {type: array, minItems: 27, uniqueItems: true, items: {type: string}}
                UniqueObjects:
                  type: array
                  minItems: 2
                  uniqueItems: true
                  items: {type: object, required: [k], properties: {k: {type: string, enum: [x, y]}}}
                UniqueBooleans: {type: array, minItems: 2, uniqueItems: true, items: {type: boolean}}
                Ghost: {type: object, required: [ghost]}
                Closed: {type: object, minProperties: 1, additionalProperties: false}
                Inferred:
                  type: object
                  required: [o, a, s, n]
                  properties:
                    o: {required: [x]}
                    a: {minItems: 1}
                    s: {minLength: 2}
                    n: {minimum: 4}
                Choice:
                  anyOf:
                    - $ref: '#/components/schemas/Inclusive'
                    - $ref: '#/components/schemas/Equal'
                NoBranch:
                  oneOf:
                    - $ref: '#/components/schemas/Equal'
                    - $ref: '#/components/schemas/Equal'
                Twins:
                  oneOf:
                    - $ref: '#/components/schemas/Inclusive'
                    - $ref: '#/components/schemas/Inclusive'
                Nested:
                  type: object
                  properties:
                    children: {type: array, items: {$ref: '#/components/schemas/Nested'}}
                Loop:
                  type: object
                  required: [next]
                  properties:
                    next:
                      oneOf:
                        - $ref: '#/components/schemas/Loop'
                        - $ref: '#/components/schemas/Loop'
                ApartPatterns: {type: string, allOf: [{pattern: '^a+$'}, {pattern: '^b+$'}]}
                Nothing: {type: string, pattern: '[]'}
                Huge: {type: string, minLength: 5000}
                ShortUuid: {type: string, format: uuid, maxLength: 10}
                Zero: {type: number, multipleOf: 0}
                Mixed:
                  type: object
                  properties:
                    s: {type: string, minLength: 2, maxLength: 3, pattern: '^[a-z]+$'}
                    f: {type: string, format: date}
                    i: {type: integer, minimum: 1, exclusiveMaximum: 10, multipleOf: 2}
                    b: {type: boolean}
                    c: {const: fixed}
                    e: {enum: [1, two, [3]]}
                    l: {type: array, minItems: 1, maxItems: 2, uniqueItems: true, items: {type: string}}
                    m:
                      type: object
                      minProperties: 1
                      maxProperties: 2
                      required: [r]
                      properties: {r: {type: string}}
                      patternProperties: {'^p': {type: integer}}
                      additionalProperties: false
                    one:
                      oneOf:
                        - {$ref: '#/components/schemas/Inclusive'}
                        - {$ref: '#/components/schemas/Equal'}
                    any:
                      anyOf:
                        - {$ref: '#/components/schemas/UpperInclusive'}
                        - {$ref: '#/components/schemas/Inclusive'}
                    all:
                      allOf:
                        - {type: string}
                        - {maxLength: 1}
            """;

    @TempDir
    static Path directory;

    static Path contract;
    static ContractModel model;
    static ValidValues values;
    static Oracle oracle;

    @BeforeAll
    static void parse() throws IOException {
        contract = directory.resolve("openapi.yaml");
        Files.writeString(contract, COMPONENTS);
        model = ContractParser.parse(contract, null);
        values = new ValidValues(new Shapes(model), 2);
        oracle = new Oracle(contract);
    }

    private static Object value(String component, ValidValues.Variant variant) {
        return values.value(model.component(component).orElseThrow().schema(), "/components/schemas/" + component,
                variant);
    }

    private static String json(String component) {
        return ValueJson.write(value(component, ValidValues.Variant.REQUIRED));
    }

    private static String reason(String component) {
        try {
            value(component, ValidValues.Variant.REQUIRED);
            throw new AssertionError(component + " has a value");
        } catch (ValidValues.Unsatisfiable e) {
            return e.reason;
        }
    }

    @Test
    void numberBoundsWithoutAMultiple() {
        assertThat(json("Inclusive")).isEqualTo("2.5");
        assertThat(json("UpperInclusive")).isEqualTo("-2.5");
        assertThat(json("UpperExclusive")).isEqualTo("-3");
        assertThat(reason("Equal")).startsWith("the bounds leave no number: >= 3");
    }

    @Test
    void distinctItemsAreFoundBelowTheStartAndPastTheLastLetter() {
        assertThat(json("UniqueNegatives")).isEqualTo("[-3,-4,-5]");
        assertThat(json("ManyStrings")).endsWith(",\"z\",\"aa\"]");
        assertThat(json("UniqueObjects")).isEqualTo("[{\"k\":\"x\"},{\"k\":\"y\"}]");
        assertThat(json("UniqueBooleans")).isEqualTo("[false,true]");
    }

    @Test
    void anUndeclaredRequiredMemberIsNull() {
        assertThat(json("Ghost")).isEqualTo("{\"ghost\":null}");
    }

    @Test
    void aClosedObjectCannotReachMinProperties() {
        assertThat(reason("Closed")).startsWith("minProperties 1 needs more members than can be added: additional1:");
    }

    @Test
    void theKeywordsImplyATypeWhereNoneIsNamed() {
        assertThat(json("Inferred")).isEqualTo("{\"o\":{\"x\":null},\"a\":[null],\"s\":\"aa\",\"n\":4}");
    }

    @Test
    void anyOfTakesTheFirstBranchWhoseValueIsValid() {
        assertThat(json("Choice")).isEqualTo("2.5");
    }

    @Test
    void aOneOfWithNoUsableBranchSaysWhyForEach() {
        assertThat(reason("NoBranch")).startsWith("no branch of the oneOf yields a value valid against exactly one "
                + "branch: branch 0: the bounds leave no number");
        assertThat(reason("Twins")).contains("the value of branch 0, 2.5, is also valid against branch(es) [1]");
    }

    @Test
    void aRecursionThatEveryBranchTakesTooFarIsTooDeep() {
        assertThatThrownBy(() -> value("Loop", ValidValues.Variant.REQUIRED)).isInstanceOf(ValidValues.TooDeep.class)
                .hasMessageContaining("no branch of the oneOf yields a value");
    }

    @Test
    void aFullBodyStopsANestedListAtTheRecursionDepth() {
        assertThat(ValueJson.write(value("Nested", ValidValues.Variant.FULL)))
                .isEqualTo("{\"children\":[{\"children\":[{\"children\":[]}]}]}");
    }

    @Test
    void theOtherStringsWithNoValueSayWhy() {
        assertThat(reason("ApartPatterns")).startsWith("no string of 0 to any code points matching ^a+$ and ^b+$ "
                + "was found among the first " + ValidValues.SEARCH);
        assertThat(reason("Nothing")).isEqualTo("pattern [] matches no string of at most 4096 code points");
        assertThat(reason("Huge")).isEqualTo("minLength 5000 is beyond the 4096 code points this generator produces");
        assertThat(reason("ShortUuid")).isEqualTo("this generator has no uuid value of 0 to 10 code points; its "
                + "canonical value is 00000000-0000-4000-8000-000000000000");
        assertThat(reason("Zero")).isEqualTo("multipleOf 0 is not positive");
    }

    @Test
    void aRequestTakesNoBodyValueFromAMediaTypeWithoutASchemaAndNoPrimitiveFromAList() {
        ValidRequests requests = new ValidRequests(model, new Shapes(model), values);
        Operation operation = model.operations().get(0);
        assertThat(requests.unsupported(operation)).extracting(ValidRequests.Unsupported::reason).containsExactly(
                "a parameter described by content rather than by a schema is not supported yet");
        assertThatThrownBy(() -> requests.request(operation, ValidRequests.Kind.REQUIRED))
                .isInstanceOf(ValidValues.Unsatisfiable.class).hasMessageContaining("parameter id has no primitive value");
        ValidRequests.Request free = requests.request(model.operations().get(1), ValidRequests.Kind.REQUIRED);
        assertThat(free.query()).containsExactly(new ValidRequests.Pair("free", ""));
        assertThat(free.contentType()).isEqualTo("application/json");
        assertThat(free.body()).isSameAs(ValueJson.NULL);
    }

    /** The generator's own validator, and the oracle, on one value. */
    @ParameterizedTest(name = "{0}: {1}")
    @CsvSource(delimiter = '|', value = {
        "Mixed|{}",
        "Mixed|{\"s\":\"ab\"}",
        "Mixed|{\"s\":\"a\"}",
        "Mixed|{\"s\":\"abcd\"}",
        "Mixed|{\"s\":\"AB\"}",
        "Mixed|{\"s\":1}",
        "Mixed|{\"f\":\"2000-02-30\"}",
        "Mixed|{\"f\":\"2000-02-03\"}",
        "Mixed|{\"i\":2}",
        "Mixed|{\"i\":0}",
        "Mixed|{\"i\":10}",
        "Mixed|{\"i\":3}",
        "Mixed|{\"i\":2.5}",
        "Mixed|{\"i\":4.0}",
        "Mixed|{\"b\":true}",
        "Mixed|{\"b\":null}",
        "Mixed|{\"c\":\"fixed\"}",
        "Mixed|{\"c\":\"other\"}",
        "Mixed|{\"e\":1.0}",
        "Mixed|{\"e\":[3]}",
        "Mixed|{\"e\":[4]}",
        "Mixed|{\"l\":[]}",
        "Mixed|{\"l\":[\"a\",\"a\"]}",
        "Mixed|{\"l\":[\"a\",\"b\",\"c\"]}",
        "Mixed|{\"l\":[\"a\",1]}",
        "Mixed|{\"m\":{\"r\":\"a\"}}",
        "Mixed|{\"m\":{}}",
        "Mixed|{\"m\":{\"r\":\"a\",\"p1\":1}}",
        "Mixed|{\"m\":{\"r\":\"a\",\"p1\":\"x\"}}",
        "Mixed|{\"m\":{\"r\":\"a\",\"q\":1}}",
        "Mixed|{\"m\":{\"r\":\"a\",\"p1\":1,\"p2\":2}}",
        "Mixed|{\"one\":2.5}",
        "Mixed|{\"one\":3}",
        "Mixed|{\"one\":1}",
        "Mixed|{\"any\":-3}",
        "Mixed|{\"any\":0}",
        "Mixed|{\"all\":\"a\"}",
        "Mixed|{\"all\":\"ab\"}",
        "Mixed|[]",
        "UniqueObjects|[{\"k\":\"x\"},{\"k\":\"x\"}]",
        "UniqueObjects|[{\"k\":\"x\"},{\"k\":\"y\"}]",
    })
    void theGeneratorsValidatorAgreesWithTheOracle(String component, String instance) {
        String at = "/components/schemas/" + component;
        Object parsed = new Load(LoadSettings.builder().build()).loadFromString(instance);
        boolean ours = values.valid(ValueJson.of(parsed), model.component(component).orElseThrow().schema(), at);
        assertThat(ours).as("the generator's validator; the oracle says %s", oracle.errors(at,
                Oracle.JSON.readTree(instance))).isEqualTo(oracle.valid(at, Oracle.JSON.readTree(instance)));
    }

    @Test
    void theValidatorRefusesToJudgeWhatTheModelDoesNotType() throws IOException {
        Path other = directory.resolve("not.yaml");
        Files.writeString(other, """
                openapi: 3.1.0
                info: {title: Not, version: 1.0.0}
                components:
                  schemas:
                    Not: {type: string, not: {const: a}}
                """);
        ContractModel notModel = ContractParser.parse(other, null);
        ValidValues notValues = new ValidValues(new Shapes(notModel), 2);
        assertThatThrownBy(() -> notValues.valid("b", notModel.component("Not").orElseThrow().schema(),
                "/components/schemas/Not")).isInstanceOf(ValidValues.Unsatisfiable.class)
                .hasMessageContaining("validity depends on not");
    }

    @Test
    void lcmIsExact() {
        assertThat(ValidValues.lcm(new java.math.BigDecimal("0.1"), new java.math.BigDecimal("0.25")))
                .isEqualByComparingTo("0.5");
        assertThat(ValidValues.lcm(java.math.BigDecimal.ONE, new java.math.BigDecimal("2.5")))
                .isEqualByComparingTo("5");
        assertThat(List.of(ValidValues.Variant.values())).hasSize(2);
    }
}
