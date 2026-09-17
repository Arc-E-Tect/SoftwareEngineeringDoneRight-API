package com.arc_e_tect.gradle.apionly.transcriberj.model;

import java.util.Map;

/**
 * One entry of a {@code content} map.
 *
 * @param contentType the media type, such as {@code application/problem+json}
 * @param schema      {@code schema}, or {@code null}
 * @param other       every other key, as parsed
 */
public record MediaType(String contentType, Schema schema, Map<String, Object> other) {
}
