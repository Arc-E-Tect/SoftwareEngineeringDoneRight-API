package com.arc_e_tect.gradle.apionly.transcriberj.core;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T12.5: every object in a required body has exactly its required members, plus those
 * {@code minProperties} forces; every array exactly {@code minItems} items. Checked
 * against the contract document itself, walking each body with the schemas that apply
 * at each level.
 */
@DisplayName("T12.5 Minimality")
class MinimalityTest {

    @TempDir
    static Path directory;

    static List<ValidValueFixtures.Fixture> corpus = new ArrayList<>();

    @BeforeAll
    static void generate() {
        for (String name : ValidValueFixtures.CORPUS) {
            corpus.add(ValidValueFixtures.corpus(name, directory.resolve(name)));
        }
    }

    @TestFactory
    Stream<DynamicTest> everyRequiredBodyIsMinimal() {
        List<DynamicTest> tests = new ArrayList<>();
        for (ValidValueFixtures.Fixture f : corpus) {
            for (JsonNode entry : f.bodies()) {
                JsonNode value = entry.get("requiredBody").get("value");
                if (value == null) continue;
                String location = entry.get("location").stringValue();
                tests.add(DynamicTest.dynamicTest(f.name() + " " + entry.get("class").stringValue(),
                        () -> minimal(f.oracle(), value, List.of(location), location)));
            }
        }
        assertThat(tests).hasSizeGreaterThan(35);
        return tests.stream();
    }

    /** Checks a value, and each value inside it, against every schema at the pointers given. */
    private static void minimal(Oracle oracle, JsonNode value, List<String> pointers, String path) {
        List<String> parts = new ArrayList<>();
        for (String pointer : pointers) expand(oracle, pointer, parts, value);
        if (value.isObject()) {
            Set<String> required = new LinkedHashSet<>();
            int minProperties = 0;
            for (String part : parts) {
                JsonNode s = oracle.document.at(part);
                if (s.has("required")) s.get("required").values().forEach(r -> required.add(r.stringValue()));
                if (s.has("minProperties")) minProperties = Math.max(minProperties, s.get("minProperties").intValue());
            }
            assertThat(value.propertyNames()).as("%s: members", path).containsAll(required);
            assertThat(value.size()).as("%s: exactly the required members, and those minProperties forces", path)
                    .isEqualTo(Math.max(required.size(), minProperties));
            for (Map.Entry<String, JsonNode> member : value.properties()) {
                List<String> inner = new ArrayList<>();
                for (String part : parts) {
                    JsonNode s = oracle.document.at(part);
                    boolean matched = false;
                    if (s.has("properties") && s.get("properties").has(member.getKey())) {
                        inner.add(part + "/properties/" + Shapes.escape(member.getKey()));
                        matched = true;
                    }
                    if (s.has("patternProperties")) {
                        for (String regex : s.get("patternProperties").propertyNames()) {
                            if (Pattern.compile(regex).matcher(member.getKey()).find()) {
                                inner.add(part + "/patternProperties/" + Shapes.escape(regex));
                                matched = true;
                            }
                        }
                    }
                    if (!matched && s.has("additionalProperties") && s.get("additionalProperties").isObject()) {
                        inner.add(part + "/additionalProperties");
                    }
                }
                minimal(oracle, member.getValue(), inner, path + "." + member.getKey());
            }
        } else if (value.isArray()) {
            int minItems = 0;
            List<String> items = new ArrayList<>();
            for (String part : parts) {
                JsonNode s = oracle.document.at(part);
                if (s.has("minItems")) minItems = Math.max(minItems, s.get("minItems").intValue());
                if (s.has("items")) items.add(part + "/items");
            }
            assertThat(value.size()).as("%s: exactly minItems items", path).isEqualTo(minItems);
            for (JsonNode item : value.values()) minimal(oracle, item, items, path + "[]");
        }
    }

    /** A schema's parts: itself, what it refers to, its allOf branches, and the choice branch the value takes. */
    private static void expand(Oracle oracle, String pointer, List<String> parts, JsonNode value) {
        if (parts.contains(pointer)) return;
        parts.add(pointer);
        JsonNode s = oracle.document.at(pointer);
        if (s.has("$ref")) expand(oracle, s.get("$ref").stringValue().substring(1), parts, value);
        if (s.has("allOf")) {
            for (int i = 0; i < s.get("allOf").size(); i++) expand(oracle, pointer + "/allOf/" + i, parts, value);
        }
        for (String choice : List.of("oneOf", "anyOf")) {
            if (!s.has(choice)) continue;
            for (int i = 0; i < s.get(choice).size(); i++) {
                String branch = pointer + "/" + choice + "/" + i;
                if (oracle.valid(branch, value)) {
                    expand(oracle, branch, parts, value);
                    break;
                }
            }
        }
    }
}
