package com.arc_e_tect.book.sedr.events;

import java.time.Instant;

/** Event model: components/schemas/RegistrationAttemptedWithTakenEmailAddressEventV1. Published by {@code publishRegistrationAttemptedWithTakenEmailAddress}. */
public record RegistrationAttemptedWithTakenEmailAddressEventV1(String emailAddress, String attemptedUsername, Instant occurredAt) implements AuditEvent {

    public static RegistrationAttemptedWithTakenEmailAddressEventV1 of(String emailAddress, String attemptedUsername) {
        return new RegistrationAttemptedWithTakenEmailAddressEventV1(emailAddress, attemptedUsername, Instant.now());
    }

    @Override
    public String toPayload() {
        return AuditEventPayloads.write(this);
    }
}
