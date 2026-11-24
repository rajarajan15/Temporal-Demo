package com.occlet.issuance.workflows;

import com.occlet.issuance.contracts.CancelOutcome;
import com.occlet.issuance.contracts.CancelRequest;
import com.occlet.issuance.contracts.CardProgress;
import com.occlet.issuance.contracts.CardRequest;
import com.occlet.issuance.contracts.ProcessorEvent;
import com.occlet.issuance.contracts.ReviewDecision;
import com.occlet.issuance.contracts.ReviewOutcome;
import com.occlet.issuance.contracts.WorkflowOutcome;
import com.occlet.issuance.contracts.WorkflowTypes;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.UpdateMethod;
import io.temporal.workflow.UpdateValidatorMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface VirtualCardWorkflow {
    @WorkflowMethod(name = WorkflowTypes.VIRTUAL_CARD)
    WorkflowOutcome issue(CardRequest request);

    @QueryMethod(name = WorkflowTypes.PROGRESS_QUERY)
    CardProgress progress();

    /** Machine callback from the processor. Fire and forget, so a signal. */
    @SignalMethod(name = WorkflowTypes.PROCESSOR_EVENT_SIGNAL)
    void processorEvent(ProcessorEvent event);

    /** Operator decision on a parked request. The caller needs a verdict, so an update. */
    @UpdateMethod(name = WorkflowTypes.REVIEW_UPDATE)
    ReviewOutcome review(ReviewDecision decision);

    @UpdateValidatorMethod(updateName = WorkflowTypes.REVIEW_UPDATE)
    void validateReview(ReviewDecision decision);

    /** Cancellation (amendment A7). Refused once the card is active. */
    @UpdateMethod(name = WorkflowTypes.CANCEL_UPDATE)
    CancelOutcome cancel(CancelRequest request);

    @UpdateValidatorMethod(updateName = WorkflowTypes.CANCEL_UPDATE)
    void validateCancel(CancelRequest request);
}
