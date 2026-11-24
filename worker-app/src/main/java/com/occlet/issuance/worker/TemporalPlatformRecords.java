package com.occlet.issuance.worker;

import com.occlet.issuance.contracts.WorkflowTypes;
import com.occlet.issuance.engine.CardSearchAttributes;
import com.occlet.issuance.ports.PlatformRecordsPort;
import com.occlet.issuance.ports.PortException;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionMetadata;
import io.temporal.common.SearchAttributes;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads the platform's card records from Temporal visibility: each issuance workflow's CardId and
 * CardStatus search attributes. In the real platform this port would call the card service.
 */
class TemporalPlatformRecords implements PlatformRecordsPort {
    private static final Pattern TENANT = Pattern.compile("[a-z0-9-]{1,32}");

    private final WorkflowClient client;

    TemporalPlatformRecords(WorkflowClient client) {
        this.client = client;
    }

    @Override
    public Map<String, String> cardStatuses(String tenantId) {
        if (tenantId == null || !TENANT.matcher(tenantId).matches()) {
            throw new PortException.Business("INVALID_TENANT");
        }
        String query = "TenantId = '" + tenantId + "' AND WorkflowType = '" + WorkflowTypes.VIRTUAL_CARD + "'";
        Map<String, String> statuses = new HashMap<>();
        try (Stream<WorkflowExecutionMetadata> executions = client.listExecutions(query)) {
            executions.forEach(execution -> {
                SearchAttributes attributes = execution.getTypedSearchAttributes();
                String cardId = attributes.get(CardSearchAttributes.CARD_ID);
                if (cardId != null) {
                    statuses.put(cardId, attributes.get(CardSearchAttributes.CARD_STATUS));
                }
            });
        } catch (RuntimeException e) {
            throw new PortException.Technical("PLATFORM_RECORDS_UNAVAILABLE");
        }
        return statuses;
    }
}
