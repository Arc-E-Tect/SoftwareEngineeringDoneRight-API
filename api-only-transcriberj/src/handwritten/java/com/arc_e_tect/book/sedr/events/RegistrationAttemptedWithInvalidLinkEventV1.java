package com.arc_e_tect.book.sedr.events;

import java.time.Instant;

/** Event model: components/schemas/RegistrationAttemptedWithInvalidLinkEventV1. Published by {@code publishRegistrationAttemptedWithInvalidLink}. */
public record RegistrationAttemptedWithInvalidLinkEventV1(String verificationLink, Instant occurredAt) implements AuditEvent {

    public static RegistrationAttemptedWithInvalidLinkEventV1 of(String verificationLink) {
        return new RegistrationAttemptedWithInvalidLinkEventV1(verificationLink, Instant.now());
    }

    @Override
    public String toPayload() {
        return AuditEventPayloads.write(this);
    }
}
