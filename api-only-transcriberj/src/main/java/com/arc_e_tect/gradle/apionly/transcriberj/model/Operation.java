package com.arc_e_tect.gradle.apionly.transcriberj.model;

import java.util.List;
import java.util.Map;

/**
 * One operation: a method on a path.
 *
 * @param path        the path it is under, such as {@code /v1/users/{username}}
 * @param method      its method
 * @param operationId {@code operationId}, or {@code null}
 * @param tags        {@code tags}, or {@code null}
 * @param summary     {@code summary}, or {@code null}
 * @param description {@code description}, or {@code null}
 * @param parameters  its own {@code parameters}, not those of its path item; or {@code null}
 * @param requestBody {@code requestBody}, or {@code null}
 * @param responses   {@code responses} in declaration order, or {@code null}
 * @param other       every other key -- {@code security}, {@code deprecated} and the like -- as parsed
 */
public record Operation(
        String path,
        HttpMethod method,
        String operationId,
        List<String> tags,
        String summary,
        String description,
        List<Parameter> parameters,
        RequestBody requestBody,
        List<Response> responses,
        Map<String, Object> other) {
}
