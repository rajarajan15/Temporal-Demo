package com.occlet.issuance.contracts;

/** Workflow and query names. Renaming one is a breaking change for in-flight runs. */
public final class WorkflowTypes {
    public static final String HELLO = "HelloWorkflow";
    public static final String VIRTUAL_CARD = "VirtualCardWorkflow";
    public static final String PROGRESS_QUERY = "progress";

    private WorkflowTypes() {}
}
