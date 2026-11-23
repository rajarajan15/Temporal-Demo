package com.occlet.issuance.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.occlet.issuance.activities.HelloActivitiesImpl;
import com.occlet.issuance.contracts.HelloRequest;
import com.occlet.issuance.contracts.HelloResult;
import com.occlet.issuance.workflows.HelloWorkflow;
import com.occlet.issuance.workflows.HelloWorkflowImpl;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorkflowHistoryTest {
    private static final String QUEUE = "test-queue";

    private TestWorkflowEnvironment env;

    @BeforeEach
    void start() {
        env = TestWorkflowEnvironment.newInstance();
        Worker worker = env.newWorker(QUEUE);
        worker.registerWorkflowImplementationTypes(HelloWorkflowImpl.class);
        worker.registerActivitiesImplementations(new HelloActivitiesImpl());
        env.start();
    }

    @AfterEach
    void stop() {
        env.close();
    }

    @Test
    void completedHistoryHasNoCardNumberAndReplays() throws Exception {
        HelloResult result = run("clean", "Ada");
        assertEquals("Hello, Ada!", result.greeting());

        WorkflowExecutionHistory history = env.getWorkflowClient().fetchHistory("clean");
        assertTrue(CardNumberScanner.findCardNumbers(history.getHistory().toString()).isEmpty());
        WorkflowReplayer.replayWorkflowExecution(history, HelloWorkflowImpl.class);
    }

    @Test
    void scannerDetectsCardNumberPlacedInHistory() {
        run("leaky", "4111111111111111");

        WorkflowExecutionHistory history = env.getWorkflowClient().fetchHistory("leaky");
        assertFalse(CardNumberScanner.findCardNumbers(history.getHistory().toString()).isEmpty());
    }

    private HelloResult run(String id, String name) {
        HelloWorkflow workflow = env.getWorkflowClient().newWorkflowStub(
                HelloWorkflow.class,
                WorkflowOptions.newBuilder().setWorkflowId(id).setTaskQueue(QUEUE).build());
        return workflow.run(new HelloRequest(id, name));
    }
}
