package com.occlet.issuance.workflows;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.occlet.issuance.contracts.CancelRequest;
import com.occlet.issuance.contracts.CardProgress;
import com.occlet.issuance.contracts.CardRequest;
import com.occlet.issuance.contracts.CardStatus;
import com.occlet.issuance.contracts.CardType;
import com.occlet.issuance.contracts.ErrorTypes;
import com.occlet.issuance.contracts.KycDecision;
import com.occlet.issuance.contracts.ProcessorEvent;
import com.occlet.issuance.contracts.ReviewAction;
import com.occlet.issuance.contracts.ReviewDecision;
import com.occlet.issuance.contracts.ReviewKind;
import com.occlet.issuance.contracts.SearchAttributeNames;
import com.occlet.issuance.contracts.StepNames;
import com.occlet.issuance.contracts.StepRecord;
import com.occlet.issuance.contracts.StepState;
import com.occlet.issuance.contracts.TenantSettings;
import com.occlet.issuance.contracts.WorkflowOutcome;
import com.occlet.issuance.engine.CardSearchAttributes;
import com.occlet.issuance.engine.RetryProfile;
import io.temporal.api.enums.v1.IndexedValueType;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.client.WorkflowUpdateException;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Runs against Temporal's in-memory test server. Timers and retry backoff are skipped, not waited. */
class VirtualCardWorkflowTest {
    private static final String QUEUE = "virtual-test";
    private static final CardRequest REQUEST = new CardRequest(
            "req-1", "bank-a", CardType.VIRTUAL, "VIRTUAL_STANDARD", "customer", "account", 50_000,
            new TenantSettings(10));
    private static final ProcessorEvent CONFIRMATION = new ProcessorEvent("evt-1", "card-1", ProcessorEvent.CARD_CREATED);

    private TestWorkflowEnvironment env;
    private FakeCardActivities activities;

    @BeforeEach
    void start() {
        env = TestWorkflowEnvironment.newInstance();
        SearchAttributeNames.ALL.forEach(name ->
                env.registerSearchAttribute(name, IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD));
        activities = new FakeCardActivities();
        Worker worker = env.newWorker(QUEUE);
        worker.registerWorkflowImplementationTypes(VirtualCardWorkflowImpl.class);
        worker.registerActivitiesImplementations(activities);
        env.start();
    }

    @AfterEach
    void stop() {
        env.close();
    }

    // ---------- happy path and saga ----------

    @Test
    void happyPathWithEarlyConfirmationRunsStepsInOrder() {
        WorkflowOutcome outcome = issueConfirmed();

        assertEquals(CardStatus.ACTIVE, outcome.status());
        assertEquals("card-1", outcome.card().cardId());
        assertEquals(List.of(StepNames.VALIDATE_REQUEST, StepNames.RUN_KYC, StepNames.CREATE_CARD,
                StepNames.SET_LIMITS, StepNames.ACTIVATE_CARD, StepNames.SEND_NOTIFICATION), activities.calls);
    }

    @Test
    void kycRejectionEndsWithoutCreatingCard() {
        activities.kyc = KycDecision.REJECTED;

        WorkflowOutcome outcome = issueConfirmed();

        assertEquals(CardStatus.REJECTED, outcome.status());
        assertEquals(ErrorTypes.KYC_REJECTED, outcome.reasonCode());
        assertFalse(activities.calls.contains(StepNames.CREATE_CARD));
    }

    @Test
    void businessFailureIsNotRetriedAndCompensatesInReverseOrder() {
        activities.business.put(StepNames.ACTIVATE_CARD, "ACTIVATION_DECLINED");

        WorkflowOutcome outcome = issueConfirmed();

        assertEquals(CardStatus.REJECTED, outcome.status());
        assertEquals("ACTIVATION_DECLINED", outcome.reasonCode());
        assertEquals(1, activities.count(StepNames.ACTIVATE_CARD));
        assertEquals(List.of(StepNames.DEACTIVATE_CARD, StepNames.RESET_LIMITS, StepNames.CANCEL_CARD),
                activities.callsAfter(StepNames.ACTIVATE_CARD));
    }

