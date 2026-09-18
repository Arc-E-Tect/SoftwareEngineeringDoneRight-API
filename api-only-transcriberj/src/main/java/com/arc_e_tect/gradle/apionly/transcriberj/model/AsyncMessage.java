package com.arc_e_tect.gradle.apionly.transcriberj.model;

/**
 * A message a channel carries, as an AsyncAPI bundle inlines it.
 *
 * @param key         its key under the channel's {@code messages}, as the bundler
 *                    chose it; not a basis for naming, as on the OpenAPI side
 * @param provenance  where the message fragment came from
 * @param name        {@code name}, the message's own name, or {@code null}
 * @param title       {@code title}, or {@code null}
 * @param summary     {@code summary}, or {@code null}
 * @param contentType {@code contentType}, or {@code null} when the document does
 *                    not say and the default applies
 * @param payload     the payload schema, as a component of the one model, or
 *                    {@code null} when the message declares none
 * @param location    the JSON pointer of the message in its document
 */
public record AsyncMessage(
        String key,
        Provenance provenance,
        String name,
        String title,
        String summary,
        String contentType,
        Component payload,
        String location) {
}
