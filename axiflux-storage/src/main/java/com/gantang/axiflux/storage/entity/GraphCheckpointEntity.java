package com.gantang.axiflux.storage.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * JPA entity for the {@code graph_checkpoint} table — durable storage for paused
 * state-graph workflow runs.
 *
 * <p>The full {@code Checkpoint} (including the graph {@code GraphState}) is carried
 * as JSON in {@link #payload}; the denormalized {@link #graphName}, {@link #nodeId},
 * {@link #userId} and {@link #sessionId} columns support filtering and listing
 * without parsing JSON. The {@link #status} column is the compare-and-set latch that
 * makes resuming a run atomic: a resume only claims a row whose status is
 * {@code PAUSED}, flipping it to {@code RESUMING}.
 */
@Entity
@Table(name = "graph_checkpoint")
public class GraphCheckpointEntity {

    @Id
    @Column(name = "run_id", length = 64)
    private String runId;

    @Column(name = "graph_name", length = 128, nullable = false)
    private String graphName;

    @Column(name = "node_id", length = 128, nullable = false)
    private String nodeId;

    @Column(name = "user_id", length = 64)
    private String userId;

    @Column(name = "session_id", length = 64)
    private String sessionId;

    /** Lifecycle latch: PAUSED | RESUMING. */
    @Column(name = "status", length = 16, nullable = false)
    private String status = "PAUSED";

    @Column(name = "reason", length = 256)
    private String reason;

    /** Full serialized checkpoint (GraphState + safe base-context fields) as JSON. */
    @Column(name = "payload", columnDefinition = "text", nullable = false)
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public GraphCheckpointEntity() {}

    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getGraphName() { return graphName; }
    public void setGraphName(String graphName) { this.graphName = graphName; }
    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
