package com.gantang.axiflux.spring.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gantang.reaxon.api.agent.AgentContext;
import com.gantang.reaxon.api.mcp.McpEndpoint;
import com.gantang.axiflux.spring.auth.AuthWebFilter;
import com.gantang.axiflux.spring.auth.CallerGuard;
import com.gantang.axiflux.spring.web.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP (Model Context Protocol) REST endpoint.
 *
 * <pre>
 * GET  /api/v1/mcp/tools   — tools/list
 * POST /api/v1/mcp/call    — tools/call
 * </pre>
 *
 * <p>The endpoint bridges to local {@link com.gantang.reaxon.api.tool.Tool}s, so calls
 * run on behalf of the verified caller (H_USER/H_SCOPES headers → {@link CallerGuard})
 * and the tool policy chain evaluates the real principal. Blocking tool execution
 * is bridged onto boundedElastic to keep the Netty event loop free.
 */
@RestController
@RequestMapping("/api/v1/mcp")
public class McpController {

    private static final ObjectMapper OM = new ObjectMapper();

    private final McpEndpoint endpoint;
    private final CallerGuard guard;

    public McpController(McpEndpoint endpoint, CallerGuard guard) {
        this.endpoint = endpoint;
        this.guard = guard;
    }

    @GetMapping("/tools")
    public Mono<ApiResponse<Map<String, Object>>> listTools() {
        return Mono.fromCallable(() -> {
            List<McpEndpoint.McpTool> tools = endpoint.listTools();
            List<Map<String, Object>> toolList = new ArrayList<>();
            for (McpEndpoint.McpTool tool : tools) {
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("name", tool.name());
                t.put("description", tool.description());
                // Convert JsonNode to plain object to avoid Jackson POJO serialization
                t.put("inputSchema", OM.convertValue(tool.inputSchema(), Object.class));
                toolList.add(t);
            }
            return ApiResponse.ok(Map.<String, Object>of("tools", toolList));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/call")
    public Mono<ApiResponse<Map<String, Object>>> callTool(@RequestBody JsonNode body,
            @RequestHeader(value = AuthWebFilter.H_USER, required = false) String authUser,
            @RequestHeader(value = AuthWebFilter.H_SCOPES, required = false) String authScopes) {
        return Mono.fromCallable(() -> {
            String name = body.path("name").asText(null);
            if (name == null || name.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing 'name' field");
            }

            JsonNode arguments = body.path("arguments");

            // Pin the call to the verified principal (refuses a sessionId owned
            // by someone else) and thread scopes so the policy chain gates the
            // real caller. Body may carry sessionId/userId for session pinning.
            CallerGuard.Caller caller = guard.context(
                authUser, authScopes,
                body.path("userId").asText(null),
                body.path("sessionId").asText(null));
            AgentContext ctx = AgentContext.builder()
                .sessionId(caller.sessionId())
                .userId(caller.userId())
                .metadata(caller.metadata())
                .currentQuery("MCP tool call: " + name)
                .build();

            McpEndpoint.McpCallResult result = endpoint.callTool(name, arguments, ctx);

            Map<String, Object> root = new LinkedHashMap<>();
            root.put("success", result.success());
            if (result.content() != null) root.put("content", result.content());
            if (result.error() != null) root.put("error", result.error());
            return ApiResponse.ok(root);
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
