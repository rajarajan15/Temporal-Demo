package com.occlet.issuance.workflows;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.occlet.issuance.contracts.CardRequest;
import com.occlet.issuance.contracts.CardStatus;
import com.occlet.issuance.contracts.CardType;
import com.occlet.issuance.contracts.ErrorTypes;
import com.occlet.issuance.contracts.StepState;
import com.occlet.issuance.contracts.ProcessorEvent;
import com.occlet.issuance.contracts.ReissueReason;
import com.occlet.issuance.contracts.ReissueRequest;
import com.occlet.issuance.contracts.SearchAttributeNames;
import com.occlet.issuance.contracts.StepNames;
import com.occlet.issuance.contracts.TenantSettings;
import com.occlet.issuance.contracts.WorkflowOutcome;
import io.temporal.api.enums.v1.IndexedValueType;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReissueWorkflowTest {
    private static final String QUEUE = "reissue-test";

    private TestWorkflowEnvironment env;
    private FakeCardActivities activities;

    @BeforeEach
    void start() {
        env = TestWorkflowEnvironment.newInstance();
        SearchAttributeNames.ALL.forEach(name ->
                env.registerSearchAttribute(name, IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD));
        activities = new FakeCardActivities();
        activities.cardId = "card-new";
        Worker worker = env.newWorker(QUEUE);
        worker.registerWorkflowImplementationTypes(ReissueWorkflowImpl.class, VirtualCardWorkflowImpl.class);
        worker.registerActivitiesImplementations(activities);
        env.start();
    }

    @AfterEach
    void stop() {
        env.close();
    }

    @Test
    void stolenCardIsBlockedBeforeReplacementAndThenLinked() {
        WorkflowOutcome outcome = reissue(ReissueReason.STOLEN);

        assertEquals(CardStatus.ACTIVE, outcome.status());
        assertEquals("card-new", outcome.card().cardId());
        List<String> calls = List.copyOf(activities.calls);
        assertEquals(StepNames.BLOCK_OLD_CARD, calls.get(0));
        assertEquals(StepNames.LINK_CARDS, calls.get(calls.size() - 1));
        assertTrue(calls.indexOf(StepNames.BLOCK_OLD_CARD) < calls.indexOf(StepNames.CREATE_CARD));
    }

    @Test
    void damagedCardIsNotBlocked() {
        WorkflowOutcome outcome = reissue(ReissueReason.DAMAGED);

        assertEquals(CardStatus.ACTIVE, outcome.status());
        assertFalse(activities.calls.contains(StepNames.BLOCK_OLD_CARD));
        assertTrue(activities.calls.contains(StepNames.LINK_CARDS));
    }

    @Test
    void unknownOldCardIsRejectedWithoutIssuing() {
        activities.business.put(StepNames.BLOCK_OLD_CARD, "CARD_NOT_FOUND");

        WorkflowOutcome outcome = reissue(ReissueReason.LOST);

        assertEquals(CardStatus.REJECTED, outcome.status());
        assertEquals("CARD_NOT_FOUND", outcome.reasonCode());
        assertFalse(activities.calls.contains(StepNames.CREATE_CARD));
    }

    @Test
    void replacementThatCannotStartParksForReview() {
        // Another request already uses the replacement's workflow ID.
        CardRequest clash = new CardRequest("r1-new", "bank-a", CardType.VIRTUAL, "VIRTUAL_STANDARD",
                "customer", "account", 50_000, new TenantSettings(600));
        VirtualCardWorkflow existing = env.getWorkflowClient().newWorkflowStub(VirtualCardWorkflow.class,
                WorkflowOptions.newBuilder().setWorkflowId("card-bank-a-r1-new").setTaskQueue(QUEUE).build());
        WorkflowClient.start(existing::issue, clash);

        ReissueWorkflow workflow = env.getWorkflowClient().newWorkflowStub(ReissueWorkflow.class,
                WorkflowOptions.newBuilder().setWorkflowId("reissue-clash").setTaskQueue(QUEUE).build());
        WorkflowOutcome outcome = workflow.reissue(new ReissueRequest("r1", "bank-a", "card-old", ReissueReason.STOLEN,
                new CardRequest("r1-new", "bank-a", CardType.VIRTUAL, "VIRTUAL_STANDARD", "customer", "account", 50_000)));

        assertEquals(CardStatus.UNDER_REVIEW, outcome.status());
        assertEquals(ErrorTypes.REPLACEMENT_FAILED, outcome.reasonCode());
        assertEquals(StepState.FAILED, workflow.progress().steps().stream()
                .filter(s -> s.name().equals(StepNames.ISSUE_NEW_CARD)).findFirst().orElseThrow().state());
    }

    private WorkflowOutcome reissue(ReissueReason reason) {
        CardRequest newCard = new CardRequest("r1-new", "bank-a", CardType.VIRTUAL, "VIRTUAL_STANDARD",
                "customer", "account", 50_000, new TenantSettings(10));
        ReissueWorkflow workflow = env.getWorkflowClient().newWorkflowStub(ReissueWorkflow.class,
                WorkflowOptions.newBuilder().setWorkflowId("reissue-test").setTaskQueue(QUEUE).build());
        WorkflowClient.start(workflow::reissue, new ReissueRequest("r1", "bank-a", "card-old", reason, newCard));
        confirmChildWhenStarted();
        return WorkflowStub.fromTyped(workflow).getResult(WorkflowOutcome.class);
    }

    /** The child waits for the processor callback, so deliver it once the child exists. */
    private void confirmChildWhenStarted() {
        for (int i = 0; i < 50; i++) {
            try {
                env.getWorkflowClient().newUntypedWorkflowStub("card-bank-a-r1-new")
                        .signal("processorEvent", new ProcessorEvent("evt-1", "card-new", ProcessorEvent.CARD_CREATED));
                return;
            } catch (WorkflowNotFoundException notYet) {
                env.sleep(Duration.ofMillis(200));
            }
        }
        // The child never started, for example because blocking the old card failed.
    }
}
