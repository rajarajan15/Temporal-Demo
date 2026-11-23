package com.occlet.issuance.architecture;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds Luhn-valid 13 to 19 digit sequences, optionally separated by single spaces or dashes. */
final class CardNumberScanner {
    private static final Pattern CANDIDATE = Pattern.compile("(?<!\\d)\\d(?:[ -]?\\d){12,18}(?!\\d)");

    private CardNumberScanner() {}

    /** Returns masked matches, so a failing test never prints a full card number. */
    static List<String> findCardNumbers(String text) {
        List<String> found = new ArrayList<>();
        Matcher matcher = CANDIDATE.matcher(text);
        while (matcher.find()) {
            String digits = matcher.group().replaceAll("[ -]", "");
            if (passesLuhn(digits)) {
                found.add("*".repeat(digits.length() - 4) + digits.substring(digits.length() - 4));
            }
        }
        return found;
    }

    static boolean passesLuhn(String digits) {
        int sum = 0;
        boolean doubled = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int digit = digits.charAt(i) - '0';
            if (doubled) {
                digit = digit * 2 > 9 ? digit * 2 - 9 : digit * 2;
            }
            sum += digit;
            doubled = !doubled;
        }
        return sum % 10 == 0;
    }
}
