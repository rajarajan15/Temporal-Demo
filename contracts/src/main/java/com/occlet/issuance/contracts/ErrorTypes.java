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
    public static final String CONFIRMATION_TIMEOUT = "CONFIRMATION_TIMEOUT";
    public static final String RETRIES_EXHAUSTED = "RETRIES_EXHAUSTED";
    public static final String COMPENSATION_FAILED = "COMPENSATION_FAILED";
    public static final String REJECTED_BY_REVIEWER = "REJECTED_BY_REVIEWER";
    public static final String CANCELLED_BY_OPERATOR = "CANCELLED_BY_OPERATOR";
    public static final String CANCELLED_BY_REQUEST = "CANCELLED_BY_REQUEST";
    public static final String REPLACEMENT_FAILED = "REPLACEMENT_FAILED";

    /** Update rejections, returned to the caller before anything enters history. */
    public static final String NO_REVIEW_PENDING = "NO_REVIEW_PENDING";
    public static final String ACTION_NOT_ALLOWED = "ACTION_NOT_ALLOWED";
    public static final String CANNOT_CANCEL = "CANNOT_CANCEL";
    public static final String ALREADY_FINISHED = "ALREADY_FINISHED";

    private ErrorTypes() {}
}
