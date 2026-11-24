package com.occlet.issuance.workflows;

import com.occlet.issuance.activities.api.CardActivities;
import com.occlet.issuance.activities.api.NotificationCommand;
import com.occlet.issuance.activities.api.ReconcileCommand;
import com.occlet.issuance.activities.api.ReconciliationActivities;
import com.occlet.issuance.activities.api.RequestScope;
import com.occlet.issuance.contracts.ReconciliationReport;
import com.occlet.issuance.contracts.ReconciliationRequest;
import com.occlet.issuance.engine.RetryProfile;
import io.temporal.failure.ActivityFailure;
import io.temporal.workflow.Workflow;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

public class ReconciliationWorkflowImpl implements ReconciliationWorkflow {
    static final int DEFAULT_PAGE_SIZE = 50;

    private final ReconciliationActivities reconciliation =
            Workflow.newActivityStub(ReconciliationActivities.class, RetryProfile.LONG_RUNNING_HEARTBEAT.options());
    private final CardActivities notifications =
            Workflow.newActivityStub(CardActivities.class, RetryProfile.BEST_EFFORT.options());

    @Override
    public ReconciliationReport run(ReconciliationRequest request) {
        // Workflow time, not the wall clock, so replay computes the same date.
        String businessDate = LocalDate.ofInstant(Instant.ofEpochMilli(Workflow.currentTimeMillis()), ZoneOffset.UTC)
                .minusDays(1)
                .toString();
        int pageSize = request.pageSize() > 0 ? request.pageSize() : DEFAULT_PAGE_SIZE;

        ReconciliationReport report = reconciliation.reconcile(new ReconcileCommand(request.tenantId(), businessDate, pageSize));

        String runKey = "recon-" + Workflow.getInfo().getRunId();
        try {
            notifications.sendNotification(new NotificationCommand(
                    new RequestScope(request.tenantId(), runKey), "RECONCILIATION_SUMMARY", "operations"));
        } catch (ActivityFailure summaryFailed) {
            Workflow.getLogger(ReconciliationWorkflowImpl.class).warn("Reconciliation summary was not sent");
        }
        return report;
    }
}
