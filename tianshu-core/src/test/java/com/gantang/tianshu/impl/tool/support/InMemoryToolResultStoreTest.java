package com.gantang.tianshu.impl.tool.support;

import com.gantang.tianshu.api.tool.ToolResultStore;
import com.gantang.tianshu.api.tool.StoredToolResult;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryToolResultStoreTest {

    @Test
    void storeThenFetch_returnsFullRecordWithProvenance() {
        InMemoryToolResultStore store = new InMemoryToolResultStore();
        String handle = store.store("s1", "full content", "web_fetch");

        assertTrue(ToolResultStore.isHandle(handle));
        String id = ToolResultStore.idOf(handle);
        Optional<StoredToolResult> rec = store.fetch("s1", id);
        assertTrue(rec.isPresent());
        assertEquals("full content", rec.get().content());
        assertEquals("web_fetch", rec.get().originTool());
        assertEquals("s1", rec.get().sessionId());
    }

    @Test
    void fetch_isSessionScoped() {
        InMemoryToolResultStore store = new InMemoryToolResultStore();
        String id = ToolResultStore.idOf(store.store("s1", "secret", "file_read"));
        assertTrue(store.fetch("s1", id).isPresent());
        assertTrue(store.fetch("s2", id).isEmpty(), "foreign session must not see the record");
    }

    @Test
    void clearSession_purgesOnlyThatSession() {
        InMemoryToolResultStore store = new InMemoryToolResultStore();
        String a = ToolResultStore.idOf(store.store("s1", "a", "file_read"));
        String b = ToolResultStore.idOf(store.store("s2", "b", "file_read"));

        store.clearSession("s1");
        assertTrue(store.fetch("s1", a).isEmpty());
        assertTrue(store.fetch("s2", b).isPresent());
        assertEquals(1, store.size());
    }

    @Test
    void lruEviction_dropsOldestUntilWithinBudget_butKeepsJustInserted() {
        InMemoryToolResultStore store = new InMemoryToolResultStore(1_000);
        String a = ToolResultStore.idOf(store.store("s1", "x".repeat(600), "file_read"));
        String b = ToolResultStore.idOf(store.store("s1", "y".repeat(600), "file_read"));

        assertTrue(store.fetch("s1", a).isEmpty(), "oldest record should be evicted");
        assertTrue(store.fetch("s1", b).isPresent(), "newest record survives");
        assertTrue(store.totalChars() <= 1_000);
    }

    @Test
    void singleRecordLargerThanBudget_isRetained() {
        InMemoryToolResultStore store = new InMemoryToolResultStore(100);
        String id = ToolResultStore.idOf(store.store("s1", "z".repeat(500), "file_read"));
        assertTrue(store.fetch("s1", id).isPresent(),
            "the just-inserted record is protected from self-eviction");
    }

    @Test
    void handleHelpers_rejectBarePrefix() {
        assertFalse(ToolResultStore.isHandle("ref://tool-result/"));
        assertFalse(ToolResultStore.isHandle(null));
        assertEquals("tr_1", ToolResultStore.idOf("ref://tool-result/tr_1"));
        assertEquals("tr_1", ToolResultStore.idOf("tr_1"));
    }
}
