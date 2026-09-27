package com.gantang.tianshu.impl.session;

import com.gantang.tianshu.api.agent.AgentContext;
import com.gantang.tianshu.api.agent.AgentHook;
import com.gantang.tianshu.api.session.Message;
import com.gantang.tianshu.api.session.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Names a conversation after its first user message, the way chat consoles do:
 * a session list of raw ids tells the user nothing about what is inside.
 *
 * <p>Runs at {@link AgentHook#onTurnStart}, which fires <em>after</em> the driving
 * user message was persisted and before the model is called — so the console's
 * post-turn session refresh already sees the name, and a turn that later fails
 * still leaves a readable label.
 *
 * <p>Only ever the first turn is considered (a session whose whole history is
 * that one user message): re-deriving from the newest question would relabel the
 * conversation on every message. An existing name — including one the user set
 * through the rename API, which writes the same {@link #META_TITLE} key — is
 * never overwritten.
 *
 * <p>Sub-agent child sessions are skipped: they are invisible to the user
 * (routed to a transient store), so naming them is pure write amplification.
 *
 * <p>Purely decorative, so every failure is swallowed, per the {@link AgentHook}
 * contract that a hook can never fail a turn.
 */
public class SessionTitleHook implements AgentHook {

    private static final Logger log = LoggerFactory.getLogger(SessionTitleHook.class);

    /**
     * Session metadata key holding the display name. Shared with the rename API
     * ({@code PATCH /api/v1/sessions/{id}}) — both write the same slot, which is
     * what makes "user rename wins" structural instead of a race to check.
     */
    public static final String META_TITLE = "title";

    /** Appended when the question is longer than the limit. */
    static final String ELLIPSIS = "…";

    /** Default name length — roughly one sidebar line. */
    public static final int DEFAULT_MAX_LENGTH = 60;

    /** A shorter cap would produce "帮我查一…", which names nothing. */
    private static final int MIN_MAX_LENGTH = 8;

    /** Tail size inspected on turn start: one message means the session is new. */
    private static final int FIRST_TURN_WINDOW = 2;

    private final int maxLength;

    public SessionTitleHook() {
        this(DEFAULT_MAX_LENGTH);
    }

    public SessionTitleHook(int maxLength) {
        this.maxLength = Math.max(MIN_MAX_LENGTH, maxLength);
    }

    @Override
    public int order() {
        // Self-contained and allocation-light: run before the other turn-start hooks.
        return 30;
    }

    @Override
    public void onTurnStart(AgentContext context, Session session) {
        try {
            if (session == null || hasTitle(session)) return;

            Map<String, Object> meta = session.metadata();
            if (meta != null && RoutingSessionManager.KIND_SUBAGENT.equals(
                    meta.get(RoutingSessionManager.META_KIND))) {
                return;
            }

            List<Message> history = session.getHistory(FIRST_TURN_WINDOW);
            if (history == null || history.size() != 1) return;

            Message driving = history.get(0);
            if (driving.role() != Message.Role.USER) return;

            // An attachment-only turn has nothing to name the session after; it
            // keeps the id fallback rather than being labelled " ".
            String title = deriveTitle(driving.content(), maxLength);
            if (title.isEmpty()) return;

            session.updateMetadata(META_TITLE, title);
        } catch (Exception e) {
            log.debug("session title skipped for {}: {}",
                context != null ? context.sessionId() : null, e.toString());
        }
    }

    /** True when a non-blank name is already set (user rename, channel, another hook). */
    private static boolean hasTitle(Session session) {
        Map<String, Object> meta = session.metadata();
        Object existing = meta != null ? meta.get(META_TITLE) : null;
        return existing != null && !existing.toString().isBlank();
    }

    /**
     * The question as a single-line name: whitespace runs (newlines from pasted
     * text and code) collapse to one space, and an over-long question is cut on a
     * code-point boundary so a trailing emoji cannot end up as half a surrogate
     * pair.
     */
    static String deriveTitle(String raw, int max) {
        if (raw == null) return "";
        String flat = raw.replaceAll("\\s+", " ").trim();
        if (flat.isEmpty()) return "";
        if (flat.codePointCount(0, flat.length()) <= max) return flat;
        String cut = flat.substring(0, flat.offsetByCodePoints(0, max)).stripTrailing();
        return cut.isEmpty() ? "" : cut + ELLIPSIS;
    }
}
