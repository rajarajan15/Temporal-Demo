package com.occlet.issuance.adapters.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.occlet.issuance.ports.PortException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;

/** Posts JSON with an idempotency key and classifies every failure. */
public final class JsonHttpClient {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http;
    private final String baseUrl;
    private final Duration requestTimeout;

    public JsonHttpClient(String baseUrl, Duration requestTimeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.requestTimeout = requestTimeout;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    JsonNode post(String path, String idempotencyKey, Map<String, ?> body) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", idempotencyKey)
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return handle(response.statusCode(), response.body());
        } catch (ConnectException | HttpConnectTimeoutException e) {
            throw new PortException.Technical("CONNECTION_FAILED");
        } catch (HttpTimeoutException e) {
            throw new PortException.Ambiguous("REQUEST_TIMED_OUT");
        } catch (IOException e) {
            throw new PortException.Ambiguous("CONNECTION_LOST");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PortException.Ambiguous("INTERRUPTED");
        }
    }

    private static JsonNode handle(int status, String body) throws IOException {
        if (status >= 200 && status < 300) {
            return body == null || body.isBlank() ? JSON.nullNode() : JSON.readTree(body);
        }
        if (status == 429 || status >= 500) {
            throw new PortException.Technical("HTTP_" + status);
        }
        throw new PortException.Business(businessCode(body));
    }

    private static String businessCode(String body) {
        try {
            JsonNode code = JSON.readTree(body).get("code");
            return code == null ? "UNKNOWN_BUSINESS_ERROR" : code.asText();
        } catch (IOException | RuntimeException e) {
            return "UNKNOWN_BUSINESS_ERROR";
        }
    }
}
