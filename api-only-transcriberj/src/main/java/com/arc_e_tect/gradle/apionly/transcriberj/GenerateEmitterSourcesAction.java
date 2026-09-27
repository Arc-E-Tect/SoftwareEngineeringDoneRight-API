package com.arc_e_tect.gradle.apionly.transcriberj;

import com.arc_e_tect.gradle.apionly.transcriberj.core.Generation;
import com.arc_e_tect.gradle.apionly.transcriberj.core.GenerationException;
import com.arc_e_tect.gradle.apionly.transcriberj.core.GenerationReport;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.Emitter;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.Settings;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.workers.WorkAction;

import java.util.Map;
import java.util.TreeMap;

/** One emitter's generation, in the class loader the emitters were loaded into. */
public abstract class GenerateEmitterSourcesAction implements WorkAction<GenerateEmitterSourcesAction.Parameters> {

    /** What the task hands over. */
    public interface Parameters extends ContractParameters {

        /**
         * The emitter's id.
         *
         * @return the id
         */
        Property<String> getEmitterId();

        /**
         * The emitter's options.
         *
         * @return the options
         */
        MapProperty<String, String> getOptions();

        /**
         * The emitter's version.
         *
         * @return the version
         */
        Property<String> getEmitterVersion();

        /**
         * The TranscriberJ's version.
         *
         * @return the version
         */
        Property<String> getTranscriberJVersion();

        /**
         * Where the emitter's Java sources go.
         *
         * @return the directory
         */
        DirectoryProperty getJavaDirectory();

        /**
         * Where the emitter's resources go.
         *
         * @return the directory
         */
        DirectoryProperty getResourceDirectory();

        /**
         * Where the emitter's files go.
         *
         * @return the directory
         */
        DirectoryProperty getFilesDirectory();

        /**
         * Where the emitter's part of the report goes, as JSON.
         *
         * @return the fragment
         */
        RegularFileProperty getReportFragment();

        /**
         * Where the emitter's part of the report goes, as AsciiDoc.
         *
         * @return the fragment
         */
        RegularFileProperty getAsciiDocFragment();

        /**
         * Where the version the output was generated from is recorded.
         *
         * @return the stamp
         */
        RegularFileProperty getStamp();

        /**
         * Where the provenance of the emitter's files goes, when it writes files.
         *
         * @return the provenance file
         */
        RegularFileProperty getProvenance();
    }

    /** Creates the action. */
    public GenerateEmitterSourcesAction() {
    }

    /**
     * The class loader the emitters are loaded from: the one Gradle built for this action from
     * the {@code transcriberjEmitters} configuration.
     *
     * @return the class loader
     */
    protected ClassLoader loader() {
        return getClass().getClassLoader();
    }

    @Override
    public void execute() {
        Parameters p = getParameters();
        String id = p.getEmitterId().get();
        Emitter emitter = GenerateContractSourcesAction.emitters(loader())
                .stream().filter(e -> e.id().equals(id)).findFirst()
                .orElseThrow(() -> new GradleException("No emitter with the id " + id
                        + " is on the transcriberjEmitters classpath any more."));
        Map<String, String> options = new TreeMap<>(p.getOptions().getOrElse(Map.of()));
        Settings settings = p.settings(Map.of(id, options));
        Generation.Derivation derivation = p.derive(settings);
        GenerationReport report;
        try {
            report = Generation.runEmitter(derivation, emitter, p.getJavaDirectory().get().getAsFile().toPath(),
                    p.getResourceDirectory().get().getAsFile().toPath(),
                    p.getFilesDirectory().get().getAsFile().toPath());
        } catch (GenerationException e) {
            throw new GradleException(e.getMessage(), e);
        }
        ContractParameters.write(p.getReportFragment().get().getAsFile().toPath(), report.fragment());
        ContractParameters.write(p.getAsciiDocFragment().get().getAsFile().toPath(),
                report.renderAsciiDoc("Emitter " + id));
        ContractParameters.write(p.getStamp().get().getAsFile().toPath(), Stamp.render(id,
                p.getContractVersion().get(), p.getContractSha256().get()));
        if (p.getProvenance().isPresent()) {
            ContractParameters.write(p.getProvenance().get().getAsFile().toPath(), Provenance.render(
                    settings.contract(), p.getContractVersion().get(), p.getContractSha256().get(),
                    p.getTranscriberJVersion().get(), id, p.getEmitterVersion().get(), options));
        }
    }
}
