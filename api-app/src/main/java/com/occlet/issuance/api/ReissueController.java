package com.occlet.issuance.api;

import static com.occlet.issuance.api.ApiResponses.TENANT_HEADER;
import static com.occlet.issuance.api.ApiResponses.error;
import static com.occlet.issuance.api.ApiResponses.isSafeReference;

import com.occlet.issuance.contracts.CardRequest;
import com.occlet.issuance.contracts.CardStatus;
import com.occlet.issuance.contracts.CardType;
import com.occlet.issuance.contracts.ReissueReason;
import com.occlet.issuance.contracts.ReissueRequest;
import com.occlet.issuance.contracts.WorkflowTypes;
import com.occlet.issuance.engine.CardSearchAttributes;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.SearchAttributes;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Starts the reissue path for a lost, stolen, damaged or expired card. */
@RestController
class ReissueController {
    private static final int MAX_KEY_LENGTH = 60;

    private final WorkflowClient client;
    private final WorkflowCalls calls;
    private final IssuanceProperties properties;

    ReissueController(WorkflowClient client, WorkflowCalls calls, IssuanceProperties properties) {
        this.client = client;
        this.calls = calls;
        this.properties = properties;
    }

    record ReissueBody(ReissueReason reason, String productCode, String customerRef, String accountRef, Long spendLimitMinor) {}

    @PostMapping("/v1/cards/{cardId}/reissue")
    ResponseEntity<?> reissue(
            @RequestHeader(TENANT_HEADER) String tenant,
            @RequestHeader("Idempotency-Key") String requestId,
            @PathVariable String cardId,
            @RequestBody ReissueBody body) {
        if (!properties.isTenant(tenant)) {
            return error(HttpStatus.BAD_REQUEST, "UNKNOWN_TENANT");
        }
        if (!isSafeReference(requestId) || requestId.length() > MAX_KEY_LENGTH || !isSafeReference(cardId)
                || body.reason() == null || !isSafeReference(body.productCode()) || !isSafeReference(body.customerRef())
                || !isSafeReference(body.accountRef()) || body.spendLimitMinor() == null) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        }

        CardRequest newCard = new CardRequest(requestId + "-new", tenant, CardType.VIRTUAL, body.productCode(),
                body.customerRef(), body.accountRef(), body.spendLimitMinor(), properties.settingsFor(tenant));
        ReissueRequest request = new ReissueRequest(requestId, tenant, cardId, body.reason(), newCard);
        WorkflowStub stub = client.newUntypedWorkflowStub(
                WorkflowTypes.REISSUE,
                WorkflowOptions.newBuilder()
                        .setWorkflowId(reissueWorkflowId(tenant, requestId))
                        .setTaskQueue(properties.taskQueue())
                        .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                        .setTypedSearchAttributes(SearchAttributes.newBuilder()
                                .set(CardSearchAttributes.TENANT_ID, tenant)
                                .set(CardSearchAttributes.REQUEST_ID, requestId)
                                .set(CardSearchAttributes.CARD_TYPE, CardType.VIRTUAL.name())
                                .set(CardSearchAttributes.CARD_ID, cardId)
                                .set(CardSearchAttributes.CARD_STATUS, CardStatus.REQUESTED.name())
                                .build())
                        .build());
        try {
            stub.start(request);
        } catch (WorkflowExecutionAlreadyStarted duplicate) {
            return calls.progress(reissueWorkflowId(tenant, requestId));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "requestId", requestId, "oldCardId", cardId, "newCardRequestId", newCard.requestId(), "status", "REQUESTED"));
    }

    @GetMapping("/v1/reissues/{id}")
    ResponseEntity<?> get(@RequestHeader(TENANT_HEADER) String tenant, @PathVariable String id) {
        if (!properties.isTenant(tenant) || !isSafeReference(id)) {
            return error(HttpStatus.NOT_FOUND, "NOT_FOUND");
        }
        return calls.progress(reissueWorkflowId(tenant, id));
    }

    private static String reissueWorkflowId(String tenant, String requestId) {
        return "reissue-" + tenant + "-" + requestId;
    }
}
