package com.gantang.tianshu.spring.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gantang.tianshu.api.agent.AgentContext;
import com.gantang.tianshu.api.tool.ToolResult;
import com.gantang.tianshu.api.tool.policy.RiskLevel;
import com.gantang.tianshu.impl.tool.support.AbstractTool;
import com.gantang.tianshu.spring.service.ScheduledTaskService;
import com.gantang.tianshu.storage.entity.ScheduledTaskEntity;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Built-in tool: let the agent create/manage scheduled tasks from conversation.
 *
 * <p>Backed directly by {@link ScheduledTaskService} (DB-persisted, restart-safe).
 * Tasks fire as {@code agentTurn}:
 * <ul>
 *   <li>{@code sessionTarget="current"} (default) — the query is sent back to the
 *       agent in the session that created the task (reminders, check-backs).</li>
 *   <li>{@code sessionTarget="isolated"} — each fire runs in a fresh ephemeral
 *       session (clean context for recurring checks); the result is routed back
 *       via {@code delivery}: {@code announce} (injected into the creating
 *       session, default), {@code webhook} (HTTP POST to deliveryUrl),
 *       or {@code none}.</li>
 * </ul>
 *
 * <p>Actions: {@code create} / {@code list} / {@code update} / {@code cancel}.
 * Schedule types: {@code cron} (5-field expr + optional IANA {@code tz}),
 * {@code periodic} ({@code intervalMs}), {@code delay} (one-shot {@code delayMs},
 * or convenience {@code minutes}/{@code hours}/{@code days}), {@code at}
 * (one-shot absolute ISO-8601 instant).
 *
 * <p>Arguments are canonicalized before use: nested {@code job}/{@code schedule}/
 * {@code payload} wrappers are flattened and common aliases / model mistakes
 * (flat params, wrong key names) are recovered automatically.
 */
public class ScheduleTaskTool extends AbstractTool {

    private static final ObjectMapper OM = new ObjectMapper();

    /** Context metadata flag: true when executing inside an isolated scheduled run (restricted grant). */
    public static final String META_RESTRICTED = "scheduledRestricted";
    /** Context metadata key: the scheduled task id whose run is executing (set by the dispatcher). */
    public static final String META_TASK_ID = "scheduledTaskId";

