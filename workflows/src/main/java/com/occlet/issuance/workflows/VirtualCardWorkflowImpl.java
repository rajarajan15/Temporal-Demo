package com.occlet.issuance.workflows;

import static com.occlet.issuance.contracts.StepNames.ACTIVATE_CARD;
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
import com.occlet.issuance.contracts.CardProgress;
import com.occlet.issuance.contracts.CardRef;
import com.occlet.issuance.contracts.CardRequest;
import com.occlet.issuance.contracts.CardStatus;
import com.occlet.issuance.contracts.ErrorTypes;
import com.occlet.issuance.contracts.KycDecision;
import com.occlet.issuance.contracts.WorkflowOutcome;
import com.occlet.issuance.engine.RetryProfile;
import com.occlet.issuance.engine.StepRunner;
import io.temporal.failure.ActivityFailure;
import io.temporal.workflow.Workflow;
import java.util.List;

public class VirtualCardWorkflowImpl implements VirtualCardWorkflow {
    private static final List<String> PLAN = List.of(
            VALIDATE_REQUEST, RUN_KYC, CREATE_CARD, SET_LIMITS, ACTIVATE_CARD, SEND_NOTIFICATION);

    private final CardActivities reads = stub(RetryProfile.FAST_READ);
    private final CardActivities writes = stub(RetryProfile.WRITE_IDEMPOTENT);
    private final CardActivities compensations = stub(RetryProfile.COMPENSATION);
    private final CardActivities bestEffort = stub(RetryProfile.BEST_EFFORT);
    private final StepRunner steps = new StepRunner(PLAN);

    @Override
    public WorkflowOutcome issue(CardRequest request) {
        RequestScope scope = new RequestScope(request.tenantId(), request.requestId());
        try {
            steps.run(VALIDATE_REQUEST, null, () -> reads.validateRequest(request));

            KycDecision kyc = steps.call(RUN_KYC, null, () -> writes.runKyc(request));
            if (kyc == KycDecision.REJECTED) {
                return steps.end(CardStatus.REJECTED, ErrorTypes.KYC_REJECTED);
            }
            if (kyc == KycDecision.NEEDS_REVIEW) {
                // Milestone 3 turns this into a wait for a manual review decision.
                return steps.end(CardStatus.UNDER_REVIEW, ErrorTypes.KYC_NEEDS_REVIEW);
            }
            steps.moveTo(CardStatus.KYC_PASSED);

            steps.compensateWith(CANCEL_CARD, () -> compensations.cancelCard(scope));
            CardRef card = steps.call(CREATE_CARD, null, () -> writes.createCard(request));
            steps.recordCard(card);
            steps.moveTo(CardStatus.CREATED);
            CardCommand cardCommand = new CardCommand(scope, card.cardId());

            steps.compensateWith(RESET_LIMITS, () -> compensations.resetLimits(cardCommand));
            steps.run(SET_LIMITS, null, () -> writes.setLimits(
                    new SetLimitsCommand(scope, card.cardId(), request.spendLimitMinor())));

            steps.compensateWith(DEACTIVATE_CARD, () -> compensations.deactivateCard(cardCommand));
            steps.run(ACTIVATE_CARD, CardStatus.ACTIVE, () -> writes.activateCard(cardCommand));
        } catch (ActivityFailure failure) {
            return steps.compensateAndEnd(failure);
        }

        steps.runBestEffort(SEND_NOTIFICATION, () -> bestEffort.sendNotification(
                new NotificationCommand(scope, "CARD_ISSUED", request.customerRef())));
        return steps.end(CardStatus.ACTIVE, null);
    }

    @Override
    public CardProgress progress() {
        return steps.progress();
    }

    private static CardActivities stub(RetryProfile profile) {
        return Workflow.newActivityStub(CardActivities.class, profile.options());
    }
}
