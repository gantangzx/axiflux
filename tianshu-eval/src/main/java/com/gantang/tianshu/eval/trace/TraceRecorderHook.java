package com.gantang.tianshu.eval.trace;

import com.gantang.tianshu.api.agent.AgentContext;
import com.gantang.tianshu.api.agent.AgentHook;
import com.gantang.tianshu.api.agent.AgentResponse;
import com.gantang.tianshu.api.session.Message;
import com.gantang.tianshu.api.session.Session;
import com.gantang.tianshu.api.tool.Tool;
import com.gantang.tianshu.api.tool.ToolCall;
import com.gantang.tianshu.api.tool.ToolResult;
import com.gantang.tianshu.impl.memory.HeuristicTokenCounter;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link AgentHook} that records a scenario's observable trajectory.
 * Observational only (per the hook contract): never mutates messages, never
 * participates in security decisions, and never fails the turn.
 */
public class TraceRecorderHook implements AgentHook {

    private final List<Trace.ToolInvocation> toolInvocations = new ArrayList<>();
    private final List<String> turnResponses = new ArrayList<>();
    private final List<Integer> promptChars = new ArrayList<>();
    private final AtomicInteger modelCalls = new AtomicInteger(0);
    private final AtomicInteger turns = new AtomicInteger(0);
    private final AtomicInteger peakChars = new AtomicInteger(0);
    private final AtomicInteger peakTokens = new AtomicInteger(0);
    private volatile String lastStatus;
    private volatile String lastResponse;

    @Override
    public void onTurnStart(AgentContext context, Session session) {
        turns.incrementAndGet();
    }

    @Override
    public void onBeforeModelCall(AgentContext context, List<Message> messages, List<JsonNode> toolDefs) {
        modelCalls.incrementAndGet();
        // Measure what the model is actually being charged for on this call.
        // Wrapped defensively: observability must never break a turn.
        try {
            int chars = 0;
            if (messages != null) {
                for (Message m : messages) {
                    if (m == null) continue;
                    if (m.content() != null) chars += m.content().length();
                    if (m.toolCalls() != null) {
                        for (var tc : m.toolCalls()) {
                            if (tc == null) continue;
                            if (tc.toolName() != null) chars += tc.toolName().length();
                            if (tc.arguments() != null) chars += String.valueOf(tc.arguments()).length();
                        }
                    }
                }
            }
            if (toolDefs != null) {
                for (JsonNode def : toolDefs) {
                    if (def != null) chars += def.toString().length();
                }
            }
            int tokens = messages == null ? 0
                    : HeuristicTokenCounter.INSTANCE.countMessages(messages);
            synchronized (promptChars) {
                promptChars.add(chars);
            }
            peakChars.accumulateAndGet(chars, Math::max);
            peakTokens.accumulateAndGet(tokens, Math::max);
        } catch (RuntimeException ignored) {
            // never fail the turn for a measurement
        }
    }

    @Override
    public void onToolResult(AgentContext context, Tool tool, ToolCall call, ToolResult result) {
        synchronized (toolInvocations) {
            toolInvocations.add(new Trace.ToolInvocation(
                    toolInvocations.size(),
                    turns.get(),
                    call.toolName(),
                    call.arguments() != null ? Map.copyOf(call.arguments()) : Map.of(),
                    tool != null,
                    result != null && result.success(),
                    result != null ? result.content() : null,
                    result != null ? result.errorMessage() : null
            ));
        }
    }

    @Override
    public void onTurnEnd(AgentContext context, AgentResponse response) {
        if (response != null) {
            lastStatus = response.status() != null ? response.status().name() : null;
            lastResponse = response.content();
            synchronized (turnResponses) {
                turnResponses.add(response.content() != null ? response.content() : "");
            }
        }
    }

    public List<String> turnResponses() {
        synchronized (turnResponses) {
            return List.copyOf(turnResponses);
        }
    }

    public List<Trace.ToolInvocation> toolInvocations() {
        synchronized (toolInvocations) {
            return List.copyOf(toolInvocations);
        }
    }

    public int modelCallCount() {
        return modelCalls.get();
    }

    /** Prompt size (chars) handed to the model on each call, in order. */
    public List<Integer> promptCharsPerCall() {
        synchronized (promptChars) {
            return List.copyOf(promptChars);
        }
    }

    /** Largest single prompt this run sent — the context-budget high-water mark. */
    public int peakPromptChars() {
        return peakChars.get();
    }

    /** Same high-water mark, in heuristic tokens. */
    public int peakPromptTokens() {
        return peakTokens.get();
    }

    public String lastStatus() { return lastStatus; }
    public String lastResponse() { return lastResponse; }
}
