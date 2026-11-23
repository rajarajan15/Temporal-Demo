package com.occlet.issuance.api;

import com.occlet.issuance.contracts.HelloRequest;
import com.occlet.issuance.contracts.HelloResult;
import com.occlet.issuance.contracts.WorkflowTypes;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowServiceException;
import io.temporal.client.WorkflowStub;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Milestone 1 sample. Starts the workflow by type name, so the API never imports workflow classes. */
@RestController
class HelloController {
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");
    // Digits are refused so a card number can never enter workflow history through this field.
    private static final Pattern NAME = Pattern.compile("[\\p{L} .'-]{1,64}");

    private final WorkflowClient client;
    private final String taskQueue;

    HelloController(WorkflowClient client, @Value("${issuance.task-queue:issuance-dev}") String taskQueue) {
        this.client = client;
        this.taskQueue = taskQueue;
    }

    @PostMapping("/v1/hello")
    ResponseEntity<?> hello(@RequestBody HelloRequest request) {
        if (request.requestId() == null || !REQUEST_ID.matcher(request.requestId()).matches()
                || request.name() == null || !NAME.matcher(request.name()).matches()) {
            return ResponseEntity.badRequest().body(Map.of("code", "INVALID_REQUEST"));
        }

        String workflowId = "hello-" + request.requestId();
        WorkflowStub stub = client.newUntypedWorkflowStub(
                WorkflowTypes.HELLO,
                WorkflowOptions.newBuilder()
                        .setWorkflowId(workflowId)
                        .setTaskQueue(taskQueue)
                        .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                        .build());
        try {
            stub.start(request);
        } catch (WorkflowExecutionAlreadyStarted duplicate) {
            stub = client.newUntypedWorkflowStub(workflowId);
        }

        try {
            return ResponseEntity.ok(stub.getResult(10, TimeUnit.SECONDS, HelloResult.class));
        } catch (TimeoutException stillRunning) {
            return ResponseEntity.accepted().body(Map.of("requestId", request.requestId(), "status", "IN_PROGRESS"));
        }
    }

    @ExceptionHandler(WorkflowServiceException.class)
    ResponseEntity<Map<String, String>> temporalUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "5")
                .body(Map.of("code", "ENGINE_UNAVAILABLE"));
    }
}
