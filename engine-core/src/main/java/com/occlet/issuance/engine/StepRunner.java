package com.occlet.issuance.engine;

import com.occlet.issuance.contracts.CardProgress;
import com.occlet.issuance.contracts.CardRef;
import com.occlet.issuance.contracts.CardStatus;
import com.occlet.issuance.contracts.ErrorTypes;
import com.occlet.issuance.contracts.StepRecord;
import com.occlet.issuance.contracts.StepState;
import com.occlet.issuance.contracts.WorkflowOutcome;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.TimeoutFailure;
import io.temporal.workflow.Functions;
import io.temporal.workflow.Saga;
import io.temporal.workflow.Workflow;
import java.util.ArrayList;
import java.util.List;

/**
 * Generic step model used from inside workflow code. The workflow still spells out its sequence.
 * This class handles progress records, status moves, search attributes and the saga.
 */
public final class StepRunner {
    private final List<StepRecord> steps = new ArrayList<>();
    private final Saga saga = new Saga(new Saga.Options.Builder().setContinueWithError(true).build());
    private CardStatus status = CardStatus.REQUESTED;
    private String reasonCode;
    private CardRef card;
    private boolean compensationFailed;

    public StepRunner(List<String> plannedSteps) {
        plannedSteps.forEach(name -> steps.add(new StepRecord(name, StepState.PENDING, null, null, null)));
    }

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
        } catch (ActivityFailure failure) {
            put(new StepRecord(step, StepState.FAILED, errorCode(failure), startedAt, Workflow.currentTimeMillis()));
            throw failure;
        }
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
        return new WorkflowOutcome(status, reasonCode, card);
    }

    /** Runs compensations in reverse order, then ends according to the failure class. */
    public WorkflowOutcome compensateAndEnd(ActivityFailure failure) {
        boolean business = failure.getCause() instanceof ApplicationFailure application && application.isNonRetryable();
        try {
            saga.compensate();
        } catch (Saga.CompensationException e) {
            compensationFailed = true;
        }
        if (compensationFailed) {
            return end(CardStatus.UNDER_REVIEW, ErrorTypes.COMPENSATION_FAILED);
        }
        return business
                ? end(CardStatus.REJECTED, errorCode(failure))
                : end(CardStatus.CANCELLED, ErrorTypes.RETRIES_EXHAUSTED);
    }

    public CardProgress progress() {
        return new CardProgress(status, reasonCode, card, List.copyOf(steps));
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

    private static String errorCode(ActivityFailure failure) {
        Throwable cause = failure.getCause();
        if (cause instanceof ApplicationFailure application) {
            return application.getType();
        }
        return cause instanceof TimeoutFailure ? ErrorTypes.TIMEOUT : ErrorTypes.TECHNICAL;
    }
}
