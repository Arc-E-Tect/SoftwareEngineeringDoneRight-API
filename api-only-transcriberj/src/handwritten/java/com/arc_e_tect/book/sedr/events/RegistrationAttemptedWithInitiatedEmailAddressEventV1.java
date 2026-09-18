package com.arc_e_tect.book.sedr.events;

import java.time.Instant;

/** Event model: components/schemas/RegistrationAttemptedWithInitiatedEmailAddressEventV1. Published by {@code publishRegistrationAttemptedWithInitiatedEmailAddress}. */
public record RegistrationAttemptedWithInitiatedEmailAddressEventV1(String emailAddress, String attemptedUsername, Instant occurredAt) implements AuditEvent {

    public static RegistrationAttemptedWithInitiatedEmailAddressEventV1 of(String emailAddress, String attemptedUsername) {
        return new RegistrationAttemptedWithInitiatedEmailAddressEventV1(emailAddress, attemptedUsername, Instant.now());
    }

    @Override
    public String toPayload() {
        return AuditEventPayloads.write(this);
    }
}
