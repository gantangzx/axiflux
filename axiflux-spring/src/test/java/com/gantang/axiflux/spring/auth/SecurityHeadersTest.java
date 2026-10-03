package com.gantang.axiflux.spring.auth;

import com.gantang.reaxon.api.llm.CompletionRequest;
import com.gantang.reaxon.api.llm.CompletionResponse;
import com.gantang.reaxon.api.llm.LlmClient;
import com.gantang.reaxon.api.memory.LongTermMemory;
import com.gantang.reaxon.api.memory.MemoryItem;
import com.gantang.axiflux.spring.config.props.CorsProperties;
import com.gantang.axiflux.spring.config.props.WebProperties;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the security response headers and CORS posture are actually emitted
 * on the wire, not merely parsed into config objects.
 *
 * <p>Headers are asserted on the dev/open chain (auth disabled) because that is
 * the weakest configuration: if the hardening holds there it holds everywhere.
 */
class SecurityHeadersTest {

    /** Property block shared by every nested context: no DB, no Redis, no actuator. */
    private static final String EXCLUDES = "spring.autoconfigure.exclude="
        + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
        + "org.springframework.boot.jdbc.autoconfigure.DataSourceInitializationAutoConfiguration,"
        + "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration,"
        + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
        + "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration,"
        + "org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration,"
        + "org.springframework.boot.jdbc.autoconfigure.health.DataSourceHealthContributorAutoConfiguration";

    // ==================== default (hardened) posture ====================

    @Nested
    @SpringBootTest(classes = HeadersApp.class)
    @TestPropertySource(properties = {
        EXCLUDES,
        "axiflux.vector.provider=none",
        "spring.main.web-application-type=reactive",
        "management.endpoints.enabled-by-default=false",
        "management.health.defaults.enabled=false",
        "axiflux.auth.enabled=false"
    })
    class Defaults {
        @Autowired ApplicationContext ctx;

        private WebTestClient http() {
            return WebTestClient.bindToApplicationContext(ctx)
                .configureClient().baseUrl("http://localhost").build();
        }

        @Test
        void cspIsEnforcingAndAllowsWhatTheConsoleActuallyNeeds() {
            String csp = http().get().uri("/").exchange()
                .returnResult(byte[].class).getResponseHeaders()
                .getFirst("Content-Security-Policy");

            assertNotNull(csp, "CSP must be present in enforcing mode");
            // Strict where it matters: scripts may only come from our own origin.
            assertTrue(csp.contains("script-src 'self'"), csp);
            assertFalse(csp.contains("script-src 'self' 'unsafe-inline'"), "script-src must stay strict: " + csp);
            assertFalse(csp.contains("unsafe-eval"), csp);
            // Documented relaxation: antd v6 CSS-in-JS injects <style> at runtime.
            assertTrue(csp.contains("style-src 'self' 'unsafe-inline'"), csp);
            // ChatPage renders attachment previews as data: URLs.
            assertTrue(csp.contains("img-src 'self' data:"), csp);
            assertTrue(csp.contains("frame-ancestors 'none'"), csp);
            assertTrue(csp.contains("object-src 'none'"), csp);
            // Audit infra P2-1: workers stay in-origin; this page embeds no sub-frames.
            assertTrue(csp.contains("worker-src 'self'"), csp);
            assertTrue(csp.contains("frame-src 'none'"), csp);
            assertTrue(csp.contains("base-uri 'self'"), csp);
        }

        @Test
        void reportOnlyHeaderIsAbsentWhenEnforcing() {
            assertNull(http().get().uri("/").exchange()
                .returnResult(byte[].class).getResponseHeaders()
                .getFirst("Content-Security-Policy-Report-Only"));
        }

        @Test
        void nosniffFrameOptionsReferrerAndPermissionsAreSet() {
            var h = http().get().uri("/").exchange()
                .returnResult(byte[].class).getResponseHeaders();
            assertEquals("nosniff", h.getFirst("X-Content-Type-Options"));
            assertEquals("DENY", h.getFirst("X-Frame-Options"));
            assertEquals("no-referrer", h.getFirst("Referrer-Policy"));
            assertNotNull(h.getFirst("Permissions-Policy"));
            assertTrue(h.getFirst("Permissions-Policy").contains("camera=()"),
                h.getFirst("Permissions-Policy"));
        }

