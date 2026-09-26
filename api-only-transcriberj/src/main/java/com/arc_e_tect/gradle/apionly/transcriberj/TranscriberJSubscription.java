package com.arc_e_tect.gradle.apionly.transcriberj;

import org.gradle.api.Named;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;

import java.util.Map;

import javax.inject.Inject;

/**
 * How the classes for one subscribed contract are generated.
 *
 * <p>Its name is the contract's name, exactly as the {@code apiOnlySubscriber} block
 * subscribes to it.
 */
public abstract class TranscriberJSubscription implements Named {

    /** The description of a class or field the contract does not describe, when descriptions are generated. */
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
     * The description of a class or field the contract does not describe, when
     * {@link #getGenerateDocs()} is on: it says the description is still to come.
     * With it off, every description is the empty string, and this is not used.
     *
     * <p>Default: {@value #DEFAULT_DESCRIPTION_PLACEHOLDER}.
     *
     * @return the placeholder
     */
    public abstract Property<String> getDescriptionPlaceholder();

    /**
     * The base name of a {@link java.util.ResourceBundle} the generated classes resolve
     * their descriptions through, such as {@code docs.Descriptions}. The project owns the
     * text; what generation produced -- the contract's description, or the placeholder --
     * is the fallback for a key the bundle does not carry.
     *
     * <p>Unset by default, and then the generated classes are the ones generated before
     * there were bundles: the text is compiled into them, and nothing is resolved.
     *
     * <p>{@code -Dapionly.descriptions.bundle} names another bundle for one run, and
     * {@code -Dapionly.descriptions.locale} the locale to resolve in, which is what a
     * documentation build rendering several languages from one generated tree uses.
     *
     * @return the bundle's base name
     */
    public abstract Property<String> getDescriptionBundle();

    /**
     * The response status that means "the request is invalid". An invalid-request case is
     * derived for an operation only when it declares exactly this status; an operation that
     * constrains its request input without declaring it is reported as a gap.
     *
     * <p>Default: {@code "400"}.
     *
     * @return the status
     */
    public abstract Property<String> getInvalidRequestStatus();

    /**
     * Whether a request object that does not declare {@code additionalProperties} forbids
     * members it does not declare, so that an unknown-member case is derived for it. An
     * explicit {@code additionalProperties: true}, an {@code additionalProperties} schema,
     * or {@code patternProperties} is respected either way.
     *
     * <p>Default: {@code true}. Turning it off prints a warning on every generation,
     * citing OWASP API3:2023 and API10:2023, and records it in the report.
     *
     * @return the setting
     */
    public abstract Property<Boolean> getStrictRequests();

    /**
     * The formats, such as {@code email}, an invalid-request case is derived for. A format
     * beside a pattern never gets one: the pattern wins.
     *
     * <p>Default: none.
     *
     * @return the format names
     */
    public abstract ListProperty<String> getValidateFormats();

    /**
     * Options for the emitters, by emitter id, each a map of names to values. The
     * TranscriberJ passes them to the emitters unchanged, and fails the build when one
     * names an emitter that is not on the {@code transcriberjEmitters} classpath.
     *
     * <pre>
     * emitterOptions = [restdocs: [tests: 'true']]
     * </pre>
     *
     * <p>Default: none.
     *
     * @return the options
     */
    public abstract MapProperty<String, Map<String, String>> getEmitterOptions();

    /**
     * Where the generation writes its report: every degraded method, recommendation and
     * undecided construct.
     *
     * <p>Default: {@code build/reports/transcriberj/<contract>.txt}.
     *
     * @return the report file
     */
    public abstract RegularFileProperty getReportFile();

    /**
     * Where the generation writes the endpoint index. A tool reads the index through
     * {@link #getEndpointIndex()}, which carries the generation task.
     *
     * <p>Default: {@code build/generated/transcriberj-index/<contract>/contract-endpoints.properties}.
     *
     * @return the index file
     */
    public abstract RegularFileProperty getEndpointIndexFile();

    /**
     * The endpoint index the generation writes: the path of every generated operation and
     * inline schema class, keyed {@code ClassName.PATH}. Read-only; it carries the
     * generation task, so a tool reading it runs after the generation.
     * {@link #getEndpointIndexFile()} says where it is written.
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

    /**
     * Where a resource an emitter writes is generated.
     *
     * <p>Default: {@code build/generated/resources/transcriberj/<contract>}.
     *
     * @return the directory
     */
    public abstract DirectoryProperty getIntoResources();
}
