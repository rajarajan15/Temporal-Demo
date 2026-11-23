package com.occlet.issuance.activities.api;

public record NotificationCommand(RequestScope scope, String template, String customerRef) {}
