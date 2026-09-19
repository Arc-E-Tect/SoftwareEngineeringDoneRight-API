package com.arc_e_tect.gradle.apionly.transcriberj.spi;

import com.arc_e_tect.gradle.apionly.transcriberj.model.ContractModel;
import com.arc_e_tect.gradle.apionly.transcriberj.model.Finding;

/** What an emitter is given to write one contract's classes. */
public interface EmitterContext {

    /**
     * The contract.
     *
     * @return the model
     */
    ContractModel model();

    /**
     * How the project has asked for the classes to be generated.
     *
     * @return the settings
     */
    Settings settings();

    /**
     * The core classes, whose names an emitter's companions follow.
     *
     * @return the class names
     */
    ClassNames names();

    /**
     * Writes one Java source file, replacing any file already written for that class
     * in this run.
     *
     * @param packageName the package
     * @param simpleName  the class's simple name
     * @param source      the whole compilation unit
     */
    void writeJava(String packageName, String simpleName, String source);

    /**
     * Writes one resource file, replacing any file already written at that path in
     * this run -- a properties file a generated or hand-written class reads through
     * {@code getResourceAsStream}, for instance.
     *
     * <p>{@code path} is {@code /}-separated and relative to the resource root, and
     * may nest in directories, such as {@code "META-INF/emitter/service.properties"}.
     * It may not be absolute, and may not use empty, {@code .} or {@code ..} segments.
     *
     * @param path    the resource's path within the resource root
     * @param content the resource's content, written UTF-8
     */
    void writeResource(String path, String content);

    /**
     * Records that a generated method cannot represent a construct, and returns the
     * statement its body consists of instead: one that throws
     * {@link UnsupportedOperationException} with the reason and, where there is one,
     * the remedy. Every degraded method is reported at the end of generation.
     *
     * @param className the simple name of the class the method is in
     * @param method    the method, as a reader would name it, such as {@code body(String)}
     * @param finding   the construct it cannot represent
     * @return a Java statement, without indentation
     */
    String degraded(String className, String method, Finding finding);
}
