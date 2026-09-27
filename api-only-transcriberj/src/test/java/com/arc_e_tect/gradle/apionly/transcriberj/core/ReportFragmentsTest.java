package com.arc_e_tect.gradle.apionly.transcriberj.core;

import com.arc_e_tect.gradle.apionly.transcriberj.model.ContractParser;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.Settings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The report stays one per subscription when the core and each emitter run in tasks of their
 * own: the core's fragment and every emitter's, merged in emitter order, render exactly what one
 * run of all of them renders, and the AsciiDoc report shows every finding.
 */
@DisplayName("T18 Report fragments")
class ReportFragmentsTest {

    @TempDir
    Path dir;

    static List<Path> contracts() {
        Path fixtures = Path.of(System.getProperty("transcriberj.fixtures"));
        Path reference = Path.of(System.getProperty("transcriberj.referenceApi"));
        return List.of(reference.resolve("user-account/openapi.yaml"), reference.resolve("portfolio/openapi.yaml"),
                fixtures.resolve("invalid-requests/corpus/keywords.yaml"),
                fixtures.resolve("invalid-requests/corpus/degraded.yaml"),
                fixtures.resolve("invalid-requests/corpus/formats.yaml"));
    }

    static Settings settings() {
        return new Settings("c", "com.example.contract", false, "P", 3, null, "400", true, List.of("email", "uuid"),
                Map.of("modal", Map.of("mode", "java")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("contracts")
    void mergedFragmentsRenderWhatOneRunRenders(Path contract) throws IOException {
        String version = ContractParser.parse(contract, null).version();
        GenerationReport whole = Generation.run(contract, null, version, "a".repeat(64), settings(),
                dir.resolve("whole/java"), dir.resolve("whole/resources"),
                List.of(new ModalEmitter(), new TestEmitter()).stream()
                        .sorted(java.util.Comparator.comparing(com.arc_e_tect.gradle.apionly.transcriberj.spi.Emitter::id))
                        .toList(), null);

        Generation.Derivation derivation = Generation.derive(contract, null, version, "a".repeat(64), settings());
        String core = Generation.writeCore(derivation, dir.resolve("core/java"), dir.resolve("core/resources"), null)
                .fragment();
        String counting = Generation.runEmitter(derivation, new TestEmitter(), dir.resolve("counting/java"),
                dir.resolve("counting/resources"), dir.resolve("counting/files")).fragment();
        String modal = Generation.runEmitter(derivation, new ModalEmitter(), dir.resolve("modal/java"),
                dir.resolve("modal/resources"), dir.resolve("modal/files")).fragment();

        GenerationReport merged = GenerationReport.fromFragment(core);
        merged.add(GenerationReport.fromFragment(counting));
        merged.add(GenerationReport.fromFragment(modal));

        assertThat(merged.render("c", version)).isEqualTo(whole.render("c", version));
        assertThat(GenerationReport.fromFragment(merged.fragment()).render("c", version))
                .isEqualTo(whole.render("c", version));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("contracts")
    void theAsciiDocReportShowsEveryLineOfTheTextReportVerbatim(Path contract) throws IOException {
        String version = ContractParser.parse(contract, null).version();
        Generation.Derivation derivation = Generation.derive(contract, null, version, "a".repeat(64), settings());
        GenerationReport core = derivation.report();
        GenerationReport counting = Generation.runEmitter(derivation, new TestEmitter(), dir.resolve("j"),
                dir.resolve("r"), dir.resolve("f"));
        GenerationReport merged = GenerationReport.fromFragment(core.fragment());
        merged.add(counting);

        String adoc = core.renderAsciiDoc("Core") + counting.renderAsciiDoc("Emitter counting");
        for (String line : merged.render("c", version).split("\n")) {
            if (line.startsWith("  ")) assertThat(adoc).as(line).contains(line.substring(2));
        }
        assertThat(counting.renderAsciiDoc("Emitter counting"))
                .startsWith("= Emitter counting\n\n== Degraded methods -- these throw UnsupportedOperationException\n\n....\n");

        String frame = merged.renderAsciiDocFrame("c", version, List.of("c/core.adoc", "c/counting.adoc"));
        String[] summary = merged.render("c", version).split("\n");
        assertThat(frame).startsWith("= API-Only TranscriberJ report: c " + version + "\n")
                .contains("....\n" + summary[1] + "\n" + summary[2] + "\n....\n")
                .endsWith("\ninclude::c/core.adoc[leveloffset=+1]\n\ninclude::c/counting.adoc[leveloffset=+1]\n");
    }

    @org.junit.jupiter.api.Test
    void anEmitterThatReportsNothingSaysSo() {
        assertThat(new GenerationReport().renderAsciiDoc("Emitter quiet"))
                .isEqualTo("= Emitter quiet\n\nNothing to report.\n");
    }
}
