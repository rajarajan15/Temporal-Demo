package com.occlet.issuance.ports;

import com.occlet.issuance.contracts.CardRef;

/** Accepts and returns only IDs and tokens. */
public interface CardProcessorPort {
    CardRef createCard(String idempotencyKey, String productCode, String accountRef);

    /**
     * Cancels whatever the given creation key produced. Works when the card ID was never learned,
     * and blocks a delayed creation that arrives after the cancel.
     */
    void cancelByCreationKey(String idempotencyKey, String creationKey);

    void setLimits(String idempotencyKey, String cardId, long limitMinor);

    void resetLimits(String idempotencyKey, String cardId);

    void activate(String idempotencyKey, String cardId);

    void deactivate(String idempotencyKey, String cardId);
}
