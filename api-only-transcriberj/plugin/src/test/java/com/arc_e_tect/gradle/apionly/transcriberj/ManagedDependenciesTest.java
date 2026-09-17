package com.arc_e_tect.gradle.apionly.transcriberj;

import com.arc_e_tect.gradle.apionly.transcriberj.spi.ManagedDependency;
import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManagedDependenciesTest {

    private static final ManagedDependency REST_DOCS =
            new ManagedDependency("org.springframework.restdocs", "spring-restdocs-core", "4.0.1", "5");

    @Test
    void versionsCompareByTheirNumbers() {
        assertThat(ManagedDependencies.atLeast("4.0.1", "5")).isFalse();
        assertThat(ManagedDependencies.atLeast("5", "5")).isTrue();
        assertThat(ManagedDependencies.atLeast("5.0.0-RC1", "5")).isTrue();
        assertThat(ManagedDependencies.atLeast("10.1", "9.9.9")).isTrue();
        assertThat(ManagedDependencies.atLeast("4.9", "4.10")).isFalse();
        assertThat(ManagedDependencies.atLeast("4", "4.0.1")).isFalse();
        assertThat(ManagedDependencies.atLeast("final", "1")).isFalse();
    }

    @Test
    void anUntestedResolvedVersionIsWarnedAboutAndATestedOneIsNot() {
        List<String> warnings = new ArrayList<>();
        ManagedDependencies.check("testCompileClasspath",
                Map.of("org.springframework.restdocs:spring-restdocs-core", "5.1.0",
                        "org.example:other", "9"),
                Map.of("restdocs", List.of(REST_DOCS)), false, warnings::add);

        assertThat(warnings).containsExactly("Emitter restdocs: org.springframework.restdocs:spring-restdocs-core "
                + "5.1.0 in testCompileClasspath is not tested with this plugin version; it is tested with "
                + "versions below 5, and adds 4.0.1 when the project declares none.");

        warnings.clear();
        ManagedDependencies.check("testCompileClasspath",
                Map.of("org.springframework.restdocs:spring-restdocs-core", "4.0.1"),
                Map.of("restdocs", List.of(REST_DOCS,
                        new ManagedDependency("org.example", "unbounded", "1", null))), false, warnings::add);
        assertThat(warnings).isEmpty();
    }

    @Test
    void strictDependenciesTurnTheWarningIntoAFailure() {
        assertThatThrownBy(() -> ManagedDependencies.check("c",
                Map.of("org.springframework.restdocs:spring-restdocs-core", "5"),
                Map.of("restdocs", List.of(REST_DOCS)), true, w -> { }))
                .isInstanceOf(GradleException.class)
                .hasMessageContaining("is not tested with this plugin version")
                .hasMessageContaining("strictDependencies");
    }
}
