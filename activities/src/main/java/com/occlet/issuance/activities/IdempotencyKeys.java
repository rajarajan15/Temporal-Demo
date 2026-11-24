package com.occlet.issuance.activities;

import com.occlet.issuance.activities.api.RequestScope;

/** Built only from workflow input, so every retry of a step sends the same key. */
public final class IdempotencyKeys {
    private IdempotencyKeys() {}

    public static String of(RequestScope scope, String stepName) {
        return scope.tenantId() + ":" + scope.requestId() + ":" + stepName;
    }
}
