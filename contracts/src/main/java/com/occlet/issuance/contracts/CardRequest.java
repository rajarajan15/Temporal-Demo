package com.occlet.issuance.contracts;

/** Workflow input. Holds references only, never card data. New fields must be optional. */
public record CardRequest(
        String requestId,
        String tenantId,
        CardType cardType,
        String productCode,
        String customerRef,
        String accountRef,
        long spendLimitMinor,
        TenantSettings settings) {

    public CardRequest(String requestId, String tenantId, CardType cardType, String productCode,
            String customerRef, String accountRef, long spendLimitMinor) {
        this(requestId, tenantId, cardType, productCode, customerRef, accountRef, spendLimitMinor, null);
    }
}
