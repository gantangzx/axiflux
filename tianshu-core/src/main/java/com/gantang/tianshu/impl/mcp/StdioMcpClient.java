package com.gantang.tianshu.impl.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gantang.tianshu.api.mcp.McpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MCP client using stdio transport (spawns a child process).
 *
 * <p>Communicates via JSON-RPC 2.0 over the child process's stdin/stdout.
 * Each request gets a unique incrementing ID; responses are matched by ID.
 *
 * <p>Design pattern: <b>Adapter</b> — adapts JSON-RPC wire protocol to {@link McpClient}.
 */
public class StdioMcpClient implements McpClient {

    private static final Logger log = LoggerFactory.getLogger(StdioMcpClient.class);
    private static final ObjectMapper OM = new ObjectMapper();
    private static final String PROTOCOL_VERSION = "2024-11-05";

    private final String command;
    private final List<String> args;
    private final Map<String, String> env;

    private Process process;
    private BufferedWriter writer;
    private BufferedReader reader;
    private final AtomicLong requestId = new AtomicLong(0);
    private volatile boolean connected = false;
    private volatile String serverName = "unknown";

    public StdioMcpClient(String command, List<String> args, Map<String, String> env) {
        this.command = Objects.requireNonNull(command);
        this.args = args != null ? args : List.of();
        this.env = env != null ? env : Map.of();
    }

    @Override
    public void initialize() {
        try {
            ProcessBuilder pb = new ProcessBuilder();
            List<String> cmd = new ArrayList<>();
            cmd.add(command);
            cmd.addAll(args);
            pb.command(cmd);
            pb.redirectErrorStream(false);
            pb.environment().putAll(env);

            process = pb.start();
            writer = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

            // Read stderr in daemon thread for debugging
            Thread errReader = new Thread(() -> {
                try (BufferedReader err = new BufferedReader(
                        new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = err.readLine()) != null) {
                        log.debug("[MCP {}] stderr: {}", command, line);
                    }
                } catch (IOException ignored) {}
            }, "mcp-stderr-" + command);
            errReader.setDaemon(true);
            errReader.start();

            // JSON-RPC initialize handshake
            ObjectNode initParams = OM.createObjectNode();
            initParams.put("protocolVersion", PROTOCOL_VERSION);
            initParams.set("capabilities", OM.createObjectNode());
            ObjectNode clientInfo = OM.createObjectNode();
            clientInfo.put("name", "tianshu-agent");
            clientInfo.put("version", "0.1.0");
            initParams.set("clientInfo", clientInfo);

            JsonNode initResult = sendRequest("initialize", initParams);

            if (initResult != null && initResult.has("serverInfo")) {
                serverName = initResult.get("serverInfo").path("name").asText("unknown");
            }

            // Send initialized notification
            sendNotification("notifications/initialized", OM.createObjectNode());
            connected = true;
            log.info("MCP server connected: {}", serverName);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize MCP client: " + command, e);
        }
    }

    @Override
    public List<RemoteTool> listTools() {
        ensureConnected();
        try {
            JsonNode result = sendRequest("tools/list", OM.createObjectNode());
            if (result == null || !result.has("tools")) return List.of();

            List<RemoteTool> tools = new ArrayList<>();
            for (JsonNode tool : result.get("tools")) {
                tools.add(new RemoteTool(
                    tool.path("name").asText(),
                    tool.path("description").asText(""),
                    tool.path("inputSchema").deepCopy()
                ));
            }
            return tools;
        } catch (Exception e) {
            log.warn("Failed to list MCP tools: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public ToolCallResult callTool(String toolName, Map<String, Object> arguments) {
        ensureConnected();
        try {
            ObjectNode params = OM.createObjectNode();
            params.put("name", toolName);
            params.set("arguments", OM.valueToTree(arguments != null ? arguments : Map.of()));

            JsonNode result = sendRequest("tools/call", params);
            if (result == null) {
                return ToolCallResult.error("Empty response from MCP server");
            }

            boolean isError = result.path("isError").asBoolean(false);
            List<ToolCallResult.ContentBlock> blocks = new ArrayList<>();
            StringBuilder text = new StringBuilder();

            if (result.has("content")) {
                for (JsonNode block : result.get("content")) {
                    String type = block.path("type").asText("text");
                    String blockText = block.path("text").asText("");
                    blocks.add(new ToolCallResult.ContentBlock(type, blockText));
                    if (!blockText.isEmpty()) {
                        if (text.length() > 0) text.append('\n');
                        text.append(blockText);
                    }
                }
            }

            return new ToolCallResult(isError, text.toString(), blocks);
        } catch (Exception e) {
            return ToolCallResult.error("MCP call failed: " + e.getMessage());
        }
    }

    @Override public boolean isConnected() { return connected; }
    @Override public String serverName() { return serverName; }

    @Override
    public void close() {
        connected = false;
        try { if (writer != null) writer.close(); } catch (Exception ignored) {}
        if (process != null) process.destroyForcibly();
    }

    // ─── JSON-RPC internals ────────────────────────────────────────────

    private void ensureConnected() {
        if (!connected) throw new IllegalStateException("MCP client not connected. Call initialize() first.");
    }

    private synchronized JsonNode sendRequest(String method, ObjectNode params) throws IOException {
        long id = requestId.incrementAndGet();
        ObjectNode req = OM.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", id);
        req.put("method", method);
        req.set("params", params);

        String json = OM.writeValueAsString(req);
        log.debug("MCP -> {} (id={}): {}", method, id, json);
        writer.write(json);
        writer.newLine();
        writer.flush();

        while (true) {
            String line = reader.readLine();
            if (line == null) throw new IOException("MCP process stdout closed");
            if (line.isBlank()) continue;

            JsonNode resp;
            try {
                resp = OM.readTree(line);
            } catch (Exception e) {
                log.warn("MCP non-JSON line: {}", line);
                continue;
            }

            if (!resp.has("id")) continue; // skip notifications

            long respId = resp.path("id").asLong(-1);
            if (respId != id) continue;

            if (resp.has("error")) {
                JsonNode err = resp.get("error");
                throw new IOException("MCP error " + err.path("code").asInt()
                    + ": " + err.path("message").asText());
            }

            return resp.get("result");
        }
    }

    private synchronized void sendNotification(String method, ObjectNode params) throws IOException {
        ObjectNode notif = OM.createObjectNode();
        notif.put("jsonrpc", "2.0");
        notif.put("method", method);
        notif.set("params", params);
        writer.write(OM.writeValueAsString(notif));
        writer.newLine();
        writer.flush();
    }
}
