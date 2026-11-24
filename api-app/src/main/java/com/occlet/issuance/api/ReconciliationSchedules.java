package com.occlet.issuance.api;

import com.occlet.issuance.contracts.ReconciliationRequest;
import com.occlet.issuance.contracts.WorkflowTypes;
import com.occlet.issuance.engine.CardSearchAttributes;
import io.temporal.api.enums.v1.ScheduleOverlapPolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.schedules.Schedule;
import io.temporal.client.schedules.ScheduleActionStartWorkflow;
import io.temporal.client.schedules.ScheduleAlreadyRunningException;
import io.temporal.client.schedules.ScheduleClient;
import io.temporal.client.schedules.ScheduleClientOptions;
import io.temporal.client.schedules.ScheduleOptions;
import io.temporal.client.schedules.SchedulePolicy;
import io.temporal.client.schedules.ScheduleSpec;
import io.temporal.common.SearchAttributes;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

/**
 * One nightly reconciliation schedule per tenant. The overlap policy skips a run while the previous
 * one is still going. Schedules are created at startup, and again on demand if Temporal was not
 * reachable when the API started.
 */
@Configuration
class ReconciliationSchedules {
    static final String NIGHTLY_CRON = "0 2 * * *";
    static final int SCHEDULED_PAGE_SIZE = 50;
    private static final Logger LOG = LoggerFactory.getLogger(ReconciliationSchedules.class);

    private final IssuanceProperties properties;
    private final ScheduleClient schedules;

    ReconciliationSchedules(WorkflowClient client, IssuanceProperties properties) {
        this.properties = properties;
        this.schedules = ScheduleClient.newInstance(client.getWorkflowServiceStubs(),
                ScheduleClientOptions.newBuilder().setNamespace(client.getOptions().getNamespace()).build());
    }

    static String scheduleId(String tenant) {
        return "recon-" + tenant;
    }

    @EventListener(ApplicationReadyEvent.class)
    void createMissingSchedules() {
        for (String tenant : properties.tenants().keySet()) {
            try {
                ensure(tenant);
            } catch (RuntimeException unavailable) {
                LOG.warn("Could not create reconciliation schedule {} yet: {}", scheduleId(tenant), unavailable.getMessage());
            }
        }
    }

    /** Runs the tenant's schedule now, creating it first if it is missing. */
    void trigger(String tenant) {
        ensure(tenant);
        schedules.getHandle(scheduleId(tenant)).trigger(ScheduleOverlapPolicy.SCHEDULE_OVERLAP_POLICY_SKIP);
    }

    /** Creates the schedule if it does not exist. Safe to call repeatedly. */
    void ensure(String tenant) {
        try {
            schedules.createSchedule(scheduleId(tenant), nightly(tenant), ScheduleOptions.newBuilder().build());
            LOG.info("Created reconciliation schedule {}", scheduleId(tenant));
        } catch (ScheduleAlreadyRunningException exists) {
            // Already there, nothing to do.
        }
    }

    private Schedule nightly(String tenant) {
        return Schedule.newBuilder()
                .setAction(ScheduleActionStartWorkflow.newBuilder()
                        .setWorkflowType(WorkflowTypes.RECONCILIATION)
                        .setArguments(new ReconciliationRequest(tenant, SCHEDULED_PAGE_SIZE))
                        .setOptions(WorkflowOptions.newBuilder()
                                .setWorkflowId("reconcile-" + tenant + "-nightly")
                                .setTaskQueue(properties.taskQueue())
                                .setTypedSearchAttributes(SearchAttributes.newBuilder()
                                        .set(CardSearchAttributes.TENANT_ID, tenant)
                                        .build())
                                .build())
                        .build())
                .setSpec(ScheduleSpec.newBuilder().setCronExpressions(List.of(NIGHTLY_CRON)).build())
                .setPolicy(SchedulePolicy.newBuilder()
                        .setOverlap(ScheduleOverlapPolicy.SCHEDULE_OVERLAP_POLICY_SKIP)
                        .build())
                .build();
    }
}
