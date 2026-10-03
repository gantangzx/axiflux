package com.gantang.reaxon.api.session;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P2-5: message ids must be unique even under burst/parallel creation.
 * CompactionService locates summary boundaries by message id, so a collision
 * would hang the compaction horizon on the wrong message.
 */
class MessageIdUniquenessTest {

    @Test
    void ids_areUniqueInATightBurst() {
        // Same-millisecond burst: the old timeMillis+4-digit-random scheme collided.
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 50_000; i++) {
            ids.add(Message.user("x").id());
        }
        assertEquals(50_000, ids.size(), "message ids must be unique in a burst (P2-5)");
    }

    @Test
    void ids_areUniqueAcrossParallelThreads() throws Exception {
        int threads = 8, perThread = 5_000;
        Set<String> ids = java.util.concurrent.ConcurrentHashMap.newKeySet();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                for (int i = 0; i < perThread; i++) {
                    ids.add(Message.user("y").id());
                }
                done.countDown();
            });
        }
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();
        assertEquals(threads * perThread, ids.size(),
            "message ids must be unique across parallel producers (P2-5)");
    }

    @Test
    void idFormat_staysPrefixed() {
        String id = Message.user("z").id();
        assertTrue(id.startsWith("msg_"), "id keeps the msg_ prefix: " + id);
    }
}
