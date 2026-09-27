package com.gantang.tianshu.impl.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gantang.tianshu.api.llm.CompletionRequest;
import com.gantang.tianshu.api.llm.CompletionResponse;
import com.gantang.tianshu.api.llm.LlmClient;
import com.gantang.tianshu.api.llm.ModelRouter;
import com.gantang.tianshu.api.llm.RoutingContext;
import com.gantang.tianshu.api.memory.MemoryExtractor;
import com.gantang.tianshu.api.session.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Extracts durable memories from a turn by asking a cheap LLM for a strict JSON
 * array, then parsing it defensively.
 *
 * <p>Runs entirely off the turn's reactive path (boundedElastic) and never
 * throws: routing failure, LLM error, timeout, or unparseable output all yield
 * an empty list. Extraction is deliberately conservative via the prompt —
 * returning nothing is the expected outcome for most turns.
 */
public class LlmMemoryExtractor implements MemoryExtractor {

    private static final Logger log = LoggerFactory.getLogger(LlmMemoryExtractor.class);
    private static final ObjectMapper OM = new ObjectMapper();

    /** Hard cap on memories persisted from a single turn. */
    static final int MAX_PER_TURN = 5;

    private final ModelRouter modelRouter;
    private final int maxInputChars;
    private final int minImportance;
    private final Duration timeout;

    public LlmMemoryExtractor(ModelRouter modelRouter) {
        this(modelRouter, 6000, 4, Duration.ofSeconds(40));
    }

    public LlmMemoryExtractor(ModelRouter modelRouter, int maxInputChars,
                              int minImportance, Duration timeout) {
        this.modelRouter = modelRouter;
        this.maxInputChars = maxInputChars;
        this.minImportance = minImportance;
        this.timeout = timeout;
    }

    @Override
    public Mono<List<ExtractedMemory>> extract(MemoryCapture capture) {
        String query = capture.userQuery() == null ? "" : capture.userQuery().trim();
        String reply = capture.assistantReply() == null ? "" : capture.assistantReply().trim();
        // Trivial turns (greetings, pings) are never worth a memory.
        if (query.length() < 8 || reply.length() < 8) {
            return Mono.just(List.of());
        }
        return Mono.fromCallable(() -> doExtract(query, reply))
            .subscribeOn(Schedulers.boundedElastic())
            .timeout(timeout)
            .onErrorResume(e -> {
                log.debug("Memory extraction skipped ({}): {}", e.getClass().getSimpleName(), e.getMessage());
                return Mono.just(List.<ExtractedMemory>of());
            });
    }

    private List<ExtractedMemory> doExtract(String query, String reply) {
        LlmClient client = modelRouter.route(RoutingContext.of(query));
        if (client == null) {
            log.debug("Memory extraction skipped: no LLM client routed");
            return List.of();
        }
        String userPrompt = buildUserPrompt(query, reply);
        CompletionRequest req = CompletionRequest.builder()
            .model(client.primaryModel())
            .addMessage(Message.system(SYSTEM_PROMPT))
            .addMessage(Message.user(userPrompt))
            .temperature(0.1)   // extraction wants deterministic, factual output
            .maxTokens(800)
            .build();

        CompletionResponse resp = client.complete(req);
        if (resp == null || resp.content() == null || resp.content().isBlank()) {
            return List.of();
        }
        List<ExtractedMemory> parsed = parse(resp.content());
        log.debug("Memory extraction via {}/{} produced {} candidate(s)",
            client.provider(), client.primaryModel(), parsed.size());
        return parsed;
    }

    private String buildUserPrompt(String query, String reply) {
        String q = truncate(query, maxInputChars / 2);
        String r = truncate(reply, maxInputChars);
        return "【用户】\n" + q + "\n\n【助手】\n" + r;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /**
     * Defensive JSON parse: tolerates prose around the array, markdown code
     * fences, and trailing commas-ish model sloppiness by isolating the first
     * {@code [} … last {@code ]} span. Malformed entries are dropped individually.
     */
    List<ExtractedMemory> parse(String raw) {
        String json = raw.strip();
        int start = json.indexOf('[');
        int end = json.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return List.of();   // model returned "[]" equivalent prose with no array
        }
        try {
            JsonNode arr = OM.readTree(json.substring(start, end + 1));
            if (!arr.isArray()) return List.of();
            List<ExtractedMemory> out = new ArrayList<>();
            for (JsonNode node : arr) {
                try {
                    String content = text(node, "content");
                    if (content == null || content.isBlank()) continue;
                    String summary = text(node, "summary");
                    if (summary == null || summary.isBlank()) summary = content;
                    int importance = node.has("importance") && node.get("importance").isNumber()
                        ? node.get("importance").asInt() : 5;
                    if (importance < minImportance) continue;
                    List<String> tags = new ArrayList<>();
                    JsonNode t = node.get("tags");
                    if (t != null && t.isArray()) {
                        t.forEach(x -> { String s = x.asText("").trim(); if (!s.isEmpty()) tags.add(s); });
                    }
                    java.time.Instant validUntil = parseValidUntil(node);
                    out.add(new ExtractedMemory(content.trim(), summary.trim(), tags, importance, validUntil));
                    if (out.size() >= MAX_PER_TURN) break;
                } catch (Exception ignore) {
                    // skip one malformed entry, keep the rest
                }
            }
            return out;
        } catch (Exception e) {
            log.debug("Memory extraction JSON unparseable: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Optional fact expiry. Accepts {@code validUntilHours} (number, hours from
     * now — e.g. 72 for a fact known to be stale in three days) or an ISO-8601
     * {@code validUntil} string. Garbage/zero/negative durations are treated as
     * "no known expiry" rather than failing the entry.
     */
    private static java.time.Instant parseValidUntil(JsonNode node) {
        JsonNode hours = node.get("validUntilHours");
        if (hours != null && hours.isNumber() && hours.asDouble() > 0) {
            return java.time.Instant.now().plusSeconds((long) (hours.asDouble() * 3600));
        }
        JsonNode iso = node.get("validUntil");
        if (iso != null && iso.isTextual()) {
            try {
                return java.time.Instant.parse(iso.asText("").trim());
            } catch (Exception ignore) {
                return null;
            }
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null ? null : v.asText("").trim();
    }

    static final String SYSTEM_PROMPT = """
        你是记忆抽取器。从一轮“用户与 AI 助手的对话”中，抽取值得【跨会话长期记住】的信息：\
        用户的稳定偏好、个人或项目事实、明确做出的决定、长期约定。

        严格规则：
        1. 只抽取在未来新会话中仍然有用的信息。一次性任务细节（“帮我改这个 bug”“看下这个报错”、\
        临时操作、寒暄）一律不要抽取。
        2. 没有值得记住的内容时，必须返回空数组 []。这是最常见的情况——宁可少抽，绝不滥抽。
        3. 每条 content 用第三人称、自包含的陈述句，脱离上下文也能读懂；不要用“他/她/这个”等指代不明的词。
        4. importance 取 1-10：稳定偏好或重要决定为 8-10，一般事实为 5-7，弱信息为 1-4。
        5. tags 用小写关键词，2-4 个。
        6. 对明确会过时的事实（如“本周出差”“当前临时使用 X，下周切回”），加数字字段 
        validUntilHours 表示预计多少小时后失效；稳定事实不要加该字段。
        7. 只输出 JSON 数组，不要任何解释、不要 markdown 代码块。

        输出格式示例：
        [{"content":"用户要求项目所有服务使用 Java 25 编译，构建工具为 Maven","summary":"构建约定：Java 25 + Maven","tags":["build","java","maven"],"importance":9}]
        """;
}
