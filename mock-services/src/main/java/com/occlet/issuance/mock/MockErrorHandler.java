package com.occlet.issuance.mock;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class MockErrorHandler {
    @ExceptionHandler(MockException.class)
    ResponseEntity<Map<String, String>> handle(MockException e) {
        return ResponseEntity.status(e.status()).body(Map.of("code", e.code()));
    }
}
