package com.occlet.issuance.mock;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Every mock call passes through here: failure injection, then idempotent execution. */
@Component
public class MockGate {
    private final long dropResponseDelayMs;
    private final Map<String, FailureRule> rules = new HashMap<>();
    private final Map<String, Integer> remaining = new HashMap<>();
    private final Map<String, Object> results = new HashMap<>();
    private final Map<String, Integer> calls = new TreeMap<>();
    private final Map<String, Integer> effects = new TreeMap<>();

    public MockGate(@Value("${mock.drop-response-delay-ms:4000}") long dropResponseDelayMs) {
        this.dropResponseDelayMs = dropResponseDelayMs;
    }

    /** The tenant is the first segment of the idempotency key. */
    public static String tenantOf(String idempotencyKey) {
        int separator = idempotencyKey == null ? -1 : idempotencyKey.indexOf(':');
        if (separator <= 0) {
            throw new MockException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY");
        }
        return idempotencyKey.substring(0, separator);
    }

    @SuppressWarnings("unchecked")
    public <T> T execute(String mock, String operation, String key, Supplier<T> work) {
        String tenant = tenantOf(key);
        String endpoint = endpoint(tenant, mock, operation);
        FailureRule rule;
        synchronized (this) {
            calls.merge(endpoint, 1, Integer::sum);
            rule = consumeRule(endpoint);
        }

        if (rule != null) {
            switch (rule.mode()) {
                case OFFLINE -> throw new MockException(HttpStatus.SERVICE_UNAVAILABLE, "OFFLINE");
                case SERVER_ERROR -> throw new MockException(HttpStatus.INTERNAL_SERVER_ERROR, "SERVER_ERROR");
                case BUSINESS_ERROR -> throw new MockException(
                        HttpStatus.UNPROCESSABLE_ENTITY, rule.code() == null ? "BUSINESS_ERROR" : rule.code());
                case LATENCY -> pause(rule.latencyMs() == null ? 0 : rule.latencyMs());
                case DROP_RESPONSE -> { }
            }
        }

        T result;
        synchronized (this) {
            String resultKey = endpoint + "|" + key;
            if (results.containsKey(resultKey)) {
                result = (T) results.get(resultKey);
            } else {
                result = work.get();
                results.put(resultKey, result);
                effects.merge(endpoint, 1, Integer::sum);
            }
        }

        if (rule != null && rule.mode() == FailureRule.Mode.DROP_RESPONSE) {
            pause(dropResponseDelayMs);
        }
        return result;
    }

    /** Reads apply failure injection but are never cached, so every page reflects current data. */
    public <T> T read(String mock, String operation, String tenant, Supplier<T> work) {
        String endpoint = endpoint(tenant, mock, operation);
        FailureRule rule;
        synchronized (this) {
            calls.merge(endpoint, 1, Integer::sum);
            rule = consumeRule(endpoint);
        }
        if (rule != null) {
            switch (rule.mode()) {
                case OFFLINE -> throw new MockException(HttpStatus.SERVICE_UNAVAILABLE, "OFFLINE");
                case SERVER_ERROR -> throw new MockException(HttpStatus.INTERNAL_SERVER_ERROR, "SERVER_ERROR");
                case BUSINESS_ERROR -> throw new MockException(
                        HttpStatus.UNPROCESSABLE_ENTITY, rule.code() == null ? "BUSINESS_ERROR" : rule.code());
                case LATENCY -> pause(rule.latencyMs() == null ? 0 : rule.latencyMs());
                case DROP_RESPONSE -> { }
            }
        }
        T result = work.get();
        if (rule != null && rule.mode() == FailureRule.Mode.DROP_RESPONSE) {
            pause(dropResponseDelayMs);
        }
        return result;
    }

    public synchronized void setRule(String tenant, String mock, String operation, FailureRule rule) {
        String endpoint = endpoint(tenant, mock, operation);
        rules.put(endpoint, rule);
        remaining.put(endpoint, rule.remaining());
    }

    public synchronized void reset() {
        rules.clear();
        remaining.clear();
        results.clear();
        calls.clear();
        effects.clear();
    }

    public synchronized Map<String, Object> counters(String tenant) {
        String prefix = tenant + "/";
        return Map.of("calls", filter(calls, prefix), "sideEffects", filter(effects, prefix));
    }

    private FailureRule consumeRule(String endpoint) {
        FailureRule rule = rules.get(endpoint);
        if (rule == null) {
            return null;
        }
        int left = remaining.get(endpoint);
        if (left == 0) {
            rules.remove(endpoint);
            remaining.remove(endpoint);
            return null;
        }
        if (left > 0) {
            remaining.put(endpoint, left - 1);
        }
        return rule;
    }

    private static Map<String, Integer> filter(Map<String, Integer> source, String prefix) {
        Map<String, Integer> filtered = new TreeMap<>();
        source.forEach((endpoint, count) -> {
            if (endpoint.startsWith(prefix)) {
                filtered.put(endpoint.substring(prefix.length()), count);
            }
        });
        return filtered;
    }

    private static String endpoint(String tenant, String mock, String operation) {
        return tenant + "/" + mock + "/" + operation;
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
