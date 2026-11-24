package com.occlet.issuance.contracts;

/** Why a request is parked, which decides the actions an operator may take. */
public enum ReviewKind {
    /** KYC returned "needs review". APPROVE or REJECT. */
    KYC_REVIEW,
    /** The processor confirmation did not arrive in time. APPROVE or REJECT. A late confirmation also clears it. */
    CONFIRMATION_TIMEOUT,
    /** A step ran out of retries. RETRY or CANCEL. */
    RETRIES_EXHAUSTED;

    public boolean allows(ReviewAction action) {
        return switch (this) {
            case KYC_REVIEW, CONFIRMATION_TIMEOUT -> action == ReviewAction.APPROVE || action == ReviewAction.REJECT;
            case RETRIES_EXHAUSTED -> action == ReviewAction.RETRY || action == ReviewAction.CANCEL;
        };
    }
}
