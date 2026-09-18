package com.arc_e_tect.book.sedr.events;

import java.time.Instant;

/** Event model: components/schemas/RegistrationAttemptedWithExpiredRegistrationProcessEventV1. Published by {@code publishRegistrationAttemptedWithExpiredRegistrationProcess}. */
public record RegistrationAttemptedWithExpiredRegistrationProcessEventV1(String username, String emailAddress, Instant occurredAt) implements AuditEvent {

    public static RegistrationAttemptedWithExpiredRegistrationProcessEventV1 of(String username, String emailAddress) {
        return new RegistrationAttemptedWithExpiredRegistrationProcessEventV1(username, emailAddress, Instant.now());
    }

    @Override
    public String toPayload() {
        return AuditEventPayloads.write(this);
    }
}
