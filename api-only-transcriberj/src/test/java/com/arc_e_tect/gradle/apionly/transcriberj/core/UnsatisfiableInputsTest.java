package com.arc_e_tect.gradle.apionly.transcriberj.core;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T12.3: each condition that leaves no valid value -- or none this generator can find --
 * yields a finding with its location and reason, and a generated method that throws
 * {@link UnsupportedOperationException} carrying them. Generation itself succeeds, and
 * the tree compiles.
 */
@DisplayName("T12.3 Unsatisfiable inputs")
class UnsatisfiableInputsTest {

    @TempDir
    static Path directory;

    static ValidValueFixtures.Fixture unsatisfiable;

    @BeforeAll
    static void generate() {
        unsatisfiable = ValidValueFixtures.corpus("unsatisfiable", directory);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "LengthsCrossV1|/components/schemas/LengthsCrossV1/properties/value|minLength 10 exceeds maxLength 5",
        "PatternTooLongV1|/components/schemas/PatternTooLongV1/properties/value|every match of pattern ^\\d{6}$ is at "
                + "least 6 code points long, and maxLength is 5",
        "EnumFailsPatternV1|/components/schemas/EnumFailsPatternV1/properties/value|none of the enum values "
                + "[\"A\",\"B\"] satisfies the other keywords",
        "RequiredSelfV1|/components/schemas/RequiredSelfV1/properties/next|the reference to component RequiredSelfV1 "
                + "would be followed more than recursionDepth (2) times",
        "TooFewDistinctV1|/components/schemas/TooFewDistinctV1/properties/value|uniqueItems needs 3 distinct items, "
                + "but only 2 distinct value(s) satisfy the item schema",
        "EnumsDisjointV1|/components/schemas/EnumsDisjointV1/allOf/0/properties/value|the enums that apply here have "
                + "no value in common: [\"a\",\"b\"] and [\"c\"]",
        "BoundsCrossV1|/components/schemas/BoundsCrossV1/properties/value|the bounds leave no number: >= 5",
        "AllOfBoundsCrossV1|/components/schemas/AllOfBoundsCrossV1/allOf/0/properties/value|the bounds leave no "
                + "number: >= 10 (/components/schemas/AllOfBoundsCrossV1/allOf/0/properties/value) and <= 2 "
                + "(/components/schemas/AllOfBoundsCrossV1/allOf/1/properties/value)",
        "BackreferenceV1|/components/schemas/BackreferenceV1/properties/value|unsatisfiable by this generator: "
                + "pattern ^(a)\\1$ uses a backreference",
        "LookaheadV1|/components/schemas/LookaheadV1/properties/value|unsatisfiable by this generator: pattern "
                + "^(?=a)a$ uses a lookahead",
        "TypesConflictV1|/components/schemas/TypesConflictV1/allOf/0/properties/value|no value has every type "
                + "required: string at",
        "NoMultipleV1|/components/schemas/NoMultipleV1/properties/value|no multiple of 0.3 lies >= 0.1",
        "ItemsCrossV1|/components/schemas/ItemsCrossV1/properties/value|minItems 3 exceeds maxItems 1",
        "ForbiddenRequiredV1|/components/schemas/ForbiddenRequiredV1/allOf/0|member b is not allowed here: "
                + "additionalProperties is false",
        "NotKeywordV1|/components/schemas/NotKeywordV1/properties/value|validity depends on not, which the model "
                + "does not type",
        "ConstsDifferV1|/components/schemas/ConstsDifferV1/allOf/0/properties/value|the const values \"a\" and \"b\" "
                + "differ",
        "TooManyRequiredV1|/components/schemas/TooManyRequiredV1|the object needs 2 member(s) -- those it requires "
                + "-- but maxProperties is 1",
    })
    void eachHasAFindingAndARequiredBodyThatThrowsIt(String className, String location, String reason) {
        assertThat(unsatisfiable.sources().report.noValidValue())
                .anySatisfy(n -> {
                    assertThat(n.className()).isEqualTo(className);
                    assertThat(n.method()).isEqualTo("requiredBody()");
                    assertThat(n.location()).isEqualTo(location);
                    assertThat(n.reason()).contains(reason);
                });
        assertThatThrownBy(() -> unsatisfiable.sources().call(className, "requiredBody", new Class<?>[0]))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining(className + ".requiredBody() has no valid value from contract unsatisfiable")
                .hasMessageContaining(reason)
                .hasMessageContaining(" at " + location);
        assertThat(unsatisfiable.body(className).get("requiredBody").get("unsatisfiable").get("location")
                .stringValue()).isEqualTo(location);
    }

    @Test
    void aBodyValidOnlyWithoutItsOptionalMembersKeepsItsRequiredBody() throws Throwable {
        assertThat(unsatisfiable.sources().call("FullClashV1", "requiredBody", new Class<?>[0]))
                .isEqualTo("{\"a\":\"a\"}\n");
        assertThatThrownBy(() -> unsatisfiable.sources().call("FullClashV1", "fullBody", new Class<?>[0]))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("the object needs 3 member(s) -- every one it declares -- but maxProperties is 2");
    }

    @Test
    void generationSucceedsAndTheTreeCompiles() {
        assertThat(unsatisfiable.sources().compile()).isNotNull();
        assertThat(unsatisfiable.sources().report.degraded()).isEmpty();
    }
}
