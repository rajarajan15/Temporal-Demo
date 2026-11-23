package com.occlet.issuance.mock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class MockGateTest {
    private static final String KEY = "bank-a:req:createCard";

    private final MockGate gate = new MockGate(1);
    private final ProcessorStore store = new ProcessorStore();
    private final AtomicInteger work = new AtomicInteger();

    @Test
    void sameKeyDoesTheWorkOnceAndReturnsTheSameResult() {
        int first = gate.execute("processor", "createCard", KEY, work::incrementAndGet);
        int second = gate.execute("processor", "createCard", KEY, work::incrementAndGet);

        assertEquals(first, second);
        assertEquals(1, work.get());
    }

    @Test
    void failFirstNThenSucceed() {
        gate.setRule("bank-a", "processor", "createCard", new FailureRule(FailureRule.Mode.SERVER_ERROR, 2, null, null));

        assertThrows(MockException.class, () -> gate.execute("processor", "createCard", KEY, work::incrementAndGet));
        assertThrows(MockException.class, () -> gate.execute("processor", "createCard", KEY, work::incrementAndGet));
        assertEquals(1, gate.execute("processor", "createCard", KEY, work::incrementAndGet));
    }

    @Test
    void droppedResponseStillDidTheWork() {
        gate.setRule("bank-a", "processor", "createCard", new FailureRule(FailureRule.Mode.DROP_RESPONSE, 1, null, null));

        gate.execute("processor", "createCard", KEY, work::incrementAndGet);
        gate.execute("processor", "createCard", KEY, work::incrementAndGet);

        assertEquals(1, work.get());
    }

    @Test
    void failureRulesAreIsolatedByTenant() {
        gate.setRule("bank-a", "processor", "createCard", new FailureRule(FailureRule.Mode.OFFLINE, -1, null, null));

        assertThrows(MockException.class, () -> gate.execute("processor", "createCard", KEY, work::incrementAndGet));
        assertEquals(1, gate.execute("processor", "createCard", "bank-b:req:createCard", work::incrementAndGet));
    }

    @Test
    void cancelBeforeCreateBlocksLateCreation() {
        store.cancelByCreationKey("bank-a", KEY);

        MockException failure = assertThrows(MockException.class, () -> store.create(KEY, "VIRTUAL_STANDARD"));
        assertEquals(HttpStatus.CONFLICT, failure.status());
    }

    @Test
    void cancelFindsCardByCreationKey() {
        store.create(KEY, "VIRTUAL_STANDARD");

        store.cancelByCreationKey("bank-a", KEY);

        assertEquals("CANCELLED", store.list("bank-a").get(0).status());
    }

    @Test
    void anotherTenantCannotTouchACard() {
        String cardId = store.create(KEY, "VIRTUAL_STANDARD").cardId();

        assertThrows(MockException.class, () -> store.setStatus("bank-b", cardId, "ACTIVE"));
        assertThrows(MockException.class, () -> store.cancelByCreationKey("bank-b", KEY));
        assertEquals(List.of(), store.list("bank-b"));
    }

    @Test
    void countersReportCallsAndSideEffects() {
        gate.execute("processor", "createCard", KEY, work::incrementAndGet);
        gate.execute("processor", "createCard", KEY, work::incrementAndGet);

        Map<String, Object> counters = gate.counters("bank-a");
        assertEquals(Map.of("processor/createCard", 2), counters.get("calls"));
        assertEquals(Map.of("processor/createCard", 1), counters.get("sideEffects"));
    }
}
