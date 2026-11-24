package com.occlet.issuance.workflows;

import static com.occlet.issuance.contracts.StepNames.ACTIVATE_CARD;
import static com.occlet.issuance.contracts.StepNames.AWAIT_CONFIRMATION;
import static com.occlet.issuance.contracts.StepNames.CANCEL_CARD;
import static com.occlet.issuance.contracts.StepNames.CREATE_CARD;
import static com.occlet.issuance.contracts.StepNames.DEACTIVATE_CARD;
import static com.occlet.issuance.contracts.StepNames.RESET_LIMITS;
import static com.occlet.issuance.contracts.StepNames.RUN_KYC;
import static com.occlet.issuance.contracts.StepNames.SEND_NOTIFICATION;
import static com.occlet.issuance.contracts.StepNames.SET_LIMITS;
import static com.occlet.issuance.contracts.StepNames.VALIDATE_REQUEST;

import com.occlet.issuance.activities.api.CardActivities;
import com.occlet.issuance.activities.api.CardCommand;
import com.occlet.issuance.activities.api.NotificationCommand;
import com.occlet.issuance.activities.api.RequestScope;
import com.occlet.issuance.activities.api.SetLimitsCommand;
import com.occlet.issuance.contracts.CancelOutcome;
import com.occlet.issuance.contracts.CancelRequest;
import com.occlet.issuance.contracts.CardProgress;
import com.occlet.issuance.contracts.CardRef;
import com.occlet.issuance.contracts.CardRequest;
import com.occlet.issuance.contracts.CardStatus;
import com.occlet.issuance.contracts.ErrorTypes;
import com.occlet.issuance.contracts.KycDecision;
import com.occlet.issuance.contracts.ProcessorEvent;
import com.occlet.issuance.contracts.ReviewAction;
import com.occlet.issuance.contracts.ReviewDecision;
import com.occlet.issuance.contracts.ReviewKind;
import com.occlet.issuance.contracts.ReviewOutcome;
import com.occlet.issuance.contracts.WorkflowOutcome;
import com.occlet.issuance.engine.RetryProfile;
import com.occlet.issuance.engine.StepRunner;
import io.temporal.failure.ActivityFailure;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;

public class VirtualCardWorkflowImpl implements VirtualCardWorkflow {
    static final Duration DEFAULT_CONFIRMATION_TIMEOUT = Duration.ofSeconds(30);
    private static final Logger LOG = Workflow.getLogger(VirtualCardWorkflowImpl.class);
    private static final List<String> PLAN = List.of(
            VALIDATE_REQUEST, RUN_KYC, CREATE_CARD, AWAIT_CONFIRMATION, SET_LIMITS, ACTIVATE_CARD, SEND_NOTIFICATION);

    private final CardActivities reads = stub(RetryProfile.FAST_READ);
    private final CardActivities writes = stub(RetryProfile.WRITE_IDEMPOTENT);
    private final CardActivities compensations = stub(RetryProfile.COMPENSATION);
    private final CardActivities bestEffort = stub(RetryProfile.BEST_EFFORT);
    private final StepRunner steps = new StepRunner(PLAN);
    // Insertion-ordered, so replay sees the same order every time.
    private final Set<String> seenEventIds = new LinkedHashSet<>();
    private final Set<String> confirmedCardIds = new LinkedHashSet<>();
    private CardRef card;

