package com.gantang.tianshu.spring.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gantang.tianshu.api.observability.MetricsReporter;
import com.gantang.tianshu.api.observability.SecretMasker;
import com.gantang.tianshu.api.observability.ToolExecutionRecord;
import com.gantang.tianshu.storage.entity.ToolExecutionEntity;
import com.gantang.tianshu.storage.repository.ToolExecutionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Persistence-backed {@link MetricsReporter} that writes one row to the
 * {@code tool_executions} table per tool invocation.
 *
 * <p>Writes are asynchronous on a single daemon thread so DB latency never
 * blocks the agent tool loop. Failures are logged but never propagated —
 * observability must never break a tool call.
 *
 * <p>This is why the {@code tool_executions} table is no longer empty:
 * previously only the Micrometer in-memory reporter existed.
 */
public class JpaToolExecutionReporter implements MetricsReporter, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(JpaToolExecutionReporter.class);

    private final ToolExecutionRepository repo;
    private final ObjectMapper om;
    private final ExecutorService executor;

    public JpaToolExecutionReporter(ToolExecutionRepository repo, ObjectMapper om) {
        this.repo = repo;
        this.om = om != null ? om : new ObjectMapper();
        AtomicInteger seq = new AtomicInteger();
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "tool-exec-writer-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        this.executor = Executors.newSingleThreadExecutor(tf);
    }

    @Override
    public void recordToolExecution(ToolExecutionRecord rec) {
        if (rec == null) return;
        executor.execute(() -> {
            try {
                ToolExecutionEntity e = new ToolExecutionEntity();
                e.setSessionId(rec.sessionId());
                e.setUserId(rec.userId());
                e.setToolName(rec.toolName());
                e.setCallId(rec.callId());
                // Redact secrets (api keys, tokens, passwords, cookies, credentials)
                // before they ever reach the audit store.
                Object masked = rec.params() != null ? SecretMasker.mask(rec.params()) : null;
                e.setParams(masked != null ? om.convertValue(masked, Object.class) : null);
                e.setSuccess(rec.success());
                e.setDurationMs(rec.duration() != null ? (int) rec.duration().toMillis() : null);
                e.setError(maskError(rec.error()));
                e.setCreatedAt(rec.executedAt());
                repo.save(e);
            } catch (Exception ex) {
                log.warn("Failed to persist tool_execution for tool={} callId={}: {}",
                    rec.toolName(), rec.callId(), ex.getMessage());
            }
        });
    }

    /** Strip NUL chars which are illegal in PostgreSQL text columns. */
    private static String sanitize(String s) {
        return s == null || s.indexOf('\u0000') < 0 ? s : s.replace("\u0000", "");
    }

    /**
     * Errors are free text: an upstream exception often echoes the request line or
     * URL (with an Authorization header or api_key= query) that key-based masking
     * cannot reach. Scrub credential-shaped substrings in addition to NUL removal.
     */
    private static String maskError(String s) {
        return SecretMasker.maskString(sanitize(s));
    }

    @Override
    public void close() {
        executor.shutdown();
    }
}
