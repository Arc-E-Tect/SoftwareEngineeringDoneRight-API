package com.arc_e_tect.gradle.apionly.transcriberj;

import com.arc_e_tect.gradle.dslupdater.DslPropertyKind;
import com.arc_e_tect.gradle.dslupdater.DslPropertySpec;
import com.arc_e_tect.gradle.dslupdater.DslUpdater;
import com.arc_e_tect.gradle.dslupdater.UpdateDslOptions;
import com.arc_e_tect.gradle.dslupdater.UpdateDslResult;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;
import org.gradle.work.DisableCachingByDefault;

import javax.inject.Inject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Adds missing {@code apiOnlyTranscriberJ} DSL properties to a consumer's
 * {@code build.gradle}, using their real plugin defaults.
 *
 * <p>Groovy DSL ({@code build.gradle}) only. Subscription declarations are never
 * invented because their targets and versions belong to the consuming project.</p>
 */
@DisableCachingByDefault(because = "Rewrites a source file in place; a cache restore would bypass that entirely")
public abstract class UpdateApiOnlyTranscriberJDslTask extends DefaultTask {

    /** Creates a new task instance. Instantiated by Gradle infrastructure. */
    @Inject
    public UpdateApiOnlyTranscriberJDslTask() {
        setGroup("api only transcriberj");
        setDescription("Adds missing apiOnlyTranscriberJ DSL properties to the build file.");
        getGenerateDsl().convention(false);
        getCleanupDsl().convention(false);
    }

    /**
     * The consumer project's {@code build.gradle}.
     *
     * @return the build file this task rewrites
     */
    @Internal
    public abstract RegularFileProperty getBuildFile();

    /**
     * Whether to generate a missing outer DSL block.
     *
     * @return whether an absent {@code apiOnlyTranscriberJ} block is generated
     */
    @Internal
    public abstract Property<Boolean> getGenerateDsl();

    /**
     * Sets {@link #getGenerateDsl()} for this run.
     *
     * @param value whether to generate the outer block when absent
     */
    @Option(option = "generateApiOnlyTranscriberJDSL",
            description = "When the apiOnlyTranscriberJ block is absent, generates one from the schema.")
    public void applyGenerateDsl(boolean value) {
        getGenerateDsl().set(value);
    }

    /**
     * Whether to remove managed comments from the DSL block.
     *
     * @return whether comments are stripped from the managed block
     */
    @Internal
    public abstract Property<Boolean> getCleanupDsl();

    /**
     * Sets {@link #getCleanupDsl()} for this run.
     *
     * @param value whether to remove comments from the managed block
     */
    @Option(option = "cleanupApiOnlyTranscriberJDSL",
            description = "Strips comments from the apiOnlyTranscriberJ block.")
    public void applyCleanupDsl(boolean value) {
        getCleanupDsl().set(value);
    }

    /** Updates the consumer build file, keeping a byte-for-byte backup before writing. */
    @TaskAction
    public void updateDsl() {
        Path buildFile = getBuildFile().get().getAsFile().toPath();
        if (!Files.exists(buildFile)) {
            throw new GradleException("API-Only TranscriberJ: build file not found: " + buildFile);
        }

        String original;
        try {
            original = Files.readString(buildFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new GradleException("API-Only TranscriberJ: failed to read " + buildFile, e);
        }

        UpdateDslOptions options = new UpdateDslOptions(getGenerateDsl().get(), getCleanupDsl().get());
        DslUpdater.Outcome outcome = DslUpdater.update(
                original, ApiOnlyTranscriberJDslSchema.SCHEMA, options);
        UpdateDslResult result = outcome.result();

        if (!result.changed()) {
            if (!result.blockFoundBefore()) {
                getLogger().lifecycle(
                        "API-Only TranscriberJ: updateApiOnlyTranscriberJDSL found no apiOnlyTranscriberJ block in {} "
                                + "- rerun with --generateApiOnlyTranscriberJDSL to add one.",
                        buildFile);
            } else {
                getLogger().lifecycle(
                        "API-Only TranscriberJ: updateApiOnlyTranscriberJDSL found the apiOnlyTranscriberJ block already "
                                + "up to date in {}",
                        buildFile);
            }
            return;
        }

        Path backup = buildFile.resolveSibling(buildFile.getFileName() + ".bak");
        try {
            Files.copy(buildFile, backup, StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(buildFile, outcome.source(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new GradleException("API-Only TranscriberJ: failed to write " + buildFile, e);
        }
        getLogger().lifecycle(
                "API-Only TranscriberJ: updateApiOnlyTranscriberJDSL backed up the original file to {}",
                backup);

        if (result.blockGenerated()) {
            getLogger().lifecycle(
                    "API-Only TranscriberJ: updateApiOnlyTranscriberJDSL generated a new apiOnlyTranscriberJ block in {}",
                    buildFile);
        } else if (!result.addedProperties().isEmpty()) {
            String propertyWord = result.addedProperties().size() == 1 ? "property" : "properties";
            getLogger().lifecycle(
                    "API-Only TranscriberJ: updateApiOnlyTranscriberJDSL added {} missing {} to the "
                            + "apiOnlyTranscriberJ block in {}",
                    result.addedProperties().size(), propertyWord, buildFile);
        }
        if (result.cleaned()) {
            getLogger().lifecycle(
                    "API-Only TranscriberJ: updateApiOnlyTranscriberJDSL removed comments from the "
                            + "apiOnlyTranscriberJ block in {}",
                    buildFile);
        }

        Map<String, String> defaultsByName = ApiOnlyTranscriberJDslSchema.SCHEMA.properties().stream()
                .filter(property -> property.kind() == DslPropertyKind.SCALAR)
                .collect(Collectors.toMap(DslPropertySpec::name, DslPropertySpec::defaultLiteral));
        for (String name : result.addedProperties()) {
            getLogger().info("API-Only TranscriberJ: updateApiOnlyTranscriberJDSL added {} = {}",
                    name, defaultsByName.get(name));
        }
    }
}
