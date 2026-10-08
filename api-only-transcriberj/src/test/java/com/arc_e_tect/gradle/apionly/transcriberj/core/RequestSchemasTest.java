package com.arc_e_tect.gradle.apionly.transcriberj.core;

import com.arc_e_tect.gradle.apionly.transcriberj.model.Construct;
import com.arc_e_tect.gradle.apionly.transcriberj.model.MediaType;
import com.arc_e_tect.gradle.apionly.transcriberj.model.Operation;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.Emitter;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.ContractCase;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.EmitterContext;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.ManagedDependency;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.Settings;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import com.networknt.schema.regex.JoniRegularExpressionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T19.10: the request schemas an emitter is given are valid JSON Schema 2020-12 and
 * self-contained; every valid body the core generates validates against its schema, and every
 * body-located invalid-request case's body fails; with strictness off an unknown member is
 * allowed, with it on it is not, and an {@code allOf} is judged as a whole.
 */
@DisplayName("T19.10 The request body schema")
class RequestSchemasTest {

    @TempDir
    static Path directory;

    /** One contract, generated with the emitter that records the schemas it is given. */
    record Generated(String name, JsonNode report, List<String> notes, Map<String, String> bodies,
                     Map<String, String> parameters) {

        /** Whether the report notes that no value is generated for a format: its values cannot be vouched for. */
        boolean unvouched(Error e) {
            return e.getKeyword().equals("format") && notes.stream().anyMatch(n ->
                    n.startsWith("No value is generated for format ") && e.getMessage().contains(
                            n.substring("No value is generated for format ".length()).split(" ")[0]));
        }
    }

    static final List<Generated> GENERATED = new ArrayList<>();

    /** Records every body schema, by {@code location mediaType}, and every parameter schema, by {@code location in name}. */
    static class Recording implements Emitter {

        final Map<String, String> bodies = new LinkedHashMap<>();
        final Map<String, String> parameters = new LinkedHashMap<>();

