package com.gantang.tianshu.impl.memory;

import com.gantang.tianshu.api.memory.ContextAssembler.Budget;
import com.gantang.tianshu.api.memory.TokenCounter;
import com.gantang.tianshu.api.session.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * Fits a conversation history into a token {@link Budget}, newest messages
 * win. Shared by the default context assembler and by the agent's
 * retrieval-unavailable fallback so both behave identically.
 *
 * <p>Truncation safety: an LLM API rejects prompts where a TOOL result has no
 * preceding assistant tool_call (or vice versa). When the cut lands between
 * an assistant tool_call message and its TOOL results we drop the orphaned
 * leading TOOL messages — the retained tail always starts at a clean
 * conversation boundary.
 */
public final class ContextBudget {

    private static final Logger log = LoggerFactory.getLogger(ContextBudget.class);

    /** Minimum history slice kept even when fixed costs already blow the budget. */
    private static final int MIN_EMERGENCY_TOKENS = 256;

    private ContextBudget() {}

    /**
     * @param prefix  messages that always stay and are never trimmed (system
     *                prompt, retrieved-memory block)
     * @param history truncatable conversation history, chronological order
     * @param suffix  message that always stays after history (current user
     *                message), or {@code null} when it is already in history
     * @return prefix + fitted history + suffix, chronological order
     */
    public static List<Message> fit(List<Message> prefix,
                                    List<Message> history,
                                    Message suffix,
                                    Budget budget,
                                    TokenCounter counter) {
        int fixed = counter.countMessages(prefix)
                  + (suffix != null ? counter.countMessage(suffix) : 0);
        int historyBudget = budget.historyBudget(fixed);

        List<Message> source = history != null ? history : List.<Message>of();
        if (historyBudget <= 0) {
            log.warn("Context budget exhausted by fixed costs ({} tokens, window {}); "
                    + "keeping minimal emergency tail", fixed, budget.contextWindowTokens());
            historyBudget = MIN_EMERGENCY_TOKENS;
        }

        // Newest-first greedy fill.
        LinkedList<Message> kept = new LinkedList<>();
        int used = 0;
        for (int i = source.size() - 1; i >= 0; i--) {
            Message m = source.get(i);
            int cost = counter.countMessage(m);
            if (used + cost > historyBudget && used > 0) break;
            used += cost;
            kept.addFirst(m);
        }

        // Drop orphaned TOOL results whose assistant tool_call was trimmed.
        int droppedOrphans = 0;
        while (!kept.isEmpty() && kept.getFirst().role() == Message.Role.TOOL) {
            kept.removeFirst();
            droppedOrphans++;
        }

        int dropped = source.size() - kept.size() - droppedOrphans;
        if (dropped > 0 || droppedOrphans > 0) {
            log.info("Context trimmed to budget: kept {} messages (~{} tokens), "
                    + "dropped {} old messages + {} orphan tool results",
                kept.size(), used, dropped, droppedOrphans);
        }

        List<Message> out = new ArrayList<>(prefix.size() + kept.size() + (suffix != null ? 1 : 0));
        out.addAll(prefix);
        out.addAll(kept);
        if (suffix != null) out.add(suffix);
        return out;
    }
}
