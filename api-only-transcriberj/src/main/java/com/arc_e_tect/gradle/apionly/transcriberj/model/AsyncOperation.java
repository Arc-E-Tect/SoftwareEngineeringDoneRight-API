package com.arc_e_tect.gradle.apionly.transcriberj.model;

import java.util.List;

/**
 * An operation of an AsyncAPI contract: an application sending or receiving on a
 * channel.
 *
 * @param operationId its key under {@code operations}, which AsyncAPI 3 makes the
 *                    operation's id
 * @param action      {@code action}: {@code send} or {@code receive}
 * @param channelKey  the key of the channel it acts on, or {@code null} when the
 *                    document does not point at one this model understands
 * @param messageKeys the keys of the channel's messages it names, in order; empty
 *                    when it names none, which means every message of the channel
 * @param summary     {@code summary}, or {@code null}
 * @param description {@code description}, or {@code null}
 * @param location    the JSON pointer of the operation in its document
 */
public record AsyncOperation(
        String operationId,
        String action,
        String channelKey,
        List<String> messageKeys,
        String summary,
        String description,
        String location) {
}
