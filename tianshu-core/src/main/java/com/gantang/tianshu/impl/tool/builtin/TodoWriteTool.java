package com.gantang.tianshu.impl.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.gantang.tianshu.api.agent.AgentContext;
import com.gantang.tianshu.api.session.Session;
import com.gantang.tianshu.api.session.SessionManager;
import com.gantang.tianshu.api.tool.Tool;
import com.gantang.tianshu.api.tool.ToolResult;
import com.gantang.tianshu.api.tool.policy.RiskLevel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code todo_write}: structured task plan for multi-step work.
 *
 * <p>Context-engineering staple (Claude Code todoWrite / hosted-agent todo
 * lists): for tasks with &ge;3 steps the model keeps an explicit, visible
 * checklist. The tool receives the <b>full</b> list every call (replacement
 * semantics), persists it in session metadata so it survives compaction and
 * restarts, and echoes a markdown checklist back into context. The agent
 * injects the current list into the system prompt every turn
 * ({@code ReactiveAgent#appendTodoPlan}).
 *
 * <p>State is per-session and user-visible only; no external side effects, so
 * the tool is {@link RiskLevel#SAFE} and never approval-gated.
 */
public final class TodoWriteTool implements Tool {

    /** Session metadata key holding {@code List<Map<String,Object>>} todos. */
    public static final String META_TODO_KEY = "oc.todo.list";

    private static final Set<String> STATUSES = Set.of("pending", "in_progress", "completed");

    private final SessionManager sessions;

    public TodoWriteTool(SessionManager sessions) {
        this.sessions = sessions;
    }

    @Override public String name() { return "todo_write"; }
    @Override public String group() { return "builtin"; }
    @Override public RiskLevel riskLevel() { return RiskLevel.SAFE; }
    @Override public boolean requiresApproval() { return false; }

    @Override
    public String description() {
        return "Maintain a structured task plan for multi-step work (3+ steps). Pass the FULL todo list "
            + "every call — it replaces the previous list. Each item: {content (what), status "
            + "(pending|in_progress|completed), activeForm (present-participle label shown while in "
            + "progress, e.g. 'Reading config files')}. Keep exactly one item in_progress at a time; "
            + "mark items completed as soon as they are done; call this whenever the plan changes or a "
            + "step completes. The list persists across turns and is shown back to you automatically.";
    }

    private static final JsonNode SCHEMA = SchemaSupport.parse("""
        {
          "type": "object",
          "properties": {
            "todos": {
              "type": "array",
              "description": "Complete task list; replaces any previous list. Send the FULL list every call.",
              "items": {
                "type": "object",
                "properties": {
                  "content":  { "type": "string", "description": "Concrete task description." },
                  "status":   { "type": "string", "enum": ["pending", "in_progress", "completed"] },
                  "activeForm": { "type": "string", "description": "Present-participle label while in progress." }
                },
                "required": ["content", "status"]
              }
            }
          },
          "required": ["todos"]
        }
        """);

    @Override
    public JsonNode parameters() { return SCHEMA; }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult execute(String callId, Map<String, Object> params, AgentContext ctx) {
        Object raw = params == null ? null : params.get("todos");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return ToolResult.failure(callId, "todos must be a non-empty array of {content, status} objects");
        }

        List<Map<String, Object>> todos = new ArrayList<>();
        int inProgress = 0;
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (!(item instanceof Map<?, ?> m)) {
                return ToolResult.failure(callId, "todos[" + i + "] must be an object");
            }
            Object content = m.get("content");
            if (content == null || content.toString().isBlank()) {
                return ToolResult.failure(callId, "todos[" + i + "].content is required");
            }
            String status = m.get("status") == null ? "pending" : m.get("status").toString().trim();
            if (!STATUSES.contains(status)) {
                return ToolResult.failure(callId,
                    "todos[" + i + "].status must be one of " + STATUSES + " but was: " + status);
            }
            if ("in_progress".equals(status)) inProgress++;

            Map<String, Object> t = new LinkedHashMap<>();
            t.put("content", content.toString().trim());
            t.put("status", status);
            Object active = m.get("activeForm");
            if (active != null && !active.toString().isBlank()) {
                t.put("activeForm", active.toString().trim());
            }
            todos.add(t);
        }
        if (inProgress > 1) {
            // Not fatal — surface as guidance but still persist.
            return persistAndRender(callId, ctx, todos,
                "提示：同一时间应只有一个 in_progress 任务，请尽快更新清单。");
        }
        return persistAndRender(callId, ctx, todos, null);
    }

    private ToolResult persistAndRender(String callId, AgentContext ctx,
                                        List<Map<String, Object>> todos, String warning) {
        Session session = sessions != null ? sessions.get(ctx.sessionId()).orElse(null) : null;
        if (session == null) {
            return ToolResult.failure(callId, "session not found: " + ctx.sessionId());
        }
        session.updateMetadata(META_TODO_KEY, todos);
        try {
            sessions.save(session).subscribe(null,
                e -> org.slf4j.LoggerFactory.getLogger(TodoWriteTool.class)
                    .warn("todo persist failed for session {}: {}", ctx.sessionId(), e.toString()));
        } catch (Exception e) {
            // Metadata is updated in-memory regardless; persistence is best-effort here.
        }
        String body = render(todos);
        if (warning != null) body = body + "\n" + warning;
        return ToolResult.success(callId, body);
    }

    /** Render the checklist as markdown (also injected into the system prompt). */
    @SuppressWarnings("unchecked")
    public static String render(List<Map<String, Object>> todos) {
        long done = todos.stream().filter(t -> "completed".equals(t.get("status"))).count();
        StringBuilder sb = new StringBuilder();
        sb.append("任务计划（").append(done).append('/').append(todos.size()).append(" 完成）：\n");
        for (Map<String, Object> t : todos) {
            String status = String.valueOf(t.get("status"));
            String box = switch (status) {
                case "completed" -> "[x]";
                case "in_progress" -> "[~]";
                default -> "[ ]";
            };
            sb.append("- ").append(box).append(' ').append(t.get("content"));
            if ("in_progress".equals(status) && t.get("activeForm") != null) {
                sb.append("  ← 进行中：").append(t.get("activeForm"));
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
