package com.gantang.tianshu.spring.web;

import io.micrometer.context.ContextRegistry;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Assigns every HTTP request a traceId, exposes it as the {@code X-Trace-Id}
 * response header and publishes it under the SLF4J {@code traceId} MDC key so
 * all log lines emitted while handling the request are correlated.
 *
 * <p><b>Anti-forgery (P1-6):</b> the request's own traceId is ALWAYS generated
 * on the server. An inbound {@code X-Trace-Id} / {@code X-Request-Id} header is
 * never used as this request's traceId — otherwise an attacker could pick a
 * victim's id (or a fixed id) and make their malicious requests "collide" with
 * unrelated sessions in the logs, corrupting traceId-based audit correlation and
 * alert attribution. When well-formed, the inbound value is preserved under the
 * separate {@code parentTraceId} MDC/Reactor key (and {@code X-Parent-Trace-Id}
 * response header) so cross-service traces can still be reconstructed without
 * trusting the caller-supplied value as the local correlation key.
 *
 * <p>WebFlux executes across thread pools, so raw ThreadLocal MDC state would be
 * lost on the first scheduler hop. The traceId is also placed in the Reactor
 * {@link Context}; with {@code io.micrometer:context-propagation} on the classpath
 * (Spring Boot enables automatic context propagation) the registered
 * {@link ContextRegistry} accessor restores the MDC value on every operator
 * thread. The MDC keys are always cleared when the request completes.
 */
public final class TraceIdWebFilter implements WebFilter, Ordered {

    public static final String MDC_KEY = "traceId";
    public static final String CONTEXT_KEY = "traceId";
    public static final String HEADER = "X-Trace-Id";

    /** MDC/Reactor key for the (untrusted, informational) caller-supplied id. */
    public static final String PARENT_MDC_KEY = "parentTraceId";
    public static final String PARENT_CONTEXT_KEY = "parentTraceId";
    public static final String PARENT_HEADER = "X-Parent-Trace-Id";

    /** Inbound ids must look like a plausible request id: 8-64 chars, safe charset. */
    private static final Pattern SAFE_INBOUND = Pattern.compile("[A-Za-z0-9._-]{8,64}");

    static {
        // Bridge Reactor Context -> SLF4J MDC for every thread hop.
        ContextRegistry.getInstance().registerThreadLocalAccessor(
                MDC_KEY,
                () -> MDC.get(MDC_KEY),
                v -> {
                    if (v != null) {
                        MDC.put(MDC_KEY, v);
                    } else {
                        MDC.remove(MDC_KEY);
                    }
                },
                () -> MDC.remove(MDC_KEY));
        ContextRegistry.getInstance().registerThreadLocalAccessor(
                PARENT_MDC_KEY,
                () -> MDC.get(PARENT_MDC_KEY),
                v -> {
                    if (v != null) {
                        MDC.put(PARENT_MDC_KEY, v);
                    } else {
                        MDC.remove(PARENT_MDC_KEY);
                    }
                },
                () -> MDC.remove(PARENT_MDC_KEY));
    }

    @Override
    public int getOrder() {
        // Outermost filter: correlate every downstream log line, including auth.
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        // Always server-generate the local traceId (anti-forgery); the inbound
        // header is demoted to an informational parent id, never the local key.
        String traceId = newTraceId();
        String parentTraceId = resolveInbound(request.getHeaders());

        exchange.getResponse().getHeaders().set(HEADER, traceId);
        if (parentTraceId != null) {
            exchange.getResponse().getHeaders().set(PARENT_HEADER, parentTraceId);
        }
        MDC.put(MDC_KEY, traceId);
        if (parentTraceId != null) {
            MDC.put(PARENT_MDC_KEY, parentTraceId);
        }
        Context ctx = parentTraceId != null
                ? Context.of(CONTEXT_KEY, traceId, PARENT_CONTEXT_KEY, parentTraceId)
                : Context.of(CONTEXT_KEY, traceId);
        return chain.filter(exchange)
                .contextWrite(ctx)
                .doFinally(signal -> {
                    MDC.remove(MDC_KEY);
                    MDC.remove(PARENT_MDC_KEY);
                });
    }

    /**
     * Extract a well-formed inbound id to keep as the (untrusted) parent trace id,
     * or {@code null} when absent/malformed. The value is never reused as the
     * local traceId.
     */
    private static String resolveInbound(HttpHeaders headers) {
        String inbound = headers.getFirst(HEADER);
        if (inbound == null || inbound.isBlank()) {
            inbound = headers.getFirst("X-Request-Id");
        }
        if (inbound != null) {
            String trimmed = inbound.trim();
            if (SAFE_INBOUND.matcher(trimmed).matches()) {
                return trimmed;
            }
        }
        return null;
    }

    private static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
