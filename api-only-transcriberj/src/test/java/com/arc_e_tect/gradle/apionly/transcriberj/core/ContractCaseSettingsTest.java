package com.arc_e_tect.gradle.apionly.transcriberj.core;

import com.arc_e_tect.gradle.apionly.transcriberj.spi.CaseKind;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.Settings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T19.11, in process: {@code derive} as {@link Settings} holds it, and switching each kind off
 * removes exactly that kind's cases and gives each response and constraint they covered the
 * reason {@code KIND_SWITCHED_OFF}, leaving everything else as it was: a response no case of the
 * kind could cover keeps its own reason. That a change regenerates, under
 * the configuration cache, is the functional test's.
 */
@DisplayName("T19.11 Settings")
class ContractCaseSettingsTest {

    @TempDir
    static Path directory;

    @Test
    void deriveDefaultsToEveryKindInTheirOwnOrder() {
        assertThat(new Settings("c", "a.b", false, "p", 3).derive()).containsExactly("success", "notFound",
                "notAcceptable", "unsupportedMediaType", "invalidRequest");
        Settings some = new Settings("c", "a.b", false, "p", 3, null, null, true, null, null,
                new ArrayList<>(List.of("invalidRequest", "success", "success")));
        assertThat(some.derive()).containsExactly("success", "invalidRequest");
        assertThat(some.derives(CaseKind.SUCCESS)).isTrue();
        assertThat(some.derives(CaseKind.NOT_FOUND)).isFalse();
        assertThat(new Settings("c", "a.b", false, "p", 3, null, null, true, null, null, List.of()).derive()).isEmpty();
        assertThatThrownBy(() -> new Settings("c", "a.b", false, "p", 3, null, null, true, null, null,
                List.of("success", "happyPath"))).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("derive names 'happyPath', which is not a kind; the kinds are success, notFound, "
                        + "notAcceptable, unsupportedMediaType, invalidRequest");
        assertThatThrownBy(() -> CaseKind.ofSetting("SUCCESS")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void eachKindKnowsItsSettingAndWhetherItRequiresState() {
        for (CaseKind kind : CaseKind.values()) {
            assertThat(CaseKind.ofSetting(kind.setting())).isEqualTo(kind);
            assertThat(kind.requiresState()).isEqualTo(kind == CaseKind.SUCCESS || kind == CaseKind.NOT_FOUND);
        }
    }

    /** The contracts every kind is switched off in: together they derive every kind. */
    static final Map<String, Path> CONTRACTS = Map.of(
            "success", ContractCaseFixtures.CORPUS_DIRECTORY.resolve("success.yaml"),
            "not-found", ContractCaseFixtures.CORPUS_DIRECTORY.resolve("not-found.yaml"),
            "negotiation", ContractCaseFixtures.CORPUS_DIRECTORY.resolve("negotiation.yaml"),
            "user-account", GeneratedSources.CONTRACTS.resolve("user-account/openapi.yaml"));

    private static JsonNode report(String contract, List<String> derive, String run) {
        Settings settings = new Settings(contract, GeneratedSources.PACKAGE, false, "PLACEHOLDER", 2, null, "400", true,
                List.of(), Map.of(), derive);
        GeneratedSources sources = GeneratedSources.generate(CONTRACTS.get(contract), "1.0.0",
                directory.resolve(contract + "-" + run), settings, List.of());
        return Oracle.JSON.readTree(sources.report.renderValidValues(contract, "1.0.0"));
    }

    @TestFactory
    Stream<DynamicTest> switchingAKindOffRemovesExactlyItsCases() {
        List<DynamicTest> tests = new ArrayList<>();
        for (String contract : List.of("success", "not-found", "negotiation", "user-account")) {
            JsonNode all = report(contract, null, "all");
            for (CaseKind kind : CaseKind.values()) {
                tests.add(DynamicTest.dynamicTest(contract + " without " + kind.setting(), () -> {
                    List<String> derive = Arrays.stream(CaseKind.values()).filter(k -> k != kind)
                            .map(CaseKind::setting).toList();
                    JsonNode without = report(contract, derive, "without-" + kind.setting());
                    assertThat(ContractCaseFixtures.cases(without).stream().map(ContractCaseFixtures::line).toList())
                            .containsExactlyElementsOf(ContractCaseFixtures.cases(all).stream()
                                    .filter(c -> !c.get("kind").stringValue().equals(kind.name()))
                                    .map(ContractCaseFixtures::line).toList());
                    List<String> ids = ContractCaseFixtures.cases(all, kind.name()).stream()
                            .map(c -> c.get("location").stringValue() + " " + c.get("id").stringValue()).toList();
                    for (int i = 0; i < all.get("responseCoverage").size(); i++) {
                        JsonNode was = all.get("responseCoverage").get(i);
                        JsonNode now = without.get("responseCoverage").get(i);
                        boolean ofKind = was.has("cases") && ids.contains(was.get("operation").stringValue() + " "
                                + was.get("cases").get(0).stringValue());
                        if (ofKind) {
                            assertThat(now.get("uncovered").get("code").stringValue()).isEqualTo("KIND_SWITCHED_OFF");
                            assertThat(now.get("uncovered").get("detail").stringValue())
                                    .isEqualTo("derive does not name " + kind.setting());
                        } else {
                            assertThat(now).isEqualTo(was);
                        }
                    }
                    for (int i = 0; i < all.get("constraintCoverage").size(); i++) {
                        JsonNode was = all.get("constraintCoverage").get(i);
                        JsonNode now = without.get("constraintCoverage").get(i);
                        if (kind == CaseKind.INVALID_REQUEST && was.has("cases")) {
                            assertThat(now.get("uncovered").get("code").stringValue()).isEqualTo("KIND_SWITCHED_OFF");
                        } else {
                            assertThat(now).isEqualTo(was);
                        }
                    }
                }));
            }
        }
        return tests.stream();
    }
}
