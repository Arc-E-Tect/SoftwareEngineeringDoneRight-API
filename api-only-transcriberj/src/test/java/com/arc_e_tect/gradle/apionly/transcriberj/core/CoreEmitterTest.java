package com.arc_e_tect.gradle.apionly.transcriberj.core;

import com.arc_e_tect.gradle.apionly.transcriberj.model.Construct;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.GeneratedClass;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.Origin;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What the core emitter generates, contract feature by contract feature. */
class CoreEmitterTest {

    @TempDir
    Path directory;

    private static String contract(String paths, String components) {
        return "openapi: 3.1.0\ninfo: {title: Test, version: 1.0.0}\npaths:\n" + paths.indent(2)
                + "components:\n" + components.indent(2);
    }

    private static String schema(String name, String path, String body) {
        return name + ":\n  x-fragment-path: " + path + "\n" + body.indent(2);
    }

    private GeneratedSources generate(String yaml) {
        return GeneratedSources.generate(yaml, directory, List.of());
    }

    // ------------------------------------------------------------- naming

    @Test
    void fragmentsSharingAFileNameAreToldApartByTheirNearestDifferingDirectory() {
        GeneratedSources g = generate(contract("{}", "schemas:\n" + (
                schema("UserV1", "openapi/components/iff/user-account/schemas/UserV1.yaml", "type: object")
                        + schema("UserV1-2", "openapi/components/iff/other/schemas/UserV1.yaml", "type: object")
                        + schema("Deep", "openapi/a/b/UserV1.yaml", "type: object")
                        + schema("Plain", "openapi/components/Plain.yaml", "type: string")).indent(2)));

        assertThat(g.classNames()).contains("UserAccountUserV1", "OtherUserV1", "BUserV1", "Plain");
        g.compile();
    }

    @Test
    void fragmentsThatStillCollideAreRefused() {
        String yaml = contract("{}", "schemas:\n" + (
                schema("A", "openapi/x/Same.yaml", "type: string")
                        + schema("B", "openapi/x/Same.yml", "type: string")).indent(2));
        assertThatThrownBy(() -> generate(yaml)).isInstanceOf(GenerationException.class)
                .hasMessageContaining("openapi/x/Same.yaml and openapi/x/Same.yml would still all be named Same");
    }

    @Test
    void aFragmentMayNotTakeTheNameOfASupportClass() {
        String yaml = contract("{}", "schemas:\n"
                + schema("F", "openapi/ContractField.yaml", "type: string").indent(2));
        assertThatThrownBy(() -> generate(yaml)).isInstanceOf(GenerationException.class)
                .hasMessageContaining("openapi/ContractField.yaml would be named ContractField, which the "
                        + "generated support classes use");
    }

    @Test
    void anInlineClassThatCollidesWithAComponentIsRefused() {
        String yaml = contract("""
                /a:
                  get:
                    operationId: getA
                    responses:
                      '200':
                        content:
                          application/json:
                            schema: {type: object, properties: {x: {type: string}}}
                """, "schemas:\n" + schema("C", "openapi/GetAResponse200.yaml", "type: object").indent(2));
        assertThatThrownBy(() -> generate(yaml)).isInstanceOf(GenerationException.class)
                .hasMessageContaining("openapi/GetAResponse200.yaml and "
                        + "/paths/~1a/get/responses/200/content/application~1json/schema would all be named "
                        + "GetAResponse200");
    }

    @Test
    void componentsWithoutAFragmentPathAreAllNamed() {
        String yaml = contract("{}", """
                schemas:
                  A: {type: string}
                responses:
                  R: {description: r}
                """);
        assertThatThrownBy(() -> generate(yaml)).isInstanceOf(GenerationException.class)
                .hasMessageContaining("/components/schemas/A, /components/responses/R")
                .hasMessageContaining("fragmentPaths");
    }

    @Test
    void fileNamesThatAreNotIdentifiersBecomePascalCase() {
        GeneratedSources g = generate(contract("{}", "schemas:\n" + (
                schema("a", "openapi/user-profile.v2.yaml", "type: string")
                        + schema("b", "openapi/2fa.yaml", "type: string")
                        + schema("c", "openapi/lower.yaml", "type: string")).indent(2)));
        assertThat(g.classNames()).contains("UserProfileV2", "Type2fa", "Lower");
    }

    // ------------------------------------------------------ inline schemas

