package com.occlet.issuance.api;

import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

final class ApiResponses {
    static final String TENANT_HEADER = "X-Tenant-Id";
    private static final Pattern REFERENCE = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    // Stops a card number being passed as a reference and entering workflow history.
    private static final Pattern LONG_DIGIT_RUN = Pattern.compile("\\d(?:[_-]?\\d){12,}");
    // Free text may separate card number groups with spaces or dots as well.
    private static final Pattern LONG_DIGIT_RUN_IN_TEXT = Pattern.compile("\\d(?:[\\s._-]?\\d){12,}");

    private ApiResponses() {}

    static boolean isSafeReference(String value) {
        return value != null && REFERENCE.matcher(value).matches() && !LONG_DIGIT_RUN.matcher(value).find();
    }

    /** Optional short text, such as a cancel reason. Refuses anything that could be a card number. */
    static boolean isSafeFreeText(String value) {
        return value == null || (value.length() <= 200 && !LONG_DIGIT_RUN_IN_TEXT.matcher(value).find());
    }

    static ResponseEntity<Map<String, String>> error(HttpStatus status, String code) {
        return ResponseEntity.status(status).body(Map.of("code", code));
    }

    static ResponseEntity<Map<String, String>> unavailable(String code) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "5").body(Map.of("code", code));
    }
}
