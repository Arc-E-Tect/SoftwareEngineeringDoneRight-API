package com.arc_e_tect.gradle.apionly.transcriberj.model;

import java.util.Map;

/**
 * An operation or path-item parameter.
 *
 * @param reference   the {@code $ref} this parameter was written as, or {@code null}; when
 *                    present, every other field is the referenced component's
 * @param name        {@code name}, or {@code null}
 * @param in          {@code in}: {@code path}, {@code query}, {@code header} or {@code cookie}; or {@code null}
 * @param required    {@code required}, or {@code null}
 * @param description {@code description}, or {@code null}
 * @param schema      {@code schema}, or {@code null}
 * @param other       every other key, as parsed
 */
public record Parameter(Reference reference, String name, String in, Boolean required, String description, Schema schema,
                        Map<String, Object> other) {
}
