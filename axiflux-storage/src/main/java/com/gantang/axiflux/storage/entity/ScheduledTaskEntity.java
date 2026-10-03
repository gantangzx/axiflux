package com.gantang.axiflux.storage.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * JPA entity for scheduled tasks.
 */
@Entity
@Table(name = "scheduled_tasks", indexes = {
    @Index(name = "idx_scheduled_tasks_user_enabled", columnList = "user_id, enabled")
})
public class ScheduledTaskEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 256)
    private String name;

    @Column(nullable = false, length = 32)
    private String type;  // CRON | DELAY | PERIODIC

    @Column(nullable = false, length = 256)
    private String schedule;  // cron expression, interval ms, or ISO-8601 instant for AT

    /** IANA timezone for CRON tasks (e.g. Asia/Shanghai); null = server default zone. */
    @Column(length = 64)
    private String timezone;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Object payload;

    @Column(name = "session_id", length = 64)
    private String sessionId;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column
    private Boolean enabled = true;

    @Column(name = "next_run")
    private Instant nextRun;

    @Column(name = "last_run")
    private Instant lastRun;

    @Column(name = "run_count")
    private Integer runCount = 0;

    @Column(name = "error_count")
    private Integer errorCount = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getSchedule() { return schedule; }
    public void setSchedule(String schedule) { this.schedule = schedule; }
    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }
    public Object getPayload() { return payload; }
    public void setPayload(Object payload) { this.payload = payload; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public Instant getNextRun() { return nextRun; }
    public void setNextRun(Instant nextRun) { this.nextRun = nextRun; }
    public Instant getLastRun() { return lastRun; }
    public void setLastRun(Instant lastRun) { this.lastRun = lastRun; }
    public Integer getRunCount() { return runCount; }
    public void setRunCount(Integer runCount) { this.runCount = runCount; }
    public Integer getErrorCount() { return errorCount; }
    public void setErrorCount(Integer errorCount) { this.errorCount = errorCount; }
    public Instant getCreatedAt() { return createdAt; }
}
