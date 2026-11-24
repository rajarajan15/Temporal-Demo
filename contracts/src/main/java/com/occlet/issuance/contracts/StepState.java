package com.occlet.issuance.contracts;

public enum StepState {
    PENDING,
    RUNNING,
    /** Waiting for a signal or a timer. */
    WAITING,
    /** Waiting for an operator decision. */
    PARKED,
    DONE,
    FAILED,
    COMPENSATING,
    COMPENSATED
}
