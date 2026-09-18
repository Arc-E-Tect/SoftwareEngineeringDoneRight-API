package com.arc_e_tect.book.sedr.events;

import java.time.Instant;

/** Event model: components/schemas/VerificationEmailResendRequestedEventV1. Published by {@code publishVerificationEmailResendRequested}. */
public record VerificationEmailResendRequestedEventV1(String username, String emailAddress, Instant occurredAt) implements AuditEvent {

    public static VerificationEmailResendRequestedEventV1 of(String username, String emailAddress) {
        return new VerificationEmailResendRequestedEventV1(username, emailAddress, Instant.now());
    }

    @Override
    public String toPayload() {
        return AuditEventPayloads.write(this);
    }
}
