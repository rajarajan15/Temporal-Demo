package com.occlet.issuance.engine;

import com.occlet.issuance.contracts.CardRequest;
import com.occlet.issuance.contracts.CardStatus;
import com.occlet.issuance.contracts.SearchAttributeNames;
import io.temporal.common.SearchAttributeKey;
import io.temporal.common.SearchAttributes;

public final class CardSearchAttributes {
    public static final SearchAttributeKey<String> TENANT_ID = SearchAttributeKey.forKeyword(SearchAttributeNames.TENANT_ID);
    public static final SearchAttributeKey<String> CARD_ID = SearchAttributeKey.forKeyword(SearchAttributeNames.CARD_ID);
    public static final SearchAttributeKey<String> CARD_TYPE = SearchAttributeKey.forKeyword(SearchAttributeNames.CARD_TYPE);
    public static final SearchAttributeKey<String> CARD_STATUS = SearchAttributeKey.forKeyword(SearchAttributeNames.CARD_STATUS);
    public static final SearchAttributeKey<String> REQUEST_ID = SearchAttributeKey.forKeyword(SearchAttributeNames.REQUEST_ID);

    private CardSearchAttributes() {}

    public static SearchAttributes initial(CardRequest request) {
        return SearchAttributes.newBuilder()
                .set(TENANT_ID, request.tenantId())
                .set(CARD_TYPE, request.cardType().name())
                .set(CARD_STATUS, CardStatus.REQUESTED.name())
                .set(REQUEST_ID, request.requestId())
                .build();
    }
}
