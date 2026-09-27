package com.gantang.tianshu.eval.engine;

import com.gantang.tianshu.api.memory.LongTermMemory;
import com.gantang.tianshu.api.memory.MemoryItem;
import com.gantang.tianshu.api.memory.ScoredMemory;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic, embedding-free {@link LongTermMemory} for replay scenarios
 * (P1-4 LongMemEval/LoCoMo-style memory set).
 *
 * <p>Relevance is a lexical overlap score in [0,1] over character unigrams and
 * bigrams (works for both Chinese and English scripted scenarios), so the
 * memory gate's 0.30 threshold behaves without any model:
 * <pre>score = 0.6 * query-unigram coverage in text + 0.4 * bigram coverage</pre>
 *
 * <p>Validity semantics mirror the production PGVector backend: search paths
 * only return active facts ({@code valid_until IS NULL OR > now}), supersede
 * inserts a new row and stamps the old one, {@code getAll} keeps expired rows
 * for the management/audit view.
 */
public final class EvalMemoryStore implements LongTermMemory {

    public static final String DEFAULT_USER = "eval-user";

    private final Map<String, List<MemoryItem>> rows = new LinkedHashMap<>();

    public EvalMemoryStore seed(List<com.gantang.tianshu.eval.scenario.Scenario.MemorySeed> seeds) {
        Instant now = Instant.now();
        int idx = 0;
        for (var seed : seeds) {
            String user = seed.user() != null && !seed.user().isBlank() ? seed.user() : DEFAULT_USER;
            Instant validUntil = null;
            if (Boolean.TRUE.equals(seed.expired())) validUntil = now.minusSeconds(3600);
            else if (seed.validUntilHours() != null) validUntil = now.plusSeconds(seed.validUntilHours() * 3600L);
            MemoryItem item = new MemoryItem(
                "mem_seed_" + idx++,
                user,
                seed.content(),
                seed.summary() != null && !seed.summary().isBlank() ? seed.summary() : seed.content(),
                seed.tags() != null ? seed.tags() : List.of(),
                seed.importance() != null ? seed.importance() : 5,
                now, now, validUntil);
            rows.computeIfAbsent(user, u -> new ArrayList<>()).add(item);
        }
        return this;
    }

    @Override
    public Mono<Void> store(String userId, MemoryItem item) {
        List<MemoryItem> list = rows.computeIfAbsent(userId, u -> new ArrayList<>());
        list.removeIf(r -> r.id().equals(item.id()));
        list.add(item);
        return Mono.empty();
    }

    @Override
    public Mono<Void> storeBatch(String userId, List<MemoryItem> items) {
        items.forEach(i -> store(userId, i).block());
        return Mono.empty();
    }

    @Override
    public Mono<List<MemoryItem>> search(String userId, String query, int topK) {
        return searchScored(userId, query, topK)
            .map(list -> list.stream().map(ScoredMemory::item).toList());
    }

    @Override
    public Mono<List<ScoredMemory>> searchScored(String userId, String query, int topK) {
        Instant now = Instant.now();
        List<ScoredMemory> hits = new ArrayList<>();
        for (MemoryItem item : rows.getOrDefault(userId, List.of())) {
            if (item.validUntil() != null && !item.validUntil().isAfter(now)) continue;
            double score = overlap(query, haystack(item));
            if (score > 0) hits.add(new ScoredMemory(item, score));
        }
        hits.sort(Comparator.comparingDouble(ScoredMemory::score).reversed());
        return Mono.just(hits.size() <= topK ? hits : hits.subList(0, topK));
    }

    @Override
    public Mono<List<MemoryItem>> searchByKeyword(String userId, String keyword) {
        Instant now = Instant.now();
        String kw = keyword == null ? "" : keyword.toLowerCase();
        return Mono.just(rows.getOrDefault(userId, List.of()).stream()
            .filter(i -> i.validUntil() == null || i.validUntil().isAfter(now))
            .filter(i -> haystack(i).toLowerCase().contains(kw))
            .toList());
    }

