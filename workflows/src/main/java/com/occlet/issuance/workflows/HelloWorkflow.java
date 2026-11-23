package com.occlet.issuance.workflows;

import com.occlet.issuance.contracts.HelloRequest;
import com.occlet.issuance.contracts.HelloResult;
import com.occlet.issuance.contracts.WorkflowTypes;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface HelloWorkflow {
    @WorkflowMethod(name = WorkflowTypes.HELLO)
    HelloResult run(HelloRequest request);
}
