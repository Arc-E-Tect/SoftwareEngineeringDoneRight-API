package com.arc_e_tect.gradle.apionly.transcriberj;

import org.gradle.api.Named;
import org.gradle.api.file.DirectoryProperty;
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
     * The description every field carries while descriptions are not generated.
     *
     * <p>Default: {@value #DEFAULT_DESCRIPTION_PLACEHOLDER}.
     *
     * @return the placeholder
     */
    public abstract Property<String> getDescriptionPlaceholder();

    /**
     * Where the sources are generated.
     *
     * <p>Default: {@code build/generated/sources/transcriberj/<contract>}.
     *
     * @return the directory
     */
    public abstract DirectoryProperty getInto();
}
