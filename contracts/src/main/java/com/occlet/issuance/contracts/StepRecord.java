package com.occlet.issuance.contracts;

/** One line of the progress view. Retry attempts are visible in the Temporal Web UI. */
public record StepRecord(String name, StepState state, String lastErrorCode, Long startedAtMillis, Long finishedAtMillis) {}
