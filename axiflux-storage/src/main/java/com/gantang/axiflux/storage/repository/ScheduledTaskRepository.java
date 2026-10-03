package com.gantang.axiflux.storage.repository;

import com.gantang.axiflux.storage.entity.ScheduledTaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * Repository for scheduled tasks.
 */
@Repository
public interface ScheduledTaskRepository extends JpaRepository<ScheduledTaskEntity, String> {

    List<ScheduledTaskEntity> findByUserId(String userId);

    List<ScheduledTaskEntity> findByUserIdAndEnabledTrue(String userId);

    java.util.Optional<ScheduledTaskEntity> findByIdAndUserId(String id, String userId);

    @Query("select t from ScheduledTaskEntity t where t.enabled = true and t.nextRun is not null and t.nextRun <= :now")
    List<ScheduledTaskEntity> findDueTasks(@Param("now") Instant now);

    List<ScheduledTaskEntity> findBySessionId(String sessionId);
}
