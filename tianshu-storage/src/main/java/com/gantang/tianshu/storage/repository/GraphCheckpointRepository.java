package com.gantang.tianshu.storage.repository;

import com.gantang.tianshu.storage.entity.GraphCheckpointEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * Repository for {@link GraphCheckpointEntity} (durable paused-run checkpoints).
 */
@Repository
public interface GraphCheckpointRepository extends JpaRepository<GraphCheckpointEntity, String> {

    List<GraphCheckpointEntity> findByStatusIn(List<String> statuses);

    List<GraphCheckpointEntity> findByUserIdAndStatusIn(String userId, List<String> statuses);

    /**
     * Compare-and-set latch: flip a PAUSED row to RESUMING. Returns the number of
     * rows updated (1 when this caller won the claim, 0 when absent or already
     * claimed), so concurrent resumes drive the run at most once.
     */
    @Modifying
    @Query("UPDATE GraphCheckpointEntity c SET c.status = 'RESUMING', c.updatedAt = :now "
        + "WHERE c.runId = :runId AND c.status = 'PAUSED'")
    int claim(@Param("runId") String runId, @Param("now") Instant now);

    /** Flip a claimed row back to PAUSED (resume failed / paused again). */
    @Modifying
    @Query("UPDATE GraphCheckpointEntity c SET c.status = 'PAUSED', c.updatedAt = :now "
        + "WHERE c.runId = :runId AND c.status = 'RESUMING'")
    int unclaim(@Param("runId") String runId, @Param("now") Instant now);
}
