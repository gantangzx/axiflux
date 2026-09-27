package com.gantang.tianshu.api.agent;

import java.util.List;
import java.util.Optional;

/**
 * Persistent registry of {@link AgentDefinition}s.
 *
 * <p>Implemented in the Spring/storage layer (JPA-backed). The reactive engine
 * consults it per turn to resolve the effective system prompt and pinned model
 * for the session's agent. The console manages definitions through this same
 * interface (create / edit / delete).
 */
public interface AgentDirectory {

    /** All definitions, ordered for display. */
    List<AgentDefinition> list();

    /** All template definitions (isTemplate=true), ordered for display. */
    List<AgentDefinition> listTemplates();

    /** Look up a single agent by id. */
    Optional<AgentDefinition> get(String agentId);

    /** Insert or update a definition; returns the stored value. */
    AgentDefinition save(AgentDefinition definition);

    /** Delete a definition; returns false if not present or not deletable (builtin). */
    boolean delete(String agentId);
}
