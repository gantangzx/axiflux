package com.gantang.tianshu.spring;

import com.gantang.tianshu.api.agent.AgentContext;
import com.gantang.tianshu.api.llm.CompletionRequest;
import com.gantang.tianshu.api.llm.CompletionResponse;
import com.gantang.tianshu.api.llm.LlmClient;
import com.gantang.tianshu.api.memory.LongTermMemory;
import com.gantang.tianshu.api.memory.MemoryItem;
import com.gantang.tianshu.api.scheduler.TaskScheduler;
import com.gantang.tianshu.api.session.Session;
import com.gantang.tianshu.api.session.SessionManager;
import com.gantang.tianshu.api.skill.SkillExecutor;
import com.gantang.tianshu.api.skill.SkillRegistry;
import com.gantang.tianshu.api.tool.Tool;
import com.gantang.tianshu.api.tool.ToolRegistry;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Boot the tianshu-spring auto-configuration in isolation (no LLM keys, no
 * external DB). Validates:
 *  - built-in tools register into the ToolRegistry
 *  - InMemorySessionManagerImpl becomes the default SessionManager
 *  - sessions can be created and mutated
 */
@SpringBootTest(classes = TianshuAutoConfigurationSmokeTest.SmokeApp.class)
@TestPropertySource(properties = {
    "spring.autoconfigure.exclude="
        + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
        + "org.springframework.boot.jdbc.autoconfigure.DataSourceInitializationAutoConfiguration,"
        + "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration,"
        + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
        + "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration,"
        + "org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration,"
        + "org.springframework.boot.jdbc.autoconfigure.health.DataSourceHealthContributorAutoConfiguration",
    "tianshu.tools.builtins.enabled=true",
    "tianshu.vector.provider=none",
    "spring.main.web-application-type=reactive",
    "management.endpoints.enabled-by-default=false",
    "management.health.defaults.enabled=false"
})
class TianshuAutoConfigurationSmokeTest {

    @Autowired ToolRegistry toolRegistry;
    @Autowired SessionManager sessionManager;
    @Autowired SkillRegistry skillRegistry;
    @Autowired SkillExecutor skillExecutor;
    @Autowired TaskScheduler taskScheduler;
    @Autowired(required = false)
    com.gantang.tianshu.spring.event.ScheduledTaskEventListener scheduledTaskEventListener;

    @Test
    void built_in_tools_are_registered() {
        List<Tool> all = toolRegistry.getAll();
        Set<String> names = all.stream().map(Tool::name)
            .collect(java.util.stream.Collectors.toSet());
        assertTrue(names.contains("http_client"), "http_client missing: " + names);
        assertTrue(names.contains("calculator"),  "calculator missing: "  + names);
        assertTrue(names.contains("date_time"),   "date_time missing: "   + names);
        assertTrue(names.contains("file_read"),   "file_read missing: "   + names);
        assertTrue(names.contains("file_write"),  "file_write missing: "  + names);
    }

    @Test
    void session_manager_defaults_to_in_memory() {
        assertNotNull(sessionManager);
        Session s = sessionManager.getOrCreate("s-1", "user-1", "agent-1", Map.of("channel", "test"));
        s.addUserMessage("hello", Map.of());
        assertEquals(1, s.messages().size());
        assertEquals("USER", s.getHistory(10).get(0).role().name());
    }

    @Test
    void skill_executor_and_registry_are_wired() {
        assertNotNull(skillExecutor);
        assertNotNull(skillRegistry);
        // Registry starts empty because no root dir was configured.
        assertEquals(0, skillRegistry.size());
    }

    @Test
    void task_scheduler_is_wired() throws Exception {
        assertNotNull(taskScheduler);
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        String id = taskScheduler.scheduleDelay("smoke", 20, latch::countDown);
        assertTrue(latch.await(2, java.util.concurrent.TimeUnit.SECONDS));
        taskScheduler.cancel(id);
    }

    @Test
    void scheduled_task_event_listener_is_a_bean() {
        // Regression: the listener was @Component-annotated but the starter module
        // is not component-scanned, so scheduled tasks fired events into the void.
        assertNotNull(scheduledTaskEventListener,
            "ScheduledTaskEventListener must be registered as an explicit @Bean");
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class SmokeApp {

        /** Stub LLM client so the Agent bean can wire up. */
        @Bean
        LlmClient stubLlmClient() {
            return new LlmClient() {
                @Override public String provider() { return "stub"; }
                @Override public String primaryModel() { return "stub-model"; }
                @Override public CompletionResponse complete(CompletionRequest req) {
                    return CompletionResponse.builder().content("stub-response").build();
                }
                @Override public Flux<String> completeStream(CompletionRequest req) {
                    return Flux.just("stub");
                }
                @Override public CompletionResponse completeWithTools(CompletionRequest req,
                                                                       com.fasterxml.jackson.databind.JsonNode tools) {
                    return CompletionResponse.builder().content("stub-tools-response").build();
                }
            };
        }

        /** Stub long-term memory so ContextAssembler/Agent can wire up. */
        @Bean
        LongTermMemory stubLongTermMemory() {
            return new LongTermMemory() {
                @Override public Mono<Void> store(String userId, MemoryItem item) { return Mono.empty(); }
                @Override public Mono<Void> storeBatch(String userId, List<MemoryItem> items) { return Mono.empty(); }
                @Override public Mono<List<MemoryItem>> search(String userId, String query, int topK) { return Mono.just(List.of()); }
                @Override public Mono<List<MemoryItem>> searchByKeyword(String userId, String keyword) { return Mono.just(List.of()); }
                @Override public Mono<List<MemoryItem>> getAll(String userId) { return Mono.just(List.of()); }
                @Override public Mono<Boolean> delete(String memoryId) { return Mono.just(false); }
                @Override public Mono<Void> deleteAll(String userId) { return Mono.empty(); }
                @Override public Mono<Integer> compress(String userId) { return Mono.just(0); }
            };
        }
    }
}
