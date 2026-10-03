package com.gantang.axiflux.spring.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gantang.reaxon.api.agent.AgentContext;
import com.gantang.reaxon.api.mcp.McpEndpoint;
import com.gantang.axiflux.spring.auth.AuthWebFilter;
import com.gantang.axiflux.spring.auth.CallerGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * MCP Streamable HTTP transport endpoint.
 *
 * <p>Implements the Model Context Protocol over HTTP (2025-03-26 spec):
 * <ul>
 *   <li>{@code POST /mcp} — JSON-RPC request/response (stateless mode). Accepts
 *       {@code application/json} and responds {@code application/json}. A
 *       {@code text/event-stream} response is also valid but not required for
 *       stateless single-shot calls.</li>
 *   <li>{@code GET /mcp} — optional SSE stream; rejected with 405 in stateless mode.</li>
 * </ul>
 *
 * <p>Requires the MCP protocol header {@code Mcp-Session-Id} handling is omitted in
 * stateless mode; the server does not open long-lived sessions.
 *
 * <p>{@code tools/call} runs on behalf of the verified caller: the identity headers
 * injected by {@link AuthWebFilter} ({@code X-axiflux-User}/{@code X-axiflux-Scopes})
 * are resolved through {@link CallerGuard} and threaded into the endpoint so the tool
 * policy chain evaluates the real principal — /mcp is an identity surface (401 when
 * auth is enabled and no token is presented).
 */
@RestController
public class McpStreamableController {

    private static final Logger log = LoggerFactory.getLogger(McpStreamableController.class);
    private static final String MCP_PATH = "/mcp";

    private final McpJsonRpcHandler handler;
    private final ObjectMapper om;
    private final CallerGuard guard;

    public McpStreamableController(ObjectMapper om, McpEndpoint endpoint, CallerGuard guard) {
        this.om = om;
        this.guard = guard;
        this.handler = new McpJsonRpcHandler(om, endpoint);
    }

    @PostMapping(value = MCP_PATH,
        consumes = { MediaType.APPLICATION_JSON_VALUE, "application/json-rpc" })
    public Mono<ResponseEntity<String>> post(
            @RequestBody String body,
            @RequestHeader(value = "MCP-Protocol-Version", required = false) String proto,
            @RequestHeader(value = AuthWebFilter.H_USER, required = false) String authUser,
            @RequestHeader(value = AuthWebFilter.H_SCOPES, required = false) String authScopes) {
        return Mono.fromCallable(() -> {
            // Resolve the verified principal for tools/call. No body-supplied identity:
            // stateless MCP carries no session pinning, so the call runs in the caller's
            // per-user default session and their own scopes.
            CallerGuard.Caller caller = guard.context(authUser, authScopes, null, null);
            AgentContext ctx = AgentContext.builder()
                .sessionId(caller.sessionId())
                .userId(caller.userId())
                .metadata(caller.metadata())
                .currentQuery("MCP streamable HTTP call")
                .build();
            JsonNode requestNode = om.readTree(body);
            JsonNode response = handler.handle(requestNode, ctx);
            if (response == null) {
                // Notification accepted, no content.
                return ResponseEntity.accepted().<String>build();
            }
            return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header("MCP-Protocol-Version", McpJsonRpcHandler.PROTOCOL_VERSION)
                .body(response.toString());
        }).subscribeOn(Schedulers.boundedElastic())
          .onErrorResume(org.springframework.web.server.ResponseStatusException.class, Mono::error)
          .onErrorResume(e -> {
              log.warn("MCP request failed: {}", e.getMessage());
              ObjectNode err = om.createObjectNode();
              err.put("jsonrpc", "2.0");
              err.putNull("id");
              err.putObject("error")
                 .put("code", -32700)
                 .put("message", "Parse error");
              return Mono.just(ResponseEntity.badRequest()
                  .contentType(MediaType.APPLICATION_JSON)
                  .body(err.toString()));
          });
    }

    /**
     * GET opens the SSE stream in stateful mode. Stateless mode does not support it;
     * respond 405 so clients fall back to POST-only.
     */
    @GetMapping(MCP_PATH)
    public Mono<ResponseEntity<Void>> get() {
        return Mono.just(ResponseEntity.status(405).build());
    }
}
