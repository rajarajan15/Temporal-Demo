package com.occlet.issuance.engine;

import com.occlet.issuance.contracts.CancelOutcome;
import com.occlet.issuance.contracts.CancelRequest;
import com.occlet.issuance.contracts.CardProgress;
import com.occlet.issuance.contracts.CardRef;
import com.occlet.issuance.contracts.CardStatus;
import com.occlet.issuance.contracts.ErrorTypes;
import com.occlet.issuance.contracts.ReviewAction;
import com.occlet.issuance.contracts.ReviewDecision;
import com.occlet.issuance.contracts.ReviewItem;
import com.occlet.issuance.contracts.ReviewKind;
import com.occlet.issuance.contracts.ReviewOutcome;
import com.occlet.issuance.contracts.StepRecord;
import com.occlet.issuance.contracts.StepState;
import com.occlet.issuance.contracts.WorkflowOutcome;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.TemporalFailure;
import io.temporal.failure.TimeoutFailure;
import io.temporal.workflow.Functions;
import io.temporal.workflow.Saga;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Generic step model used from inside workflow code. The workflow still spells out its sequence.
 * This class handles progress records, status moves, search attributes, the saga, parking for
 * operator review, and cancellation.
 */
public final class StepRunner {
    private final List<StepRecord> steps = new ArrayList<>();
    private final Saga saga = new Saga(new Saga.Options.Builder().setContinueWithError(true).build());
    private CardStatus status = CardStatus.REQUESTED;
    private String reasonCode;
    private CardRef card;
    private boolean compensationFailed;
    private boolean finished;
    private boolean pointOfNoReturn;
    private boolean cancelRequested;
    private ReviewItem pendingReview;
    private ReviewDecision decision;

    /** Ends the normal sequence early: compensate, then finish with this status and reason. */
    public static final class Stop extends RuntimeException {
        private final CardStatus status;
        private final String reason;

        public Stop(CardStatus status, String reason) {
            super(reason, null, false, false);
            this.status = status;
            this.reason = reason;
        }
    }

    public StepRunner(List<String> plannedSteps) {
        plannedSteps.forEach(name -> steps.add(new StepRecord(name, StepState.PENDING, null, null, null)));
    }

    // ---------- running steps ----------

    public void run(String step, CardStatus statusAfter, Functions.Proc action) {
        call(step, statusAfter, () -> {
            action.apply();
            return null;
        });
    }

    public <T> T call(String step, CardStatus statusAfter, Functions.Func<T> action) {
        long startedAt = Workflow.currentTimeMillis();
        put(new StepRecord(step, StepState.RUNNING, null, startedAt, null));
        try {
            T result = action.apply();
            put(new StepRecord(step, StepState.DONE, null, startedAt, Workflow.currentTimeMillis()));
            if (statusAfter != null) {
                moveTo(statusAfter);
            }
            return result;
        } catch (TemporalFailure failure) {
            put(new StepRecord(step, StepState.FAILED, errorCode(failure), startedAt, Workflow.currentTimeMillis()));
            throw failure;
        }
    }

    /**
     * Like {@link #call}, but a technical failure that outlasts its retries parks the request for an
     * operator instead of failing it. RETRY runs the step again with the same idempotency key.
     * CANCEL compensates and ends the request. Business failures are still thrown.
     */
    public <T> T callOrPark(String step, CardStatus statusAfter, Functions.Func<T> action) {
        while (true) {
            try {
                return call(step, statusAfter, action);
            } catch (ActivityFailure failure) {
                if (isBusiness(failure)) {
                    throw failure;
                }
                ReviewAction action2 = park(ReviewKind.RETRIES_EXHAUSTED, step, ErrorTypes.RETRIES_EXHAUSTED, () -> false);
                if (action2 == ReviewAction.CANCEL) {
                    throw new Stop(CardStatus.CANCELLED, ErrorTypes.CANCELLED_BY_OPERATOR);
                }
            }
        }
    }

