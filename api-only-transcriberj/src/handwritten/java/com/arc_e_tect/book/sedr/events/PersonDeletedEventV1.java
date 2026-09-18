package com.arc_e_tect.book.sedr.events;

import java.time.Instant;

/** Event model: components/schemas/PersonDeletedEventV1. Published by {@code publishPersonDeleted}. */
public record PersonDeletedEventV1(String username, String emailAddress, Instant occurredAt) implements AuditEvent {

    public static PersonDeletedEventV1 of(String username, String emailAddress) {
        return new PersonDeletedEventV1(username, emailAddress, Instant.now());
    }

    @Override
    public String toPayload() {
        return AuditEventPayloads.write(this);
    }
}
