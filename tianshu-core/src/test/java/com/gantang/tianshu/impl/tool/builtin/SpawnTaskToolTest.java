package com.gantang.tianshu.impl.tool.builtin;

import com.gantang.tianshu.api.agent.AgentContext;
import com.gantang.tianshu.api.agent.BackgroundSpawner;
import com.gantang.tianshu.api.auth.CallerIdentity;
import com.gantang.tianshu.api.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SpawnTaskToolTest {

    private final AgentContext ctx = AgentContext.builder()
        .sessionId("parent-1").userId("user-1").currentQuery("q").build();

    @Test
    void spawnDelegatesAsyncAndReturnsHandle() {
        BackgroundSpawner spawner = req -> {
            assertEquals("do research", req.task());
            assertEquals("user-1", req.userId());
            assertEquals("parent-1", req.parentSessionId());
            assertEquals(1, req.childDepth(), "root session spawns at depth 1");
            return new BackgroundSpawner.SpawnHandle("task-abc", "sub:user-1:x", "running");
        };
        SpawnTaskTool tool = new SpawnTaskTool(() -> spawner);

        ToolResult r = tool.execute("c1", Map.of("task", "do research"), ctx);

        assertTrue(r.success());
        assertEquals("task-abc", r.metadata().get("taskId"));
        assertEquals("sub:user-1:x", r.metadata().get("childSessionId"));
        assertEquals(true, r.metadata().get("async"));
        // The tool must instruct the model not to block/wait.
        assertTrue(r.content().contains("asynchronously") || r.content().contains("background"));
    }

    @Test
    void childInheritsExactlyTheParentScopes() {
        AgentContext scoped = AgentContext.builder()
            .sessionId("parent-1").userId("user-1").currentQuery("q")
            .metadata(Map.of(CallerIdentity.META_SCOPES, List.of("agent:spawn", "tool:net")))
            .build();
        BackgroundSpawner spawner = req -> {
            assertEquals(Set.of("agent:spawn", "tool:net"), req.caller().scopes());
            return new BackgroundSpawner.SpawnHandle("t", "s", "running");
        };
        assertTrue(new SpawnTaskTool(() -> spawner)
            .execute("c1", Map.of("task", "x"), scoped).success());
    }

    @Test
    void childGetsNoWildcardWhenParentTurnCarriesNoScopes() {
        // A parent turn with no injected grant must not be upgraded to full access
        // here: the strict/lenient decision belongs to ScopePolicy, and minting "*"
        // on the spawn path would be an escalation route out of a narrow grant.
        BackgroundSpawner spawner = req -> {
            assertTrue(req.caller().scopes().isEmpty(), "expected no inherited scopes");
            assertFalse(req.caller().has("anything"));
            return new BackgroundSpawner.SpawnHandle("t", "s", "running");
        };
        assertTrue(new SpawnTaskTool(() -> spawner)
            .execute("c1", Map.of("task", "x"), ctx).success());
    }

    @Test
    void missingTaskFails() {
        SpawnTaskTool tool = new SpawnTaskTool(() -> null);
        ToolResult r = tool.execute("c1", Map.of(), ctx);
        assertFalse(r.success());
        assertTrue(r.errorMessage().contains("task is required"));
    }

    @Test
    void spawnerUnavailableFailsGracefully() {
        SpawnTaskTool tool = new SpawnTaskTool(() -> null);
        ToolResult r = tool.execute("c1", Map.of("task", "x"), ctx);
        assertFalse(r.success());
        assertTrue(r.errorMessage().contains("not available"));
    }

    @Test
    void spawnerErrorPropagatesAsFailure() {
        BackgroundSpawner boom = req -> { throw new RuntimeException("boom"); };
        SpawnTaskTool tool = new SpawnTaskTool(() -> boom);
        ToolResult r = tool.execute("c1", Map.of("task", "x"), ctx);
        assertFalse(r.success());
        assertTrue(r.errorMessage().contains("boom"));
    }

    @Test
    void returnsImmediatelyWithoutBlocking() {
        // Even though the real child runs for a long time in the background,
        // spawnAsync returns a handle synchronously and the tool does not block.
        BackgroundSpawner spawner = req ->
            new BackgroundSpawner.SpawnHandle("task-1", "sub:x", "running");
        SpawnTaskTool tool = new SpawnTaskTool(() -> spawner);
        long start = System.nanoTime();
        ToolResult r = tool.execute("c1", Map.of("task", "a long background job"), ctx);
        long elapsedMs = java.time.Duration.ofNanos(System.nanoTime() - start).toMillis();
        assertTrue(r.success());
        assertTrue(elapsedMs < 2000, "async spawn must return immediately, took " + elapsedMs + "ms");
    }

    @Test
    void spawnsAtDepthTwoFromDepthOne() {
        AgentContext child = AgentContext.builder()
            .sessionId("sub-1").userId("user-1").currentQuery("q")
            .metadata(Map.of(BackgroundSpawner.META_SPAWN_DEPTH, 1)).build();
        BackgroundSpawner spawner = req -> {
            assertEquals(2, req.childDepth());
            return new BackgroundSpawner.SpawnHandle("task-d2", "sub:y", "running");
        };
        SpawnTaskTool tool = new SpawnTaskTool(() -> spawner);
        ToolResult r = tool.execute("c1", Map.of("task", "x"), child);
        assertTrue(r.success());
        assertEquals("task-d2", r.metadata().get("taskId"));
    }

    @Test
    void nestingBeyondMaxDepthIsRefused() {
        // A session already at MAX_SPAWN_DEPTH must not spawn a deeper child.
        AgentContext grandchild = AgentContext.builder()
            .sessionId("sub-deep").userId("user-1").currentQuery("q")
            .metadata(Map.of(BackgroundSpawner.META_SPAWN_DEPTH, BackgroundSpawner.MAX_SPAWN_DEPTH)).build();
        SpawnTaskTool tool = new SpawnTaskTool(() -> req -> {
            fail("spawner must not be invoked beyond max depth");
            return null;
        });
        ToolResult r = tool.execute("c1", Map.of("task", "x"), grandchild);
        assertFalse(r.success());
        assertTrue(r.errorMessage().contains("nesting limit"), r.errorMessage());
    }

    @Test
    void metadata() {
        SpawnTaskTool tool = new SpawnTaskTool(() -> null);
        assertEquals("spawn_task", tool.name());
        assertEquals("builtin", tool.group());
        assertNotNull(tool.parameters());
        assertTrue(tool.description().contains("background"));
    }

    // ===== audit toolsec P2-8: scope attenuation =====

    @Test
    void childScopesAreAlwaysASubsetOfParentScopes() {
        // The formal guarantee behind the inheritance design: whatever the
        // attenuation mode, a child can never hold anything the parent lacked.
        SpawnTaskTool.scopeAttenuation = SpawnTaskTool.ScopeAttenuation.INHERIT;
        Set<String> parent = Set.of("agent:spawn", "tool:net", "*");
        assertTrue(parent.containsAll(SpawnTaskTool.attenuateScopes(parent)));
        SpawnTaskTool.scopeAttenuation = SpawnTaskTool.ScopeAttenuation.DROP_WILDCARD;
        assertTrue(parent.containsAll(SpawnTaskTool.attenuateScopes(parent)));
    }

    @Test
    void dropWildcardModeStripsStarButKeepsExplicitScopes() {
        SpawnTaskTool.ScopeAttenuation prev = SpawnTaskTool.scopeAttenuation;
        SpawnTaskTool.scopeAttenuation = SpawnTaskTool.ScopeAttenuation.DROP_WILDCARD;
        try {
            assertEquals(Set.of("agent:spawn", "tool:net"),
                SpawnTaskTool.attenuateScopes(Set.of("agent:spawn", "tool:net", "*")));
            assertEquals(Set.of(), SpawnTaskTool.attenuateScopes(Set.of("*")));

            AgentContext scoped = AgentContext.builder()
                .sessionId("parent-1").userId("user-1").currentQuery("q")
                .metadata(Map.of(CallerIdentity.META_SCOPES, List.of("*", "tool:net")))
                .build();
            BackgroundSpawner spawner = req -> {
                assertEquals(Set.of("tool:net"), req.caller().scopes(),
                    "wildcard must not survive into the child identity under DROP_WILDCARD");
                return new BackgroundSpawner.SpawnHandle("t", "s", "running");
            };
            assertTrue(new SpawnTaskTool(() -> spawner)
                .execute("c1", Map.of("task", "x"), scoped).success());
        } finally {
            SpawnTaskTool.scopeAttenuation = prev;
        }
    }

    @Test
    void descriptionDisclosesScopeInheritanceRisk() {
        String desc = new SpawnTaskTool(() -> null).description();
        assertTrue(desc.contains("inherits"), "description must state the inheritance: " + desc);
        assertTrue(desc.contains("wildcard"), "description must name the wildcard risk: " + desc);
    }
}
