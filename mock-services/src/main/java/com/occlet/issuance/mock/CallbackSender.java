package com.occlet.issuance.mock;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Delivers processor callbacks to the platform's webhook, the way a real processor would. */
@Component
public class CallbackSender {
    private static final Logger LOG = LoggerFactory.getLogger(CallbackSender.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * @param mode NORMAL (after the default delay), DELAY (after delayMs), DUPLICATE (sent twice
     *     with the same event ID) or NEVER
     */
    public record CallbackRule(String mode, Long delayMs) {}

    record WebhookEvent(String eventId, String tenantId, String requestId, String cardId, String type) {}

    private static final int MAX_DELIVERY_ATTEMPTS = 6;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final Map<String, CallbackRule> rules = new HashMap<>();
    private final String targetUrl;
    private final String secret;
    private final long defaultDelayMs;

    public CallbackSender(
            @Value("${mock.callbacks.target-url}") String targetUrl,
            @Value("${mock.callbacks.secret}") String secret,
            @Value("${mock.callbacks.default-delay-ms:200}") long defaultDelayMs) {
        this.targetUrl = targetUrl;
        this.secret = secret;
        this.defaultDelayMs = defaultDelayMs;
    }

    public synchronized void setRule(String tenant, CallbackRule rule) {
        rules.put(tenant, rule);
    }

    public synchronized void reset() {
        rules.clear();
    }

    /** The creation key is tenant:request:step, so the callback can name the request (amendment A5). */
    public void cardCreated(String creationKey, String cardId) {
        String[] parts = creationKey.split(":", 3);
        if (parts.length < 3) {
            return;
        }
        CallbackRule rule;
        synchronized (this) {
            rule = rules.getOrDefault(parts[0], new CallbackRule("NORMAL", null));
        }
        WebhookEvent event = new WebhookEvent("evt_" + UUID.randomUUID(), parts[0], parts[1], cardId, "CARD_CREATED");
        switch (rule.mode() == null ? "NORMAL" : rule.mode()) {
            case "NEVER" -> LOG.info("Callback suppressed for {}", parts[1]);
            case "DELAY" -> schedule(event, rule.delayMs() == null ? defaultDelayMs : rule.delayMs());
            case "DUPLICATE" -> {
                schedule(event, defaultDelayMs);
                schedule(event, defaultDelayMs + 300);
            }
            default -> schedule(event, defaultDelayMs);
        }
    }

    private void schedule(WebhookEvent event, long delayMs) {
        scheduler.schedule(() -> deliver(event, 1), delayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Real processors retry webhooks that fail, so the platform must tolerate repeats. Connection
     * failures and 5xx answers are retried with backoff, keeping the same event ID. A 4xx answer,
     * such as a wrong secret, is not retried.
     */
    private void deliver(WebhookEvent event, int attempt) {
        boolean retry;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(targetUrl))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .header("X-Webhook-Secret", secret)
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(event)))
                    .build();
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            LOG.info("Callback {} for {} answered {} (attempt {})", event.eventId(), event.requestId(), response.statusCode(), attempt);
            retry = response.statusCode() >= 500;
        } catch (Exception e) {
            LOG.warn("Callback {} for {} failed on attempt {}: {}", event.eventId(), event.requestId(), attempt, e.getMessage());
            retry = true;
        }
        if (retry && attempt < MAX_DELIVERY_ATTEMPTS) {
            long backoffMs = 1000L << (attempt - 1);
            scheduler.schedule(() -> deliver(event, attempt + 1), backoffMs, TimeUnit.MILLISECONDS);
        }
    }
}
