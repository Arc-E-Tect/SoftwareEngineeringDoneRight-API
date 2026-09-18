package com.arc_e_tect.gradle.apionly.transcriberj;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.workers.WorkQueue;
import org.gradle.workers.WorkerExecutor;

import javax.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.List;

/**
 * Generates one contract's class tree.
 *
 * <p>Its inputs are the fetched document's content and the lockfile, not only the
 * document's path: a new contract version regenerates the tree however alike the
 * two documents' files look (D20). Emitters run in a class loader of their own,
 * built from the {@code transcriberjEmitters} configuration.
 */
@CacheableTask
public abstract class GenerateContractSourcesTask extends DefaultTask {

    /** Creates the task. */
    public GenerateContractSourcesTask() {
    }

    /**
     * The fetched contract document.
     *
     * @return the document
     */
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getContract();

    /**
     * The contract's AsyncAPI document, where it has one. Both documents are read
     * into one model, so a fragment they share is one generated class.
     *
     * <p>A file collection rather than a file: whether the contract has an AsyncAPI
     * document is only known once it has been fetched, and a collection tolerates
     * the absence where an input file would fail the build.
     *
     * @return the document, empty when the contract describes no events
     */
    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    public abstract ConfigurableFileCollection getAsyncContract();

    /**
     * The Subscriber's lockfile, which names the contract's version and hash.
     *
     * @return the lockfile
     */
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getLockfile();

    /**
     * The contract's name.
     *
     * @return the name
     */
    @Input
    public abstract Property<String> getContractName();

    /**
     * The package the core classes go in. Optional only so that its absence is
     * reported by this task, naming the subscription, rather than by Gradle.
     *
     * @return the package
     */
    @Input
    @Optional
    public abstract Property<String> getBasePackage();

    /**
     * How often a recursive reference is followed.
     *
     * @return the depth
     */
    @Input
    public abstract Property<Integer> getRecursionDepth();

    /**
     * Whether descriptions come from the contract.
     *
     * @return the setting
     */
    @Input
    public abstract Property<Boolean> getGenerateDocs();

    /**
     * The description every field carries.
     *
     * @return the placeholder
     */
    @Input
    public abstract Property<String> getDescriptionPlaceholder();

    /**
     * The emitter libraries, and everything they need.
     *
     * @return the classpath
     */
    @Classpath
    public abstract ConfigurableFileCollection getEmitterClasspath();

    /**
     * Where the sources go.
     *
     * @return the directory
     */
    @OutputDirectory
    public abstract DirectoryProperty getOutputDirectory();

    /**
     * Where the path of every generated operation and inline schema class goes, keyed
     * {@code ClassName.PATH}: a properties file for tools that read test sources without
     * a classpath, such as the API-Only Suite's contract-evidence scan.
     *
     * @return the index
     */
    @OutputFile
    public abstract RegularFileProperty getEndpointIndex();

    /**
     * Where the report of what could not be generated in full goes.
     *
     * @return the report
     */
    @OutputFile
    public abstract RegularFileProperty getReportFile();

    /**
     * Gradle's worker API.
     *
     * @return the executor
     */
    @Inject
    protected abstract WorkerExecutor getWorkerExecutor();

    /** Generates the sources and reports what could not be generated in full. */
    @TaskAction
    public void generate() {
        if (!getBasePackage().isPresent()) {
            throw new org.gradle.api.GradleException("apiOnlyTranscriberJ: subscription('"
                    + getContractName().get() + "') needs a basePackage.");
        }
        LockedContract locked = LockedContract.read(getLockfile().get().getAsFile(), getContractName().get());
        WorkQueue queue = getWorkerExecutor().classLoaderIsolation(spec ->
                spec.getClasspath().from(getEmitterClasspath()));
        queue.submit(GenerateContractSourcesAction.class, parameters -> {
            parameters.getContract().set(getContract());
            parameters.getAsyncContract().setFrom(getAsyncContract());
            parameters.getContractVersion().set(locked.version());
            parameters.getContractSha256().set(locked.sha256());
            parameters.getContractName().set(getContractName());
            parameters.getBasePackage().set(getBasePackage());
            parameters.getRecursionDepth().set(getRecursionDepth());
            parameters.getGenerateDocs().set(getGenerateDocs());
            parameters.getDescriptionPlaceholder().set(getDescriptionPlaceholder());
            parameters.getOutputDirectory().set(getOutputDirectory());
            parameters.getReportFile().set(getReportFile());
            parameters.getEndpointIndex().set(getEndpointIndex());
        });
        queue.await();

        try {
            List<String> report = Files.readAllLines(getReportFile().get().getAsFile().toPath());
            getLogger().lifecycle("{}: {} -- see {}", report.get(0), report.get(1),
                    getReportFile().get().getAsFile());
            int warnings = report.indexOf("Warnings:");
            for (int i = warnings + 1; warnings >= 0 && i < report.size() && !report.get(i).isBlank(); i++) {
                getLogger().warn("{}: warning: {}", report.get(0), report.get(i).strip());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
