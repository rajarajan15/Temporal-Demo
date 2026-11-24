package com.occlet.issuance.mock;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Mock processor records. The fake PAN stays inside this class and is never returned. */
@Component
public class ProcessorStore {
    private static final String LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ";

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Card> cards = new HashMap<>();
    private final Map<String, String> cardIdByCreationKey = new HashMap<>();
    private final Set<String> cancelledCreationKeys = new HashSet<>();

    public record Created(String cardId, String token) {}

    public record CardView(String cardId, String status, long limitMinor, String productCode) {}

    private static final class Card {
        final String cardId;
        final String tenant;
        final String productCode;
        @SuppressWarnings("unused")
        final String fakePan;
        String status = "INACTIVE";
        long limitMinor;

        Card(String cardId, String tenant, String productCode, String fakePan) {
            this.cardId = cardId;
            this.tenant = tenant;
            this.productCode = productCode;
            this.fakePan = fakePan;
        }
    }

    public synchronized Created create(String creationKey, String productCode) {
        if (cancelledCreationKeys.contains(creationKey)) {
            throw new MockException(HttpStatus.CONFLICT, "CARD_CREATION_CANCELLED");
        }
        String cardId = "card_" + letters(20);
        String token = "tok_" + letters(24);
        cards.put(cardId, new Card(cardId, MockGate.tenantOf(creationKey), productCode, fakePan()));
        cardIdByCreationKey.put(creationKey, cardId);
        return new Created(cardId, token);
    }

    /** Tolerates a creation that never happened, and blocks one that arrives late. */
    public synchronized void cancelByCreationKey(String tenant, String creationKey) {
        if (!tenant.equals(MockGate.tenantOf(creationKey))) {
            throw new MockException(HttpStatus.NOT_FOUND, "CARD_NOT_FOUND");
        }
        cancelledCreationKeys.add(creationKey);
        String cardId = cardIdByCreationKey.get(creationKey);
        if (cardId != null) {
            cards.get(cardId).status = "CANCELLED";
        }
    }

    public synchronized void setLimit(String tenant, String cardId, long limitMinor) {
        card(tenant, cardId).limitMinor = limitMinor;
    }

    public synchronized void setStatus(String tenant, String cardId, String status) {
        card(tenant, cardId).status = status;
    }

    public synchronized List<CardView> list(String tenant) {
        List<CardView> views = new ArrayList<>();
        cards.values().stream()
                .filter(card -> card.tenant.equals(tenant))
                .forEach(card -> views.add(new CardView(card.cardId, card.status, card.limitMinor, card.productCode)));
        return views;
    }

    public synchronized void reset() {
        cards.clear();
        cardIdByCreationKey.clear();
        cancelledCreationKeys.clear();
    }

    private Card card(String tenant, String cardId) {
        Card card = cards.get(cardId);
        if (card == null || !card.tenant.equals(tenant)) {
            throw new MockException(HttpStatus.NOT_FOUND, "CARD_NOT_FOUND");
        }
        return card;
    }

    private String letters(int length) {
        StringBuilder value = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            value.append(LETTERS.charAt(random.nextInt(LETTERS.length())));
        }
        return value.toString();
    }

    private String fakePan() {
        StringBuilder pan = new StringBuilder("999999");
        for (int i = 0; i < 9; i++) {
            pan.append(random.nextInt(10));
        }
        int sum = 0;
        for (int i = 0; i < pan.length(); i++) {
            int digit = pan.charAt(pan.length() - 1 - i) - '0';
            sum += i % 2 == 0 ? (digit * 2 > 9 ? digit * 2 - 9 : digit * 2) : digit;
        }
        return pan.append((10 - sum % 10) % 10).toString();
    }
}
