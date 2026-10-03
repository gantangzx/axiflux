package com.gantang.reaxon.api.scheduler;

/**
 * Command pattern — encapsulates work that a {@link TaskScheduler} runs.
 *
 * <p>Marker interface plus a factory method for turning a plain {@link Runnable}
 * into a named command, giving the scheduler a stable identity for logging
 * and observability.
 */
@FunctionalInterface
public interface TaskCommand {

    /** Execute the task. */
    void execute();

    /** Optional human-readable name; defaults to class name. */
    default String name() { return getClass().getSimpleName(); }

    /** Wrap a Runnable as a TaskCommand with the given name. */
    static TaskCommand named(String name, Runnable r) {
        return new TaskCommand() {
            @Override public void execute() { r.run(); }
            @Override public String name()  { return name; }
        };
    }
}