    public void runOrPark(String step, CardStatus statusAfter, Functions.Proc action) {
        callOrPark(step, statusAfter, () -> {
            action.apply();
            return null;
        });
    }

    /** A failed best-effort step is recorded but never fails the workflow or triggers compensation. */
    public void runBestEffort(String step, Functions.Proc action) {
        try {
            run(step, null, action);
        } catch (ActivityFailure ignored) {
            Workflow.getLogger(StepRunner.class).warn("Best-effort step failed: {}", step);
        }
    }

    /**
     * Waits for a condition, such as a callback signal, up to a timeout. Returns false if the timer
     * won, so the caller can escalate. A cancel request ends the wait.
     */
    public boolean waitFor(String step, Duration timeout, Supplier<Boolean> condition) {
        long startedAt = Workflow.currentTimeMillis();
        put(new StepRecord(step, StepState.WAITING, null, startedAt, null));
        boolean met = Workflow.await(timeout, () -> condition.get() || cancelRequested);
        throwIfCancelRequested();
        if (met) {
            put(new StepRecord(step, StepState.DONE, null, startedAt, Workflow.currentTimeMillis()));
        }
        return met;
    }

    public void markDone(String step) {
        StepRecord current = find(step);
        Long startedAt = current == null ? null : current.startedAtMillis();
        put(new StepRecord(step, StepState.DONE, null, startedAt, Workflow.currentTimeMillis()));
    }

    // ---------- operator review ----------

    /**
     * Parks the request until an operator decides, a cancel request arrives, or the given condition
     * becomes true on its own (for example a late callback). Card status shows UNDER_REVIEW meanwhile.
     *
     * @return the operator's action, or APPROVE when the condition resolved it
     */
    public ReviewAction park(ReviewKind kind, String step, String reason, Supplier<Boolean> resolvedWithoutOperator) {
        CardStatus before = status;
        StepRecord current = find(step);
        Long startedAt = current == null ? null : current.startedAtMillis();
        put(new StepRecord(step, StepState.PARKED, reason, startedAt, null));
        pendingReview = new ReviewItem(kind, step, reason, Workflow.currentTimeMillis());
        decision = null;
        moveTo(CardStatus.UNDER_REVIEW);

        Workflow.await(() -> decision != null || cancelRequested || resolvedWithoutOperator.get());

        ReviewDecision made = decision;
        pendingReview = null;
        decision = null;
        if (made == null && cancelRequested) {
            throw new Stop(CardStatus.CANCELLED, ErrorTypes.CANCELLED_BY_REQUEST);
        }
        moveTo(before);
        return made == null ? ReviewAction.APPROVE : made.action();
    }

    /** Update validator: rejects a decision before it enters history. Must not change state. */
    public void validateDecision(ReviewDecision request) {
        if (finished) {
            throw reject(ErrorTypes.ALREADY_FINISHED);
        }
        if (pendingReview == null || decision != null) {
            throw reject(ErrorTypes.NO_REVIEW_PENDING);
        }
        if (request == null || request.action() == null || request.reviewer() == null || request.reviewer().isBlank()) {
            throw reject(ErrorTypes.INVALID_REQUEST);
        }
        if (!pendingReview.kind().allows(request.action())) {
            throw reject(ErrorTypes.ACTION_NOT_ALLOWED);
        }
    }

    public ReviewOutcome decide(ReviewDecision request) {
        ReviewKind kind = pendingReview.kind();
        decision = request;
        return new ReviewOutcome(kind, request.action());
    }

    // ---------- cancellation ----------

    /** Update validator: a request can be cancelled only before its point of no return. */
    public void validateCancel(CancelRequest request) {
        if (finished) {
            throw reject(ErrorTypes.ALREADY_FINISHED);
        }
        if (pointOfNoReturn) {
            throw reject(ErrorTypes.CANNOT_CANCEL);
        }
    }

