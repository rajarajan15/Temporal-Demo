package com.occlet.issuance.mock;

import org.springframework.http.HttpStatus;

public class MockException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public MockException(HttpStatus status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
