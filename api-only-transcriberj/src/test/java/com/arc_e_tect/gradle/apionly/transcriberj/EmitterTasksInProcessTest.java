package com.arc_e_tect.gradle.apionly.transcriberj;

import com.arc_e_tect.gradle.apionly.subscriber.ApiOnlySubscriberExtension;
import com.arc_e_tect.gradle.apionly.subscriber.Lockfile;
import com.arc_e_tect.gradle.apionly.transcriberj.core.ModalEmitter;
import com.arc_e_tect.gradle.apionly.transcriberj.core.TestEmitter;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

/** The emitter tasks, the report task and the wiring, in-process, where coverage sees them. */
@DisplayName("T18 Emitter tasks, in-process")
class EmitterTasksInProcessTest {

    static final Path CONTRACT = ReferenceContract.USER_ACCOUNT;

    @TempDir
    Path projectDir;

    Project project;
    Path jar;

    @BeforeEach
    void setUp() throws Exception {
        projectDir = projectDir.toRealPath();
        project = ProjectBuilder.builder().withProjectDir(projectDir.toFile()).build();
        jar = projectDir.resolve("emitters.jar");
        EmitterBuild.emittersJar(jar, TestEmitter.class, ModalEmitter.class);
    }

    private File lockfile() {
        Lockfile lock = new Lockfile();
        lock.put(new Lockfile.Entry("user-account", ReferenceContract.VERSION, "file", Map.of("openapi.yaml", "abc")));
        File file = projectDir.resolve("apionly.lock").toFile();
        lock.write(file);
        return file;
    }

    private void inputs(ContractSourcesTask task) {
        task.getContract().set(CONTRACT.toFile());
        task.getLockfile().set(lockfile());
        task.getContractName().set("user-account");
        task.getBasePackage().set("com.example.contract");
        task.getRecursionDepth().set(3);
        task.getGenerateDocs().set(false);
        task.getDescriptionPlaceholder().set("P");
    }

    @Test
    void theCoreTheEmittersAndTheReportRunAsTheirTasksDo() throws Exception {
        project.getPluginManager().apply(ApiOnlyTranscriberJPlugin.class);
        GenerateContractSourcesTask core = project.getTasks().create("core",
                ApiOnlyTranscriberJPluginTest.InProcessGenerateTask.class);
        inputs(core);
        core.getOutputDirectory().set(projectDir.resolve("core").toFile());
        core.getResourceDirectory().set(projectDir.resolve("core-resources").toFile());
        core.getEndpointIndex().set(projectDir.resolve("index.properties").toFile());
        core.getReportFragment().set(projectDir.resolve("report/core.json").toFile());
        core.getAsciiDocFragment().set(projectDir.resolve("report/core.adoc").toFile());
        core.generate();

        for (String id : List.of("counting", "modal")) {
            InProcessEmitterTask task = project.getTasks().create(id, InProcessEmitterTask.class);
            task.jar = jar;
            inputs(task);
            task.getEmitterId().set(id);
            task.getOptions().set(id.equals("modal") ? Map.of("mode", "files") : Map.of());
            task.getEmitterVersion().set("9.9.9");
            task.getTranscriberJVersion().set("1.2.3");
            task.getJavaDirectory().set(projectDir.resolve(id + "/java").toFile());
            task.getResourceDirectory().set(projectDir.resolve(id + "/resources").toFile());
            task.getFilesDirectory().set(projectDir.resolve(id + "/files").toFile());
            task.getReportFragment().set(projectDir.resolve("report/" + id + ".json").toFile());
            task.getAsciiDocFragment().set(projectDir.resolve("report/" + id + ".adoc").toFile());
            task.getStamp().set(projectDir.resolve("stamps/" + id + ".properties").toFile());
            if (id.equals("modal")) task.getProvenance().set(projectDir.resolve("modal/apionly-provenance.json").toFile());
            task.generate();
        }
        assertThat(projectDir.resolve("counting/java/com/example/contract/counting/UserV1Count.java")).exists();
        assertThat(projectDir.resolve("modal/files/mappings/modal.json")).exists();
        assertThat(projectDir.resolve("modal/java")).isEmptyDirectory();
        assertThat(projectDir.resolve("modal/apionly-provenance.json")).content().isEqualTo(
                "{\"contract\":\"user-account\",\"contractSha256\":\"abc\",\"contractVersion\":\"" + ReferenceContract.VERSION + "\","
                        + "\"emitter\":\"modal\",\"emitterVersion\":\"9.9.9\",\"options\":{\"mode\":\"files\"},"
                        + "\"transcriberj\":\"1.2.3\"}\n");
        assertThat(Stamp.parse(Files.readString(projectDir.resolve("stamps/counting.properties"))))
                .isEqualTo(new Stamp("counting", ReferenceContract.VERSION, "abc"));

        ReportContractSourcesTask report = project.getTasks().create("report", ReportContractSourcesTask.class);
        report.getContractName().set("user-account");
        report.getLockfile().set(lockfile());
        report.getCoreFragment().set(projectDir.resolve("report/core.json").toFile());
        report.getEmitterFragments().from(projectDir.resolve("report/counting.json"), projectDir.resolve("report/modal.json"));
        report.getAsciiDocFragments().from(projectDir.resolve("report/core.adoc"), projectDir.resolve("report/counting.adoc"),
                projectDir.resolve("report/modal.adoc"));
        report.getReportFile().set(projectDir.resolve("user-account.txt").toFile());
        report.getAsciiDocReport().set(projectDir.resolve("user-account.adoc").toFile());
        report.report();

        assertThat(projectDir.resolve("user-account.txt")).content().startsWith("API-Only TranscriberJ: user-account " + ReferenceContract.VERSION + "\n")
                .contains("[counting]");
        assertThat(projectDir.resolve("user-account.adoc")).content()
                .contains("include::report/counting.adoc[leveloffset=+1]");

        InProcessEmitterTask missing = project.getTasks().create("missing", InProcessEmitterTask.class);
        missing.jar = jar;
        inputs(missing);
        missing.getEmitterId().set("absent");
        assertThatThrownBy(missing::generate).isInstanceOf(GradleException.class)
                .hasMessageContaining("No emitter with the id absent");
    }

