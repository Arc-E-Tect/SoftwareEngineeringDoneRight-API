package com.arc_e_tect.gradle.apionly.transcriberj.core;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T12.6: an {@code allOf}'s value satisfies every branch, since generation intersects
 * them; the existing descriptors and {@code body(...)} still take the first definition
 * of each keyword, exactly as before. That the existing members are byte for byte what
 * they were is BaselineSourcesTest's to show, for these classes too.
 */
@DisplayName("T12.6 allOf intersection without regression")
class AllOfIntersectionTest {

    @TempDir
    static Path directory;

    static ValidValueFixtures.Fixture keywords;

    @BeforeAll
    static void generate() {
        keywords = ValidValueFixtures.corpus("keywords", directory);
    }

    private static void satisfiesEveryBranch(String component, int branches) {
        String at = "/components/schemas/" + component;
        for (String variant : new String[]{"requiredBody", "fullBody"}) {
            JsonNode value = keywords.body(component).get(variant).get("value");
            for (int i = 0; i < branches; i++) {
                assertThat(keywords.oracle().errors(at + "/allOf/" + i, value))
                        .as("%s.%s against branch %d", component, variant, i).isEmpty();
            }
        }
    }

    @Test
    void theCompatibleValueSatisfiesBothBranches() {
        satisfiesEveryBranch("AllOfCompatibleV1", 2);
    }

    @Test
    void theConflictingValueSatisfiesBothBranches() {
        satisfiesEveryBranch("AllOfConflictingV1", 2);
    }

    @Test
    void theFirstDefinitionAloneWouldHaveGivenAnInvalidValue() {
        // What the first-definition merge describes -- minLength 2, the enum's first value --
        // is not valid against the second branch: the reason generation intersects instead.
        JsonNode first = Oracle.JSON.readTree("{\"value\":\"aa\",\"count\":0}");
        assertThat(keywords.oracle().errors("/components/schemas/AllOfConflictingV1/allOf/0", first)).isEmpty();
        assertThat(keywords.oracle().errors("/components/schemas/AllOfConflictingV1/allOf/1", first)).isNotEmpty();
    }

    @Test
    void theDescriptorsKeepTheFirstDefinitionOfEachKeyword() throws Throwable {
        assertThat(keywords.sources().constant("AllOfConflictingV1", "VALUE_MIN_LENGTH")).isEqualTo(2);
        assertThat(keywords.sources().constant("AllOfConflictingV1", "COUNT_MINIMUM")).isEqualTo(0);
        assertThat(keywords.sources().constant("AllOfConflictingV1", "COUNT_MAXIMUM")).isEqualTo(10);
        assertThat(keywords.sources().call("AllOfConflictingV1", "body", new Class<?>[]{String.class, int.class},
                "bbbbb", 4)).isEqualTo("{\"value\":\"bbbbb\",\"count\":4}\n");
        assertThat(keywords.sources().call("AllOfConflictingV1", "requiredBody", new Class<?>[0]))
                .isEqualTo("{\"value\":\"bbbbb\",\"count\":4}\n");
    }
}
