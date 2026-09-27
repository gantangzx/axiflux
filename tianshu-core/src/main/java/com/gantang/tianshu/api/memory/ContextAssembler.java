package com.gantang.tianshu.api.memory;

import com.gantang.tianshu.api.agent.AgentContext;
import com.gantang.tianshu.api.session.Message;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Assembles the full message list for LLM:
 *   [system_prompt] + [retrieved long-term memories] + [recent short-term messages] + [current user message]
 */
public interface ContextAssembler {

    int DEFAULT_SHORT_TERM_LIMIT = 50;
    int DEFAULT_LONG_TERM_LIMIT = 5;

    /**
     * Build the complete message list for LLM.
     */
    List<Message> assemble(
        AgentContext ctx,
        List<Message> shortTermMsgs,
        List<MemoryItem> longTermItems,
        Message currentUserMsg
    );

    /**
     * Convenience: assemble with auto-retrieval of long-term memory.
     * Default implementation requires a LongTermMemory — override if you have a custom retrieval strategy.
     */
    default Mono<List<Message>> assembleWithRetrieval(
        AgentContext ctx,
        List<Message> shortTermMsgs,
        Message currentUserMsg
    ) {
        return Mono.just(assemble(ctx, shortTermMsgs, List.of(), currentUserMsg));
    }

    /**
     * Token budget for a single LLM turn. The assembler must keep the total
     * prompt size within {@code contextWindowTokens - maxOutputTokens},
     * accounting for tool JSON schemas, system prompt, retrieved memory and
     * a safety reserve.
     *
     * @param contextWindowTokens the routed model's full context window
     * @param maxOutputTokens     completion tokens reserved for the answer
     * @param toolSchemasTokens   estimated tokens of the tool-definition JSON array
     * @param reserveTokens       safety margin (message framing, router variance)
     */
    record Budget(int contextWindowTokens, int maxOutputTokens,
                  int toolSchemasTokens, int reserveTokens) {
        public Budget {
            if (contextWindowTokens < 1) contextWindowTokens = 1;
            if (maxOutputTokens < 0) maxOutputTokens = 0;
            if (toolSchemasTokens < 0) toolSchemasTokens = 0;
            if (reserveTokens < 0) reserveTokens = 0;
        }

        /** Tokens available for history after fixed costs; floor of 0. */
        public int historyBudget(int fixedTokens) {
            return Math.max(0, contextWindowTokens - maxOutputTokens
                - toolSchemasTokens - reserveTokens - fixedTokens);
        }
    }

    /**
     * Assemble the turn's messages <b>within a token budget</b>. History is
     * truncated oldest-first when over budget; the system prompt, retrieved
     * memory and the current user message are always retained.
     *
     * <p>Default implementation ignores the budget (legacy behaviour). The
     * SDK's {@code DefaultContextAssembler} implements budget-aware trimming.
     */
    default Mono<List<Message>> assembleBudgeted(
        AgentContext ctx,
        List<Message> history,
        Message currentUserMsg,
        Budget budget,
        TokenCounter counter
    ) {
        return assembleWithRetrieval(ctx, history, currentUserMsg);
    }
}
