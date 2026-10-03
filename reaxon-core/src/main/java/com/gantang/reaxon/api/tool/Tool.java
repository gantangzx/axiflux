package com.gantang.reaxon.api.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.gantang.reaxon.api.agent.AgentContext;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Map;

/**
 * Tool interface — aligned with OpenAI Function Calling / Anthropic Tool Use.
 *
 * A tool has three parts:
 *   1. name + description — tells the LLM when to call it
 *   2. parameters (JSON Schema) — tells the LLM what args to pass
 *   3. execute() — actual business logic
 */
public interface Tool {

    /** Tool unique name (snake_case) */
    String name();

    /** Tool description shown to the LLM for decision making */
    String description();

    /** Input parameters as JSON Schema (draft-07) */
    JsonNode parameters();

    /** Tool group for batch registration and permission control */
    default String group() { return "default"; }

    /**
     * Risk classification used by the tool policy chain.
     * Defaults to WRITE for approval-gated tools, READ otherwise.
     */
    default com.gantang.reaxon.api.tool.policy.RiskLevel riskLevel() {
        return requiresApproval()
            ? com.gantang.reaxon.api.tool.policy.RiskLevel.WRITE
            : com.gantang.reaxon.api.tool.policy.RiskLevel.READ;
    }

    /** Whether this tool requires human approval before execution */
    default boolean requiresApproval() { return false; }

    /** Whether this tool is hidden from tool listing (internal only) */
    default boolean hidden() { return false; }

    /**
     * Wall-clock budget for a single execution of this tool before the agent loop
     * cancels it. Return {@code null} (the default) to use the agent's global tool
     * timeout. Long-running delegation tools (e.g. sub-agent spawning) override this
     * to allow minutes rather than the default short cap.
     */
    default Duration timeout() { return null; }

    /**
     * Execute the tool.
     *
     * @param callId   Call ID (used to route result back to the correct tool_call)
     * @param params   Parsed & validated parameters
     * @param context  Agent execution context (session info, accumulated tool results)
     * @return execution result
     */
    ToolResult execute(String callId, Map<String, Object> params, AgentContext context);

    /**
     * Reactive execution entry point used by the agent loop.
     *
     * <p>The default implementation bridges the synchronous {@link #execute}
     * onto the {@code boundedElastic} scheduler, so existing blocking tools
     * work unchanged without occupying a Netty/event-loop thread. Tools built
     * on non-blocking IO (async HTTP clients, reactive drivers) should override
     * this method to return a proper {@link Mono} so the call holds no thread
     * while waiting for the remote response.
     *
     * <p>Implementations must NOT block the subscribing thread; block inside a
     * {@code Mono.fromCallable(...).subscribeOn(boundedElastic)} or use
     * non-blocking APIs end to end.
     */
    default Mono<ToolResult> executeReactive(String callId, Map<String, Object> params,
                                             AgentContext context) {
        return Mono.fromCallable(() -> execute(callId, params, context))
            .subscribeOn(Schedulers.boundedElastic());
    }
}
