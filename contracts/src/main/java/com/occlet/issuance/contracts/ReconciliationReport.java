package com.occlet.issuance.contracts;

import java.util.List;

/** @param discrepancies the first discrepancies found, capped; discrepancyCount is the full total */
public record ReconciliationReport(
        String tenantId,
        String businessDate,
        int processorCards,
        int platformCards,
        int pagesRead,
        int discrepancyCount,
        List<Discrepancy> discrepancies) {}
