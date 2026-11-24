package com.occlet.issuance.activities.api;

public record LinkCommand(RequestScope scope, String oldCardId, String newCardId) {}