    @Test
    void inlineSchemasAreNamedAfterTheirOperationAndRecommendedAsComponents() {
        GeneratedSources g = generate(contract("""
                /things/{id}:
                  get:
                    responses:
                      '200':
                        content:
                          application/json:
                            schema: {type: object, properties: {name: {type: string}}}
                          application/xml:
                            schema: {type: object, properties: {name: {type: string}}}
                      4XX:
                        content:
                          application/json:
                            schema:
                              oneOf: [{$ref: '#/components/schemas/Problem'}]
                      default:
                        $ref: '#/components/responses/Failure'
                  put:
                    operationId: replace-thing
                    requestBody:
                      content:
                        application/json:
                          schema: {type: array, items: {type: string}}
                    responses:
                      '204': {description: done}
                """, "schemas:\n" + schema("Problem", "openapi/Problem.yaml", "type: object").indent(2)
                + "responses:\n" + schema("Failure", "openapi/responses/Failure.yaml",
                "description: failed\ncontent:\n  text/plain: {}").indent(2)));

        assertThat(g.classNames()).contains("GetThingsByIdResponse200Json", "GetThingsByIdResponse200Xml",
                "ReplaceThingRequest", "Failure", "Problem");
        assertThat(g.classNames()).noneMatch(n -> n.contains("4XX"));
        assertThat(g.report.recommendations()).extracting(GenerationReport.Recommendation::advice)
                .anyMatch(a -> a.startsWith("an operation without an operationId"))
                .filteredOn(a -> a.startsWith("an inline schema")).hasSize(3);
        assertThat(g.constant("ReplaceThingRequest", "METHOD")).isEqualTo("PUT");
        assertThat(g.constant("ReplaceThingRequest", "CONTENT_TYPE")).isEqualTo("application/json");
        assertThat(g.constant("GetThingsByIdResponse200Xml", "CONTENT_TYPE")).isEqualTo("application/xml");
        assertThat(g.constant("GetThingsByIdResponse200Xml", "PATH")).isEqualTo("/things/{id}");
        assertThat(g.source("GetThingsByIdResponse200Json")).doesNotContain("OPERATION_ID");
        assertThat(g.constant("Failure", "CONTENT_TYPES")).isEqualTo(List.of("text/plain"));
        assertThat(g.source("ReplaceThingRequest")).doesNotContain("body(");
    }

    // --------------------------------------------------- reusable components

    @Test
    void reusableComponentsGetClassesOfTheirOwn() throws Throwable {
        GeneratedSources g = generate(contract("""
                /users/{id}:
                  parameters:
                    - $ref: '#/components/parameters/Id'
                  post:
                    parameters:
                      - $ref: '#/components/parameters/Page'
                    requestBody:
                      $ref: '#/components/requestBodies/NewUser'
                    responses:
                      '201':
                        $ref: '#/components/responses/Created'
                      '400':
                        $ref: '#/components/responses/Invalid'
                """, "schemas:\n" + (
                schema("User", "openapi/schemas/User.yaml",
                        "type: object\nrequired: [name]\nproperties:\n  name: {type: string}\n  age: {type: integer}")
                        + schema("Id", "openapi/schemas/Id.yaml", "type: string\nformat: uuid")).indent(2)
                + "parameters:\n" + (
                schema("Id", "openapi/parameters/IdParam.yaml",
                        "name: id\nin: path\nrequired: true\nschema: {$ref: '#/components/schemas/Id'}")
                        + schema("Page", "openapi/parameters/Page.yaml",
                        "name: page\nin: query\nschema: {type: integer, minimum: 1, maximum: 5000000000}")).indent(2)
                + "requestBodies:\n" + schema("NewUser", "openapi/bodies/NewUser.yaml",
                "content:\n  application/json:\n    schema: {$ref: '#/components/schemas/User'}").indent(2)
                + "responses:\n" + (
                schema("Created", "openapi/responses/Created.yaml", """
                        description: created
                        content:
                          application/json:
                            schema: {$ref: '#/components/schemas/User'}
                          application/xml:
                            schema: {$ref: '#/components/schemas/User'}
                        """)
                        + schema("Invalid", "openapi/responses/Invalid.yaml", """
                        description: invalid
                        content:
                          application/problem+json:
                            schema:
                              type: object
                              required: [status]
                              properties:
                                status: {type: integer, const: 400}
                                detail: {type: string}
                        """)).indent(2)));

        assertThat(g.constant("IdParam", "NAME")).isEqualTo("id");
        assertThat(g.constant("IdParam", "IN")).isEqualTo("path");
        assertThat(g.constant("IdParam", "REQUIRED")).isEqualTo(true);
        assertThat(g.constant("IdParam", "SCHEMA")).isEqualTo(g.type("Id"));
        assertThat(g.constant("Page", "REQUIRED")).isEqualTo(false);
        assertThat(g.constant("Page", "MINIMUM")).isEqualTo(1);
        assertThat(g.constant("Page", "MAXIMUM")).isEqualTo(5000000000L);
        assertThat(g.constant("Id", "FORMAT")).isEqualTo("uuid");
        assertThat(Modifier.isPublic(g.type("Id").getModifiers())).isTrue();

        assertThat(g.constant("NewUser", "CONTENT_TYPE")).isEqualTo("application/json");
        assertThat(g.call("NewUser", "body", new Class<?>[]{String.class}, "ann")).isEqualTo("{\"name\":\"ann\"}\n");
        assertThat(g.call("NewUser", "body", new Class<?>[]{String.class, Integer.class}, "ann", 3))
                .isEqualTo("{\"name\":\"ann\",\"age\":3}\n");
        assertThat((List<?>) g.call("NewUser", "fields", new Class<?>[]{String.class}, "")).hasSize(2);

        assertThat(g.constant("Created", "CONTENT_TYPES")).isEqualTo(List.of("application/json", "application/xml"));
        assertThat(g.source("Created")).doesNotContain("CONTENT_TYPE =").doesNotContain("body(");

        assertThat(g.call("Invalid", "body", new Class<?>[]{}))
                .isEqualTo("{\"status\":400}\n");
        assertThat(g.call("Invalid", "body", new Class<?>[]{String.class}, "bad"))
                .isEqualTo("{\"status\":400,\"detail\":\"bad\"}\n");
        assertThat(Modifier.isPublic(g.type("User").getModifiers())).isTrue();
    }

