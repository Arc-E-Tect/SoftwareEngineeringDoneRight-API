package com.arc_e_tect.gradle.apionly.transcriberj.model;

import java.util.List;
import java.util.Map;

/**
 * An operation's request body.
 *
 * @param reference   the {@code $ref} this request body was written as, or {@code null}; when
 *                    present, every other field is the referenced component's
 * @param description {@code description}, or {@code null}
 * @param required    {@code required}, or {@code null}
 * @param content     {@code content} in declaration order, or {@code null}
 * @param other       every other key, as parsed
 */
public record RequestBody(Reference reference, String description, Boolean required, List<MediaType> content,
                          Map<String, Object> other) {
}