    /** Includes expired/superseded rows — the management/audit view (mirrors PGVector). */
    @Override
    public Mono<List<MemoryItem>> getAll(String userId) {
        return Mono.just(List.copyOf(rows.getOrDefault(userId, List.of())));
    }

    @Override
    public Mono<Boolean> delete(String memoryId) {
        boolean[] removed = {false};
        rows.values().forEach(list -> removed[0] |= list.removeIf(i -> i.id().equals(memoryId)));
        return Mono.just(removed[0]);
    }

    @Override
    public Mono<Void> deleteAll(String userId) {
        rows.remove(userId);
        return Mono.empty();
    }

    @Override
    public Mono<Boolean> invalidate(String memoryId, String supersededById) {
        Instant now = Instant.now();
        for (List<MemoryItem> list : rows.values()) {
            for (int i = 0; i < list.size(); i++) {
                MemoryItem item = list.get(i);
                if (item.id().equals(memoryId) && item.validUntil() == null) {
                    list.set(i, item.withValidUntil(now));
                    return Mono.just(true);
                }
            }
        }
        return Mono.just(false);
    }

    @Override
    public Mono<Integer> compress(String userId) {
        return Mono.just(0);
    }

    @Override
    public Mono<List<String>> listUserIds() {
        return Mono.just(new ArrayList<>(rows.keySet()));
    }

    private static String haystack(MemoryItem item) {
        return item.content() + "\n" + (item.summary() == null ? "" : item.summary())
            + "\n" + String.join(" ", item.tags());
    }

    /**
     * Deterministic relevance: fractions of the query's character unigrams /
     * bigrams that occur in the text, weighted 0.6/0.4. Whitespace and common
     * Chinese function characters are dropped so function words do not inflate.
     */
    static double overlap(String query, String text) {
        if (query == null || query.isBlank() || text == null || text.isBlank()) return 0;
        Set<Character> qUni = unigrams(query);
        Set<String> qBi = bigrams(query);
        if (qUni.isEmpty()) return 0;
        Set<Character> tUni = unigrams(text);
        Set<String> tBi = bigrams(text);
        long uniHit = qUni.stream().filter(tUni::contains).count();
        long biHit = qBi.isEmpty() ? qUni.stream().filter(tUni::contains).count()
                                  : qBi.stream().filter(tBi::contains).count();
        double uniScore = (double) uniHit / qUni.size();
        double biDenom = qBi.isEmpty() ? qUni.size() : qBi.size();
        double biScore = (double) biHit / biDenom;
        return Math.min(1.0, 0.6 * uniScore + 0.4 * biScore);
    }

    private static final Set<Character> STOP = Set.of(
        '的', '了', '是', '我', '你', '他', '她', '它', '吗', '呢', '啊', '在', '和', '与',
        '什', '么', '怎', '样', '哪', '里', '谁', '多', '少', '会', '要', '有', '没');

    private static Set<Character> unigrams(String s) {
        Set<Character> out = new HashSet<>();
        for (char c : s.toCharArray()) {
            if (Character.isWhitespace(c) || STOP.contains(c)) continue;
            if (c == '?' || c == '？' || c == ',' || c == '，' || c == '。' || c == '.'
                || c == '!' || c == '！' || c == ':' || c == '：') continue;
            out.add(Character.toLowerCase(c));
        }
        return out;
    }

    private static Set<String> bigrams(String s) {
        List<Character> filtered = new ArrayList<>();
        for (char c : s.toCharArray()) {
            if (Character.isWhitespace(c) || STOP.contains(c)) continue;
            if (c == '?' || c == '？' || c == ',' || c == '，' || c == '。' || c == '.'
                || c == '!' || c == '！' || c == ':' || c == '：') continue;
            filtered.add(Character.toLowerCase(c));
        }
        Set<String> out = new HashSet<>();
        for (int i = 0; i < filtered.size() - 1; i++) {
            out.add("" + filtered.get(i) + filtered.get(i + 1));
        }
        return out;
    }
}