        @Test
        void hstsIsNotSentOverPlainHttp() {
            // Spring's writer only emits HSTS on secure exchanges; asserting this
            // keeps us honest that enabling it by default is safe on a dev port.
            assertNull(http().get().uri("/").exchange()
                .returnResult(byte[].class).getResponseHeaders()
                .getFirst("Strict-Transport-Security"));
        }

        @Test
        void noCorsHeadersWhenCorsDisabled() {
            var h = http().get().uri("/api/v1/models")
                .header("Origin", "https://evil.example.com")
                .exchange().returnResult(byte[].class).getResponseHeaders();
            assertNull(h.getFirst("Access-Control-Allow-Origin"),
                "same-origin console must not advertise CORS");
        }

        @Test
        void headersAlsoAppliedToApiPathsNotJustStaticConsole() {
            var h = http().get().uri("/api/v1/models").exchange()
                .returnResult(byte[].class).getResponseHeaders();
            assertNotNull(h.getFirst("Content-Security-Policy"));
            assertEquals("nosniff", h.getFirst("X-Content-Type-Options"));
        }
    }

    // ==================== CORS allowlist enabled ====================

    @Nested
    @SpringBootTest(classes = HeadersApp.class)
    @TestPropertySource(properties = {
        EXCLUDES,
        "axiflux.vector.provider=none",
        "spring.main.web-application-type=reactive",
        "management.endpoints.enabled-by-default=false",
        "management.health.defaults.enabled=false",
        "axiflux.auth.enabled=false",
        "axiflux.web.cors.enabled=true",
        "axiflux.web.cors.allowed-origins[0]=https://console.example.com",
        "axiflux.web.cors.allow-credentials=true"
    })
    class CorsAllowlist {
        @Autowired ApplicationContext ctx;

        private WebTestClient http() {
            return WebTestClient.bindToApplicationContext(ctx)
                .configureClient().baseUrl("http://localhost").build();
        }

        @Test
        void allowedOriginIsEchoedWithCredentials() {
            var h = http().get().uri("/api/v1/models")
                .header("Origin", "https://console.example.com")
                .exchange().returnResult(byte[].class).getResponseHeaders();
            assertEquals("https://console.example.com", h.getFirst("Access-Control-Allow-Origin"));
            assertEquals("true", h.getFirst("Access-Control-Allow-Credentials"));
        }

        @Test
        void disallowedOriginIsRejected() {
            // Spring's CORS filter short-circuits a non-matching origin with 403
            // and never echoes the origin back.
            var res = http().get().uri("/api/v1/models")
                .header("Origin", "https://evil.example.com")
                .exchange();
            res.expectStatus().isForbidden();
            assertNull(res.returnResult(byte[].class).getResponseHeaders()
                .getFirst("Access-Control-Allow-Origin"));
        }

        @Test
        void preflightIsAnsweredForAllowedOrigin() {
            http().options().uri("/api/v1/models")
                .header("Origin", "https://console.example.com")
                .header("Access-Control-Request-Method", "POST")
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "https://console.example.com");
        }

