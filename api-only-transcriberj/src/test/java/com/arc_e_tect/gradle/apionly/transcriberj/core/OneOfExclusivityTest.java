package com.arc_e_tect.gradle.apionly.transcriberj.core;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T12.7: where a {@code oneOf}'s first branch yields a value the second branch accepts
 * too, the generated value is the second branch's, valid against exactly one branch --
 * checked with the oracle, branch by branch.
 */
@DisplayName("T12.7 oneOf exclusivity")
class OneOfExclusivityTest {

    private static final String OVERLAP = "/components/schemas/OverlapV1/properties/value/oneOf/";

    @TempDir
    static Path directory;

    static ValidValueFixtures.Fixture keywords;

    @BeforeAll
    static void generate() {
        keywords = ValidValueFixtures.corpus("keywords", directory);
    }

    private static List<Integer> branchesValid(String prefix, JsonNode value) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            if (keywords.oracle().valid(prefix + i, value)) out.add(i);
        }
        return out;
    }

    @Test
    void theBranchesOverlap() {
        // The first branch's own value, 3, is valid against both: it cannot be the one.
        assertThat(branchesValid(OVERLAP, Oracle.JSON.readTree("3"))).containsExactly(0, 1);
    }

    @Test
    void theGeneratedValueIsValidAgainstExactlyOneBranch() {
        for (String variant : new String[]{"requiredBody", "fullBody"}) {
            JsonNode value = keywords.body("OverlapV1").get(variant).get("value").get("value");
            assertThat(branchesValid(OVERLAP, value)).as(variant).containsExactly(1);
        }
    }

    @Test
    void soIsTheRequestBodyWhoseSchemaIsTheSameChoice() {
        String prefix = "/paths/~1overlap/post/requestBody/content/application~1json/schema/oneOf/";
        for (String variant : new String[]{"requiredRequest", "fullRequest"}) {
            JsonNode body = keywords.request("PostOverlapOperation").get(variant).get("value").get("body");
            assertThat(branchesValid(prefix, body)).as(variant).containsExactly(1);
        }
    }

    @Test
    void aDiscriminatedChoiceTakesTheBranchTheMappingNames() {
        JsonNode pet = keywords.body("DiscriminatedV1").get("requiredBody").get("value").get("pet");
        assertThat(branchesValid("/components/schemas/PetV1/oneOf/", pet)).containsExactly(0);
        assertThat(pet.get("petType").stringValue()).isEqualTo("feline");
    }
}
