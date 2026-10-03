package com.gantang.reaxon.api.agent;

import com.gantang.reaxon.api.session.Message;
import com.gantang.reaxon.api.session.Session;
import com.gantang.reaxon.api.tool.Tool;
import com.gantang.reaxon.api.tool.ToolCall;
import com.gantang.reaxon.api.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Lifecycle hooks for observing (and lightly integrating with) an agent turn.
 *
 * <p>This is the embedding application's extension point: audit logging,
 * metrics, prompt injection, compliance capture. Hooks are <b>observational</b>
 * — they run synchronously at well-defined points, must return fast, and must
 * not block. A hook that throws is logged and skipped; it never fails the
 * turn. Security allow/deny decisions belong to the tool policy chain, not
 * here; hooks cannot approve or deny calls.
 *
 * <p>Register via {@code ReactiveAgent.withHooks(...)}. Multiple hooks run
 * sorted by {@link #order()} (lower first).
 */
public interface AgentHook {

    /**
     * The turn has been dequeued and actual execution starts (after any queue
     * wait, after the driving user message was persisted).
     */
    default void onTurnStart(AgentContext context, Session session) {}

    /**
     * The fully assembled model messages are about to be sent, after context
     * assembly and any proactive compaction. {@code messages} is the live list
     * and must not be mutated by hooks.
     */
    default void onBeforeModelCall(AgentContext context, List<Message> messages, List<JsonNode> toolDefs) {}

    /**
     * A tool call finished and its (possibly pruned) result has been persisted
     * to the session. {@code tool} is {@code null} when the tool was not found
     * in the registry.
     */
    default void onToolResult(AgentContext context, Tool tool, ToolCall call, ToolResult result) {}

    /**
     * The turn produced its terminal response — success, max-iterations,
     * interrupt, or classified error. Fires exactly once per turn.
     */
    default void onTurnEnd(AgentContext context, AgentResponse response) {}

    /** Order — lower runs first. Default 0. */
    default int order() {
        return 0;
    }
}
