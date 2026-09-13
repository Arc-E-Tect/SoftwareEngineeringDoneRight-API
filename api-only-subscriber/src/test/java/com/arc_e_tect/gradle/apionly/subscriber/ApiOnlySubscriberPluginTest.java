package com.arc_e_tect.gradle.apionly.subscriber;

import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.SourceSet;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * In-process tests, via {@link ProjectBuilder}.
 *
 * <p>These exist alongside the TestKit suite rather than instead of it, and the
 * division is deliberate. TestKit runs a real build in a separate process, which
 * is the only way to see what a consumer actually sees -- and also the reason it
 * contributes nothing to coverage, since the code under test never runs in this
 * JVM. These tests run the same code in-process, where both the assertions and
 * the coverage instrumentation can reach it.</p>
 *
 * <p>Neither kind is sufficient alone: ProjectBuilder does not enforce every
 * runtime restriction a real build does, and TestKit cannot tell you which branch
 * went unexercised.</p>
 */
@DisplayName("ApiOnlySubscriberPlugin (in process)")
class ApiOnlySubscriberPluginTest {

    @TempDir Path projectDir;
    Project project;

    @BeforeEach
    void setUp() {
        project = ProjectBuilder.builder().withProjectDir(projectDir.toFile()).build();
        project.getPlugins().apply(ApiOnlySubscriberPlugin.class);
    }

    private ApiOnlySubscriberExtension extension() {
        return project.getExtensions().getByType(ApiOnlySubscriberExtension.class);
    }

    /** The task names a task actually depends on, resolved rather than stringified. */
    private java.util.List<String> dependencyNamesOf(String taskName) {
        Task task = project.getTasks().getByName(taskName);
        return task.getTaskDependencies().getDependencies(task).stream().map(Task::getName).sorted().toList();
    }

    /** macOS resolves /var through a symlink, so compare canonical paths. */
    private static java.io.File canonical(java.io.File file) {
        try { return file.getCanonicalFile(); } catch (java.io.IOException e) { return file.getAbsoluteFile(); }
    }

    private Subscription subscribe(String target, String version) {
        return extension().subscribe(target, s -> s.getVersion().set(version));
    }

    @Nested
    @DisplayName("applying the plugin")
    class Applying {

        @Test
        @DisplayName("creates the extension under its documented name")
        void createsExtension() {
            assertThat(project.getExtensions().findByName("apiOnlySubscriber")).isNotNull();
            assertThat(extension()).isNotNull();
        }

        @Test
        @DisplayName("registers the two aggregate tasks")
        void registersAggregateTasks() {
            assertThat(project.getTasks().findByName("fetchApiSpec")).isNotNull();
            assertThat(project.getTasks().findByName("verifyApiSpec")).isNotNull();
        }

        @Test
        @DisplayName("puts them in groups a person browsing tasks would look in")
        void tasksAreGrouped() {
            assertThat(project.getTasks().getByName("fetchApiSpec").getGroup()).isEqualTo("api-only");
            assertThat(project.getTasks().getByName("verifyApiSpec").getGroup()).isEqualTo("verification");
        }

        @Test
        @DisplayName("defaults the lockfile to apionly.lock beside the build file")
        void lockfileConvention() {
            assertThat(canonical(extension().getLockfile().get().getAsFile()))
                .isEqualTo(canonical(new File(projectDir.toFile(), "apionly.lock")));
        }

        @Test
        @DisplayName("registers no per-target task until something is subscribed")
        void noSubscriptionsNoTasks() {
            assertThat(project.getTasks().findByName("fetchApiSpecUserAccount")).isNull();
        }
    }

    @Nested
    @DisplayName("a subscription")
    class Subscribing {

        @Test
        @DisplayName("registers a fetch and a verify task named after the target")
        void registersPerTargetTasks() {
            subscribe("user-account", "1.0.0");

            assertThat(project.getTasks().findByName("fetchApiSpecUserAccount")).isNotNull();
            assertThat(project.getTasks().findByName("verifyApiSpecUserAccount")).isNotNull();
        }

        @Test
        @DisplayName("turns every separator in a target name into a camel-case task suffix")
        void capitalizesAcrossSeparators() {
            subscribe("user-account", "1.0.0");
            subscribe("auth_server", "1.0.0");
            subscribe("billing.api", "1.0.0");

            assertThat(project.getTasks().findByName("fetchApiSpecUserAccount")).isNotNull();
            assertThat(project.getTasks().findByName("fetchApiSpecAuthServer")).isNotNull();
            assertThat(project.getTasks().findByName("fetchApiSpecBillingApi")).isNotNull();
        }

