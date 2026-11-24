package com.occlet.issuance.ports;

import com.occlet.issuance.contracts.KycDecision;

public interface KycPort {
    KycDecision check(String idempotencyKey, String customerRef);
}
