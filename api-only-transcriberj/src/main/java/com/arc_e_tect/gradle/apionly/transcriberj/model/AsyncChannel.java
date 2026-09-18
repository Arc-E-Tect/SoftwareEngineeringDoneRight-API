package com.arc_e_tect.gradle.apionly.transcriberj.model;

import java.util.List;

/**
 * A channel of an AsyncAPI contract: an address messages travel over.
 *
 * @param key         its key under {@code channels}, as the bundler chose it
 * @param provenance  where the channel fragment came from
 * @param address     {@code address}, the address on the broker, or {@code null}
 * @param title       {@code title}, or {@code null}
 * @param description {@code description}, or {@code null}
 * @param messages    the messages it carries, in declaration order
 * @param location    the JSON pointer of the channel in its document
 */
public record AsyncChannel(
        String key,
        Provenance provenance,
        String address,
        String title,
        String description,
        List<AsyncMessage> messages,
        String location) {
}
