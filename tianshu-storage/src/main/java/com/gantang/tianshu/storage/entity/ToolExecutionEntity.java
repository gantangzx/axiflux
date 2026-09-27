package com.gantang.tianshu.storage.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * JPA entity for the {@code tool_executions} audit table.
 *
 * <p>One row per tool invocation, written by the persistence-backed
 * MetricsReporter. Used for observability, debugging and cost/usage analysis.
 */
@Entity
@Table(name = "tool_executions", indexes = {
    @Index(name = "idx_tool_executions_session", columnList = "session_id"),
    @Index(name = "idx_tool_executions_created", columnList = "created_at")
})
public class ToolExecutionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(name = "user_id", length = 64)
    private String userId;

    @Column(name = "tool_name", nullable = false, length = 128)
    private String toolName;

    @Column(name = "call_id", length = 64)
    private String callId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Object params;

    @Column(nullable = false)
    private Boolean success;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column(columnDefinition = "text")
    private String error;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }
    public String getCallId() { return callId; }
    public void setCallId(String callId) { this.callId = callId; }
    public Object getParams() { return params; }
    public void setParams(Object params) { this.params = params; }
    public Boolean getSuccess() { return success; }
    public void setSuccess(Boolean success) { this.success = success; }
    public Integer getDurationMs() { return durationMs; }
    public void setDurationMs(Integer durationMs) { this.durationMs = durationMs; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
