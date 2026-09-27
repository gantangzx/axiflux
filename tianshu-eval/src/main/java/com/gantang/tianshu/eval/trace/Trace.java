package com.gantang.tianshu.eval.trace;

import java.util.List;
import java.util.Map;

/**
 * Everything observable about one scenario run. Assertions and grading read
 * only this object — never the agent's internals.
 */
public record Trace(
        String scenarioId,
        String status,          // AgentResponse.Status name, or "ERROR" if the run threw
        String finalResponse,
        List<String> eventTypes,                 // AgentEvent.Type in emission order
        List<ToolInvocation> toolInvocations,    // from AgentHook.onToolResult, in order
        List<String> errors,
        int modelCallCount,
        List<String> turnResponses,              // assistant reply per user turn (onTurnEnd)
        // Context-budget observability (roadmap P1-2/P1-3 acceptance): the size of
        // the prompt actually handed to the model on every call, so a scenario can
        // be compared against itself under different context-engineering settings.
        List<Integer> promptCharsPerCall,
        int peakPromptChars,
        int peakPromptTokens
) {
    public Trace {
        eventTypes = eventTypes == null ? List.of() : List.copyOf(eventTypes);
        toolInvocations = toolInvocations == null ? List.of() : List.copyOf(toolInvocations);
        errors = errors == null ? List.of() : List.copyOf(errors);
        turnResponses = turnResponses == null ? List.of() : List.copyOf(turnResponses);
        promptCharsPerCall = promptCharsPerCall == null ? List.of() : List.copyOf(promptCharsPerCall);
    }

    public record ToolInvocation(
            int order,
            int turn,            // 1-based user turn in which the tool ran (onTurnStart count)
            String name,
            Map<String, Object> args,
            boolean found,         // false when the tool was not registered
            boolean success,
            String result,
            String error
    ) {}
}
