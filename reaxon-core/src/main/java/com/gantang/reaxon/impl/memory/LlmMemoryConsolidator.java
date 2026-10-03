package com.gantang.reaxon.impl.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gantang.reaxon.api.llm.CompletionRequest;
import com.gantang.reaxon.api.llm.CompletionResponse;
import com.gantang.reaxon.api.llm.LlmClient;
import com.gantang.reaxon.api.llm.ModelRouter;
import com.gantang.reaxon.api.llm.RoutingContext;
import com.gantang.reaxon.api.memory.MemoryConsolidator;
import com.gantang.reaxon.api.memory.MemoryExtractor.ExtractedMemory;
import com.gantang.reaxon.api.memory.MemoryItem;
import com.gantang.reaxon.api.session.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;

/**
 * Resolves a candidate memory against the stored memories that are
 * semantically near it by asking a cheap LLM, in a single call, whether the
 * candidate is fully covered by them (duplicate), supersedes one of them
 * (update — which, and the merged replacement text), or states a genuinely new
 * fact. Runs off the turn path; any failure degrades to NOVEL so a classifier
 * outage can never suppress or destroy a memory.
 */
public class LlmMemoryConsolidator implements MemoryConsolidator {

    private static final Logger log = LoggerFactory.getLogger(LlmMemoryConsolidator.class);
    private static final ObjectMapper OM = new ObjectMapper();

    private final ModelRouter modelRouter;
    private final Duration timeout;

    public LlmMemoryConsolidator(ModelRouter modelRouter) {
        this(modelRouter, Duration.ofSeconds(30));
    }

    public LlmMemoryConsolidator(ModelRouter modelRouter, Duration timeout) {
        this.modelRouter = modelRouter;
        this.timeout = timeout;
    }

    @Override
    public Mono<Decision> classify(List<MemoryItem> near, ExtractedMemory candidate) {
        if (near == null || near.isEmpty()) {
            return Mono.just(Decision.novel());
        }
        return Mono.fromCallable(() -> doClassify(near, candidate))
            .subscribeOn(Schedulers.boundedElastic())
            .timeout(timeout)
            .onErrorResume(e -> {
                log.debug("Memory consolidation skipped ({}): {}", e.getClass().getSimpleName(), e.getMessage());
                return Mono.just(Decision.novel());
            });
    }

    private Decision doClassify(List<MemoryItem> near, ExtractedMemory candidate) {
        LlmClient client = modelRouter.route(RoutingContext.of(candidate.content()));
        if (client == null) return Decision.novel();

        StringBuilder existing = new StringBuilder();
        for (int i = 0; i < near.size(); i++) {
            existing.append(i + 1).append(". ").append(near.get(i).content()).append('\n');
        }
        String userPrompt = "【已有记忆】\n" + existing + "\n【新信息】\n" + candidate.content();

        CompletionRequest req = CompletionRequest.builder()
            .model(client.primaryModel())
            .addMessage(Message.system(SYSTEM_PROMPT))
            .addMessage(Message.user(userPrompt))
            .temperature(0.0)
            .maxTokens(500)
            .build();

        CompletionResponse resp = client.complete(req);
        if (resp == null || resp.content() == null || resp.content().isBlank()) {
            return Decision.novel();
        }
        return parse(resp.content(), near, candidate);
    }

    /** Defensive parse; anything unrecognised degrades to NOVEL. */
    private Decision parse(String raw, List<MemoryItem> near, ExtractedMemory candidate) {
        String json = raw.strip();
        int s = json.indexOf('{');
        int e = json.lastIndexOf('}');
        if (s < 0 || e <= s) return Decision.novel();
        try {
            JsonNode node = OM.readTree(json.substring(s, e + 1));
            String rel = node.has("relation") ? node.get("relation").asText("").trim().toLowerCase() : "";
            switch (rel) {
                case "duplicate":
                    return Decision.duplicate();
                case "update": {
                    String targetId = resolveTargetId(node, near);
                    if (targetId == null) targetId = near.get(0).id();   // default to nearest
                    String merged = text(node, "mergedContent");
                    if (merged == null || merged.isBlank()) merged = candidate.content();
                    String summary = text(node, "mergedSummary");
                    if (summary == null || summary.isBlank()) summary = candidate.summary();
                    return Decision.update(targetId, merged.trim(), summary.trim());
                }
                default:
                    return Decision.novel();
            }
        } catch (Exception ex) {
            log.debug("Consolidation JSON unparseable: {}", ex.getMessage());
            return Decision.novel();
        }
    }

    /** LLM returns targetIndex (1-based); map it to the memory id. */
    private String resolveTargetId(JsonNode node, List<MemoryItem> near) {
        if (node.has("targetIndex") && node.get("targetIndex").isNumber()) {
            int idx = node.get("targetIndex").asInt() - 1;
            if (idx >= 0 && idx < near.size()) return near.get(idx).id();
        }
        if (node.has("targetId")) {
            String tid = node.get("targetId").asText("").trim();
            if (!tid.isBlank() && near.stream().anyMatch(m -> m.id().equals(tid))) return tid;
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null ? null : v.asText("").trim();
    }

    static final String SYSTEM_PROMPT = """
        你在维护一个长期记忆库。判断【新信息】与下列编号的【已有记忆】的关系，只输出一个 JSON 对象。

        关系三选一：
        - "duplicate"：新信息里每个事实都已被某条已有记忆覆盖（可能合并了多条已有记忆的内容、\
        或只是措辞不同）。只要新信息没有超出已有记忆范围，就判 duplicate。
        - "update"：新信息与某条已有记忆同主题，但更正、覆盖或与之矛盾（如版本升级、决定改变、\
        旧值被新值替换）。此时给出被更新记忆的编号 targetIndex，和合并后的最新表述。
        - "novel"：新信息包含任意一条已有记忆都没有覆盖的全新事实。

        规则：
        1. 只有新信息确实让某条旧记忆过时/错误时才用 "update"；仅补充细节不算更新。
        2. update 时 targetIndex 填被替换记忆的编号；mergedContent 用第三人称、自包含陈述句写\
        最新状态（不要写“之前/现在”），mergedSummary 是一句话概括。
        3. 只输出 JSON，不要解释、不要 markdown。

        输出格式：
        重复：{"relation":"duplicate"}
        更新：{"relation":"update","targetIndex":1,"mergedContent":"...","mergedSummary":"..."}
        全新：{"relation":"novel"}
        """;
}
