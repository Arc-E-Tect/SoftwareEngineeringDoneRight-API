package com.arc_e_tect.gradle.apionly.transcriberj.core;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T12.2: the keyword corpus, {@code fixtures/valid-values/corpus/keywords.yaml} and
 * {@code exclusive-3.0.yaml}. Each row of the rule table, in isolation and in the
 * combinations that matter, gives the value its rule says. That every one of them is
 * valid is T12.1's to show; this pins which valid value the rules choose.
 */
@DisplayName("T12.2 A keyword corpus")
class KeywordCorpusTest {

    @TempDir
    static Path directory;

    static ValidValueFixtures.Fixture keywords;
    static ValidValueFixtures.Fixture exclusive30;

    @BeforeAll
    static void generate() {
        keywords = ValidValueFixtures.corpus("keywords", directory.resolve("keywords"));
        exclusive30 = ValidValueFixtures.corpus("exclusive-3.0", directory.resolve("exclusive"));
    }

    private static JsonNode json(String text) {
        return Oracle.JSON.readTree(text);
    }

    private static JsonNode required(ValidValueFixtures.Fixture f, String className) {
        return f.body(className).get("requiredBody").get("value");
    }

    private static JsonNode full(ValidValueFixtures.Fixture f, String className) {
        return f.body(className).get("fullBody").get("value");
    }

    @ParameterizedTest(name = "{0}: {1}")
    @CsvSource(delimiter = '|', value = {
        // const, enum, and enum with a pattern
        "ConstV1|{\"value\":\"fixed\"}",
        "EnumV1|{\"value\":\"b\"}",
        "EnumPatternV1|{\"value\":\"ab\"}",
        // strings: the filler, minLength 0, maxLength 0, lengths with a pattern
        "StringPlainV1|{\"value\":\"a\"}",
        "MinLengthZeroV1|{\"value\":\"a\"}",
        "MaxLengthZeroV1|{\"value\":\"\"}",
        "LengthPatternV1|{\"value\":\"Aaa\"}",
        // anchored, unanchored, and a class, a quantifier and an alternation
        "AnchoredPatternV1|{\"value\":\"000\"}",
        "UnanchoredPatternV1|{\"value\":\"00aa\"}",
        "ClassQuantifierAlternationV1|{\"value\":\"ab-0\"}",
        // pattern + format: the pattern wins
        "PatternFormatV1|{\"value\":\"a\"}",
        // integers: 0, the smallest multiple at or above the lower bound, the largest at or
        // below the upper, and the 3.1 numeric exclusive bounds
        "IntegerV1|{\"value\":0}",
        "IntegerBoundsV1|{\"value\":6}",
        "IntegerUpperV1|{\"value\":-3}",
        "IntegerExclusiveV1|{\"value\":6}",
        // numbers, in exact decimal arithmetic
        "NumberV1|{\"value\":0}",
        "NumberBoundsV1|{\"value\":1.5}",
        "TenthsV1|{\"value\":0.3}",
        "HundredthsV1|{\"value\":1.01}",
        "NumberExclusiveV1|{\"value\":0.625}",
        "BooleanV1|{\"value\":false}",
        // arrays: exactly minItems, distinct when uniqueItems says so
        "UniqueItemsV1|{\"letters\":[\"x\",\"y\"],\"numbers\":[0,1,2],\"plain\":[]}",
        // objects: minProperties filled with a declared optional member, then additionalProperties
        "MinPropertiesV1|{\"a\":\"a\",\"b\":\"a\",\"additional1\":7}",
        "AdditionalOnlyV1|{\"additional1\":\"ok\",\"additional2\":\"ok\"}",
        "PatternPropertiesV1|{\"x-\":false}",
        // allOf: every branch applies
        "AllOfCompatibleV1|{\"value\":\"ab\"}",
        "AllOfConflictingV1|{\"value\":\"bbbbb\",\"count\":4}",
        // oneOf with overlapping branches, and a discriminator with a mapping
        "OverlapV1|{\"value\":1}",
        "DiscriminatedV1|{\"pet\":{\"petType\":\"feline\"}}",
        // a recursive $ref, and a member nothing constrains
        "RecursiveV1|{\"name\":\"a\"}",
        "UntypedV1|{\"value\":null}",
    })
    void eachRuleGivesTheValueItSays(String className, String expected) {
        assertThat(required(keywords, className)).isEqualTo(json(expected));
    }

    @Test
    void theFormatsHaveTheirCanonicalValuesAdjustedToLength() {
        assertThat(required(keywords, "FormatsV1")).isEqualTo(json("""
                {"email":"user@example.com","uuid":"00000000-0000-4000-8000-000000000000","date":"2000-01-01",
                 "dateTime":"2000-01-01T00:00:00Z","time":"00:00:00Z","uri":"https://example.com/",
                 "uriReference":"https://example.com/","hostname":"example.com","ipv4":"192.0.2.1",
                 "ipv6":"2001:db8::1","longEmail":"useraaaaaaaa@example.com","shortUri":"https://example.com",
                 "longHostname":"%s.aaaaa.example.com","custom":"a"}""".formatted("a".repeat(62))));
    }

    @Test
    void anUnsupportedFormatIsAPlainStringAndNoted() {
        assertThat(keywords.sources().report.notes()).containsExactly("No value is generated for format x-custom at "
                + "/components/schemas/FormatsV1/properties/custom; valid values there are plain strings");
    }

    @Test
    void lengthIsCountedInCodePoints() {
        String value = required(keywords, "AstralV1").get("value").stringValue();
        assertThat(value).isEqualTo("😀😀");
        assertThat(value.length()).isEqualTo(4);
        assertThat(value.codePointCount(0, value.length())).isEqualTo(2);
    }

    @Test
    void theOpenApi30BooleanExclusiveBoundsAreHonoured() {
        assertThat(required(exclusive30, "BoundsV1"))
                .isEqualTo(json("{\"between\":6,\"quarter\":0.25,\"below\":-1,\"inclusive\":3}"));
    }

    @Test
    void theFullBodyHasEveryDeclaredMember() {
        assertThat(full(keywords, "DiscriminatedV1")).isEqualTo(json("{\"pet\":{\"petType\":\"feline\",\"whiskers\":1}}"));
        assertThat(full(keywords, "UniqueItemsV1"))
                .isEqualTo(json("{\"letters\":[\"x\",\"y\"],\"numbers\":[0,1,2],\"plain\":[\"a\"]}"));
        assertThat(full(keywords, "MinPropertiesV1")).isEqualTo(json("{\"a\":\"a\",\"b\":\"a\",\"additional1\":7}"));
    }

    @Test
    void aRecursiveReferenceIsFollowedRecursionDepthTimesInTheFullBody() {
        // recursionDepth is 2 here, as fields(...) follows it: the child, and its child.
        assertThat(full(keywords, "RecursiveV1"))
                .isEqualTo(json("{\"name\":\"a\",\"child\":{\"name\":\"a\",\"child\":{\"name\":\"a\"}}}"));
    }

    @Test
    void anOptionalRequestBodyGivesARequestWithoutOneAsWell() {
        JsonNode overlap = keywords.request("PostOverlapOperation");
        assertThat(overlap.get("requiredRequest").get("value").get("body")).isEqualTo(json("1"));
        assertThat(overlap.get("noBodyRequest").get("value").get("body").isNull()).isTrue();
        assertThat(overlap.get("noBodyRequest").get("value").get("contentType").isNull()).isTrue();
        assertThat(keywords.request("PostCorpusOperation").has("noBodyRequest")).isFalse();
    }
}
