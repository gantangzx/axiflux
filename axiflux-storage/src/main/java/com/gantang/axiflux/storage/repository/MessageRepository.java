package com.gantang.axiflux.storage.repository;

import com.gantang.axiflux.storage.entity.MessageEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * Repository for {@link MessageEntity}.
 */
@Repository
public interface MessageRepository extends JpaRepository<MessageEntity, String> {

    /**
     * Fetch full message history in ascending creation order.
     * Tie-broken by id so same-millisecond messages (common in a single turn)
     * come back in a deterministic order (P2-6).
     */
    List<MessageEntity> findBySessionIdOrderByCreatedAtAscIdAsc(String sessionId);

    /** Fetch the most recent N messages (returned newest-first; caller may reverse). */
    List<MessageEntity> findBySessionIdOrderByCreatedAtDesc(String sessionId, Pageable pageable);

    /** Messages within a time window (tie-broken by id for determinism). */
    @Query("select m from MessageEntity m where m.sessionId = :sid and m.createdAt >= :since order by m.createdAt asc, m.id asc")
    List<MessageEntity> findSince(@Param("sid") String sessionId, @Param("since") Instant since);

    long countBySessionId(String sessionId);

    @Modifying
    @Query("delete from MessageEntity m where m.sessionId = :sid")
    int deleteBySessionId(@Param("sid") String sessionId);
}
