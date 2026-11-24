package com.occlet.issuance.api;

import static com.occlet.issuance.api.ApiResponses.TENANT_HEADER;
import static com.occlet.issuance.api.ApiResponses.error;
import static com.occlet.issuance.api.ApiResponses.isSafeFreeText;
import static com.occlet.issuance.api.ApiResponses.isSafeReference;

import com.occlet.issuance.contracts.CancelOutcome;
import com.occlet.issuance.contracts.CancelRequest;
import com.occlet.issuance.contracts.CardRequest;
import com.occlet.issuance.contracts.CardType;
import com.occlet.issuance.contracts.ReviewDecision;
import com.occlet.issuance.contracts.ReviewOutcome;
import com.occlet.issuance.contracts.WorkflowTypes;
import com.occlet.issuance.engine.CardSearchAttributes;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Card request endpoints. Milestone 7 completes the API with listing and an OpenAPI spec. */
@RestController
class CardRequestController {
    private final WorkflowClient client;
    private final WorkflowCalls calls;
    private final IssuanceProperties properties;

    CardRequestController(WorkflowClient client, WorkflowCalls calls, IssuanceProperties properties) {
        this.client = client;
        this.calls = calls;
        this.properties = properties;
    }

    record CreateCardBody(CardType cardType, String productCode, String customerRef, String accountRef, Long spendLimitMinor) {}

    @PostMapping("/v1/card-requests")
    ResponseEntity<?> create(
            @RequestHeader(TENANT_HEADER) String tenant,
            @RequestHeader("Idempotency-Key") String requestId,
            @RequestBody CreateCardBody body) {
        if (!properties.isTenant(tenant)) {
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
                body.customerRef(), body.accountRef(), body.spendLimitMinor(), properties.settingsFor(tenant));
        WorkflowStub stub = client.newUntypedWorkflowStub(
                WorkflowTypes.VIRTUAL_CARD,
                WorkflowOptions.newBuilder()
                        .setWorkflowId(cardWorkflowId(tenant, requestId))
                        .setTaskQueue(properties.taskQueue())
                        .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                        .setTypedSearchAttributes(CardSearchAttributes.initial(request))
                        .build());
        try {
            stub.start(request);
        } catch (WorkflowExecutionAlreadyStarted duplicate) {
            return calls.progress(cardWorkflowId(tenant, requestId));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("requestId", requestId, "status", "REQUESTED"));
    }

    @GetMapping("/v1/card-requests/{id}")
    ResponseEntity<?> get(@RequestHeader(TENANT_HEADER) String tenant, @PathVariable String id) {
        if (!properties.isTenant(tenant) || !isSafeReference(id)) {
            return error(HttpStatus.NOT_FOUND, "NOT_FOUND");
        }
        return calls.progress(cardWorkflowId(tenant, id));
    }

    /** Operator decision on a parked request: APPROVE or REJECT, or RETRY or CANCEL after retries ran out. */
    @PostMapping("/v1/card-requests/{id}/approvals")
    ResponseEntity<?> review(
            @RequestHeader(TENANT_HEADER) String tenant,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @PathVariable String id,
            @RequestBody ReviewDecision decision) {
        if (!properties.isTenant(tenant) || !isSafeReference(id)) {
            return error(HttpStatus.NOT_FOUND, "NOT_FOUND");
        }
        if (decision == null || decision.action() == null || !isSafeReference(decision.reviewer())
                || (key != null && !isSafeReference(key))) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        }
        return calls.update(cardWorkflowId(tenant, id), WorkflowTypes.REVIEW_UPDATE, ReviewOutcome.class, decision, key);
    }

    /** Refused with 409 CANNOT_CANCEL once the card is active. Offer block or reissue instead. */
    @PostMapping("/v1/card-requests/{id}/cancel")
    ResponseEntity<?> cancel(
            @RequestHeader(TENANT_HEADER) String tenant,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @PathVariable String id,
            @RequestBody(required = false) CancelRequest request) {
        if (!properties.isTenant(tenant) || !isSafeReference(id)) {
            return error(HttpStatus.NOT_FOUND, "NOT_FOUND");
        }
        CancelRequest cancel = request == null ? new CancelRequest(null) : request;
        // The reason is free text that enters workflow history, so it must not be able to carry a card number.
        if ((key != null && !isSafeReference(key)) || !isSafeFreeText(cancel.reason())) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        }
        return calls.update(cardWorkflowId(tenant, id), WorkflowTypes.CANCEL_UPDATE, CancelOutcome.class, cancel, key);
    }

    static String cardWorkflowId(String tenant, String requestId) {
        return "card-" + tenant + "-" + requestId;
    }
}
