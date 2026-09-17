package com.arc_e_tect.gradle.apionly.transcriberj.model;

/**
 * A schema component.
 *
 * @param name       its key under {@code components.schemas}, as the bundler chose
 *                   it; not a basis for naming, since the bundler renames on collision
 * @param provenance where it came from
 * @param schema     the schema, without its {@code x-fragment-path}
 */
public record Component(String name, Provenance provenance, Schema schema) {
}
