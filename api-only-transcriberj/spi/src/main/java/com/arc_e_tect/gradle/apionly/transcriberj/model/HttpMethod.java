package com.arc_e_tect.gradle.apionly.transcriberj.model;

import java.util.Locale;
import java.util.Optional;

/** The HTTP methods an OpenAPI path item can hold an operation for. */
public enum HttpMethod {
    /** {@code get}. */
    GET,
    /** {@code put}. */
    PUT,
    /** {@code post}. */
    POST,
    /** {@code delete}. */
    DELETE,
    /** {@code options}. */
    OPTIONS,
    /** {@code head}. */
    HEAD,
    /** {@code patch}. */
    PATCH,
    /** {@code trace}. */
    TRACE;

    /**
     * The key a path item holds this method's operation under.
     *
     * @return the lower-case key
     */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * The method a path-item key names.
     *
     * @param key a path-item key
     * @return the method, or empty when the key names none; keys are lower case
     */
    public static Optional<HttpMethod> fromKey(String key) {
        for (HttpMethod method : values()) {
            if (method.key().equals(key)) return Optional.of(method);
        }
        return Optional.empty();
    }
}
