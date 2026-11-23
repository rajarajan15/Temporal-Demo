package com.occlet.issuance.contracts;

/** Stable failure type and reason codes. */
public final class ErrorTypes {
    public static final String TECHNICAL = "TECHNICAL";
    public static final String AMBIGUOUS = "AMBIGUOUS";
    public static final String TIMEOUT = "TIMEOUT";

    public static final String INVALID_REQUEST = "INVALID_REQUEST";
    public static final String INVALID_PRODUCT = "INVALID_PRODUCT";
    public static final String LIMIT_EXCEEDS_POLICY = "LIMIT_EXCEEDS_POLICY";
    public static final String UNKNOWN_BUSINESS_ERROR = "UNKNOWN_BUSINESS_ERROR";

    public static final String KYC_REJECTED = "KYC_REJECTED";
    public static final String KYC_NEEDS_REVIEW = "KYC_NEEDS_REVIEW";
    public static final String RETRIES_EXHAUSTED = "RETRIES_EXHAUSTED";
    public static final String COMPENSATION_FAILED = "COMPENSATION_FAILED";

    private ErrorTypes() {}
}
