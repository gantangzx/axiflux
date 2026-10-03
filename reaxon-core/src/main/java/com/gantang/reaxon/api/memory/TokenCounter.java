package com.gantang.reaxon.api.memory;

import com.gantang.reaxon.api.session.Message;
import com.gantang.reaxon.api.tool.ToolCall;

/**
 * Estimates the token cost of text and messages for context-budget planning.
 *
 * <p>Implementations may be model-specific tokenizers (tiktoken/jtokkit, a
 * provider's tokenizer API) or a conservative heuristic. The default
 * {@code HeuristicTokenCounter} in {@code impl.memory} deliberately
 * over-estimates slightly: for budget protection, over-counting is safe
 * (trims a bit more history), under-counting causes provider context-window
 * errors.
 *
 * <p>Hosts embedding the SDK can supply their own counter (e.g. an exact
 * tokenizer for their model family) as a bean; the agent and context
 * assembler pick it up automatically.
 */
public interface TokenCounter {

    /** Estimated tokens for a piece of free text (null/empty → 0). */
    int countText(String text);

    /**
     * Estimated tokens for a whole chat message, including its tool-call
     * requests (arguments JSON) and a small per-message structural overhead.
     */
    default int countMessage(Message m) {
        if (m == null) return 0;
        int n = countText(m.content());
        if (m.toolCalls() != null) {
            for (ToolCall tc : m.toolCalls()) {
                n += countText(tc.toolName());
                if (tc.arguments() != null) {
                    n += countText(tc.arguments().toString());
                }
            }
        }
        if (m.attachments() != null) {
            n += m.attachments().size() * 8;
        }
        return n + MESSAGE_OVERHEAD;
    }

    /** Total estimated tokens for a list of messages. */
    default int countMessages(Iterable<Message> msgs) {
        if (msgs == null) return 0;
        int total = 0;
        for (Message m : msgs) total += countMessage(m);
        return total;
    }

    /** Per-message structural tokens (role markers, framing). */
    int MESSAGE_OVERHEAD = 4;
}
