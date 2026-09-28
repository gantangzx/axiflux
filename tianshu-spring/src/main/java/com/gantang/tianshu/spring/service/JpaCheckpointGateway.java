package com.gantang.tianshu.spring.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gantang.tianshu.api.workflow.Checkpoint;
import com.gantang.tianshu.storage.entity.GraphCheckpointEntity;
import com.gantang.tianshu.storage.repository.GraphCheckpointRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Transactional JPA gateway for {@link JpaCheckpointStore}.
 *
 * <p>A separate Spring bean so {@code @Transactional} is applied through the proxy.
 * Serializes checkpoints to JSON; the {@link #claim} compare-and-set (PAUSED →
 * RESUMING) makes resuming a run atomic.
 */
public class JpaCheckpointGateway {

    private static final Logger log = LoggerFactory.getLogger(JpaCheckpointGateway.class);

    private final GraphCheckpointRepository repository;
    private final ObjectMapper mapper;

    public JpaCheckpointGateway(GraphCheckpointRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Transactional
    public void upsert(Checkpoint checkpoint) {
        Instant now = Instant.now();
        GraphCheckpointEntity entity = repository.findById(checkpoint.runId())
            .orElseGet(GraphCheckpointEntity::new);
        boolean isNew = entity.getCreatedAt() == null;
        entity.setRunId(checkpoint.runId());
        entity.setGraphName(checkpoint.graphName());
        entity.setNodeId(checkpoint.nodeId());
        entity.setUserId(checkpoint.userId());
        entity.setSessionId(checkpoint.sessionId());
        entity.setReason(checkpoint.reason());
        entity.setPayload(write(checkpoint));
        entity.setStatus(com.gantang.tianshu.api.workflow.CheckpointStore.Status.PAUSED.name());
        if (isNew) {
            entity.setCreatedAt(checkpoint.createdAt() != null ? checkpoint.createdAt() : now);
        }
        entity.setUpdatedAt(now);
        repository.save(entity);
    }

    @Transactional
    public void delete(String runId) {
        repository.deleteById(runId);
    }

    @Transactional(readOnly = true)
    public Optional<Checkpoint> find(String runId) {
        return repository.findById(runId).map(this::read);
    }

    @Transactional(readOnly = true)
    public List<Checkpoint> list(List<com.gantang.tianshu.api.workflow.CheckpointStore.Status> statuses) {
        List<String> names = statuses.isEmpty()
            ? List.of(
                com.gantang.tianshu.api.workflow.CheckpointStore.Status.PAUSED.name(),
                com.gantang.tianshu.api.workflow.CheckpointStore.Status.RESUMING.name())
            : statuses.stream().map(Enum::name).toList();
        return repository.findByStatusIn(names).stream().map(this::read).toList();
    }

    /** Atomically flip PAUSED → RESUMING in one transaction and read the winner. */
    @Transactional
    public Optional<Checkpoint> claim(String runId) {
        int updated = repository.claim(runId, Instant.now());
        if (updated == 0) {
            return Optional.empty();
        }
        return repository.findById(runId).map(this::read);
    }

    /** Flip RESUMING → PAUSED and persist the (possibly advanced) checkpoint. */
    @Transactional
    public void release(Checkpoint checkpoint) {
        Instant now = Instant.now();
        int rows = repository.unclaim(checkpoint.runId(), now);
        if (rows == 0) {
            upsert(checkpoint);
            return;
        }
        repository.findById(checkpoint.runId()).ifPresent(entity -> {
            entity.setPayload(write(checkpoint));
            entity.setNodeId(checkpoint.nodeId());
            entity.setReason(checkpoint.reason());
            entity.setUpdatedAt(now);
            repository.save(entity);
        });
    }

    private String write(Checkpoint checkpoint) {
        try {
            return mapper.writeValueAsString(checkpoint);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize checkpoint " + checkpoint.runId(), e);
        }
    }

    private Checkpoint read(GraphCheckpointEntity entity) {
        try {
            return mapper.readValue(entity.getPayload(), Checkpoint.class);
        } catch (Exception e) {
            log.error("Failed to deserialize checkpoint {}", entity.getRunId(), e);
            throw new IllegalStateException(
                "failed to deserialize checkpoint " + entity.getRunId(), e);
        }
    }
}
