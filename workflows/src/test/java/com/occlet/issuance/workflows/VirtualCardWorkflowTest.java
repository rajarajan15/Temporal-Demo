package com.occlet.issuance.workflows;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.occlet.issuance.activities.api.CardActivities;
import com.occlet.issuance.activities.api.CardCommand;
import com.occlet.issuance.activities.api.NotificationCommand;
import com.occlet.issuance.activities.api.RequestScope;
import com.occlet.issuance.activities.api.SetLimitsCommand;
import com.occlet.issuance.contracts.CardProgress;
import com.occlet.issuance.contracts.CardRef;
import com.occlet.issuance.contracts.CardRequest;
import com.occlet.issuance.contracts.CardStatus;
import com.occlet.issuance.contracts.CardType;
import com.occlet.issuance.contracts.ErrorTypes;
import com.occlet.issuance.contracts.KycDecision;
import com.occlet.issuance.contracts.SearchAttributeNames;
import com.occlet.issuance.contracts.StepNames;
import com.occlet.issuance.contracts.StepRecord;
import com.occlet.issuance.contracts.StepState;
import com.occlet.issuance.contracts.WorkflowOutcome;
import com.occlet.issuance.engine.CardSearchAttributes;
import com.occlet.issuance.engine.RetryProfile;
import io.temporal.api.enums.v1.IndexedValueType;
import io.temporal.client.WorkflowOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VirtualCardWorkflowTest {
    private static final String QUEUE = "virtual-test";
    private static final CardRequest REQUEST = new CardRequest(
            "req-1", "bank-a", CardType.VIRTUAL, "VIRTUAL_STANDARD", "customer", "account", 50_000);

    private TestWorkflowEnvironment env;
    private FakeActivities activities;

    @BeforeEach
    void start() {
        env = TestWorkflowEnvironment.newInstance();
        SearchAttributeNames.ALL.forEach(name ->
                env.registerSearchAttribute(name, IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD));
        activities = new FakeActivities();
        Worker worker = env.newWorker(QUEUE);
        worker.registerWorkflowImplementationTypes(VirtualCardWorkflowImpl.class);
        worker.registerActivitiesImplementations(activities);
        env.start();
    }

    @AfterEach
    void stop() {
        env.close();
    }

    @Test
    void happyPathRunsStepsInOrderAndEndsActive() {
        WorkflowOutcome outcome = issue();

        assertEquals(CardStatus.ACTIVE, outcome.status());
        assertEquals("card-1", outcome.card().cardId());
        assertEquals(List.of(StepNames.VALIDATE_REQUEST, StepNames.RUN_KYC, StepNames.CREATE_CARD,
                StepNames.SET_LIMITS, StepNames.ACTIVATE_CARD, StepNames.SEND_NOTIFICATION), activities.calls);
    }

    @Test
    void kycRejectionEndsWithoutCreatingCard() {
        activities.kyc = KycDecision.REJECTED;

        WorkflowOutcome outcome = issue();

        assertEquals(CardStatus.REJECTED, outcome.status());
        assertEquals(ErrorTypes.KYC_REJECTED, outcome.reasonCode());
        assertFalse(activities.calls.contains(StepNames.CREATE_CARD));
        assertFalse(activities.calls.contains(StepNames.CANCEL_CARD));
    }

    @Test
    void businessFailureIsNotRetriedAndCompensatesInReverseOrder() {
        activities.business.put(StepNames.ACTIVATE_CARD, "ACTIVATION_DECLINED");

        WorkflowOutcome outcome = issue();

        assertEquals(CardStatus.REJECTED, outcome.status());
        assertEquals("ACTIVATION_DECLINED", outcome.reasonCode());
        assertEquals(1, activities.count(StepNames.ACTIVATE_CARD));
        assertEquals(List.of(StepNames.DEACTIVATE_CARD, StepNames.RESET_LIMITS, StepNames.CANCEL_CARD),
                activities.callsAfter(StepNames.ACTIVATE_CARD));
    }

    @Test
    void technicalFailureRetriesToBudgetThenCompensates() {
        activities.technical.put(StepNames.SET_LIMITS, Integer.MAX_VALUE);

        WorkflowOutcome outcome = issue();

        assertEquals(CardStatus.CANCELLED, outcome.status());
        assertEquals(ErrorTypes.RETRIES_EXHAUSTED, outcome.reasonCode());
        assertEquals(RetryProfile.WRITE_IDEMPOTENT.maximumAttempts(), activities.count(StepNames.SET_LIMITS));
        assertEquals(1, activities.count(StepNames.CANCEL_CARD));
        assertFalse(activities.calls.contains(StepNames.ACTIVATE_CARD));
    }

    @Test
    void transientFailureRecoversWithoutCompensation() {
        activities.technical.put(StepNames.CREATE_CARD, 2);

        WorkflowOutcome outcome = issue();

        assertEquals(CardStatus.ACTIVE, outcome.status());
        assertEquals(3, activities.count(StepNames.CREATE_CARD));
        assertFalse(activities.calls.contains(StepNames.CANCEL_CARD));
    }

    @Test
    void failedCreateStillRunsCancelBecauseOutcomeMayBeUnknown() {
        activities.technical.put(StepNames.CREATE_CARD, Integer.MAX_VALUE);

        WorkflowOutcome outcome = issue();

        assertEquals(CardStatus.CANCELLED, outcome.status());
        assertEquals(1, activities.count(StepNames.CANCEL_CARD));
    }

    @Test
    void failedNotificationNeverCompensates() {
        activities.business.put(StepNames.SEND_NOTIFICATION, "MAILBOX_FULL");

        WorkflowOutcome outcome = issue();

        assertEquals(CardStatus.ACTIVE, outcome.status());
        assertFalse(activities.calls.contains(StepNames.DEACTIVATE_CARD));
        assertFalse(activities.calls.contains(StepNames.CANCEL_CARD));
    }

    @Test
    void failedCompensationContinuesAndParksForReview() {
        activities.business.put(StepNames.ACTIVATE_CARD, "ACTIVATION_DECLINED");
        activities.business.put(StepNames.RESET_LIMITS, "RESET_REFUSED");

        WorkflowOutcome outcome = issue();

        assertEquals(CardStatus.UNDER_REVIEW, outcome.status());
        assertEquals(ErrorTypes.COMPENSATION_FAILED, outcome.reasonCode());
        assertTrue(activities.calls.contains(StepNames.CANCEL_CARD));
    }

    @Test
    void progressQueryShowsFailureAndCompensationEntries() {
        activities.business.put(StepNames.ACTIVATE_CARD, "ACTIVATION_DECLINED");
        VirtualCardWorkflow workflow = newWorkflow();
        workflow.issue(REQUEST);

        CardProgress progress = workflow.progress();

        assertEquals(CardStatus.REJECTED, progress.status());
        assertEquals(StepState.FAILED, state(progress, StepNames.ACTIVATE_CARD));
        assertEquals(StepState.COMPENSATED, state(progress, StepNames.CANCEL_CARD));
        assertEquals(StepState.PENDING, state(progress, StepNames.SEND_NOTIFICATION));
    }

    private WorkflowOutcome issue() {
        return newWorkflow().issue(REQUEST);
    }

    private VirtualCardWorkflow newWorkflow() {
        return env.getWorkflowClient().newWorkflowStub(VirtualCardWorkflow.class, WorkflowOptions.newBuilder()
                .setWorkflowId("card-test")
                .setTaskQueue(QUEUE)
                .setTypedSearchAttributes(CardSearchAttributes.initial(REQUEST))
                .build());
    }

    private static StepState state(CardProgress progress, String name) {
        return progress.steps().stream().filter(s -> s.name().equals(name)).map(StepRecord::state).findFirst().orElseThrow();
    }

    private static final class FakeActivities implements CardActivities {
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        final Map<String, String> business = new HashMap<>();
        final Map<String, Integer> technical = new HashMap<>();
        volatile KycDecision kyc = KycDecision.APPROVED;

        int count(String step) {
            return Collections.frequency(calls, step);
        }

        List<String> callsAfter(String step) {
            return List.copyOf(calls.subList(calls.lastIndexOf(step) + 1, calls.size()));
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
        @Override public CardRef createCard(CardRequest request) { record(StepNames.CREATE_CARD); return new CardRef("card-1", "token-1"); }
        @Override public void cancelCard(RequestScope scope) { record(StepNames.CANCEL_CARD); }
        @Override public void setLimits(SetLimitsCommand command) { record(StepNames.SET_LIMITS); }
        @Override public void resetLimits(CardCommand command) { record(StepNames.RESET_LIMITS); }
        @Override public void activateCard(CardCommand command) { record(StepNames.ACTIVATE_CARD); }
        @Override public void deactivateCard(CardCommand command) { record(StepNames.DEACTIVATE_CARD); }
        @Override public void sendNotification(NotificationCommand command) { record(StepNames.SEND_NOTIFICATION); }
    }
}