    @Test
    void verificationChecksEveryEmittersStamp() throws Exception {
        project.getPluginManager().apply(ApiOnlyTranscriberJPlugin.class);
        VerifyContractSourcesTask task = project.getTasks().create("verify", VerifyContractSourcesTask.class);
        task.getLockfile().set(lockfile());
        task.getContractName().set("user-account");
        task.getBasePackage().set("com.example.contract");
        task.getSourcesDirectory().set(projectDir.resolve("gen").toFile());
        Files.createDirectories(projectDir.resolve("gen/com/example/contract"));
        Files.writeString(projectDir.resolve("gen/com/example/contract/ContractManifest.java"),
                "CONTRACT_VERSION = \"" + ReferenceContract.VERSION + "\";\nCONTRACT_SHA256 = \"abc\";\n");
        Path stamp = projectDir.resolve("stamps/restdocs.properties");
        task.getEmitterStamps().from(stamp.toFile());

        assertThatThrownBy(task::verify).hasMessageContaining("No output of emitter restdocs has been generated")
                .hasMessageContaining("generateContractSourcesUserAccountRestdocs");
        Files.createDirectories(stamp.getParent());
        Files.writeString(stamp, Stamp.render("restdocs", ReferenceContract.VERSION, "abc"));
        task.verify();
        Files.writeString(stamp, Stamp.render("restdocs", "0.9.0", "old"));
        assertThatThrownBy(task::verify).hasMessageContaining("The output of emitter restdocs for contract user-account "
                + "was generated from version 0.9.0 (old)");
    }

    @Test
    void theLockedVersionIsReadFromTheLockfilesText() {
        String text = "format 1\n\ntarget other\nversion 2.0.0\n\ntarget user-account\nversion 1.2.0\n";
        assertThat(LockedContract.version(text, "user-account")).isEqualTo("1.2.0");
        assertThat(LockedContract.version(text, "absent")).isEqualTo("unspecified");
        assertThat(LockedContract.version("", "user-account")).isEqualTo("unspecified");
    }

    @Test
    void provenanceEscapesWhatJsonMust() {
        assertThat(Provenance.render("c", "1", "s", "t", "e", "v", Map.of("k", "a\"b\\c\nd\re\tf\u0001")))
                .contains("\"options\":{\"k\":\"a\\\"b\\\\c\\nd\\re\\tf\\u0001\"}");
    }

