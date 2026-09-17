package com.arc_e_tect.gradle.apionly.transcriberj.model;

/**
 * A reusable component other than a schema: a response, parameter or request body.
 *
 * @param name       its key under {@code components.<type>}, as the bundler chose it
 * @param provenance where it came from; hashed as bundled, as a schema component is
 * @param value      the component, without its {@code x-fragment-path}
 * @param <T>        {@link Response}, {@link Parameter} or {@link RequestBody}
 */
public record Reusable<T>(String name, Provenance provenance, T value) {
}
