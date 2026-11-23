package com.occlet.issuance.adapters.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.occlet.issuance.contracts.KycDecision;
import com.occlet.issuance.ports.KycPort;
import com.occlet.issuance.ports.PortException;
import java.util.Map;

public final class HttpKycAdapter implements KycPort {
    private final JsonHttpClient client;

    public HttpKycAdapter(JsonHttpClient client) {
        this.client = client;
    }

    @Override
    public KycDecision check(String idempotencyKey, String customerRef) {
        JsonNode response = client.post("/kyc/checks", idempotencyKey, Map.of("customerRef", customerRef));
        try {
            return KycDecision.valueOf(response.path("decision").asText());
        } catch (IllegalArgumentException e) {
            throw new PortException.Technical("MALFORMED_RESPONSE");
        }
    }
}
