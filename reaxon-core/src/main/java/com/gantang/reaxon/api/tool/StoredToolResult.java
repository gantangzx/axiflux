package com.gantang.reaxon.api.tool;

import java.time.Instant;

/**
 * One parked tool result: full content plus provenance of the producing tool.
 *
 * @param id         bare record id (handle is {@code ref://tool-result/<id>})
 * @param sessionId  owning session
 * @param content    full unpruned result text
 * @param originTool tool that originally produced the content
 * @param storedAt   insertion time (eviction ordering)
 */
public record StoredToolResult(
    String id,
    String sessionId,
    String content,
    String originTool,
    Instant storedAt
) {
    public int length() {
        return content == null ? 0 : content.length();
    }
}
