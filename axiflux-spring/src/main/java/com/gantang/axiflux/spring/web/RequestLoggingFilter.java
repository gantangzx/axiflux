package com.gantang.axiflux.spring.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Set;

/**
 * Global HTTP access log for the reactive stack.
 *
 * <p>Logs one line per request: {@code METHOD path -> status (Xms)}, mirroring
 * what a reverse-proxy access log would capture. Runs as the outermost filter so
 * it times the full handler execution including the agent/LLM call.
 *
 * <p>Actuator, static assets and the API docs are noisily excluded to keep the
 * operational log focused on real traffic.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    /** Path prefixes that produce no business value in the access log. */
    private static final Set<String> SILENT_PREFIXES = Set.of(
        "/actuator", "/swagger-ui", "/v3/api-docs", "/webjars", "/favicon.ico"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (isSilent(path)) {
            return chain.filter(exchange);
        }

        long start = System.currentTimeMillis();
        String method = exchange.getRequest().getMethod().name();
        // Audit infra P2-4 (compliance note): the remote IP is personal data
        // (PII) under GDPR/CCPA-style regimes. This log line is a deliberate
        // operational access log — deployments with compliance obligations
        // must configure log retention/access control for it (or mask the
        // trailing octet at the logback pattern level). Do NOT add further
        // per-request identifiers (user agent, user id) here without
        // reviewing that policy.
        String remote = exchange.getRequest().getRemoteAddress() != null
            ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress() : "-";

        return chain.filter(exchange)
            .doFinally(signal -> {
                HttpStatusCode status = exchange.getResponse().getStatusCode();
                int code = status != null ? status.value() : 0;
                long elapsed = System.currentTimeMillis() - start;
                // `path` comes from getPath().value() — the decoded path WITHOUT
                // the query string, so credentials in ?token= (WebSocket hoist,
                // api_key=...) can never land in the access log. Keep it that
                // way: never log getURI().getQuery() here (audit infra P2-4).
                // SSE streaming requests end on CANCEL; that is normal for the
                // chat/stream endpoint, not an error.
                String note = "cancel".equals(signal.name().toLowerCase()) && path.endsWith("/stream")
                    ? " (stream closed)" : "";
                if (code >= 500) {
                    log.error("{} {} -> {} {}ms by {}{}", method, path,
                        code, elapsed, remote, note);
                } else if (code >= 400) {
                    log.warn("{} {} -> {} {}ms by {}{}", method, path,
                        code, elapsed, remote, note);
                } else {
                    log.info("{} {} -> {} {}ms by {}{}", method, path, code, elapsed, remote, note);
                }
            });
    }

    private boolean isSilent(String path) {
        for (String prefix : SILENT_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

}
