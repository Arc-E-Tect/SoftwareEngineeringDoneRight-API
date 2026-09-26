package com.arc_e_tect.gradle.apionly.transcriberj.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T12.10: the valid values of the five reference-implementation contracts are those
 * recorded in {@code fixtures/valid-values/golden/<contract>.json}, so that any change
 * to value generation shows up as a reviewable diff. T12.1 shows each of them valid.
 * {@code ./gradlew recordValidValuesGolden} re-records them.
 */
@DisplayName("T12.10 Reference contracts, end to end")
class ReferenceContractValuesTest {

    @TempDir
    static Path directory;

    @TestFactory
    Stream<DynamicTest> eachReferenceContractGivesItsGoldenValues() {
        return ValidValueFixtures.REFERENCE.stream().map(contract -> DynamicTest.dynamicTest(contract, () -> {
            ValidValueFixtures.Fixture f = ValidValueFixtures.reference(contract, directory.resolve(contract));
            Path golden = Baselines.GOLDEN.resolve(contract + ".json");
            assertThat(golden).as("the golden file; ./gradlew recordValidValuesGolden records it").exists();
            assertThat(Baselines.golden(f)).isEqualTo(Files.readString(golden, StandardCharsets.UTF_8));
        }));
    }
}
