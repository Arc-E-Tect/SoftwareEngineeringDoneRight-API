package com.arc_e_tect.book.sedr.events;

/**
 * Common shape of this adapter's own event model -- the Kafka outbound adapter's
 * counterpart to the domain layer's information model ({@code Person}, {@code UserAccount})
 * and the persistence adapter's data model: one class per {@code components/schemas/*EventV1}
 * entry in {@code asyncapi.yaml}, each knowing how to render itself as that schema's
 * JSON payload. {@code KafkaAuditEventPublisherAdapter} only ever constructs one of these
 * from the domain arguments it receives and asks it for its own payload -- it does not
 * shape JSON itself.
 */
public interface AuditEvent {

    /** This event's own wire payload, conforming to its {@code *EventV1} schema. */
    String toPayload();
}
