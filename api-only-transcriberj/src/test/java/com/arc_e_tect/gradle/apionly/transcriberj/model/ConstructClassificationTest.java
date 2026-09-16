package com.arc_e_tect.gradle.apionly.transcriberj.model;

import org.junit.jupiter.api.Test;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.schema.CoreSchema;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every construct in the brief's construct mapping is classified, and anything
 * else the model does not type is classified as undecided rather than dropped.
 */
class ConstructClassificationTest {

    private static final String HEADER = """
            openapi: 3.1.0
            info:
              title: Constructs
              version: 1.2.3
            paths: {}
            components:
              schemas:
            """;

    private static ContractModel parse(String schemas) {
        return ContractParser.parse(HEADER + schemas.indent(4), "constructs.yaml");
    }

    @SuppressWarnings("unchecked")
    private static void assertRoundTrips(ContractModel model, String schemas) {
        Map<String, Object> document = (Map<String, Object>) new Load(
                LoadSettings.builder().setSchema(new CoreSchema()).build())
                .loadFromString(HEADER + schemas.indent(4));
        assertThat(ModelWriter.schemas(model))
                .isEqualTo(((Map<String, Object>) document.get("components")).get("schemas"));
    }

    private static Finding only(ContractModel model) {
        assertThat(model.findings()).hasSize(1);
        return model.findings().get(0);
    }

    @Test
    void oneOfWhoseBranchesAreAllComponentsIsRepresented() {
        String schemas = """
                A: {type: object}
                B: {type: object}
                Either:
                  oneOf:
                    - $ref: '#/components/schemas/A'
                    - $ref: '#/components/schemas/B'
                """;
        ContractModel model = parse(schemas);

        assertThat(only(model)).isEqualTo(new Finding("/components/schemas/Either",
                Construct.ONE_OF_REF_BRANCHES, Treatment.REPRESENTED, "oneOf: A, B"));
        assertThat(model.findings().get(0).remedy()).isNull();
        assertRoundTrips(model, schemas);
    }

    @Test
    void anOnymousBranchesAreDegradedWithTheRemedyStated() {
        String schemas = """
                A: {type: object}
                Either:
                  anyOf:
                    - $ref: '#/components/schemas/A'
                    - type: string
                """;
        Finding finding = only(parse(schemas));

        assertThat(finding.construct()).isEqualTo(Construct.ANY_OF_INLINE_BRANCHES);
        assertThat(finding.treatment()).isEqualTo(Treatment.DEGRADED);
        assertThat(finding.detail()).isEqualTo("anyOf: A, <inline #2>");
        assertThat(finding.remedy()).isEqualTo(
                "name the branches in the specification so that each becomes a component");
        assertRoundTrips(parse(schemas), schemas);
    }

    @Test
    void allFourCompositionCasesAreDistinguished() {
        ContractModel model = parse("""
                A: {type: object}
                AnyRef: {anyOf: [{$ref: '#/components/schemas/A'}]}
                OneInline: {oneOf: [{type: string}]}
                """);

        assertThat(model.findings()).extracting(Finding::construct, Finding::treatment).containsExactly(
                org.assertj.core.groups.Tuple.tuple(Construct.ANY_OF_REF_BRANCHES, Treatment.REPRESENTED),
                org.assertj.core.groups.Tuple.tuple(Construct.ONE_OF_INLINE_BRANCHES, Treatment.DEGRADED));
    }

    @Test
    void aDiscriminatorIsRepresentedAndKeptWhole() {
        String schemas = """
                Cat: {type: object}
                Pet:
                  oneOf:
                    - $ref: '#/components/schemas/Cat'
                  discriminator:
                    propertyName: kind
                    mapping:
                      cat: '#/components/schemas/Cat'
                    x-note: kept
                """;
        ContractModel model = parse(schemas);

        assertThat(model.findings()).extracting(Finding::construct)
                .containsExactly(Construct.ONE_OF_REF_BRANCHES, Construct.DISCRIMINATOR);
        Discriminator discriminator = model.component("Pet").orElseThrow().schema().discriminator();
        assertThat(discriminator.propertyName()).isEqualTo("kind");
        assertThat(discriminator.mapping()).containsEntry("cat", "#/components/schemas/Cat");
        assertThat(discriminator.other()).containsEntry("x-note", "kept");
        assertRoundTrips(model, schemas);
    }

