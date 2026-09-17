package com.arc_e_tect.gradle.apionly.transcriberj;

import org.gradle.api.Named;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

import javax.inject.Inject;

/**
 * How the classes for one subscribed contract are generated.
 *
 * <p>Its name is the contract's name, exactly as the {@code apiOnlySubscriber} block
 * subscribes to it.
 */
public abstract class TranscriberJSubscription implements Named {

    /** The description every generated field carries while descriptions are not generated. */
    public static final String DEFAULT_DESCRIPTION_PLACEHOLDER =
            "INTENTIONALLY LEFT BLANK - WILL BE PROVIDED AT A LATER STAGE";

    private final String name;

    /**
     * Creates the settings for one contract.
     *
     * @param name the contract's name
     */
    @Inject
    public TranscriberJSubscription(String name) {
        this.name = name;
    }

    /**
     * The contract's name.
     *
     * @return the name
     */
    @Override
    public String getName() {
        return name;
    }

    /**
     * The package the core classes are generated into. Required: there is no default.
     *
     * @return the base package
     */
    public abstract Property<String> getBasePackage();

    /**
     * The source sets the generated classes are compiled with.
     *
     * <p>Default: {@code test}.
     *
     * @return the source set names
     */
    public abstract ListProperty<String> getSourceSets();

    /**
     * How many times a recursive reference is followed before the rest of a body is
     * documented as a subsection.
     *
     * <p>Default: 3.
     *
     * @return the depth
     */
    public abstract Property<Integer> getRecursionDepth();

    /**
     * Whether the generated descriptions come from the contract. When they do, a class
     * or field the contract does not describe is reported, and documented with the
     * placeholder.
     *
     * <p>Default: {@code false}. The class tree is for contract testing; documentation
     * is usually generated elsewhere, from text a technical writer owns.
     *
     * @return the setting
     */
    public abstract Property<Boolean> getGenerateDocs();

    /**
     * The description every field carries while descriptions are not generated.
     *
     * <p>Default: {@value #DEFAULT_DESCRIPTION_PLACEHOLDER}.
     *
     * @return the placeholder
     */
    public abstract Property<String> getDescriptionPlaceholder();

    /**
     * The endpoint index the generation writes: the path of every generated operation and
     * inline schema class, keyed {@code ClassName.PATH}. Read-only; it carries the
     * generation task, so a tool reading it runs after the generation.
     *
     * <pre>
     * doppelgangerApiDetector {
     *     propertyFiles.from(apiOnlyTranscriberJ.subscriptions.named('orders').flatMap { it.endpointIndex })
     * }
     * </pre>
     *
     * @return the index
     */
    public abstract RegularFileProperty getEndpointIndex();

    /**
     * Where the sources are generated.
     *
     * <p>Default: {@code build/generated/sources/transcriberj/<contract>}.
     *
     * @return the directory
     */
    public abstract DirectoryProperty getInto();
}
