package com.occlet.issuance.api;

import io.temporal.client.WorkflowServiceException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class ApiErrorHandler {
    @ExceptionHandler(WorkflowServiceException.class)
    ResponseEntity<Map<String, String>> temporalUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "5")
                .body(Map.of("code", "ENGINE_UNAVAILABLE"));
    }
}
