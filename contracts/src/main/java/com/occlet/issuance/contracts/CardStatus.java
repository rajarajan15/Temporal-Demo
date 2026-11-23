package com.occlet.issuance.contracts;

/** Business states from design section 5, plus DELIVERED from amendment A6. */
public enum CardStatus {
    REQUESTED,
    KYC_PASSED,
    CREATED,
    ACTIVE,
    PERSONALIZED,
    IN_PRODUCTION,
    SHIPPED,
    DELIVERED,
    REJECTED,
    CANCELLED,
    UNDER_REVIEW,
    BLOCKED
}
