package com.gantang.reaxon.impl.tool;

import com.gantang.reaxon.api.session.Message;
import com.gantang.reaxon.api.session.Session;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Prompt-injection boundary support.
 *
 * <p>Content fetched from outside the trust boundary (web pages, search
 * results, raw HTTP calls, mail bodies, MCP servers, sub-agent output) is
 * wrapped in explicit markers before entering conversation history, and the
 * recent untrusted window is surfaced to the policy chain so that derived
 * state-changing / exfiltration actions can be force-approved.
 *
 * <p>Three-layer defence (see {@code docs/agent-frontier-roadmap.md} P0-2):
 * <ol>
 *   <li>boundary wrapping — done at the single tool-result persistence exit;</li>
 *   <li>{@code InjectionEscalationPolicy} — escalates ASK gates while untrusted
 *       content is in recent context (auto-approval bypassed);</li>
 *   <li>parameter data-flow check — egress destinations appearing verbatim in
 *       untrusted content force approval even for read-level tools.</li>
 * </ol>
 */
public final class UntrustedContent {

    private UntrustedContent() {}

    public static final String MARKER = "untrusted_tool_output";
    public static final String META_UNTRUSTED_PRESENT = "oc.injection.recentUntrusted";
    public static final String META_UNTRUSTED_TEXT = "oc.injection.recentText";

    /** Tools whose output originates outside the trust boundary. */
    public static final Set<String> UNTRUSTED_SOURCES = Set.of(
        "web_fetch", "web_search", "http_client", "mcp_client", "email_send", "spawn_task");

    public static boolean isUntrustedSource(String toolName) {
        return toolName != null && UNTRUSTED_SOURCES.contains(toolName);
    }

    public static boolean isWrapped(String content) {
        return content != null && content.contains("<<" + MARKER);
    }

    /** Wrap external content in explicit boundary markers before it enters history. */
    public static String wrap(String toolName, Map<String, Object> args, String content) {
        String detail = sourceDetail(args);
        StringBuilder sb = new StringBuilder(content.length() + 256);
        sb.append("<<").append(MARKER)
          .append(" source=\"").append(toolName).append('"');
        if (!detail.isBlank()) {
            sb.append(" detail=\"").append(detail.replace("\"", "'")).append('"');
        }
        sb.append(" timestamp=\"").append(Instant.now()).append("\">\n");
        sb.append(content);
        if (!content.endsWith("\n")) sb.append('\n');
        sb.append("<</").append(MARKER).append(">>");
        return sb.toString();
    }

    /** Best-effort locator for what was fetched/contacted (url / query / recipient / task). */
    public static String sourceDetail(Map<String, Object> args) {
        if (args == null) return "";
        for (String k : List.of("url", "query", "tool_name", "to", "recipient", "task", "command", "subject")) {
            Object v = args.get(k);
            if (v instanceof String s && !s.isBlank()) {
                return s.length() > 160 ? s.substring(0, 160) + "..." : s;
            }
        }
        return "";
    }

    /** Result of a recent-history scan: presence flag + bounded lowercase text window. */
    public record Scan(boolean present, String text) {
        public static Scan none() { return new Scan(false, ""); }
    }

    /**
     * Scan the most recent messages for wrapped untrusted tool output.
     * Returns the presence flag and a bounded, lowercased text window used
     * for parameter data-flow containment checks. Never throws.
     */
    public static Scan scanRecent(Session session, int maxMessages, int textWindowChars) {
        if (session == null) return Scan.none();
        List<Message> recent;
        try {
            recent = session.messages().getRecent(maxMessages);
        } catch (Exception e) {
            return Scan.none();
        }
        if (recent == null || recent.isEmpty()) return Scan.none();

        boolean present = false;
        StringBuilder window = new StringBuilder();
        for (Message m : recent) {
            String c = m.content();
            if (c == null || !isWrapped(c)) continue;
            present = true;
            if (window.length() < textWindowChars) {
                window.append(c).append('\n');
            }
        }
        if (!present) return Scan.none();
        String t = window.toString();
        if (t.length() > textWindowChars) {
            t = t.substring(t.length() - textWindowChars);
        }
        return new Scan(true, t.toLowerCase());
    }
}
