package com.arc_e_tect.gradle.apionly.subscriber;

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
 * Adds missing {@code apiOnlySubscriber} DSL properties to a consumer's
 * {@code build.gradle}, using their real plugin defaults.
 *
 * <p>Groovy DSL ({@code build.gradle}) only. Subscription declarations are never
 * invented because their targets and versions belong to the consuming project.</p>
 */
@DisableCachingByDefault(because = "Rewrites a source file in place; a cache restore would bypass that entirely")
public abstract class UpdateApiOnlySubscriberDslTask extends DefaultTask {

    /** Creates a new task instance. Instantiated by Gradle infrastructure. */
    @Inject
    public UpdateApiOnlySubscriberDslTask() {
        setGroup("api only subscriber");
        setDescription("Adds missing apiOnlySubscriber DSL properties to the build file.");
        getGenerateDsl().convention(false);
        getCleanupDsl().convention(false);
    }

    /** The consumer project's {@code build.gradle}. */
    @Internal
    public abstract RegularFileProperty getBuildFile();

    /** Whether to generate a missing outer DSL block. */
    @Internal
    public abstract Property<Boolean> getGenerateDsl();

    /**
     * Sets {@link #getGenerateDsl()} for this run.
     *
     * @param value whether to generate the outer block when absent
     */
    @Option(option = "generateApiOnlySubscriberDSL",
            description = "When the apiOnlySubscriber block is absent, generates one from the schema.")
    public void applyGenerateDsl(boolean value) {
        getGenerateDsl().set(value);
    }

    /** Whether to remove managed comments from the DSL block. */
    @Internal
    public abstract Property<Boolean> getCleanupDsl();

    /**
     * Sets {@link #getCleanupDsl()} for this run.
     *
     * @param value whether to remove comments from the managed block
     */
    @Option(option = "cleanupApiOnlySubscriberDSL",
            description = "Strips comments from the apiOnlySubscriber block.")
    public void applyCleanupDsl(boolean value) {
        getCleanupDsl().set(value);
    }

    /** Updates the consumer build file, keeping a byte-for-byte backup before writing. */
    @TaskAction
    public void updateDsl() {
        Path buildFile = getBuildFile().get().getAsFile().toPath();
        if (!Files.exists(buildFile)) {
            throw new GradleException("API-Only Subscriber: build file not found: " + buildFile);
        }

        String original;
        try {
            original = Files.readString(buildFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new GradleException("API-Only Subscriber: failed to read " + buildFile, e);
        }

        UpdateDslOptions options = new UpdateDslOptions(getGenerateDsl().get(), getCleanupDsl().get());
        DslUpdater.Outcome outcome = DslUpdater.update(
                original, ApiOnlySubscriberDslSchema.SCHEMA, options);
        UpdateDslResult result = outcome.result();

        if (!result.changed()) {
            if (!result.blockFoundBefore()) {
                getLogger().lifecycle(
                        "API-Only Subscriber: updateApiOnlySubscriberDSL found no apiOnlySubscriber block in {} "
                                + "- rerun with --generateApiOnlySubscriberDSL to add one.",
                        buildFile);
            } else {
                getLogger().lifecycle(
                        "API-Only Subscriber: updateApiOnlySubscriberDSL found the apiOnlySubscriber block already "
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
            throw new GradleException("API-Only Subscriber: failed to write " + buildFile, e);
        }
        getLogger().lifecycle(
                "API-Only Subscriber: updateApiOnlySubscriberDSL backed up the original file to {}",
                backup);

        if (result.blockGenerated()) {
            getLogger().lifecycle(
                    "API-Only Subscriber: updateApiOnlySubscriberDSL generated a new apiOnlySubscriber block in {}",
                    buildFile);
        } else if (!result.addedProperties().isEmpty()) {
            String propertyWord = result.addedProperties().size() == 1 ? "property" : "properties";
            getLogger().lifecycle(
                    "API-Only Subscriber: updateApiOnlySubscriberDSL added {} missing {} to the "
                            + "apiOnlySubscriber block in {}",
                    result.addedProperties().size(), propertyWord, buildFile);
        }
        if (result.cleaned()) {
            getLogger().lifecycle(
                    "API-Only Subscriber: updateApiOnlySubscriberDSL removed comments from the "
                            + "apiOnlySubscriber block in {}",
                    buildFile);
        }

        Map<String, String> defaultsByName = ApiOnlySubscriberDslSchema.SCHEMA.properties().stream()
                .filter(property -> property.kind() == DslPropertyKind.SCALAR)
                .collect(Collectors.toMap(DslPropertySpec::name, DslPropertySpec::defaultLiteral));
        for (String name : result.addedProperties()) {
            getLogger().info("API-Only Subscriber: updateApiOnlySubscriberDSL added {} = {}",
                    name, defaultsByName.get(name));
        }
    }
}