    private static final JsonNode SCHEMA;
    static {
        try {
            SCHEMA = OM.readTree("""
        {
          "type": "object",
          "properties": {
            "action": {
              "type": "string",
              "enum": ["create", "list", "update", "cancel"],
              "description": "create: schedule a new task; list: show your tasks; update: change an existing task (needs taskId); cancel: delete a task (needs taskId)"
            },
            "type": {
              "type": "string",
              "enum": ["cron", "periodic", "delay", "at"],
              "description": "Schedule type for create/update. cron: cron expression (with optional tz); periodic: fixed interval; delay: one-shot after a wait; at: one-shot at an absolute time"
            },
            "name": {
              "type": "string",
              "description": "Human-readable task name"
            },
            "cron": {
              "type": "string",
              "description": "Cron expression for type=cron, 5 fields 'minute hour day-of-month month day-of-week' in the tz local wall-clock time (e.g. '0 9 * * *' daily 09:00; '*/30 * * * *' every 30 min; '0 18 * * 1-5' weekdays 18:00)"
            },
            "tz": {
              "type": "string",
              "description": "IANA timezone for type=cron, e.g. 'Asia/Shanghai'. Omit to use the server timezone. Write the cron expr in local wall-clock time of this zone."
            },
            "intervalMs": {
              "type": "integer",
              "description": "Interval in milliseconds for type=periodic (e.g. 3600000 = hourly)"
            },
            "delayMs": {
              "type": "integer",
              "description": "Delay in milliseconds for type=delay (one-shot, e.g. 1200000 = in 20 minutes)"
            },
            "minutes": { "type": "number", "description": "Convenience: delay in minutes (type=delay)" },
            "hours":   { "type": "number", "description": "Convenience: delay in hours (type=delay)" },
            "days":    { "type": "number", "description": "Convenience: delay in days (type=delay)" },
            "at": {
              "type": "string",
              "description": "Absolute fire time for type=at, ISO-8601 with timezone offset, e.g. '2026-09-07T09:00:00+08:00' or '2026-09-07T01:00:00Z'. Must be in the future."
            },
            "query": {
              "type": "string",
              "description": "The instruction/message sent to the agent when the task fires. For reminders this is the reminder text; for checks this is the task to perform."
            },
            "direct": {
              "type": "boolean",
              "description": "If true, at fire time the 'query' text is posted directly into THIS conversation as a reminder WITHOUT running the model (cheaper, no AI-generated text). Use for pure reminders like 'drink water' or 'stand up'. Only valid for sessionTarget=current. Default false (run the agent)."
            },
            "sessionTarget": {
              "type": "string",
              "enum": ["current", "isolated"],
              "description": "current (default): fire in this conversation. isolated: run each fire in a fresh background session (use for recurring checks/reports that should not clutter this chat)"
            },
            "deliveryMode": {
              "type": "string",
              "enum": ["announce", "webhook", "none"],
              "description": "For sessionTarget=isolated: how to deliver the result. announce (default): post the result back into this conversation; webhook: HTTP POST to deliveryUrl; none: run silently"
            },
            "deliveryUrl": {
              "type": "string",
              "description": "Webhook URL for deliveryMode=webhook"
            },
            "enabled": {
              "type": "boolean",
              "description": "action=update: enable or disable the task"
            },
            "taskId": {
              "type": "string",
              "description": "Task id for update/cancel; get ids from action=list"
            }
          },
          "required": ["action"]
        }
        """);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final Supplier<ScheduledTaskService> tasks;

    public ScheduleTaskTool(Supplier<ScheduledTaskService> tasks) {
        this.tasks = tasks;
    }

    @Override public RiskLevel riskLevel() { return RiskLevel.WRITE; }
    @Override public String name()        { return "schedule_task"; }
    @Override public String group()       { return "builtin"; }

    @Override
    public String description() {
        return "Create, list, update or cancel scheduled tasks (reminders, periodic checks, one-shot delayed runs). "
            + "Schedule types: cron (5-field expr, optional tz like Asia/Shanghai), periodic (intervalMs), "
            + "delay (one-shot: delayMs or minutes/hours/days), at (one-shot absolute ISO-8601 time). "
            + "By default the task fires by sending 'query' back to the agent in THIS conversation "
            + "(sessionTarget=current). For recurring BACKGROUND checks use sessionTarget=isolated with "
            + "deliveryMode=announce — each run happens in a fresh session and the result is posted back here. "
            + "For a PURE reminder with no AI call, set direct=true (the reminder text is posted verbatim). "
            + "Call action=list first if you need existing task ids.";
    }

    @Override
    public JsonNode parameters() { return SCHEMA; }

    // ── Entry point ─────────────────────────────────────────────────────────

    @Override
    protected ToolResult doExecute(String callId, Params params, AgentContext context) {
        ScheduledTaskService svc = tasks.get();
        if (svc == null) {
            return ToolResult.failure(callId, "Scheduler service unavailable");
        }
        Spec spec = Spec.canonicalize(params);
        String userId = context.userId() != null ? context.userId() : "console-user";
        String sessionId = context.sessionId();

        // Restricted grant: inside an isolated scheduled run the agent may only
        // inspect/cancel its OWN task and cannot create new schedules (anti
        // self-replication / tampering; mirrors Tianshu RESTRICTED cron grant).
        Map<String, Object> meta = context.metadata() != null ? context.metadata() : Map.of();
        boolean restricted = Boolean.TRUE.equals(meta.get(META_RESTRICTED));
        String ownTaskId = meta.get(META_TASK_ID) instanceof String s && !s.isBlank() ? s : null;
        if (restricted) {
            if ("create".equals(spec.action())) {
                return ToolResult.failure(callId,
                    "Background scheduled runs cannot create new schedules (restricted grant).");
            }
            if ("update".equals(spec.action()) && (spec.taskId() == null
                || !spec.taskId().equals(ownTaskId) || spec.enabled() == null || spec.enabled())) {
                // A background run may only disable its own task, nothing else.
                return ToolResult.failure(callId,
                    "Background scheduled runs may only disable their own task (id=" + ownTaskId + ").");
            }
            if (("cancel".equals(spec.action()) || "update".equals(spec.action()))
                && (spec.taskId() == null || !spec.taskId().equals(ownTaskId))) {
                return ToolResult.failure(callId,
                    "Background scheduled runs may only manage their own task (id=" + ownTaskId + ").");
            }
        }

        try {
            return switch (spec.action()) {
                case "list"   -> listTasks(callId, svc, userId, restricted ? ownTaskId : null);
                case "cancel" -> cancelTask(callId, spec, svc, userId);
                case "update" -> updateTask(callId, spec, svc, userId);
                default       -> createTask(callId, spec, svc, userId, sessionId);
            };
        } catch (IllegalArgumentException ex) {
            return ToolResult.failure(callId, "Invalid argument: " + ex.getMessage());
        }
    }

    // ── Actions ─────────────────────────────────────────────────────────────

    private ToolResult createTask(String callId, Spec spec, ScheduledTaskService tasks,
                                  String userId, String sessionId) {
        if (spec.query() == null || spec.query().isBlank()) {
            return ToolResult.failure(callId, "Missing required parameter: query (what to do/say when the task fires)");
        }
        boolean isolated = "isolated".equals(spec.sessionTarget());
        // direct=true: post the text as a visible reminder with NO model call.
        // Always a current-session, systemEvent payload.
        boolean direct = Boolean.TRUE.equals(spec.direct());
        if (direct) isolated = false;
        if (direct && (sessionId == null || sessionId.isBlank())) {
            return ToolResult.failure(callId, "direct reminders can only be created from an active conversation session");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", direct ? "systemEvent" : "agentTurn");
        payload.put(direct ? "text" : "query", spec.query());
        if (!direct) payload.put("sessionTarget", isolated ? "isolated" : "current");
        if (isolated) {
            if (sessionId != null) payload.put("originSessionId", sessionId);
            Map<String, Object> delivery = new LinkedHashMap<>();
            delivery.put("mode", spec.deliveryMode() != null ? spec.deliveryMode() : "announce");
            if (spec.deliveryUrl() != null) delivery.put("url", spec.deliveryUrl());
            payload.put("delivery", delivery);
        }

        String name = spec.name() != null && !spec.name().isBlank() ? spec.name().trim()
            : (direct ? "提醒：" : "任务：") + spec.query().substring(0, Math.min(20, spec.query().length()));

        // isolated tasks are not bound to the creating session at the entity level;
        // the origin session is carried in payload for result delivery.
        String bindSession = isolated ? null : sessionId;

        ScheduledTaskEntity e = switch (spec.type()) {
            case "cron" -> {
                if (spec.cron() == null || spec.cron().isBlank())
                    throw new IllegalArgumentException("cron expression required for type=cron (5 fields: minute hour day-of-month month day-of-week)");
                yield tasks.createCron(name, spec.cron(), spec.tz(), payload, userId, bindSession);
            }
            case "periodic" -> {
                if (spec.intervalMs() == null || spec.intervalMs() <= 0)
                    throw new IllegalArgumentException("intervalMs required and > 0 for type=periodic");
                yield tasks.createPeriodic(name, spec.intervalMs(), payload, userId, bindSession);
            }
            case "at" -> {
                Instant at = parseAt(spec.at());
                yield tasks.createAt(name, at, payload, userId, bindSession);
            }
            default -> { // delay
                long delay = spec.delayMs() != null ? spec.delayMs() : 0L;
                if (delay <= 0)
                    throw new IllegalArgumentException("delayMs (or minutes/hours/days) required and > 0 for type=delay");
                yield tasks.createDelay(name, delay, payload, userId, bindSession);
            }
        };

        String body = "Scheduled task created.\n"
            + "- id: " + e.getId() + "\n"
            + "- name: " + e.getName() + "\n"
            + "- type: " + e.getType() + " (" + e.getSchedule()
            + (e.getTimezone() != null ? " " + e.getTimezone() : "") + ")\n"
            + (direct ? "- fires as a direct reminder (no model run): " + spec.query() + "\n"
                : isolated ? "- runs: isolated background session, result via "
                + (spec.deliveryMode() != null ? spec.deliveryMode() : "announce") + "\n"
                : "- fires by sending to this conversation: " + spec.query() + "\n")
            + (e.getNextRun() != null ? "- next run: " + e.getNextRun() : "");
        return ToolResult.success(callId, body);
    }

    private ToolResult updateTask(String callId, Spec spec, ScheduledTaskService tasks, String userId) {
        if (spec.taskId() == null || spec.taskId().isBlank()) {
            return ToolResult.failure(callId, "Missing required parameter: taskId (from action=list)");
        }
        boolean hasSchedule = spec.type() != null || spec.tz() != null;
        boolean hasChange = spec.name() != null || spec.query() != null || spec.enabled() != null || hasSchedule;
        if (!hasChange) {
            return ToolResult.failure(callId, "Nothing to update: provide one of name, query, type+cron/intervalMs/delayMs/at, tz, enabled");
        }
        ScheduledTaskService.TaskUpdate patch = new ScheduledTaskService.TaskUpdate(
            spec.name(), spec.query(), spec.tz(),
            spec.type(), spec.cron(), spec.intervalMs(), spec.delayMs(),
            spec.at() != null ? parseAt(spec.at()) : null,
            spec.enabled());
        ScheduledTaskEntity e = tasks.updateTask(spec.taskId(), userId, false, patch);
        return ToolResult.success(callId, "Task " + e.getId() + " updated.\n"
            + "- name: " + e.getName() + "\n"
            + "- type: " + e.getType() + " (" + e.getSchedule()
            + (e.getTimezone() != null ? " " + e.getTimezone() : "") + ")\n"
            + "- enabled: " + Boolean.TRUE.equals(e.getEnabled())
            + (e.getNextRun() != null ? "\n- next run: " + e.getNextRun() : ""));
    }

    private ToolResult listTasks(String callId, ScheduledTaskService tasks, String userId, String onlyTaskId) {
        List<ScheduledTaskEntity> list = tasks.listByUser(userId);
        if (onlyTaskId != null) {
            list = list.stream().filter(e -> onlyTaskId.equals(e.getId())).toList();
        }
        if (list.isEmpty()) return ToolResult.success(callId, "No scheduled tasks.");
        StringBuilder sb = new StringBuilder("Scheduled tasks (").append(list.size()).append("):\n");
        for (ScheduledTaskEntity e : list) {
            sb.append("- id=").append(e.getId())
              .append(" | ").append(e.getName())
              .append(" | ").append(e.getType()).append(" ").append(e.getSchedule())
              .append(e.getTimezone() != null ? " " + e.getTimezone() : "")
              .append(" | enabled=").append(Boolean.TRUE.equals(e.getEnabled()))
              .append(" | runs=").append(e.getRunCount())
              .append(e.getNextRun() != null ? " | next=" + e.getNextRun() : "")
              .append('\n');
        }
        return ToolResult.success(callId, sb.toString().stripTrailing());
    }

    private ToolResult cancelTask(String callId, Spec spec, ScheduledTaskService tasks, String userId) {
        if (spec.taskId() == null || spec.taskId().isBlank()) {
            return ToolResult.failure(callId, "Missing required parameter: taskId (from action=list)");
        }
        boolean ok = tasks.cancelTask(spec.taskId(), userId, false);
        return ok
            ? ToolResult.success(callId, "Task " + spec.taskId() + " cancelled.")
            : ToolResult.failure(callId, "Task not found or not owned by you: " + spec.taskId());
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static Instant parseAt(String at) {
        if (at == null || at.isBlank()) throw new IllegalArgumentException("at must be an ISO-8601 timestamp");
        try {
            Instant instant = Instant.parse(at.trim().replace(' ', 'T'));
            if (!instant.isAfter(Instant.now())) {
                throw new IllegalArgumentException("'at' must be in the future: " + at);
            }
            return instant;
        } catch (java.time.format.DateTimeParseException ex) {
            throw new IllegalArgumentException("'at' must be ISO-8601 with offset, e.g. 2026-09-07T09:00:00+08:00");
        }
    }

    /**
     * Normalized tool arguments. Flattens nested job/schedule/payload wrappers
     * and recovers the common aliases / flattened shapes models emit.
     */
    record Spec(String action, String type, String name, String query, String cron,
                Long intervalMs, Long delayMs, String at, String tz, String taskId,
                String sessionTarget, String deliveryMode, String deliveryUrl, Boolean enabled,
                Boolean direct) {

        @SuppressWarnings("unchecked")
        static Spec canonicalize(Params params) {
            // Merge sources in priority order: root > job > schedule > payload/delivery
            List<Map<String, Object>> sources = new ArrayList<>();
            Map<String, Object> root = new LinkedHashMap<>();
            for (String k : List.of("action", "type", "name", "query", "cron", "tz", "timezone",
                    "intervalMs", "delayMs", "at", "taskId", "id", "jobId",
                    "sessionTarget", "target", "deliveryMode", "mode", "deliveryUrl", "webhookUrl",
                    "url", "enabled", "minutes", "hours", "days", "seconds",
                    "expr", "cronExpr", "expression", "message", "text", "reminder", "prompt",
                    "title", "taskName", "everyMs", "direct", "raw", "simpleReminder", "noAgent")) {
                Object v = params.get(k);
                if (v != null) root.put(k, v);
            }
            sources.add(root);
            addIfMap(sources, params.getMap("job"));
            addIfMap(sources, params.getMap("schedule"));
            addIfMap(sources, params.getMap("payload"));
            addIfMap(sources, params.getMap("delivery"));

            String action = normAction(str(sources, "action"));
            String type = normType(str(sources, "type", "kind", "scheduleKind"));

            // Field aliases
            String cron = str(sources, "cron", "cronExpr", "expr", "expression");
            String tz = str(sources, "tz", "timezone");
            String query = str(sources, "query", "message", "text", "reminder", "prompt");
            String name = str(sources, "name", "title", "taskName");
            String taskId = str(sources, "taskId", "id", "jobId");
            String at = str(sources, "at", "when", "time");
            String sessionTarget = normTarget(str(sources, "sessionTarget", "target"));
            String deliveryMode = str(sources, "deliveryMode", "mode");
            String deliveryUrl = str(sources, "deliveryUrl", "webhookUrl", "url");
            Boolean enabled = bool(sources, "enabled");
            Boolean direct = bool(sources, "direct", "raw", "simpleReminder", "noAgent");
            // Explicit payload kind=systemEvent also means a direct reminder.
            if (direct == null) {
                String payloadKind = str(sources, "payloadKind", "event");
                if (payloadKind != null && payloadKind.toLowerCase(Locale.ROOT).contains("system")) direct = true;
            }

            Long intervalMs = lng(sources, "intervalMs", "everyMs", "periodMs");
            Long delayMs = lng(sources, "delayMs", "delay");
            Long minutes = lng(sources, "minutes", "minute", "mins", "min");
            Long hours = lng(sources, "hours", "hour", "hr");
            Long days = lng(sources, "days", "day");
            Long seconds = lng(sources, "seconds", "second", "secs", "sec");
            if (delayMs == null) {
                long ms = 0L;
                if (days != null) ms += days * ChronoUnit.DAYS.getDuration().toMillis();
                if (hours != null) ms += hours * ChronoUnit.HOURS.getDuration().toMillis();
                if (minutes != null) ms += minutes * ChronoUnit.MINUTES.getDuration().toMillis();
                if (seconds != null) ms += seconds * ChronoUnit.SECONDS.getDuration().toMillis();
                if (ms > 0) delayMs = ms;
            }

            // Infer type from the time fields when the model didn't state it
            if (type == null) {
                if (at != null) type = "at";
                else if (cron != null) type = "cron";
                else if (intervalMs != null) type = "periodic";
                else if (delayMs != null) type = "delay";
            }

            // webhook mode implies a url is needed; normalise mode values
            if (deliveryMode != null) {
                deliveryMode = switch (deliveryMode.toLowerCase(Locale.ROOT)) {
                    case "announce", "notify", "message", "chat" -> "announce";
                    case "webhook", "http", "post" -> "webhook";
                    case "none", "silent", "off" -> "none";
                    default -> deliveryMode;
                };
            }
            if ("webhook".equals(deliveryMode) && deliveryUrl == null) {
                throw new IllegalArgumentException("deliveryMode=webhook requires deliveryUrl");
            }

            return new Spec(action, type, name, query, cron, intervalMs, delayMs, at, tz,
                taskId, sessionTarget, deliveryMode, deliveryUrl, enabled, direct);
        }

        private static void addIfMap(List<Map<String, Object>> sources, Map<String, Object> m) {
            if (m != null && !m.isEmpty()) sources.add(m);
        }

        private static String str(List<Map<String, Object>> sources, String... keys) {
            for (Map<String, Object> m : sources) {
                for (String k : keys) {
                    Object v = m.get(k);
                    if (v != null && !String.valueOf(v).isBlank()) return String.valueOf(v).trim();
                }
            }
            return null;
        }

        private static Long lng(List<Map<String, Object>> sources, String... keys) {
            for (Map<String, Object> m : sources) {
                for (String k : keys) {
                    Object v = m.get(k);
                    if (v instanceof Number n) return n.longValue();
                    if (v instanceof String s && !s.isBlank()) {
                        try { return (long) Double.parseDouble(s.trim()); } catch (NumberFormatException ignored) {}
                    }
                }
            }
            return null;
        }

        private static Boolean bool(List<Map<String, Object>> sources, String... keys) {
            for (Map<String, Object> m : sources) {
                for (String k : keys) {
                    Object v = m.get(k);
                    if (v instanceof Boolean b) return b;
                    if (v instanceof String s && !s.isBlank())
                        return Boolean.parseBoolean(s.trim());
                }
            }
            return null;
        }

        private static String normAction(String raw) {
            if (raw == null) return "create";
            return switch (raw.toLowerCase(Locale.ROOT)) {
                case "list", "ls", "status", "get" -> "list";
                case "cancel", "remove", "delete", "rm", "del" -> "cancel";
                case "update", "edit", "modify", "patch", "change" -> "update";
                default -> "create"; // create/add/new
            };
        }

        private static String normType(String raw) {
            if (raw == null) return null;
            return switch (raw.toLowerCase(Locale.ROOT)) {
                case "cron", "cronexpr", "schedule" -> "cron";
                case "periodic", "every", "interval", "recurring" -> "periodic";
                case "at", "once-at", "date", "absolute" -> "at";
                case "delay", "remind", "reminder", "once", "in", "timer", "wait" -> "delay";
                default -> null;
            };
        }

        private static String normTarget(String raw) {
            if (raw == null) return "current";
            return switch (raw.toLowerCase(Locale.ROOT)) {
                case "isolated", "isolate", "background", "ephemeral", "new" -> "isolated";
                default -> "current";
            };
        }
    }
}
