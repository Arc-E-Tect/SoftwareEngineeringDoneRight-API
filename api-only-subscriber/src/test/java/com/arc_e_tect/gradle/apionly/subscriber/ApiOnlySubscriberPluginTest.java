package com.arc_e_tect.gradle.apionly.subscriber;

import org.gradle.api.InvalidUserDataException;
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
            assertThat(project.getTasks().findByName("fetchApiSpecCustomerOrders")).isNull();
        }
    }

    @Nested
    @DisplayName("a subscription")
    class Subscribing {

        @Test
        @DisplayName("registers a fetch and a verify task named after the target")
        void registersPerTargetTasks() {
            subscribe("customer-orders", "1.0.0");

            assertThat(project.getTasks().findByName("fetchApiSpecCustomerOrders")).isNotNull();
            assertThat(project.getTasks().findByName("verifyApiSpecCustomerOrders")).isNotNull();
        }

        @Test
        @DisplayName("turns every separator in a target name into a camel-case task suffix")
        void capitalizesAcrossSeparators() {
            // One contract per project, so each target gets a project of its own.
            java.util.Map.of(
                "customer-orders", "fetchApiSpecCustomerOrders",
                "order_payments", "fetchApiSpecOrderPayments",
                "billing.api", "fetchApiSpecBillingApi"
            ).forEach((target, task) -> {
                Project own = ProjectBuilder.builder().build();
                own.getPlugins().apply(ApiOnlySubscriberPlugin.class);
                own.getExtensions().getByType(ApiOnlySubscriberExtension.class)
                    .subscribe(target, s -> s.getVersion().set("1.0.0"));

                assertThat(own.getTasks().findByName(task)).isNotNull();
            });
        }

        @Test
        @DisplayName("lands in build/, not in src/")
        void fetchDestinationConvention() {
            Subscription subscription = subscribe("customer-orders", "1.0.0");

            assertThat(canonical(subscription.getInto().get().getAsFile()))
                .isEqualTo(canonical(new File(projectDir.toFile(), "build/api-spec/customer-orders")));
        }

        @Test
        @DisplayName("defaults its artifact name to the target name")
        void artifactIdConvention() {
            assertThat(subscribe("customer-orders", "1.0.0").getArtifactId().get()).isEqualTo("customer-orders");
        }

        @Test
        @DisplayName("refuses pre-releases unless asked otherwise")
        void prereleaseConvention() {
            assertThat(subscribe("customer-orders", "1.0.0").getAllowPrerelease().get()).isFalse();
        }

        @Test
        @DisplayName("is retrievable by name, and an unknown name lists what does exist")
        void lookup() {
            subscribe("customer-orders", "1.0.0");

            assertThat(extension().subscription("customer-orders").getTarget()).isEqualTo("customer-orders");
            assertThatThrownBy(() -> extension().subscription("nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no subscription for 'nope'")
                .hasMessageContaining("customer-orders");
        }

        @Test
        @DisplayName("subscribing the same target twice configures one subscription, not two")
        void subscribingIsIdempotent() {
            extension().subscribe("customer-orders", s -> s.getVersion().set("1.0.0"));
            extension().subscribe("customer-orders", s -> s.getVersion().set("2.0.0"));

            assertThat(extension().getSubscriptions()).hasSize(1);
            assertThat(extension().subscription("customer-orders").getVersion().get()).isEqualTo("2.0.0");
        }

        @Test
        @DisplayName("a second contract in the same project is refused, saying why and where to read more")
        void aSecondContractIsRefused() {
            subscribe("customer-orders", "1.0.0");

            assertThatThrownBy(() -> subscribe("order-payments", "1.0.0"))
                .isInstanceOf(InvalidUserDataException.class)
                .hasMessageContaining(
                    "apiOnlySubscriber already implements 'customer-orders', so it cannot also implement 'order-payments'.")
                .hasMessageContaining("declare it with subscribeAsClient('order-payments') instead")
                .hasMessageContaining("put each contract in a project of its own, in a multi-project build")
                .hasMessageContaining("api-only-subscriber/README.adoc#one-contract-per-project");
            assertThatThrownBy(() -> extension().subscribe("order-payments"))
                .isInstanceOf(InvalidUserDataException.class);
            assertThat(extension().getSubscriptions().getNames()).containsExactly("customer-orders");
        }

        @Test
        @DisplayName("a second contract added to the container directly is refused as well")
        void aSecondContractInTheContainerIsRefused() {
            subscribe("customer-orders", "1.0.0");

            assertThatThrownBy(() -> extension().getSubscriptions().create("order-payments"))
                .hasStackTraceContaining(
                    "apiOnlySubscriber already implements 'customer-orders', so it cannot also implement 'order-payments'.");
        }

        @Test
        @DisplayName("a project may call any number of APIs, next to the one contract it implements")
        void clientsNextToTheImplementedContract() {
            subscribe("customer-orders", "1.0.0");
            Subscription payments = extension().subscribeAsClient("order-payments", s -> s.getVersion().set("1.4.0"));
            Subscription billing = extension().subscribeAsClient("billing-api", s -> s.getVersion().set("2.0.0"));

            assertThat(extension().subscription("customer-orders").isClient()).isFalse();
            assertThat(payments.isClient()).isTrue();
            assertThat(billing.isClient()).isTrue();
            assertThat(project.getTasks().findByName("fetchApiSpecOrderPayments")).isNotNull();
            assertThat(project.getTasks().findByName("verifyApiSpecBillingApi")).isNotNull();
        }

        @Test
        @DisplayName("a project may call APIs without implementing one")
        void clientsOnly() {
            extension().subscribeAsClient("order-payments", s -> s.getVersion().set("1.4.0"));
            extension().subscribeAsClient("billing-api");

            assertThat(extension().getSubscriptions()).hasSize(2).allMatch(Subscription::isClient);
        }

        @Test
        @DisplayName("subscribing to the same API as a client twice configures one subscription")
        void clientSubscribingIsIdempotent() {
            extension().subscribeAsClient("order-payments", s -> s.getVersion().set("1.0.0"));
            extension().subscribeAsClient("order-payments", s -> s.getVersion().set("2.0.0"));

            assertThat(extension().getSubscriptions()).hasSize(1);
            assertThat(extension().subscription("order-payments").getVersion().get()).isEqualTo("2.0.0");
        }

        @Test
        @DisplayName("a contract is either implemented or called, never both")
        void implementedOrCalled() {
            subscribe("customer-orders", "1.0.0");
            extension().subscribeAsClient("order-payments", s -> s.getVersion().set("1.0.0"));

            assertThatThrownBy(() -> extension().subscribeAsClient("customer-orders"))
                .isInstanceOf(InvalidUserDataException.class)
                .hasMessageContaining("implements 'customer-orders', so it cannot also subscribe to it as a client");
            assertThatThrownBy(() -> extension().subscribe("order-payments"))
                .isInstanceOf(InvalidUserDataException.class)
                .hasMessageContaining("calls 'order-payments' as a client, so it cannot also implement 'order-payments'");
        }

        @Test
        @DisplayName("can be created without configuring it")
        void subscribeWithoutAction() {
            Subscription subscription = extension().subscribe("customer-orders");

            assertThat(subscription.getName()).isEqualTo("customer-orders");
            assertThat(subscription.getTarget()).isEqualTo("customer-orders");
        }

        @Test
        @DisplayName("exposes the documents it will fetch, as providers")
        void exposesDocuments() {
            Subscription subscription = subscribe("customer-orders", "1.0.0");

            assertThat(subscription.getOpenapi().get().getAsFile().getName()).isEqualTo("openapi.yaml");
            assertThat(subscription.getAsyncapi().get().getAsFile().getName()).isEqualTo("asyncapi.yaml");
        }

        @Test
        @DisplayName("the aggregate tasks depend on the per-target task")
        void aggregatesDependOnTheTarget() {
            subscribe("customer-orders", "1.0.0");

            assertThat(dependencyNamesOf("fetchApiSpec")).contains("fetchApiSpecCustomerOrders");
            assertThat(dependencyNamesOf("verifyApiSpec")).contains("verifyApiSpecCustomerOrders");
        }
    }

    @Nested
    @DisplayName("integration with other plugins")
    class Integration {

        @Test
        @DisplayName("the fetched directory becomes a resource directory when java is applied")
        void registersResourceDirectory() {
            project.getPlugins().apply("java");
            Subscription subscription = subscribe("customer-orders", "1.0.0");

            SourceSet main = project.getExtensions().getByType(JavaPluginExtension.class)
                .getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);

            assertThat(main.getResources().getSrcDirs())
                .contains(subscription.getInto().get().getAsFile());
        }

        @Test
        @DisplayName("processResources waits for the fetch")
        void processResourcesDependsOnFetch() {
            project.getPlugins().apply("java");
            subscribe("customer-orders", "1.0.0");

            assertThat(dependencyNamesOf("processResources")).contains("fetchApiSpecCustomerOrders");
        }

        @Test
        @DisplayName("check fails the build on drift, without anyone remembering to ask")
        void checkDependsOnVerify() {
            project.getPlugins().apply("base");
            subscribe("customer-orders", "1.0.0");

            assertThat(dependencyNamesOf("check")).contains("verifyApiSpec");
        }

        @Test
        @DisplayName("works without the java plugin at all")
        void javaIsOptional() {
            subscribe("customer-orders", "1.0.0");

            assertThat(project.getTasks().findByName("fetchApiSpecCustomerOrders")).isNotNull();
            assertThat(project.getPlugins().hasPlugin("java")).isFalse();
        }

        @Test
        @DisplayName("an API the project calls is not a resources directory of its own, and processResources still waits for it")
        void clientDocumentsAreCopiedNotRooted() {
            project.getPlugins().apply("java");
            Subscription payments = extension().subscribeAsClient("order-payments", s -> s.getVersion().set("1.0.0"));

            SourceSet main = project.getExtensions().getByType(JavaPluginExtension.class)
                .getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);

            assertThat(main.getResources().getSrcDirs()).doesNotContain(payments.getInto().get().getAsFile());
            assertThat(dependencyNamesOf("processResources")).contains("fetchApiSpecOrderPayments");
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
        @DisplayName("an API the project calls with no version says that a client sets its own")
        void clientVersionIsRequired() {
            extension().getChannel().getType().set("file");
            extension().getChannel().getDirectory().set(projectDir.toString());
            extension().getVersion().set("2.0.0");
            extension().subscribeAsClient("account");

            assertThat(resolving("account"))
                .hasMessageContaining("client subscription 'account' declares no version")
                .hasMessageContaining("the version of the contract this project implements");
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

        @Test
        @DisplayName("a subscription's own channel overrides the project's")
        void subscriptionChannelWins() {
            extension().getChannel().getGroupId().set("com.example.all");
            extension().subscribe("account", s -> {
                s.getVersion().set("1.0.0");
                s.channel(c -> {
                    c.getType().set("file");
                    c.getDirectory().set(projectDir.toString());
                });
            });

            assertThat(resolving("account")).isNull();
            FetchApiSpecTask task = (FetchApiSpecTask) project.getTasks().getByName("fetchApiSpecAccount");
            assertThat(canonical(task.getArchive().getSingleFile()))
                .isEqualTo(canonical(projectDir.resolve("account/1.0.0/account-1.0.0.tgz").toFile()));
            assertThat(task.getChannel().get()).isEqualTo("file");
        }

        @Test
        @DisplayName("every setting a subscription's channel leaves out comes from the project's")
        void subscriptionChannelFallsBackToTheProjects() {
            extension().getChannel().getType().set("file");
            extension().getChannel().getDirectory().set(projectDir.toString());
            Subscription account = subscribe("account", "1.0.0");
            Subscription payments = extension().subscribeAsClient("payments", s -> {
                s.getVersion().set("1.0.0");
                s.channel(c -> c.getGroupId().set("com.example.payments"));
            });

            assertThat(account.getChannel().getType().get()).isEqualTo("file");
            assertThat(account.getChannel().getDirectory().get()).isEqualTo(projectDir.toString());
            assertThat(payments.getChannel().getType().get()).isEqualTo("file");
            assertThat(payments.getChannel().getGroupId().get()).isEqualTo("com.example.payments");
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

        @Test
        @DisplayName("an API the project calls does not take the version of the contract the project implements")
        void clientTakesNoProjectVersion() {
            extension().getVersion().set("2.0.0");

            assertThat(extension().subscribeAsClient("account").getVersion().isPresent()).isFalse();
        }
    }
}
