package com.gantang.tianshu.impl.agent;

import com.gantang.tianshu.api.llm.CompletionRequest;
import com.gantang.tianshu.api.llm.CompletionResponse;
import com.gantang.tianshu.api.llm.LlmClient;
import com.gantang.tianshu.api.memory.ContextAssembler;
import com.gantang.tianshu.api.memory.TokenCounter;
import com.gantang.tianshu.api.session.Message;
import com.gantang.tianshu.api.session.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rolling conversation compaction.
 *
 * <p>Long sessions eventually exceed the model's context window. The naive
 * guard ({@code ContextBudget}) trims oldest messages and silently loses their
 * meaning; this service instead <b>summarizes</b> the older turns into one
 * rolling system entry and keeps the recent tail verbatim — mirroring the
 * upstream Tianshu compaction model. The full transcript stays in the session
 * store; compaction only changes what the model sees, tracked per session by a
 * horizon message id:
 *
 * <pre>
 *   model view  =  [ system(rolling summary) ]  +  transcript[horizonIndex … end]
 * </pre>
 *
 * <p>Two trigger paths:
 * <ul>
 *   <li><b>Proactive</b> ({@link #maybeCompact}) — before the LLM call, when the
 *       assembled history would overrun the routed model's window and enough
 *       old content exists to be worth summarizing.</li>
 *   <li><b>Overflow recovery</b> ({@link #compactForOverflow}) — after the
 *       provider returns a context-length error, summarize aggressively keeping
 *       a small tail, then the caller retries the same model.</li>
 * </ul>
 *
 * <p>The split point is tool-pair-safe: the retained tail never starts with an
 * orphan {@code TOOL} result (its assistant {@code tool_calls} would be
 * missing), which providers reject.
 */
public class CompactionService {

    private static final Logger log = LoggerFactory.getLogger(CompactionService.class);

    /** Prefix on the injected system message so a re-compaction can recognise the prior summary. */
    static final String SUMMARY_MARKER = "【对话历史摘要】";

    /** Outer scan cap — must match the agent's HISTORY_SCAN. */
    private static final int HISTORY_SCAN = 200;

    /** Floor on messages worth a compaction pass (tiny chats never need it). */
    private static final int MIN_HISTORY_TOKENS = 1_500;
    /** Floor on the older chunk; below this, truncation is cheaper than summarizing. */
    private static final int MIN_SUMMARIZABLE_TOKENS = 600;
    /** Fraction of the window kept verbatim when recovering from an overflow error. */
    private static final double OVERFLOW_KEEP_RATIO = 0.28;
    /** Fraction of the available history budget the tail targets when proactively compacting. */
    private static final double PROACTIVE_KEEP_RATIO = 0.72;
    /** Tokens reserved for the generated summary when sizing the proactive tail. */
    private static final int SUMMARY_RESERVE_TOKENS = 900;
    /** Per-message character cap when feeding transcript to the summarizer. */
    private static final int RENDER_MSG_CAP_CHARS = 1_800;

    private static final String SUMMARIZER_SYSTEM_PROMPT =
        "你是对话摘要助手。把用户与助手之间较早的对话压缩成简洁的中文摘要，供后续对话参考。\n"
        + "必须保留：关键事实与背景、做出的决定、未完成的任务/待办、重要的标识符/数字/文件名/路径、"
        + "以及工具执行得到的关键结论。按主题要点组织，不要编造对话中没有的信息，不要丢失待办事项。"
        + "摘要控制在 500 字以内。若已有一段“此前摘要”，请把它与新内容合并去重。";

    /** Per-session compaction horizon. */
    private record Horizon(String firstRetainedMsgId, String summary) {}

    private final Map<String, Horizon> horizons = new ConcurrentHashMap<>();

    /**
     * The model-facing history for a session: the rolling summary (if any)
     * followed by every transcript message at/after the horizon. Returns the
     * raw history when no compaction has run or the horizon has aged out of the
     * scan window (in which case a fresh compaction will rebuild it).
     */
    public List<Message> view(Session session) {
        List<Message> full = session.getHistory(HISTORY_SCAN);
        Horizon h = horizons.get(session.sessionId());
        if (h == null) return full;
        int idx = -1;
        for (int i = 0; i < full.size(); i++) {
            if (full.get(i).id().equals(h.firstRetainedMsgId())) { idx = i; break; }
        }
        if (idx < 0) {
            // Horizon message scrolled out of the window — the prior summary is
            // stale relative to the visible tail; drop it and let compaction rebuild.
            horizons.remove(session.sessionId());
            return full;
        }
        List<Message> out = new ArrayList<>(full.size() - idx + 1);
        out.add(Message.system(SUMMARY_MARKER + "\n" + h.summary()));
        out.addAll(full.subList(idx, full.size()));
        return out;
    }

    /** Drop any compaction state for a session (e.g. on session reset). */
    public void reset(String sessionId) {
        if (sessionId != null) horizons.remove(sessionId);
    }

    /**
     * Proactive compaction before the LLM call. Runs the summarizer only when
     * the current view would overrun the history budget by a meaningful margin.
     *
     * @return true if a compaction was performed (caller should rebuild context)
     */
    public Mono<Boolean> maybeCompact(Session session, LlmClient client,
                                      ContextAssembler.Budget budget, int systemPromptTokens,
                                      TokenCounter counter) {
        List<Message> view = view(session);
        int histTokens = counter.countMessages(view);
        if (histTokens < MIN_HISTORY_TOKENS) return Mono.just(false);

        int available = budget.historyBudget(systemPromptTokens);
        if (available <= 0 || histTokens <= (long) available * 1.15) {
            return Mono.just(false);  // fits (or nearly); normal trimming is enough
        }
        int keepTailTokens = Math.max(400, (int) (available * PROACTIVE_KEEP_RATIO) - SUMMARY_RESERVE_TOKENS);
        return compact(session, view, client, keepTailTokens, "proactive", counter);
    }

    /**
     * Aggressive compaction after a context-overflow error: keep only a small
     * recent tail sized to the routed window so the retried request fits.
     *
     * @return true if compaction produced a smaller view worth retrying
     */
    public Mono<Boolean> compactForOverflow(Session session, LlmClient client,
                                            int windowTokens, TokenCounter counter) {
        List<Message> view = view(session);
        int keepTailTokens = Math.max(300, (int) (windowTokens * OVERFLOW_KEEP_RATIO));
        return compact(session, view, client, keepTailTokens, "overflow-recovery", counter);
    }

    /**
     * Summarize {@code view[0..split)} into a rolling summary and advance the
     * session horizon to the first retained tail message.
     */
    private Mono<Boolean> compact(Session session, List<Message> view, LlmClient client,
                                  int keepTailTokens, String reason, TokenCounter counter) {
        int split = findSplit(view, keepTailTokens, counter);
        if (split <= 0 || split >= view.size()) {
            log.debug("[compaction:{}] {} skipped: no viable split (split={}, size={})",
                session.sessionId(), reason, split, view.size());
            return Mono.just(false);
        }
        List<Message> older = view.subList(0, split);
        List<Message> tail = view.subList(split, view.size());
        int olderTokens = counter.countMessages(older);
        if (olderTokens < MIN_SUMMARIZABLE_TOKENS && !"overflow-recovery".equals(reason)) {
            return Mono.just(false);
        }
        String firstRetainedId = tail.get(0).id();
        Horizon prev = horizons.get(session.sessionId());

        return summarize(client, prev, older)
            .map(summary -> {
                horizons.put(session.sessionId(), new Horizon(firstRetainedId, summary));
                log.info("[compaction:{}] {} summarized {} older messages (~{} tokens) into {} chars; "
                        + "tail starts at message {} ({} retained)",
                    session.sessionId(), reason, older.size(), olderTokens,
                    summary.length(), firstRetainedId, tail.size());
                return Boolean.TRUE;
            })
            .onErrorResume(e -> {
                log.warn("[compaction:{}] {} summarization failed: {}",
                    session.sessionId(), reason, e.toString());
                return Mono.just(Boolean.FALSE);
            });
    }

    /**
     * Find the tail start index so that the tail's token cost is around
     * {@code keepTailTokens}, then shift it to a tool-pair-safe boundary: the
     * tail must not begin with a TOOL message whose assistant tool_calls would
     * be left in the summarized (and therefore dropped-from-context) portion.
     */
    static int findSplit(List<Message> view, int keepTailTokens, TokenCounter counter) {
        int n = view.size();
        int used = 0;
        int split = n;
        for (int i = n - 1; i >= 0; i--) {
            int cost = counter.countMessage(view.get(i));
            if (used + cost > keepTailTokens && used > 0) break;
            used += cost;
            split = i;
        }
        // Tool-pair safety: never let the tail start on an orphan TOOL result;
        // pull the boundary back over the preceding assistant tool_calls.
        while (split > 0 && split < n && view.get(split).role() == Message.Role.TOOL) {
            split--;
        }
        // Keep at least one message in the older chunk worth summarizing.
        if (split < 1) split = 1;
        return split;
    }

    /** Call the model to summarize the older chunk, folding in any prior summary. */
    private Mono<String> summarize(LlmClient client, Horizon prev, List<Message> older) {
        return Mono.fromCallable(() -> {
            StringBuilder sb = new StringBuilder();
            if (prev != null && prev.summary() != null && !prev.summary().isBlank()) {
                sb.append("以下是此前对话的摘要，请合并保留其中仍有效的信息：\n")
                  .append(prev.summary()).append("\n\n---\n以下是更早的原始对话，请一并摘要：\n");
            }
            sb.append(renderTranscript(older));

            CompletionRequest req = CompletionRequest.builder()
                .messages(List.of(
                    Message.system(SUMMARIZER_SYSTEM_PROMPT),
                    Message.user(sb.toString())))
                .maxTokens(600)
                .temperature(0.2)
                .build();
            CompletionResponse resp = client.complete(req);
            String s = resp != null ? resp.content() : null;
            if (s == null || s.isBlank()) {
                return fallbackSummary(older);
            }
            return s.trim();
        }).subscribeOn(Schedulers.boundedElastic())
          .onErrorResume(e -> Mono.just(fallbackSummary(older)));
    }

    /** Deterministic shrink when the summarizer model is unavailable (still frees context). */
    private String fallbackSummary(List<Message> older) {
        StringBuilder sb = new StringBuilder();
        sb.append("（较早的 ").append(older.size()).append(" 条消息因上下文过长已省略；以下为最近对话。）");
        // Preserve at least the gist of the latest user request before the tail.
        for (int i = older.size() - 1; i >= 0; i--) {
            Message m = older.get(i);
            if (m.role() == Message.Role.USER && m.content() != null && !m.content().isBlank()) {
                String c = m.content().strip();
                sb.append(" 最后讨论：").append(c, 0, Math.min(120, c.length()));
                break;
            }
        }
        return sb.toString();
    }

    /** Render messages into a compact transcript for the summarizer. */
    private String renderTranscript(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (Message m : messages) {
            String body = m.content() == null ? "" : m.content().strip();
            if (body.startsWith(SUMMARY_MARKER)) {
                sb.append("[此前摘要] ").append(body).append('\n');
                continue;
            }
            String label = switch (m.role()) {
                case USER -> "[用户] ";
                case ASSISTANT -> "[助手] ";
                case TOOL -> "[工具结果] ";
                case SYSTEM -> "[系统] ";
            };
            if (body.length() > RENDER_MSG_CAP_CHARS) {
                body = body.substring(0, RENDER_MSG_CAP_CHARS) + "…(截断)";
            }
            sb.append(label).append(body).append('\n');
        }
        return sb.toString();
    }
}
