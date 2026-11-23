package com.occlet.issuance.activities.api;

public record SetLimitsCommand(RequestScope scope, String cardId, long limitMinor) {}
