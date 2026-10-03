package com.gantang.reaxon.impl.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gantang.reaxon.api.agent.AgentContext;
import com.gantang.reaxon.api.agent.AgentDefinition;
import com.gantang.reaxon.api.agent.AgentDirectory;
import com.gantang.reaxon.api.agent.AgentResponse;
import com.gantang.reaxon.api.llm.CompletionRequest;
import com.gantang.reaxon.api.llm.LlmClient;
import com.gantang.reaxon.api.llm.ModelRouter;
import com.gantang.reaxon.api.session.Message;
import com.gantang.reaxon.impl.memory.DefaultContextAssembler;
import com.gantang.reaxon.impl.tool.DefaultToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@link ReactiveAgent#effectiveContext} composes an agent's identity
 * files (AGENTS.md / SOUL.md / USER.md + base systemPrompt) into the system prompt
 * in the native-Axiflux order, and that a per-turn systemPrompt is prepended as a
 * higher-priority override without dropping the persona identity block.
 */
class ReactiveAgentIdentityFilesTest {

    private static final ObjectMapper OM = new ObjectMapper();

    private ReactiveAgentTest.InMemorySessionManager sessionManager;
    private CapturingLlmClient llm;
    private ReactiveAgent agent;

    @BeforeEach
    void setUp() {
        sessionManager = new ReactiveAgentTest.InMemorySessionManager();
        llm = new CapturingLlmClient();
        ModelRouter router = ctx -> llm;
        agent = new ReactiveAgent(router, new DefaultToolRegistry(),
            new DefaultContextAssembler(null, 50, 5), null, sessionManager, OM);
    }

    private AgentDefinition def(String soul, String user, String agents, String sysPrompt) {
        return new AgentDefinition("default", "默认助手", "desc", "🤖",
            sysPrompt, soul, user, agents, null, true, true, List.of(), null,
            Instant.now(), Instant.now(), false, null);
    }

    private void useDirectory(AgentDefinition d) {
        AgentDirectory dir = new AgentDirectory() {
            @Override public List<AgentDefinition> list() { return List.of(d); }
            @Override public List<AgentDefinition> listTemplates() { return List.of(); }
            @Override public Optional<AgentDefinition> get(String id) { return Optional.of(d); }
            @Override public AgentDefinition save(AgentDefinition x) { return x; }
            @Override public boolean delete(String id) { return false; }
        };
        agent.withAgentDirectory(dir);
    }

    private AgentContext ctx(String sessionId, String turnSystemPrompt) {
        return AgentContext.builder()
            .sessionId(sessionId).userId("u1")
            .currentQuery("hi")
            .systemPrompt(turnSystemPrompt)
            .build();
    }

    private String firstSystemContent() {
        List<Message> msgs = llm.lastRequest.get().messages();
        assertFalse(msgs.isEmpty(), "LLM request must carry messages");
        Message sys = msgs.stream().filter(m -> m.role() == Message.Role.SYSTEM).findFirst()
            .orElseThrow(() -> new AssertionError("no system message composed"));
        return sys.content();
    }

    @Test
    void identityFiles_composedInOrder_whenNoTurnSystemPrompt() {
        useDirectory(def("be blunt", "user is a backend dev", "always read code first", "base prompt"));
        StepVerifier.create(agent.process(ctx("s1", null)))
            .assertNext(r -> assertEquals(AgentResponse.Status.SUCCESS, r.status()))
            .verifyComplete();

        String sys = firstSystemContent();
        int agentsIdx = sys.indexOf("always read code first");
        int soulIdx = sys.indexOf("be blunt");
        int userIdx = sys.indexOf("user is a backend dev");
        int baseIdx = sys.indexOf("base prompt");
        assertTrue(agentsIdx >= 0 && soulIdx >= 0 && userIdx >= 0 && baseIdx >= 0,
            "all four identity parts present, got: " + sys);
        assertTrue(agentsIdx < soulIdx && soulIdx < userIdx && userIdx < baseIdx,
            "order must be AGENTS -> SOUL -> USER -> base systemPrompt, got: " + sys);
        assertTrue(sys.contains("AGENTS.md") && sys.contains("SOUL.md") && sys.contains("USER.md"),
            "file markers present, got: " + sys);
    }

    @Test
    void turnSystemPrompt_prependedWithoutDroppingIdentity() {
        useDirectory(def("be blunt", null, null, null));
        StepVerifier.create(agent.process(ctx("s2", "turn override")))
            .assertNext(r -> assertEquals(AgentResponse.Status.SUCCESS, r.status()))
            .verifyComplete();

        String sys = firstSystemContent();
        assertTrue(sys.startsWith("turn override"), "turn prompt first, got: " + sys);
        assertTrue(sys.indexOf("turn override") < sys.indexOf("be blunt"),
            "identity block retained after turn override, got: " + sys);
    }

    @Test
    void absentFiles_omitted_cleanly() {
        useDirectory(def(null, "user only", null, null));
        StepVerifier.create(agent.process(ctx("s3", null)))
            .assertNext(r -> assertEquals(AgentResponse.Status.SUCCESS, r.status()))
            .verifyComplete();

        String sys = firstSystemContent();
        assertTrue(sys.contains("user only") && sys.contains("USER.md"));
        assertFalse(sys.contains("AGENTS.md"), "absent file marker must be omitted, got: " + sys);
        assertFalse(sys.contains("SOUL.md"), "absent file marker must be omitted, got: " + sys);
    }

    @Test
    void noIdentityFiles_fallsBackToBaseSystemPrompt() {
        useDirectory(def(null, null, null, "only base prompt"));
        StepVerifier.create(agent.process(ctx("s4", null)))
            .assertNext(r -> assertEquals(AgentResponse.Status.SUCCESS, r.status()))
            .verifyComplete();

        String sys = firstSystemContent();
        assertTrue(sys.contains("only base prompt"), "base prompt survives, got: " + sys);
        // No identity-file headers are added when no identity file is set. (Later
        // appends such as the security-rules section may use '##' headings of their
        // own, so we assert on the absence of the file markers, not on '##'.)
        assertFalse(sys.contains("AGENTS.md"), "no AGENTS.md marker, got: " + sys);
        assertFalse(sys.contains("SOUL.md"), "no SOUL.md marker, got: " + sys);
        assertFalse(sys.contains("USER.md"), "no USER.md marker, got: " + sys);
    }

    /** Captures the last completion request so tests can inspect the composed prompt. */
    static class CapturingLlmClient implements LlmClient {
        final AtomicReference<CompletionRequest> lastRequest = new AtomicReference<>();

        @Override public String provider() { return "stub"; }
        @Override public String primaryModel() { return "stub-model"; }
        @Override public int contextWindowTokens() { return 128_000; }

        @Override
        public com.gantang.reaxon.api.llm.CompletionResponse complete(CompletionRequest request) {
            lastRequest.set(request);
            return com.gantang.reaxon.api.llm.CompletionResponse.builder().content("ok").build();
        }

        @Override
        public Flux<String> completeStream(CompletionRequest request) {
            lastRequest.set(request);
            return Flux.just("ok");
        }

        @Override
        public com.gantang.reaxon.api.llm.CompletionResponse completeWithTools(CompletionRequest request,
                com.fasterxml.jackson.databind.JsonNode tools) {
            lastRequest.set(request);
            return com.gantang.reaxon.api.llm.CompletionResponse.builder().content("ok").toolCalls(List.of()).build();
        }
    }
}
