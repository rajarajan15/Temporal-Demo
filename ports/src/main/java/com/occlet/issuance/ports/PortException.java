package com.occlet.issuance.ports;

/** Port failures, classified once. Messages carry codes only, so nothing sensitive reaches history. */
public sealed class PortException extends RuntimeException {
    private final String code;

    private PortException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** The external system said no. Retrying cannot help. */
    public static final class Business extends PortException {
        public Business(String code) {
            super(code);
        }
    }

    /** The call failed and the work was not done, for example connection refused, 5xx or 429. */
    public static final class Technical extends PortException {
        public Technical(String code) {
            super(code);
        }
    }

    /** The outcome is unknown, for example a timeout. Safe to retry only with the same key. */
    public static final class Ambiguous extends PortException {
        public Ambiguous(String code) {
            super(code);
        }
    }
}
