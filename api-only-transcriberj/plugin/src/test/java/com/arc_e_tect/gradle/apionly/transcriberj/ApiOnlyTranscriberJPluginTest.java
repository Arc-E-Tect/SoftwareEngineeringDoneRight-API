package com.arc_e_tect.gradle.apionly.transcriberj;

import com.arc_e_tect.gradle.apionly.subscriber.ApiOnlySubscriberExtension;
import com.arc_e_tect.gradle.apionly.subscriber.Lockfile;
import com.arc_e_tect.gradle.apionly.transcriberj.core.TestEmitter;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.Emitter;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The plugin's wiring and its tasks' actions, in-process, where coverage can see them. */
class ApiOnlyTranscriberJPluginTest {

    @TempDir
    Path projectDir;

    private Project project;

    @BeforeEach
    void setUp() throws java.io.IOException {
        projectDir = projectDir.toRealPath();
        project = ProjectBuilder.builder().withProjectDir(projectDir.toFile()).build();
    }

    private Path lockfile(String version, String sha256) {
        Lockfile lock = new Lockfile();
        lock.put(new Lockfile.Entry("user-account", version, "file", Map.of("openapi.yaml", sha256)));
        File file = projectDir.resolve("apionly.lock").toFile();
        lock.write(file);
        return file.toPath();
    }

    @Test
    void registersTheExtensionTheConfigurationAndTheSubscriber() {
        project.getPluginManager().apply(ApiOnlyTranscriberJPlugin.class);

        assertThat(project.getExtensions().findByName(ApiOnlyTranscriberJExtension.NAME)).isNotNull();
        assertThat(project.getExtensions().findByType(ApiOnlySubscriberExtension.class)).isNotNull();
        assertThat(project.getConfigurations().getByName("transcriberjEmitters").isCanBeResolved()).isTrue();
        assertThat(project.getConfigurations().getByName("transcriberjEmitters").isCanBeConsumed()).isFalse();
    }

    @Test
    void eachSubscriptionGetsTasksConventionsAndItsSourceSets() {
        project.getPluginManager().apply("java");
        project.getPluginManager().apply(ApiOnlyTranscriberJPlugin.class);
        project.getExtensions().getByType(ApiOnlySubscriberExtension.class).subscribe("user-account");
        ApiOnlyTranscriberJExtension extension = project.getExtensions().getByType(ApiOnlyTranscriberJExtension.class);
        TranscriberJSubscription subscription = extension.subscription("user-account", s -> {
            s.getBasePackage().set("com.example.contract");
            s.getSourceSets().set(List.of("main", "test"));
        });

        assertThat(subscription.getName()).isEqualTo("user-account");
        assertThat(extension.subscription("user-account", s -> { })).isSameAs(subscription);
        assertThat(subscription.getRecursionDepth().get()).isEqualTo(3);
        assertThat(subscription.getDescriptionPlaceholder().get())
                .isEqualTo(TranscriberJSubscription.DEFAULT_DESCRIPTION_PLACEHOLDER);
        assertThat(subscription.getInto().get().getAsFile())
                .isEqualTo(projectDir.resolve("build/generated/sources/transcriberj/user-account").toFile());

        GenerateContractSourcesTask generate = (GenerateContractSourcesTask)
                project.getTasks().getByName("generateContractSourcesUserAccount");
        assertThat(generate.getContractName().get()).isEqualTo("user-account");
        assertThat(generate.getBasePackage().get()).isEqualTo("com.example.contract");
        assertThat(generate.getContract().get().getAsFile())
                .isEqualTo(projectDir.resolve("build/api-spec/user-account/openapi.yaml").toFile());
        assertThat(generate.getReportFile().get().getAsFile())
                .isEqualTo(projectDir.resolve("build/reports/transcriberj/user-account.txt").toFile());
        assertThat(project.getTasks().getByName("check").getDependsOn()).anySatisfy(d ->
                assertThat(d.toString()).contains("verifyContractSourcesUserAccount"));

        ((ProjectInternal) project).evaluate();
        SourceSetContainer sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
        for (String name : List.of("main", "test")) {
            assertThat(sourceSets.getByName(name).getJava().getSrcDirs())
                    .contains(projectDir.resolve("build/generated/sources/transcriberj/user-account").toFile());
        }
    }

    @Test
    void theTaskSuffixIsTheSubscribersOwn() {
        assertThat(ApiOnlyTranscriberJPlugin.suffix("user-account")).isEqualTo("UserAccount");
        assertThat(ApiOnlyTranscriberJPlugin.suffix("order_payments.v2")).isEqualTo("OrderPaymentsV2");
    }

