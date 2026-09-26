package com.arc_e_tect.gradle.apionly.transcriberj.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T13.14: the cases of the reference implementation's user-account contract, and the
 * coverage of its constraints, are exactly the committed golden file: the tests the real
 * application will be run against, reviewed as a diff when they change.
 */
@DisplayName("T13.14 The reference contract, as a golden file")
class InvalidRequestGoldenTest {

    static final Path GOLDEN = InvalidRequestFixtures.GOLDEN.resolve("user-account.json");

    @Test
    void theUserAccountCasesAreTheGoldenOnes(@TempDir Path directory) throws Exception {
        ValidValueFixtures.Fixture f = ValidValueFixtures.reference("user-account", directory);
        assertThat(GOLDEN).as("the golden file; ./gradlew recordInvalidRequestsGolden records it").exists();
        assertThat(InvalidRequestFixtures.golden(f)).isEqualTo(Files.readString(GOLDEN, StandardCharsets.UTF_8));
        assertThat(f.report().get("invalidRequests").toString()).contains("body-username-pattern");
    }
}
