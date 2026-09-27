package com.gantang.tianshu.impl.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gantang.tianshu.api.agent.Agent;
import com.gantang.tianshu.api.agent.AgentContext;
import com.gantang.tianshu.api.agent.AgentEvent;
import com.gantang.tianshu.api.agent.AgentResponse;
import com.gantang.tianshu.api.agent.BackgroundSpawner;
import com.gantang.tianshu.api.auth.CallerIdentity;
import com.gantang.tianshu.api.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Shared child-session plumbing used by both orchestrators (single / critique) and
 * by the aggregation pipeline.
 *
 * <p>Owns the low-level, stateless helpers that prepare a transient child session,
 * relay its events to the owner, normalise the terminal {@link AgentResponse} into a
 * presentable answer, and classify terminal failures. Holds the {@link Agent},
 * {@link SessionManager}, {@link ObjectMapper} and the {@link SubAgentEventBus} so
 * the orchestrators stay free of these concerns.
 *
 * <p>Extracted from {@code DefaultSubAgentService} (audit-2026-09-14 P1-1) with no
 * behavioural change; every method body is verbatim from the original.
 */
final class SubAgentChildPipeline {

    private static final Logger log = LoggerFactory.getLogger(SubAgentChildPipeline.class);

    /**
     * Sentinel for "no usable text answer". Matches the literal ReactiveAgent
     * historically emitted; in practice an empty/blank answer is the real signal
     * (see the isBlank() checks at every use site), so this constant is a
     * conservative extra guard rather than the primary detection path.
     */
    private static final String NO_RESULT_SENTINEL = "(no response)";

    final Agent agent;
    final SessionManager sessionManager;
    final ObjectMapper om;
    final SubAgentEventBus events;

    SubAgentChildPipeline(Agent agent, SessionManager sessionManager, ObjectMapper om,
                          SubAgentEventBus events) {
        this.agent = agent;
        this.sessionManager = sessionManager;
        this.om = om;
        this.events = events;
    }

    // ---------- session lifecycle ----------

    Mono<Void> prepareChild(String childSessionId, String childUserId,
                            int childDepth, String parent, String taskId) {
        return Mono.fromRunnable(() ->
            sessionManager.getOrCreate(childSessionId, childUserId, agent.getAgentId(),
                Map.of("kind", "subagent", "parentSessionId", parent,
                    "taskId", taskId, BackgroundSpawner.META_SPAWN_DEPTH, childDepth))).then();
    }

    /**
     * Destroy a transient sub-agent session after its run. Routing (prefix
     * {@code sub:}) takes this to the in-memory session store, so this only
     * evicts memory — no JPA/Redis rows are touched.
     */
    void cleanupChildSession(String childSessionId) {
        try {
            sessionManager.delete(childSessionId).subscribe(null,
                e -> log.warn("failed to clean up transient sub-agent session {}: {}",
                    childSessionId, e.toString()));
        } catch (Exception e) {
            log.warn("cleanup error for sub-agent session {}: {}", childSessionId, e.toString());
        }
    }

    /** Child context metadata: inherited scopes (verbatim, empty set included) + depth. */
    static Map<String, Object> childMeta(CallerIdentity caller, int childDepth) {
        Map<String, Object> meta = new HashMap<>(4);
        meta.put(BackgroundSpawner.META_SPAWN_DEPTH, childDepth);
        meta.put(CallerIdentity.META_SCOPES, caller.scopes());
        return meta;
    }

    static String newChildSessionId(String userId) {
        return BackgroundSpawner.SUBAGENT_SESSION_PREFIX + userId + ":" + UUID.randomUUID();
    }

    // ---------- event relay ----------

