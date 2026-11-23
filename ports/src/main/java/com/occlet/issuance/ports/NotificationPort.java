package com.occlet.issuance.ports;

public interface NotificationPort {
    void send(String idempotencyKey, String template, String customerRef);
}