    @Test
    void aReusableResponseReferringToAComponentWithoutOptionalMembersDelegatesOnce() throws Throwable {
        GeneratedSources g = generate(contract("{}", "schemas:\n" + schema("Pair", "openapi/Pair.yaml",
                "type: object\nrequired: [a]\nproperties:\n  a: {type: boolean}").indent(2)
                + "responses:\n" + schema("Ok", "openapi/responses/Ok.yaml",
                "description: ok\ncontent:\n  application/json:\n    schema: {$ref: '#/components/schemas/Pair'}")
                .indent(2)));

        assertThat(g.call("Ok", "body", new Class<?>[]{boolean.class}, true)).isEqualTo("{\"a\":true}\n");
        assertThat(g.source("Ok")).containsOnlyOnce("public static String body(");
    }

    @Test
    void theScaffoldsReusableResponseCarriesItsOwnBody() throws Throwable {
        GeneratedSources g = GeneratedSources.generate(GeneratedSources.FIXTURES.resolve("scaffold/openapi.yaml"),
                "0.1.0", directory, List.of());

        assertThat(g.classNames()).contains("InvalidRequestProblemV1");
        assertThat(g.constant("InvalidRequestProblemV1", "FRAGMENT_PATH"))
                .isEqualTo("openapi/components/common/responses/errors/InvalidRequestProblemV1.yaml");
        assertThat(g.call("InvalidRequestProblemV1", "body",
                new Class<?>[]{String.class, String.class, int.class}, "t", "T", 400))
                .isEqualTo("{\"type\":\"t\",\"title\":\"T\",\"status\":400}\n");
        assertThat(g.report.undecided()).extracting(f -> f.construct())
                .containsExactly(Construct.UNMODELLED_COMPONENT_TYPE);
    }

    // -------------------------------------------------------------- bodies

