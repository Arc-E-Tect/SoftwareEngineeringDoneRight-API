package com.arc_e_tect.gradle.apionly.transcriberj.model;

/**
 * A schema's validation keywords. Each is {@code null} when the schema does not
 * declare it; numbers are as parsed.
 *
 * @param minLength        {@code minLength}
 * @param maxLength        {@code maxLength}
 * @param pattern          {@code pattern}
 * @param minimum          {@code minimum}
 * @param maximum          {@code maximum}
 * @param exclusiveMinimum {@code exclusiveMinimum}: a number in OpenAPI 3.1, a boolean in 3.0
 * @param exclusiveMaximum {@code exclusiveMaximum}: a number in OpenAPI 3.1, a boolean in 3.0
 * @param multipleOf       {@code multipleOf}
 * @param minItems         {@code minItems}
 * @param maxItems         {@code maxItems}
 * @param uniqueItems      {@code uniqueItems}
 * @param minProperties    {@code minProperties}
 * @param maxProperties    {@code maxProperties}
 */
public record Constraints(
        Number minLength,
        Number maxLength,
        String pattern,
        Number minimum,
        Number maximum,
        Object exclusiveMinimum,
        Object exclusiveMaximum,
        Number multipleOf,
        Number minItems,
        Number maxItems,
        Boolean uniqueItems,
        Number minProperties,
        Number maxProperties) {
}
