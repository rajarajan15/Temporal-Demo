package com.occlet.issuance.activities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.occlet.issuance.activities.api.ReconcileCommand;
import com.occlet.issuance.activities.api.ReconciliationActivities;
import com.occlet.issuance.contracts.CardRef;
import com.occlet.issuance.contracts.Discrepancy;
import com.occlet.issuance.contracts.ReconciliationReport;
import com.occlet.issuance.ports.CardPage;
import com.occlet.issuance.ports.CardProcessorPort;
import io.temporal.activity.ActivityOptions;
import io.temporal.testing.TestActivityEnvironment;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReconciliationActivitiesImplTest {
    private static final ReconcileCommand COMMAND = new ReconcileCommand("bank-a", "2026-11-23", 2);

    private TestActivityEnvironment env;
    private ReconciliationActivities activity;
    private final List<ReconciliationActivitiesImpl.Progress> heartbeats = new ArrayList<>();
    private volatile boolean heartbeatBeforePlatformLoad;

    // Processor: card_A active, card_B active (platform says cancelled), card_D active (unknown to platform),
    // card_E inactive. Platform also has card_C and card_F, which the processor does not.
    private final FakeProcessor processor = new FakeProcessor(Map.of(
            "card_A", "ACTIVE", "card_B", "ACTIVE", "card_D", "ACTIVE", "card_E", "INACTIVE"));
    private final Map<String, String> platform = Map.of(
            "card_A", "ACTIVE", "card_B", "CANCELLED", "card_C", "ACTIVE", "card_E", "CANCELLED", "card_F", "ACTIVE");

    @BeforeEach
    void start() {
        env = TestActivityEnvironment.newInstance();
        env.registerActivitiesImplementations(new ReconciliationActivitiesImpl(processor, tenant -> {
            heartbeatBeforePlatformLoad = !heartbeats.isEmpty();
            return platform;
        }));
        env.setActivityHeartbeatListener(ReconciliationActivitiesImpl.Progress.class, heartbeats::add);
        activity = env.newActivityStub(ReconciliationActivities.class, ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofMinutes(1))
                .setHeartbeatTimeout(Duration.ofSeconds(5))
                .build());
    }

    @AfterEach
    void stop() {
        env.close();
    }

    @Test
    void fullScanFindsEveryKindOfDiscrepancy() {
        ReconciliationReport report = activity.reconcile(COMMAND);

        assertEquals(4, report.processorCards());
        assertEquals(5, report.platformCards());
        assertEquals(2, report.pagesRead());
        Map<String, String> found = report.discrepancies().stream()
                .collect(Collectors.toMap(Discrepancy::cardId, Discrepancy::type));
        assertEquals(Map.of(
                "card_B", Discrepancy.STATUS_MISMATCH,
                "card_C", Discrepancy.MISSING_AT_PROCESSOR,
                "card_D", Discrepancy.UNKNOWN_TO_PLATFORM,
                "card_F", Discrepancy.MISSING_AT_PROCESSOR), found);
        assertEquals(4, report.discrepancyCount());
    }

    /**
     * A slow platform load must not look like a dead worker, so the activity heartbeats before it.
     * (The SDK then sends at most one heartbeat per 80% of the heartbeat timeout, so later page
     * heartbeats in this fast test are throttled and not all reach the listener.)
     */
    @Test
    void heartbeatsBeforeLoadingPlatformRecords() {
        activity.reconcile(COMMAND);

        assertTrue(heartbeatBeforePlatformLoad);
        assertTrue(heartbeats.size() >= 1);
    }

    @Test
    void blockedCardIsNotAMismatchAndCardWithoutStatusIsStillKnown() {
        Map<String, String> platformWithNullStatus = new java.util.HashMap<>();
        platformWithNullStatus.put("card_A", "ACTIVE");
        platformWithNullStatus.put("card_B", null);

        ReconciliationReport report = run(Map.of("card_A", "BLOCKED", "card_B", "INACTIVE"), platformWithNullStatus);

        assertEquals(0, report.discrepancyCount(), () -> report.discrepancies().toString());
    }

    @Test
    void emptyProcessorReportsEveryPlatformCardMissingAndReadsNoPages() {
        ReconciliationReport report = run(Map.of(), Map.of("card_A", "ACTIVE", "card_B", "ACTIVE"));

        assertEquals(0, report.pagesRead());
        assertEquals(0, report.processorCards());
        assertEquals(2, report.discrepancyCount());
        assertTrue(report.discrepancies().stream().allMatch(d -> d.type().equals(Discrepancy.MISSING_AT_PROCESSOR)));
    }

    @Test
    void resumeAfterTheLastPageDoesNotCountAnExtraPage() {
        env.setHeartbeatDetails(new ReconciliationActivitiesImpl.Progress("card_E", 4, 2, 2, List.of(
                new Discrepancy("card_B", Discrepancy.STATUS_MISMATCH, "ACTIVE", "CANCELLED"),
                new Discrepancy("card_D", Discrepancy.UNKNOWN_TO_PLATFORM, "ACTIVE", null))));

        ReconciliationReport report = activity.reconcile(COMMAND);

        assertEquals(2, report.pagesRead());
        assertEquals(4, report.processorCards());
        // card_C was found on page 2 before the restart in real life; here only the tail (card_F) is new.
        assertEquals(3, report.discrepancyCount());
    }

    private static ReconciliationReport run(Map<String, String> processorCards, Map<String, String> platformCards) {
        TestActivityEnvironment local = TestActivityEnvironment.newInstance();
        try {
            local.registerActivitiesImplementations(
                    new ReconciliationActivitiesImpl(new FakeProcessor(processorCards), tenant -> platformCards));
            return local.newActivityStub(ReconciliationActivities.class, ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(1)).build()).reconcile(COMMAND);
        } finally {
            local.close();
        }
    }

    @Test
    void retryResumesFromLastHeartbeatInsteadOfStartingOver() {
        // As if a previous attempt read the first page and then its worker died.
        env.setHeartbeatDetails(new ReconciliationActivitiesImpl.Progress("card_B", 2, 1, 1,
                List.of(new Discrepancy("card_B", Discrepancy.STATUS_MISMATCH, "ACTIVE", "CANCELLED"))));

        ReconciliationReport report = activity.reconcile(COMMAND);

        assertEquals(List.of("card_B"), processor.afterCursors);
        assertEquals(4, report.processorCards());
        assertEquals(2, report.pagesRead());
        assertEquals(4, report.discrepancyCount());
        assertTrue(report.discrepancies().stream().anyMatch(d -> d.cardId().equals("card_B")));
    }

    private static final class FakeProcessor implements CardProcessorPort {
        private final TreeMap<String, String> cards;
        final List<String> afterCursors = new ArrayList<>();

        FakeProcessor(Map<String, String> cards) {
            this.cards = new TreeMap<>(cards);
        }

        @Override
        public CardPage listCards(String tenantId, String afterCardId, int pageSize) {
            afterCursors.add(afterCardId);
            Map<String, String> rest = afterCardId == null ? cards : cards.tailMap(afterCardId, false);
            List<CardPage.ProcessorCard> page = rest.entrySet().stream().limit(pageSize)
                    .map(e -> new CardPage.ProcessorCard(e.getKey(), e.getValue())).toList();
            boolean more = rest.size() > pageSize;
            return new CardPage(page, more ? page.get(page.size() - 1).cardId() : null);
        }

        @Override public CardRef createCard(String key, String productCode, String accountRef) { throw new UnsupportedOperationException(); }
        @Override public void cancelByCreationKey(String key, String creationKey) { }
        @Override public void setLimits(String key, String cardId, long limitMinor) { }
        @Override public void resetLimits(String key, String cardId) { }
        @Override public void activate(String key, String cardId) { }
        @Override public void deactivate(String key, String cardId) { }
        @Override public void block(String key, String cardId) { }
        @Override public void linkReplacement(String key, String oldCardId, String newCardId) { }
    }
}
