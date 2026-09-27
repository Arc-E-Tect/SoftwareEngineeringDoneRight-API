package com.arc_e_tect.gradle.apionly.transcriberj;

import org.gradle.api.Named;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;

import javax.inject.Inject;

/**
 * One emitter's configuration for one subscription: the {@code emitter('<id>')} block.
 *
 * <pre>
 * apiOnlyTranscriberJ {
 *     subscription('orders') {
 *         emitter('restdocs') {
 *             sourceSets = ['testContract', 'testContractWireMock']
 *             options = [tests: 'true']
 *         }
 *     }
 * }
 * </pre>
 */
public abstract class EmitterSpec implements Named {

    private final String id;

    /**
     * Creates the block for one emitter.
     *
     * @param id the emitter's id, such as {@code restdocs}
     */
    @Inject
    public EmitterSpec(String id) {
        this.id = id;
    }

    /**
     * The emitter's id.
     *
     * @return the id
     */
    @Override
    public String getName() {
        return id;
    }

    /**
     * The source sets the emitter's Java sources and classpath resources go to. Each must be one
     * of the subscription's {@code sourceSets}, so that it sees the schema classes.
     *
     * <p>Default: the subscription's {@code sourceSets} for an emitter that writes Java or
     * resources, and none for one that writes only files. An empty list is allowed only for the
     * latter.
     *
     * @return the source set names
     */
    public abstract ListProperty<String> getSourceSets();

    /**
     * The emitter's options, passed to it unchanged; what each means is the emitter's README's
     * to say.
     *
     * <p>Default: none.
     *
     * @return the options
     */
    public abstract MapProperty<String, String> getOptions();

    /**
     * Where the emitter's Java sources are generated.
     *
     * <p>Default: {@code build/generated/sources/transcriberj/<contract>/<id>}.
     *
     * @return the directory
     */
    public abstract DirectoryProperty getIntoJava();

    /**
     * Where the emitter's classpath resources are generated.
     *
     * <p>Default: {@code build/generated/resources/transcriberj/<contract>/<id>}.
     *
     * @return the directory
     */
    public abstract DirectoryProperty getIntoResources();

    /**
     * Where the emitter's files, which belong on no classpath, are generated.
     *
     * <p>Default: {@code build/generated/files/transcriberj/<contract>/<id>}.
     *
     * @return the directory
     */
    public abstract DirectoryProperty getIntoFiles();
}
