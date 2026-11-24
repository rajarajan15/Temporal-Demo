package com.occlet.issuance.contracts;

public record ReviewItem(ReviewKind kind, String step, String reasonCode, Long sinceMillis) {}
