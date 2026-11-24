package com.occlet.issuance.activities;

import com.occlet.issuance.contracts.ErrorTypes;
import com.occlet.issuance.ports.PortException;
import io.temporal.failure.ApplicationFailure;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** The single place where a port failure becomes a Temporal failure. */
final class FailureMapper {
    private static final Pattern SAFE_CODE = Pattern.compile("[A-Z][A-Z_]{0,63}");

    private FailureMapper() {}

    static <T> T call(Supplier<T> portCall) {
        try {
            return portCall.get();
        } catch (PortException.Business e) {
            String code = safeCode(e.code());
            throw ApplicationFailure.newNonRetryableFailure(code, code);
        } catch (PortException.Ambiguous e) {
            throw ApplicationFailure.newFailure(ErrorTypes.AMBIGUOUS, ErrorTypes.AMBIGUOUS);
        } catch (PortException.Technical e) {
            throw ApplicationFailure.newFailure(ErrorTypes.TECHNICAL, ErrorTypes.TECHNICAL);
        }
    }

    static void run(Runnable portCall) {
        call(() -> {
            portCall.run();
            return null;
        });
    }

    /** External codes are untrusted and end up in history, so anything unexpected is replaced. */
    private static String safeCode(String code) {
        return code != null && SAFE_CODE.matcher(code).matches() ? code : ErrorTypes.UNKNOWN_BUSINESS_ERROR;
    }
}
