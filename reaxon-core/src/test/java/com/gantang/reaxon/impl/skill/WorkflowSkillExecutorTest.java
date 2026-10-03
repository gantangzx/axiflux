package com.gantang.reaxon.impl.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.gantang.reaxon.api.agent.AgentContext;
import com.gantang.reaxon.api.skill.*;
import com.gantang.reaxon.api.tool.Tool;
import com.gantang.reaxon.api.tool.ToolRegistry;
import com.gantang.reaxon.api.tool.ToolResult;
import com.gantang.reaxon.impl.skill.interceptor.LoggingInterceptor;
import com.gantang.reaxon.impl.skill.interceptor.RequiredToolsInterceptor;
import com.gantang.reaxon.impl.skill.interceptor.TimeoutInterceptor;
import com.gantang.reaxon.impl.skill.strategy.LlmGuidedWorkflowStrategy;
import com.gantang.reaxon.impl.skill.strategy.ParallelWorkflowStrategy;
import com.gantang.reaxon.impl.skill.strategy.SequentialWorkflowStrategy;
import com.gantang.reaxon.impl.tool.DefaultToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowSkillExecutorTest {

    private ToolRegistry tools;
    private WorkflowSkillExecutor executor;

    @BeforeEach
    void setUp() {
        tools = new DefaultToolRegistry();
        tools.register(new EchoTool());

        executor = new WorkflowSkillExecutor(
            new DefaultSkillRegistry(),
            new SkillLoaderFactory(),
            List.of(
                new SequentialWorkflowStrategy(tools),
                new ParallelWorkflowStrategy(tools, 2),
                new LlmGuidedWorkflowStrategy(tools)
            )
        );
        executor.addInterceptor(new LoggingInterceptor());
        executor.addInterceptor(new TimeoutInterceptor(Duration.ofSeconds(30)));
        executor.addInterceptor(new RequiredToolsInterceptor(tools));
    }

    @Test
    void sequential_runs_all_steps_and_captures_output_var() {
        SkillMetadata meta = SkillMetadata.builder("greet")
            .executionMode(SkillMetadata.MODE_SEQUENTIAL)
            .steps(List.of(
                new SkillMetadata.StepDefinition(
                    "hello", "tool", "echo",
                    Map.of("text", "hi"), null, "greeting", 30),
                new SkillMetadata.StepDefinition(
                    "shout", "tool", "echo",
                    Map.of("text", "${greeting}!"), null, "final", 30)
            ))
            .build();
        Skill skill = new DefaultSkill(meta, Path.of("."), Path.of("."));

        SkillResult r = executor.execute(skill, "hello", ctx()).block();
        assertNotNull(r);
        assertTrue(r.success(), r.error());
        assertEquals(2, r.stepsExecuted());
        assertEquals("hi!", r.output());
    }

    @Test
    void sequential_stops_on_first_failure() {
        SkillMetadata meta = SkillMetadata.builder("fails")
            .executionMode(SkillMetadata.MODE_SEQUENTIAL)
            .steps(List.of(
                new SkillMetadata.StepDefinition("boom", "tool", "missing",
                    Map.of(), null, null, 30),
                new SkillMetadata.StepDefinition("never", "tool", "echo",
                    Map.of("text", "x"), null, null, 30)
            ))
            .build();
        Skill skill = new DefaultSkill(meta, Path.of("."), Path.of("."));

        SkillResult r = executor.execute(skill, "in", ctx()).block();
        assertNotNull(r);
        assertFalse(r.success());
        assertEquals(1, r.stepsExecuted());
    }

    @Test
    void required_tools_interceptor_short_circuits() {
        SkillMetadata meta = SkillMetadata.builder("needs_x")
            .executionMode(SkillMetadata.MODE_SEQUENTIAL)
            .requiredTools(List.of("does_not_exist"))
            .build();
        Skill skill = new DefaultSkill(meta, Path.of("."), Path.of("."));

        SkillResult r = executor.execute(skill, "in", ctx()).block();
        assertNotNull(r);
        assertFalse(r.success());
        assertTrue(r.error().contains("missing required tools"));
    }

    @Test
    void parallel_runs_all_steps() {
        SkillMetadata meta = SkillMetadata.builder("fanout")
            .executionMode(SkillMetadata.MODE_PARALLEL)
            .steps(List.of(
                new SkillMetadata.StepDefinition("a", "tool", "echo",
                    Map.of("text", "A"), null, null, 30),
                new SkillMetadata.StepDefinition("b", "tool", "echo",
                    Map.of("text", "B"), null, null, 30),
                new SkillMetadata.StepDefinition("c", "tool", "echo",
                    Map.of("text", "C"), null, null, 30)
            ))
            .build();
        Skill skill = new DefaultSkill(meta, Path.of("."), Path.of("."));

        SkillResult r = executor.execute(skill, "in", ctx()).block();
        assertNotNull(r);
        assertTrue(r.success(), r.error());
        assertEquals(3, r.stepsExecuted());
    }

    @Test
    void event_listener_receives_lifecycle_events() {
        List<SkillEvent> events = new CopyOnWriteArrayList<>();
        executor.addListener(events::add);

        SkillMetadata meta = SkillMetadata.builder("observed")
            .executionMode(SkillMetadata.MODE_SEQUENTIAL)
            .steps(List.of(new SkillMetadata.StepDefinition(
                "s1", "tool", "echo", Map.of("text", "x"), null, null, 30)))
            .build();
        Skill skill = new DefaultSkill(meta, Path.of("."), Path.of("."));

        executor.execute(skill, "in", ctx()).block();

        assertTrue(events.stream().anyMatch(e -> e.type() == SkillEvent.Type.SKILL_STARTED));
        assertTrue(events.stream().anyMatch(e -> e.type() == SkillEvent.Type.SKILL_COMPLETED));
    }

    @Test
    void trigger_matching_finds_registered_skill() {
        SkillMetadata meta = SkillMetadata.builder("weather")
            .executionMode(SkillMetadata.MODE_SEQUENTIAL)
            .triggers(List.of("weather", "temperature"))
            .steps(List.of(new SkillMetadata.StepDefinition(
                "get", "tool", "echo", Map.of("text", "sunny"), null, null, 30)))
            .build();
        Skill s = new DefaultSkill(meta, Path.of("."), Path.of("."));
        executor.registry().register(s);

        assertTrue(executor.matchesTrigger(s, "What's the weather like?"));
        SkillResult r = executor.executeMatching("What's the weather like?", ctx()).block();
        assertNotNull(r);
        assertTrue(r.success());
        assertEquals("sunny", r.output());
    }

    // === Fixtures ===

    private static AgentContext ctx() {
        return AgentContext.builder()
            .sessionId("s-1").userId("u-1").currentQuery("hi").build();
    }

    /** Echoes the {@code text} parameter back as the tool's content. */
    static final class EchoTool implements Tool {
        @Override public String name()        { return "echo"; }
        @Override public String description() { return "returns input"; }
        @Override public JsonNode parameters() {
            return JsonNodeFactory.instance.objectNode()
                .put("type", "object")
                .set("properties", JsonNodeFactory.instance.objectNode()
                    .set("text", JsonNodeFactory.instance.objectNode().put("type", "string")));
        }
        @Override
        public ToolResult execute(String callId, Map<String, Object> params, AgentContext context) {
            return ToolResult.success(callId, String.valueOf(params.getOrDefault("text", "")));
        }
    }
}
