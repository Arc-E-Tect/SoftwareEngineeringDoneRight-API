package com.arc_e_tect.gradle.apionly.subscriber;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the plugin through a real Gradle build via TestKit.
 *
 * <p>The other tests in this module cover pure logic -- hashing, the lockfile
 * format, manifest parsing -- and not one of them applies the plugin. They would
 * all pass with a DSL that does not evaluate, a task that is never registered, or
 * a provider Gradle refuses because it carries no producer. Those are the first
 * failures a consumer meets, so they are the ones worth a real build.</p>
 *
 * <p>Everything here uses the {@code file} channel, so the suite needs no network,
 * no registry and no Maven repository. What it exercises is the plugin's own
 * behaviour: the DSL, the task graph, the lockfile lifecycle, and the two refusals
 * the design turns on -- a contract edited after it was fetched, and a published
 * version that changed underneath its own coordinates.</p>
 */
@DisplayName("ApiOnlySubscriberPlugin (real Gradle build)")
class ApiOnlySubscriberPluginFunctionalTest {

    @TempDir Path projectDir;
    @TempDir Path channelDir;

    private static final String OPENAPI = """
            openapi: 3.1.1
            info:
              title: Example
              version: 1.0.0
            paths: {}
            """;

    @BeforeEach
    void seedProject() throws IOException {
        Files.writeString(projectDir.resolve("settings.gradle"), "rootProject.name = 'consumer'\n");
    }

    // ---------------------------------------------------------------- helpers

    private GradleRunner runner(String... arguments) {
        List<String> args = new java.util.ArrayList<>(List.of(arguments));
        args.add("--stacktrace");
        return GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withPluginClasspath()
            .withArguments(args)
            .forwardOutput();
    }

    private void buildFile(String body) throws IOException {
        Files.writeString(projectDir.resolve("build.gradle"), body);
    }

    /** The build every happy-path test uses: one subscription, over the file channel. */
    private String subscribingBuild(String version, String extra) {
        return """
            plugins {
                id 'java'
                id 'com.arc-e-tect.api-only-subscriber'
            }

            apiOnlySubscriber {
                channel {
                    type = 'file'
                    directory = '%s'
                }
                subscribe('user-account') {
                    version = '%s'
                    %s
                }
            }
            """.formatted(channelDir.toString().replace("\\", "\\\\"), version, extra);
    }

    /**
     * Writes an archive into the file channel, shaped exactly as api-only-publisher
     * produces one: the documents plus a manifest.json declaring their hashes.
     */
    private void publish(String target, String version, String openapi) throws Exception {
        Path stage = Files.createTempDirectory("api-only-fixture");
        Files.writeString(stage.resolve("openapi.yaml"), openapi);
        String sha = Lockfile.sha256(stage.resolve("openapi.yaml").toFile());
        Files.writeString(stage.resolve("manifest.json"), """
            {
              "schemaVersion": 1,
              "target": "%s",
              "version": "%s",
              "producedAt": "2026-09-12T00:00:00Z",
              "closureSha256": "%s",
              "source": { "repository": "https://example.invalid/specs.git", "commit": "abc123" },
              "files": [ { "path": "openapi.yaml", "sha256": "%s" } ]
            }
            """.formatted(target, version, "c".repeat(64), sha));

        Path out = channelDir.resolve(target).resolve(version);
        Files.createDirectories(out);
        Process tar = new ProcessBuilder(
            "tar", "-czf", out.resolve(target + "-" + version + ".tgz").toString(),
            "openapi.yaml", "manifest.json")
            .directory(stage.toFile()).inheritIO().start();
        assertThat(tar.waitFor()).as("tar exit code").isZero();
    }

    private Path fetched(String name) {
        return projectDir.resolve("build/api-spec/user-account").resolve(name);
    }

    private String lockfile() throws IOException {
        return Files.readString(projectDir.resolve("apionly.lock"));
    }

    // ------------------------------------------------------------------ tests

    @Nested
    @DisplayName("the DSL and the task graph")
    class Wiring {

        @Test
        @DisplayName("registers an aggregate task and a per-target task for each subscription")
        void registersTasks() throws Exception {
            publish("user-account", "1.0.0", OPENAPI);
            buildFile(subscribingBuild("1.0.0", ""));

            BuildResult result = runner("tasks", "--group", "api-only").build();

            assertThat(result.getOutput())
                .contains("fetchApiSpec")
                .contains("fetchApiSpecUserAccount");
        }

        @Test
        @DisplayName("a subscription with no version is refused, naming the subscription")
        void versionIsRequired() throws Exception {
            buildFile(subscribingBuild("1.0.0", "").replace("version = '1.0.0'", ""));

            BuildResult result = runner("fetchApiSpec").buildAndFail();

            assertThat(result.getOutput()).contains("declares no version");
        }

