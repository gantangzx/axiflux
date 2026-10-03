package com.gantang.axiflux.spring.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies traceId generation, response header echo, MDC propagation into the
 * filter chain and MDC cleanup after completion — plus the P1-6 anti-forgery
 * contract: an inbound {@code X-Trace-Id} is NEVER used as this request's
 * traceId; it is preserved only as the untrusted {@code parentTraceId}.
 */
class TraceIdWebFilterTest {

    private final TraceIdWebFilter filter = new TraceIdWebFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void generatesIdWhenNoInboundHeader() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/models").build());
        AtomicReference<String> mdcInChain = new AtomicReference<>();

        WebFilterChain chain = ex -> Mono.fromRunnable(
                () -> mdcInChain.set(MDC.get(TraceIdWebFilter.MDC_KEY)));

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        String responseId = exchange.getResponse().getHeaders().getFirst(TraceIdWebFilter.HEADER);
        assertNotNull(responseId);
        assertEquals(32, responseId.length(), "hex UUID without dashes is 32 chars");
        assertEquals(responseId, mdcInChain.get(), "MDC must be populated inside the chain");
        assertNull(MDC.get(TraceIdWebFilter.MDC_KEY), "MDC must be cleared after the request");
    }

    /**
     * P1-6: an inbound X-Trace-Id must NOT be honored as the local traceId.
     * The response/MDC traceId is server-generated; the inbound value survives
     * only under parentTraceId so cross-service traces remain reconstructable
     * without letting a caller forge the local correlation key.
     */
    @Test
    void inboundTraceIdIsDemotedToParentNotUsedAsLocalId() {
        String inbound = "upstream-trace-12345";
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/models")
                        .header(TraceIdWebFilter.HEADER, inbound)
                        .build());
        AtomicReference<String> mdcTrace = new AtomicReference<>();
        AtomicReference<String> mdcParent = new AtomicReference<>();
        WebFilterChain chain = ex -> Mono.fromRunnable(() -> {
            mdcTrace.set(MDC.get(TraceIdWebFilter.MDC_KEY));
            mdcParent.set(MDC.get(TraceIdWebFilter.PARENT_MDC_KEY));
        });

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        String responseId = exchange.getResponse().getHeaders().getFirst(TraceIdWebFilter.HEADER);
        assertNotNull(responseId);
        assertNotEquals(inbound, responseId, "inbound id must NOT become the local traceId");
        assertEquals(32, responseId.length(), "local traceId is a server-generated hex UUID");
        assertEquals(responseId, mdcTrace.get(), "MDC traceId is the server-generated id");
        assertEquals(inbound, mdcParent.get(), "inbound id is kept only as parentTraceId");
        assertEquals(inbound,
                exchange.getResponse().getHeaders().getFirst(TraceIdWebFilter.PARENT_HEADER),
                "parent id echoed back for cross-service correlation");
        assertNull(MDC.get(TraceIdWebFilter.PARENT_MDC_KEY), "parentTraceId cleared after request");
    }

    @Test
    void requestIdHeaderAlsoDemotedToParent() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/models")
                        .header("X-Request-Id", "req-id-abcdefgh")
                        .build());
        WebFilterChain chain = ex -> Mono.empty();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        String responseId = exchange.getResponse().getHeaders().getFirst(TraceIdWebFilter.HEADER);
        assertNotEquals("req-id-abcdefgh", responseId);
        assertEquals("req-id-abcdefgh",
                exchange.getResponse().getHeaders().getFirst(TraceIdWebFilter.PARENT_HEADER));
    }

    @Test
    void noParentHeaderWhenInboundMalformed() {
        // Too short / illegal characters must not be reflected (header injection, log forgery).
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/models")
                        .header(TraceIdWebFilter.HEADER, "x\nEvil: injected")
                        .build());
        WebFilterChain chain = ex -> Mono.empty();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        String id = exchange.getResponse().getHeaders().getFirst(TraceIdWebFilter.HEADER);
        assertNotNull(id);
        assertEquals(32, id.length());
        assertNull(exchange.getResponse().getHeaders().getFirst(TraceIdWebFilter.PARENT_HEADER),
                "malformed inbound id is dropped entirely, not even as parent");
    }

    @Test
    void clearsMdcEvenWhenChainErrors() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/models").build());
        WebFilterChain chain = ex -> Mono.error(new IllegalStateException("boom"));

        StepVerifier.create(filter.filter(exchange, chain))
                .expectErrorMessage("boom")
                .verify();

        assertNull(MDC.get(TraceIdWebFilter.MDC_KEY), "MDC must be cleared even on error");
        assertNull(MDC.get(TraceIdWebFilter.PARENT_MDC_KEY), "parentTraceId must be cleared even on error");
    }
}
