package com.occlet.issuance.contracts;

/** A processor callback as delivered to a workflow. The event ID is used to ignore duplicates. */
public record ProcessorEvent(String eventId, String cardId, String type) {
    public static final String CARD_CREATED = "CARD_CREATED";
}