        @Test
        @DisplayName("verifyApiSpec is wired into check, so drift fails an ordinary build")
        void verifyRunsAsPartOfCheck() throws Exception {
            publish("user-account", "1.0.0", OPENAPI);
            buildFile(subscribingBuild("1.0.0", ""));
            runner("fetchApiSpec").build();

            BuildResult result = runner("check").build();

            assertThat(result.task(":verifyApiSpecUserAccount")).isNotNull();
            assertThat(result.task(":verifyApiSpecUserAccount").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        }

        @Test
        @DisplayName("the fetched document reaches the classpath without living under src/")
        void fetchedDocumentIsAResource() throws Exception {
            publish("user-account", "1.0.0", OPENAPI);
            buildFile(subscribingBuild("1.0.0", ""));

            runner("processResources").build();

            assertThat(projectDir.resolve("build/resources/main/openapi.yaml")).exists();
            assertThat(projectDir.resolve("src")).doesNotExist();
        }

        @Test
        @DisplayName("a consumer wiring the fetched document into its own task needs no explicit dependsOn")
        void providersCarryTheirTaskDependency() throws Exception {
            // The bug this guards against: handing out a bare path lets a consumer
            // read the directory before anything has populated it. Gradle rejects
            // that outright, which is the whole reason the documents are exposed as
            // providers derived from the fetch task.
            publish("user-account", "1.0.0", OPENAPI);
            buildFile(subscribingBuild("1.0.0", "") + """

                abstract class ConsumeContract extends DefaultTask {
                    @InputFile abstract RegularFileProperty getContract()
                    @TaskAction void go() { println "read ${getContract().get().asFile.name}" }
                }

                tasks.register('consumeContract', ConsumeContract) {
                    contract = apiOnlySubscriber.subscription('user-account').openapi
                }
                """);

            BuildResult result = runner("consumeContract").build();

            assertThat(result.task(":fetchApiSpecUserAccount").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
            assertThat(result.getOutput()).contains("read openapi.yaml");
        }
    }

    @Nested
    @DisplayName("fetching")
    class Fetching {

        @Test
        @DisplayName("unpacks into build/, and records what it unpacked")
        void fetchWritesDocumentsAndLockfile() throws Exception {
            publish("user-account", "1.0.0", OPENAPI);
            buildFile(subscribingBuild("1.0.0", ""));

            BuildResult result = runner("fetchApiSpec").build();

            assertThat(result.task(":fetchApiSpecUserAccount").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
            assertThat(fetched("openapi.yaml")).exists();
            assertThat(fetched("manifest.json")).exists();
            assertThat(lockfile())
                .contains("target user-account")
                .contains("version 1.0.0")
                .contains("channel file")
                .contains("openapi.yaml");
        }

        @Test
        @DisplayName("is up to date on a second run")
        void fetchIsUpToDate() throws Exception {
            publish("user-account", "1.0.0", OPENAPI);
            buildFile(subscribingBuild("1.0.0", ""));
            runner("fetchApiSpec").build();

            BuildResult second = runner("fetchApiSpec").build();

            assertThat(second.task(":fetchApiSpecUserAccount").getOutcome()).isEqualTo(TaskOutcome.UP_TO_DATE);
        }

        @Test
        @DisplayName("refuses a pre-release version unless the subscription opts in")
        void prereleaseIsRefusedByDefault() throws Exception {
            publish("user-account", "1.1.0-rc.1", OPENAPI);
            buildFile(subscribingBuild("1.1.0-rc.1", ""));

            BuildResult result = runner("fetchApiSpec").buildAndFail();

            assertThat(result.getOutput())
                .contains("pre-release version 1.1.0-rc.1")
                .contains("allowPrerelease = true");
        }

        @Test
        @DisplayName("accepts a pre-release when the subscription opts in")
        void prereleaseIsAllowedWhenOptedIn() throws Exception {
            publish("user-account", "1.1.0-rc.1", OPENAPI);
            buildFile(subscribingBuild("1.1.0-rc.1", "allowPrerelease = true"));

            BuildResult result = runner("fetchApiSpec").build();

            assertThat(result.task(":fetchApiSpecUserAccount").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        }

        @Test
        @DisplayName("refuses an archive whose contents do not match its own manifest")
        void archiveMustMatchItsOwnManifest() throws Exception {
            publish("user-account", "1.0.0", OPENAPI);
            // Repack the archive with a document the manifest no longer describes.
            Path tampered = Files.createTempDirectory("tampered");
            Process untar = new ProcessBuilder("tar", "-xzf",
                channelDir.resolve("user-account/1.0.0/user-account-1.0.0.tgz").toString())
                .directory(tampered.toFile()).inheritIO().start();
            assertThat(untar.waitFor()).isZero();
            Files.writeString(tampered.resolve("openapi.yaml"), OPENAPI + "# tampered\n");
            Process retar = new ProcessBuilder("tar", "-czf",
                channelDir.resolve("user-account/1.0.0/user-account-1.0.0.tgz").toString(),
                "openapi.yaml", "manifest.json")
                .directory(tampered.toFile()).inheritIO().start();
            assertThat(retar.waitFor()).isZero();

            buildFile(subscribingBuild("1.0.0", ""));
            BuildResult result = runner("fetchApiSpec").buildAndFail();

            assertThat(result.getOutput()).contains("does not match the hash its own manifest declares");
        }

        @Test
        @DisplayName("refuses to re-lock when a released version comes back with different bytes")
        void aMovedTagIsRefused() throws Exception {
            publish("user-account", "1.0.0", OPENAPI);
            buildFile(subscribingBuild("1.0.0", ""));
            runner("fetchApiSpec").build();

            // The same coordinates, republished with different content: a rebuilt
            // release, or a tag moved under the build's feet.
            publish("user-account", "1.0.0", OPENAPI + "# a server added after 1.0.0\n");

            BuildResult result = runner("fetchApiSpec", "--rerun-tasks").buildAndFail();

            assertThat(result.getOutput())
                .contains("is not the one recorded in apionly.lock")
                .contains("A released version was rebuilt, or a tag was moved");
        }

        @Test
        @DisplayName("a deliberate upgrade updates the lock rather than failing")
        void upgradingVersionRelocks() throws Exception {
            publish("user-account", "1.0.0", OPENAPI);
            buildFile(subscribingBuild("1.0.0", ""));
            runner("fetchApiSpec").build();

            publish("user-account", "1.1.0", OPENAPI + "# genuinely new\n");
            buildFile(subscribingBuild("1.1.0", ""));
            BuildResult result = runner("fetchApiSpec").build();

            assertThat(result.getOutput()).contains("Updated user-account from 1.0.0 to 1.1.0");
            assertThat(lockfile()).contains("version 1.1.0");
        }
    }

    @Nested
    @DisplayName("verifying")
    class Verifying {

        @Test
        @DisplayName("fails when a fetched document is edited by hand")
        void handEditIsCaught() throws Exception {
            publish("user-account", "1.0.0", OPENAPI);
            buildFile(subscribingBuild("1.0.0", ""));
            runner("fetchApiSpec").build();

            Files.writeString(fetched("openapi.yaml"), OPENAPI + "# somebody edited this\n");

            BuildResult result = runner("verifyApiSpec").buildAndFail();

            assertThat(result.getOutput())
                .contains("has drifted from apionly.lock")
                .contains("openapi.yaml has changed since it was locked");
        }

        @Test
        @DisplayName("does not quietly repair that edit by re-fetching first")
        void verifyDoesNotRefetch() throws Exception {
            // If verify depended on fetch, the edit above would be overwritten
            // before the check ran and the build would go green -- hiding exactly
            // the drift the task exists to report.
            publish("user-account", "1.0.0", OPENAPI);
            buildFile(subscribingBuild("1.0.0", ""));
            runner("fetchApiSpec").build();
            Files.writeString(fetched("openapi.yaml"), OPENAPI + "# somebody edited this\n");

            BuildResult result = runner("verifyApiSpec").buildAndFail();

            assertThat(result.task(":fetchApiSpecUserAccount"))
                .as("verify must not drag fetch into the graph")
                .isNull();
        }

        @Test
        @DisplayName("says what to do when nothing has been fetched yet")
        void verifyBeforeAnyFetch() throws Exception {
            publish("user-account", "1.0.0", OPENAPI);
            buildFile(subscribingBuild("1.0.0", ""));

            BuildResult result = runner("verifyApiSpec").buildAndFail();

            assertThat(result.getOutput())
                .contains("nothing has been fetched for 'user-account' yet")
                .contains("Run fetchApiSpec first");
        }

        @Test
        @DisplayName("says what to do when there is no lockfile at all")
        void missingLockEntryIsCaught() throws Exception {
            publish("user-account", "1.0.0", OPENAPI);
            buildFile(subscribingBuild("1.0.0", ""));
            runner("fetchApiSpec").build();
            Files.delete(projectDir.resolve("apionly.lock"));

            BuildResult result = runner("verifyApiSpec").buildAndFail();

            assertThat(result.getOutput())
                .contains("there is no apionly.lock in this project")
                .contains("Run fetchApiSpec to create one, and commit it");
        }
    }

    @Nested
    @DisplayName("Gradle features")
    class Features {

        @Test
        @DisplayName("works with the configuration cache")
        void configurationCacheIsSupported() throws Exception {
            publish("user-account", "1.0.0", OPENAPI);
            buildFile(subscribingBuild("1.0.0", ""));

            runner("fetchApiSpec", "--configuration-cache").build();
            BuildResult reused = runner("fetchApiSpec", "--configuration-cache").build();

            assertThat(reused.getOutput()).contains("Reusing configuration cache");
        }
    }
}
