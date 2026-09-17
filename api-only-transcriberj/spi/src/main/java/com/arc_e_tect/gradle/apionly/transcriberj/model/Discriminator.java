package com.arc_e_tect.gradle.apionly.transcriberj.model;

import java.util.Map;

/**
 * A {@code discriminator}.
 *
 * @param propertyName the discriminating property, or {@code null}
 * @param mapping      discriminator values to schema references, or {@code null}
 * @param other        every other key, as parsed
 */
public record Discriminator(String propertyName, Map<String, String> mapping, Map<String, Object> other) {
}