    @Test
    void everyKindOfValueIsPassedAndWritten() throws Throwable {
        GeneratedSources g = generate(contract("""
                /x:
                  post:
                    requestBody:
                      content:
                        application/json:
                          schema: {$ref: '#/components/schemas/Everything'}
                    responses: {}
                """, "schemas:\n" + (schema("Everything", "openapi/Everything.yaml", """
                type: object
                required: [text, count, big, ratio, flag, child, tags, scores, children, inline, class, first-name]
                properties:
                  text: {type: string}
                  count: {type: integer}
                  big: {type: integer, format: int64}
                  ratio: {type: number}
                  flag: {type: boolean}
                  child: {$ref: '#/components/schemas/Child'}
                  tags: {type: array, items: {type: string}}
                  scores: {type: array, items: {type: integer}}
                  children: {type: array, items: {$ref: '#/components/schemas/Child'}}
                  inline: {type: object, properties: {deep: {type: string}}}
                  class: {type: string}
                  first-name: {type: string}
                  firstName: {type: string}
                  alias: {$ref: '#/components/schemas/Code'}
                  list: {$ref: '#/components/schemas/Children'}
                  anything: {}
                  either: {oneOf: [{$ref: '#/components/schemas/Child'}]}
                  raw: {type: array}
                  nothing: {type: 'null'}
                """) + schema("Child", "openapi/Child.yaml", "type: object\nproperties:\n  id: {type: string}")
                + schema("Code", "openapi/Code.yaml", "type: string\nenum: [a, b]")
                + schema("Children", "openapi/Children.yaml",
                "type: array\nitems: {$ref: '#/components/schemas/Child'}")).indent(2)));

        String child = (String) g.call("Child", "body", new Class<?>[]{String.class}, "c1");
        assertThat(child).isEqualTo("{\"id\":\"c1\"}\n");
        String source = g.source("Everything");
        assertThat(source).contains("String classValue", "String firstName,", "String firstName2");
        Class<?>[] required = {String.class, int.class, long.class, double.class, boolean.class, String.class,
                List.class, List.class, List.class, String.class, String.class, String.class};
        Object body = g.call("Everything", "body", required, "t", 1, 2L, 0.5, true, child, List.of("a", "b"),
                List.of(1, 2), List.of(child, child), "{\"deep\":\"d\"}", "k", "f");
        assertThat(body).isEqualTo("{\"text\":\"t\",\"count\":1,\"big\":2,\"ratio\":0.5,\"flag\":true,"
                + "\"child\":{\"id\":\"c1\"},\"tags\":[\"a\",\"b\"],\"scores\":[1,2],"
                + "\"children\":[{\"id\":\"c1\"},{\"id\":\"c1\"}],\"inline\":{\"deep\":\"d\"},\"class\":\"k\","
                + "\"first-name\":\"f\"}\n");
        assertThatThrownBy(() -> g.call("Everything", "body", required, null, 1, 2L, 0.5, true, child,
                List.of(), List.of(), List.of(), "{}", "k", "f"))
                .isInstanceOf(NullPointerException.class).hasMessage("text");

        assertThat(g.constant("Code", "ENUM")).isEqualTo(List.of("a", "b"));
        assertThat(Modifier.isPublic(g.type("Child").getModifiers())).isTrue();
        assertThat(Modifier.isPublic(g.type("Code").getModifiers())).isFalse();

        List<?> fields = (List<?>) g.call("Everything", "fields", new Class<?>[]{String.class}, "p.");
        assertThat(fields).extracting(Object::toString).contains(
                "ContractField[path=p.child, type=object, optional=false, description=PLACEHOLDER, "
                        + "subsection=false, open=false]",
                "ContractField[path=p.child.id, type=string, optional=true, description=PLACEHOLDER, "
                        + "subsection=false, open=false]",
                "ContractField[path=p.children[].id, type=string, optional=true, description=PLACEHOLDER, "
                        + "subsection=false, open=false]",
                "ContractField[path=p.inline.deep, type=string, optional=true, description=PLACEHOLDER, "
                        + "subsection=false, open=false]",
                "ContractField[path=p.alias, type=string, optional=true, description=PLACEHOLDER, "
                        + "subsection=false, open=false]",
                "ContractField[path=p.list[].id, type=string, optional=true, description=PLACEHOLDER, "
                        + "subsection=false, open=false]",
                "ContractField[path=p.anything, type=varies, optional=true, description=PLACEHOLDER, "
                        + "subsection=false, open=false]",
                "ContractField[path=p.either, type=varies, optional=true, description=PLACEHOLDER, "
                        + "subsection=false, open=false]",
                "ContractField[path=p.raw, type=array, optional=true, description=PLACEHOLDER, "
                        + "subsection=false, open=false]",
                "ContractField[path=p.nothing, type=null, optional=true, description=PLACEHOLDER, "
                        + "subsection=false, open=false]");
    }

