package com.arc_e_tect.gradle.apionly.transcriberj;

import com.arc_e_tect.gradle.apionly.transcriberj.core.Generation;
import com.arc_e_tect.gradle.apionly.transcriberj.core.GenerationReport;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.Emitter;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.Settings;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.workers.WorkAction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

/** The core's generation: the schema classes, operations, cases and the endpoint index. */
public abstract class GenerateContractSourcesAction implements WorkAction<GenerateContractSourcesAction.Parameters> {

    /** What the task hands over. */
    public interface Parameters extends ContractParameters {

        /**
         * Where the sources go.
         *
         * @return the directory
         */
        DirectoryProperty getOutputDirectory();

        /**
         * Where the core's resources go.
         *
         * @return the directory
         */
        DirectoryProperty getResourceDirectory();

        /**
         * Where the endpoint index goes.
         *
         * @return the index
         */
        RegularFileProperty getEndpointIndex();

        /**
         * Where the core's part of the report goes, as JSON.
         *
         * @return the fragment
         */
        RegularFileProperty getReportFragment();

        /**
         * Where the core's part of the report goes, as AsciiDoc.
         *
         * @return the fragment
         */
        RegularFileProperty getAsciiDocFragment();

        /**
         * Where the machine-readable report of every generated valid value goes.
         *
         * @return the report
         */
        RegularFileProperty getValidValuesReport();
    }

    /** Creates the action. */
    public GenerateContractSourcesAction() {
    }

    @Override
    public void execute() {
        Parameters p = getParameters();
        Settings settings = p.settings(Map.of());
        Generation.Derivation derivation = p.derive(settings);
        GenerationReport report = Generation.writeCore(derivation, p.getOutputDirectory().get().getAsFile().toPath(),
                p.getResourceDirectory().get().getAsFile().toPath(), p.getEndpointIndex().get().getAsFile().toPath());
        ContractParameters.write(p.getReportFragment().get().getAsFile().toPath(), report.fragment());
        ContractParameters.write(p.getAsciiDocFragment().get().getAsFile().toPath(), report.renderAsciiDoc("Core"));
        if (p.getValidValuesReport().isPresent()) {
            ContractParameters.write(p.getValidValuesReport().get().getAsFile().toPath(),
                    report.renderValidValues(settings.contract(), p.getContractVersion().get()));
        }
    }

    /** Every emitter the class loader offers, by id; two with one id are an error. */
    static List<Emitter> emitters(ClassLoader loader) {
        List<Emitter> emitters = new ArrayList<>();
        ServiceLoader.load(Emitter.class, loader).forEach(emitters::add);
        emitters.sort(Comparator.comparing(Emitter::id));
        Set<String> ids = new HashSet<>(Set.of("core"));
        for (Emitter emitter : emitters) {
            if (!ids.add(emitter.id())) {
                throw new GradleException("Two emitters on the transcriberjEmitters classpath have the id "
                        + emitter.id() + " (" + emitter.getClass().getName() + "); remove one of them.");
            }
        }
        return emitters;
    }
}
