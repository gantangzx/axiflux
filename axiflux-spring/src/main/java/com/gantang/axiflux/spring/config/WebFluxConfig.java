package com.gantang.axiflux.spring.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import com.gantang.reaxon.api.agent.Agent;
import com.gantang.reaxon.api.session.SessionManager;
import com.gantang.axiflux.spring.auth.CallerGuard;
import com.gantang.axiflux.spring.ws.AgentWebSocketHandler;
import com.gantang.axiflux.spring.config.props.AgentProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.server.RequestUpgradeStrategy;
import org.springframework.web.reactive.socket.server.WebSocketService;
import org.springframework.web.reactive.socket.server.support.HandshakeWebSocketService;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;
import org.springframework.web.reactive.socket.server.upgrade.ReactorNettyRequestUpgradeStrategy;
import org.springframework.http.CacheControl;
import org.springframework.web.server.WebFilter;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Reactive WebFlux + WebSocket configuration.
 *
 * <p>Registers the {@link AgentWebSocketHandler} at {@code /Axiflux/ws} with all
 * its required dependencies injected. The handler is only created when a real
 * {@link Agent} bean is available, so smoke tests without a full Agent still boot.
 *
 * <p>Endpoint: {@code ws://host:port/Axiflux/ws} (subprotocol {@code Axiflux}).
 *
 * <p>Deliberately NOT annotated with {@code @EnableWebFlux}: that annotation takes
 * over WebFlux configuration completely and disables Spring Boot's
 * WebFluxAutoConfiguration (static resource serving, welcome page, configurer
 * aggregation). We implement {@link WebFluxConfigurer} instead so Boot keeps
 * auto-configuring and our beans/filters simply participate.
 */
@Configuration
@ConditionalOnClass(WebSocketHandler.class)
public class WebFluxConfig implements WebFluxConfigurer {

    /**
     * Raise the WebFlux codec aggregation limit from the 256KB default so
     * multimodal chat requests (base64 images inline in JSON) are accepted.
     * 10MB accommodates a 2048px JPEG at ~85% quality plus protocol overhead.
     */
    @Override
    public void configureHttpMessageCodecs(org.springframework.http.codec.ServerCodecConfigurer configurer) {
        configurer.defaultCodecs().maxInMemorySize(10 * 1024 * 1024);
    }

    @Bean
    @ConditionalOnBean(Agent.class)
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "axiflux.websocket.enabled", havingValue = "true", matchIfMissing = true)
    public AgentWebSocketHandler agentWebSocketHandler(
            Agent agent,
            SessionManager sessionManager,
            ObjectMapper objectMapper,
            AgentProperties agentProps,
            CallerGuard callerGuard,
            org.springframework.beans.factory.ObjectProvider<com.gantang.axiflux.spring.service.QuotaServiceSpi> quotas
    ) {
        AgentWebSocketHandler handler = new AgentWebSocketHandler();
        handler.setAgent(agent);
        handler.setSessionManager(sessionManager);
        handler.setObjectMapper(objectMapper);
        handler.setAgentProperties(agentProps);
        handler.setCallerGuard(callerGuard);
        handler.setQuotaService(quotas.getIfAvailable());
        return handler;
    }

    @Bean
    @ConditionalOnBean(AgentWebSocketHandler.class)
    public HandlerMapping webSocketMapping(AgentWebSocketHandler handler) {
        Map<String, WebSocketHandler> map = new HashMap<>();
        map.put("/Axiflux/ws", handler);

        SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
        mapping.setOrder(-1); // before annotated controllers
        mapping.setUrlMap(map);
        return mapping;
    }

    @Bean
    @ConditionalOnBean(WebSocketHandler.class)
    public WebSocketHandlerAdapter webSocketHandlerAdapter(WebSocketService wsService) {
        return new WebSocketHandlerAdapter(wsService);
    }

    @Bean
    @ConditionalOnMissingBean
    public RequestUpgradeStrategy requestUpgradeStrategy() {
        return new ReactorNettyRequestUpgradeStrategy();
    }

    @Bean
    @ConditionalOnMissingBean
    public WebSocketService webSocketService(RequestUpgradeStrategy strategy) {
        return new HandshakeWebSocketService(strategy);
    }

    /**
     * Per-request traceId: MDC {@code traceId} + {@code X-Trace-Id} response header.
     * Implements {@link org.springframework.core.Ordered Ordered} HIGHEST_PRECEDENCE
     * so every downstream log line (auth, controllers, error handlers) is correlated.
     */
    @Bean
    @ConditionalOnMissingBean(com.gantang.axiflux.spring.web.TraceIdWebFilter.class)
    public com.gantang.axiflux.spring.web.TraceIdWebFilter traceIdWebFilter() {
        return new com.gantang.axiflux.spring.web.TraceIdWebFilter();
    }

    /**
     * Cache policy for the single-page console so a redeploy never strands users on
     * a stale bundle: the hashed assets under {@code /assets/} are cached
     * immutably, while the {@code index.html} entry point must always be
     * revalidated (it references the current hashed bundle names).
     */
    @Bean
    public WebFilter staticCacheControlFilter() {
        return (exchange, chain) -> {
            String path = exchange.getRequest().getPath().value();
            var headers = exchange.getResponse().getHeaders();
            if (path.startsWith("/assets/")) {
                headers.setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
            } else if (path.equals("/") || path.endsWith(".html")) {
                headers.setCacheControl(CacheControl.noCache().cachePublic());
            }
            return chain.filter(exchange);
        };
    }

    /**
     * OBO auth filter. When an {@link com.gantang.axiflux.spring.auth.AuthTokenService} bean is
     * present it enforces scoped bearer tokens on /api/** and injects the resolved
     * caller identity as internal headers.
     *
     * <p>Without that bean the filter still runs rather than being skipped: it
     * must strip client-supplied {@code X-axiflux-*} headers and substitute a
     * default identity. A pass-through here would let any client forge
     * {@code X-axiflux-User}/{@code X-axiflux-Scopes: *} and walk straight
     * past every ownership and admin-scope check downstream. When
     * {@code axiflux.auth.enabled=true} but the token service is missing, the
     * filter fails closed with 401 (audit authz P2-3); only an explicit
     * {@code enabled=false} selects the dev identity.
     */
    @Bean
    @ConditionalOnMissingBean(com.gantang.axiflux.spring.auth.AuthWebFilter.class)
    public WebFilter authWebFilter(
            org.springframework.beans.factory.ObjectProvider<com.gantang.axiflux.spring.auth.AuthTokenService> tokens,
            ObjectProvider<com.gantang.axiflux.spring.auth.JwtIdentityResolver> identityResolver,
            ObjectProvider<com.gantang.axiflux.spring.auth.AccountDirectorySpi> userAccounts,
            ObjectMapper objectMapper,
            com.gantang.axiflux.spring.config.props.AuthProperties authProperties) {
        boolean jit = authProperties != null && authProperties.isEnabled()
            && authProperties.isJitProvisioning();
        return new com.gantang.axiflux.spring.auth.AuthWebFilter(
            tokens.getIfAvailable(), objectMapper,
            authProperties != null && authProperties.isEnabled(),
            identityResolver.getIfAvailable(), jit ? userAccounts.getIfAvailable() : null);
    }

    /**
     * Hoists {@code ?token=} on the WebSocket upgrade into the Authorization
     * header so the resource-server chain can authenticate the handshake. Runs
     * before the security chain; a no-op when auth is disabled.
     */
    @Bean
    @ConditionalOnMissingBean(com.gantang.axiflux.spring.auth.BearerTokenHoistFilter.class)
    public WebFilter bearerTokenHoistFilter(
            org.springframework.beans.factory.ObjectProvider<com.gantang.axiflux.spring.auth.AuthTokenService> tokens) {
        com.gantang.axiflux.spring.auth.AuthTokenService svc = tokens.getIfAvailable();
        if (svc == null) {
            return (exchange, chain) -> chain.filter(exchange);
        }
        return new com.gantang.axiflux.spring.auth.BearerTokenHoistFilter(svc);
    }
}
