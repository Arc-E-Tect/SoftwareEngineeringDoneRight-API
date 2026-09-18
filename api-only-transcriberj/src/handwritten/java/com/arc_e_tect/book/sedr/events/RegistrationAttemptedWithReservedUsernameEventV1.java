package com.arc_e_tect.book.sedr.events;

import java.time.Instant;

/** Event model: components/schemas/RegistrationAttemptedWithReservedUsernameEventV1. Published by {@code publishRegistrationAttemptedWithReservedUsername}. */
public record RegistrationAttemptedWithReservedUsernameEventV1(String username, String emailAddress, Instant occurredAt) implements AuditEvent {

    public static RegistrationAttemptedWithReservedUsernameEventV1 of(String username, String emailAddress) {
        return new RegistrationAttemptedWithReservedUsernameEventV1(username, emailAddress, Instant.now());
    }

    @Override
    public String toPayload() {
        return AuditEventPayloads.write(this);
    }
}
