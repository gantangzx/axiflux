package com.gantang.tianshu.api.agent;

import java.util.List;
import java.util.Map;

/**
 * Final response returned after an agent turn completes.
 */
public record AgentResponse(
    String responseId,
    String content,
    List<ToolCallInfo> toolCalls,
    Status status,
    Map<String, Object> metadata
) {
    public enum Status {
        SUCCESS,
        MAX_ITERATIONS,
        INTERRUPTED,
        ERROR
    }

    public record ToolCallInfo(
        String callId,
        String toolName,
        Map<String, Object> arguments,
        String result
    ) {}

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String responseId = "";
        private String content = "";
        private List<ToolCallInfo> toolCalls = List.of();
        private Status status = Status.SUCCESS;
        private Map<String, Object> metadata = Map.of();

        public Builder responseId(String v)      { this.responseId   = v; return this; }
        public Builder content(String v)         { this.content      = v; return this; }
        public Builder toolCalls(List<ToolCallInfo> v) { this.toolCalls = v; return this; }
        public Builder status(Status v)         { this.status       = v; return this; }
        public Builder metadata(Map<String, Object> v) { this.metadata = v; return this; }

        public AgentResponse build() {
            return new AgentResponse(responseId, content, toolCalls, status, metadata);
        }
    }
}
