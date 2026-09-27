package com.gantang.tianshu.impl.memory;

import com.gantang.tianshu.api.agent.AgentContext;
import com.gantang.tianshu.api.memory.ContextAssembler;
import com.gantang.tianshu.api.memory.LongTermMemory;
import com.gantang.tianshu.api.memory.MemoryItem;
import com.gantang.tianshu.api.memory.ScoredMemory;
import com.gantang.tianshu.api.memory.TokenCounter;
import com.gantang.tianshu.api.session.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Default context assembler.
 *
 * Message list structure:
 *  1. System prompt (from AgentContext.systemPrompt)
 *  2. Long-term memory block (semantic retrieval)
 *  3. Short-term messages (token-budgeted sliding window, oldest trimmed first)
 *  4. Current user message
 *
 * <p>The budgeted path ({@link #assembleBudgeted}) sizes the history window
 * from the routed model's context window via {@link ContextBudget}; the
 * legacy {@link #assemble} path keeps the fixed {@code shortTermLimit} window
 * for hosts that call it directly.
 */
public class DefaultContextAssembler implements ContextAssembler {

    private static final Logger log = LoggerFactory.getLogger(DefaultContextAssembler.class);

    /** Max history messages considered by the budgeted assembly path. */
    public static final int BUDGET_SCAN_CAP = 200;

    /** Conservative default context window when the host configures nothing. */
    public static final int DEFAULT_CONTEXT_WINDOW_TOKENS = 128_000;
    /** Default completion token reservation. */
    public static final int DEFAULT_MAX_OUTPUT_TOKENS = 4_096;
    /** Default safety margin on top of all counted costs. */
    public static final int DEFAULT_RESERVE_TOKENS = 2_048;
    /**
     * Minimum cosine similarity for a vector memory hit to be injected into the
     * turn context. Weakly-related memories ("你好" pulling random facts) only
     * waste prefix tokens and bias the model; unscored hits (keyword fallback,
     * non-vector backends) always pass through.
     */
    public static final double DEFAULT_MEMORY_SCORE_THRESHOLD = 0.30;

    private final LongTermMemory longTermMemory;
    private final int shortTermLimit;
    private final int longTermLimit;
    private final TokenCounter tokenCounter;
    private volatile int contextWindowTokens = DEFAULT_CONTEXT_WINDOW_TOKENS;
    private volatile int maxOutputTokens = DEFAULT_MAX_OUTPUT_TOKENS;
    private volatile int reserveTokens = DEFAULT_RESERVE_TOKENS;
    private volatile double memoryScoreThreshold = DEFAULT_MEMORY_SCORE_THRESHOLD;

    public DefaultContextAssembler(LongTermMemory longTermMemory) {
        this(longTermMemory, DEFAULT_SHORT_TERM_LIMIT, DEFAULT_LONG_TERM_LIMIT);
    }

    public DefaultContextAssembler(LongTermMemory longTermMemory,
                                   int shortTermLimit, int longTermLimit) {
        this(longTermMemory, shortTermLimit, longTermLimit, HeuristicTokenCounter.INSTANCE);
    }

    public DefaultContextAssembler(LongTermMemory longTermMemory,
                                   int shortTermLimit, int longTermLimit,
                                   TokenCounter tokenCounter) {
        this.longTermMemory = longTermMemory;
        this.shortTermLimit  = shortTermLimit;
        this.longTermLimit   = longTermLimit;
        this.tokenCounter    = tokenCounter != null ? tokenCounter : HeuristicTokenCounter.INSTANCE;
    }

    /** Configure the token budget used by {@link #assembleBudgeted}. */
    public DefaultContextAssembler withTokenBudget(int contextWindowTokens,
                                                   int maxOutputTokens,
                                                   int reserveTokens) {
        if (contextWindowTokens > 0) this.contextWindowTokens = contextWindowTokens;
        if (maxOutputTokens > 0) this.maxOutputTokens = maxOutputTokens;
        if (reserveTokens >= 0) this.reserveTokens = reserveTokens;
        return this;
    }

    /** Minimum vector similarity (cosine, {@code [0,1]}) for injected memories. */
    public DefaultContextAssembler withMemoryScoreThreshold(double threshold) {
        if (threshold >= 0.0 && threshold < 1.0) this.memoryScoreThreshold = threshold;
        return this;
    }

    @Override
    public List<Message> assemble(
        AgentContext ctx,
        List<Message> shortTermMsgs,
        List<MemoryItem> longTermItems,
        Message currentUserMsg
    ) {
        List<Message> result = new ArrayList<>();

        // 1. System prompt
        if (ctx.systemPrompt() != null && !ctx.systemPrompt().isBlank()) {
            result.add(Message.system(ctx.systemPrompt()));
        }

        // 2. Long-term memory block
        if (longTermItems != null && !longTermItems.isEmpty()) {
            String block = buildMemoryBlock(longTermItems);
            result.add(Message.system("【相关记忆】\n" + block));
        }

        // 3. Short-term (sliding window, newest first — reverse for chronological)
        List<Message> recent = shortTermMsgs != null
            ? shortTermMsgs.stream().skip(Math.max(0, shortTermMsgs.size() - shortTermLimit)).toList()
            : List.of();
        result.addAll(recent);

        // 4. Current user message (skip if null — already in session history)
        if (currentUserMsg != null) {
            result.add(currentUserMsg);
        }

        return result;
    }

    @Override
    public Mono<List<Message>> assembleBudgeted(
        AgentContext ctx,
        List<Message> history,
        Message currentUserMsg,
        Budget budget,
        TokenCounter counter
    ) {
        TokenCounter tc = counter != null ? counter : this.tokenCounter;
        return retrieveMemory(ctx)
            .map(memoryItems -> buildBudgeted(ctx, history, currentUserMsg, memoryItems, budget, tc))
            .timeout(Duration.ofSeconds(5))
            .onErrorReturn(buildBudgeted(ctx, history, currentUserMsg, List.of(), budget, tc));
    }

    @Override
    public Mono<List<Message>> assembleWithRetrieval(
        AgentContext ctx,
        List<Message> shortTermMsgs,
        Message currentUserMsg
    ) {
        return retrieveMemory(ctx)
            .map(items -> assemble(ctx, shortTermMsgs, items, currentUserMsg))
            .timeout(Duration.ofSeconds(5))
            .onErrorReturn(assemble(ctx, shortTermMsgs, List.of(), currentUserMsg));
    }

    private Mono<List<MemoryItem>> retrieveMemory(AgentContext ctx) {
        if (longTermMemory == null) {
            return Mono.just(List.of());
        }
        return longTermMemory.searchScored(ctx.userId(), ctx.currentQuery(), longTermLimit)
            .map(scored -> {
                double min = memoryScoreThreshold;
                List<MemoryItem> kept = scored.stream()
                    .filter(s -> !s.hasScore() || s.score() >= min)
                    .map(ScoredMemory::item)
                    .toList();
                int dropped = scored.size() - kept.size();
                if (dropped > 0) {
                    log.debug("memory gate: suppressed {} hit(s) below similarity {} ", dropped, min);
                }
                return kept;
            })
            .onErrorResume(e -> longTermMemory.search(ctx.userId(), ctx.currentQuery(), longTermLimit)
                .onErrorReturn(List.of()));
    }

    private List<Message> buildBudgeted(
        AgentContext ctx,
        List<Message> history,
        Message currentUserMsg,
        List<MemoryItem> longTermItems,
        Budget budget,
        TokenCounter counter
    ) {
        List<Message> prefix = new ArrayList<>(2);
        if (ctx.systemPrompt() != null && !ctx.systemPrompt().isBlank()) {
            prefix.add(Message.system(ctx.systemPrompt()));
        }
        if (longTermItems != null && !longTermItems.isEmpty()) {
            prefix.add(Message.system("【相关记忆】\n" + buildMemoryBlock(longTermItems)));
        }

        // Outer scan cap for the budgeted path: generous (token budgeting does
        // the fine-grained trimming inside), but bounded so a huge session
        // never loads unbounded history into memory for one turn.
        List<Message> recent = history != null
            ? history.stream().skip(Math.max(0, history.size() - BUDGET_SCAN_CAP)).toList()
            : List.of();

        return ContextBudget.fit(prefix, recent, currentUserMsg, budget, counter);
    }

    /** Token budget configured for this assembler (used when the caller supplies no per-turn budget). */
    public Budget defaultBudget(int toolSchemasTokens) {
        return new Budget(contextWindowTokens, maxOutputTokens, toolSchemasTokens, reserveTokens);
    }

    private String buildMemoryBlock(List<MemoryItem> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            MemoryItem item = items.get(i);
            String content = item.summary() != null && !item.summary().isBlank()
                ? item.summary() : item.content();
            sb.append(i + 1).append(". ").append(content);
            if (item.tags() != null && !item.tags().isEmpty()) {
                sb.append(" [").append(String.join(", ", item.tags())).append("]");
            }
            sb.append("\n\n");
        }
        return sb.toString();
    }
}
