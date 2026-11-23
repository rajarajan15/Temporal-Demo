package com.occlet.issuance.adapters.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.occlet.issuance.contracts.CardRef;
import com.occlet.issuance.ports.CardProcessorPort;
import com.occlet.issuance.ports.PortException;
import java.util.Map;

public final class HttpCardProcessorAdapter implements CardProcessorPort {
    private final JsonHttpClient client;

    public HttpCardProcessorAdapter(JsonHttpClient client) {
        this.client = client;
    }

    @Override
    public CardRef createCard(String idempotencyKey, String productCode, String accountRef) {
        JsonNode response = client.post("/processor/cards", idempotencyKey,
                Map.of("productCode", productCode, "accountRef", accountRef));
        String cardId = response.path("cardId").asText("");
        String token = response.path("token").asText("");
        if (cardId.isEmpty() || token.isEmpty()) {
            throw new PortException.Technical("MALFORMED_RESPONSE");
        }
        return new CardRef(cardId, token);
    }

    @Override
    public void cancelByCreationKey(String idempotencyKey, String creationKey) {
        client.post("/processor/cards/cancel", idempotencyKey, Map.of("creationKey", creationKey));
    }

    @Override
    public void setLimits(String idempotencyKey, String cardId, long limitMinor) {
        client.post("/processor/cards/" + cardId + "/limits", idempotencyKey, Map.of("limitMinor", limitMinor));
    }

    @Override
    public void resetLimits(String idempotencyKey, String cardId) {
        client.post("/processor/cards/" + cardId + "/limits/reset", idempotencyKey, Map.of());
    }

    @Override
    public void activate(String idempotencyKey, String cardId) {
        client.post("/processor/cards/" + cardId + "/activate", idempotencyKey, Map.of());
    }

    @Override
    public void deactivate(String idempotencyKey, String cardId) {
        client.post("/processor/cards/" + cardId + "/deactivate", idempotencyKey, Map.of());
    }
}
