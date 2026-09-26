package com.arc_e_tect.gradle.apionly.transcriberj.core;

import com.arc_e_tect.gradle.apionly.transcriberj.spi.Settings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** T13.12, in process: the settings as {@link Settings} holds them, and as generation reads them. */
@DisplayName("T13.12 Settings plumbing")
class InvalidRequestSettingsTest {

    private static final Path USER_ACCOUNT = GeneratedSources.CONTRACTS.resolve("user-account/openapi.yaml");

    @TempDir
    Path directory;

    @Test
    void theEarlierConstructorsGiveTheDefaults() {
        for (Settings s : List.of(new Settings("c", "a.b", false, "p", 3),
                new Settings("c", "a.b", false, "p", 3, "docs.Descriptions"))) {
            assertThat(s.invalidRequestStatus()).isEqualTo(Settings.DEFAULT_INVALID_REQUEST_STATUS).isEqualTo("400");
            assertThat(s.strictRequests()).isTrue();
            assertThat(s.validateFormats()).isEmpty();
            assertThat(s.emitterOptions()).isEmpty();
            assertThat(s.emitterOptions("restdocs")).isEmpty();
        }
    }

    @Test
    void nullsAreTheDefaultsAndWhatIsGivenIsCopied() {
        Settings defaults = new Settings("c", "a.b", false, "p", 3, null, null, true, null, null);
        assertThat(defaults.invalidRequestStatus()).isEqualTo("400");
        assertThat(defaults.validateFormats()).isEmpty();
        assertThat(defaults.emitterOptions()).isEmpty();

        List<String> formats = new ArrayList<>(List.of("email"));
        Map<String, String> restdocs = new HashMap<>(Map.of("tests", "true", "b", "2"));
        Map<String, Map<String, String>> options = new HashMap<>();
        options.put("restdocs", restdocs);
        options.put("empty", null);
        Settings s = new Settings("c", "a.b", false, "p", 3, null, "422", false, formats, options);
        formats.add("uuid");
        restdocs.put("later", "x");
        assertThat(s.validateFormats()).containsExactly("email");
        assertThat(s.emitterOptions("restdocs")).containsExactly(Map.entry("b", "2"), Map.entry("tests", "true"));
        assertThat(s.emitterOptions("empty")).isEmpty();
        assertThatThrownBy(() -> s.emitterOptions().put("x", Map.of())).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> s.emitterOptions("restdocs").put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void emitterOptionsReachTheEmitterUnchanged() throws Exception {
        Map<String, String> counting = new LinkedHashMap<>();
        counting.put("tests", "true");
        counting.put("with space", "a = b; c");
        Settings s = new Settings("user-account", GeneratedSources.PACKAGE, false, "p", 2, null, "400", true, List.of(),
                Map.of("counting", counting));
        GeneratedSources tree = GeneratedSources.generate(USER_ACCOUNT, "1.0.0", directory, s, List.of(new TestEmitter()));
        assertThat(Files.readString(tree.resources.resolve("counting/options.properties")))
                .isEqualTo("tests=true\nwith space=a = b; c\n");
    }

    @Test
    void anEmitterWithoutOptionsWritesNone() {
        GeneratedSources tree = GeneratedSources.generate(USER_ACCOUNT, "1.0.0", directory,
                GeneratedSources.settings("user-account"), List.of(new TestEmitter()));
        assertThat(tree.resources.resolve("counting/options.properties")).doesNotExist();
    }

    @Test
    void optionsForAnEmitterThatIsNotThereFailTheGeneration() {
        Settings s = new Settings("user-account", GeneratedSources.PACKAGE, false, "p", 2, null, "400", true, List.of(),
                Map.of("restdocs", Map.of("tests", "true")));
        assertThatThrownBy(() -> GeneratedSources.generate(USER_ACCOUNT, "1.0.0", directory, s, List.of()))
                .isInstanceOf(GenerationException.class)
                .hasMessageContaining("emitterOptions names restdocs")
                .hasMessageContaining("the emitters there are none");
        assertThatThrownBy(() -> GeneratedSources.generate(USER_ACCOUNT, "1.0.0", directory.resolve("2"), s,
                List.of(new TestEmitter()))).hasMessageContaining("the emitters there are counting");
    }

    @Test
    void anInvalidRequestStatusThatIsNoStatusFailsTheGeneration() {
        for (String status : List.of("4XX", "default", "600", "40")) {
            Settings s = new Settings("user-account", GeneratedSources.PACKAGE, false, "p", 2, null, status, true,
                    List.of(), Map.of());
            assertThatThrownBy(() -> GeneratedSources.generate(USER_ACCOUNT, "1.0.0", directory.resolve(status), s,
                    List.of())).as(status).isInstanceOf(GenerationException.class)
                    .hasMessageContaining("invalidRequestStatus " + status + " is not a status code");
        }
    }

    @Test
    void theReportSaysWhatWasDerivedAndCountsIt() {
        GeneratedSources tree = GeneratedSources.generate(USER_ACCOUNT, "1.0.0", directory,
                GeneratedSources.settings("user-account"), List.of());
        GenerationReport report = tree.report;
        assertThat(report.invalidRequestCases()).isPositive();
        assertThat(report.constraintCoverage()).containsKey("covered");
        assertThat(report.gaps()).contains("/paths/~1v1~1users~1{username}/get");
        String text = report.render("user-account", "1.0.0");
        assertThat(text.lines().toList().get(2)).startsWith("Invalid requests: " + report.invalidRequestCases()
                + " case(s) derived, " + report.constraintCoverage().get("covered") + " constraint(s) covered, ");
        assertThat(text).contains("\nGaps -- ").contains("\nFormat recommendations:\n")
                .contains("\nConstraint coverage -- ");
    }
}
