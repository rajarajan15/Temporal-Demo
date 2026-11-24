package com.occlet.issuance.contracts;

public enum ReissueReason {
    LOST,
    STOLEN,
    DAMAGED,
    EXPIRED;

    /** Lost and stolen cards are blocked before the replacement is issued. */
    public boolean blocksOldCard() {
        return this == LOST || this == STOLEN;
    }
}
