package com.arc_e_tect.gradle.apionly.transcriberj.model;

/**
 * A schema component.
 *
 * <p>An OpenAPI bundle hoists every schema into {@code components.schemas}. An
 * AsyncAPI bundle inlines a message payload where it is used, so a payload is a
 * component of this model too, and says where it was found rather than pretending
 * to a components key it never had.
 *
 * @param name       its key under {@code components.schemas}, as the bundler chose
 *                   it, or the message key a payload was inlined under; not a basis
 *                   for naming, since the bundler renames on collision
 * @param provenance where it came from
 * @param schema     the schema, without its {@code x-fragment-path}
 * @param location   the JSON pointer of the component in its document
 */
public record Component(String name, Provenance provenance, Schema schema, String location) {
}
