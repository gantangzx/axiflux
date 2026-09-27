package com.gantang.tianshu.api.agent;

import com.gantang.tianshu.api.session.Session;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Agent core interface.
 *
 * Execution model:
 *  1. Assemble context (short-term + long-term memory → messages)
 *  2. Call LLM (with tool definitions)
 *  3. If text → return; if tool_call → execute → inject result → goto 2
 *
 * Design principles:
 *  - Fully reactive (Reactor), supports streaming output
 *  - Immutable context, built via Builder
 *  - Deterministic tool call loop with configurable max iterations
 */
public interface Agent {

    /** Unique agent identifier */
    String getAgentId();

    /**
     * Synchronous single-turn.
     * Runs the full tool-call loop internally and returns the final text response.
     */
    Mono<AgentResponse> process(AgentContext context);

    /**
     * Streaming response — SSE / WebSocket compatible.
     * Each emitted element is an AgentEvent:
     *   - text_token : incremental text token from LLM
     *   - tool_call  : LLM requested a tool invocation
     *   - tool_result: tool execution result (injected into next LLM call)
     *   - done       : final response completed
     *   - error      : an error occurred
     */
    Flux<AgentEvent> processStream(AgentContext context);

    /** Interrupt the ongoing execution for a given session. */
    void interrupt(String sessionId);

    /** Check whether a session is currently running. */
    AgentStatus getStatus(String sessionId);
}