    /**
     * Relay child-run events the parent must see. {@code branchIndex} is 0-based
     * for the single run / answerer branches, -1 for the critic. APPROVAL_REQUIRED
     * is republished on the owner's event stream with the parent session id — the
     * only place a human can act on it before it times out.
     *
     * <p>P2-14: the {@code ti.status} flips between "awaiting_approval" and
     * "running" are <b>observational only</b> (status reporting). Critique branches
     * emit events concurrently, so two branches can race these writes; the last
     * write wins and the value is only ever read for status display, never for
     * control flow. The {@code status} field is volatile for visibility; the
     * benign race is accepted rather than adding a lock to the hot event path.
     */
    void forwardChildEvent(SubAgentTaskInfo ti, AgentEvent ev, int branchIndex) {
        if (ev == null) return;
        if (ev.type() == AgentEvent.Type.APPROVAL_REQUIRED) {
            ti.status = "awaiting_approval";
            String sid = branchIndex < 0 ? ti.criticSessionId
                : (branchIndex < ti.childSessionIds().size() ? ti.childSessionIds().get(branchIndex) : "");
            log.info("sub-agent {} awaiting approval callId={} tool={} branch={}",
                sid, ev.callId(), ev.toolName(), branchIndex);
            Map<String, Object> fields = new HashMap<>();
            fields.put("taskId", ti.taskId);
            fields.put("childSessionId", sid);
            fields.put("parentSessionId", ti.parentSessionId);
            fields.put("callId", ev.callId() != null ? ev.callId() : "");
            fields.put("toolName", ev.toolName() != null ? ev.toolName() : "");
            fields.put("description", ev.content() != null ? ev.content() : "");
            fields.put("mode", ti.mode.name().toLowerCase());
            if (branchIndex >= 0) fields.put("branch", branchIndex);
            events.emit(ti, "spawn_approval_required", fields);
        } else if ("awaiting_approval".equals(ti.status)) {
            ti.status = "running";
        }
    }

    // ---------- terminal response handling ----------

    /** Rebuild the child's {@link AgentResponse} from its terminal DONE event. */
    AgentResponse parseDone(AgentEvent e) {
        if (e.rawPayload() != null && !e.rawPayload().isBlank()) {
            try {
                return om.readValue(e.rawPayload(), AgentResponse.class);
            } catch (Exception ex) {
                log.debug("could not parse DONE payload, falling back to content: {}", ex.toString());
            }
        }
        return AgentResponse.builder()
            .content(e.content())
            .status(AgentResponse.Status.SUCCESS)
            .build();
    }

    /** Normalize a terminal response into the presentable answer text. */
    static String presentableAnswer(AgentResponse resp) {
        String raw = resp != null && resp.content() != null ? resp.content() : "";
        boolean maxIter = resp != null && resp.status() == AgentResponse.Status.MAX_ITERATIONS;
        boolean empty = raw.isBlank() || NO_RESULT_SENTINEL.equals(raw.trim());
        if (empty) {
            return "子代理在最大迭代轮次内未能产出文本结论（所有轮次可能都消耗在工具调用上，或工具结果未被模型接收）。请缩小任务范围重试，或改在主会话直接执行。";
        }
        return maxIter ? raw + "\n[子代理在达到最大迭代次数时停止]" : raw;
    }

    /**
     * Classify a terminal {@link AgentResponse} that cannot produce a usable answer.
     * Returns a human-readable failure reason, or {@code null} when the run is usable.
     * MAX_ITERATIONS counts as usable only when the model actually produced text
     * ({@link #presentableAnswer} annotates the truncation).
     */
    static String terminalFailure(AgentResponse resp) {
        if (resp == null) return "子代理未返回结果";
        if (resp.status() == AgentResponse.Status.ERROR) return "子代理执行出错: " + firstLine(resp.content());
        if (resp.status() == AgentResponse.Status.INTERRUPTED) return "子代理被中断";
        String raw = resp.content();
        if (raw == null || raw.isBlank() || NO_RESULT_SENTINEL.equals(raw.trim())) {
            return "子代理未产出文本结论（空结果）";
        }
        return null;
    }

    static String firstLine(String s) {
        if (s == null || s.isBlank()) return "未知错误";
        String line = s.strip().lines().findFirst().orElse(s.strip());
        return line.length() > 160 ? line.substring(0, 160) + "…" : line;
    }
}
