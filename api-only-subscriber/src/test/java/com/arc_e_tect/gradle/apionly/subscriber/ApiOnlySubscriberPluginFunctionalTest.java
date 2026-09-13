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

    /** Two APIs the project calls, next to the contract subscribingBuild implements. */
    private static final String CALLS_TWO_APIS = """

            apiOnlySubscriber {
                subscribeAsClient('order-payments') {
                    version = '1.4.0'
                }
                subscribeAsClient('billing-api') {
                    version = '2.0.0'
                }
            }
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
                subscribe('customer-orders') {
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
        return projectDir.resolve("build/api-spec/customer-orders").resolve(name);
    }

    private String lockfile() throws IOException {
        return Files.readString(projectDir.resolve("apionly.lock"));
    }

    // ------------------------------------------------------------------ tests

    @Nested
    @DisplayName("the DSL and the task graph")
    class Wiring {

        @Test
        @DisplayName("a second contract in one project fails the build, saying why and where to read more")
        void aSecondContractFailsTheBuild() throws IOException {
            buildFile(subscribingBuild("1.0.0", "") + """

                apiOnlySubscriber {
                    subscribe('order-payments') {
                        version = '1.0.0'
                    }
                }
                """);

            BuildResult result = runner("help").buildAndFail();

            assertThat(result.getOutput())
                .contains("apiOnlySubscriber already implements 'customer-orders', so it cannot also implement 'order-payments'.")
                .contains("declare it with subscribeAsClient('order-payments') instead")
                .contains("api-only-subscriber/README.adoc#one-contract-per-project");
        }

        @Test
        @DisplayName("a project implementing one contract and calling two APIs fetches, locks and verifies all three")
        void clientsNextToTheImplementedContract() throws Exception {
            publish("customer-orders", "1.0.0", OPENAPI);
            publish("order-payments", "1.4.0", OPENAPI.replace("title: Example", "title: Payments"));
            publish("billing-api", "2.0.0", OPENAPI.replace("title: Example", "title: Billing"));
            buildFile(subscribingBuild("1.0.0", "") + CALLS_TWO_APIS);

            BuildResult first = runner("check", "--configuration-cache").build();

            assertThat(first.task(":verifyApiSpecCustomerOrders").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
            assertThat(first.task(":verifyApiSpecOrderPayments").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
            assertThat(first.task(":verifyApiSpecBillingApi").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
            assertThat(lockfile())
                .contains("target customer-orders\nversion 1.0.0")
                .contains("target order-payments\nversion 1.4.0")
                .contains("target billing-api\nversion 2.0.0");
            // The implemented contract at the classpath root; each called API under contracts/<target>/.
            Path resources = projectDir.resolve("build/resources/main");
            assertThat(Files.readString(resources.resolve("openapi.yaml"))).contains("title: Example");
            assertThat(Files.readString(resources.resolve("contracts/order-payments/openapi.yaml")))
                .contains("title: Payments");
            assertThat(resources.resolve("contracts/order-payments/manifest.json")).exists();
            assertThat(Files.readString(resources.resolve("contracts/billing-api/openapi.yaml")))
                .contains("title: Billing");

            BuildResult second = runner("check", "--configuration-cache").build();

            assertThat(second.task(":fetchApiSpecCustomerOrders").getOutcome()).isEqualTo(TaskOutcome.UP_TO_DATE);
            assertThat(second.task(":fetchApiSpecOrderPayments").getOutcome()).isEqualTo(TaskOutcome.UP_TO_DATE);
            assertThat(second.task(":fetchApiSpecBillingApi").getOutcome()).isEqualTo(TaskOutcome.UP_TO_DATE);
        }

        @Test
        @DisplayName("a called API gets every guard: a hand edit fails verification, and changed bytes under a locked version are refused")
        void clientsGetEveryGuard() throws Exception {
            publish("customer-orders", "1.0.0", OPENAPI);
            publish("order-payments", "1.4.0", OPENAPI.replace("title: Example", "title: Payments"));
            publish("billing-api", "2.0.0", OPENAPI.replace("title: Example", "title: Billing"));
            buildFile(subscribingBuild("1.0.0", "") + CALLS_TWO_APIS);
            runner("fetchApiSpec").build();

            Path payments = projectDir.resolve("build/api-spec/order-payments/openapi.yaml");
            Files.writeString(payments, Files.readString(payments) + "# edited by hand\n");
            assertThat(runner("verifyApiSpec").buildAndFail().getOutput())
                .contains("the contract for 'order-payments' has drifted from apionly.lock");

            publish("order-payments", "1.4.0", OPENAPI.replace("title: Example", "title: Payments, rebuilt"));
            assertThat(runner("fetchApiSpec").buildAndFail().getOutput())
                .contains("the published contract for 'order-payments' 1.4.0 is not the one recorded in apionly.lock");
        }

        @Test
        @DisplayName("registers an aggregate task and a per-target task for each subscription")
        void registersTasks() throws Exception {
            publish("customer-orders", "1.0.0", OPENAPI);
            buildFile(subscribingBuild("1.0.0", ""));

            BuildResult result = runner("tasks", "--group", "api-only").build();

            assertThat(result.getOutput())
                .contains("fetchApiSpec")
                .contains("fetchApiSpecCustomerOrders");
        }

        @Test
        @DisplayName("updates missing defaulted DSL properties and keeps a backup")
        void updatesDsl() throws Exception {
            buildFile(subscribingBuild("1.0.0", ""));

            BuildResult result = runner("updateApiOnlySubscriberDSL").build();

            assertThat(result.getOutput()).contains("added 2 missing properties");
            assertThat(projectDir.resolve("build.gradle.bak")).exists();
            assertThat(Files.readString(projectDir.resolve("build.gradle")))
                .contains("lockfile = layout.projectDirectory.file('apionly.lock')");
        }

        @Test
        @DisplayName("a subscription with no version is refused, naming the subscription")
        void versionIsRequired() throws Exception {
            buildFile(subscribingBuild("1.0.0", "").replace("version = '1.0.0'", ""));

            BuildResult result = runner("fetchApiSpec").buildAndFail();

            assertThat(result.getOutput()).contains("declares no version").contains("apiContractVersion");
        }

        @Test
        @DisplayName("verifyApiSpec is wired into check, so drift fails an ordinary build")
        void verifyRunsAsPartOfCheck() throws Exception {
            publish("customer-orders", "1.0.0", OPENAPI);
            buildFile(subscribingBuild("1.0.0", ""));
            runner("fetchApiSpec").build();

            BuildResult result = runner("check").build();

            assertThat(result.task(":verifyApiSpecCustomerOrders")).isNotNull();
            assertThat(result.task(":verifyApiSpecCustomerOrders").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        }

        @Test
        @DisplayName("the fetched document reaches the classpath without living under src/")
        void fetchedDocumentIsAResource() throws Exception {
            publish("customer-orders", "1.0.0", OPENAPI);
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
            publish("customer-orders", "1.0.0", OPENAPI);
            buildFile(subscribingBuild("1.0.0", "") + """

                abstract class ConsumeContract extends DefaultTask {
                    @InputFile abstract RegularFileProperty getContract()
                    @TaskAction void go() { println "read ${getContract().get().asFile.name}" }
                }

                tasks.register('consumeContract', ConsumeContract) {
                    contract = apiOnlySubscriber.subscription('customer-orders').openapi
                }
                """);

            BuildResult result = runner("consumeContract").build();

            assertThat(result.task(":fetchApiSpecCustomerOrders").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
            assertThat(result.getOutput()).contains("read openapi.yaml");
        }
    }

    @Nested
    @DisplayName("fetching")
    class Fetching {

        @Test
        @DisplayName("unpacks into build/, and records what it unpacked")
        void fetchWritesDocumentsAndLockfile() throws Exception {
            publish("customer-orders", "1.0.0", OPENAPI);
            buildFile(subscribingBuild("1.0.0", ""));

            BuildResult result = runner("fetchApiSpec").build();

            assertThat(result.task(":fetchApiSpecCustomerOrders").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
            assertThat(fetched("openapi.yaml")).exists();
            assertThat(fetched("manifest.json")).exists();
            assertThat(lockfile())
                .contains("target customer-orders")
                .contains("version 1.0.0")
                .contains("channel file")
                .contains("openapi.yaml");
        }

        @Test
        @DisplayName("is up to date on a second run")
        void fetchIsUpToDate() throws Exception {
            publish("customer-orders", "1.0.0", OPENAPI);
            buildFile(subscribingBuild("1.0.0", ""));
            runner("fetchApiSpec").build();

            BuildResult second = runner("fetchApiSpec").build();

            assertThat(second.task(":fetchApiSpecCustomerOrders").getOutcome()).isEqualTo(TaskOutcome.UP_TO_DATE);
        }

        @Test
        @DisplayName("fetches again when its entry is gone from apionly.lock, and records it")
        void refetchesWhenTheLockEntryIsGone() throws Exception {
            publish("customer-orders", "1.0.0", OPENAPI);
            buildFile(subscribingBuild("1.0.0", ""));
            runner("fetchApiSpec").build();
            Files.delete(projectDir.resolve("apionly.lock"));

            BuildResult again = runner("fetchApiSpec").build();

            assertThat(again.task(":fetchApiSpecCustomerOrders").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
            assertThat(lockfile()).contains("target customer-orders");
        }

        @Test
        @DisplayName("refuses a pre-release version unless the subscription opts in")
        void prereleaseIsRefusedByDefault() throws Exception {
            publish("customer-orders", "1.1.0-rc.1", OPENAPI);
            buildFile(subscribingBuild("1.1.0-rc.1", ""));

            BuildResult result = runner("fetchApiSpec").buildAndFail();

            assertThat(result.getOutput())
                .contains("pre-release version 1.1.0-rc.1")
                .contains("allowPrerelease = true");
        }

        @Test
        @DisplayName("accepts a pre-release when the subscription opts in")
        void prereleaseIsAllowedWhenOptedIn() throws Exception {
            publish("customer-orders", "1.1.0-rc.1", OPENAPI);
            buildFile(subscribingBuild("1.1.0-rc.1", "allowPrerelease = true"));

            BuildResult result = runner("fetchApiSpec").build();

            assertThat(result.task(":fetchApiSpecCustomerOrders").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        }

        @Test
        @DisplayName("refuses an archive whose contents do not match its own manifest")
        void archiveMustMatchItsOwnManifest() throws Exception {
            publish("customer-orders", "1.0.0", OPENAPI);
            // Repack the archive with a document the manifest no longer describes.
            Path tampered = Files.createTempDirectory("tampered");
            Process untar = new ProcessBuilder("tar", "-xzf",
                channelDir.resolve("customer-orders/1.0.0/customer-orders-1.0.0.tgz").toString())
                .directory(tampered.toFile()).inheritIO().start();
            assertThat(untar.waitFor()).isZero();
            Files.writeString(tampered.resolve("openapi.yaml"), OPENAPI + "# tampered\n");
            Process retar = new ProcessBuilder("tar", "-czf",
                channelDir.resolve("customer-orders/1.0.0/customer-orders-1.0.0.tgz").toString(),
                "openapi.yaml", "manifest.json")
                .directory(tampered.toFile()).inheritIO().start();
            assertThat(retar.waitFor()).isZero();

            buildFile(subscribingBuild("1.0.0", ""));
            BuildResult result = runner("fetchApiSpec").buildAndFail();

            assertThat(result.getOutput()).contains("does not match the hash its own manifest declares");
        }

        @Test
        @DisplayName("refuses to re-lock when a version is republished with different bytes")
        void aRepublishedVersionIsRefused() throws Exception {
            publish("customer-orders", "1.0.0", OPENAPI);
            buildFile(subscribingBuild("1.0.0", ""));
            runner("fetchApiSpec").build();

            // The same version, published to the file channel again with different content.
            publish("customer-orders", "1.0.0", OPENAPI + "# a server added after 1.0.0\n");

            BuildResult result = runner("fetchApiSpec", "--rerun-tasks").buildAndFail();

            assertThat(result.getOutput())
                .contains("is not the one recorded in apionly.lock")
                .contains("1.0.0 was published to the file channel again, with different content")
                .doesNotContain("tag was moved");
        }

        @Test
        @DisplayName("a project's gradle.properties sets the version its subscriptions default to, and -P overrides it")
        void versionFromProjectProperty() throws Exception {
            // A subproject declaring its own version, as a service in a multi-project
            // build does. Its gradle.properties is visible to that project only:
            // providers.gradleProperty(...) does not see it, which is what this proves
            // the plugin does not rely on.
            publish("customer-orders", "1.0.0", OPENAPI);
            publish("customer-orders", "2.0.0", OPENAPI.replace("version: 1.0.0", "version: 2.0.0"));
            Files.writeString(projectDir.resolve("settings.gradle"), "rootProject.name = 'consumer'\ninclude 'svc'\n");
            Path svc = Files.createDirectories(projectDir.resolve("svc"));
            Files.writeString(svc.resolve("gradle.properties"), "apiContractVersion=1.0.0\n");
            Files.writeString(svc.resolve("build.gradle"), """
                plugins {
                    id 'java'
                    id 'com.arc-e-tect.api-only-subscriber'
                }

                apiOnlySubscriber {
                    channel {
                        type = 'file'
                        directory = '%s'
                    }
                    subscribe('customer-orders')
                }
                """.formatted(channelDir.toString().replace("\\", "\\\\")));

            runner(":svc:fetchApiSpec", "--configuration-cache").build();
            assertThat(Files.readString(svc.resolve("apionly.lock"))).contains("version 1.0.0");

            BuildResult upgraded =
                runner(":svc:fetchApiSpec", "--configuration-cache", "-PapiContractVersion=2.0.0").build();
            assertThat(upgraded.getOutput()).contains("Updated customer-orders from 1.0.0 to 2.0.0");
        }

        @Test
        @DisplayName("fetches an archive that a task in the same build publishes first")
        void fetchesWhatTheSameBuildPublishes() throws Exception {
            // A library and its consumers can share one build: a task publishes to the
            // file channel, and the fetch depends on it. Planning must not require the
            // archive before that task has run.
            publish("customer-orders", "1.0.0", OPENAPI);
            Path staged = Files.createTempDirectory("api-only-staged");
            Files.move(channelDir.resolve("customer-orders"), staged.resolve("customer-orders"));
            buildFile(subscribingBuild("1.0.0", "") + """

                def publishContract = tasks.register('publishContract', Copy) {
                    from '%s'
                    into '%s'
                }
                tasks.named('fetchApiSpecCustomerOrders') { dependsOn publishContract }
                """.formatted(
                    staged.toString().replace("\\", "\\\\"),
                    channelDir.toString().replace("\\", "\\\\")));

            BuildResult result = runner("fetchApiSpec", "--configuration-cache").build();

            assertThat(result.task(":publishContract").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
            assertThat(result.task(":fetchApiSpecCustomerOrders").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
            assertThat(lockfile()).contains("customer-orders");
        }

        @Test
        @DisplayName("a version the file channel does not hold fails the fetch, naming the versions it does")
        void missingVersionNamesPublishedOnes() throws Exception {
            publish("customer-orders", "1.0.0", OPENAPI);
            buildFile(subscribingBuild("2.0.0", ""));

            BuildResult result = runner("fetchApiSpec").buildAndFail();

            assertThat(result.task(":fetchApiSpecCustomerOrders").getOutcome()).isEqualTo(TaskOutcome.FAILED);
            assertThat(result.getOutput())
                .contains("no archive for 'customer-orders' 2.0.0")
                .contains("Published versions of 'customer-orders': 1.0.0.");
        }

        @Test
        @DisplayName("a deliberate upgrade updates the lock rather than failing")
        void upgradingVersionRelocks() throws Exception {
            publish("customer-orders", "1.0.0", OPENAPI);
            buildFile(subscribingBuild("1.0.0", ""));
            runner("fetchApiSpec").build();

            publish("customer-orders", "1.1.0", OPENAPI + "# genuinely new\n");
            buildFile(subscribingBuild("1.1.0", ""));
            BuildResult result = runner("fetchApiSpec").build();

            assertThat(result.getOutput()).contains("Updated customer-orders from 1.0.0 to 1.1.0");
            assertThat(lockfile()).contains("version 1.1.0");
        }
    }

    @Nested
    @DisplayName("verifying")
    class Verifying {

        @Test
        @DisplayName("fails when a fetched document is edited by hand")
        void handEditIsCaught() throws Exception {
            publish("customer-orders", "1.0.0", OPENAPI);
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
            publish("customer-orders", "1.0.0", OPENAPI);
            buildFile(subscribingBuild("1.0.0", ""));
            runner("fetchApiSpec").build();
            Files.writeString(fetched("openapi.yaml"), OPENAPI + "# somebody edited this\n");

            BuildResult result = runner("verifyApiSpec").buildAndFail();

            assertThat(result.task(":fetchApiSpecCustomerOrders"))
                .as("verify must not drag fetch into the graph")
                .isNull();
        }

        @Test
        @DisplayName("says what to do when nothing has been fetched yet")
        void verifyBeforeAnyFetch() throws Exception {
            publish("customer-orders", "1.0.0", OPENAPI);
            buildFile(subscribingBuild("1.0.0", ""));

            BuildResult result = runner("verifyApiSpec").buildAndFail();

            assertThat(result.getOutput())
                .contains("nothing has been fetched for 'customer-orders' yet")
                .contains("Run fetchApiSpec first");
        }

        @Test
        @DisplayName("says what to do when there is no lockfile at all")
        void missingLockEntryIsCaught() throws Exception {
            publish("customer-orders", "1.0.0", OPENAPI);
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
            publish("customer-orders", "1.0.0", OPENAPI);
            buildFile(subscribingBuild("1.0.0", ""));

            runner("fetchApiSpec", "--configuration-cache").build();
            BuildResult reused = runner("fetchApiSpec", "--configuration-cache").build();

            assertThat(reused.getOutput()).contains("Reusing configuration cache");
        }
    }
}