    @Test
    void transientFailureRecoversWithoutCompensation() {
        activities.technical.put(StepNames.CREATE_CARD, 2);

        WorkflowOutcome outcome = issueConfirmed();

        assertEquals(CardStatus.ACTIVE, outcome.status());
        assertEquals(3, activities.count(StepNames.CREATE_CARD));
        assertFalse(activities.calls.contains(StepNames.CANCEL_CARD));
    }

    @Test
    void failedNotificationNeverCompensates() {
        activities.business.put(StepNames.SEND_NOTIFICATION, "MAILBOX_FULL");

        WorkflowOutcome outcome = issueConfirmed();

        assertEquals(CardStatus.ACTIVE, outcome.status());
        assertFalse(activities.calls.contains(StepNames.DEACTIVATE_CARD));
    }

    @Test
    void failedCompensationEndsUnderReview() {
        activities.business.put(StepNames.ACTIVATE_CARD, "ACTIVATION_DECLINED");
        activities.business.put(StepNames.RESET_LIMITS, "RESET_REFUSED");

        WorkflowOutcome outcome = issueConfirmed();

        assertEquals(CardStatus.UNDER_REVIEW, outcome.status());
        assertEquals(ErrorTypes.COMPENSATION_FAILED, outcome.reasonCode());
        assertTrue(activities.calls.contains(StepNames.CANCEL_CARD));
    }

    // ---------- parking when retries run out ----------

    @Test
    void exhaustedRetriesParkThenOperatorRetrySucceeds() {
        activities.technical.put(StepNames.SET_LIMITS, RetryProfile.WRITE_IDEMPOTENT.maximumAttempts());
        VirtualCardWorkflow workflow = startConfirmed();

        CardProgress parked = awaitParked(workflow);
        assertEquals(ReviewKind.RETRIES_EXHAUSTED, parked.pendingReview().kind());
        assertEquals(StepNames.SET_LIMITS, parked.pendingReview().step());
        assertEquals(CardStatus.UNDER_REVIEW, parked.status());

        workflow.review(new ReviewDecision(ReviewAction.RETRY, "ops-1"));
        WorkflowOutcome outcome = result(workflow);

        assertEquals(CardStatus.ACTIVE, outcome.status());
        assertEquals(RetryProfile.WRITE_IDEMPOTENT.maximumAttempts() + 1, activities.count(StepNames.SET_LIMITS));
        assertFalse(activities.calls.contains(StepNames.CANCEL_CARD));
    }

    @Test
    void exhaustedRetriesThenOperatorCancelCompensates() {
        activities.technical.put(StepNames.CREATE_CARD, Integer.MAX_VALUE);
        VirtualCardWorkflow workflow = startConfirmed();
        awaitParked(workflow);

        workflow.review(new ReviewDecision(ReviewAction.CANCEL, "ops-1"));
        WorkflowOutcome outcome = result(workflow);

        assertEquals(CardStatus.CANCELLED, outcome.status());
        assertEquals(ErrorTypes.CANCELLED_BY_OPERATOR, outcome.reasonCode());
        // The create call's outcome is unknown, so its compensation still runs (amendment A3).
        assertEquals(1, activities.count(StepNames.CANCEL_CARD));
    }

    // ---------- processor confirmation: signal versus timer ----------

    @Test
    void confirmationTimeoutParksAndLateSignalClearsIt() {
        VirtualCardWorkflow workflow = startWithoutConfirmation();

        CardProgress parked = awaitParked(workflow);
        assertEquals(ReviewKind.CONFIRMATION_TIMEOUT, parked.pendingReview().kind());

        WorkflowStub.fromTyped(workflow).signal("processorEvent", CONFIRMATION);
        WorkflowOutcome outcome = result(workflow);

        assertEquals(CardStatus.ACTIVE, outcome.status());
    }

    @Test
    void confirmationTimeoutApprovedByOperatorContinues() {
        VirtualCardWorkflow workflow = startWithoutConfirmation();
        awaitParked(workflow);

        workflow.review(new ReviewDecision(ReviewAction.APPROVE, "ops-1"));

        assertEquals(CardStatus.ACTIVE, result(workflow).status());
    }

