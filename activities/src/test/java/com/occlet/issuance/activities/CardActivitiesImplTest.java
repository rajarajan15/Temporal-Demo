package com.occlet.issuance.activities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.occlet.issuance.activities.api.RequestScope;
import com.occlet.issuance.contracts.CardRef;
import com.occlet.issuance.contracts.CardRequest;
import com.occlet.issuance.contracts.CardType;
import com.occlet.issuance.contracts.ErrorTypes;
import com.occlet.issuance.contracts.KycDecision;
import com.occlet.issuance.ports.CardProcessorPort;
import com.occlet.issuance.ports.PortException;
import io.temporal.failure.ApplicationFailure;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CardActivitiesImplTest {
    private static final CardRequest REQUEST = new CardRequest(
            "req-1", "bank-a", CardType.VIRTUAL, "VIRTUAL_STANDARD", "customer", "account", 50_000);

    private final FakeProcessor processor = new FakeProcessor();
    private final CardActivitiesImpl activities = new CardActivitiesImpl(
            (key, customer) -> KycDecision.APPROVED, processor, (key, template, customer) -> { });

    @Test
    void retriesSendTheSameIdempotencyKey() {
        activities.createCard(REQUEST);
        activities.createCard(REQUEST);

        assertEquals(List.of("bank-a:req-1:createCard", "bank-a:req-1:createCard"), processor.keys);
    }

    @Test
    void cancelTargetsTheCreationKey() {
        activities.cancelCard(new RequestScope("bank-a", "req-1"));

        assertEquals("bank-a:req-1:createCard", processor.cancelledCreationKey);
        assertEquals(List.of("bank-a:req-1:cancelCard"), processor.keys);
    }

    @Test
    void businessFailureIsNonRetryable() {
        processor.failure = new PortException.Business("PRODUCT_CLOSED");

        ApplicationFailure failure = assertThrows(ApplicationFailure.class, () -> activities.createCard(REQUEST));

        assertTrue(failure.isNonRetryable());
        assertEquals("PRODUCT_CLOSED", failure.getType());
    }

    @Test
    void technicalAndAmbiguousFailuresAreRetryable() {
        processor.failure = new PortException.Technical("HTTP_503");
        ApplicationFailure technical = assertThrows(ApplicationFailure.class, () -> activities.createCard(REQUEST));
        assertFalse(technical.isNonRetryable());
        assertEquals(ErrorTypes.TECHNICAL, technical.getType());

        processor.failure = new PortException.Ambiguous("REQUEST_TIMED_OUT");
        ApplicationFailure ambiguous = assertThrows(ApplicationFailure.class, () -> activities.createCard(REQUEST));
        assertFalse(ambiguous.isNonRetryable());
        assertEquals(ErrorTypes.AMBIGUOUS, ambiguous.getType());
    }

    @Test
    void untrustedBusinessCodeCannotReachHistory() {
        processor.failure = new PortException.Business("bad pan 4111111111111111");

        ApplicationFailure failure = assertThrows(ApplicationFailure.class, () -> activities.createCard(REQUEST));

        assertEquals(ErrorTypes.UNKNOWN_BUSINESS_ERROR, failure.getType());
        assertFalse(failure.getMessage().contains("4111"));
    }

    @Test
    void validationRejectsBadProductAndLimit() {
        CardRequest badProduct = new CardRequest("r", "bank-a", CardType.VIRTUAL, "NOPE", "c", "a", 1);
        CardRequest badLimit = new CardRequest("r", "bank-a", CardType.VIRTUAL, "VIRTUAL_STANDARD", "c", "a", 1_000_001);

        assertEquals(ErrorTypes.INVALID_PRODUCT,
                assertThrows(ApplicationFailure.class, () -> activities.validateRequest(badProduct)).getType());
        assertEquals(ErrorTypes.LIMIT_EXCEEDS_POLICY,
                assertThrows(ApplicationFailure.class, () -> activities.validateRequest(badLimit)).getType());
    }

    private static final class FakeProcessor implements CardProcessorPort {
        final List<String> keys = new ArrayList<>();
        PortException failure;
        String cancelledCreationKey;

        @Override
        public CardRef createCard(String key, String productCode, String accountRef) {
            keys.add(key);
            if (failure != null) {
                throw failure;
            }
            return new CardRef("card", "token");
        }

        @Override
        public void cancelByCreationKey(String key, String creationKey) {
            keys.add(key);
            cancelledCreationKey = creationKey;
        }

        @Override public void setLimits(String key, String cardId, long limitMinor) { }
        @Override public void resetLimits(String key, String cardId) { }
        @Override public void activate(String key, String cardId) { }
        @Override public void deactivate(String key, String cardId) { }
    }
}
