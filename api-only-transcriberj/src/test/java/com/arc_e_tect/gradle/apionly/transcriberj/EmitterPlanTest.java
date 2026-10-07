package com.arc_e_tect.gradle.apionly.transcriberj;

import com.arc_e_tect.gradle.apionly.transcriberj.core.ModalEmitter;
import com.arc_e_tect.gradle.apionly.transcriberj.core.TestEmitter;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.Emitter;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.Output;
import org.gradle.api.GradleException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** T18.2, in-process: the placement rules, and what each broken rule says. */
@DisplayName("T18.2 Placement rules")
class EmitterPlanTest {

    static final List<String> CORE = List.of("testComponent", "testContract", "testContractWireMock");
    static final List<Emitter> LOADED = List.of(new TestEmitter(), new ModalEmitter());

    static EmitterPlan plan(String mode, Map<String, Map<String, String>> emitterOptions,
                            Map<String, EmitterPlan.Configured> configured) {
        return EmitterPlan.of("orders", mode, CORE, emitterOptions, configured, LOADED);
    }

    static EmitterPlan.Configured block(List<String> sourceSets, Map<String, String> options) {
        return new EmitterPlan.Configured(sourceSets, options);
    }

    @Test
    void emittersAreNamedOrConfiguredByDefaultWithTheSubscriptionsSourceSets() {
        EmitterPlan plan = plan("perSourceSet", Map.of(),
                Map.of("modal", block(List.of("testContract"), Map.of("mode", "java"))));

        assertThat(plan.emitters()).containsOnlyKeys("counting", "modal");
        assertThat(plan.emitters().get("modal").sourceSets()).containsExactly("testContract");
        assertThat(plan.emitters().get("modal").produces()).containsExactly(Output.JAVA);
        assertThat(plan.emitters().get("modal").named()).isTrue();
        assertThat(plan.emitters().get("counting").sourceSets()).isEqualTo(CORE);
        assertThat(plan.emitters().get("counting").named()).isFalse();
        assertThat(plan.warnings()).singleElement().asString()
                .contains("emitter 'counting' is on transcriberjEmitters but not configured with emitter('counting')")
                .contains("no options").contains("From 1.0.0").contains(EmitterPlan.MIGRATION_GUIDE);
    }

    @Test
    void aFilesOnlyEmitterIsOnNoSourceSetByDefault() {
        EmitterPlan plan = plan("shared", Map.of(),
                Map.of("modal", block(null, Map.of("mode", "files")), "counting", block(null, Map.of())));

        assertThat(plan.emitters().get("modal").sourceSets()).isEmpty();
        assertThat(plan.emitters().get("modal").produces()).containsExactly(Output.FILES);
        assertThat(plan.warnings()).isEmpty();
    }

    @Test
    void emitterOptionsStillReachTheEmitterAndAreWarnedAboutOnce() {
        EmitterPlan plan = plan("perSourceSet", Map.of("modal", Map.of("mode", "java"), "counting", Map.of("a", "b")),
                Map.of("modal", block(null, Map.of())));

        assertThat(plan.emitters().get("modal").options()).containsExactly(Map.entry("mode", "java"));
        assertThat(plan.emitters().get("counting").options()).containsExactly(Map.entry("a", "b"));
        assertThat(plan.warnings()).filteredOn(w -> w.contains("emitterOptions, which is deprecated")).hasSize(1);
        assertThat(plan.warnings()).anyMatch(w -> w.contains("runs with the subscription's source sets and its "
                + "emitterOptions"));
    }

    @Test
    void anUnknownEmitterIdFailsNamingTheLoadedOnes() {
        assertThatThrownBy(() -> plan("perSourceSet", Map.of(), Map.of("wiremock", block(null, Map.of()))))
                .isInstanceOf(GradleException.class)
                .hasMessage("apiOnlyTranscriberJ: subscription('orders') configures emitter('wiremock'), but no "
                        + "emitter with that id is on the transcriberjEmitters classpath; the emitters there are "
                        + "counting, modal. Add the emitter's library to transcriberjEmitters, or remove "
                        + "emitter('wiremock').");
        assertThatThrownBy(() -> EmitterPlan.of("orders", "perSourceSet", CORE, Map.of("x", Map.of()), Map.of(),
                List.of()))
                .hasMessage("apiOnlyTranscriberJ: subscription('orders'): emitterOptions names x, but no emitter with "
                        + "that id is on the transcriberjEmitters classpath; the emitters there are none. Add the "
                        + "emitter's library to transcriberjEmitters, or remove its options.");
    }

    @Test
    void anEmitterSourceSetThatDoesNotSeeTheSchemaClassesFails() {
        assertThatThrownBy(() -> plan("perSourceSet", Map.of(),
                Map.of("modal", block(List.of("testContract", "testSystem"), Map.of()))))
                .hasMessage("apiOnlyTranscriberJ: subscription('orders'): emitter('modal') compiles in source set "
                        + "'testSystem', which does not see the schema classes: it does not compile them. Add "
                        + "'testSystem' to the subscription's sourceSets, or remove it from emitter('modal')'s.");
        assertThatThrownBy(() -> plan("shared", Map.of(), Map.of("modal", block(List.of("testSystem"), Map.of()))))
                .hasMessageContaining("it does not depend on the shared source set transcriberjOrders");
    }

