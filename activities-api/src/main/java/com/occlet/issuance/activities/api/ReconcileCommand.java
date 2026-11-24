package com.occlet.issuance.activities.api;

public record ReconcileCommand(String tenantId, String businessDate, int pageSize) {}
