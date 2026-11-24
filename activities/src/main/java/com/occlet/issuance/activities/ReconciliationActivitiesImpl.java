package com.occlet.issuance.activities;

import com.occlet.issuance.activities.api.ReconcileCommand;
import com.occlet.issuance.activities.api.ReconciliationActivities;
import com.occlet.issuance.contracts.Discrepancy;
import com.occlet.issuance.contracts.ReconciliationReport;
import com.occlet.issuance.ports.CardPage;
import com.occlet.issuance.ports.CardProcessorPort;
import com.occlet.issuance.ports.PlatformRecordsPort;
import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Compares the processor's records with the platform's, one page at a time. After each page it
 * heartbeats its cursor and running totals. If the worker dies, Temporal notices the missing
 * heartbeats and retries, and the new attempt resumes from the last recorded page.
 *
 * <p>Both sides are walked in card ID order, so cards missing at the processor are found page by
 * page without holding every processor ID in memory.
 */
public class ReconciliationActivitiesImpl implements ReconciliationActivities {
    static final int MAX_LISTED_DISCREPANCIES = 50;
    private static final String PROCESSOR_ACTIVE = "ACTIVE";
    private static final String PROCESSOR_BLOCKED = "BLOCKED";
    private static final String PLATFORM_ACTIVE = "ACTIVE";

    private final CardProcessorPort processor;
    private final PlatformRecordsPort platformRecords;

    public ReconciliationActivitiesImpl(CardProcessorPort processor, PlatformRecordsPort platformRecords) {
        this.processor = processor;
        this.platformRecords = platformRecords;
    }

    /** Heartbeat details. Small and made only of IDs and counts. */
    public record Progress(String cursor, int processorCards, int pagesRead, int discrepancyCount, List<Discrepancy> discrepancies) {
        static Progress start() {
            return new Progress(null, 0, 0, 0, List.of());
        }
    }

    @Override
    public ReconciliationReport reconcile(ReconcileCommand command) {
        ActivityExecutionContext context = Activity.getExecutionContext();
        Progress progress = context.getHeartbeatDetails(Progress.class).orElseGet(Progress::start);
        // Heartbeat around the platform load too, so a slow load is not mistaken for a dead worker.
        context.heartbeat(progress);
        NavigableMap<String, String> platform = new TreeMap<>(
                FailureMapper.call(() -> platformRecords.cardStatuses(command.tenantId())));
        context.heartbeat(progress);

        while (true) {
            String after = progress.cursor();
            CardPage page = FailureMapper.call(() -> processor.listCards(command.tenantId(), after, command.pageSize()));
            List<Discrepancy> found = new ArrayList<>();
            String last = after;
            for (CardPage.ProcessorCard card : page.cards()) {
                compare(card, platform.containsKey(card.cardId()), platform.get(card.cardId()), found);
                last = card.cardId();
            }
            // Platform cards in the range this page covered, but absent from it, are missing at the processor.
            if (last != null) {
                Map<String, String> covered = after == null ? platform.headMap(last, true) : platform.subMap(after, false, last, true);
                covered.forEach((cardId, status) -> {
                    if (page.cards().stream().noneMatch(c -> c.cardId().equals(cardId))) {
                        found.add(new Discrepancy(cardId, Discrepancy.MISSING_AT_PROCESSOR, null, status));
                    }
                });
            }
            // An empty page (no cards, or a resume after the last page) is not counted as a page read.
            progress = advance(progress, page.nextCursor() == null ? last : page.nextCursor(), page.cards().size(), found,
                    page.cards().isEmpty() ? 0 : 1);
            context.heartbeat(progress);
            if (page.nextCursor() == null) {
                break;
            }
        }

        List<Discrepancy> tail = new ArrayList<>();
        Map<String, String> remaining = progress.cursor() == null ? platform : platform.tailMap(progress.cursor(), false);
        remaining.forEach((cardId, status) -> tail.add(new Discrepancy(cardId, Discrepancy.MISSING_AT_PROCESSOR, null, status)));
        progress = advance(progress, progress.cursor(), 0, tail, 0);

        return new ReconciliationReport(command.tenantId(), command.businessDate(), progress.processorCards(),
                platform.size(), progress.pagesRead(), progress.discrepancyCount(), progress.discrepancies());
    }

    /**
     * A mismatch is a card usable at the processor that the platform did not activate, or a card the
     * platform activated that the processor no longer has usable. BLOCKED is a lifecycle action after
     * issuance (lost, stolen, reissued), so it is not a mismatch with an ACTIVE issuance record.
     */
    private static void compare(CardPage.ProcessorCard card, boolean onPlatform, String platformStatus, List<Discrepancy> found) {
        if (!onPlatform) {
            found.add(new Discrepancy(card.cardId(), Discrepancy.UNKNOWN_TO_PLATFORM, card.status(), null));
            return;
        }
        boolean activeAtProcessor = PROCESSOR_ACTIVE.equals(card.status());
        boolean activeOnPlatform = PLATFORM_ACTIVE.equals(platformStatus);
        boolean blockedAfterIssuance = activeOnPlatform && PROCESSOR_BLOCKED.equals(card.status());
        if (activeAtProcessor != activeOnPlatform && !blockedAfterIssuance) {
            found.add(new Discrepancy(card.cardId(), Discrepancy.STATUS_MISMATCH, card.status(), platformStatus));
        }
    }

    private static Progress advance(Progress progress, String cursor, int cards, List<Discrepancy> found, int pages) {
        List<Discrepancy> listed = new ArrayList<>(progress.discrepancies());
        for (Discrepancy discrepancy : found) {
            if (listed.size() < MAX_LISTED_DISCREPANCIES) {
                listed.add(discrepancy);
            }
        }
        return new Progress(cursor, progress.processorCards() + cards, progress.pagesRead() + pages,
                progress.discrepancyCount() + found.size(), List.copyOf(listed));
    }
}
