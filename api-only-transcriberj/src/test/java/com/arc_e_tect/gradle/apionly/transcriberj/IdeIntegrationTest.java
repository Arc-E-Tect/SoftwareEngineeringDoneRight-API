package com.arc_e_tect.gradle.apionly.transcriberj;

import com.arc_e_tect.gradle.apionly.subscriber.ApiOnlySubscriberExtension;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.plugins.ExtensionAware;
import org.gradle.plugins.ide.eclipse.model.EclipseModel;
import org.gradle.plugins.ide.idea.model.IdeaModel;
import org.gradle.testfixtures.ProjectBuilder;
import org.jetbrains.gradle.ext.ProjectSettings;
import org.jetbrains.gradle.ext.TaskTriggersConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What an IDE is told about the generated sources: the tasks its sync runs, and the
 * directories it should treat as generated.
 */
class IdeIntegrationTest {

    @TempDir
    Path projectDir;

    private Project project;

    @BeforeEach
    void setUp() throws IOException {
        projectDir = projectDir.toRealPath();
        project = ProjectBuilder.builder().withProjectDir(projectDir.toFile()).build();
    }

    private void subscribe(String... contracts) {
        project.getPluginManager().apply("java");
        project.getPluginManager().apply(ApiOnlyTranscriberJPlugin.class);
        ApiOnlySubscriberExtension subscriber = project.getExtensions().getByType(ApiOnlySubscriberExtension.class);
        ApiOnlyTranscriberJExtension transcriber =
                project.getExtensions().getByType(ApiOnlyTranscriberJExtension.class);
        boolean implemented = false;
        for (String contract : contracts) {
            // A project implements one contract and calls the rest, as the Subscriber requires.
            if (implemented) {
                subscriber.subscribeAsClient(contract, s -> s.getApiContractVersion().set("1.0.0"));
            } else {
                subscriber.subscribe(contract);
                implemented = true;
            }
            transcriber.subscription(contract, s -> s.getBasePackage().set("com.example." + s.getName()));
        }
        ((org.gradle.api.internal.project.ProjectInternal) project).evaluate();
    }

    private File generated(String contract) {
        return projectDir.resolve("build/generated/sources/transcriberj/" + contract).toFile();
    }

    private static List<?> afterSync(Project project) {
        IdeaModel idea = project.getExtensions().getByType(IdeaModel.class);
        ProjectSettings settings = (ProjectSettings)
                ((ExtensionAware) idea.getProject()).getExtensions().getByName("settings");
        TaskTriggersConfig triggers = (TaskTriggersConfig)
                ((ExtensionAware) settings).getExtensions().getByName("taskTriggers");
        return triggers.getPhaseMap().get("afterSync");
    }

    @Test
    void ideaSyncRunsEveryGenerationTask() {
        project.getPluginManager().apply("idea");
        project.getPluginManager().apply("org.jetbrains.gradle.plugin.idea-ext");

        subscribe("user-account", "billing");

        assertThat(afterSync(project)).hasSize(2).allSatisfy(trigger ->
                assertThat(trigger.toString()).contains("generateContractSources"));
        assertThat(afterSync(project)).anySatisfy(trigger ->
                assertThat(trigger.toString()).contains("generateContractSourcesUserAccount"));
        assertThat(afterSync(project)).anySatisfy(trigger ->
                assertThat(trigger.toString()).contains("generateContractSourcesBilling"));
    }

    @Test
    void ideaIsToldTheSourcesAreGenerated() {
        project.getPluginManager().apply("idea");

        subscribe("user-account", "billing");

        assertThat(project.getExtensions().getByType(IdeaModel.class).getModule().getGeneratedSourceDirs())
                .contains(generated("user-account"), generated("billing"));
    }

    @Test
    void eclipseSynchronisationRunsEveryGenerationTask() {
        project.getPluginManager().apply("eclipse");

        subscribe("user-account", "billing");

        assertThat(project.getExtensions().getByType(EclipseModel.class).getSynchronizationTasks()
                .getDependencies(null)).extracting(Task::getName)
                .contains("generateContractSourcesUserAccount", "generateContractSourcesBilling");
    }

