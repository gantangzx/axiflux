package com.gantang.reaxon.impl.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gantang.reaxon.api.agent.AgentContext;
import com.gantang.reaxon.api.tool.Tool;
import com.gantang.reaxon.api.tool.ToolResult;
import com.gantang.reaxon.impl.tool.support.AbstractTool;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tool reactive SPI contract:
 *  - synchronous tools are bridged onto boundedElastic automatically
 *  - tools overriding executeReactive run their own non-blocking path
 *  - AbstractTool validation/error wrapping applies on the reactive path too
 */
class ToolReactiveSpiTest {

    private static final ObjectMapper OM = new ObjectMapper();

    @Test
    void syncTool_defaultBridgeReturnsResultAndRunsOffCallingThread() {
        AtomicReference<String> execThread = new AtomicReference<>();
        String callingThread = Thread.currentThread().getName();

        Tool sync = new Tool() {
            @Override public String name() { return "sync_tool"; }
            @Override public String description() { return "blocking tool"; }
            @Override public JsonNode parameters() { return OM.createObjectNode(); }
            @Override
            public ToolResult execute(String callId, Map<String, Object> params, AgentContext ctx) {
                execThread.set(Thread.currentThread().getName());
                try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                return ToolResult.success(callId, "sync-done");
            }
        };

        StepVerifier.create(sync.executeReactive("c1", Map.of(), null))
            .assertNext(r -> {
                assertTrue(r.success());
                assertEquals("sync-done", r.content());
            })
            .verifyComplete();

        assertNotNull(execThread.get());
        assertNotEquals(callingThread, execThread.get(),
            "blocking tool must run on a scheduler thread, not the caller");
        assertTrue(execThread.get().contains("boundedElastic"),
            "default bridge should use boundedElastic, was: " + execThread.get());
    }

    @Test
    void asyncTool_overrideIsUsedWithoutBoundedElastic() {
        AtomicReference<String> execThread = new AtomicReference<>();

        Tool async = new Tool() {
            @Override public String name() { return "async_tool"; }
            @Override public String description() { return "non-blocking tool"; }
            @Override public JsonNode parameters() { return OM.createObjectNode(); }
            @Override
            public ToolResult execute(String callId, Map<String, Object> params, AgentContext ctx) {
                throw new AssertionError("sync execute() must not be called when executeReactive is overridden");
            }
            @Override
            public Mono<ToolResult> executeReactive(String callId, Map<String, Object> params, AgentContext ctx) {
                return Mono.delay(Duration.ofMillis(10))
                    .doOnNext(t -> execThread.set(Thread.currentThread().getName()))
                    .map(t -> ToolResult.success(callId, "async-done"));
            }
        };

        StepVerifier.create(async.executeReactive("c2", Map.of(), null))
            .assertNext(r -> {
                assertTrue(r.success());
                assertEquals("async-done", r.content());
            })
            .verifyComplete();

        assertNotNull(execThread.get());
        assertFalse(execThread.get().contains("boundedElastic"),
            "overridden reactive tool must not hop onto boundedElastic, was: " + execThread.get());
    }

    @Test
    void abstractTool_validationAndErrorWrappingApplyOnReactivePath() {
        AbstractTool requiresText = new AbstractTool() {
            @Override public String name() { return "needs_text"; }
            @Override public String description() { return "requires a text param"; }
            @Override public JsonNode parameters() {
                ObjectNode schema = OM.createObjectNode();
                schema.set("properties", OM.createObjectNode());
                var required = OM.createArrayNode();
                required.add("text");
                schema.set("required", required);
                return schema;
            }
            @Override
            protected ToolResult doExecute(String callId, Params params, AgentContext context) {
                return ToolResult.success(callId, "ok");
            }
        };

        // Missing required field → failure, doExecute never reached.
        StepVerifier.create(requiresText.executeReactive("c3", Map.of(), null))
            .assertNext(r -> {
                assertFalse(r.success());
                assertTrue(r.errorMessage().contains("text"));
            })
            .verifyComplete();

        // Thrown exception wrapped into a failure result instead of propagating.
        AbstractTool boom = new AbstractTool() {
            @Override public String name() { return "boom"; }
            @Override public String description() { return "always fails"; }
            @Override public JsonNode parameters() {
                ObjectNode schema = OM.createObjectNode();
                schema.set("properties", OM.createObjectNode());
                schema.set("required", OM.createArrayNode());
                return schema;
            }
            @Override
            protected ToolResult doExecute(String callId, Params params, AgentContext context) {
                throw new IllegalStateException("kaboom");
            }
        };

        StepVerifier.create(boom.executeReactive("c4", Map.of(), null))
            .assertNext(r -> {
                assertFalse(r.success());
                assertTrue(r.errorMessage().contains("kaboom"));
            })
            .verifyComplete();
    }
}
