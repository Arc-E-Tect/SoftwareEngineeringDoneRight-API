package com.arc_e_tect.gradle.apionly.transcriberj.model;

import java.util.List;
import java.util.Map;

/**
 * A JSON Schema, as a bundled contract writes it.
 *
 * <p>A schema written as the literal {@code true} or {@code false} has that
 * {@code literal} and nothing else.
 *
 * @param literal              {@code true} or {@code false} for a boolean schema, otherwise {@code null}
 * @param ref                  the name of the schema component {@code $ref} points at, or {@code null}
 * @param types                {@code type}, as a list even when written as a single string, or {@code null}
 * @param typeWrittenAsList    whether {@code type} was written as a list
 * @param format               {@code format}, or {@code null}
 * @param description          {@code description}, or {@code null}
 * @param constValue           {@code const}, or {@code null} when absent
 * @param enumValues           {@code enum}, or {@code null}
 * @param required             {@code required}, or {@code null}
 * @param properties           {@code properties} in declaration order, or {@code null}
 * @param patternProperties    {@code patternProperties} in declaration order, or {@code null}
 * @param items                {@code items}, or {@code null}
 * @param additionalProperties {@code additionalProperties}, or {@code null}
 * @param allOf                {@code allOf}, or {@code null}
 * @param oneOf                {@code oneOf}, or {@code null}
 * @param anyOf                {@code anyOf}, or {@code null}
 * @param discriminator        {@code discriminator}, or {@code null}
 * @param constraints          the validation keywords; never {@code null}
 * @param annotations          keywords that describe rather than constrain -- {@code title},
 *                             {@code examples}, {@code default}, {@code x-} extensions and the
 *                             like -- as parsed, in declaration order
 * @param unmodelled           every other keyword, as parsed; each is also a {@link Finding}
 */
public record Schema(
        Boolean literal,
        String ref,
        List<String> types,
        boolean typeWrittenAsList,
        String format,
        String description,
        Const constValue,
        List<Object> enumValues,
        List<String> required,
        Map<String, Schema> properties,
        Map<String, Schema> patternProperties,
        Schema items,
        AdditionalProperties additionalProperties,
        List<Schema> allOf,
        List<Schema> oneOf,
        List<Schema> anyOf,
        Discriminator discriminator,
        Constraints constraints,
        Map<String, Object> annotations,
        Map<String, Object> unmodelled) {
}
