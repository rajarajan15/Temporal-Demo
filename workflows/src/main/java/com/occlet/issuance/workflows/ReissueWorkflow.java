package com.occlet.issuance.workflows;

import com.occlet.issuance.contracts.CardProgress;
import com.occlet.issuance.contracts.ReissueRequest;
import com.occlet.issuance.contracts.WorkflowOutcome;
import com.occlet.issuance.contracts.WorkflowTypes;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/** Replaces a lost, stolen, damaged or expired card. Never edits the original issuance run. */
@WorkflowInterface
public interface ReissueWorkflow {
    @WorkflowMethod(name = WorkflowTypes.REISSUE)
    WorkflowOutcome reissue(ReissueRequest request);

    @QueryMethod(name = WorkflowTypes.PROGRESS_QUERY)
    CardProgress progress();
}
