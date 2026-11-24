package com.occlet.issuance.api;

import static com.occlet.issuance.api.ApiResponses.TENANT_HEADER;
import static com.occlet.issuance.api.ApiResponses.error;
import static com.occlet.issuance.api.ApiResponses.isSafeReference;
import static com.occlet.issuance.api.ApiResponses.unavailable;

import com.occlet.issuance.contracts.ReconciliationReport;
import com.occlet.issuance.contracts.ReconciliationRequest;
import com.occlet.issuance.contracts.WorkflowTypes;
import com.occlet.issuance.engine.CardSearchAttributes;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowException;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.SearchAttributes;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** On-demand reconciliation runs, and a way to trigger the nightly schedule now. */
@RestController
class ReconciliationController {
    private final WorkflowClient client;
    private final ReconciliationSchedules schedules;
    private final IssuanceProperties properties;

    ReconciliationController(WorkflowClient client, ReconciliationSchedules schedules, IssuanceProperties properties) {
        this.client = client;
        this.schedules = schedules;
        this.properties = properties;
    }

    record RunBody(Integer pageSize) {}

    @PostMapping("/v1/reconciliations")
    ResponseEntity<?> run(
            @RequestHeader(TENANT_HEADER) String tenant,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody(required = false) RunBody body) {
        if (!properties.isTenant(tenant)) {
            return error(HttpStatus.BAD_REQUEST, "UNKNOWN_TENANT");
        }
        int pageSize = body == null || body.pageSize() == null ? 50 : body.pageSize();
        if (!isSafeReference(key) || pageSize < 1 || pageSize > 500) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        }
        WorkflowStub stub = client.newUntypedWorkflowStub(
                WorkflowTypes.RECONCILIATION,
                WorkflowOptions.newBuilder()
                        .setWorkflowId(runWorkflowId(tenant, key))
                        .setTaskQueue(properties.taskQueue())
                        // A reused key returns the existing run, even after it has finished.
                        .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                        .setTypedSearchAttributes(SearchAttributes.newBuilder().set(CardSearchAttributes.TENANT_ID, tenant).build())
                        .build());
        try {
            stub.start(new ReconciliationRequest(tenant, pageSize));
        } catch (WorkflowExecutionAlreadyStarted duplicate) {
            return ResponseEntity.ok(Map.of("runId", key, "status", "ALREADY_STARTED"));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("runId", key, "status", "RUNNING"));
    }

    @GetMapping("/v1/reconciliations/{key}")
    ResponseEntity<?> get(@RequestHeader(TENANT_HEADER) String tenant, @PathVariable String key) {
        if (!properties.isTenant(tenant) || !isSafeReference(key)) {
            return error(HttpStatus.NOT_FOUND, "NOT_FOUND");
        }
        WorkflowStub stub = client.newUntypedWorkflowStub(runWorkflowId(tenant, key));
        try {
            return ResponseEntity.ok(Map.of("status", "COMPLETED",
                    "report", stub.getResult(1, TimeUnit.SECONDS, ReconciliationReport.class)));
        } catch (TimeoutException stillRunning) {
            return ResponseEntity.ok(Map.of("status", "RUNNING"));
        } catch (WorkflowNotFoundException notFound) {
            return error(HttpStatus.NOT_FOUND, "NOT_FOUND");
        } catch (WorkflowFailedException failed) {
            return ResponseEntity.ok(Map.of("status", "FAILED"));
        } catch (WorkflowException transientError) {
            // For example a timed-out long poll. Not a verdict on the run, so the caller should ask again.
            return unavailable("RESULT_UNAVAILABLE");
        }
    }

    /** Runs the nightly schedule now. If a run is still going, the overlap policy skips this one. */
    @PostMapping("/v1/reconciliations/schedule/trigger")
    ResponseEntity<?> trigger(@RequestHeader(TENANT_HEADER) String tenant) {
        if (!properties.isTenant(tenant)) {
            return error(HttpStatus.BAD_REQUEST, "UNKNOWN_TENANT");
        }
        try {
            schedules.trigger(tenant);
        } catch (RuntimeException unavailable) {
            return unavailable("SCHEDULE_UNAVAILABLE");
        }
        return ResponseEntity.accepted().body(Map.of("scheduleId", ReconciliationSchedules.scheduleId(tenant), "result", "TRIGGERED"));
    }

    private static String runWorkflowId(String tenant, String key) {
        return "reconcile-" + tenant + "-" + key;
    }
}
