package com.occlet.issuance.contracts;

/** @param newCard the replacement card request, issued as a child workflow */
public record ReissueRequest(String requestId, String tenantId, String oldCardId, ReissueReason reason, CardRequest newCard) {}
