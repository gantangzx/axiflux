package com.gantang.reaxon.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gantang.reaxon.api.session.Message;
import com.gantang.reaxon.api.tool.ToolCall;
import com.gantang.reaxon.eval.engine.OpenAiCompatLlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for the request-shape conversions used by the live HTTP client. */
class OpenAiCompatLlmClientTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void messages_plainRoles_passThrough() {
        JsonNode arr = OpenAiCompatLlmClient.toOpenAiMessages(om, List.of(
                Message.system("sys"),
                Message.user("hi"),
                Message.assistant("hello", List.of())));

        assertEquals(3, arr.size());
        assertEquals("system", arr.get(0).get("role").asText());
        assertEquals("sys", arr.get(0).get("content").asText());
        assertEquals("user", arr.get(1).get("role").asText());
        assertEquals("assistant", arr.get(2).get("role").asText());
        assertEquals("hello", arr.get(2).get("content").asText());
    }

    @Test
    void messages_blankContent_isSkipped() {
        JsonNode arr = OpenAiCompatLlmClient.toOpenAiMessages(om, List.of(
                Message.user("  "),
                Message.system("")));
        assertEquals(0, arr.size());
    }

    @Test
    void messages_assistantToolCalls_emitOpenAiShape() {
        ToolCall call = new ToolCall("call_1", "web_search", Map.of("q", "报销"));
        JsonNode arr = OpenAiCompatLlmClient.toOpenAiMessages(om, List.of(
                Message.assistant("", List.of(call)),
                Message.tool("call_1", "2026 新规：8 号")));

        JsonNode assistant = arr.get(0);
        assertEquals("assistant", assistant.get("role").asText());
        assertTrue(assistant.get("content").isNull(), "tool-call assistant message has null content");
        JsonNode tc = assistant.get("tool_calls").get(0);
        assertEquals("call_1", tc.get("id").asText());
        assertEquals("function", tc.get("type").asText());
        assertEquals("web_search", tc.get("function").get("name").asText());
        assertTrue(tc.get("function").get("arguments").asText().contains("报销"));

        JsonNode toolMsg = arr.get(1);
        assertEquals("tool", toolMsg.get("role").asText());
        assertEquals("call_1", toolMsg.get("tool_call_id").asText());
        assertTrue(toolMsg.get("content").asText().contains("8 号"));
    }

    @Test
    void tools_agentShape_convertsToOpenAiFunctionSchema() {
        ObjectNode agentTool = om.createObjectNode();
        agentTool.put("name", "web_search");
        agentTool.put("description", "搜索网络");
        ObjectNode props = agentTool.putObject("properties");
        props.putObject("q").put("type", "string").put("description", "查询词");
        agentTool.putArray("required").add("q");

        JsonNode tools = OpenAiCompatLlmClient.toOpenAiTools(om, om.createArrayNode().add(agentTool));

        assertEquals(1, tools.size());
        JsonNode fn = tools.get(0);
        assertEquals("function", fn.get("type").asText());
        assertEquals("web_search", fn.get("function").get("name").asText());
        assertEquals("搜索网络", fn.get("function").get("description").asText());
        JsonNode params = fn.get("function").get("parameters");
        assertEquals("object", params.get("type").asText());
        assertTrue(params.get("properties").has("q"));
        assertEquals("q", params.get("required").get(0).asText());
    }
}