    @Test
    void theGenerationTaskRunsTheCoreAndTheEmittersInIsolation() throws Exception {
        project.getPluginManager().apply(ApiOnlyTranscriberJPlugin.class);
        Path contract = Path.of(System.getProperty("transcriberj.fixtures"), "contracts/user-account/openapi.yaml");
        Path jar = projectDir.resolve("emitter.jar");
        ApiOnlyTranscriberJPluginFunctionalTest.emitterJar(jar);
        GenerateContractSourcesTask task = project.getTasks().create("generate", InProcessGenerateTask.class);
        task.getContract().set(contract.toFile());
        task.getLockfile().set(lockfile("1.0.0", "abc").toFile());
        task.getContractName().set("user-account");
        task.getBasePackage().set("com.example.contract");
        task.getRecursionDepth().set(3);
        task.getDescriptionPlaceholder().set("P");
        task.getEmitterClasspath().from(jar.toFile());
        task.getOutputDirectory().set(projectDir.resolve("out").toFile());
        task.getReportFile().set(projectDir.resolve("report.txt").toFile());

        task.generate();

        // The fake runs the action in this class loader, which has no emitter on it; the
        // functional test covers the isolated class loader.
        assertThat(projectDir.resolve("out/com/example/contract/UserV1.java")).exists();
        assertThat(Files.readString(projectDir.resolve("out/com/example/contract/ContractManifest.java")))
                .contains("CONTRACT_SHA256 = \"abc\"");
        assertThat(Files.readString(projectDir.resolve("report.txt"))).startsWith("API-Only TranscriberJ: user-account");

        task.getBasePackage().set((String) null);
        assertThatThrownBy(task::generate).isInstanceOf(GradleException.class)
                .hasMessageContaining("subscription('user-account') needs a basePackage");
    }

    @Test
    void theGenerationActionReportsAnUngeneratableContractAsABuildFailure() throws Exception {
        Path contract = projectDir.resolve("openapi.yaml");
        Files.writeString(contract, "openapi: 3.1.0\ninfo: {title: t, version: 1.0.0}\n"
                + "components: {schemas: {A: {type: string}}}\n");
        GenerateContractSourcesAction.Parameters parameters =
                project.getObjects().newInstance(GenerateContractSourcesAction.Parameters.class);
        parameters.getContract().set(contract.toFile());
        parameters.getContractVersion().set("1.0.0");
        parameters.getContractSha256().set("x");
        parameters.getContractName().set("c");
        parameters.getBasePackage().set("a.b");
        parameters.getRecursionDepth().set(1);
        parameters.getDescriptionPlaceholder().set("p");
        parameters.getOutputDirectory().set(projectDir.resolve("out").toFile());
        parameters.getReportFile().set(projectDir.resolve("report.txt").toFile());
        GenerateContractSourcesAction action = new GenerateContractSourcesAction() {
            @Override
            public Parameters getParameters() {
                return parameters;
            }
        };

        assertThatThrownBy(action::execute).isInstanceOf(GradleException.class)
                .hasMessageContaining("have no x-fragment-path");

        parameters.getReportFile().set(projectDir.resolve("missing/dir/report.txt").toFile());
        Files.writeString(contract, "openapi: 3.1.0\ninfo: {title: t, version: 1.0.0}\n");
        assertThatThrownBy(action::execute).isInstanceOf(java.io.UncheckedIOException.class);
    }

