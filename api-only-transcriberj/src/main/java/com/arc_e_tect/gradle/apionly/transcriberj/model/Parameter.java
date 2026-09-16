package com.arc_e_tect.gradle.apionly.transcriberj.model;

import java.util.Map;

/**
 * An operation or path-item parameter.
 *
 * @param name        {@code name}, or {@code null}
 * @param in          {@code in}: {@code path}, {@code query}, {@code header} or {@code cookie}; or {@code null}
 * @param required    {@code required}, or {@code null}
 * @param description {@code description}, or {@code null}
 * @param schema      {@code schema}, or {@code null}
 * @param other       every other key, as parsed; a {@code $ref} parameter has only this
 */
public record Parameter(String name, String in, Boolean required, String description, Schema schema,
                        Map<String, Object> other) {
}
