package com.occlet.issuance.adapters.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.occlet.issuance.ports.PortException;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JsonHttpClientTest {
    private HttpServer server;
    private JsonHttpClient client;
    private volatile String receivedKey;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        respond("/ok", 200, "{\"value\":\"yes\"}");
        respond("/business", 422, "{\"code\":\"PRODUCT_CLOSED\"}");
        respond("/server-error", 503, "{\"code\":\"OFFLINE\"}");
        respond("/rate-limited", 429, "");
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.start();
        client = new JsonHttpClient("http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofMillis(200));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void successSendsIdempotencyKey() {
        assertEquals("yes", client.post("/ok", "bank-a:req:step", Map.of()).get("value").asText());
        assertEquals("bank-a:req:step", receivedKey);
    }

    @Test
    void clientErrorIsBusinessWithCode() {
        PortException.Business failure =
                assertThrows(PortException.Business.class, () -> client.post("/business", "k", Map.of()));
        assertEquals("PRODUCT_CLOSED", failure.code());
    }

    @Test
    void serverErrorAndRateLimitAreTechnical() {
        assertThrows(PortException.Technical.class, () -> client.post("/server-error", "k", Map.of()));
        assertThrows(PortException.Technical.class, () -> client.post("/rate-limited", "k", Map.of()));
    }

    @Test
    void timeoutIsAmbiguous() {
        assertThrows(PortException.Ambiguous.class, () -> client.post("/slow", "k", Map.of()));
    }

    @Test
    void connectionRefusedIsTechnical() {
        int port = server.getAddress().getPort();
        server.stop(0);
        JsonHttpClient offline = new JsonHttpClient("http://127.0.0.1:" + port, Duration.ofMillis(500));

        assertThrows(PortException.Technical.class, () -> offline.post("/ok", "k", Map.of()));
    }

    private void respond(String path, int status, String body) {
        server.createContext(path, exchange -> {
            receivedKey = exchange.getRequestHeaders().getFirst("Idempotency-Key");
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
    }
}
