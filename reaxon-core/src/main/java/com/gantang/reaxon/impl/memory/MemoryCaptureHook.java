package com.gantang.reaxon.impl.memory;

import com.gantang.reaxon.api.agent.AgentContext;
import com.gantang.reaxon.api.agent.AgentHook;
import com.gantang.reaxon.api.agent.AgentResponse;
import com.gantang.reaxon.api.memory.MemoryExtractor.MemoryCapture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agent lifecycle hook that drives the long-term memory write loop: when a turn
 * finishes successfully, it hands the (user query, assistant reply) pair to
 * {@link MemoryWritingService} for extraction + dedup + persistence.
 *
 * <p><b>Observational and off the critical path.</b> {@code onTurnEnd} must
 * return immediately and never affect the turn: the pipeline is fire-and-forget
 * (it already runs on boundedElastic and degrades every failure to a no-op),
 * and this hook subscribes without blocking. Errors are logged at debug level
 * only — memory is an enhancement, never a dependency of the conversation.
 */
public class MemoryCaptureHook implements AgentHook {

    private static final Logger log = LoggerFactory.getLogger(MemoryCaptureHook.class);

    /** Run after audit/metrics hooks (order 0) so observability sees the turn first. */
    private static final int HOOK_ORDER = 100;

    private final MemoryWritingService writingService;

    public MemoryCaptureHook(MemoryWritingService writingService) {
        this.writingService = writingService;
    }

    @Override
    public void onTurnEnd(AgentContext context, AgentResponse response) {
        // Only successful, substantive turns are worth remembering.
        if (response == null || response.status() != AgentResponse.Status.SUCCESS) return;
        String reply = response.content();
        if (reply == null || reply.isBlank()) return;
        String userId = context.userId();
        if (userId == null || userId.isBlank()) return;

        MemoryCapture capture = new MemoryCapture(
            userId, context.sessionId(), context.currentQuery(), reply);

        try {
            writingService.captureAndStore(capture)
                .subscribe(
                    stored -> { },
                    err -> log.debug("Memory capture subscription failed (turn unaffected): {}",
                        err.toString()));
        } catch (Exception e) {
            // Defensive: never let hook wiring break the turn.
            log.debug("Memory capture not triggered: {}", e.toString());
        }
    }

    @Override
    public int order() {
        return HOOK_ORDER;
    }
}