    @Test
    void additionalAndPatternPropertiesAreRepresentedWhateverTheirForm() {
        String schemas = """
                Closed: {type: object, additionalProperties: false}
                Open: {type: object, additionalProperties: {type: string}}
                Patterned:
                  type: object
                  patternProperties:
                    '^x-': {type: string}
                """;
        ContractModel model = parse(schemas);

        assertThat(model.findings()).containsExactly(
                new Finding("/components/schemas/Closed", Construct.ADDITIONAL_PROPERTIES, Treatment.REPRESENTED,
                        "additionalProperties: false"),
                new Finding("/components/schemas/Open", Construct.ADDITIONAL_PROPERTIES, Treatment.REPRESENTED,
                        "additionalProperties: a schema"),
                new Finding("/components/schemas/Patterned", Construct.PATTERN_PROPERTIES, Treatment.REPRESENTED,
                        "patternProperties: ^x-"));
        assertThat(model.component("Closed").orElseThrow().schema().additionalProperties().allowed()).isFalse();
        assertThat(model.component("Open").orElseThrow().schema().additionalProperties().schema().types())
                .containsExactly("string");
        assertRoundTrips(model, schemas);
    }

    @Test
    void aRecursiveReferenceIsMarkedWhereTheCycleCloses() {
        String schemas = """
                Node:
                  type: object
                  properties:
                    children:
                      type: array
                      items: {$ref: '#/components/schemas/Edge'}
                Edge:
                  type: object
                  properties:
                    target: {$ref: '#/components/schemas/Node'}
                Self:
                  type: object
                  properties:
                    next: {$ref: '#/components/schemas/Self'}
                """;
        ContractModel model = parse(schemas);

        assertThat(model.findings()).containsExactly(
                new Finding("/components/schemas/Edge/properties/target", Construct.RECURSIVE_REF,
                        Treatment.REPRESENTED, "Node -> Edge -> Node"),
                new Finding("/components/schemas/Self/properties/next", Construct.RECURSIVE_REF,
                        Treatment.REPRESENTED, "Self -> Self"));
        assertRoundTrips(model, schemas);
    }

