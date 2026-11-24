package com.occlet.issuance.api;

import static com.occlet.issuance.api.ApiResponses.error;
import static com.occlet.issuance.api.ApiResponses.unavailable;

import com.occlet.issuance.contracts.CardProgress;
import com.occlet.issuance.contracts.ErrorTypes;
import com.occlet.issuance.contracts.WorkflowOutcome;
import com.occlet.issuance.contracts.WorkflowTypes;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.client.WorkflowClient;
import io.temporal.client.UpdateOptions;
import io.temporal.client.WorkflowException;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowUpdateStage;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowStub;
import io.temporal.client.WorkflowUpdateException;
import io.temporal.failure.ApplicationFailure;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * Queries and updates need a worker to answer. With no worker they would wait for one, so each call
 * runs with a deadline and the caller gets a quick 503 instead of a hung request.
 */
@Component
class WorkflowCalls {
    private static final long QUERY_DEADLINE_SECONDS = 3;
    private static final long UPDATE_DEADLINE_SECONDS = 5;

    private final WorkflowClient client;
    private final ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor();

    WorkflowCalls(WorkflowClient client) {
        this.client = client;
    }

    ResponseEntity<?> progress(String workflowId) {
        WorkflowStub stub = client.newUntypedWorkflowStub(workflowId);
        Future<CardProgress> query = calls.submit(() -> stub.query(WorkflowTypes.PROGRESS_QUERY, CardProgress.class));
        try {
            return ResponseEntity.ok(query.get(QUERY_DEADLINE_SECONDS, TimeUnit.SECONDS));
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof WorkflowNotFoundException) {
                return error(HttpStatus.NOT_FOUND, "NOT_FOUND");
            }
            return finishedResult(stub);
        } catch (TimeoutException noWorkerAnswered) {
            query.cancel(true);
            return finishedResult(stub);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return unavailable("PROGRESS_UNAVAILABLE");
        }
    }

    /**
     * Sends an update and maps the outcome: 200 with the result, 409 with the validator's reason
     * when the workflow refuses it, 404 when there is no such workflow.
     *
     * <p>A 503 after the deadline does not mean the update was dropped: Temporal may still apply it
     * once a worker is back. With an idempotency key, the update ID is fixed, so a retry returns the
     * original result instead of being refused as a second decision.
     */
    <R> ResponseEntity<?> update(String workflowId, String updateName, Class<R> resultType, Object argument, String idempotencyKey) {
        WorkflowStub stub = client.newUntypedWorkflowStub(workflowId);
        UpdateOptions<R> options = UpdateOptions.newBuilder(resultType)
                .setUpdateName(updateName)
                .setUpdateId(updateName + "-" + (idempotencyKey == null ? UUID.randomUUID().toString() : idempotencyKey))
                .setWaitForStage(WorkflowUpdateStage.COMPLETED)
                .build();
        Callable<R> call = () -> stub.startUpdate(options, argument).getResult();
        Future<R> update = calls.submit(call);
        try {
            return ResponseEntity.ok(update.get(UPDATE_DEADLINE_SECONDS, TimeUnit.SECONDS));
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof WorkflowUpdateException rejected) {
                return error(HttpStatus.CONFLICT, rejectionCode(rejected));
            }
            if (cause instanceof WorkflowNotFoundException) {
                return isClosed(stub) ? error(HttpStatus.CONFLICT, ErrorTypes.ALREADY_FINISHED) : error(HttpStatus.NOT_FOUND, "NOT_FOUND");
            }
            return isClosed(stub) ? error(HttpStatus.CONFLICT, ErrorTypes.ALREADY_FINISHED) : unavailable("ENGINE_UNAVAILABLE");
        } catch (TimeoutException noWorkerAnswered) {
            update.cancel(true);
            return unavailable("WORKER_UNAVAILABLE");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return unavailable("ENGINE_UNAVAILABLE");
        }
    }

    /** With no worker to answer the query, a finished run can still report its result. */
    private ResponseEntity<?> finishedResult(WorkflowStub stub) {
        try {
            WorkflowOutcome outcome = stub.getResult(1, TimeUnit.SECONDS, WorkflowOutcome.class);
            return ResponseEntity.ok(new CardProgress(outcome.status(), outcome.reasonCode(), outcome.card(), List.of(), null, true));
        } catch (WorkflowFailedException failed) {
            // Requests end in a business state, so a failed workflow means a defect, not a decision.
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "WORKFLOW_FAILED");
        } catch (TimeoutException | WorkflowException unavailable) {
            return unavailable("PROGRESS_UNAVAILABLE");
        }
    }

    private static boolean isClosed(WorkflowStub stub) {
        try {
            return stub.describe().getStatus() != WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING;
        } catch (RuntimeException notFound) {
            return false;
        }
    }

    private static String rejectionCode(WorkflowUpdateException rejected) {
        Throwable cause = rejected.getCause();
        if (cause instanceof ApplicationFailure application && application.getType() != null) {
            return application.getType();
        }
        return "UPDATE_REJECTED";
    }
}
