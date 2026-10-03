package com.gantang.axiflux.spring.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gantang.reaxon.api.llm.CompletionResponse;
import com.gantang.reaxon.api.llm.LlmClient;
import com.gantang.reaxon.api.llm.ModelRouter;
import com.gantang.reaxon.impl.agent.ReactiveAgent;
import com.gantang.reaxon.impl.llm.router.DefaultModelRouter;
import com.gantang.axiflux.spring.config.props.AgentProperties;
import com.gantang.axiflux.spring.config.props.AuthProperties;
import com.gantang.axiflux.spring.config.props.EmailProperties;
import com.gantang.axiflux.spring.config.props.LlmProperties;
import com.gantang.axiflux.spring.config.props.MemoryProperties;
import com.gantang.axiflux.spring.config.props.SchedulerProperties;
import com.gantang.axiflux.spring.config.props.SessionProperties;
import com.gantang.axiflux.spring.config.props.SkillsProperties;
import com.gantang.axiflux.spring.config.props.ToolsProperties;
import com.gantang.axiflux.spring.config.props.TtsProperties;
import com.gantang.axiflux.spring.config.props.VectorProperties;
import com.gantang.axiflux.spring.config.props.VisionProperties;
import com.gantang.axiflux.spring.config.props.WebProperties;
import com.gantang.axiflux.spring.config.props.WebsocketProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConfigControllerTest {

    /** Scope set an operator token must carry to reach any config endpoint. */
    private static final String ADMIN = "config:admin";

    private ObjectMapper om;
    private LlmProperties llm;
    private EmailProperties email;
    private VectorProperties vector;
    private DefaultModelRouter router;
    private ReactiveAgent agent;
    private ConfigController controller;

    /** Minimal LlmClient stub — only identity methods are used by the config API. */
    static LlmClient stubClient(String provider, String model) {
        return new LlmClient() {
            @Override public String provider() { return provider; }
            @Override public String primaryModel() { return model; }
            @Override public CompletionResponse complete(com.gantang.reaxon.api.llm.CompletionRequest r) {
                throw new UnsupportedOperationException();
            }
            @Override public Flux<String> completeStream(com.gantang.reaxon.api.llm.CompletionRequest r) {
                return Flux.empty();
            }
            @Override public CompletionResponse completeWithTools(com.gantang.reaxon.api.llm.CompletionRequest r, JsonNode t) {
                throw new UnsupportedOperationException();
            }
        };
    }

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> providerOf(T bean) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(bean);
        return p;
    }

    @BeforeEach
    void setUp() {
        om = new ObjectMapper();
        llm = new LlmProperties();
        llm.setOpenai(new LlmProperties.OpenAiProperties());
        llm.getOpenai().setApiKey("sk-secret-key-1234");
        email = new EmailProperties();
        email.setPassword("dbpassword");
        vector = new VectorProperties();
        vector.setApiKey("vector-secret");

        router = new DefaultModelRouter();
        router.register(stubClient("openai", "gpt-4o"));
        router.register(stubClient("anthropic", "claude-sonnet"));
        router.setDefaultProvider("openai");

        agent = mock(ReactiveAgent.class);
        when(agent.getMaxIterations()).thenReturn(10);

        controller = newController(providerOf((com.gantang.axiflux.spring.service.RuntimeConfigService) null));
    }

    private ConfigController newController(ObjectProvider<com.gantang.axiflux.spring.service.RuntimeConfigService> cfgSvc) {
        return new ConfigController(
            llm, new SchedulerProperties(), vector, new AgentProperties(),
            new ToolsProperties(), new SkillsProperties(), new WebProperties(),
            new SessionProperties(), new VisionProperties(), new TtsProperties(),
            email, new AuthProperties(), new MemoryProperties(), new WebsocketProperties(),
            om,
            providerOf((ModelRouter) router),
            providerOf((com.gantang.reaxon.api.agent.Agent) agent),
            providerOf(null),
            cfgSvc,
            providerOf((com.gantang.reaxon.api.config.LiveSettings) null));
    }

    @Test
    void getMasksSecretsButKeepsNonSensitiveValues() {
        Map<String, Object> resp = controller.get(ADMIN).block().data();
        assertNotNull(resp);
        JsonNode cfg = om.valueToTree(resp.get("config"));
        JsonNode runtime = om.valueToTree(resp.get("runtime"));

        // Secrets masked
        assertEquals("***", cfg.path("llm").path("openai").path("apiKey").asText());
        assertEquals("***", cfg.path("email").path("password").asText());
        assertEquals("***", cfg.path("vector").path("apiKey").asText());
        // Non-secret preserved
        assertEquals("gpt-4o", cfg.path("llm").path("openai").path("model").asText());
        assertEquals(587, cfg.path("email").path("port").asInt());

        // Runtime status
        assertEquals("openai", runtime.path("defaultProvider").asText());
        assertEquals(2, runtime.path("providers").size());
    }

    /** Block a Mono expected to fail with a ResponseStatusException; return its status. */
    private static int blockedStatus(reactor.core.publisher.Mono<?> mono) {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, mono::block);
        return ex.getStatusCode().value();
    }

    @Test
    void everyEndpointRejectsCallerWithoutConfigAdminScope() {
        // Config is deployment-wide: a plain user token must not read it (it echoes
        // provider/runtime topology) nor change tool policy, the file jail or routing.
        assertEquals(403, blockedStatus(controller.get("tool:net")));
        assertEquals(403, blockedStatus(controller.settings("tool:net")));
        assertEquals(403, blockedStatus(controller.setLive(
            new ConfigController.LiveValueRequest("tools.policy-mode", "all"), "tool:net")));
        assertEquals(403, blockedStatus(controller.routing(
            new ConfigController.RoutingRequest("anthropic"), null)));
        assertEquals(403, blockedStatus(controller.agent(
            new ConfigController.AgentRequest(20), "")));
        assertEquals(403, blockedStatus(controller.resetOverride("agent.max-iterations", "scheduler:admin")));

        // Nothing was applied.
        assertEquals("openai", router.getDefaultProvider());
        verify(agent, never()).withMaxIterations(anyInt());
    }

    @Test
    void wildcardDevScopeIsAcceptedAsConfigAdmin() {
        assertTrue(controller.get("*").block().success());
    }

    @Test
    void routingSwitchesKnownProvider() {
        var result = controller.routing(new ConfigController.RoutingRequest("anthropic"), ADMIN).block();
        assertTrue(result.success());
        assertEquals("anthropic", router.getDefaultProvider());
    }

    @Test
    void routingRejectsUnknownProvider() {
        assertEquals(400, blockedStatus(
            controller.routing(new ConfigController.RoutingRequest("nope"), ADMIN)));
        assertEquals("openai", router.getDefaultProvider());
    }

    @Test
    void routingRejectsBlankProvider() {
        assertEquals(400, blockedStatus(
            controller.routing(new ConfigController.RoutingRequest("  "), ADMIN)));
    }

    @Test
    void agentTuningAcceptsValidMaxIterations() {
        when(agent.getMaxIterations()).thenReturn(20);
        var result = controller.agent(new ConfigController.AgentRequest(20), ADMIN).block();
        assertTrue(result.success());
        verify(agent).withMaxIterations(20);
    }

    @Test
    void agentTuningRejectsOutOfRange() {
        assertEquals(400, blockedStatus(
            controller.agent(new ConfigController.AgentRequest(0), ADMIN)));
        verify(agent, never()).withMaxIterations(anyInt());
    }

    @Test
    void agentTuningRejectsMissing() {
        assertEquals(400, blockedStatus(
            controller.agent(new ConfigController.AgentRequest(null), ADMIN)));
    }

    @Test
    void validChangesArePersistedWhenServiceAvailable() {
        com.gantang.axiflux.spring.service.RuntimeConfigService svc =
            mock(com.gantang.axiflux.spring.service.RuntimeConfigService.class);
        ConfigController c2 = newController(providerOf(svc));

        var r1 = c2.routing(new ConfigController.RoutingRequest("anthropic"), ADMIN).block();
        assertTrue(r1.success());
        verify(svc).put(com.gantang.axiflux.spring.service.RuntimeConfigService.KEY_DEFAULT_PROVIDER,
            "anthropic", "api");

        var r2 = c2.agent(new ConfigController.AgentRequest(15), ADMIN).block();
        assertTrue(r2.success());
        verify(svc).put(com.gantang.axiflux.spring.service.RuntimeConfigService.KEY_MAX_ITERATIONS,
            "15", "api");
    }

    /** authz P2-4: arrays are recursed and token-shaped values masked even under innocuous keys. */
    @Test
    void maskSecrets_recursesArraysAndHeuristicValues() throws Exception {
        String json = """
            {
              "mcp": {
                "servers": [
                  { "name": "a", "env": [ {"API_TOKEN": "sk-abcdef1234567890XYZ"} ] },
                  { "name": "b", "note": "plain text stays" }
                ],
                "tokens": ["x", "y"]
              },
              "misc": {
                "webhook": "ghp_0123456789abcdefGHIJKLMNOP",
                "entropy": "a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6",
                "prose": "this is an ordinary sentence, definitely not a secret",
                "url": "https://example.com/api/v1"
              }
            }
            """;
        com.fasterxml.jackson.databind.node.ObjectNode node =
            (com.fasterxml.jackson.databind.node.ObjectNode) om.readTree(json);
        // invoke the private masker via the public GET path shape: call it directly
        var m = ConfigController.class.getDeclaredMethod("maskSecrets", JsonNode.class);
        m.setAccessible(true);
        m.invoke(controller, node);

        // secret-keyed string inside an object inside an array -> masked
        assertEquals("***", node.at("/mcp/servers/0/env/0/API_TOKEN").asText());
        // secret-keyed array -> masked wholesale
        assertEquals("***", node.at("/mcp/tokens").asText());
        // innocuous key but token-shaped value -> masked
        assertEquals("***", node.at("/misc/webhook").asText());
        assertEquals("***", node.at("/misc/entropy").asText());
        // ordinary values untouched
        assertEquals("plain text stays", node.at("/mcp/servers/1/note").asText());
        assertTrue(node.at("/misc/prose").asText().startsWith("this is an ordinary"));
        assertEquals("https://example.com/api/v1", node.at("/misc/url").asText());
    }

    /** infra P2-7: validation failure returns a generic message — no Jackson detail echoed. */
    @Test
    void validate_malformedJsonBudgets_returnsGenericMessage() {
        com.gantang.axiflux.spring.service.RuntimeConfigService svc =
            mock(com.gantang.axiflux.spring.service.RuntimeConfigService.class);
        ConfigController c2 = newController(providerOf(svc));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
            c2.setLive(new ConfigController.LiveValueRequest(
                "tools.auto-approve-budgets", "{not json: ,,,}"), ADMIN).block());
        assertEquals(400, ex.getStatusCode().value());
        assertNotNull(ex.getReason());
        assertTrue(ex.getReason().contains("value is invalid"), ex.getReason());
        assertTrue(ex.getReason().contains("tools.auto-approve-budgets"), ex.getReason());
        // no parser internals leaked
        assertFalse(ex.getReason().contains("Unexpected"), ex.getReason());
        assertFalse(ex.getReason().contains("JsonParse"), ex.getReason());
        assertFalse(ex.getReason().contains("{not json"), ex.getReason());
    }
}
