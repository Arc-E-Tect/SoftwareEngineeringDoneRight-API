package com.arc_e_tect.book.sedr.events;

import java.time.Instant;

/** Event model: components/schemas/RegistrationAttemptedWithExpiredLinkEventV1. Published by {@code publishRegistrationAttemptedWithExpiredLink}. */
public record RegistrationAttemptedWithExpiredLinkEventV1(String username, String emailAddress, Instant occurredAt) implements AuditEvent {

    public static RegistrationAttemptedWithExpiredLinkEventV1 of(String username, String emailAddress) {
        return new RegistrationAttemptedWithExpiredLinkEventV1(username, emailAddress, Instant.now());
    }

    @Override
    public String toPayload() {
        return AuditEventPayloads.write(this);
    }
}
