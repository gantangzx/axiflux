package com.gantang.tianshu.storage.repository;

import com.gantang.tianshu.storage.entity.ToolExecutionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * Repository for {@link ToolExecutionEntity} (tool_executions audit table).
 */
@Repository
public interface ToolExecutionRepository extends JpaRepository<ToolExecutionEntity, Long> {

    List<ToolExecutionEntity> findBySessionIdOrderByCreatedAtDesc(String sessionId, Pageable pageable);

    /**
     * Paginated, filterable audit search. Null filters are ignored.
     *
     * <p>{@code user} is the tenant boundary: callers without the audit-reader scope
     * must always pass their own id so the query can never return another user's rows.
     */
    @Query("""
            select t from ToolExecutionEntity t
            where (:tool is null or t.toolName = :tool)
              and (:session is null or t.sessionId = :session)
              and (:user is null or t.userId = :user)
              and (:success is null or t.success = :success)
            order by t.createdAt desc
            """)
    Page<ToolExecutionEntity> search(@Param("tool") String tool,
                                     @Param("session") String session,
                                     @Param("user") String user,
                                     @Param("success") Boolean success,
                                     Pageable pageable);

    /** Row count in a window, optionally narrowed to one user ({@code null} = all). */
    @Query("""
            select count(t) from ToolExecutionEntity t
            where t.createdAt >= :after
              and (:user is null or t.userId = :user)
            """)
    long countSince(@Param("after") Instant after, @Param("user") String user);

    /** Success/failure count in a window, optionally narrowed to one user. */
    @Query("""
            select count(t) from ToolExecutionEntity t
            where t.createdAt >= :after
              and t.success = :success
              and (:user is null or t.userId = :user)
            """)
    long countSinceBySuccess(@Param("after") Instant after,
                             @Param("success") boolean success,
                             @Param("user") String user);

    /**
     * Per-tool rollup within a window for abuse alerting, optionally narrowed to
     * one user ({@code null} = deployment-wide).
     * Each row: { toolName(String), total(Long), failures(Long) }.
     */
    @Query("""
            select t.toolName as tool, count(t) as total,
                   sum(case when t.success = false then 1 else 0 end) as failures
            from ToolExecutionEntity t
            where t.createdAt >= :after
              and (:user is null or t.userId = :user)
            group by t.toolName
            order by count(t) desc
            """)
    List<Object[]> toolRollup(@Param("after") Instant after, @Param("user") String user);
}
