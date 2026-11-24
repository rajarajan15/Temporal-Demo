package com.occlet.issuance.ports;

import java.util.List;

/** @param nextCursor the card ID to continue after, or null on the last page */
public record CardPage(List<ProcessorCard> cards, String nextCursor) {

    public record ProcessorCard(String cardId, String status) {}
}
