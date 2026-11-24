package com.occlet.issuance.contracts;

/** @param type UNKNOWN_TO_PLATFORM, MISSING_AT_PROCESSOR or STATUS_MISMATCH */
public record Discrepancy(String cardId, String type, String processorStatus, String platformStatus) {
    public static final String UNKNOWN_TO_PLATFORM = "UNKNOWN_TO_PLATFORM";
    public static final String MISSING_AT_PROCESSOR = "MISSING_AT_PROCESSOR";
    public static final String STATUS_MISMATCH = "STATUS_MISMATCH";
}
