package com.arc_e_tect.gradle.apionly.transcriberj;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The IDE wiring in a real build, where the idea-ext plugin the build applies is loaded
 * by a class loader of its own -- which is why the plugin reaches its model reflectively.
 */
class IdeIntegrationFunctionalTest {

    /** The idea-ext version the test build applies; the plugin itself compiles against none. */
    private static final String IDEA_EXT_VERSION = "1.4.1";

    @TempDir
    Path projectDir;

    @BeforeEach
    void seedProject() throws IOException {
        projectDir = projectDir.toRealPath();
        Files.writeString(projectDir.resolve("settings.gradle"), "rootProject.name = 'consumer'\n");
        Files.writeString(projectDir.resolve("build.gradle"), """
                plugins {
                    id 'java'
                    id 'eclipse'
                    id 'org.jetbrains.gradle.plugin.idea-ext' version '%s'
                    id 'com.arc-e-tect.api-only-transcriberj'
                }

                apiOnlySubscriber {
                    channel {
                        type = 'file'
                        directory = file('channel')
                    }
                    subscribe('user-account') {
                        version = '1.0.0'
                    }
                }

                apiOnlyTranscriberJ {
                    subscription('user-account') {
                        basePackage = 'com.example.contract'
                    }
                }

                // Read at configuration time, so the task carries values rather than the model.
                def triggers = idea.project.settings.taskTriggers.phaseMap.collectEntries { phase, tasks ->
                    [phase, tasks.collect { it.toString() }]
                }.toString()
                def generated = idea.module.generatedSourceDirs.collect { it.path }.toString()
                def synchronised = eclipse.synchronizationTasks.getDependencies(null).collect { it.name }.toString()

                tasks.register('ideModel') {
                    doLast {
                        println "TRIGGERS ${triggers}"
                        println "GENERATED ${generated}"
                        println "SYNCHRONISED ${synchronised}"
                    }
                }
                """.formatted(IDEA_EXT_VERSION));
    }

    private BuildResult run(String... arguments) {
        return GradleRunner.create().withProjectDir(projectDir.toFile()).withPluginClasspath()
                .withArguments(arguments).forwardOutput().build();
    }

    @Test
    void anIdeIsToldWhatToRunOnSyncAndWhatIsGenerated() {
        BuildResult result = run("ideModel", "--configuration-cache", "--stacktrace");

        assertThat(result.getOutput())
                .contains("TRIGGERS [afterSync:[provider(task 'generateContractSourcesUserAccount'")
                .contains("GENERATED [" + projectDir.resolve("build/generated/sources/transcriberj/user-account"))
                .contains("SYNCHRONISED [generateContractSourcesUserAccount]")
                .doesNotContain("could not register")
                .doesNotContain("will not run on IDE sync");

        BuildResult again = run("ideModel", "--configuration-cache");
        assertThat(again.getOutput()).contains("Configuration cache entry reused");
    }

    @Test
    void aSubprojectsGenerationRunsOnTheRootsSync() throws IOException {
        // The shape of a real service build: the IDE plugin at the root, the TranscriberJ in
        // the project that subscribes to a contract. IntelliJ reads the root's triggers.
        Files.writeString(projectDir.resolve("settings.gradle"),
                "rootProject.name = 'shop'\ninclude 'service'\n");
        Files.writeString(projectDir.resolve("build.gradle"), """
                plugins {
                    id 'org.jetbrains.gradle.plugin.idea-ext' version '%s'
                }

                // The subproject registers its trigger while it is evaluated, which is after
                // this file is read, so the model is captured once every project is evaluated.
                def triggers = new StringBuilder()
                gradle.projectsEvaluated {
                    triggers.append(idea.project.settings.taskTriggers.phaseMap.collectEntries { phase, tasks ->
                        [phase, tasks.collect { it.toString() }]
                    }.toString())
                }

                tasks.register('ideModel') {
                    doLast { println "TRIGGERS ${triggers}" }
                }
                """.formatted(IDEA_EXT_VERSION));
        Path service = Files.createDirectories(projectDir.resolve("service"));
        Files.writeString(service.resolve("build.gradle"), """
                plugins {
                    id 'java'
                    id 'com.arc-e-tect.api-only-transcriberj'
                }

                apiOnlySubscriber {
                    channel {
                        type = 'file'
                        directory = file('channel')
                    }
                    subscribe('user-account') {
                        version = '1.0.0'
                    }
                }

                apiOnlyTranscriberJ {
                    subscription('user-account') {
                        basePackage = 'com.example.contract'
                    }
                }
                """);

        BuildResult result = run("ideModel", "--configuration-cache", "--stacktrace");

        // Only :service registers that task, so the root's triggers holding it is the point.
        assertThat(result.getOutput())
                .contains("TRIGGERS [afterSync:[provider(task 'generateContractSourcesUserAccount'")
                .doesNotContain("will not run on IDE sync");
    }
}
