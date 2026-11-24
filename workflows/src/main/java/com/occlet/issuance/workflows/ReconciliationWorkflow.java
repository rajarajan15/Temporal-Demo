package com.occlet.issuance.workflows;

import com.occlet.issuance.contracts.ReconciliationReport;
import com.occlet.issuance.contracts.ReconciliationRequest;
import com.occlet.issuance.contracts.WorkflowTypes;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/** Started nightly by a Temporal Schedule per tenant, or on demand. */
@WorkflowInterface
public interface ReconciliationWorkflow {
    @WorkflowMethod(name = WorkflowTypes.RECONCILIATION)
    ReconciliationReport run(ReconciliationRequest request);
}
