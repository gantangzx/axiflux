package com.gantang.reaxon.api.memory;

import java.time.Instant;
import java.util.List;

/**
 * A single long-term memory entry.
 */
public record MemoryItem(
    String id,
    String userId,
    String content,
    String summary,         // LLM-generated summary (shown in context)
    List<String> tags,
    int importance,         // 1-10; >= 8 triggers compression
    Instant createdAt,
    Instant updatedAt,
    Instant validUntil      // null = currently valid; non-null = the moment this fact
                            // stopped being true (superseded by a newer fact, or an
                            // explicitly short-lived fact expired). Retrieval filters it.
) {
    /** Back-compat constructor for callers that do not track validity (valid until = forever). */
    public MemoryItem(String id, String userId, String content, String summary,
                      List<String> tags, int importance, Instant createdAt, Instant updatedAt) {
        this(id, userId, content, summary, tags, importance, createdAt, updatedAt, null);
    }

    public MemoryItem withSummary(String newSummary) {
        return new MemoryItem(id, userId, content, newSummary, tags, importance,
                              createdAt, Instant.now(), validUntil);
    }

    public MemoryItem withValidUntil(Instant when) {
        return new MemoryItem(id, userId, content, summary, tags, importance,
                              createdAt, updatedAt, when);
    }

    /** Whether this fact is still active at {@code now}. */
    public boolean validAt(Instant now) {
        return validUntil == null || validUntil.isAfter(now);
    }
}
