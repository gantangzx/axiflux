package com.gantang.reaxon.impl.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gantang.reaxon.api.agent.AgentContext;
import com.gantang.reaxon.api.mcp.McpEndpoint;
import com.gantang.reaxon.api.tool.Tool;
import com.gantang.reaxon.api.tool.ToolRegistry;
import com.gantang.reaxon.api.tool.ToolResult;
import com.gantang.reaxon.api.tool.policy.PolicyDecision;
import com.gantang.reaxon.api.tool.policy.ToolPolicyChain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Default {@link McpEndpoint} implementation that bridges the MCP protocol
 * to Axiflux's {@link ToolRegistry}.
 *
 * <p>Design pattern: <b>Adapter</b> — adapts the internal Tool system to the
 * MCP protocol surface. Every registered non-hidden tool becomes an MCP tool
 * automatically; no separate MCP tool registration required.
 *
 * <p>MCP calls are executed with the caller's {@link AgentContext} when the
 * transport supplies one (userId/sessionId/scopes threaded from the verified
 * auth principal); otherwise a system-level context (userId="mcp",
 * sessionId derived from the call) is used. For tools requiring approval, the
 * call returns an error — MCP is a machine-to-machine protocol and does not
 * support interactive approval.
 */
public class DefaultMcpEndpoint implements McpEndpoint {

    private static final Logger log = LoggerFactory.getLogger(DefaultMcpEndpoint.class);
    private static final ObjectMapper OM = new ObjectMapper();

    private final ToolRegistry toolRegistry;
    /** Optional policy chain; when present every MCP tool call is evaluated (ASK/DENY rejected). */
    private final ToolPolicyChain policyChain;

    public DefaultMcpEndpoint(ToolRegistry toolRegistry) {
        this(toolRegistry, null);
    }

    public DefaultMcpEndpoint(ToolRegistry toolRegistry, ToolPolicyChain policyChain) {
        this.toolRegistry = Objects.requireNonNull(toolRegistry);
        this.policyChain = policyChain;
    }

    @Override
    public List<McpTool> listTools() {
        List<McpTool> result = new ArrayList<>();
        for (Tool tool : toolRegistry.getAll()) {
            if (tool.hidden()) continue;
            result.add(new McpTool(
                tool.name(),
                tool.description(),
                tool.parameters()
            ));
        }
        return result;
    }

    @Override
    public McpCallResult callTool(String toolName, JsonNode arguments) {
        return callTool(toolName, arguments, null);
    }

    @Override
    public McpCallResult callTool(String toolName, JsonNode arguments, AgentContext caller) {
        Optional<Tool> opt = toolRegistry.get(toolName);
        if (opt.isEmpty()) {
            return new McpCallResult(false, null, "Tool not found: " + toolName);
        }

        Tool tool = opt.get();
        if (tool.requiresApproval()) {
            return new McpCallResult(false, null,
                "Tool '" + toolName + "' requires approval and cannot be called via MCP");
        }

        // Convert JsonNode arguments to Map<String, Object>
        Map<String, Object> params = OM.convertValue(
            arguments != null ? arguments : OM.createObjectNode(),
            OM.getTypeFactory().constructMapType(HashMap.class, String.class, Object.class)
        );

        // Use the authenticated caller's identity/scopes when the transport
        // provided one; fall back to the system "mcp" principal for anonymous
        // local bridges.
        AgentContext ctx = caller != null
            ? AgentContext.builder()
                .sessionId(caller.sessionId())
                .userId(caller.userId())
                .metadata(caller.metadata())
                .currentQuery("MCP tool call: " + toolName)
                .build()
            : AgentContext.builder()
                .sessionId("mcp-" + UUID.randomUUID())
                .userId("mcp")
                .currentQuery("MCP tool call: " + toolName)
                .build();

        // Evaluate the full policy chain (scope, risk level, egress/SSRF, throttle).
        // MCP is machine-to-machine with no interactive approval: ASK is rejected
        // just like DENY. With no caller identity (anonymous bridge), scope-gated
        // tools fail closed here.
        if (policyChain != null) {
            PolicyDecision decision = policyChain.evaluate(tool, params, ctx);
            if (decision.isDeny() || decision.isAsk()) {
                return new McpCallResult(false, null,
                    "Tool '" + toolName + "' is not permitted over MCP: " + decision.reason());
            }
        }

        String callId = "mcp-" + UUID.randomUUID();
        try {
            ToolResult result = tool.execute(callId, params, ctx);

            if (result.success()) {
                ObjectNode content = OM.createObjectNode();
                content.put("type", "text");
                content.put("text", result.displayContent());
                return new McpCallResult(true, content, null);
            } else {
                return new McpCallResult(false, null,
                    result.errorMessage() != null ? result.errorMessage() : "Tool execution failed");
            }
        } catch (Exception e) {
            log.warn("MCP tool call {} failed: {}", toolName, e.getMessage());
            return new McpCallResult(false, null,
                "Tool execution error: " + e.getMessage());
        }
    }

    @Override
    public List<McpResource> listResources() {
        // No built-in resources for now; extension point for future
        return List.of();
    }

    @Override
    public McpResourceContent readResource(String uri) {
        ObjectNode content = OM.createObjectNode();
        content.put("error", "Resource not found: " + uri);
        return new McpResourceContent(uri, "application/json", content);
    }

    @Override
    public List<McpPrompt> listPrompts() {
        // No built-in prompts for now; extension point
        return List.of();
    }

    @Override
    public McpPromptContent getPrompt(String name, JsonNode arguments) {
        return new McpPromptContent(name, List.of(
            new McpPromptContent.Message("user", "Prompt not found: " + name)
        ));
    }
}
