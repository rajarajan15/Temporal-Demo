package com.occlet.issuance.api;

import com.occlet.issuance.contracts.CardProgress;
import com.occlet.issuance.contracts.CardRequest;
import com.occlet.issuance.contracts.CardType;
import com.occlet.issuance.contracts.WorkflowOutcome;
import com.occlet.issuance.contracts.WorkflowTypes;
import com.occlet.issuance.engine.CardSearchAttributes;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowException;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Virtual card endpoints for milestone 2. Milestone 7 completes the API. */
@RestController
class CardRequestController {
    private static final String TENANT_HEADER = "X-Tenant-Id";
    private static final Pattern REFERENCE = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    // Stops a card number being passed as a reference and entering workflow history.
    private static final Pattern LONG_DIGIT_RUN = Pattern.compile("\\d(?:[_-]?\\d){12,}");

    private final WorkflowClient client;
    private final String taskQueue;
    private final Set<String> tenants;

    CardRequestController(
            WorkflowClient client,
            @Value("${issuance.task-queue}") String taskQueue,
            @Value("${issuance.tenants}") List<String> tenants) {
        this.client = client;
        this.taskQueue = taskQueue;
        this.tenants = Set.copyOf(tenants);
    }

    record CreateCardBody(CardType cardType, String productCode, String customerRef, String accountRef, Long spendLimitMinor) {}

    @PostMapping("/v1/card-requests")
    ResponseEntity<?> create(
            @RequestHeader(TENANT_HEADER) String tenant,
            @RequestHeader("Idempotency-Key") String requestId,
            @RequestBody CreateCardBody body) {
        if (!tenants.contains(tenant)) {
            return error(HttpStatus.BAD_REQUEST, "UNKNOWN_TENANT");
        }
        if (!isSafeReference(requestId) || !isSafeReference(body.productCode())
                || !isSafeReference(body.customerRef()) || !isSafeReference(body.accountRef())
                || body.spendLimitMinor() == null) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        }
        if (body.cardType() != CardType.VIRTUAL) {
            return error(HttpStatus.BAD_REQUEST, "UNSUPPORTED_CARD_TYPE");
        }

        CardRequest request = new CardRequest(requestId, tenant, body.cardType(), body.productCode(),
                body.customerRef(), body.accountRef(), body.spendLimitMinor());
        WorkflowStub stub = client.newUntypedWorkflowStub(
                WorkflowTypes.VIRTUAL_CARD,
                WorkflowOptions.newBuilder()
                        .setWorkflowId(workflowId(tenant, requestId))
                        .setTaskQueue(taskQueue)
                        .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                        .setTypedSearchAttributes(CardSearchAttributes.initial(request))
                        .build());
        try {
            stub.start(request);
        } catch (WorkflowExecutionAlreadyStarted duplicate) {
            return read(tenant, requestId);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("requestId", requestId, "status", "REQUESTED"));
    }

    @GetMapping("/v1/card-requests/{id}")
    ResponseEntity<?> get(@RequestHeader(TENANT_HEADER) String tenant, @PathVariable String id) {
        if (!tenants.contains(tenant) || !REFERENCE.matcher(id).matches()) {
            return error(HttpStatus.NOT_FOUND, "NOT_FOUND");
        }
        return read(tenant, id);
    }

    private ResponseEntity<?> read(String tenant, String requestId) {
        WorkflowStub stub = client.newUntypedWorkflowStub(workflowId(tenant, requestId));
        try {
            return ResponseEntity.ok(stub.query(WorkflowTypes.PROGRESS_QUERY, CardProgress.class));
        } catch (WorkflowNotFoundException notFound) {
            return error(HttpStatus.NOT_FOUND, "NOT_FOUND");
        } catch (WorkflowException queryFailed) {
            return finishedResult(stub);
        }
    }

    /** With no worker to answer the query, a finished run can still report its result. */
    private ResponseEntity<?> finishedResult(WorkflowStub stub) {
        try {
            WorkflowOutcome outcome = stub.getResult(1, TimeUnit.SECONDS, WorkflowOutcome.class);
            return ResponseEntity.ok(new CardProgress(outcome.status(), outcome.reasonCode(), outcome.card(), List.of()));
        } catch (TimeoutException | WorkflowException unavailable) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header("Retry-After", "5")
                    .body(Map.of("code", "PROGRESS_UNAVAILABLE"));
        }
    }

    private static String workflowId(String tenant, String requestId) {
        return "card-" + tenant + "-" + requestId;
    }

    private static boolean isSafeReference(String value) {
        return value != null && REFERENCE.matcher(value).matches() && !LONG_DIGIT_RUN.matcher(value).find();
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String code) {
        return ResponseEntity.status(status).body(Map.of("code", code));
    }
}
