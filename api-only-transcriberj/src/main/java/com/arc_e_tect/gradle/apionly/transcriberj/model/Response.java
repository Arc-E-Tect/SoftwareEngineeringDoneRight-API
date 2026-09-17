package com.arc_e_tect.gradle.apionly.transcriberj.model;

import java.util.List;
import java.util.Map;

/**
 * One response of an operation.
 *
 * @param status      its key in {@code responses}: a status code such as {@code 404}, a
 *                    range such as {@code 4XX}, or {@code default}; {@code null} for a
 *                    reusable response component
 * @param reference   the {@code $ref} this response was written as, or {@code null}; when
 *                    present, every other field is the referenced component's
 * @param description {@code description}, or {@code null}
 * @param content     {@code content} in declaration order, or {@code null}
 * @param other       every other key -- {@code headers}, {@code links} -- as parsed
 */
public record Response(String status, Reference reference, String description, List<MediaType> content, Map<String, Object> other) {
}
