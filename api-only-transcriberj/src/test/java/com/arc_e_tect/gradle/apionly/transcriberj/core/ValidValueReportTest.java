package com.arc_e_tect.gradle.apionly.transcriberj.core;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T12.12: the text report lists every method without a valid value and every parameter
 * not supported yet, its count line -- the one the build log prints -- counts them, and
 * the machine-readable report records every valid value and every reason there is none.
 */
@DisplayName("T12.12 Report content")
class ValidValueReportTest {

    @TempDir
    static Path directory;

    static List<ValidValueFixtures.Fixture> fixtures;

    @BeforeAll
    static void generate() {
        fixtures = ValidValueFixtures.all(directory);
    }

    @Test
    void theTextReportListsEveryMethodWithoutAValidValueAndCountsThem() {
        for (ValidValueFixtures.Fixture f : fixtures) {
            String text = f.text();
            GenerationReport report = f.sources().report;
            assertThat(text.lines().toList().get(1)).as(f.name()).endsWith(", "
                    + report.noValidValue().size() + " method(s) without a valid value, "
                    + report.unsupportedParameters().size() + " parameter(s) not supported yet");
            for (GenerationReport.NoValidValue n : report.noValidValue()) {
                assertThat(text).contains("  " + n.className() + "." + n.method() + " at " + n.location() + ": "
                        + n.reason() + "\n");
            }
            for (GenerationReport.UnsupportedParameter u : report.unsupportedParameters()) {
                assertThat(text).contains("  " + u.className() + " " + u.name() + " at " + u.location() + ": "
                        + u.reason() + "\n");
            }
        }
    }

    @Test
    void everyFindingOfTheUnsatisfiableCorpusIsInBothReports() {
        ValidValueFixtures.Fixture f = fixtures.stream().filter(x -> x.name().equals("unsatisfiable")).findFirst()
                .orElseThrow();
        assertThat(f.sources().report.noValidValue()).hasSize(37);
        assertThat(f.text()).contains("No valid value could be generated -- these methods throw "
                + "UnsupportedOperationException:");
        for (GenerationReport.NoValidValue n : f.sources().report.noValidValue()) {
            String method = n.method().replace("()", "");
            JsonNode entry = n.className().endsWith("Operation") ? f.request(n.className()) : f.body(n.className());
            JsonNode why = entry.get(method).get("unsatisfiable");
            assertThat(why.get("location").stringValue()).isEqualTo(n.location());
            assertThat(why.get("reason").stringValue()).isEqualTo(n.reason());
        }
    }

    @Test
    void theMachineReadableReportCoversEveryGeneratedMethodAndNothingElse() {
        for (ValidValueFixtures.Fixture f : fixtures) {
            long bodies = countDeclaring(f, "public static String requiredBody()");
            long requests = countDeclaring(f, "public static ContractRequest requiredRequest()");
            assertThat(f.bodies()).as(f.name()).hasSize((int) bodies);
            assertThat(f.requests()).as(f.name()).hasSize((int) requests);
            long withoutValue = Stream.concat(f.bodies().stream(), f.requests().stream())
                    .flatMap(e -> Stream.of("requiredBody", "fullBody", "requiredRequest", "fullRequest",
                            "noBodyRequest").filter(e::has).map(e::get))
                    .filter(v -> v.has("unsatisfiable")).count();
            assertThat(withoutValue).as(f.name()).isEqualTo(f.sources().report.noValidValue().size());
            assertThat(f.report().get("schemaVersion").intValue()).isEqualTo(1);
            assertThat(f.report().get("contract").stringValue()).isEqualTo(f.name());
            assertThat(ValidValueFixtures.list(f.report().get("unsupportedParameters")))
                    .hasSize(f.sources().report.unsupportedParameters().size());
        }
    }

    @Test
    void theMachineReadableReportIsCompactSortedAndEndsWithANewline() {
        ValidValueFixtures.Fixture f = fixtures.get(0);
        String json = f.sources().report.renderValidValues(f.name(), "1.0.0");
        assertThat(json).endsWith("}\n").doesNotContain("\n{").startsWith("{\"bodies\":[");
        assertThat(json.indexOf("\"requests\"")).isGreaterThan(json.indexOf("\"contract\""));
    }

    private static long countDeclaring(ValidValueFixtures.Fixture f, String signature) {
        try (Stream<Path> files = Files.walk(f.sources().sources)) {
            return files.filter(p -> p.toString().endsWith(".java")).filter(p -> {
                try {
                    return Files.readString(p).contains(signature);
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }).count();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
