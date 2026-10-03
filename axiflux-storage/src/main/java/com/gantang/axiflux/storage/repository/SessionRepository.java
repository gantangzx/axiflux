package com.gantang.axiflux.storage.repository;

import com.gantang.axiflux.storage.entity.SessionEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link SessionEntity}.
 */
@Repository
public interface SessionRepository extends JpaRepository<SessionEntity, String> {

    /**
     * One page of a user's sessions. Callers must pass a {@link Pageable} with a
     * deterministic sort, otherwise rows can repeat or vanish across pages.
     */
    List<SessionEntity> findByUserId(String userId, Pageable pageable);

    /** As {@link #findByUserId}, filtered by state. */
    List<SessionEntity> findByUserIdAndState(String userId, String state, Pageable pageable);

    /**
     * User-facing page of a user's sessions with transient sub-agent sessions
     * excluded <b>in SQL</b>. Filtering after pagination would produce short or
     * empty pages whenever a page happens to hold many sub-agent rows (P1-6),
     * making later pages unreachable for clients that stop on a short page.
     * metadata is jsonb; a missing kind is a normal user session.
     */
    @Query(value = "SELECT * FROM sessions s WHERE s.user_id = :userId " +
        "AND (s.metadata ->> 'kind' IS NULL OR s.metadata ->> 'kind' <> 'subagent') " +
        "ORDER BY s.last_active_at DESC, s.id ASC",
        nativeQuery = true)
    List<SessionEntity> findUserFacingByUserId(@Param("userId") String userId, Pageable pageable);

    /** As {@link #findUserFacingByUserId}, filtered by state. */
    @Query(value = "SELECT * FROM sessions s WHERE s.user_id = :userId AND s.state = :state " +
        "AND (s.metadata ->> 'kind' IS NULL OR s.metadata ->> 'kind' <> 'subagent') " +
        "ORDER BY s.last_active_at DESC, s.id ASC",
        nativeQuery = true)
    List<SessionEntity> findUserFacingByUserIdAndState(@Param("userId") String userId,
                                                       @Param("state") String state, Pageable pageable);

    Optional<SessionEntity> findByIdAndUserId(String id, String userId);

    long countByUserId(String userId);

    @Modifying
    @Query("update SessionEntity s set s.state = :state, s.updatedAt = :now where s.id = :id")
    int updateState(@Param("id") String id, @Param("state") String state, @Param("now") Instant now);

    @Modifying
    @Query("update SessionEntity s set s.lastActiveAt = :now, s.updatedAt = :now where s.id = :id")
    int touch(@Param("id") String id, @Param("now") Instant now);

    long countByUserIdAndState(String userId, String state);
}
