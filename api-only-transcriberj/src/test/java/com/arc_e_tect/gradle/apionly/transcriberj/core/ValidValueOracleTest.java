package com.arc_e_tect.gradle.apionly.transcriberj.core;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T12.1: every generated valid body and valid request, in every fixture contract, has
 * zero errors against the independent oracle.
 */
@DisplayName("T12.1 The independent oracle")
class ValidValueOracleTest {

    private static final List<String> BODY_VARIANTS = List.of("requiredBody", "fullBody");
    private static final List<String> REQUEST_VARIANTS = List.of("requiredRequest", "fullRequest", "noBodyRequest");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^}]+)}");

    @TempDir
    static Path directory;

    static List<ValidValueFixtures.Fixture> fixtures;

    @BeforeAll
    static void generate() {
        fixtures = ValidValueFixtures.all(directory);
    }

    @TestFactory
    Stream<DynamicTest> everyValidBodyValidates() {
        List<DynamicTest> tests = new ArrayList<>();
        for (ValidValueFixtures.Fixture f : fixtures) {
            for (JsonNode entry : f.bodies()) {
                for (String variant : BODY_VARIANTS) {
                    JsonNode value = entry.get(variant).get("value");
                    if (value == null) continue;
                    String location = entry.get("location").stringValue();
                    tests.add(DynamicTest.dynamicTest(f.name() + " " + entry.get("class").stringValue() + "." + variant,
                            () -> assertThat(f.oracleFor(location).errors(location, value))
                                    .as("%s %s.%s at %s: %s", f.name(), entry.get("class").stringValue(), variant,
                                            location, value)
                                    .isEmpty()));
                }
            }
        }
        return tests.stream();
    }

    @TestFactory
    Stream<DynamicTest> everyValidRequestValidates() {
        List<DynamicTest> tests = new ArrayList<>();
        for (ValidValueFixtures.Fixture f : fixtures) {
            for (JsonNode entry : f.requests()) {
                for (String variant : REQUEST_VARIANTS) {
                    JsonNode variantNode = entry.get(variant);
                    if (variantNode == null || variantNode.get("value") == null) continue;
                    String name = f.name() + " " + entry.get("class").stringValue() + "." + variant;
                    tests.add(DynamicTest.dynamicTest(name, () -> assertRequestValid(f,
                            entry.get("location").stringValue(), variantNode.get("value"), name)));
                }
            }
        }
        return tests.stream();
    }

    @Test
    void theOracleRunsOverEveryKindOfValueRatherThanNothing() {
        long bodies = fixtures.stream().flatMap(f -> f.bodies().stream())
                .filter(e -> e.get("requiredBody").has("value")).count();
        long requests = fixtures.stream().flatMap(f -> f.requests().stream())
                .filter(e -> e.get("requiredRequest").has("value")).count();
        assertThat(bodies).isGreaterThan(60);
        assertThat(fixtures.stream().filter(f -> f.async() != null)).isNotEmpty();
        assertThat(requests).isGreaterThan(10);
    }

    @Test
    void theOracleRejectsAnInvalidValueSoThatItsSilenceMeansSomething() {
        ValidValueFixtures.Fixture keywords = fixtures.get(0);
        JsonNodeFactory nodes = JsonNodeFactory.instance;
        String at = "/components/schemas/LengthPatternV1";
        assertThat(keywords.oracle().errors(at, nodes.objectNode().put("value", "aaa"))).isNotEmpty();
        assertThat(keywords.oracle().errors(at, nodes.objectNode().put("value", "Aaa"))).isEmpty();
        String formats = "/components/schemas/PatternFormatV1";
        // The pattern wins over the format: a value that is no email is still valid here.
        assertThat(keywords.oracle().errors(formats, nodes.objectNode().put("value", "a"))).isEmpty();
        String email = "/components/schemas/FormatsV1/properties/email";
        assertThat(keywords.oracle().errors(email, nodes.stringNode("not an email"))).isNotEmpty();
    }

    /** Each parameter, converted as a server would, against its schema; the body against the request body's. */
    static void assertRequestValid(ValidValueFixtures.Fixture f, String operation, JsonNode request, String name) {
        Oracle oracle = f.oracle();
        List<String> placeholders = new ArrayList<>();
        Matcher m = PLACEHOLDER.matcher(request.get("pathTemplate").stringValue());
        while (m.find()) placeholders.add(m.group(1));
        assertThat(ValidValueFixtures.list(request.get("pathParameters"))).as(name).hasSize(placeholders.size());
        for (int i = 0; i < placeholders.size(); i++) {
            parameter(oracle, operation, "path", placeholders.get(i),
                    request.get("pathParameters").get(i).stringValue(), name);
        }
        for (JsonNode q : request.get("query")) {
            parameter(oracle, operation, "query", q.get("name").stringValue(), q.get("value").stringValue(), name);
        }
        for (JsonNode h : request.get("headers")) {
            parameter(oracle, operation, "header", h.get("name").stringValue(), h.get("value").stringValue(), name);
        }
        JsonNode contentType = request.get("contentType");
        if (contentType != null && !contentType.isNull()) {
            String body = requestBodyPointer(oracle, operation) + "/content/" + Shapes.escape(contentType.stringValue())
                    + "/schema";
            assertThat(oracle.errors(body, request.get("body"))).as("%s body at %s: %s", name, body,
                    request.get("body")).isEmpty();
        } else {
            assertThat(request.get("body").isNull()).as(name).isTrue();
        }
    }

    static String requestBodyPointer(Oracle oracle, String operation) {
        JsonNode body = oracle.document.at(operation + "/requestBody");
        if (body.has("$ref")) return body.get("$ref").stringValue().substring(1);
        return operation + "/requestBody";
    }

    private static void parameter(Oracle oracle, String operation, String in, String name, String value, String test) {
        String pointer = parameterPointer(oracle, operation, in, name);
        assertThat(pointer).as("%s: parameter %s in %s is declared", test, name, in).isNotNull();
        JsonNode schema = oracle.resolve(pointer + "/schema");
        JsonNode instance = serverSide(schema, value);
        assertThat(oracle.errors(pointer + "/schema", instance))
                .as("%s: %s parameter %s at %s = \"%s\"", test, in, name, pointer, value).isEmpty();
    }

    /** The pointer of the parameter an operation has of a name and location: its own, or else its path item's. */
    static String parameterPointer(Oracle oracle, String operation, String in, String name) {
        String item = operation.substring(0, operation.lastIndexOf('/'));
        for (String owner : List.of(operation, item)) {
            JsonNode parameters = oracle.document.at(owner + "/parameters");
            for (int i = 0; i < parameters.size(); i++) {
                String pointer = owner + "/parameters/" + i;
                JsonNode p = parameters.get(i);
                if (p.has("$ref")) {
                    pointer = p.get("$ref").stringValue().substring(1);
                    p = oracle.document.at(pointer);
                }
                if (name.equals(p.get("name").stringValue()) && in.equals(p.get("in").stringValue())) return pointer;
            }
        }
        return null;
    }

    /** A parameter's wire value as a server reads it: a number, a boolean, or the string it is. */
    static JsonNode serverSide(JsonNode schema, String value) {
        JsonNodeFactory nodes = JsonNodeFactory.instance;
        String type = schema.has("type") ? schema.get("type").stringValue() : "string";
        return switch (type) {
            case "integer", "number" -> nodes.numberNode(new BigDecimal(value));
            case "boolean" -> {
                assertThat(value).isIn("true", "false");
                yield nodes.booleanNode(Boolean.parseBoolean(value));
            }
            default -> nodes.stringNode(value);
        };
    }
}
