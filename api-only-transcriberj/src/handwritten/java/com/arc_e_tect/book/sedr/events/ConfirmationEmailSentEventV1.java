package com.arc_e_tect.book.sedr.events;

import java.time.Instant;

/** Event model: components/schemas/ConfirmationEmailSentEventV1. Published by {@code publishConfirmationEmailSent}. */
public record ConfirmationEmailSentEventV1(String username, String emailAddress, Instant occurredAt) implements AuditEvent {

    public static ConfirmationEmailSentEventV1 of(String username, String emailAddress) {
        return new ConfirmationEmailSentEventV1(username, emailAddress, Instant.now());
    }

    @Override
    public String toPayload() {
        return AuditEventPayloads.write(this);
    }
}
