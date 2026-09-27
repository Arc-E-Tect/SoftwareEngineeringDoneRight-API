package com.arc_e_tect.gradle.apionly.transcriberj;

import com.arc_e_tect.gradle.apionly.transcriberj.core.GenerationReport;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes the one report of a subscription from the fragments its generation tasks wrote: the
 * text report, as one generation of the core and every emitter would have written it, and an
 * AsciiDoc report, a frame that summarises it and includes each task's AsciiDoc fragment.
 */
@CacheableTask
public abstract class ReportContractSourcesTask extends DefaultTask {

    /** Creates the task. */
    public ReportContractSourcesTask() {
    }

    /**
     * The contract's name.
     *
     * @return the name
     */
    @Input
    public abstract Property<String> getContractName();

    /**
     * The Subscriber's lockfile, for the version the report names.
     *
     * @return the lockfile
     */
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getLockfile();

    /**
     * The core's part of the report, as JSON.
     *
     * @return the fragment
     */
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getCoreFragment();

    /**
     * Each emitter's part of the report, as JSON, in emitter order.
     *
     * @return the fragments
     */
    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    public abstract ConfigurableFileCollection getEmitterFragments();

    /**
     * The AsciiDoc fragments, the core's first, then each emitter's, which the AsciiDoc report
     * includes.
     *
     * @return the fragments
     */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getAsciiDocFragments();

    /**
     * Where the text report goes.
     *
     * @return the report
     */
    @OutputFile
    public abstract RegularFileProperty getReportFile();

    /**
     * Where the AsciiDoc report goes.
     *
     * @return the report
     */
    @OutputFile
    public abstract RegularFileProperty getAsciiDocReport();

    /** Merges the fragments and writes the report. */
    @TaskAction
    public void report() {
        String contract = getContractName().get();
        String version = LockedContract.read(getLockfile().get().getAsFile(), contract).version();
        GenerationReport merged = GenerationReport.fromFragment(read(getCoreFragment().get().getAsFile()));
        for (File fragment : getEmitterFragments().getFiles()) {
            merged.add(GenerationReport.fromFragment(read(fragment)));
        }
        String text = merged.render(contract, version);
        Path reportFile = getReportFile().get().getAsFile().toPath();
        ContractParameters.write(reportFile, text);
        Path frame = getAsciiDocReport().get().getAsFile().toPath();
        List<String> includes = new ArrayList<>();
        for (File fragment : getAsciiDocFragments().getFiles()) {
            includes.add(frame.getParent().relativize(fragment.toPath()).toString().replace('\\', '/'));
        }
        ContractParameters.write(frame, merged.renderAsciiDocFrame(contract, version, includes));

        List<String> lines = List.of(text.split("\n"));
        getLogger().lifecycle("{}: {} -- see {}", lines.get(0), lines.get(1), reportFile.toFile());
        for (String line : lines.subList(2, Math.min(4, lines.size()))) {
            if (line.startsWith("Invalid requests:") || line.startsWith("Contract cases:")) {
                getLogger().lifecycle("{}: {}", lines.get(0), line);
            }
        }
        int warnings = lines.indexOf("Warnings:");
        for (int i = warnings + 1; warnings >= 0 && i < lines.size() && !lines.get(i).isBlank(); i++) {
            getLogger().warn("{}: warning: {}", lines.get(0), lines.get(i).strip());
        }
    }

    private static String read(File file) {
        try {
            return Files.readString(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
