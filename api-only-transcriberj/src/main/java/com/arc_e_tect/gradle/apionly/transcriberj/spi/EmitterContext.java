package com.arc_e_tect.gradle.apionly.transcriberj.spi;

import com.arc_e_tect.gradle.apionly.transcriberj.model.ContractModel;
import com.arc_e_tect.gradle.apionly.transcriberj.model.Finding;

import java.util.List;

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
     * Writes one file that belongs on no classpath -- a mapping file, part of an archive --
     * replacing any file already written at that path in this run. The plugin packages these
     * files; it never compiles them or puts them on a classpath.
     *
     * <p>{@code path} follows the rules of {@link #writeResource(String, String)}: {@code /}-separated,
     * relative, and without empty, {@code .} or {@code ..} segments. Only an emitter whose
     * {@link Emitter#produces(java.util.Map)} includes {@link Output#FILES} may call it.
     *
     * @param path    the file's path within the emitter's files directory
     * @param content the file's bytes
     * @throws UnsupportedOperationException when this context has nowhere to write files: a
     *                                       generation run without a files directory
     */
    default void writeFile(String path, byte[] content) {
        throw new UnsupportedOperationException("This generation run has no files directory, so emitter output "
                + path + " cannot be written.");
    }

    /**
     * Writes one text file that belongs on no classpath, UTF-8 encoded, as
     * {@link #writeFile(String, byte[])} does.
     *
     * @param path    the file's path within the emitter's files directory
     * @param content the file's text
     */
    default void writeFile(String path, String content) {
        writeFile(path, content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

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

    /**
     * The contract cases of an operation, in the order its generated {@code CASES} lists them:
     * success, not found, not acceptable, unsupported media type, then invalid requests. What an
     * emitter that renders cases shapes its code by. The core derives them before any other
     * emitter runs.
     *
     * @param location the JSON pointer of the operation, such as {@code /paths/~1v1~1users/get}
     * @return the cases; empty when the operation has none, or there is no such operation
     */
    default List<ContractCase> contractCases(String location) {
        return List.of();
    }

    /**
     * The schema a request body of an operation must satisfy, as a self-contained JSON Schema
     * 2020-12 document: every {@code $ref} bundled into {@code $defs}, strict as the core's own
     * notion of validity is when {@code strictRequests} is on, and with {@code format} asserted
     * only for the formats {@code validateFormats} names -- any other is written as the
     * annotation {@code x-format}. It is exactly the schema the core generates valid bodies for
     * and derives invalid ones against.
     *
     * @param location  the JSON pointer of the operation
     * @param mediaType the request body's media type, as the operation declares it
     * @return the schema's JSON text; empty when the operation declares no such body or its
     *         schema
     */
    default java.util.Optional<String> requestBodySchema(String location, String mediaType) {
        return java.util.Optional.empty();
    }

    /**
     * The schema a path, query or header parameter of an operation must satisfy, as a
     * self-contained JSON Schema 2020-12 document, made as {@link #requestBodySchema} makes a
     * body's. A value travels as a string: the schema is that of the value it stands for, so a
     * stub matching a path segment by its {@code pattern} reads it here.
     *
     * @param location the JSON pointer of the operation
     * @param in       where the parameter is: {@code path}, {@code query} or {@code header}
     * @param name     the parameter's name
     * @return the schema's JSON text; empty when the operation declares no such parameter or its
     *         schema
     */
    default java.util.Optional<String> parameterSchema(String location, String in, String name) {
        return java.util.Optional.empty();
    }
}
