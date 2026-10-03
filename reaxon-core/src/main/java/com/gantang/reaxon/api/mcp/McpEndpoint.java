package com.gantang.reaxon.api.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.gantang.reaxon.api.agent.AgentContext;
import java.util.List;

/**
 * MCP protocol endpoint.
 * Implements the Model Context Protocol for exposing tools/resources/prompts to LLMs.
 */
public interface McpEndpoint {

    // --- Tools ---

    /** tools/list — return all available tools */
    List<McpTool> listTools();

    /** tools/call — invoke a tool */
    McpCallResult callTool(String toolName, JsonNode arguments);

    /**
     * tools/call on behalf of an authenticated caller. The {@link AgentContext}
     * carries the caller's userId/sessionId/scopes so the tool policy chain
     * (scope, risk, egress) evaluates the real principal instead of a system
     * identity. Default delegates to the legacy 2-arg form for backward
     * compatibility; implementations should override it.
     */
    default McpCallResult callTool(String toolName, JsonNode arguments, AgentContext caller) {
        return callTool(toolName, arguments);
    }

    // --- Resources ---

    /** resources/list */
    List<McpResource> listResources();

    /** resources/read */
    McpResourceContent readResource(String uri);

    // --- Prompts ---

    /** prompts/list */
    List<McpPrompt> listPrompts();

    /** prompts/get */
    McpPromptContent getPrompt(String name, JsonNode arguments);

    // --- Data types ---

    record McpTool(
        String name,
        String description,
        JsonNode inputSchema
    ) {}

    record McpCallResult(
        boolean success,
        JsonNode content,
        String error
    ) {}

    record McpResource(
        String uri,
        String name,
        String mimeType,
        String description
    ) {}

    record McpResourceContent(
        String uri,
        String mimeType,
        JsonNode content
    ) {}

    record McpPrompt(
        String name,
        String description,
        List<McpPromptArgument> arguments
    ) {}

    record McpPromptArgument(
        String name,
        String description,
        String required
    ) {}

    record McpPromptContent(
        String name,
        List<Message> messages
    ) {
        public record Message(String role, String content) {}
    }
}
