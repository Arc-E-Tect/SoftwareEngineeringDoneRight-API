package com.arc_e_tect.book.sedr.events;

import tools.jackson.databind.json.JsonMapper;

/** Shared JSON rendering for every {@link AuditEvent}'s {@code toPayload()}. Not a public API of its own. */
final class AuditEventPayloads {

    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    private AuditEventPayloads() {
    }

    static String write(Object event) {
        return JSON_MAPPER.writeValueAsString(event);
    }
}