    @Override
    public WorkflowOutcome issue(CardRequest request) {
        RequestScope scope = new RequestScope(request.tenantId(), request.requestId());
        try {
            steps.run(VALIDATE_REQUEST, null, () -> reads.validateRequest(request));

            KycDecision kyc = steps.callOrPark(RUN_KYC, null, () -> writes.runKyc(request));
            if (kyc == KycDecision.REJECTED) {
                return steps.end(CardStatus.REJECTED, ErrorTypes.KYC_REJECTED);
            }
            if (kyc == KycDecision.NEEDS_REVIEW) {
                ReviewAction action = steps.park(ReviewKind.KYC_REVIEW, RUN_KYC, ErrorTypes.KYC_NEEDS_REVIEW, () -> false);
                if (action == ReviewAction.REJECT) {
                    throw new StepRunner.Stop(CardStatus.REJECTED, ErrorTypes.REJECTED_BY_REVIEWER);
                }
                steps.markDone(RUN_KYC);
            }
            steps.moveTo(CardStatus.KYC_PASSED);
            steps.throwIfCancelRequested();

            steps.compensateWith(CANCEL_CARD, () -> compensations.cancelCard(scope));
            card = steps.callOrPark(CREATE_CARD, null, () -> writes.createCard(request));
            steps.recordCard(card);
            steps.moveTo(CardStatus.CREATED);
            CardCommand cardCommand = new CardCommand(scope, card.cardId());

            awaitProcessorConfirmation(request);
            steps.throwIfCancelRequested();

            steps.compensateWith(RESET_LIMITS, () -> compensations.resetLimits(cardCommand));
            steps.runOrPark(SET_LIMITS, null, () -> writes.setLimits(
                    new SetLimitsCommand(scope, card.cardId(), request.spendLimitMinor())));
            steps.throwIfCancelRequested();

            steps.compensateWith(DEACTIVATE_CARD, () -> compensations.deactivateCard(cardCommand));
            steps.runOrPark(ACTIVATE_CARD, null, () -> writes.activateCard(cardCommand));
            // A cancel accepted while activation was running still takes effect.
            steps.throwIfCancelRequested();
            steps.markPointOfNoReturn();
            steps.moveTo(CardStatus.ACTIVE);
        } catch (ActivityFailure failure) {
            return steps.compensateAndEnd(failure);
        } catch (StepRunner.Stop stop) {
            return steps.compensateAndEnd(stop);
        }

        steps.runBestEffort(SEND_NOTIFICATION, () -> bestEffort.sendNotification(
                new NotificationCommand(scope, "CARD_ISSUED", request.customerRef())));
        return steps.end(CardStatus.ACTIVE, null);
    }

    /**
     * Waits for the processor's confirmation signal or a timer, whichever comes first. If the timer
     * wins, the request is parked for manual review, and a late confirmation still clears it.
     */
    private void awaitProcessorConfirmation(CardRequest request) {
        Duration timeout = request.settings() != null && request.settings().confirmationTimeoutSeconds() != null
                ? Duration.ofSeconds(request.settings().confirmationTimeoutSeconds())
                : DEFAULT_CONFIRMATION_TIMEOUT;
        String cardId = card.cardId();
        if (steps.waitFor(AWAIT_CONFIRMATION, timeout, () -> confirmedCardIds.contains(cardId))) {
            return;
        }
        ReviewAction action = steps.park(ReviewKind.CONFIRMATION_TIMEOUT, AWAIT_CONFIRMATION,
                ErrorTypes.CONFIRMATION_TIMEOUT, () -> confirmedCardIds.contains(cardId));
        if (action == ReviewAction.REJECT) {
            throw new StepRunner.Stop(CardStatus.REJECTED, ErrorTypes.REJECTED_BY_REVIEWER);
        }
        steps.markDone(AWAIT_CONFIRMATION);
    }

    /** Duplicate events are ignored by event ID. Early events are kept until the wait starts. */
    @Override
    public void processorEvent(ProcessorEvent event) {
        if (event == null || event.eventId() == null || !seenEventIds.add(event.eventId())) {
            LOG.info("Ignoring duplicate or empty processor event");
            return;
        }
        if (ProcessorEvent.CARD_CREATED.equals(event.type()) && event.cardId() != null) {
            confirmedCardIds.add(event.cardId());
        }
    }

    @Override
    public void validateReview(ReviewDecision decision) {
        steps.validateDecision(decision);
    }

    @Override
    public ReviewOutcome review(ReviewDecision decision) {
        return steps.decide(decision);
    }

    @Override
    public void validateCancel(CancelRequest request) {
        steps.validateCancel(request);
    }

    @Override
    public CancelOutcome cancel(CancelRequest request) {
        return steps.requestCancel(request);
    }

    @Override
    public CardProgress progress() {
        return steps.progress();
    }

    private static CardActivities stub(RetryProfile profile) {
        return Workflow.newActivityStub(CardActivities.class, profile.options());
    }
}
