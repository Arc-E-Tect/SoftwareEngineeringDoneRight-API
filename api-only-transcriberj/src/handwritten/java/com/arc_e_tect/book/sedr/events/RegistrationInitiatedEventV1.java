package com.arc_e_tect.book.sedr.events;

import java.time.Instant;

/** Event model: components/schemas/RegistrationInitiatedEventV1. Published by {@code publishRegistrationInitiated}. */
public record RegistrationInitiatedEventV1(String username, String emailAddress, Instant occurredAt) implements AuditEvent {

    public static RegistrationInitiatedEventV1 of(String username, String emailAddress) {
        return new RegistrationInitiatedEventV1(username, emailAddress, Instant.now());
    }

    @Override
    public String toPayload() {
        return AuditEventPayloads.write(this);
    }
}