        @Test
        void corsDoesNotApplyOutsideApiPaths() {
            var h = http().get().uri("/")
                .header("Origin", "https://console.example.com")
                .exchange().returnResult(byte[].class).getResponseHeaders();
            assertNull(h.getFirst("Access-Control-Allow-Origin"),
                "CORS is registered for /api/** only");
        }
    }

    // ==================== opt-out / rollout modes ====================

    @Nested
    @SpringBootTest(classes = HeadersApp.class)
    @TestPropertySource(properties = {
        EXCLUDES,
        "axiflux.vector.provider=none",
        "spring.main.web-application-type=reactive",
        "management.endpoints.enabled-by-default=false",
        "management.health.defaults.enabled=false",
        "axiflux.auth.enabled=false",
        "axiflux.web.headers.csp-report-only=true",
        "axiflux.web.headers.frame-options=SAMEORIGIN",
        "axiflux.web.headers.referrer-policy=strict-origin-when-cross-origin",
        "axiflux.web.headers.permissions-policy=",
        "axiflux.web.headers.hsts-max-age-seconds=0"
    })
    class RolloutTuning {
        @Autowired ApplicationContext ctx;

        @Test
        void reportOnlyMovesCspToTheReportHeaderAndHonoursOverrides() {
            var h = WebTestClient.bindToApplicationContext(ctx).configureClient().build()
                .get().uri("/").exchange().returnResult(byte[].class).getResponseHeaders();
            assertNotNull(h.getFirst("Content-Security-Policy-Report-Only"),
                "report-only mode must emit the Report-Only header");
            assertNull(h.getFirst("Content-Security-Policy"),
                "report-only must not also enforce");
            assertEquals("SAMEORIGIN", h.getFirst("X-Frame-Options"));
            assertEquals("strict-origin-when-cross-origin", h.getFirst("Referrer-Policy"));
            assertNull(h.getFirst("Permissions-Policy"), "blank value disables the header");
        }
    }

    // ==================== pure unit checks (no Spring context) ====================

    @Nested
    class Mapping {
        @Test
        void referrerPolicyAcceptsHeaderStyleValues() {
            assertEquals("no-referrer",
                SecurityConfig.referrerPolicy("no-referrer").getPolicy());
            assertEquals("strict-origin-when-cross-origin",
                SecurityConfig.referrerPolicy("strict-origin-when-cross-origin").getPolicy());
        }

        @Test
        void badReferrerPolicyFailsLoudlyWithGuidance() {
            var e = assertThrows(IllegalStateException.class,
                () -> SecurityConfig.referrerPolicy("nonsense"));
            assertTrue(e.getMessage().contains("referrer-policy"), e.getMessage());
            assertTrue(e.getMessage().contains("no-referrer"), "must list valid values");
        }

        @Test
        void frameOptionsAcceptsBothSpellings() {
            assertEquals("DENY", SecurityConfig.frameOptionsMode("DENY").name());
            assertEquals("SAMEORIGIN", SecurityConfig.frameOptionsMode("same-origin").name());
            assertEquals("SAMEORIGIN", SecurityConfig.frameOptionsMode("SAMEORIGIN").name());
        }

        @Test
        void badFrameOptionsFailsLoudly() {
            assertThrows(IllegalStateException.class,
                () -> SecurityConfig.frameOptionsMode("ALLOW-FROM"));
        }
    }

    @Nested
    class CorsValidation {

        private WebProperties propsWithCors(java.util.function.Consumer<CorsProperties> tune) {
            WebProperties p = new WebProperties();
            p.getCors().setEnabled(true);
            tune.accept(p.getCors());
            return p;
        }

        @Test
        void wildcardOriginWithCredentialsIsRejectedAtStartup() {
            WebProperties p = propsWithCors(c -> {
                c.setAllowedOrigins(new java.util.ArrayList<>(List.of("*")));
                c.setAllowCredentials(true);
            });
            var e = assertThrows(IllegalStateException.class,
                () -> new SecurityConfig().AxifluxCorsConfigurationSource(p));
            assertTrue(e.getMessage().contains("allow-credentials"), e.getMessage());
            assertTrue(e.getMessage().contains("allowed-origin-patterns"),
                "error must point at the supported alternative");
        }

        @Test
        void enabledWithNoOriginsIsRejected() {
            WebProperties p = propsWithCors(c -> { });
            var e = assertThrows(IllegalStateException.class,
                () -> new SecurityConfig().AxifluxCorsConfigurationSource(p));
            assertTrue(e.getMessage().contains("allowed-origins"), e.getMessage());
        }

        @Test
        void wildcardWithoutCredentialsIsAllowed() {
            WebProperties p = propsWithCors(c -> {
                c.setAllowedOrigins(new java.util.ArrayList<>(List.of("*")));
                c.setAllowCredentials(false);
            });
            assertNotNull(new SecurityConfig().AxifluxCorsConfigurationSource(p));
        }

        @Test
        void originPatternsSatisfyTheOriginRequirement() {
            WebProperties p = propsWithCors(c -> {
                c.setAllowedOriginPatterns(new java.util.ArrayList<>(List.of("https://*.example.com")));
                c.setAllowCredentials(true);
            });
            assertNotNull(new SecurityConfig().AxifluxCorsConfigurationSource(p));
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class HeadersApp {
        @Bean
        LlmClient stubLlmClient() {
            return new LlmClient() {
                @Override public String provider() { return "stub"; }
                @Override public String primaryModel() { return "stub-model"; }
                @Override public CompletionResponse complete(CompletionRequest req) {
                    return CompletionResponse.builder().content("stub").build();
                }
                @Override public Flux<String> completeStream(CompletionRequest req) {
                    return Flux.just("stub");
                }
                @Override public CompletionResponse completeWithTools(CompletionRequest req,
                                                                       com.fasterxml.jackson.databind.JsonNode tools) {
                    return CompletionResponse.builder().content("stub").build();
                }
            };
        }

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
