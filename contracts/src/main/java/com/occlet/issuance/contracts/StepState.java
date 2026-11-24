package com.occlet.issuance.contracts;

public enum StepState {
    PENDING,
    RUNNING,
    DONE,
    FAILED,
    COMPENSATING,
    COMPENSATED
}
