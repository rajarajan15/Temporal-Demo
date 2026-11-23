package com.occlet.issuance.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class CardNumberScannerTest {
    @Test
    void findsAndMasksPlainCardNumber() {
        assertEquals(List.of("************1111"),
                CardNumberScanner.findCardNumbers("pan=4111111111111111;"));
    }

    @Test
    void findsSeparatedCardNumber() {
        assertEquals(1, CardNumberScanner.findCardNumbers("4111 1111-1111 1111").size());
    }

    @Test
    void ignoresMillisecondTimestamp() {
        assertTrue(CardNumberScanner.findCardNumbers("startedAt=1700000000000").isEmpty());
    }

    @Test
    void ignoresShortAndOverlongDigitRuns() {
        assertTrue(CardNumberScanner.findCardNumbers("411111111111").isEmpty());
        assertTrue(CardNumberScanner.findCardNumbers("41111111111111111111").isEmpty());
    }
}
