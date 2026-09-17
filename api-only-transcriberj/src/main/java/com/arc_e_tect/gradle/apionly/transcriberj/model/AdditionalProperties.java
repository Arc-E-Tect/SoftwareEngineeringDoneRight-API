package com.arc_e_tect.gradle.apionly.transcriberj.model;

/**
 * The value of {@code additionalProperties}: a boolean, or a schema.
 *
 * @param allowed the boolean, or {@code null} when the value is a schema
 * @param schema  the schema, or {@code null} when the value is a boolean
 */
public record AdditionalProperties(Boolean allowed, Schema schema) {
}
