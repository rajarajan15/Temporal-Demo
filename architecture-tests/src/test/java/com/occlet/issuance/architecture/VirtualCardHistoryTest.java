package com.occlet.issuance.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.occlet.issuance.activities.CardActivitiesImpl;
import com.occlet.issuance.contracts.CardRef;
import com.occlet.issuance.contracts.CardRequest;
import com.occlet.issuance.contracts.CardStatus;
import com.occlet.issuance.contracts.CardType;
import com.occlet.issuance.contracts.KycDecision;
import com.occlet.issuance.contracts.SearchAttributeNames;
import com.occlet.issuance.contracts.WorkflowOutcome;
import com.occlet.issuance.engine.CardSearchAttributes;
import com.occlet.issuance.ports.CardProcessorPort;
import com.occlet.issuance.ports.PortException;
import com.occlet.issuance.workflows.VirtualCardWorkflow;
import com.occlet.issuance.workflows.VirtualCardWorkflowImpl;
import io.temporal.api.enums.v1.IndexedValueType;
import com.occlet.issuance.contracts.ProcessorEvent;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import java.time.Duration;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Happy and saga paths must leave no card number in history and must replay. */
class VirtualCardHistoryTest {
    private static final String QUEUE = "history-test";

    private TestWorkflowEnvironment env;
    private volatile boolean failActivation;

    @BeforeEach
    void start() {
        env = TestWorkflowEnvironment.newInstance();
        SearchAttributeNames.ALL.forEach(name ->
                env.registerSearchAttribute(name, IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD));
        Worker worker = env.newWorker(QUEUE);
        worker.registerWorkflowImplementationTypes(VirtualCardWorkflowImpl.class);
        worker.registerActivitiesImplementations(new CardActivitiesImpl(
                (key, customer) -> KycDecision.APPROVED, new Processor(), (key, template, customer) -> { }));
        env.start();
    }

    @AfterEach
    void stop() {
        env.close();
    }

    @Test
    void happyPathHistoryIsCleanAndReplays() throws Exception {
        assertEquals(CardStatus.ACTIVE, issue("happy").status());
        verify("happy");
    }

    @Test
    void sagaHistoryIsCleanAndReplays() throws Exception {
        failActivation = true;
        assertEquals(CardStatus.REJECTED, issue("saga").status());
        verify("saga");
    }

    /** Timer fires, the request parks, then a late callback clears it: timers and signals must replay too. */
    @Test
    void parkedThenLateCallbackHistoryReplays() throws Exception {
        VirtualCardWorkflow workflow = start("late");
        for (int i = 0; i < 60 && workflow.progress().pendingReview() == null; i++) {
            env.sleep(Duration.ofSeconds(5));
        }
        WorkflowStub stub = WorkflowStub.fromTyped(workflow);
        stub.signal("processorEvent", new ProcessorEvent("evt-late", "card_ABC", ProcessorEvent.CARD_CREATED));
        assertEquals(CardStatus.ACTIVE, stub.getResult(WorkflowOutcome.class).status());
        verify("late");
    }

    private void verify(String id) throws Exception {
        WorkflowExecutionHistory history = env.getWorkflowClient().fetchHistory(id);
        assertTrue(CardNumberScanner.findCardNumbers(history.getHistory().toString()).isEmpty());
        WorkflowReplayer.replayWorkflowExecution(history, VirtualCardWorkflowImpl.class);
    }

    private WorkflowOutcome issue(String id) {
        VirtualCardWorkflow workflow = start(id);
        WorkflowStub stub = WorkflowStub.fromTyped(workflow);
        stub.signal("processorEvent", new ProcessorEvent("evt-" + id, "card_ABC", ProcessorEvent.CARD_CREATED));
        return stub.getResult(WorkflowOutcome.class);
    }

    private VirtualCardWorkflow start(String id) {
        CardRequest request = new CardRequest(id, "bank-a", CardType.VIRTUAL, "VIRTUAL_STANDARD", "customer", "account", 50_000);
        VirtualCardWorkflow workflow = env.getWorkflowClient().newWorkflowStub(VirtualCardWorkflow.class, WorkflowOptions.newBuilder()
                .setWorkflowId(id)
                .setTaskQueue(QUEUE)
                .setTypedSearchAttributes(CardSearchAttributes.initial(request))
                .build());
        WorkflowClient.start(workflow::issue, request);
        return workflow;
    }

    private final class Processor implements CardProcessorPort {
        @Override public CardRef createCard(String key, String productCode, String accountRef) { return new CardRef("card_ABC", "tok_ABC"); }
        @Override public void cancelByCreationKey(String key, String creationKey) { }
        @Override public void setLimits(String key, String cardId, long limitMinor) { }
        @Override public void resetLimits(String key, String cardId) { }
        @Override public void activate(String key, String cardId) {
            if (failActivation) {
                throw new PortException.Business("ACTIVATION_DECLINED");
            }
        }
        @Override public void deactivate(String key, String cardId) { }
        @Override public void block(String key, String cardId) { }
        @Override public void linkReplacement(String key, String oldCardId, String newCardId) { }
        @Override public com.occlet.issuance.ports.CardPage listCards(String tenantId, String after, int pageSize) { return new com.occlet.issuance.ports.CardPage(java.util.List.of(), null); }
    }
}
