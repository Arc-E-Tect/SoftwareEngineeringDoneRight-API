package com.arc_e_tect.gradle.apionly.transcriberj.model;

/**
 * Where a component came from.
 *
 * @param fragmentPath the component's {@code x-fragment-path}: its fragment's path
 *                     in the specification library, relative to the library's
 *                     source root; {@code null} when the contract does not say
 * @param sha256       the lower-case hex SHA-256 of the component as bundled,
 *                     {@code x-fragment-path} included, in RFC 8785 canonical JSON
 */
public record Provenance(String fragmentPath, String sha256) {
}
