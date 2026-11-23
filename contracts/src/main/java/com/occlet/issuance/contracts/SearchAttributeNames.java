package com.occlet.issuance.contracts;

import java.util.List;

/** Indexed and visible without decryption, so these must never hold sensitive data. */
public final class SearchAttributeNames {
    public static final String TENANT_ID = "TenantId";
    public static final String CARD_ID = "CardId";
    public static final String CARD_TYPE = "CardType";
    public static final String CARD_STATUS = "CardStatus";
    public static final String REQUEST_ID = "RequestId";

    public static final List<String> ALL = List.of(TENANT_ID, CARD_ID, CARD_TYPE, CARD_STATUS, REQUEST_ID);

    private SearchAttributeNames() {}
}
