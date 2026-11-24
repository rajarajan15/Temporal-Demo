package com.occlet.issuance.contracts;

/** Workflow, signal, update and query names. Renaming one is a breaking change for in-flight runs. */
public final class WorkflowTypes {
    public static final String HELLO = "HelloWorkflow";
    public static final String VIRTUAL_CARD = "VirtualCardWorkflow";
    public static final String REISSUE = "ReissueWorkflow";
    public static final String RECONCILIATION = "ReconciliationWorkflow";

    public static final String PROGRESS_QUERY = "progress";
    public static final String PROCESSOR_EVENT_SIGNAL = "processorEvent";
    public static final String REVIEW_UPDATE = "review";
    public static final String CANCEL_UPDATE = "cancel";

    private WorkflowTypes() {}
}
