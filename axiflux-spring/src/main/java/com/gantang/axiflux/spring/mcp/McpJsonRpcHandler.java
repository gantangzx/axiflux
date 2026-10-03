package com.gantang.axiflux.spring.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gantang.reaxon.api.agent.AgentContext;
import com.gantang.reaxon.api.mcp.McpEndpoint;
import com.gantang.reaxon.api.mcp.McpEndpoint.McpCallResult;
import com.gantang.reaxon.api.mcp.McpEndpoint.McpPrompt;
import com.gantang.reaxon.api.mcp.McpEndpoint.McpPromptArgument;
import com.gantang.reaxon.api.mcp.McpEndpoint.McpPromptContent;
import com.gantang.reaxon.api.mcp.McpEndpoint.McpResource;
import com.gantang.reaxon.api.mcp.McpEndpoint.McpResourceContent;
import com.gantang.reaxon.api.mcp.McpEndpoint.McpTool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Stateless JSON-RPC 2.0 handler for the Model Context Protocol (Streamable HTTP
 * transport). Bridges MCP method calls to {@link McpEndpoint}.
 *
 * <p>Supported methods:
 * <ul>
 *   <li>{@code initialize} — protocol handshake, returns capabilities + server info</li>
 *   <li>{@code notifications/initialized} — client ready (notification, no response)</li>
 *   <li>{@code ping} — liveness</li>
 *   <li>{@code tools/list}, {@code tools/call}</li>
 *   <li>{@code resources/list}, {@code resources/read}</li>
 *   <li>{@code prompts/list}, {@code prompts/get}</li>
 * </ul>
 */
public class McpJsonRpcHandler {

    /** MCP protocol version this server speaks. */
    public static final String PROTOCOL_VERSION = "2024-11-05";
    private static final String SERVER_NAME = "axiflux-agent";
    private static final String SERVER_VERSION = "0.1.0";

    private static final int PARSE_ERROR = -32700;
    private static final int INVALID_REQUEST = -32600;
    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INVALID_PARAMS = -32602;
    private static final int INTERNAL_ERROR = -32603;

    private final ObjectMapper om;
    private final McpEndpoint endpoint;

    public McpJsonRpcHandler(ObjectMapper om, McpEndpoint endpoint) {
        this.om = om;
        this.endpoint = endpoint;
    }

    /**
     * Handle a raw JSON-RPC payload (single request or batch). Returns the response
     * node, or {@code null} for notifications (no response expected).
     */
    public JsonNode handle(JsonNode payload) {
        return handle(payload, null);
    }

    /**
     * Handle a payload on behalf of an authenticated caller. The {@link AgentContext}
     * (userId/sessionId/scopes threaded from the verified transport principal) is
     * applied to {@code tools/call} so the tool policy chain evaluates the real
     * caller; a null caller falls back to the system "mcp" principal (fail-closed
     * for scope-gated tools).
     */
    public JsonNode handle(JsonNode payload, AgentContext caller) {
        if (payload == null) {
            return error(null, PARSE_ERROR, "Parse error");
        }
        if (payload.isArray()) {
            ArrayNode responses = om.createArrayNode();
            for (JsonNode req : payload) {
                JsonNode resp = dispatch(req, caller);
                if (resp != null) responses.add(resp);
            }
            return responses.isEmpty() ? null : responses;
        }
        return dispatch(payload, caller);
    }

    private JsonNode dispatch(JsonNode req, AgentContext caller) {
        JsonNode id = req.get("id");
        String method = req.path("method").asText(null);
        JsonNode params = req.get("params");

        // Notifications (no id) get no response.
        boolean isNotification = id == null || id.isNull();
        if (method == null || method.isBlank()) {
            return isNotification ? null : error(id, INVALID_REQUEST, "Missing method");
        }
        if (isNotification) {
            return null;
        }

        try {
            ObjectNode result = switch (method) {
                case "initialize" -> initialize();
                case "ping" -> om.createObjectNode();
                case "tools/list" -> toolsList();
                case "tools/call" -> toolsCall(params, caller);
                case "resources/list" -> resourcesList();
                case "resources/read" -> resourcesRead(params);
                case "prompts/list" -> promptsList();
                case "prompts/get" -> promptsGet(params);
                default -> null;
            };
            if (result == null) {
                return error(id, METHOD_NOT_FOUND, "Method not found: " + method);
            }
            return ok(id, result);
        } catch (IllegalArgumentException e) {
            return error(id, INVALID_PARAMS, "Invalid params");
        } catch (Exception e) {
            return error(id, INTERNAL_ERROR, "Internal server error");
        }
    }

    private ObjectNode initialize() {
        ObjectNode result = om.createObjectNode();
        result.put("protocolVersion", PROTOCOL_VERSION);
        ObjectNode caps = result.putObject("capabilities");
        caps.putObject("tools");
        caps.putObject("resources");
        caps.putObject("prompts");
        ObjectNode server = result.putObject("serverInfo");
        server.put("name", SERVER_NAME);
        server.put("version", SERVER_VERSION);
        return result;
    }

