package com.gantang.tianshu.spring.controller;

import com.gantang.tianshu.api.memory.LongTermMemory;
import com.gantang.tianshu.api.memory.MemoryItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * MemoryController exposes the long-term memory store. The two invariants
 * are: (1) when no LongTermMemory bean is configured every endpoint degrades
 * to 503, and (2) per-user access is gated by CallerAuthorization so a caller
 * can only see their own memories (404 for foreign paths, not 403, to avoid
 * existence leaks).
 */
class MemoryControllerTest {

    private LongTermMemory ltm;
    private MemoryController controller;

    @BeforeEach
    void setUp() {
        ltm = mock(LongTermMemory.class);
        ObjectProvider<LongTermMemory> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(ltm);
        controller = new MemoryController(provider);
    }

    private MemoryItem item(String id, String userId, String content) {
        return new MemoryItem(id, userId, content, null, List.of(), 5,
            Instant.now(), Instant.now(), null);
    }

    // ===== unavailable =====

    @Test
    void noLtmReturns503() {
        ObjectProvider<LongTermMemory> empty = mock(ObjectProvider.class);
        when(empty.getIfAvailable()).thenReturn(null);
        MemoryController noLtm = new MemoryController(empty);

        // ltmOrUnavailable() throws before the reactive chain is assembled,
        // so the exception escapes synchronously rather than as a Mono error.
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> noLtm.search(
                new MemoryController.SearchRequest("alice", "test", 5), "alice", null));
        assertEquals(503, ex.getStatusCode().value());
    }

    // ===== search =====

    @Test
    void searchReturnsResults() {
        when(ltm.search("alice", "java", 5))
            .thenReturn(Mono.just(List.of(item("m1", "alice", "java tips"))));

        var req = new MemoryController.SearchRequest("alice", "java", 5);
        StepVerifier.create(controller.search(req, "alice", null))
            .assertNext(resp -> {
                assertTrue(resp.success());
                @SuppressWarnings("unchecked")
                var results = (List<MemoryItem>) resp.data().get("results");
                assertEquals(1, results.size());
            })
            .verifyComplete();
    }

    @Test
    void searchDefaultsTopKTo5() {
        when(ltm.search("alice", "java", 5))
            .thenReturn(Mono.just(List.of()));

        var req = new MemoryController.SearchRequest("alice", "java", 0);
        StepVerifier.create(controller.search(req, "alice", null))
            .assertNext(resp -> assertTrue(resp.success()))
            .verifyComplete();
        verify(ltm).search("alice", "java", 5);
    }

    // ===== store =====

    @Test
    void storeGeneratesIdWhenAbsent() {
        when(ltm.store(eq("alice"), any(MemoryItem.class))).thenReturn(Mono.empty());

        var req = new MemoryController.StoreRequest(null, "alice", "remember this", null, null, 0, null);
        StepVerifier.create(controller.store(req, "alice", null))
            .assertNext(resp -> {
                assertTrue(resp.success());
                assertEquals(true, resp.data().get("stored"));
                assertNotNull(resp.data().get("id"));
                assertTrue(resp.data().get("id").toString().startsWith("mem_"));
            })
            .verifyComplete();
    }

    @Test
    void storeUsesProvidedId() {
        when(ltm.store(eq("alice"), any(MemoryItem.class))).thenReturn(Mono.empty());

        var req = new MemoryController.StoreRequest("my-id", "alice", "content", null, null, 0, null);
        StepVerifier.create(controller.store(req, "alice", null))
            .assertNext(resp -> assertEquals("my-id", resp.data().get("id")))
            .verifyComplete();
    }

    @Test
    void storeInvalidValidUntilReturns400() {
        var req = new MemoryController.StoreRequest(null, "alice", "c", null, null, 0, "not-a-date");
        // Same synchronous-throw pattern as ltmOrUnavailable: the date is
        // validated before any Mono is created.
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> controller.store(req, "alice", null));
        assertEquals(400, ex.getStatusCode().value());
    }

    // ===== P2-11: input validation =====

    @Test
    void storeBlankContentReturns400() {
        var req = new MemoryController.StoreRequest(null, "alice", "", null, null, 0, null);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> controller.store(req, "alice", null));
        assertEquals(400, ex.getStatusCode().value());
    }

    @Test
    void storeNullContentReturns400() {
        var req = new MemoryController.StoreRequest(null, "alice", null, null, null, 0, null);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> controller.store(req, "alice", null));
        assertEquals(400, ex.getStatusCode().value());
    }

    @Test
    void storeImportanceTooLowReturns400() {
        var req = new MemoryController.StoreRequest(null, "alice", "c", null, null, -1, null);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> controller.store(req, "alice", null));
        assertEquals(400, ex.getStatusCode().value());
    }

    @Test
    void storeImportanceTooHighReturns400() {
        var req = new MemoryController.StoreRequest(null, "alice", "c", null, null, 11, null);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> controller.store(req, "alice", null));
        assertEquals(400, ex.getStatusCode().value());
    }

    @Test
    void storeImportanceBoundaryValuesAccepted() {
        when(ltm.store(eq("alice"), any(MemoryItem.class))).thenReturn(Mono.empty());

        // importance=0 → defaults to 5, not rejected
        var req0 = new MemoryController.StoreRequest(null, "alice", "c", null, null, 0, null);
        StepVerifier.create(controller.store(req0, "alice", null))
            .assertNext(resp -> assertTrue(resp.success()))
            .verifyComplete();

        // importance=1 → accepted
        var req1 = new MemoryController.StoreRequest(null, "alice", "c", null, null, 1, null);
        StepVerifier.create(controller.store(req1, "alice", null))
            .assertNext(resp -> assertTrue(resp.success()))
            .verifyComplete();

        // importance=10 → accepted
        var req10 = new MemoryController.StoreRequest(null, "alice", "c", null, null, 10, null);
        StepVerifier.create(controller.store(req10, "alice", null))
            .assertNext(resp -> assertTrue(resp.success()))
            .verifyComplete();
    }

    // ===== getAll =====

    @Test
    void listByUserReturnsOwnItems() {
        when(ltm.getAll("alice")).thenReturn(Mono.just(List.of(item("m1", "alice", "note"))));

        StepVerifier.create(controller.listByUser("alice", "alice", null))
            .assertNext(resp -> {
                assertTrue(resp.success());
                assertEquals("alice", resp.data().get("userId"));
                @SuppressWarnings("unchecked")
                var items = (List<MemoryItem>) resp.data().get("items");
                assertEquals(1, items.size());
            })
            .verifyComplete();
    }

    @Test
    void listByForeignUserReturns404() {
        StepVerifier.create(controller.listByUser("bob", "alice", null))
            .expectErrorSatisfies(e -> {
                assertInstanceOf(ResponseStatusException.class, e);
                assertEquals(404, ((ResponseStatusException) e).getStatusCode().value());
            })
            .verify();
    }

    // ===== delete =====

    @Test
    void deleteWildcardDeletesDirectly() {
        when(ltm.delete("m1")).thenReturn(Mono.just(true));

        StepVerifier.create(controller.delete("m1", "op", "*"))
            .assertNext(resp -> {
                assertTrue(resp.success());
                assertEquals(true, resp.data().get("deleted"));
            })
            .verifyComplete();
    }

    @Test
    void deleteOwnerVerifiesThenDeletes() {
        when(ltm.getAll("alice")).thenReturn(Mono.just(List.of(item("m1", "alice", "note"))));
        when(ltm.delete("m1")).thenReturn(Mono.just(true));

        StepVerifier.create(controller.delete("m1", "alice", null))
            .assertNext(resp -> assertEquals(true, resp.data().get("deleted")))
            .verifyComplete();
    }

    @Test
    void deleteForeignMemoryReturns404() {
        when(ltm.getAll("alice")).thenReturn(Mono.just(List.of(item("m1", "alice", "note"))));

        StepVerifier.create(controller.delete("m2", "alice", null))
            .expectError(ResponseStatusException.class)
            .verify();
        verify(ltm, never()).delete("m2");
    }
}
