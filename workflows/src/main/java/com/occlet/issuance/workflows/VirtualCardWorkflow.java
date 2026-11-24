package com.occlet.issuance.workflows;

import com.occlet.issuance.contracts.CardProgress;
import com.occlet.issuance.contracts.CardRequest;
import com.occlet.issuance.contracts.WorkflowOutcome;
import com.occlet.issuance.contracts.WorkflowTypes;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface VirtualCardWorkflow {
    @WorkflowMethod(name = WorkflowTypes.VIRTUAL_CARD)
    WorkflowOutcome issue(CardRequest request);

    @QueryMethod(name = WorkflowTypes.PROGRESS_QUERY)
    CardProgress progress();
}
