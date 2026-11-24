package com.occlet.issuance.activities.api;

import com.occlet.issuance.contracts.ReconciliationReport;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface ReconciliationActivities {
    /** Long-running: pages through processor records, heartbeating its progress after each page. */
    @ActivityMethod
    ReconciliationReport reconcile(ReconcileCommand command);
}
