package dev.orca.ui;

/**
 * Where panels push their feedback: transient messages and the currently highlighted row.
 */
public interface StatusSink {

    void setStatus(String message);

    void setSelection(String description);
}
