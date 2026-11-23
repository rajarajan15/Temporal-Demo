package com.occlet.issuance.activities.api;

public record CardCommand(RequestScope scope, String cardId) {}
