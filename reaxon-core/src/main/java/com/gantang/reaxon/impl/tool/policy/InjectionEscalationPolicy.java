package com.gantang.reaxon.impl.tool.policy;

import com.gantang.reaxon.api.agent.AgentContext;
import com.gantang.reaxon.api.tool.Tool;
import com.gantang.reaxon.api.tool.policy.PolicyDecision;
import com.gantang.reaxon.api.tool.policy.RiskLevel;
import com.gantang.reaxon.api.tool.policy.ToolPolicy;
import com.gantang.reaxon.impl.tool.UntrustedContent;

import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prompt-injection escalation (defence layer 2).
 *
 * <p>When the recent conversation contains untrusted external content
 * (flagged by {@link UntrustedContent} boundary markers and surfaced via
 * turn metadata), any derived state-changing or exfiltration-capable action
 * is forced through human approval via {@link PolicyDecision#askEscalated} —
 * a decision that deployment trust, budget auto-approval and session grants
 * must NOT bypass.
 *
 * <p>When {@code ctx.metadata()} is {@code null} (metadata chain broken, e.g.
 * background/aggregated turns), the policy defaults to <b>fail-closed</b>:
 * state-changing tools are escalated as if untrusted content were present.
 * Read-only tools remain allowed.
 *
 * <p>Two trigger paths:
 * <ul>
 *   <li><b>state change</b> — WRITE / DESTRUCTIVE risk level (or a tool that
 *       statically requires approval) after reading untrusted content;</li>
 *   <li><b>data flow</b> — an egress tool (http_client / email_send /
 *       mcp_client) whose destination URL host or email address appears
 *       verbatim inside the recent untrusted text (attacker-controlled
 *       destination), even if the tool itself is read/network level.</li>
 * </ul>
 */
public final class InjectionEscalationPolicy implements ToolPolicy {

    /** Tools whose parameters can point at an attacker-chosen destination. */
    private static final Set<String> EGRESS_TOOLS = Set.of(
        "http_client", "email_send", "mcp_client");

    private static final Pattern URL_HOST = Pattern.compile("https?://([^/\\s\"'<>]+)");
    private static final Pattern EMAIL = Pattern.compile("[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}");

    @Override
    public PolicyDecision evaluate(Tool tool, Map<String, Object> params, AgentContext ctx) {
        Map<String, Object> meta = ctx != null ? ctx.metadata() : null;
        if (meta == null) {
            // Fail-closed: no metadata at all means we cannot confirm the
            // context is clean (metadata chain may be broken). Escalate
            // state-changing tools; allow read-only/safe tools.
            RiskLevel level = tool.riskLevel();
            boolean stateChanging = tool.requiresApproval()
                || level == RiskLevel.WRITE || level == RiskLevel.DESTRUCTIVE;
            if (stateChanging) {
                return PolicyDecision.askEscalated(
                    "无法确认当前会话是否包含不可信外部内容（metadata 缺失）。"
                  + "为防止提示注入，状态修改或数据外发动作必须由人工审批，"
                  + "自动批准（部署信任/预算/会话授权）对此不适用。");
            }
            return PolicyDecision.allow();
        }
        if (!Boolean.TRUE.equals(meta.get(UntrustedContent.META_UNTRUSTED_PRESENT))) {
            return PolicyDecision.allow();
        }

        RiskLevel level = tool.riskLevel();
        boolean stateChanging = tool.requiresApproval()
            || level == RiskLevel.WRITE || level == RiskLevel.DESTRUCTIVE;

        boolean dataFlow = false;
        if (EGRESS_TOOLS.contains(tool.name())) {
            Object win = meta.get(UntrustedContent.META_UNTRUSTED_TEXT);
            if (win instanceof String w && !w.isBlank()) {
                dataFlow = paramDestinationInText(params, w);
            }
        }

        if (stateChanging || dataFlow) {
            return PolicyDecision.askEscalated(
                "最近读取了不可信的外部内容（网页/搜索/HTTP/邮件/MCP/子代理输出）。"
              + "为防止提示注入，此后的状态修改或数据外发动作必须由人工审批，"
              + "自动批准（部署信任/预算/会话授权）对此不适用。");
        }
        return PolicyDecision.allow();
    }

    /** True when a URL host or email address in the params appears inside untrusted text. */
    private boolean paramDestinationInText(Map<String, Object> params, String untrustedText) {
        if (params == null) return false;
        for (Object v : params.values()) {
            if (!(v instanceof String s) || s.length() < 6) continue;
            String low = s.toLowerCase();
            Matcher url = URL_HOST.matcher(low);
            while (url.find()) {
                if (untrustedContains(untrustedText, url.group(1))) return true;
            }
            Matcher em = EMAIL.matcher(low);
            while (em.find()) {
                if (untrustedText.contains(em.group())) return true;
            }
        }
        return false;
    }

    /** Host compare tolerates a trailing dot and host appearing as part of a longer string. */
    private static boolean untrustedContains(String text, String host) {
        String h = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
        return text.contains(h);
    }
}
