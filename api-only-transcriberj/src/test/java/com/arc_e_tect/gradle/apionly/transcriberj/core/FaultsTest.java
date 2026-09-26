package com.arc_e_tect.gradle.apionly.transcriberj.core;

import com.arc_e_tect.gradle.apionly.transcriberj.model.ContractModel;
import com.arc_e_tect.gradle.apionly.transcriberj.model.ContractParser;
import com.arc_e_tect.gradle.apionly.transcriberj.model.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The keyword-level evaluator every case is kept or dropped by, on schemas written to reach each rule. */
class FaultsTest {

    @TempDir
    Path directory;

    private Shapes shapes;
    private ContractModel model;

    private Schema schema(String name, String components) throws Exception {
        Path contract = directory.resolve("openapi.yaml");
        Files.writeString(contract, "openapi: 3.1.0\ninfo: {title: t, version: 1.0.0}\npaths: {}\ncomponents:\n  schemas:\n"
                + components);
        model = ContractParser.parse(contract, null);
        shapes = new Shapes(model);
        return shapes.component(name).orElseThrow();
    }

    private List<Faults.Fault> faults(Schema schema, String name, Object value, boolean strict) {
        return new Faults(shapes, strict).of(value, List.of(new Faults.At(schema, "/components/schemas/" + name)));
    }

    private static Map<String, Object> object(Object... pairs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) out.put((String) pairs[i], pairs[i + 1]);
        return out;
    }

    @Test
    void aBooleanSchemaFalseFailsEverythingAndTrueNothing() throws Exception {
        Schema s = schema("A", """
                    A:
                      x-fragment-path: A.yaml
                      type: object
                      properties:
                        never: false
                        always: true
                """);
        assertThat(faults(s, "A", object("never", "x", "always", "y"), false))
                .containsExactly(new Faults.Fault("false", "/never", "/components/schemas/A/properties/never"));
    }

    @Test
    void aKeywordTheModelDoesNotTypeCannotBeEvaluated() throws Exception {
        Schema s = schema("A", """
                    A:
                      x-fragment-path: A.yaml
                      type: string
                      not: {const: x}
                """);
        assertThatThrownBy(() -> faults(s, "A", "y", false)).isInstanceOf(Faults.Unevaluable.class)
                .hasMessage("not at /components/schemas/A");
    }

    @Test
    void aPatternThatCannotBeReadCannotBeEvaluated() throws Exception {
        Schema s = schema("A", """
                    A:
                      x-fragment-path: A.yaml
                      type: string
                      pattern: '(a)\\1'
                """);
        assertThatThrownBy(() -> faults(s, "A", "aa", false)).isInstanceOf(Faults.Unevaluable.class);
    }

    @Test
    void choicesCountTheirMatchingBranches() throws Exception {
        Schema s = schema("A", """
                    A:
                      x-fragment-path: A.yaml
                      type: object
                      properties:
                        one:
                          oneOf: [{type: string}, {minLength: 1}]
                        any:
                          anyOf: [{type: integer}, {type: boolean}]
                """);
        List<Faults.Fault> found = faults(s, "A", object("one", "ab", "any", "x"), false);
        assertThat(found).extracting(Faults.Fault::keyword).containsExactly("oneOf", "anyOf");
        assertThat(faults(s, "A", object("one", BigDecimal.ONE, "any", true), false)).isEmpty();
    }

    @Test
    void theBoundsOfOpenApi30AndTheUncheckedFormats() throws Exception {
        Schema s = schema("A", """
                    A:
                      x-fragment-path: A.yaml
                      type: object
                      properties:
                        low: {type: number, minimum: 0, exclusiveMinimum: true}
                        high: {type: number, maximum: 10, exclusiveMaximum: true}
                        top: {type: number, exclusiveMaximum: 10}
                        period: {type: string, format: duration}
                        mail: {type: string, format: email}
                """);
        List<Faults.Fault> found = faults(s, "A", object("low", BigDecimal.ZERO, "high", BigDecimal.TEN,
                "top", BigDecimal.TEN, "period", "P1D", "mail", "nope"), false);
        assertThat(found).extracting(Faults.Fault::keyword, Faults.Fault::checked).containsExactly(
                org.assertj.core.groups.Tuple.tuple("exclusiveMinimum", true),
                org.assertj.core.groups.Tuple.tuple("exclusiveMaximum", true),
                org.assertj.core.groups.Tuple.tuple("exclusiveMaximum", true),
                org.assertj.core.groups.Tuple.tuple("format", false),
                org.assertj.core.groups.Tuple.tuple("format", true));
    }

    @Test
    void strictnessAndAnExplicitFalseEachNameTheMember() throws Exception {
        Schema s = schema("A", """
                    A:
                      x-fragment-path: A.yaml
                      type: object
                      properties:
                        closed:
                          type: object
                          additionalProperties: false
                          patternProperties:
                            '^x-': {type: string}
                          properties:
                            a: {type: string}
                        open:
                          type: object
                          additionalProperties: {type: integer}
                        items:
                          type: array
                          items: {type: object, properties: {a: {type: string}}}
                """);
        Map<String, Object> value = object("closed", object("a", "1", "x-y", "z", "b", "2"),
                "open", object("n", "not-a-number"), "items", List.of(object("a", "1", "extra", "2")), "stray", "s");
        assertThat(faults(s, "A", value, true)).containsExactly(
                new Faults.Fault("additionalProperties", "/stray", "/components/schemas/A"),
                new Faults.Fault("additionalProperties", "/closed/b", "/components/schemas/A/properties/closed"),
                new Faults.Fault("type", "/open/n", "/components/schemas/A/properties/open/additionalProperties"),
                new Faults.Fault("additionalProperties", "/items/0/extra", "/components/schemas/A/properties/items/items"));
        assertThat(faults(s, "A", value, false)).extracting(Faults.Fault::pointer).containsExactly("/closed/b", "/open/n");
    }

    @Test
    void aRecursiveValueDeeperThanItCanFollowCannotBeEvaluated() throws Exception {
        Schema s = schema("A", """
                    A:
                      x-fragment-path: A.yaml
                      type: object
                      properties:
                        next: {$ref: '#/components/schemas/A'}
                """);
        Object value = object();
        for (int i = 0; i < 80; i++) value = object("next", value);
        Object deep = value;
        assertThatThrownBy(() -> faults(s, "A", deep, false)).isInstanceOf(Faults.Unevaluable.class);
    }
}