        @Test
        @DisplayName("lands in build/, not in src/")
        void fetchDestinationConvention() {
            Subscription subscription = subscribe("user-account", "1.0.0");

            assertThat(canonical(subscription.getInto().get().getAsFile()))
                .isEqualTo(canonical(new File(projectDir.toFile(), "build/api-spec/user-account")));
        }

        @Test
        @DisplayName("defaults its artifact name to the target name")
        void artifactIdConvention() {
            assertThat(subscribe("user-account", "1.0.0").getArtifactId().get()).isEqualTo("user-account");
        }

        @Test
        @DisplayName("refuses pre-releases unless asked otherwise")
        void prereleaseConvention() {
            assertThat(subscribe("user-account", "1.0.0").getAllowPrerelease().get()).isFalse();
        }

        @Test
        @DisplayName("is retrievable by name, and an unknown name lists what does exist")
        void lookup() {
            subscribe("user-account", "1.0.0");

            assertThat(extension().subscription("user-account").getTarget()).isEqualTo("user-account");
            assertThatThrownBy(() -> extension().subscription("nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no subscription for 'nope'")
                .hasMessageContaining("user-account");
        }

        @Test
        @DisplayName("subscribing the same target twice configures one subscription, not two")
        void subscribingIsIdempotent() {
            extension().subscribe("user-account", s -> s.getVersion().set("1.0.0"));
            extension().subscribe("user-account", s -> s.getVersion().set("2.0.0"));

            assertThat(extension().getSubscriptions()).hasSize(1);
            assertThat(extension().subscription("user-account").getVersion().get()).isEqualTo("2.0.0");
        }

        @Test
        @DisplayName("can be created without configuring it")
        void subscribeWithoutAction() {
            Subscription subscription = extension().subscribe("user-account");

            assertThat(subscription.getName()).isEqualTo("user-account");
            assertThat(subscription.getTarget()).isEqualTo("user-account");
        }

        @Test
        @DisplayName("exposes the documents it will fetch, as providers")
        void exposesDocuments() {
            Subscription subscription = subscribe("user-account", "1.0.0");

            assertThat(subscription.getOpenapi().get().getAsFile().getName()).isEqualTo("openapi.yaml");
            assertThat(subscription.getAsyncapi().get().getAsFile().getName()).isEqualTo("asyncapi.yaml");
        }

        @Test
        @DisplayName("the aggregate tasks depend on every per-target task")
        void aggregatesDependOnEachTarget() {
            subscribe("user-account", "1.0.0");
            subscribe("auth-server", "1.0.0");

            assertThat(dependencyNamesOf("fetchApiSpec"))
                .contains("fetchApiSpecUserAccount", "fetchApiSpecAuthServer");
            assertThat(dependencyNamesOf("verifyApiSpec"))
                .contains("verifyApiSpecUserAccount", "verifyApiSpecAuthServer");
        }
    }

    @Nested
    @DisplayName("integration with other plugins")
    class Integration {

        @Test
        @DisplayName("the fetched directory becomes a resource directory when java is applied")
        void registersResourceDirectory() {
            project.getPlugins().apply("java");
            Subscription subscription = subscribe("user-account", "1.0.0");

            SourceSet main = project.getExtensions().getByType(JavaPluginExtension.class)
                .getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);

            assertThat(main.getResources().getSrcDirs())
                .contains(subscription.getInto().get().getAsFile());
        }

        @Test
        @DisplayName("processResources waits for the fetch")
        void processResourcesDependsOnFetch() {
            project.getPlugins().apply("java");
            subscribe("user-account", "1.0.0");

            assertThat(dependencyNamesOf("processResources")).contains("fetchApiSpecUserAccount");
        }

        @Test
        @DisplayName("check fails the build on drift, without anyone remembering to ask")
        void checkDependsOnVerify() {
            project.getPlugins().apply("base");
            subscribe("user-account", "1.0.0");

            assertThat(dependencyNamesOf("check")).contains("verifyApiSpec");
        }

        @Test
        @DisplayName("works without the java plugin at all")
        void javaIsOptional() {
            subscribe("user-account", "1.0.0");

            assertThat(project.getTasks().findByName("fetchApiSpecUserAccount")).isNotNull();
            assertThat(project.getPlugins().hasPlugin("java")).isFalse();
        }
    }

    @Nested
    @DisplayName("resolving where an archive comes from")
    class Resolving {

        /** Forces the lazily-resolved archive to be evaluated, so its errors surface. */
        private Throwable resolving(String target) {
            FetchApiSpecTask task = (FetchApiSpecTask) project.getTasks()
                .getByName("fetchApiSpec" + target.substring(0, 1).toUpperCase() + target.substring(1));
            return org.assertj.core.api.Assertions.catchThrowable(() -> task.getArchive().getFiles());
        }