    @Test
    void constantsOfEveryKindAreDeclared() throws Throwable {
        GeneratedSources g = generate(contract("{}", "schemas:\n" + (schema("Fixed", "openapi/Fixed.yaml", """
                type: object
                properties:
                  s: {const: text}
                  i: {const: 7}
                  l: {const: 5000000000}
                  d: {const: 1.5}
                  b: {const: true}
                  n: {const: null}
                  o: {const: {k: [1]}}
                  big: {const: 100000000000000000000}
                  count:
                    type: array
                    minItems: 1
                    maxItems: 3
                    uniqueItems: true
                  size:
                    type: object
                    minProperties: 1
                    maxProperties: 2
                  ratio:
                    type: number
                    minimum: 0.5
                    exclusiveMinimum: true
                    exclusiveMaximum: 10
                    multipleOf: 0.25
                  mixed: {enum: [a, 1, null, 2.5, 5000000000, false, [x]]}
                """) + schema("Old", "openapi/Old.yaml",
                "type: number\nexclusiveMaximum: false\nminLength: 5000000000")).indent(2)));

        assertThat(g.call("Fixed", "body", new Class<?>[]{List.class, String.class, Double.class, String.class},
                null, null, null, null)).isEqualTo("{\"s\":\"text\",\"i\":7,\"l\":5000000000,\"d\":1.5,"
                + "\"b\":true,\"n\":null,\"o\":{\"k\":[1]},\"big\":100000000000000000000}\n");
        assertThat(g.constant("Fixed", "COUNT_MIN_ITEMS")).isEqualTo(1);
        assertThat(g.constant("Fixed", "COUNT_UNIQUE_ITEMS")).isEqualTo(true);
        assertThat(g.constant("Fixed", "SIZE_MAX_PROPERTIES")).isEqualTo(2);
        assertThat(g.constant("Fixed", "RATIO_MINIMUM")).isEqualTo(0.5d);
        assertThat(g.constant("Fixed", "RATIO_EXCLUSIVE_MINIMUM")).isEqualTo(true);
        assertThat(g.constant("Fixed", "RATIO_EXCLUSIVE_MAXIMUM")).isEqualTo(10);
        assertThat(g.constant("Fixed", "RATIO_MULTIPLE_OF")).isEqualTo(0.25d);
        assertThat(g.constant("Fixed", "MIXED_ENUM"))
                .isEqualTo(java.util.Arrays.asList("a", 1, null, 2.5d, 5000000000L, false, "[\"x\"]"));
        assertThat(g.constant("Old", "EXCLUSIVE_MAXIMUM")).isEqualTo(false);
        assertThat(g.constant("Old", "MIN_LENGTH")).isEqualTo(5000000000L);
    }

    @Test
    void propertiesWhoseConstantsWouldClashAreRefused() {
        String yaml = contract("{}", "schemas:\n" + schema("Clash", "openapi/Clash.yaml", """
                type: object
                properties:
                  first-name: {const: a}
                  first_name: {const: b}
                """).indent(2));
        assertThatThrownBy(() -> generate(yaml)).isInstanceOf(GenerationException.class)
                .hasMessageContaining("Clash would declare FIRST_NAME twice");
    }

    // --------------------------------------------------------- degradation

    @Test
    void anUnrepresentableConstructDegradesOnlyTheMethodsItReaches() throws Throwable {
        GeneratedSources g = generate(contract("{}", "schemas:\n" + (schema("Union", "openapi/Union.yaml", """
                type: object
                properties:
                  either: {type: [string, 'null']}
                """) + schema("Anonymous", "openapi/Anonymous.yaml", """
                type: object
                properties:
                  choice:
                    oneOf: [{type: string}, {type: integer}]
                """) + schema("Literal", "openapi/Literal.yaml", """
                type: object
                properties:
                  anything: true
                  list: {type: array, items: false}
                """) + schema("Deep", "openapi/Deep.yaml", """
                type: object
                properties:
                  items:
                    type: array
                    items: {type: object, properties: {v: {type: [integer, string]}}}
                """) + schema("Whole", "openapi/Whole.yaml", "type: [object, 'null']\nproperties: {a: {type: string}}")
                + schema("Uses", "openapi/Uses.yaml", "type: object\nproperties:\n  u: {$ref: '#/components/schemas/Union'}")
        ).indent(2)));

        assertThat(g.constant("Union", "FRAGMENT_PATH")).isEqualTo("openapi/Union.yaml");
        assertThatThrownBy(() -> g.call("Union", "body", new Class<?>[]{Object[].class}, (Object) new Object[0]))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("Union.body(...) cannot be generated from contract test-contract: MULTIPLE_TYPES at "
                        + "/components/schemas/Union/properties/either (type: string, null). "
                        + "See the API-Only TranscriberJ report.");
        assertThatThrownBy(() -> g.call("Union", "fields", new Class<?>[]{String.class}, ""))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> g.call("Anonymous", "body", new Class<?>[]{Object[].class}, (Object) new Object[0]))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("ONE_OF_INLINE_BRANCHES")
                .hasMessageContaining("; name the branches in the specification");
        assertThatThrownBy(() -> g.call("Deep", "fields", new Class<?>[]{String.class}, ""))
                .isInstanceOf(UnsupportedOperationException.class);
        // The caller writes an inline object's JSON, so the union inside it never reaches body(...).
        assertThat(g.call("Deep", "body", new Class<?>[]{List.class}, List.of("{\"v\":1}")))
                .isEqualTo("{\"items\":[{\"v\":1}]}\n");
        assertThat(g.source("Uses")).doesNotContain("UnsupportedOperationException");

