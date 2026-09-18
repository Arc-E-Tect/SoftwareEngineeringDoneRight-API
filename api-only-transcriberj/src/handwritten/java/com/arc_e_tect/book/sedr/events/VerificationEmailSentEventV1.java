package com.arc_e_tect.book.sedr.events;

import java.time.Instant;

/** Event model: components/schemas/VerificationEmailSentEventV1. Published by {@code publishVerificationEmailSent}. */
public record VerificationEmailSentEventV1(String username, String emailAddress, Instant occurredAt) implements AuditEvent {

    public static VerificationEmailSentEventV1 of(String username, String emailAddress) {
        return new VerificationEmailSentEventV1(username, emailAddress, Instant.now());
    }

    @Override
    public String toPayload() {
        return AuditEventPayloads.write(this);
    }
}