    public CancelOutcome requestCancel(CancelRequest request) {
        cancelRequested = true;
        return new CancelOutcome(true);
    }

    /** Called between steps. An accepted cancel always takes effect, even if it raced a step. */
    public void throwIfCancelRequested() {
        if (cancelRequested) {
            throw new Stop(CardStatus.CANCELLED, ErrorTypes.CANCELLED_BY_REQUEST);
        }
    }

    public void markPointOfNoReturn() {
        pointOfNoReturn = true;
    }

    // ---------- saga ----------

    /**
     * Register before calling the step (amendment A3). A call that timed out may still have taken
     * effect, so compensations must tolerate a step that never completed.
     */
    public void compensateWith(String compensationStep, Functions.Proc compensation) {
        saga.addCompensation(() -> {
            long startedAt = Workflow.currentTimeMillis();
            steps.add(new StepRecord(compensationStep, StepState.COMPENSATING, null, startedAt, null));
            int index = steps.size() - 1;
            try {
                compensation.apply();
                steps.set(index, new StepRecord(
                        compensationStep, StepState.COMPENSATED, null, startedAt, Workflow.currentTimeMillis()));
            } catch (ActivityFailure failure) {
                steps.set(index, new StepRecord(
                        compensationStep, StepState.FAILED, errorCode(failure), startedAt, Workflow.currentTimeMillis()));
                // With continueWithError, Saga.compensate() does not report this failure itself.
                compensationFailed = true;
                throw failure;
            }
        });
    }

    /** Runs compensations in reverse order, then ends according to the failure class. */
    public WorkflowOutcome compensateAndEnd(ActivityFailure failure) {
        return isBusiness(failure)
                ? compensateAndEnd(new Stop(CardStatus.REJECTED, errorCode(failure)))
                : compensateAndEnd(new Stop(CardStatus.CANCELLED, ErrorTypes.RETRIES_EXHAUSTED));
    }

    public WorkflowOutcome compensateAndEnd(Stop stop) {
        try {
            saga.compensate();
        } catch (Saga.CompensationException e) {
            compensationFailed = true;
        }
        if (compensationFailed) {
            return end(CardStatus.UNDER_REVIEW, ErrorTypes.COMPENSATION_FAILED);
        }
        return end(stop.status, stop.reason);
    }

    // ---------- state ----------

    public void recordCard(CardRef created) {
        card = created;
        Workflow.upsertTypedSearchAttributes(CardSearchAttributes.CARD_ID.valueSet(created.cardId()));
    }

    public void moveTo(CardStatus next) {
        if (next != status) {
            status = next;
            Workflow.upsertTypedSearchAttributes(CardSearchAttributes.CARD_STATUS.valueSet(next.name()));
        }
    }

    public WorkflowOutcome end(CardStatus finalStatus, String reason) {
        moveTo(finalStatus);
        reasonCode = reason;
        finished = true;
        return new WorkflowOutcome(status, reasonCode, card);
    }

    public CardProgress progress() {
        return new CardProgress(status, reasonCode, card, List.copyOf(steps), pendingReview, finished);
    }

    private StepRecord find(String name) {
        for (StepRecord record : steps) {
            if (record.name().equals(name)) {
                return record;
            }
        }
        return null;
    }

    private void put(StepRecord record) {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).name().equals(record.name())) {
                steps.set(i, record);
                return;
            }
        }
        steps.add(record);
    }

    private static boolean isBusiness(ActivityFailure failure) {
        return failure.getCause() instanceof ApplicationFailure application && application.isNonRetryable();
    }

    private static ApplicationFailure reject(String code) {
        return ApplicationFailure.newNonRetryableFailure(code, code);
    }

    private static String errorCode(TemporalFailure failure) {
        Throwable cause = failure.getCause();
        if (cause instanceof ApplicationFailure application) {
            return application.getType();
        }
        return cause instanceof TimeoutFailure ? ErrorTypes.TIMEOUT : ErrorTypes.TECHNICAL;
    }
}
