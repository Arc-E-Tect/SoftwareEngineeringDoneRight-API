package com.arc_e_tect.book.sedr.events;

import java.time.Instant;

/** Event model: components/schemas/RegistrationAttemptedWithTakenUsernameEventV1. Published by {@code publishRegistrationAttemptedWithTakenUsername}. */
public record RegistrationAttemptedWithTakenUsernameEventV1(String username, String emailAddress, Instant occurredAt) implements AuditEvent {

    public static RegistrationAttemptedWithTakenUsernameEventV1 of(String username, String emailAddress) {
        return new RegistrationAttemptedWithTakenUsernameEventV1(username, emailAddress, Instant.now());
    }

    @Override
    public String toPayload() {
        return AuditEventPayloads.write(this);
    }
}
