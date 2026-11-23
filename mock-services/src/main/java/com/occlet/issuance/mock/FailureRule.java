package com.occlet.issuance.mock;

/**
 * A failure applied to one tenant's endpoint.
 *
 * @param times how many calls it affects; negative means every call
 * @param latencyMs delay for LATENCY mode
 * @param code business code for BUSINESS_ERROR mode
 */
public record FailureRule(Mode mode, Integer times, Long latencyMs, String code) {
    public enum Mode {
        /** Service unavailable (503) without doing the work. */
        OFFLINE,
        /** Server error (500) without doing the work. Use times=N for "fail the first N calls". */
        SERVER_ERROR,
        /** Client error (422) with a business code. */
        BUSINESS_ERROR,
        /** Delays before doing the work, including beyond the activity timeout. */
        LATENCY,
        /** Does the work, then holds the response until the client gives up. */
        DROP_RESPONSE
    }

    public int remaining() {
        return times == null ? 1 : times;
    }
}
