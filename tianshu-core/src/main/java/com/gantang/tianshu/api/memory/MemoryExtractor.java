package com.gantang.tianshu.api.memory;

import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Extracts durable, cross-session memories from a finished agent turn.
 *
 * <p>This is the <b>write path</b> of long-term memory: given what the user
 * asked and what the agent answered, decide which facts / preferences /
 * decisions are worth remembering in future sessions. Implementations must be
 * conservative — a one-off task detail is not a memory; a stable preference
 * or project convention is. Returning an empty list is the common case.
 *
 * <p>Implementations must never throw into the calling turn: on any failure
 * they should return an empty list (memory is an enhancement, never a
 * dependency of the conversation).
 */
public interface MemoryExtractor {

    /**
     * Analyze one turn and return the memories worth persisting.
     *
     * @param capture the finished turn (user query + assistant reply)
     * @return extracted memories; empty list when nothing is worth remembering
     */
    Mono<List<ExtractedMemory>> extract(MemoryCapture capture);

    /**
     * A candidate memory produced by extraction, before dedup and persistence.
     *
     * @param content    the fact in self-contained prose (third person, no pronouns
     *                   that lose meaning out of context)
     * @param summary    one-line gist shown when the memory is injected into context
     * @param tags       short retrieval tags (lowercase keywords)
     * @param importance 1-10; higher = more durable / worth compressing
     */
    record ExtractedMemory(String content, String summary, List<String> tags, int importance,
                           java.time.Instant validUntil) {
        /** Back-compat: durable memory with no known expiry. */
        public ExtractedMemory(String content, String summary, List<String> tags, int importance) {
            this(content, summary, tags, importance, null);
        }
        public ExtractedMemory {
            if (content == null || content.isBlank()) {
                throw new IllegalArgumentException("memory content is required");
            }
            tags = tags == null ? List.of() : List.copyOf(tags);
            importance = Math.max(1, Math.min(10, importance));
        }
    }

    /**
     * The inputs to extraction: a single finished turn.
     */
    record MemoryCapture(String userId, String sessionId, String userQuery, String assistantReply) {
        public MemoryCapture {
            userQuery = userQuery == null ? "" : userQuery;
            assistantReply = assistantReply == null ? "" : assistantReply;
        }
    }
}
