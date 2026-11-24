package com.occlet.issuance.contracts;

public record WorkflowOutcome(CardStatus status, String reasonCode, CardRef card) {}