    @Test
    void confirmationTimeoutRejectedByOperatorCompensates() {
        VirtualCardWorkflow workflow = startWithoutConfirmation();
        awaitParked(workflow);

        workflow.review(new ReviewDecision(ReviewAction.REJECT, "ops-1"));
        WorkflowOutcome outcome = result(workflow);

        assertEquals(CardStatus.REJECTED, outcome.status());
        assertEquals(ErrorTypes.REJECTED_BY_REVIEWER, outcome.reasonCode());
        assertEquals(1, activities.count(StepNames.CANCEL_CARD));
    }

    @Test
    void confirmationForAnotherCardIsIgnored() {
        VirtualCardWorkflow workflow = startWithoutConfirmation();
        WorkflowStub.fromTyped(workflow).signal("processorEvent", new ProcessorEvent("evt-x", "card-other", ProcessorEvent.CARD_CREATED));

        assertEquals(ReviewKind.CONFIRMATION_TIMEOUT, awaitParked(workflow).pendingReview().kind());
    }

    @Test
    void duplicateConfirmationIsProcessedOnce() {
        VirtualCardWorkflow workflow = startWithoutConfirmation();
        WorkflowStub stub = WorkflowStub.fromTyped(workflow);
        stub.signal("processorEvent", CONFIRMATION);
        stub.signal("processorEvent", CONFIRMATION);

        assertEquals(CardStatus.ACTIVE, result(workflow).status());
    }

    // ---------- KYC manual review ----------

    @Test
    void kycReviewApprovedContinues() {
        activities.kyc = KycDecision.NEEDS_REVIEW;
        VirtualCardWorkflow workflow = startConfirmed();
        assertEquals(ReviewKind.KYC_REVIEW, awaitParked(workflow).pendingReview().kind());

        workflow.review(new ReviewDecision(ReviewAction.APPROVE, "ops-1"));

        assertEquals(CardStatus.ACTIVE, result(workflow).status());
    }

    @Test
    void kycReviewRejectedEndsWithoutCard() {
        activities.kyc = KycDecision.NEEDS_REVIEW;
        VirtualCardWorkflow workflow = startConfirmed();
        awaitParked(workflow);

        workflow.review(new ReviewDecision(ReviewAction.REJECT, "ops-1"));
        WorkflowOutcome outcome = result(workflow);

        assertEquals(CardStatus.REJECTED, outcome.status());
        assertEquals(ErrorTypes.REJECTED_BY_REVIEWER, outcome.reasonCode());
        assertFalse(activities.calls.contains(StepNames.CREATE_CARD));
    }

    // ---------- update validators ----------

    @Test
    void reviewIsRejectedWhenNothingIsPending() {
        VirtualCardWorkflow workflow = startWithoutConfirmation();

        assertEquals(ErrorTypes.NO_REVIEW_PENDING,
                rejectionCode(() -> workflow.review(new ReviewDecision(ReviewAction.APPROVE, "ops-1"))));
    }

    @Test
    void reviewActionMustSuitTheReviewKind() {
        VirtualCardWorkflow workflow = startWithoutConfirmation();
        awaitParked(workflow);

        assertEquals(ErrorTypes.ACTION_NOT_ALLOWED,
                rejectionCode(() -> workflow.review(new ReviewDecision(ReviewAction.RETRY, "ops-1"))));
    }

    // ---------- cancellation ----------

    @Test
    void cancelBeforeCardCreationNeedsNoCompensation() {
        activities.kyc = KycDecision.NEEDS_REVIEW;
        VirtualCardWorkflow workflow = startConfirmed();
        awaitParked(workflow);

        assertTrue(workflow.cancel(new CancelRequest(null)).accepted());
        WorkflowOutcome outcome = result(workflow);

        assertEquals(CardStatus.CANCELLED, outcome.status());
        assertEquals(ErrorTypes.CANCELLED_BY_REQUEST, outcome.reasonCode());
        assertFalse(activities.calls.contains(StepNames.CREATE_CARD));
        assertFalse(activities.calls.contains(StepNames.CANCEL_CARD));
    }

