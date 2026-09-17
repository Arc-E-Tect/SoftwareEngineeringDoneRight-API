package com.arc_e_tect.gradle.apionly.transcriberj;

import com.arc_e_tect.gradle.apionly.transcriberj.core.Generation;
import com.arc_e_tect.gradle.apionly.transcriberj.core.GenerationException;
import com.arc_e_tect.gradle.apionly.transcriberj.core.GenerationReport;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.Emitter;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.Settings;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.workers.WorkAction;
import org.gradle.workers.WorkParameters;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;

/** The generation itself, in the class loader the emitters were loaded into. */
public abstract class GenerateContractSourcesAction implements WorkAction<GenerateContractSourcesAction.Parameters> {

    /** What the task hands over. */
    public interface Parameters extends WorkParameters {

        /**
         * The fetched contract document.
         *
         * @return the document
         */
        RegularFileProperty getContract();

        /**
         * The locked version.
         *
         * @return the version
         */
        Property<String> getContractVersion();

        /**
         * The locked hash.
         *
         * @return the hash
         */
        Property<String> getContractSha256();

        /**
         * The contract's name.
         *
         * @return the name
         */
        Property<String> getContractName();

        /**
         * The base package.
         *
         * @return the package
         */
        Property<String> getBasePackage();

        /**
         * The recursion depth.
         *
         * @return the depth
         */
        Property<Integer> getRecursionDepth();

        /**
         * The description placeholder.
         *
         * @return the placeholder
         */
        Property<String> getDescriptionPlaceholder();

        /**
         * Where the sources go.
         *
         * @return the directory
         */
        DirectoryProperty getOutputDirectory();

        /**
         * Where the endpoint index goes.
         *
         * @return the index
         */
        RegularFileProperty getEndpointIndex();

        /**
         * Where the report goes.
         *
         * @return the report
         */
        RegularFileProperty getReportFile();
    }

    /** Creates the action. */
    public GenerateContractSourcesAction() {
    }

    @Override
    public void execute() {
        Parameters p = getParameters();
        Settings settings = new Settings(p.getContractName().get(), p.getBasePackage().get(), false,
                p.getDescriptionPlaceholder().get(), p.getRecursionDepth().get());
        GenerationReport report;
        try {
            report = Generation.run(p.getContract().get().getAsFile().toPath(), p.getContractVersion().get(),
                    p.getContractSha256().get(), settings, p.getOutputDirectory().get().getAsFile().toPath(),
                    emitters(GenerateContractSourcesAction.class.getClassLoader()),
                    p.getEndpointIndex().get().getAsFile().toPath());
        } catch (GenerationException e) {
            throw new GradleException(e.getMessage(), e);
        }
        try {
            Files.writeString(p.getReportFile().get().getAsFile().toPath(),
                    report.render(settings.contract(), p.getContractVersion().get()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
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
