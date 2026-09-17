package com.arc_e_tect.gradle.apionly.transcriberj.model;

import java.util.Map;

/**
 * A {@code $ref} written where a parameter, request body or response would be.
 *
 * <p>The object carrying it is resolved: its typed fields are those of the
 * component the reference leads to, following references to references. What
 * the reference itself says is kept here.
 *
 * @param name        the name of the component the reference names directly
 * @param summary     the reference's own {@code summary}, or {@code null}
 * @param description the reference's own {@code description}, or {@code null}; when
 *                    present, OpenAPI 3.1 has it override the component's
 * @param other       any other key written beside the {@code $ref}, as parsed;
 *                    OpenAPI has these ignored
 */
public record Reference(String name, String summary, String description, Map<String, Object> other) {
}
