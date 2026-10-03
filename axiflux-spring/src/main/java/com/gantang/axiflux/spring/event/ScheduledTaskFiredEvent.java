package com.gantang.axiflux.spring.event;

import org.springframework.context.ApplicationEvent;

/**
 * Domain event published every time a scheduled task fires.
 *
 * <p>Published <em>after</em> the transaction commits via
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)}.
 *
 * <h2>Dispatch semantics</h2>
 * <ul>
 *   <li>{@code kind = "agentTurn"} → invoke the {@link Agent} with the payload as
 *       the user's current query, targeting the stored {@code sessionId}</li>
 *   <li>{@code kind = "systemEvent"} → re-publish as a Spring
 *       {@code systemEvent} to registered {@code SystemEventListener}s</li>
 * </ul>
 *
 * <p>Sub-classes carry the deserialised payload as a generic {@code T} for
 * type-safe access in handlers.
 */
public class ScheduledTaskFiredEvent extends ApplicationEvent {

    private final String taskId;
    private final String taskName;
    private final String userId;
    private final String sessionId;
    private final String kind;        // "agentTurn" | "systemEvent"
    private final Object payload;     // deserialised payload (JSON Object or Map)

    public ScheduledTaskFiredEvent(Object source, String taskId, String taskName,
                                   String userId, String sessionId,
                                   String kind, Object payload) {
        super(source);
        this.taskId     = taskId;
        this.taskName   = taskName;
        this.userId     = userId;
        this.sessionId  = sessionId;
        this.kind       = kind;
        this.payload    = payload;
    }

    public String taskId()    { return taskId; }
    public String taskName()  { return taskName; }
    public String userId()    { return userId; }
    public String sessionId() { return sessionId; }
    public String kind()      { return kind; }
    /** The deserialised payload. Cast or deserialize as appropriate. */
    public Object payload()   { return payload; }

    /** Convenience: returns {@code payload} cast to {@code T}. */
    @SuppressWarnings("unchecked")
    public <T> T payloadAs(Class<T> clazz) {
        if (payload == null) return null;
        if (clazz.isInstance(payload)) return (T) payload;
        throw new ClassCastException("payload is not of type " + clazz.getName());
    }
}
