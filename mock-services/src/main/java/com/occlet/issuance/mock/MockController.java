package com.occlet.issuance.mock;

import com.occlet.issuance.contracts.KycDecision;
import java.util.Locale;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Mock KYC, card processor and notification endpoints. */
@RestController
class MockController {
    private static final String KEY = "Idempotency-Key";
    private static final Map<String, String> OK = Map.of("result", "OK");

    private final MockGate gate;
    private final ProcessorStore store;

    MockController(MockGate gate, ProcessorStore store) {
        this.gate = gate;
        this.store = store;
    }

    record KycBody(String customerRef) {}
    record CreateBody(String productCode, String accountRef) {}
    record CancelBody(String creationKey) {}
    record LimitBody(long limitMinor) {}
    record NotificationBody(String template, String customerRef) {}

    /** Customer references containing "reject" or "review" drive the KYC outcome. */
    @PostMapping("/kyc/checks")
    Map<String, KycDecision> kyc(@RequestHeader(KEY) String key, @RequestBody KycBody body) {
        return gate.execute("kyc", "check", key, () -> {
            String ref = body.customerRef().toLowerCase(Locale.ROOT);
            KycDecision decision = ref.contains("reject") ? KycDecision.REJECTED
                    : ref.contains("review") ? KycDecision.NEEDS_REVIEW
                    : KycDecision.APPROVED;
            return Map.of("decision", decision);
        });
    }

    @PostMapping("/processor/cards")
    ProcessorStore.Created create(@RequestHeader(KEY) String key, @RequestBody CreateBody body) {
        return gate.execute("processor", "createCard", key, () -> store.create(key, body.productCode()));
    }

    @PostMapping("/processor/cards/cancel")
    Map<String, String> cancel(@RequestHeader(KEY) String key, @RequestBody CancelBody body) {
        return gate.execute("processor", "cancelCard", key, () -> {
            store.cancelByCreationKey(MockGate.tenantOf(key), body.creationKey());
            return OK;
        });
    }

    @PostMapping("/processor/cards/{cardId}/limits")
    Map<String, String> limits(@RequestHeader(KEY) String key, @PathVariable String cardId, @RequestBody LimitBody body) {
        return gate.execute("processor", "setLimits", key, () -> {
            store.setLimit(MockGate.tenantOf(key), cardId, body.limitMinor());
            return OK;
        });
    }

    @PostMapping("/processor/cards/{cardId}/limits/reset")
    Map<String, String> resetLimits(@RequestHeader(KEY) String key, @PathVariable String cardId) {
        return gate.execute("processor", "resetLimits", key, () -> {
            store.setLimit(MockGate.tenantOf(key), cardId, 0);
            return OK;
        });
    }

    @PostMapping("/processor/cards/{cardId}/activate")
    Map<String, String> activate(@RequestHeader(KEY) String key, @PathVariable String cardId) {
        return gate.execute("processor", "activateCard", key, () -> {
            store.setStatus(MockGate.tenantOf(key), cardId, "ACTIVE");
            return OK;
        });
    }

    @PostMapping("/processor/cards/{cardId}/deactivate")
    Map<String, String> deactivate(@RequestHeader(KEY) String key, @PathVariable String cardId) {
        return gate.execute("processor", "deactivateCard", key, () -> {
            store.setStatus(MockGate.tenantOf(key), cardId, "INACTIVE");
            return OK;
        });
    }

    @PostMapping("/notifications")
    Map<String, String> notify(@RequestHeader(KEY) String key, @RequestBody NotificationBody body) {
        return gate.execute("notification", "send", key, () -> OK);
    }
}
