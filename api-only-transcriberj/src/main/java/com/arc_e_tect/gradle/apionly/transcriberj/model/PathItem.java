package com.arc_e_tect.gradle.apionly.transcriberj.model;

import java.util.List;
import java.util.Map;

/**
 * One entry of {@code paths}.
 *
 * @param path       the path, such as {@code /v1/users/{username}}
 * @param parameters the parameters shared by its operations, or {@code null}
 * @param operations its operations, in declaration order
 * @param other      every other key, as parsed
 */
public record PathItem(String path, List<Parameter> parameters, List<Operation> operations,
                       Map<String, Object> other) {
}
