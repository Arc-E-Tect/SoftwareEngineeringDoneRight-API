package com.arc_e_tect.gradle.apionly.transcriberj.spi;

import com.arc_e_tect.gradle.apionly.transcriberj.model.Construct;

import java.util.List;
import java.util.Set;

/**
 * A dialect of generated code: REST Docs descriptors, WireMock stubs, and the like.
 *
 * <p>Implementations need a public no-argument constructor, as {@link java.util.ServiceLoader}
 * requires, and must not keep state between calls to {@link #emit(EmitterContext)}.
 */
public interface Emitter {

    /**
     * The emitter's identifier: lower case, such as {@code restdocs}. It names the
     * package its companion classes go in, under the base package.
     *
     * @return the identifier
     */
    String id();

    /**
     * The libraries the generated code needs, which the plugin adds to the source
     * sets it generates into when the project does not declare them itself.
     *
     * @return the dependencies; empty when the generated code needs none
     */
    List<ManagedDependency> dependencies();

    /**
     * The classified constructs this emitter represents in full. For any other
     * construct in a class, the emitter writes the affected method with
     * {@link EmitterContext#degraded(String, String, com.arc_e_tect.gradle.apionly.transcriberj.model.Finding)}.
     *
     * @return the constructs represented
     */
    Set<Construct> represents();

    /**
     * Writes this emitter's classes for one contract.
     *
     * @param context the contract, the settings, the class names and where to write
     */
    void emit(EmitterContext context);
}
