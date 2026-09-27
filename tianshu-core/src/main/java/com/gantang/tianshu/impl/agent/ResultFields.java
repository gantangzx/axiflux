package com.gantang.tianshu.impl.agent;

import java.util.HashMap;
import java.util.Map;

/**
 * Builds the common {@code spawn_result} event payload (task id, parent session,
 * answer, mode and the accumulated token/model-call usage). Shared by the single
 * and critique orchestrators and the facade.
 *
 * <p>Extracted from {@code DefaultSubAgentService} (audit-2026-09-14 P1-1).
 */
final class ResultFields {

    private ResultFields() {}

    static Map<String, Object> of(SubAgentTaskInfo ti, String answer) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("taskId", ti.taskId);
        fields.put("parentSessionId", ti.parentSessionId);
        fields.put("answer", answer);
        fields.put("mode", ti.mode.name().toLowerCase());
        fields.put("inputTokens", ti.inputTokens);
        fields.put("cachedInputTokens", ti.cachedInputTokens);
        fields.put("outputTokens", ti.outputTokens);
        fields.put("totalTokens", ti.inputTokens + ti.outputTokens);
        fields.put("modelCalls", ti.modelCalls);
        return fields;
    }
}
