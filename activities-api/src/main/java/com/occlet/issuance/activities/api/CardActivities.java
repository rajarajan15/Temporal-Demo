package com.occlet.issuance.activities.api;

import com.occlet.issuance.contracts.CardRef;
import com.occlet.issuance.contracts.CardRequest;
import com.occlet.issuance.contracts.KycDecision;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/** Shared activity library. Renaming a method is a breaking change for in-flight runs. */
@ActivityInterface
public interface CardActivities {
    @ActivityMethod
    void validateRequest(CardRequest request);

    @ActivityMethod
    KycDecision runKyc(CardRequest request);

    @ActivityMethod
    CardRef createCard(CardRequest request);

    @ActivityMethod
    void cancelCard(RequestScope scope);

    @ActivityMethod
    void setLimits(SetLimitsCommand command);

    @ActivityMethod
    void resetLimits(CardCommand command);

    @ActivityMethod
    void activateCard(CardCommand command);

    @ActivityMethod
    void deactivateCard(CardCommand command);

    @ActivityMethod
    void sendNotification(NotificationCommand command);

    @ActivityMethod
    void blockCard(CardCommand command);

    @ActivityMethod
    void linkReplacement(LinkCommand command);
}