        @Override
        public String id() {
            return "recording";
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
                if (operation.requestBody() != null && operation.requestBody().content() != null) {
                    for (MediaType media : operation.requestBody().content()) {
                        context.requestBodySchema(location, media.contentType())
                                .ifPresent(s -> bodies.put(location + " " + media.contentType(), s));
                    }
                }
                List<ContractCase> cases = context.contractCases(location);
                if (cases.isEmpty()) continue;
                List<String[]> declared = new ArrayList<>();
                java.util.regex.Matcher placeholders = java.util.regex.Pattern.compile("\\{([^}]+)}")
                        .matcher(cases.get(0).request().pathTemplate());
                while (placeholders.find()) declared.add(new String[]{"path", placeholders.group(1)});
                cases.get(0).declaredQuery().forEach(n -> declared.add(new String[]{"query", n}));
                cases.get(0).declaredHeaders().forEach(n -> declared.add(new String[]{"header", n}));
                for (String[] p : declared) {
                    context.parameterSchema(location, p[0], p[1])
                            .ifPresent(s -> parameters.put(location + " " + p[0] + " " + p[1], s));
                }
            }
        }
    }

    @BeforeAll
    static void generate() {
        for (String name : ValidValueFixtures.CORPUS) {
            generate("valid-values-" + name, ValidValueFixtures.CORPUS_DIRECTORY.resolve(name + ".yaml"),
                    GeneratedSources.settings(name));
        }
        for (InvalidRequestFixtures.Variant v : InvalidRequestFixtures.CORPUS) {
            generate(v.name(), InvalidRequestFixtures.CORPUS_DIRECTORY.resolve(v.contract() + ".yaml"), v.settings());
        }
        for (InvalidRequestFixtures.Variant v : ContractCaseFixtures.CORPUS) {
            generate("contract-" + v.name(), ContractCaseFixtures.CORPUS_DIRECTORY.resolve(v.contract() + ".yaml"),
                    v.settings());
        }
        for (String name : ValidValueFixtures.REFERENCE) {
            generate("reference-" + name, GeneratedSources.CONTRACTS.resolve(name).resolve("openapi.yaml"),
                    GeneratedSources.settings(name));
        }
    }

    private static void generate(String name, Path document, Settings settings) {
        Recording recording = new Recording();
        GeneratedSources sources = GeneratedSources.generate(document, GeneratedSources.version(document), directory.resolve(name), settings,
                List.of(recording));
        JsonNode report = Oracle.JSON.readTree(sources.report.renderValidValues(settings.contract(), GeneratedSources.version(document)));
        GENERATED.add(new Generated(name, report, sources.report.notes(), recording.bodies, recording.parameters));
    }

    /**
     * The one schema the test's validator cannot compile: the unsatisfiable corpus's back-reference
     * pattern, valid ECMA-262 that joni's ECMAScript syntax rejects. The generator reports that
     * pattern as unsatisfiable, so no case depends on it.
     */
    static final String UNCOMPILABLE = "valid-values-unsatisfiable /paths/~1unsatisfiable/post application/json";

    private static final SchemaRegistry META = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

    /** A validator over one exposed schema, asserting formats, with ECMA-262 patterns. */
    static Schema schema(String text) {
        SchemaRegistryConfig config = new SchemaRegistryConfig.Builder()
                .formatAssertionsEnabled(true)
                .regularExpressionFactory(JoniRegularExpressionFactory.getInstance())
                .build();
        return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12, b -> b.schemaRegistryConfig(config))
                .getSchema(text);
    }

    private static List<String> refs(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node.isObject()) {
            node.properties().forEach(e -> {
                if (e.getKey().equals("$ref") && e.getValue().isString()) out.add(e.getValue().stringValue());
                out.addAll(refs(e.getValue()));
            });
        } else if (node.isArray()) {
            node.forEach(n -> out.addAll(refs(n)));
        }
        return out;
    }

    @Test
    void theFixturesExposeSchemas() {
        assertThat(GENERATED.stream().mapToInt(g -> g.bodies().size()).sum()).isGreaterThan(20);
        assertThat(GENERATED.stream().mapToInt(g -> g.parameters().size()).sum()).isGreaterThan(20);
    }

    @TestFactory
    Stream<DynamicTest> everySchemaIsValidAndSelfContained() {
        Schema meta = META.getSchema(SchemaLocation.of(SpecificationVersion.DRAFT_2020_12.getDialectId()));
        List<DynamicTest> tests = new ArrayList<>();
        for (Generated g : GENERATED) {
            Map<String, String> all = new LinkedHashMap<>(g.bodies());
            all.putAll(g.parameters());
            all.forEach((key, text) -> tests.add(DynamicTest.dynamicTest(g.name() + " " + key, () -> {
                JsonNode node = Oracle.JSON.readTree(text);
                assertThat(node.get("$schema").stringValue()).isEqualTo("https://json-schema.org/draft/2020-12/schema");
                assertThat(meta.validate(node)).isEmpty();
                List<String> targets = refs(node);
                node.findValues("mapping").forEach(m -> m.forEach(t -> targets.add(t.stringValue())));
                for (String ref : targets) {
                    assertThat(ref).startsWith("#/$defs/");
                    assertThat(node.at(ref.substring(1)).isMissingNode()).as(ref).isFalse();
                }
                assertThat(text).doesNotContain("#/components/");
            })));
        }
        return tests.stream();
    }

    @TestFactory
    Stream<DynamicTest> everyGeneratedValidBodyValidates() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Generated g : GENERATED) {
            for (JsonNode entry : g.report().get("requests")) {
                for (String kind : List.of("requiredRequest", "fullRequest")) {
                    JsonNode request = entry.path(kind).path("value");
                    if (request.isMissingNode() || request.get("contentType").isNull()) continue;
                    String key = entry.get("location").stringValue() + " " + request.get("contentType").stringValue();
                    if (!g.bodies().containsKey(key) || (g.name() + " " + key).equals(UNCOMPILABLE)) continue;
                    tests.add(DynamicTest.dynamicTest(g.name() + " " + key + " " + kind, () ->
                            assertThat(schema(g.bodies().get(key)).validate(request.get("body")))
                                    .filteredOn(e -> !g.unvouched(e)).isEmpty()));
                }
            }
        }
        assertThat(tests).hasSizeGreaterThan(20);
        return tests.stream();
    }

    @TestFactory
    Stream<DynamicTest> everyBodyLocatedInvalidRequestFails() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Generated g : GENERATED) {
            for (JsonNode c : ContractCaseFixtures.cases(g.report(), "INVALID_REQUEST")) {
                if (!c.get("in").stringValue().equals("body") || c.get("request").get("contentType").isNull()) continue;
                String key = c.get("location").stringValue() + " " + c.get("request").get("contentType").stringValue();
                tests.add(DynamicTest.dynamicTest(g.name() + " " + c.get("class").stringValue() + " "
                        + c.get("id").stringValue(), () -> {
                    assertThat(g.bodies()).containsKey(key);
                    List<Error> errors = schema(g.bodies().get(key)).validate(c.get("request").get("body"));
                    assertThat(errors).as("the errors of %s", c.get("request").get("body")).isNotEmpty();
                }));
            }
        }
        assertThat(tests).hasSizeGreaterThan(100);
        return tests.stream();
    }

    @Test
    void theOneSchemaLeftOutIsLeftOutForTheValidatorsSake() {
        Generated g = generated("valid-values-unsatisfiable");
        String key = UNCOMPILABLE.substring(g.name().length() + 1);
        assertThat(g.bodies().get(key)).contains("\"pattern\":\"^(a)\\\\1$\"");
        assertThatThrownBy(() -> schema(g.bodies().get(key))).hasStackTraceContaining("Invalid escape");
    }

    private static Generated generated(String name) {
        return GENERATED.stream().filter(g -> g.name().equals(name)).findFirst().orElseThrow();
    }

    private static ObjectNode requiredBody(Generated g, String location) {
        for (JsonNode entry : g.report().get("requests")) {
            if (entry.get("location").stringValue().equals(location)) {
                return (ObjectNode) entry.get("requiredRequest").get("value").get("body").deepCopy();
            }
        }
        throw new AssertionError(location);
    }

    @Test
    void withStrictnessOffAnUnknownMemberIsAllowedAndWithItOnItIsNot() {
        String key = "/paths/~1strict/post application/json";
        for (String member : List.of("", "/closed", "/merged")) {
            ObjectNode off = requiredBody(generated("strictness-off"), "/paths/~1strict/post");
            ((ObjectNode) off.at(member)).put("unknown", "x");
            assertThat(schema(generated("strictness-off").bodies().get(key)).validate(off)).as(member).isEmpty();
            assertThat(schema(generated("strictness").bodies().get(key)).validate(off)).as(member).isNotEmpty();
        }
    }

    @Test
    void anAllOfIsJudgedAsAWhole() {
        String key = "/paths/~1strict/post application/json";
        Schema strict = schema(generated("strictness").bodies().get(key));
        ObjectNode body = requiredBody(generated("strictness"), "/paths/~1strict/post");
        assertThat(body.get("merged").has("first") && body.get("merged").has("second")).isTrue();
        assertThat(strict.validate(body)).isEmpty();
        ((ObjectNode) body.get("merged")).remove("second");
        assertThat(strict.validate(body)).isNotEmpty();
    }
}
