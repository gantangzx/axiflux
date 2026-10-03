package com.gantang.axiflux.storage.repository;

import com.gantang.axiflux.storage.entity.AgentDefinitionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link AgentDefinitionEntity} (agent_definition table).
 */
@Repository
public interface AgentDefinitionRepository extends JpaRepository<AgentDefinitionEntity, String> {
    List<AgentDefinitionEntity> findAllByOrderByCreatedAtAsc();

    Optional<AgentDefinitionEntity> findByAgentId(String agentId);

    /** Explicit query (avoids ambiguity between the isTemplate field and the Is* keyword). */
    @Query("select e from AgentDefinitionEntity e where e.isTemplate = true order by e.createdAt asc")
    List<AgentDefinitionEntity> findByIsTemplateTrue();
}