    @Test
    void cancelWhileAwaitingConfirmationCompensates() throws InterruptedException {
        VirtualCardWorkflow workflow = startWithoutConfirmation();
        long deadline = System.currentTimeMillis() + 10_000;
        while (stateOrNull(workflow.progress(), StepNames.AWAIT_CONFIRMATION) != StepState.WAITING
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }

        assertTrue(workflow.cancel(new CancelRequest("customer asked")).accepted());
        WorkflowOutcome outcome = result(workflow);

        assertEquals(CardStatus.CANCELLED, outcome.status());
        assertEquals(ErrorTypes.CANCELLED_BY_REQUEST, outcome.reasonCode());
        assertEquals(1, activities.count(StepNames.CANCEL_CARD));
        assertFalse(activities.calls.contains(StepNames.SET_LIMITS));
    }

    @Test
    void cancelIsRefusedOnceCardIsActive() throws InterruptedException {
        // The notification keeps retrying, which holds the workflow open after activation.
        activities.technical.put(StepNames.SEND_NOTIFICATION, Integer.MAX_VALUE);
        VirtualCardWorkflow workflow = startConfirmed();
        long deadline = System.currentTimeMillis() + 10_000;
        while (workflow.progress().status() != CardStatus.ACTIVE && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }

        assertEquals(ErrorTypes.CANNOT_CANCEL, rejectionCode(() -> workflow.cancel(new CancelRequest(null))));
    }

    @Test
    void progressQueryShowsFailureAndCompensationEntries() {
        activities.business.put(StepNames.ACTIVATE_CARD, "ACTIVATION_DECLINED");
        VirtualCardWorkflow workflow = startConfirmed();
        result(workflow);

        CardProgress progress = workflow.progress();

        assertEquals(CardStatus.REJECTED, progress.status());
        assertTrue(progress.finished());
        assertEquals(StepState.FAILED, state(progress, StepNames.ACTIVATE_CARD));
        assertEquals(StepState.DONE, state(progress, StepNames.AWAIT_CONFIRMATION));
        assertEquals(StepState.COMPENSATED, state(progress, StepNames.CANCEL_CARD));
        assertEquals(StepState.PENDING, state(progress, StepNames.SEND_NOTIFICATION));
    }

    // ---------- helpers ----------

    private WorkflowOutcome issueConfirmed() {
        return result(startConfirmed());
    }

    /** Sends the confirmation straight after starting, before the card exists, to prove early signals are kept. */
    private VirtualCardWorkflow startConfirmed() {
        VirtualCardWorkflow workflow = startWithoutConfirmation();
        WorkflowStub.fromTyped(workflow).signal("processorEvent", CONFIRMATION);
        return workflow;
    }

    private VirtualCardWorkflow startWithoutConfirmation() {
        VirtualCardWorkflow workflow = env.getWorkflowClient().newWorkflowStub(VirtualCardWorkflow.class, WorkflowOptions.newBuilder()
                .setWorkflowId("card-test")
                .setTaskQueue(QUEUE)
                .setTypedSearchAttributes(CardSearchAttributes.initial(REQUEST))
                .build());
        WorkflowClient.start(workflow::issue, REQUEST);
        return workflow;
    }

    private static WorkflowOutcome result(VirtualCardWorkflow workflow) {
        return WorkflowStub.fromTyped(workflow).getResult(WorkflowOutcome.class);
    }

    /** Advances test time until the workflow parks for review. */
    private CardProgress awaitParked(VirtualCardWorkflow workflow) {
        for (int i = 0; i < 120; i++) {
            CardProgress progress = workflow.progress();
            if (progress.pendingReview() != null) {
                return progress;
            }
            env.sleep(Duration.ofSeconds(5));
        }
        throw new AssertionError("Workflow never parked: " + workflow.progress());
    }

    private static String rejectionCode(Runnable update) {
        WorkflowUpdateException rejected = assertThrows(WorkflowUpdateException.class, update::run);
        ApplicationFailure cause = assertInstanceOf(ApplicationFailure.class, rejected.getCause());
        assertNotNull(cause.getType());
        return cause.getType();
    }

    private static StepState stateOrNull(CardProgress progress, String name) {
        return progress.steps().stream().filter(s -> s.name().equals(name)).map(StepRecord::state).findFirst().orElse(null);
    }

    private static StepState state(CardProgress progress, String name) {
        return progress.steps().stream().filter(s -> s.name().equals(name)).map(StepRecord::state).findFirst().orElseThrow();
    }
}
