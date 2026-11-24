package com.occlet.issuance.adapters.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.occlet.issuance.contracts.CardRef;
import com.occlet.issuance.ports.CardPage;
import com.occlet.issuance.ports.CardProcessorPort;
import com.occlet.issuance.ports.PortException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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
        client.post("/processor/cards/" + segment(cardId) + "/limits", idempotencyKey, Map.of("limitMinor", limitMinor));
    }

    @Override
    public void resetLimits(String idempotencyKey, String cardId) {
        client.post("/processor/cards/" + segment(cardId) + "/limits/reset", idempotencyKey, Map.of());
    }

    @Override
    public void activate(String idempotencyKey, String cardId) {
        client.post("/processor/cards/" + segment(cardId) + "/activate", idempotencyKey, Map.of());
    }

    @Override
    public void deactivate(String idempotencyKey, String cardId) {
        client.post("/processor/cards/" + segment(cardId) + "/deactivate", idempotencyKey, Map.of());
    }

    @Override
    public void block(String idempotencyKey, String cardId) {
        client.post("/processor/cards/" + segment(cardId) + "/block", idempotencyKey, Map.of());
    }

    @Override
    public void linkReplacement(String idempotencyKey, String oldCardId, String newCardId) {
        client.post("/processor/cards/" + segment(oldCardId) + "/replacement", idempotencyKey, Map.of("newCardId", newCardId));
    }

    @Override
    public CardPage listCards(String tenantId, String afterCardId, int pageSize) {
        String query = "?pageSize=" + pageSize
                + (afterCardId == null ? "" : "&after=" + URLEncoder.encode(afterCardId, StandardCharsets.UTF_8));
        JsonNode response = client.get("/processor/cards" + query, tenantId);
        List<CardPage.ProcessorCard> cards = new ArrayList<>();
        for (JsonNode card : response.path("cards")) {
            cards.add(new CardPage.ProcessorCard(card.path("cardId").asText(), card.path("status").asText()));
        }
        JsonNode next = response.get("nextCursor");
        return new CardPage(cards, next == null || next.isNull() ? null : next.asText());
    }

    /** Card IDs come from an external system, so they are encoded before going into a URL path. */
    private static String segment(String id) {
        return URLEncoder.encode(id, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
