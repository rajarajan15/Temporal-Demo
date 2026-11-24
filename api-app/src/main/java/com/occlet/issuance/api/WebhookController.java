package com.occlet.issuance.api;

import static com.occlet.issuance.api.ApiResponses.error;
import static com.occlet.issuance.api.ApiResponses.isSafeReference;

import com.occlet.issuance.contracts.ProcessorEvent;
import com.occlet.issuance.contracts.WorkflowTypes;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Webhooks are untrusted input. The handler checks the shared secret and the tenant, routes by
 * request ID (amendment A5), signals the workflow and answers at once. It never waits for the
 * workflow. Duplicates are ignored inside the workflow by event ID.
 */
@RestController
class WebhookController {
    private static final Logger LOG = LoggerFactory.getLogger(WebhookController.class);

    private final WorkflowClient client;
    private final IssuanceProperties properties;

    WebhookController(WorkflowClient client, IssuanceProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    record ProcessorWebhook(String eventId, String tenantId, String requestId, String cardId, String type) {}

    @PostMapping("/v1/webhooks/processor")
    ResponseEntity<?> processor(
            @RequestHeader(value = "X-Webhook-Secret", required = false) String secret,
            @RequestBody ProcessorWebhook event) {
        if (!secretMatches(secret)) {
            return error(HttpStatus.UNAUTHORIZED, "INVALID_SIGNATURE");
        }
        if (!properties.isTenant(event.tenantId()) || !isSafeReference(event.requestId())
                || !isSafeReference(event.eventId()) || !isSafeReference(event.cardId())) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_EVENT");
        }
        String workflowId = CardRequestController.cardWorkflowId(event.tenantId(), event.requestId());
        try {
            client.newUntypedWorkflowStub(workflowId).signal(
                    WorkflowTypes.PROCESSOR_EVENT_SIGNAL, new ProcessorEvent(event.eventId(), event.cardId(), event.type()));
        } catch (WorkflowNotFoundException unknownOrFinished) {
            LOG.info("Ignored processor event {} for {}: no running workflow", event.eventId(), workflowId);
            return ResponseEntity.accepted().body(Map.of("result", "IGNORED"));
        }
        return ResponseEntity.accepted().body(Map.of("result", "ACCEPTED"));
    }

    /** Constant-time comparison, so response timing reveals nothing about the secret. */
    private boolean secretMatches(String provided) {
        String expected = properties.webhooks().secret();
        if (provided == null || expected == null || expected.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }
}