    @Test
    void theWiringPlacesEachEmitterAndSharesTheSchemaClasses() {
        project.getPluginManager().apply("java");
        project.getPluginManager().apply("idea");
        project.getPluginManager().apply("eclipse");
        project.getPluginManager().apply(ApiOnlyTranscriberJPlugin.class);
        project.getRepositories().mavenCentral();
        SourceSetContainer sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
        sourceSets.create("contractTest");
        project.getDependencies().add("transcriberjEmitters", project.files(jar.toFile()));
        project.getExtensions().getByType(ApiOnlySubscriberExtension.class).subscribe("user-account");
        ApiOnlyTranscriberJExtension extension = project.getExtensions().getByType(ApiOnlyTranscriberJExtension.class);
        extension.subscription("user-account", s -> {
            s.getBasePackage().set("a.b");
            s.getSourceSets().set(List.of("test", "contractTest"));
            s.getSchemaClasses().set("shared");
            s.emitter("counting", e -> e.getSourceSets().set(List.of("contractTest")));
            s.emitter("modal", e -> e.getOptions().put("mode", "files"));
        });
        ((ProjectInternal) project).evaluate();

        assertThat(sourceSets.getNames()).contains("transcriberjUserAccount");
        assertThat(sourceSets.getByName("transcriberjUserAccount").getJava().getSrcDirs())
                .contains(projectDir.resolve("build/generated/sources/transcriberj/user-account/core").toFile());
        assertThat(sourceSets.getByName("test").getJava().getSrcDirs())
                .doesNotContain(projectDir.resolve("build/generated/sources/transcriberj/user-account/core").toFile());
        assertThat(sourceSets.getByName("contractTest").getJava().getSrcDirs())
                .contains(projectDir.resolve("build/generated/sources/transcriberj/user-account/counting").toFile());
        assertThat(project.getTasks().getNames()).contains("generateContractSourcesUserAccountCounting",
                "generateContractSourcesUserAccountModal", "packageUserAccountModal", "reportContractSourcesUserAccount");
        assertThat(project.getTasks().findByName("packageUserAccountCounting")).isNull();
        assertThat(project.getComponents().getNames()).contains("transcriberjUserAccountModal");
        assertThat(project.getConfigurations().findByName("transcriberjUserAccountCountingElements")).isNull();
        assertThat(project.getConfigurations().getByName("transcriberjUserAccountModalElements").isCanBeConsumed())
                .isTrue();
        assertThat(project.getConfigurations().getByName("transcriberjUserAccountModalElements").getAttributes()
                .getAttribute(ApiOnlyTranscriberJPlugin.FILES_ARTIFACT_ATTRIBUTE))
                .isEqualTo(ApiOnlyTranscriberJPlugin.filesArtifactIdentity("user-account", "modal"));
        assertThat(project.getConfigurations().getByName("transcriberjUserAccountModalElements").getOutgoing()
                .getArtifacts()).hasSize(1);
        assertThat(ApiOnlyTranscriberJPlugin.transcriberJVersion()).isNotBlank();
    }

    @Test
    void aSourceSetTheProjectDoesNotHaveIsNamed() {
        project.getPluginManager().apply("java");
        project.getPluginManager().apply(ApiOnlyTranscriberJPlugin.class);
        project.getExtensions().getByType(ApiOnlySubscriberExtension.class).subscribe("user-account");
        project.getExtensions().getByType(ApiOnlyTranscriberJExtension.class).subscription("user-account", s -> {
            s.getBasePackage().set("a.b");
            s.getSourceSets().set(List.of("nowhere"));
        });

        assertThatThrownBy(() -> ((ProjectInternal) project).evaluate())
                .hasStackTraceContaining("names source set 'nowhere', which this project does not have");
    }

    /** The emitter task, with a worker executor that runs its action here, over the emitters' jar. */
    public abstract static class InProcessEmitterTask extends GenerateEmitterSourcesTask {

        Path jar;

        @Override
        protected org.gradle.workers.WorkerExecutor getWorkerExecutor() {
            Project project = getProject();
            Path emitters = jar;
            org.gradle.workers.WorkQueue queue = new org.gradle.workers.WorkQueue() {
                @Override
                @SuppressWarnings("unchecked")
                public <T extends org.gradle.workers.WorkParameters> void submit(
                        Class<? extends org.gradle.workers.WorkAction<T>> action,
                        org.gradle.api.Action<? super T> configure) {
                    GenerateEmitterSourcesAction.Parameters parameters =
                            project.getObjects().newInstance(GenerateEmitterSourcesAction.Parameters.class);
                    configure.execute((T) parameters);
                    new GenerateEmitterSourcesAction() {
                        @Override
                        public Parameters getParameters() {
                            return parameters;
                        }

                        @Override
                        protected ClassLoader loader() {
                            try {
                                return new URLClassLoader(new URL[]{emitters.toUri().toURL()},
                                        GenerateEmitterSourcesAction.class.getClassLoader());
                            } catch (java.net.MalformedURLException e) {
                                throw new IllegalStateException(e);
                            }
                        }
                    }.execute();
                }

                @Override
                public void await() {
                }
            };
            return new ApiOnlyTranscriberJPluginTest.InProcessGenerateTask.Executor(project, queue);
        }
    }
}
