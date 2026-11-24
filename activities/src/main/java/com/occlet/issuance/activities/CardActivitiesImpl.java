package com.occlet.issuance.activities;

import com.occlet.issuance.activities.api.CardActivities;
import com.occlet.issuance.activities.api.CardCommand;
import com.occlet.issuance.activities.api.LinkCommand;
import com.occlet.issuance.activities.api.NotificationCommand;
import com.occlet.issuance.activities.api.RequestScope;
import com.occlet.issuance.activities.api.SetLimitsCommand;
import com.occlet.issuance.contracts.CardRef;
import com.occlet.issuance.contracts.CardRequest;
import com.occlet.issuance.contracts.CardType;
import com.occlet.issuance.contracts.ErrorTypes;
import com.occlet.issuance.contracts.KycDecision;
import com.occlet.issuance.contracts.StepNames;
import com.occlet.issuance.ports.CardProcessorPort;
import com.occlet.issuance.ports.KycPort;
import com.occlet.issuance.ports.NotificationPort;
import io.temporal.failure.ApplicationFailure;
import java.util.Set;

/** Thin translation layer: build the key, call one port, map its failure. */
public class CardActivitiesImpl implements CardActivities {
    // Moves to per-tenant configuration in milestone 6.
    private static final Set<String> VIRTUAL_PRODUCTS = Set.of("VIRTUAL_STANDARD", "VIRTUAL_PREMIUM");
    private static final long MAX_LIMIT_MINOR = 1_000_000L;

    private final KycPort kyc;
    private final CardProcessorPort processor;
    private final NotificationPort notifications;

    public CardActivitiesImpl(KycPort kyc, CardProcessorPort processor, NotificationPort notifications) {
        this.kyc = kyc;
        this.processor = processor;
        this.notifications = notifications;
    }

    @Override
    public void validateRequest(CardRequest request) {
        if (request.cardType() != CardType.VIRTUAL) {
            throw business(ErrorTypes.INVALID_REQUEST);
        }
        if (!VIRTUAL_PRODUCTS.contains(request.productCode())) {
            throw business(ErrorTypes.INVALID_PRODUCT);
        }
        if (request.spendLimitMinor() <= 0 || request.spendLimitMinor() > MAX_LIMIT_MINOR) {
            throw business(ErrorTypes.LIMIT_EXCEEDS_POLICY);
        }
    }

    @Override
    public KycDecision runKyc(CardRequest request) {
        String key = IdempotencyKeys.of(scope(request), StepNames.RUN_KYC);
        return FailureMapper.call(() -> kyc.check(key, request.customerRef()));
    }

    @Override
    public CardRef createCard(CardRequest request) {
        String key = IdempotencyKeys.of(scope(request), StepNames.CREATE_CARD);
        return FailureMapper.call(() -> processor.createCard(key, request.productCode(), request.accountRef()));
    }

    @Override
    public void cancelCard(RequestScope scope) {
        String key = IdempotencyKeys.of(scope, StepNames.CANCEL_CARD);
        String creationKey = IdempotencyKeys.of(scope, StepNames.CREATE_CARD);
        FailureMapper.run(() -> processor.cancelByCreationKey(key, creationKey));
    }

    @Override
    public void setLimits(SetLimitsCommand command) {
        String key = IdempotencyKeys.of(command.scope(), StepNames.SET_LIMITS);
        FailureMapper.run(() -> processor.setLimits(key, command.cardId(), command.limitMinor()));
    }

    @Override
    public void resetLimits(CardCommand command) {
        String key = IdempotencyKeys.of(command.scope(), StepNames.RESET_LIMITS);
        FailureMapper.run(() -> processor.resetLimits(key, command.cardId()));
    }

    @Override
    public void activateCard(CardCommand command) {
        String key = IdempotencyKeys.of(command.scope(), StepNames.ACTIVATE_CARD);
        FailureMapper.run(() -> processor.activate(key, command.cardId()));
    }

    @Override
    public void deactivateCard(CardCommand command) {
        String key = IdempotencyKeys.of(command.scope(), StepNames.DEACTIVATE_CARD);
        FailureMapper.run(() -> processor.deactivate(key, command.cardId()));
    }

    @Override
    public void sendNotification(NotificationCommand command) {
        String key = IdempotencyKeys.of(command.scope(), StepNames.SEND_NOTIFICATION);
        FailureMapper.run(() -> notifications.send(key, command.template(), command.customerRef()));
    }

    @Override
    public void blockCard(CardCommand command) {
        String key = IdempotencyKeys.of(command.scope(), StepNames.BLOCK_OLD_CARD);
        FailureMapper.run(() -> processor.block(key, command.cardId()));
    }

    @Override
    public void linkReplacement(LinkCommand command) {
        String key = IdempotencyKeys.of(command.scope(), StepNames.LINK_CARDS);
        FailureMapper.run(() -> processor.linkReplacement(key, command.oldCardId(), command.newCardId()));
    }

    private static RequestScope scope(CardRequest request) {
        return new RequestScope(request.tenantId(), request.requestId());
    }

    private static ApplicationFailure business(String code) {
        return ApplicationFailure.newNonRetryableFailure(code, code);
    }
}