    @Test
    void emittersAreFoundByServiceLoaderAndMayNotShareAnId() throws Exception {
        Path jar = projectDir.resolve("emitter.jar");
        ApiOnlyTranscriberJPluginFunctionalTest.emitterJar(jar);
        try (URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, getClass().getClassLoader())) {
            assertThat(GenerateContractSourcesAction.emitters(loader)).singleElement()
                    .extracting(Emitter::id).isEqualTo("counting");
        }
        Path services = projectDir.resolve("services");
        Files.writeString(services, TestEmitter.class.getName() + "\n"
                + com.arc_e_tect.gradle.apionly.transcriberj.core.TwinEmitter.class.getName() + "\n");
        ClassLoader twice = new ClassLoader(getClass().getClassLoader()) {
            @Override
            public java.util.Enumeration<URL> getResources(String name) throws java.io.IOException {
                return name.equals("META-INF/services/" + Emitter.class.getName())
                        ? java.util.Collections.enumeration(List.of(services.toUri().toURL()))
                        : super.getResources(name);
            }
        };
        assertThatThrownBy(() -> GenerateContractSourcesAction.emitters(twice)).isInstanceOf(GradleException.class)
                .hasMessageContaining("Two emitters on the transcriberjEmitters classpath have the id counting");
    }

    @Test
    void verificationComparesTheGeneratedManifestWithTheLockfile() throws Exception {
        project.getPluginManager().apply(ApiOnlyTranscriberJPlugin.class);
        VerifyContractSourcesTask task = project.getTasks().create("verify", VerifyContractSourcesTask.class);
        task.getLockfile().set(lockfile("1.0.0", "abc").toFile());
        task.getContractName().set("user-account");
        task.getBasePackage().set("com.example.contract");
        task.getSourcesDirectory().set(projectDir.resolve("gen").toFile());

        assertThatThrownBy(task::verify).isInstanceOf(GradleException.class)
                .hasMessageContaining("No classes have been generated for contract user-account")
                .hasMessageContaining("Run generateContractSourcesUserAccount");

        Path manifest = Files.createDirectories(projectDir.resolve("gen/com/example/contract"))
                .resolve("ContractManifest.java");
        Files.writeString(manifest, "CONTRACT_VERSION = \"1.0.0\";\nCONTRACT_SHA256 = \"abc\";\n");
        task.verify();

        Files.writeString(manifest, "CONTRACT_VERSION = \"1.0.0\";\nCONTRACT_SHA256 = \"def\";\n");
        assertThatThrownBy(task::verify).isInstanceOf(GradleException.class)
                .hasMessageContaining("generated from version 1.0.0 (def)");

        Files.writeString(manifest, "nothing here");
        assertThatThrownBy(task::verify).isInstanceOf(GradleException.class)
                .hasMessageContaining("generated from version null (null)");

        Files.delete(projectDir.resolve("apionly.lock"));
        Files.writeString(manifest, "CONTRACT_VERSION = \"1.0.0\";\nCONTRACT_SHA256 = \"abc\";\n");
        assertThatThrownBy(task::verify).isInstanceOf(GradleException.class)
                .hasMessageContaining("has no openapi.yaml for contract user-account. Run fetchApiSpec first.");
    }

    @Test
    void aLockfileWithoutTheContractsDocumentIsNamed() {
        Lockfile lock = new Lockfile();
        lock.put(new Lockfile.Entry("user-account", "1.0.0", "file", Map.of("asyncapi.yaml", "x")));
        File file = projectDir.resolve("apionly.lock").toFile();
        lock.write(file);

        assertThatThrownBy(() -> LockedContract.read(file, "user-account")).isInstanceOf(GradleException.class)
                .hasMessageContaining("has no openapi.yaml for contract user-account");
        assertThatThrownBy(() -> LockedContract.read(file, "other")).isInstanceOf(GradleException.class);
        assertThat(LockedContract.read(lockfileFor(), "user-account").version()).isEqualTo("2.0.0");
    }

    private File lockfileFor() {
        Lockfile lock = new Lockfile();
        lock.put(new Lockfile.Entry("user-account", "2.0.0", "file", Map.of("openapi.yaml", "y")));
        File file = projectDir.resolve("other.lock").toFile();
        lock.write(file);
        return file;
    }

    /** The generation task, with a worker executor that runs its action here and now. */
    public abstract static class InProcessGenerateTask extends GenerateContractSourcesTask {

        @Override
        protected org.gradle.workers.WorkerExecutor getWorkerExecutor() {
            Project project = getProject();
            org.gradle.workers.WorkQueue queue = new org.gradle.workers.WorkQueue() {
                @Override
                @SuppressWarnings("unchecked")
                public <T extends org.gradle.workers.WorkParameters> void submit(
                        Class<? extends org.gradle.workers.WorkAction<T>> action,
                        org.gradle.api.Action<? super T> configure) {
                    GenerateContractSourcesAction.Parameters parameters =
                            project.getObjects().newInstance(GenerateContractSourcesAction.Parameters.class);
                    configure.execute((T) parameters);
                    new GenerateContractSourcesAction() {
                        @Override
                        public Parameters getParameters() {
                            return parameters;
                        }
                    }.execute();
                }

                @Override
                public void await() {
                }
            };
            return new org.gradle.workers.WorkerExecutor() {
                @Override
                public org.gradle.workers.WorkQueue noIsolation() {
                    return queue;
                }

                @Override
                public org.gradle.workers.WorkQueue classLoaderIsolation() {
                    return queue;
                }

                @Override
                public org.gradle.workers.WorkQueue processIsolation() {
                    return queue;
                }

                @Override
                public org.gradle.workers.WorkQueue noIsolation(
                        org.gradle.api.Action<? super org.gradle.workers.WorkerSpec> action) {
                    return queue;
                }

                @Override
                public org.gradle.workers.WorkQueue classLoaderIsolation(
                        org.gradle.api.Action<? super org.gradle.workers.ClassLoaderWorkerSpec> action) {
                    org.gradle.api.file.ConfigurableFileCollection classpath = project.getObjects().fileCollection();
                    org.gradle.workers.ClassLoaderWorkerSpec spec = () -> classpath;
                    action.execute(spec);
                    return queue;
                }

                @Override
                public org.gradle.workers.WorkQueue processIsolation(
                        org.gradle.api.Action<? super org.gradle.workers.ProcessWorkerSpec> action) {
                    return queue;
                }

                @Override
                public void await() {
                }
            };
        }
    }
}
