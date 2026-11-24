package com.occlet.issuance.contracts;

import java.util.List;

public record CardProgress(CardStatus status, String reasonCode, CardRef card, List<StepRecord> steps) {}