        assertThat(g.report.degraded()).extracting(d -> d.className() + "." + d.method() + " " + d.emitter())
                .containsExactly(
                        "Union.body(...) core", "Union.fields(String) core",
                        "Anonymous.body(...) core", "Anonymous.fields(String) core",
                        "Literal.body(...) core", "Literal.fields(String) core",
                        "Deep.fields(String) core",
                        "Whole.body(...) core", "Whole.fields(String) core");
        assertThat(g.report.render("test-contract", "1.0.0"))
                .contains("9 degraded method(s), 0 recommendation(s),")
                .contains("  Anonymous.body(...) [core]: ONE_OF_INLINE_BRANCHES at "
                        + "/components/schemas/Anonymous/properties/choice (oneOf: <inline #1>, <inline #2>); "
                        + "remedy: name the branches")
                .contains("Undecided constructs");
    }

    @Test
    void aReusableResponseDelegatingToADegradedComponentDegradesToo() throws Throwable {
        GeneratedSources g = generate(contract("{}", "schemas:\n" + schema("Union", "openapi/Union.yaml",
                "type: object\nproperties:\n  e: {type: [string, integer]}").indent(2)
                + "responses:\n" + schema("Wrap", "openapi/responses/Wrap.yaml",
                "description: w\ncontent:\n  application/json:\n    schema: {$ref: '#/components/schemas/Union'}")
                .indent(2)));

        assertThatThrownBy(() -> g.call("Wrap", "body", new Class<?>[]{Object[].class}, (Object) new Object[0]))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // --------------------------------------------------------- composition

    private static String requestBody(String component) {
        return """
                /refunds:
                  post:
                    requestBody:
                      content:
                        application/json:
                          schema: {$ref: '#/components/schemas/%s'}
                    responses: {}
                """.formatted(component);
    }

    private static final String BRANCHES = schema("Card", "openapi/Card.yaml", """
            type: object
            required: [destination, digits]
            properties:
              destination: {const: card}
              digits: {type: string}
            """) + schema("Bank", "openapi/Bank.yaml", """
            type: object
            required: [destination, iban]
            properties:
              destination: {const: bank-account}
              iban: {type: string}
            """);

    @Test
    void aChoiceWithAnInlineObjectBranchDegradesItsBodyWhetherOrNotItSaysItIsAnObject() {
        GeneratedSources g = generate(contract(requestBody("Bare"), "schemas:\n" + (
                schema("Bare", "openapi/Bare.yaml", """
                        oneOf:
                          - {type: object, properties: {digits: {type: string}}}
                          - {type: object, properties: {iban: {type: string}}}
                        """) + schema("Typed", "openapi/Typed.yaml", """
                        type: object
                        anyOf:
                          - {type: object, properties: {digits: {type: string}}}
                          - {$ref: '#/components/schemas/Bank'}
                        """) + schema("Scalar", "openapi/Scalar.yaml", """
                        oneOf: [{type: string}, {type: integer}]
                        """) + BRANCHES).indent(2)));

        assertThatThrownBy(() -> g.call("Bare", "body", new Class<?>[]{Object[].class}, (Object) new Object[0]))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("ONE_OF_INLINE_BRANCHES at /components/schemas/Bare");
        assertThatThrownBy(() -> g.call("Bare", "fields", new Class<?>[]{String.class}, ""))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> g.call("Typed", "body", new Class<?>[]{Object[].class}, (Object) new Object[0]))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("ANY_OF_INLINE_BRANCHES at /components/schemas/Typed");
        // A choice between values, not objects, is no body: it is written as a value.
        assertThat(g.source("Scalar")).doesNotContain("body(").doesNotContain("fields(");

        assertThat(g.report.degraded()).extracting(d -> d.className() + "." + d.method())
                .containsExactlyInAnyOrder("Bare.body(...)", "Bare.fields(String)",
                        "Typed.body(...)", "Typed.fields(String)");
    }

    @Test
    void aChoiceOfNamedBranchesIsWrittenByItsBranches() throws Throwable {
        GeneratedSources g = generate(contract(requestBody("Refund") + requestBody("Bare").replace("/refunds", "/bare"),
                "schemas:\n" + (schema("Refund", "openapi/Refund.yaml", """
                        type: object
                        oneOf:
                          - {$ref: '#/components/schemas/Card'}
                          - {$ref: '#/components/schemas/Bank'}
                        """) + schema("Bare", "openapi/Bare.yaml", """
                        anyOf:
                          - {$ref: '#/components/schemas/Card'}
                        """) + BRANCHES).indent(2)));

        // Not an empty object that matches neither branch: no body at all.
        assertThat(g.source("Refund")).doesNotContain("body(").doesNotContain("fields(")
                .contains("{@link Card}", "{@link Bank}");
        assertThat(g.source("Bare")).doesNotContain("body(").doesNotContain("fields(");
        assertThat(g.report.degraded()).isEmpty();

        // The branches are what a caller writes, so they are as public as the body they make up.
        assertThat(Modifier.isPublic(g.type("Card").getModifiers())).isTrue();
        assertThat(Modifier.isPublic(g.type("Bank").getModifiers())).isTrue();
        assertThat(g.call("Card", "body", new Class<?>[]{String.class}, "4242"))
                .isEqualTo("{\"destination\":\"card\",\"digits\":\"4242\"}\n");
    }

    @Test
    void theBranchesOfAChoiceNothingPublicUsesStayPackagePrivate() {
        GeneratedSources g = generate(contract("{}", "schemas:\n" + (schema("Refund", "openapi/Refund.yaml", """
                type: object
                oneOf:
                  - {$ref: '#/components/schemas/Card'}
                  - {$ref: '#/components/schemas/Bank'}
                """) + BRANCHES).indent(2)));

        assertThat(Modifier.isPublic(g.type("Card").getModifiers())).isFalse();
    }

    // ----------------------------------------------------------- recursion

    @Test
    void aRecursiveReferenceIsFollowedToTheConfiguredDepthAndThenIsASubsection() throws Throwable {
        GeneratedSources g = generate(contract("{}", "schemas:\n" + (schema("Node", "openapi/Node.yaml", """
                type: object
                properties:
                  name: {type: string}
                  next: {$ref: '#/components/schemas/Node'}
                  children: {type: array, items: {$ref: '#/components/schemas/Node'}}
                """)).indent(2)));

        List<?> fields = (List<?>) g.call("Node", "fields", new Class<?>[]{String.class}, "");
        List<String> paths = fields.stream().map(Object::toString)
                .map(s -> s.substring(s.indexOf("path=") + 5, s.indexOf(','))
                        + (s.contains("subsection=true") ? " (subsection)" : "")).toList();
        assertThat(paths).startsWith("name", "next", "next.name", "next.next", "next.next.name",
                "next.next.next (subsection)", "next.next.children");
        assertThat(paths).contains("children", "children[].name", "next.next.children[] (subsection)",
                "children[].children[].children[] (subsection)");
        assertThat(g.constant("ContractField", "MAX_DEPTH")).isEqualTo(2);
    }

    // ---------------------------------------------------------- the run

    @Test
    void theRunRefusesWhatItCannotGenerateFrom() throws Exception {
        Path contract = GeneratedSources.FIXTURES.resolve("contracts/user-account/openapi.yaml");
        Path out = directory.resolve("out");
        assertThatThrownBy(() -> Generation.run(contract, "1.0.0", "x",
                new Settings("c", "not a package", false, "p", 1), out, List.of(), null))
                .isInstanceOf(GenerationException.class).hasMessageContaining("basePackage not a package");
        assertThatThrownBy(() -> Generation.run(contract, "1.0.0", "x",
                new Settings("c", null, false, "p", 1), out, List.of(), null))
                .isInstanceOf(GenerationException.class).hasMessageContaining("basePackage null");
        assertThatThrownBy(() -> Generation.run(contract, "2.0.0", "x",
                new Settings("c", "a.b", false, "p", 1), out, List.of(), null))
                .isInstanceOf(GenerationException.class)
                .hasMessageContaining("locked at version 2.0.0, but " + contract + " says it is version 1.0.0");
        assertThatThrownBy(() -> Generation.run(directory.resolve("missing.yaml"), "1.0.0", "x",
                new Settings("c", "a.b", false, "p", 1), out, List.of(), null))
                .isInstanceOf(java.io.UncheckedIOException.class);
    }

    @Test
    void theRunReplacesWhatTheOutputDirectoryHeld() throws Exception {
        Path out = directory.resolve("sources");
        Files.createDirectories(out.resolve("old/pkg"));
        Files.writeString(out.resolve("old/pkg/Stale.java"), "stale");
        GeneratedSources g = GeneratedSources.generate(GeneratedSources.FIXTURES.resolve("scaffold/openapi.yaml"),
                "0.1.0", directory, List.of());
        assertThat(out.resolve("old")).doesNotExist();
        assertThat(g.classNames()).isNotEmpty();
    }

    @Test
    void anEmitterRunsAfterTheCoreOverTheSameNamesAndReportsUnderItsOwnId() throws Throwable {
        GeneratedSources g = GeneratedSources.generate(
                GeneratedSources.FIXTURES.resolve("contracts/user-account/openapi.yaml"), "1.0.0", directory,
                GeneratedSources.settings("user-account"), List.of(new TestEmitter()));

        Class<?> counter = Class.forName(GeneratedSources.PACKAGE + ".counting.UserV1Count", true, g.compile());
        assertThat(counter.getMethod("count").invoke(null)).isEqualTo(2);
        assertThat(g.file(GeneratedSources.PACKAGE + ".counting", "ProblemDetailsV1Count")).doesNotExist();
        assertThat(g.report.degraded()).extracting(GenerationReport.Degraded::emitter).containsOnly("counting");
        var unsupported = counter.getMethod("unsupported");
        assertThatThrownBy(() -> {
            try {
                unsupported.invoke(null);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            }
        }).isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("UserV1Count.unsupported()");
    }

    @Test
    void anEmitterMayNotWriteAClassWithAnInvalidName() {
        var bad = new TestEmitter() {
            @Override
            public void emit(com.arc_e_tect.gradle.apionly.transcriberj.spi.EmitterContext context) {
                context.writeJava("a.b", "Not.Valid", "");
            }
        };
        assertThatThrownBy(() -> GeneratedSources.generate(
                GeneratedSources.FIXTURES.resolve("scaffold/openapi.yaml"), "0.1.0", directory, List.of(bad)))
                .isInstanceOf(GenerationException.class)
                .hasMessageContaining("Emitter counting wrote a class with an invalid name: a.b.Not.Valid");
    }

    @Test
    void theClassNamesAnswerEveryLookup() {
        var model = com.arc_e_tect.gradle.apionly.transcriberj.model.ContractParser.parse(contract("""
                /a:
                  get:
                    operationId: getA
                    responses:
                      '200':
                        content:
                          application/json:
                            schema: {type: object}
                """, "schemas:\n" + schema("S", "openapi/S.yaml", "type: string").indent(2)), "names.yaml");
        CoreClassNames names = new CoreClassNames(model, new Shapes(model), new GenerationReport());

        assertThat(names.component(Origin.SCHEMA, "S")).get().extracting(GeneratedClass::bodyShaped).isEqualTo(false);
        assertThat(names.component(Origin.RESPONSE, "S")).isEmpty();
        String location = "/paths/~1a/get/responses/200/content/application~1json/schema";
        assertThat(names.inline(location)).get().extracting(GeneratedClass::simpleName).isEqualTo("GetAResponse200");
        assertThat(names.inline("/nowhere")).isEmpty();
        assertThat(names.schemaLocation(names.inline(location).orElseThrow())).isEqualTo(location);
        assertThat(names.schemaLocation(new GeneratedClass("X", Origin.SCHEMA, "missing", false, null, null, false)))
                .isNull();
        assertThat(new CoreEmitter(new Shapes(model), names, "x", new GenerationReport()).dependencies()).isEmpty();
        assertThat(new CoreEmitter(new Shapes(model), names, "x", new GenerationReport()).represents())
                .doesNotContain(Construct.MULTIPLE_TYPES).contains(Construct.RECURSIVE_REF);
        assertThat(Map.of()).isEmpty();
    }
}
