package com.occlet.issuance.ports;

import java.util.Map;

/** The platform's own view of issued cards, used by reconciliation. */
public interface PlatformRecordsPort {
    /** Card ID to card status for every card the platform issued for a tenant. */
    Map<String, String> cardStatuses(String tenantId);
}
