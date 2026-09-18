package com.arc_e_tect.book.sedr.events;

import java.time.Instant;

/** Event model: components/schemas/NewVerificationEmailSentEventV1. Published by {@code publishNewVerificationEmailSent}. */
public record NewVerificationEmailSentEventV1(String username, String emailAddress, Instant occurredAt) implements AuditEvent {

    public static NewVerificationEmailSentEventV1 of(String username, String emailAddress) {
        return new NewVerificationEmailSentEventV1(username, emailAddress, Instant.now());
    }

    @Override
    public String toPayload() {
        return AuditEventPayloads.write(this);
    }
}