        @Test
        @DisplayName("a subscription with no version says so, and names itself")
        void versionIsRequired() {
            extension().getChannel().getType().set("file");
            extension().getChannel().getDirectory().set(projectDir.toString());
            extension().subscribe("account", s -> { });

            assertThat(resolving("account"))
                .hasMessageContaining("subscription 'account' declares no version")
                .hasMessageContaining("apiOnlySubscriber")
                .hasMessageContaining("apiContractVersion");
        }

        @Test
        @DisplayName("a pre-release is refused, and the message says how to accept one deliberately")
        void prereleaseRefused() {
            extension().getChannel().getType().set("file");
            extension().getChannel().getDirectory().set(projectDir.toString());
            subscribe("account", "1.1.0-rc.1");

            assertThat(resolving("account"))
                .hasMessageContaining("pre-release version 1.1.0-rc.1")
                .hasMessageContaining("allowPrerelease = true");
        }

        @Test
        @DisplayName("the file channel needs a directory")
        void fileChannelNeedsDirectory() {
            extension().getChannel().getType().set("file");
            subscribe("account", "1.0.0");

            assertThat(resolving("account")).hasMessageContaining("requires channel.directory");
        }

        @Test
        @DisplayName("the file channel resolves where the archive belongs, before anything is published there")
        void fileChannelResolvesPathBeforePublication() {
            // A build may publish the archive itself, in a task that runs before the
            // fetch. Planning that build must not require the archive to exist yet:
            // the fetch checks for it when it runs.
            extension().getChannel().getType().set("file");
            extension().getChannel().getDirectory().set(projectDir.toString());
            subscribe("account", "1.0.0");

            assertThat(resolving("account")).isNull();
            FetchApiSpecTask task = (FetchApiSpecTask) project.getTasks().getByName("fetchApiSpecAccount");
            assertThat(canonical(task.getArchive().getSingleFile()))
                .isEqualTo(canonical(projectDir.resolve("account/1.0.0/account-1.0.0.tgz").toFile()));
        }

        @Test
        @DisplayName("the maven channel needs a groupId")
        void mavenChannelNeedsGroupId() {
            subscribe("account", "1.0.0");

            assertThat(resolving("account")).hasMessageContaining("requires channel.groupId");
        }

        @Test
        @DisplayName("an unknown channel names the ones that exist")
        void unknownChannelType() {
            extension().getChannel().getType().set("carrier-pigeon");
            subscribe("account", "1.0.0");

            assertThat(resolving("account"))
                .hasMessageContaining("unknown channel type 'carrier-pigeon'")
                .hasMessageContaining("'maven' and 'file'");
        }

        @Test
        @DisplayName("a per-subscription groupId overrides the channel's")
        void subscriptionGroupIdWins() {
            extension().getChannel().getGroupId().set("com.example.all");
            extension().subscribe("account", s -> {
                s.getVersion().set("1.0.0");
                s.getGroupId().set("com.example.special");
            });

            // Resolution reaches the repository and fails there, not on configuration:
            // the point is that a groupId was found at all.
            assertThat(resolving("account")).hasMessageNotContaining("requires channel.groupId");
        }
    }

    @Nested
    @DisplayName("the contract version")
    class Versions {

        @Test
        @DisplayName("a subscription that sets no version takes the one set on apiOnlySubscriber")
        void subscriptionTakesTheProjectVersion() {
            extension().getVersion().set("2.0.0");

            assertThat(extension().subscribe("account").getVersion().get()).isEqualTo("2.0.0");
        }

        @Test
        @DisplayName("a subscription's own version wins over the one set on apiOnlySubscriber")
        void subscriptionVersionWins() {
            extension().getVersion().set("2.0.0");

            assertThat(subscribe("account", "1.4.0").getVersion().get()).isEqualTo("1.4.0");
        }

        @Test
        @DisplayName("apiOnlySubscriber's version defaults to the apiContractVersion project property")
        void versionDefaultsToProjectProperty() {
            project.getExtensions().getExtraProperties().set("apiContractVersion", "3.1.0");

            assertThat(extension().getVersion().get()).isEqualTo("3.1.0");
            assertThat(extension().subscribe("account").getVersion().get()).isEqualTo("3.1.0");
        }

        @Test
        @DisplayName("with no version anywhere, a subscription's version stays unset")
        void noVersionAnywhere() {
            assertThat(extension().getVersion().isPresent()).isFalse();
            assertThat(extension().subscribe("account").getVersion().isPresent()).isFalse();
        }
    }
}
