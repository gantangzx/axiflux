package com.gantang.reaxon.impl.memory;

import com.gantang.reaxon.api.memory.LongTermMemory;
import com.gantang.reaxon.api.memory.MemoryItem;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * No-op {@link LongTermMemory} used when no vector store is configured
 * ({@code axiflux.vector.provider=none}). Silently accepts stores and
 * returns empty search results so the application can boot without any
 * external memory service.
 */
public final class NoOpLongTermMemory implements LongTermMemory {

    @Override
    public Mono<Void> store(String userId, MemoryItem item) {
        return Mono.empty();
    }

    @Override
    public Mono<Void> storeBatch(String userId, List<MemoryItem> items) {
        return Mono.empty();
    }

    @Override
    public Mono<List<MemoryItem>> search(String userId, String query, int topK) {
        return Mono.just(List.of());
    }

    @Override
    public Mono<List<MemoryItem>> searchByKeyword(String userId, String keyword) {
        return Mono.just(List.of());
    }

    @Override
    public Mono<List<MemoryItem>> getAll(String userId) {
        return Mono.just(List.of());
    }

    @Override
    public Mono<Boolean> delete(String memoryId) {
        return Mono.just(false);
    }

    @Override
    public Mono<Void> deleteAll(String userId) {
        return Mono.empty();
    }

    @Override
    public Mono<Integer> compress(String userId) {
        return Mono.just(0);
    }

    @Override
    public boolean enabled() {
        return false;
    }
}