    private ObjectNode toolsList() {
        ObjectNode result = om.createObjectNode();
        ArrayNode tools = result.putArray("tools");
        for (McpTool t : endpoint.listTools()) {
            ObjectNode tool = tools.addObject();
            tool.put("name", t.name());
            if (t.description() != null) tool.put("description", t.description());
            tool.set("inputSchema", t.inputSchema() != null ? t.inputSchema() : emptySchema());
        }
        return result;
    }

    private ObjectNode toolsCall(JsonNode params, AgentContext caller) {
        if (params == null) throw new IllegalArgumentException("Missing params");
        String name = params.path("name").asText(null);
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Missing tool name");
        JsonNode arguments = params.get("arguments");

        // With no caller identity the call goes through the legacy 2-arg path
        // (system "mcp" principal inside the endpoint); otherwise thread the
        // caller so the policy chain evaluates the real principal.
        McpCallResult r = caller != null
            ? endpoint.callTool(name, arguments, caller)
            : endpoint.callTool(name, arguments);
        ObjectNode result = om.createObjectNode();
        ArrayNode content = result.putArray("content");
        if (r.success()) {
            result.put("isError", false);
            ObjectNode item = content.addObject();
            item.put("type", "text");
            item.put("text", r.content() != null && r.content().has("text")
                ? r.content().get("text").asText() : "");
        } else {
            result.put("isError", true);
            ObjectNode item = content.addObject();
            item.put("type", "text");
            item.put("text", r.error() != null ? r.error() : "Tool call failed");
        }
        return result;
    }

    private ObjectNode resourcesList() {
        ObjectNode result = om.createObjectNode();
        ArrayNode resources = result.putArray("resources");
        for (McpResource r : endpoint.listResources()) {
            ObjectNode res = resources.addObject();
            res.put("uri", r.uri());
            res.put("name", r.name());
            if (r.mimeType() != null) res.put("mimeType", r.mimeType());
            if (r.description() != null) res.put("description", r.description());
        }
        return result;
    }

    private ObjectNode resourcesRead(JsonNode params) {
        if (params == null) throw new IllegalArgumentException("Missing params");
        String uri = params.path("uri").asText(null);
        if (uri == null || uri.isBlank()) throw new IllegalArgumentException("Missing uri");
        McpResourceContent c = endpoint.readResource(uri);
        ObjectNode result = om.createObjectNode();
        ArrayNode contents = result.putArray("contents");
        ObjectNode item = contents.addObject();
        item.put("uri", c.uri());
        if (c.mimeType() != null) item.put("mimeType", c.mimeType());
        item.set("text", om.valueToTree(c.content() != null ? c.content().toString() : ""));
        return result;
    }

    private ObjectNode promptsList() {
        ObjectNode result = om.createObjectNode();
        ArrayNode prompts = result.putArray("prompts");
        for (McpPrompt p : endpoint.listPrompts()) {
            ObjectNode prompt = prompts.addObject();
            prompt.put("name", p.name());
            if (p.description() != null) prompt.put("description", p.description());
            ArrayNode args = prompt.putArray("arguments");
            for (McpPromptArgument a : p.arguments()) {
                ObjectNode arg = args.addObject();
                arg.put("name", a.name());
                if (a.description() != null) arg.put("description", a.description());
                if (a.required() != null) arg.put("required", Boolean.parseBoolean(a.required()));
            }
        }
        return result;
    }

    private ObjectNode promptsGet(JsonNode params) {
        if (params == null) throw new IllegalArgumentException("Missing params");
        String name = params.path("name").asText(null);
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Missing prompt name");
        McpPromptContent c = endpoint.getPrompt(name, params.get("arguments"));
        ObjectNode result = om.createObjectNode();
        if (c.name() != null) result.put("description", c.name());
        ArrayNode messages = result.putArray("messages");
        List<McpPromptContent.Message> msgs = c.messages() != null ? c.messages() : new ArrayList<>();
        for (McpPromptContent.Message m : msgs) {
            ObjectNode msg = messages.addObject();
            msg.put("role", m.role());
            ObjectNode content = msg.putObject("content");
            content.put("type", "text");
            content.put("text", m.content());
        }
        return result;
    }

    private ObjectNode emptySchema() {
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", om.createObjectNode());
        return schema;
    }

    private ObjectNode ok(JsonNode id, ObjectNode result) {
        ObjectNode resp = om.createObjectNode();
        resp.put("jsonrpc", "2.0");
        resp.set("id", id);
        resp.set("result", result);
        return resp;
    }

    private ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode resp = om.createObjectNode();
        resp.put("jsonrpc", "2.0");
        resp.set("id", id);
        ObjectNode err = resp.putObject("error");
        err.put("code", code);
        err.put("message", message != null ? message : "");
        return resp;
    }

    /** Protocol + server metadata exposed for capabilities/handshakes. */
    public static Map<String, String> serverInfo() {
        return Map.of("name", SERVER_NAME, "version", SERVER_VERSION,
            "protocolVersion", PROTOCOL_VERSION);
    }
}
