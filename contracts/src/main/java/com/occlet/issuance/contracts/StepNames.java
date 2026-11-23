package com.occlet.issuance.contracts;

/** Step names. They form part of idempotency keys, so renaming one is a breaking change. */
public final class StepNames {
    public static final String VALIDATE_REQUEST = "validateRequest";
    public static final String RUN_KYC = "runKyc";
    public static final String CREATE_CARD = "createCard";
    public static final String CANCEL_CARD = "cancelCard";
    public static final String SET_LIMITS = "setLimits";
    public static final String RESET_LIMITS = "resetLimits";
    public static final String ACTIVATE_CARD = "activateCard";
    public static final String DEACTIVATE_CARD = "deactivateCard";
    public static final String SEND_NOTIFICATION = "sendNotification";

    private StepNames() {}
}
