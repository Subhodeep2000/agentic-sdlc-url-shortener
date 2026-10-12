package com.acme.orchestrator;

/** Observer invoked on the coordinator thread; may use the controls to model operator/stakeholder actions. */
public interface RunHook {
    /** Operator/stakeholder handle on a live run. */
    interface Controls {
        void safeStop(String reason);

        /** Publishes a new version of an input (or human.*) artifact from outside the graph. */
        void submitChange(String artifactKey, Object value, String author);
    }

    default void onTaskSucceeded(String taskId, Controls controls) {
    }
}
