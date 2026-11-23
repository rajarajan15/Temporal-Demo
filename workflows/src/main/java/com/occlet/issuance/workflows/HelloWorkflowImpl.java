package com.occlet.issuance.workflows;

import com.occlet.issuance.activities.api.HelloActivities;
import com.occlet.issuance.contracts.HelloRequest;
import com.occlet.issuance.contracts.HelloResult;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;

public class HelloWorkflowImpl implements HelloWorkflow {
    private final HelloActivities activities = Workflow.newActivityStub(
            HelloActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(5))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
                    .build());

    @Override
    public HelloResult run(HelloRequest request) {
        return new HelloResult(request.requestId(), activities.composeGreeting(request.name()));
    }
}
