package com.occlet.issuance.adapters.http;

import com.occlet.issuance.ports.NotificationPort;
import java.util.Map;

public final class HttpNotificationAdapter implements NotificationPort {
    private final JsonHttpClient client;

    public HttpNotificationAdapter(JsonHttpClient client) {
        this.client = client;
    }

    @Override
    public void send(String idempotencyKey, String template, String customerRef) {
        client.post("/notifications", idempotencyKey, Map.of("template", template, "customerRef", customerRef));
    }
}