    @Test
    void ideaExtWithoutTheIdeaPluginStillGetsTheTrigger() {
        project.getPluginManager().apply("org.jetbrains.gradle.plugin.idea-ext");

        subscribe("user-account");

        assertThat(afterSync(project)).hasSize(1);
    }

    @Test
    void withoutAnIdePluginTheBuildConfiguresAsBefore() {
        subscribe("user-account");

        assertThat(project.getExtensions().findByName("idea")).isNull();
        assertThat(project.getExtensions().findByName("eclipse")).isNull();
        assertThat(project.getTasks().findByName("generateContractSourcesUserAccount")).isNotNull();
    }

    @Test
    void anIdePluginAppliedAfterTheTranscriberJIsStillWiredUp() {
        subscribe("user-account");

        project.getPluginManager().apply("idea");
        project.getPluginManager().apply("org.jetbrains.gradle.plugin.idea-ext");
        project.getPluginManager().apply("eclipse");

        assertThat(afterSync(project)).hasSize(1);
        assertThat(project.getExtensions().getByType(IdeaModel.class).getModule().getGeneratedSourceDirs())
                .contains(generated("user-account"));
        assertThat(project.getExtensions().getByType(EclipseModel.class).getSynchronizationTasks()
                .getDependencies(null)).extracting(Task::getName)
                .contains("generateContractSourcesUserAccount");
    }

    @Test
    void inAMultiProjectBuildTheRootsSyncRunsEverySubprojectsGeneration() {
        Project root = ProjectBuilder.builder().withProjectDir(projectDir.toFile()).build();
        root.getPluginManager().apply("idea");
        root.getPluginManager().apply("org.jetbrains.gradle.plugin.idea-ext");
        project = ProjectBuilder.builder().withName("service").withParent(root)
                .withProjectDir(projectDir.resolve("service").toFile()).build();

        subscribe("user-account");

        // IntelliJ reads the task triggers from the root project's model, wherever the
        // TranscriberJ is applied.
        assertThat(afterSync(root)).hasSize(1).allSatisfy(trigger ->
                assertThat(trigger.toString()).contains("generateContractSourcesUserAccount"));
    }

    @Test
    void ideaExtOnTheSubprojectIsWiredIntoTheRootRatherThanFailing() {
        Project root = ProjectBuilder.builder().withProjectDir(projectDir.toFile()).build();
        root.getPluginManager().apply("idea");
        root.getPluginManager().apply("org.jetbrains.gradle.plugin.idea-ext");
        project = ProjectBuilder.builder().withName("service").withParent(root)
                .withProjectDir(projectDir.resolve("service").toFile()).build();
        project.getPluginManager().apply("idea");
        project.getPluginManager().apply("org.jetbrains.gradle.plugin.idea-ext");

        subscribe("user-account");

        // A subproject has no IDEA project model of its own, and the task is registered once.
        assertThat(afterSync(root)).hasSize(1);
    }

    @Test
    void aProjectWithoutIdeaExtHasNoTaskTriggers() {
        assertThat(IdeIntegration.taskTriggers(project)).isNull();

        project.getPluginManager().apply("idea");
        assertThat(IdeIntegration.taskTriggers(project)).isNull();

        project.getPluginManager().apply("org.jetbrains.gradle.plugin.idea-ext");
        assertThat(IdeIntegration.taskTriggers(project)).isInstanceOf(TaskTriggersConfig.class);
    }

    @Test
    void anIdeaExtThatHoldsNoTaskTriggersIsReportedRatherThanFatal() {
        subscribe("user-account");
        TaskProvider<GenerateContractSourcesTask> generate = project.getTasks()
                .named("generateContractSourcesUserAccount", GenerateContractSourcesTask.class);

        assertThat(IdeIntegration.registerAfterSync(project, null, generate)).isFalse();
        assertThat(IdeIntegration.registerAfterSync(project, new Object(), generate)).isFalse();
        assertThat(project.getTasks().findByName("generateContractSourcesUserAccount")).isNotNull();
    }

    @Test
    void theEndpointIndexIsNotOfferedToTheIdeAsASourceDirectory() {
        project.getPluginManager().apply("idea");

        subscribe("user-account");

        assertThat(project.getExtensions().getByType(IdeaModel.class).getModule().getGeneratedSourceDirs())
                .noneSatisfy(dir -> assertThat(dir.getPath()).contains("transcriberj-index"));
    }
}
