package com.gantang.tianshu.api.llm;

import com.gantang.tianshu.api.session.Message;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * LLM completion request.
 */
public record CompletionRequest(
    String model,
    List<Message> messages,
    Double temperature,
    Integer maxTokens,
    Double topP,
    String stopSequence,
    String forcedModel,
    Map<String, Object> extraParams
) {
    public static Builder builder() { return new Builder(); }

    public Builder toBuilder() { return new Builder(this); }

    public static class Builder {
        private String model = "gpt-4o";
        private List<Message> messages = new ArrayList<>();
        private Double temperature = 0.7;
        private Integer maxTokens = 4096;
        private Double topP;
        private String stopSequence;
        private String forcedModel;
        private Map<String, Object> extraParams = Map.of();

        Builder() {}

        Builder(CompletionRequest req) {
            this.model = req.model;
            this.messages = new ArrayList<>(req.messages);
            this.temperature = req.temperature;
            this.maxTokens = req.maxTokens;
            this.topP = req.topP;
            this.stopSequence = req.stopSequence;
            this.forcedModel = req.forcedModel();
            this.extraParams = req.extraParams;
        }

        public Builder model(String v)           { this.model         = v; return this; }
        public Builder forcedModel(String v)      { this.forcedModel   = v; return this; }
        public Builder messages(List<Message> v) { this.messages      = v; return this; }
        public Builder addMessage(Message m)      { this.messages.add(m); return this; }
        public Builder temperature(Double v)    { this.temperature   = v; return this; }
        public Builder maxTokens(Integer v)     { this.maxTokens     = v; return this; }
        public Builder topP(Double v)           { this.topP           = v; return this; }
        public Builder stopSequence(String v)   { this.stopSequence  = v; return this; }
        public Builder extraParams(Map<String, Object> v) { this.extraParams = v; return this; }

        public CompletionRequest build() {
            return new CompletionRequest(model, messages, temperature, maxTokens, topP, stopSequence, forcedModel, extraParams);
        }
    }
}
