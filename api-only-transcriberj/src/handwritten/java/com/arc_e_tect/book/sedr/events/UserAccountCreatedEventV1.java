package com.arc_e_tect.book.sedr.events;

import java.time.Instant;

/** Event model: components/schemas/UserAccountCreatedEventV1. Published by {@code publishUserAccountCreated}. */
public record UserAccountCreatedEventV1(String username, String emailAddress, Instant occurredAt) implements AuditEvent {

    public static UserAccountCreatedEventV1 of(String username, String emailAddress) {
        return new UserAccountCreatedEventV1(username, emailAddress, Instant.now());
    }

    @Override
    public String toPayload() {
        return AuditEventPayloads.write(this);
    }
}
