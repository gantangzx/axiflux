package com.gantang.tianshu.eval.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gantang.tianshu.api.agent.AgentContext;
import com.gantang.tianshu.api.tool.Tool;
import com.gantang.tianshu.api.tool.ToolResult;
import com.gantang.tianshu.eval.scenario.Scenario;

import java.util.Map;

/**
 * Scripted tool for replay scenarios: returns a fixed result (or error) and
 * records every invocation into the shared {@link SideEffectStore}.
 */
class FakeTool implements Tool {

    private static final ObjectMapper OM = new ObjectMapper();

    private final Scenario.ToolStub spec;
    private final SideEffectStore effects;

    FakeTool(Scenario.ToolStub spec, SideEffectStore effects) {
        this.spec = spec;
        this.effects = effects;
    }

    @Override
    public String name() {
        return spec.name();
    }

    @Override
    public String description() {
        return spec.description() != null ? spec.description() : ("Fake tool " + spec.name());
    }

    @Override
    public boolean requiresApproval() {
        return Boolean.TRUE.equals(spec.requiresApproval());
    }

    @Override
    public JsonNode parameters() {
        // Declared schema when the scenario provides one (live quality scenarios
        // need real parameters to be passed); otherwise a permissive schema that
        // accepts any object arguments from the scripted model.
        if (spec.parameters() != null && !spec.parameters().isEmpty()) {
            return OM.valueToTree(spec.parameters());
        }
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        schema.set("properties", JsonNodeFactory.instance.objectNode());
        schema.put("additionalProperties", true);
        return schema;
    }

    @Override
    public ToolResult execute(String callId, Map<String, Object> params, AgentContext context) {
        if (spec.error() != null && !spec.error().isBlank()) {
            effects.record(spec.name(), params, false, null, spec.error());
            return ToolResult.failure(callId, spec.error());
        }
        String content;
        if (spec.returns() != null) {
            content = spec.returns();
        } else if (spec.repeatReturns() != null && spec.repeatTimes() != null) {
            content = spec.repeatReturns().repeat(Math.max(0, spec.repeatTimes()));
        } else {
            content = "ok";
        }
        effects.record(spec.name(), params, true, content, null);
        return ToolResult.success(callId, content);
    }
}
