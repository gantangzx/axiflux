package com.gantang.reaxon.eval.engine;

import com.gantang.reaxon.api.agent.BackgroundSpawner;
import com.gantang.reaxon.api.agent.DelegationRequest;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Scripted {@link BackgroundSpawner} for replay scenarios: {@link #spawnAsync}
 * honours the fire-and-forget contract (returns a handle immediately, never
 * blocks on a child run) and records the delegation in the shared
 * {@link SideEffectStore} so assertions can target the task the model handed
 * off. The child run itself is not simulated — parent-turn behaviour after
 * spawning (relay the handle, keep the conversation responsive) is what the
 * fan-out regression covers; child orchestration has its own tests in core.
 */
class FakeBackgroundSpawner implements BackgroundSpawner {

    private final SideEffectStore effects;
    private final AtomicLong counter = new AtomicLong(0);

    FakeBackgroundSpawner(SideEffectStore effects) {
        this.effects = effects;
    }

    @Override
    public SpawnHandle spawnAsync(DelegationRequest request) {
        long n = counter.incrementAndGet();
        String taskId = "eval-task-" + n;
        String childSessionId = SUBAGENT_SESSION_PREFIX + request.userId()
                + ":eval-" + UUID.randomUUID();
        effects.record("spawn_task", Map.of("task", request.task()),
                true, "background task started: " + taskId, null);
        return new SpawnHandle(taskId, childSessionId, "running");
    }
}
