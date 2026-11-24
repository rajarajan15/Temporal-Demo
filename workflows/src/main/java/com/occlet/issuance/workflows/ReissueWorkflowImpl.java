package com.occlet.issuance.workflows;

import static com.occlet.issuance.contracts.StepNames.BLOCK_OLD_CARD;
import static com.occlet.issuance.contracts.StepNames.ISSUE_NEW_CARD;
import static com.occlet.issuance.contracts.StepNames.LINK_CARDS;

import com.occlet.issuance.activities.api.CardActivities;
import com.occlet.issuance.activities.api.CardCommand;
import com.occlet.issuance.activities.api.LinkCommand;
import com.occlet.issuance.activities.api.RequestScope;
import com.occlet.issuance.contracts.CardProgress;
import com.occlet.issuance.contracts.CardRequest;
import com.occlet.issuance.contracts.CardStatus;
import com.occlet.issuance.contracts.ReissueRequest;
import com.occlet.issuance.contracts.WorkflowOutcome;
import com.occlet.issuance.engine.CardSearchAttributes;
import com.occlet.issuance.engine.RetryProfile;
import com.occlet.issuance.engine.StepRunner;
import com.occlet.issuance.contracts.ErrorTypes;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ChildWorkflowFailure;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Workflow;
import java.util.ArrayList;
import java.util.List;

/**
 * Blocks the old card first when it was lost or stolen, issues the replacement as a child
 * workflow, then links old and new card references.
 */
public class ReissueWorkflowImpl implements ReissueWorkflow {
    private final CardActivities writes = Workflow.newActivityStub(CardActivities.class, RetryProfile.WRITE_IDEMPOTENT.options());
    private StepRunner steps = new StepRunner(List.of());

    @Override
    public WorkflowOutcome reissue(ReissueRequest request) {
        List<String> plan = new ArrayList<>();
        if (request.reason().blocksOldCard()) {
            plan.add(BLOCK_OLD_CARD);
        }
        plan.add(ISSUE_NEW_CARD);
        plan.add(LINK_CARDS);
        steps = new StepRunner(plan);
        RequestScope scope = new RequestScope(request.tenantId(), request.requestId());

        try {
            if (request.reason().blocksOldCard()) {
                steps.runOrPark(BLOCK_OLD_CARD, null, () -> writes.blockCard(new CardCommand(scope, request.oldCardId())));
            }

            CardRequest newCard = request.newCard();
            VirtualCardWorkflow child = Workflow.newChildWorkflowStub(VirtualCardWorkflow.class,
                    ChildWorkflowOptions.newBuilder()
                            .setWorkflowId(cardWorkflowId(newCard))
                            .setTypedSearchAttributes(CardSearchAttributes.initial(newCard))
                            .build());
            WorkflowOutcome issued;
            try {
                issued = steps.call(ISSUE_NEW_CARD, null, () -> child.issue(newCard));
            } catch (ChildWorkflowFailure replacementNotIssued) {
                // For example, the replacement's workflow ID is already taken. The old card may
                // already be blocked, so an operator must decide what happens next.
                return steps.end(CardStatus.UNDER_REVIEW, ErrorTypes.REPLACEMENT_FAILED);
            }
            if (issued.card() != null) {
                steps.recordCard(issued.card());
            }
            if (issued.status() != CardStatus.ACTIVE) {
                return steps.end(issued.status(), issued.reasonCode());
            }

            String newCardId = issued.card().cardId();
            steps.runOrPark(LINK_CARDS, null, () -> writes.linkReplacement(
                    new LinkCommand(scope, request.oldCardId(), newCardId)));
            return steps.end(CardStatus.ACTIVE, null);
        } catch (ActivityFailure failure) {
            return steps.compensateAndEnd(failure);
        } catch (StepRunner.Stop stop) {
            return steps.compensateAndEnd(stop);
        }
    }

    @Override
    public CardProgress progress() {
        return steps.progress();
    }

    static String cardWorkflowId(CardRequest request) {
        return "card-" + request.tenantId() + "-" + request.requestId();
    }
}
