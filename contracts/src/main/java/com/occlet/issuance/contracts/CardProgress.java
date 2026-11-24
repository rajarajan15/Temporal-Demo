package com.occlet.issuance.contracts;

import java.util.List;

/**
 * @param pendingReview the decision an operator must make, or null
 * @param finished true once the workflow has reached its final state
 */
public record CardProgress(
        CardStatus status,
        String reasonCode,
        CardRef card,
        List<StepRecord> steps,
        ReviewItem pendingReview,
        Boolean finished) {}