    @Test
    void sourceSetsOnAFilesOnlyEmitterFail() {
        assertThatThrownBy(() -> plan("perSourceSet", Map.of(),
                Map.of("modal", block(List.of("testContract"), Map.of("mode", "files")))))
                .hasMessage("apiOnlyTranscriberJ: subscription('orders'): emitter('modal') writes only files with "
                        + "the options {mode=files}, and files belong on no source set, but its sourceSets names "
                        + "testContract. Remove sourceSets from emitter('modal').");
    }

    @Test
    void anEmptySourceSetListOnAJavaOrResourceEmitterFails() {
        assertThatThrownBy(() -> plan("perSourceSet", Map.of(), Map.of("modal", block(List.of(), Map.of()))))
                .hasMessage("apiOnlyTranscriberJ: subscription('orders'): emitter('modal') writes Java sources with "
                        + "the options {}, which need a source set, but its sourceSets is empty. Name one or more of "
                        + "the subscription's source sets (testComponent, testContract, testContractWireMock), or "
                        + "remove sourceSets from emitter('modal') to use them all.");
        assertThatThrownBy(() -> plan("perSourceSet", Map.of(),
                Map.of("modal", block(List.of(), Map.of("mode", "resources")))))
                .hasMessageContaining("writes classpath resources with the options {mode=resources}");
    }

    @Test
    void optionsSetTwiceFail() {
        assertThatThrownBy(() -> plan("perSourceSet", Map.of("modal", Map.of("mode", "java")),
                Map.of("modal", block(null, Map.of("mode", "files")))))
                .hasMessage("apiOnlyTranscriberJ: subscription('orders') sets the options of emitter 'modal' twice: "
                        + "in emitterOptions and in emitter('modal') { options }. Keep emitter('modal') { options = "
                        + "... } and remove 'modal' from emitterOptions, which is deprecated.");
    }

    @Test
    void anUnknownSchemaClassesModeFails() {
        assertThatThrownBy(() -> plan("once", Map.of(), Map.of()))
                .hasMessage("apiOnlyTranscriberJ: subscription('orders') sets schemaClasses = 'once', which is not a "
                        + "mode. Set it to 'perSourceSet', for each source set to compile the schema classes, or "
                        + "'shared', for one source set to compile them and the others to depend on it.");
    }

    @Test
    void schemaClassesInTestAndAnotherSourceSetAreWarnedAboutForIntelliJ() {
        List<String> withTest = List.of("test", "testContract", "testSystem");
        EmitterPlan plan = EmitterPlan.of("orders", "perSourceSet", withTest, Map.of(),
                Map.of("modal", block(List.of("testContract"), Map.of("mode", "java")), "counting", block(null, Map.of())),
                LOADED);

        assertThat(plan.warnings()).singleElement().asString()
                .isEqualTo("apiOnlyTranscriberJ: subscription('orders'): IntelliJ IDEA will not resolve the schema "
                        + "classes (test, testContract, testSystem) or emitter('counting')'s output (test, "
                        + "testContract, testSystem) outside the main or test module. Each is compiled in main or "
                        + "test and in another source set, and IntelliJ's Gradle import keeps a directory that main "
                        + "or test shares with another source set in that one module. The Gradle build is not "
                        + "affected. To fix it, leave 'test' out of the subscription's sourceSets, or set "
                        + "schemaClasses = 'shared'; name emitter('counting')'s sourceSets without 'test'. See "
                        + EmitterPlan.IDE_TEST_SOURCE_SET);
    }

    @Test
    void sharedSchemaClassesInTestAreNotWarnedAboutButAnEmittersOutputIs() {
        List<String> withTest = List.of("test", "testContract");
        EmitterPlan quiet = EmitterPlan.of("orders", "shared", withTest, Map.of(),
                Map.of("modal", block(List.of("testContract"), Map.of("mode", "java")),
                        "counting", block(List.of("testContract"), Map.of())), LOADED);
        EmitterPlan warned = EmitterPlan.of("orders", "shared", withTest, Map.of(),
                Map.of("modal", block(List.of("testContract"), Map.of("mode", "java")), "counting", block(null, Map.of())),
                LOADED);

        assertThat(quiet.warnings()).isEmpty();
        assertThat(warned.warnings()).singleElement().asString()
                .contains("will not resolve emitter('counting')'s output (test, testContract) outside")
                .doesNotContain("the schema classes")
                .contains("To fix it, name emitter('counting')'s sourceSets without 'test'.");
    }

    @Test
    void testOrMainAloneIsNotWarnedAbout() {
        Map<String, EmitterPlan.Configured> configured =
                Map.of("modal", block(null, Map.of("mode", "files")), "counting", block(null, Map.of()));

        assertThat(EmitterPlan.of("orders", "perSourceSet", List.of("test"), Map.of(), configured, LOADED).warnings())
                .isEmpty();
        assertThat(EmitterPlan.of("orders", "perSourceSet", List.of("main"), Map.of(), configured, LOADED).warnings())
                .isEmpty();
        assertThat(plan("perSourceSet", Map.of(), configured).warnings()).isEmpty();
    }

    @Test
    void theSharedSourceSetIsNamedAfterTheContract() {
        assertThat(EmitterPlan.sharedSourceSet("user-account")).isEqualTo("transcriberjUserAccount");
    }
}
