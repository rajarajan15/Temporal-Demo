package com.occlet.issuance.workflows;

import com.occlet.issuance.activities.api.CardActivities;
import com.occlet.issuance.activities.api.CardCommand;
import com.occlet.issuance.activities.api.LinkCommand;
import com.occlet.issuance.activities.api.NotificationCommand;
import com.occlet.issuance.activities.api.RequestScope;
import com.occlet.issuance.activities.api.SetLimitsCommand;
import com.occlet.issuance.contracts.CardRef;
import com.occlet.issuance.contracts.CardRequest;
import com.occlet.issuance.contracts.ErrorTypes;
import com.occlet.issuance.contracts.KycDecision;
import com.occlet.issuance.contracts.StepNames;
import io.temporal.failure.ApplicationFailure;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Records each activity call and fails on demand: business (not retried) or technical (retried). */
final class FakeCardActivities implements CardActivities {
    final List<String> calls = Collections.synchronizedList(new ArrayList<>());
    final Map<String, String> business = new ConcurrentHashMap<>();
    final Map<String, Integer> technical = new ConcurrentHashMap<>();
    volatile KycDecision kyc = KycDecision.APPROVED;
    volatile String cardId = "card-1";

    int count(String step) {
        return Collections.frequency(List.copyOf(calls), step);
    }

    List<String> callsAfter(String step) {
        List<String> copy = List.copyOf(calls);
        return copy.subList(copy.lastIndexOf(step) + 1, copy.size());
    }

    private void record(String step) {
        calls.add(step);
        if (business.containsKey(step)) {
            throw ApplicationFailure.newNonRetryableFailure(business.get(step), business.get(step));
        }
        int failuresLeft = technical.getOrDefault(step, 0);
        if (failuresLeft > 0) {
            technical.put(step, failuresLeft - 1);
            throw ApplicationFailure.newFailure(ErrorTypes.TECHNICAL, ErrorTypes.TECHNICAL);
        }
    }

    @Override public void validateRequest(CardRequest request) { record(StepNames.VALIDATE_REQUEST); }
    @Override public KycDecision runKyc(CardRequest request) { record(StepNames.RUN_KYC); return kyc; }
    @Override public CardRef createCard(CardRequest request) { record(StepNames.CREATE_CARD); return new CardRef(cardId, "token-1"); }
    @Override public void cancelCard(RequestScope scope) { record(StepNames.CANCEL_CARD); }
    @Override public void setLimits(SetLimitsCommand command) { record(StepNames.SET_LIMITS); }
    @Override public void resetLimits(CardCommand command) { record(StepNames.RESET_LIMITS); }
    @Override public void activateCard(CardCommand command) { record(StepNames.ACTIVATE_CARD); }
    @Override public void deactivateCard(CardCommand command) { record(StepNames.DEACTIVATE_CARD); }
    @Override public void sendNotification(NotificationCommand command) { record(StepNames.SEND_NOTIFICATION); }
    @Override public void blockCard(CardCommand command) { record(StepNames.BLOCK_OLD_CARD); }
    @Override public void linkReplacement(LinkCommand command) { record(StepNames.LINK_CARDS); }
}
