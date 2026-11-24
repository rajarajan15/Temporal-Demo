package com.occlet.issuance.mock;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Mock processor records. The fake PAN stays inside this class and is never returned. */
@Component
public class ProcessorStore {
    private static final String LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ";

    private final SecureRandom random = new SecureRandom();
    // Sorted by card ID, so reconciliation can page through in a stable order.
    private final Map<String, Card> cards = new TreeMap<>();
    private final Map<String, String> cardIdByCreationKey = new HashMap<>();
    private final Set<String> cancelledCreationKeys = new HashSet<>();

    public record Created(String cardId, String token) {}

    public record CardView(String cardId, String status, long limitMinor, String productCode, String replacedBy) {}

    public record CardSummary(String cardId, String status) {}

    public record Page(List<CardSummary> cards, String nextCursor) {}

    private static final class Card {
        final String cardId;
        final String tenant;
        final String productCode;
        @SuppressWarnings("unused")
        final String fakePan;
        String status = "INACTIVE";
        long limitMinor;
        String replacedBy;

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
        Created created = newCard(MockGate.tenantOf(creationKey), productCode);
        cardIdByCreationKey.put(creationKey, created.cardId());
        return created;
    }

    /** A card the platform never issued, for demonstrating reconciliation. */
    public synchronized Created createOrphan(String tenant) {
        Created created = newCard(tenant, "VIRTUAL_STANDARD");
        cards.get(created.cardId()).status = "ACTIVE";
        return created;
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

    public synchronized void linkReplacement(String tenant, String oldCardId, String newCardId) {
        card(tenant, newCardId);
        card(tenant, oldCardId).replacedBy = newCardId;
    }

    public synchronized Page page(String tenant, String after, int pageSize) {
        List<CardSummary> page = new ArrayList<>();
        String nextCursor = null;
        for (Card card : cards.values()) {
            if (!card.tenant.equals(tenant) || (after != null && card.cardId.compareTo(after) <= 0)) {
                continue;
            }
            if (page.size() == pageSize) {
                nextCursor = page.get(page.size() - 1).cardId();
                break;
            }
            page.add(new CardSummary(card.cardId, card.status));
        }
        return new Page(page, nextCursor);
    }

    public synchronized List<CardView> list(String tenant) {
        List<CardView> views = new ArrayList<>();
        cards.values().stream()
                .filter(card -> card.tenant.equals(tenant))
                .forEach(card -> views.add(
                        new CardView(card.cardId, card.status, card.limitMinor, card.productCode, card.replacedBy)));
        return views;
    }

    public synchronized void reset() {
        cards.clear();
        cardIdByCreationKey.clear();
        cancelledCreationKeys.clear();
    }

    private Created newCard(String tenant, String productCode) {
        String cardId = "card_" + letters(20);
        String token = "tok_" + letters(24);
        cards.put(cardId, new Card(cardId, tenant, productCode, fakePan()));
        return new Created(cardId, token);
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