    @Test
    void everyTypedKeywordIsKeptAndNothingUnknownIsDropped() {
        String schemas = """
                Everything:
                  type: [string, 'null']
                  format: date
                  description: All of it.
                  const: null
                  enum: [a, b, null]
                  minLength: 1
                  maxLength: 2
                  pattern: '^a$'
                  minimum: 0
                  maximum: 9.5
                  exclusiveMinimum: -1
                  exclusiveMaximum: 10
                  multipleOf: 0.5
                  minItems: 0
                  maxItems: 3
                  uniqueItems: true
                  minProperties: 0
                  maxProperties: 4
                  title: Everything
                  default: a
                  examples: [a]
                  example: a
                  readOnly: true
                  writeOnly: false
                  deprecated: false
                  externalDocs: {url: 'https://example.invalid'}
                  xml: {name: e}
                  $comment: a comment
                  contentMediaType: text/plain
                  contentEncoding: base64
                  x-vendor: {anything: [1, 2]}
                  not: {type: integer}
                  if: {type: string}
                  then: {minLength: 1}
                Listed:
                  type: array
                  items: false
                  prefixItems: [{type: string}]
                """;
        ContractModel model = parse(schemas);

        Schema everything = model.component("Everything").orElseThrow().schema();
        assertThat(everything.types()).containsExactly("string", "null");
        assertThat(everything.typeWrittenAsList()).isTrue();
        assertThat(everything.constValue()).isEqualTo(new Const(null));
        assertThat(everything.enumValues()).containsExactly("a", "b", null);
        assertThat(everything.constraints().multipleOf()).isEqualTo(0.5);
        assertThat(everything.annotations()).containsOnlyKeys("title", "default", "examples", "example",
                "readOnly", "writeOnly", "deprecated", "externalDocs", "xml", "$comment", "contentMediaType",
                "contentEncoding", "x-vendor");
        assertThat(everything.unmodelled()).containsOnlyKeys("not", "if", "then");
        assertThat(model.component("Listed").orElseThrow().schema().items().literal()).isFalse();

        assertThat(model.findings()).extracting(Finding::location, Finding::construct, Finding::detail)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("/components/schemas/Everything",
                                Construct.MULTIPLE_TYPES, "type: string, null"),
                        org.assertj.core.groups.Tuple.tuple("/components/schemas/Everything",
                                Construct.UNMODELLED_KEYWORD, "not"),
                        org.assertj.core.groups.Tuple.tuple("/components/schemas/Everything",
                                Construct.UNMODELLED_KEYWORD, "if"),
                        org.assertj.core.groups.Tuple.tuple("/components/schemas/Everything",
                                Construct.UNMODELLED_KEYWORD, "then"),
                        org.assertj.core.groups.Tuple.tuple("/components/schemas/Listed/items",
                                Construct.BOOLEAN_SCHEMA, "false"),
                        org.assertj.core.groups.Tuple.tuple("/components/schemas/Listed",
                                Construct.UNMODELLED_KEYWORD, "prefixItems"));
        assertThat(model.findings()).filteredOn(f -> f.construct() != Construct.MULTIPLE_TYPES)
                .extracting(Finding::treatment).containsOnly(Treatment.UNDECIDED);
        assertRoundTrips(model, schemas);
    }

    @Test
    void aTypeWrittenAsASingleElementListStaysAList() {
        String schemas = "Listed: {type: [string]}\n";
        ContractModel model = parse(schemas);

        assertThat(model.component("Listed").orElseThrow().schema().typeWrittenAsList()).isTrue();
        assertThat(model.findings()).isEmpty();
        assertRoundTrips(model, schemas);
    }

    @Test
    void aComponentWithoutAFragmentPathHasNoneAndStillHasAHash() {
        ContractModel model = parse("Plain: {type: string}\n");
        Provenance provenance = model.component("Plain").orElseThrow().provenance();

        assertThat(provenance.fragmentPath()).isNull();
        assertThat(provenance.sha256()).isEqualTo(CanonicalJson.sha256(Map.of("type", "string")));
    }

    @Test
    void otherComponentTypesAreKeptAndClassified() {
        ContractModel model = ContractParser.parse("""
                openapi: 3.0.3
                info: {title: Other, version: '1'}
                paths: {}
                components:
                  responses:
                    NotFound: {description: Not found.}
                  securitySchemes:
                    bearer: {type: http, scheme: bearer}
                """, "other.yaml");

        assertThat(model.components()).isEmpty();
        assertThat(model.otherComponents()).containsOnlyKeys("responses", "securitySchemes");
        assertThat(model.findings()).containsExactly(
                new Finding("/components/responses/NotFound", Construct.UNMODELLED_COMPONENT_TYPE,
                        Treatment.UNDECIDED, "responses"),
                new Finding("/components/securitySchemes/bearer", Construct.UNMODELLED_COMPONENT_TYPE,
                        Treatment.UNDECIDED, "securitySchemes"));
    }

    @Test
    void referencesOutsideSchemasInOperationsAreKeptAndClassified() {
        String yaml = """
                openapi: 3.1.0
                info: {title: Refs, version: '1'}
                paths:
                  /things/{id}:
                    parameters:
                      - $ref: '#/components/parameters/Id'
                    summary: Things.
                    put:
                      requestBody:
                        $ref: '#/components/requestBodies/Thing'
                      responses:
                        '404':
                          $ref: '#/components/responses/NotFound'
                        default:
                          description: Anything else.
                          headers:
                            X-Trace: {schema: {type: string}}
                          content:
                            text/plain:
                              example: oops
                      security: []
                """;
        ContractModel model = ContractParser.parse(yaml, "refs.yaml");

        assertThat(model.findings()).extracting(Finding::location, Finding::construct).containsExactly(
                org.assertj.core.groups.Tuple.tuple("/paths/~1things~1{id}/parameters/0",
                        Construct.UNMODELLED_REFERENCE),
                org.assertj.core.groups.Tuple.tuple("/paths/~1things~1{id}/put/requestBody",
                        Construct.UNMODELLED_REFERENCE),
                org.assertj.core.groups.Tuple.tuple("/paths/~1things~1{id}/put/responses/404",
                        Construct.UNMODELLED_REFERENCE));
        PathItem item = model.paths().get(0);
        assertThat(item.other()).containsEntry("summary", "Things.");
        Operation put = item.operations().get(0);
        assertThat(put.method()).isEqualTo(HttpMethod.PUT);
        assertThat(put.operationId()).isNull();
        assertThat(put.other()).containsEntry("security", List.of());
        assertThat(put.responses().get(1).other()).containsKey("headers");
        assertThat(put.responses().get(1).content().get(0).schema()).isNull();

        @SuppressWarnings("unchecked")
        Map<String, Object> document = (Map<String, Object>) new Load(
                LoadSettings.builder().setSchema(new CoreSchema()).build()).loadFromString(yaml);
        assertThat(ModelWriter.paths(model)).isEqualTo(document.get("paths"));
    }
}
