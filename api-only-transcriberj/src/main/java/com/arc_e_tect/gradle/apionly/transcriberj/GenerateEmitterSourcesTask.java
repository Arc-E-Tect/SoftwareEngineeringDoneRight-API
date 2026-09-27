package com.arc_e_tect.gradle.apionly.transcriberj;

import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.workers.WorkQueue;
import org.gradle.workers.WorkerExecutor;

import javax.inject.Inject;

/**
 * Generates one emitter's output for one contract, over the same model the core generates from:
 * its Java sources, its classpath resources and its files, each in a directory of its own. Its
 * inputs are the contract, the settings the model depends on, and this emitter's id, options and
 * classpath, so changing another emitter's options leaves it up to date.
 */
@CacheableTask
public abstract class GenerateEmitterSourcesTask extends ContractSourcesTask {

    /** Creates the task. */
    public GenerateEmitterSourcesTask() {
    }

    /**
     * The emitter's id.
     *
     * @return the id
     */
    @Input
    public abstract Property<String> getEmitterId();

    /**
     * The emitter's options.
     *
     * @return the options
     */
    @Input
    public abstract MapProperty<String, String> getOptions();

    /**
     * The emitter's version, as the {@code transcriberjEmitters} configuration resolves it, for the
     * provenance of the files it writes.
     *
     * @return the version
     */
    @Input
    public abstract Property<String> getEmitterVersion();

    /**
     * The TranscriberJ's version, for the provenance of the files the emitter writes.
     *
     * @return the version
     */
    @Input
    public abstract Property<String> getTranscriberJVersion();

    /**
     * The emitter libraries.
     *
     * @return the classpath
     */
    @Classpath
    public abstract ConfigurableFileCollection getEmitterClasspath();

    /**
     * Where the emitter's Java sources go.
     *
     * @return the directory
     */
    @OutputDirectory
    public abstract DirectoryProperty getJavaDirectory();

    /**
     * Where the emitter's classpath resources go.
     *
     * @return the directory
     */
    @OutputDirectory
    public abstract DirectoryProperty getResourceDirectory();

    /**
     * Where the emitter's files go.
     *
     * @return the directory
     */
    @OutputDirectory
    public abstract DirectoryProperty getFilesDirectory();

    /**
     * Where the emitter's part of the report goes, as JSON.
     *
     * @return the fragment
     */
    @OutputFile
    public abstract RegularFileProperty getReportFragment();

    /**
     * Where the emitter's part of the report goes, as AsciiDoc.
     *
     * @return the fragment
     */
    @OutputFile
    public abstract RegularFileProperty getAsciiDocFragment();

    /**
     * Where the contract version and hash the output was generated from are recorded, for
     * {@code verifyContractSources<Contract>}.
     *
     * @return the stamp
     */
    @OutputFile
    public abstract RegularFileProperty getStamp();

    /**
     * Where the provenance of the emitter's files goes: absent for an emitter that writes none.
     *
     * @return the provenance file
     */
    @OutputFile
    @Optional
    public abstract RegularFileProperty getProvenance();

    /**
     * Gradle's worker executor.
     *
     * @return the executor
     */
    @Inject
    protected abstract WorkerExecutor getWorkerExecutor();

    /** Generates the emitter's output. */
    @TaskAction
    public void generate() {
        LockedContract locked = locked();
        WorkQueue queue = getWorkerExecutor().classLoaderIsolation(spec ->
                spec.getClasspath().from(getEmitterClasspath()));
        queue.submit(GenerateEmitterSourcesAction.class, parameters -> {
            fill(parameters, locked);
            parameters.getEmitterId().set(getEmitterId());
            parameters.getOptions().set(getOptions());
            parameters.getEmitterVersion().set(getEmitterVersion());
            parameters.getTranscriberJVersion().set(getTranscriberJVersion());
            parameters.getJavaDirectory().set(getJavaDirectory());
            parameters.getResourceDirectory().set(getResourceDirectory());
            parameters.getFilesDirectory().set(getFilesDirectory());
            parameters.getReportFragment().set(getReportFragment());
            parameters.getAsciiDocFragment().set(getAsciiDocFragment());
            parameters.getStamp().set(getStamp());
            parameters.getProvenance().set(getProvenance());
        });
        queue.await();
    }
}
