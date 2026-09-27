package com.gantang.tianshu.spring.auth;

import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Hoists a bearer token from the request query ({@code ?token=} /
 * {@code ?access_token=}) into the {@code Authorization} header so that
 * WebSocket handshakes — which cannot set HTTP headers in the browser API —
 * can still authenticate against the OAuth2 resource-server chain.
 *
 * <p>Runs before the Spring Security chain ({@code Ordered.HIGHEST_PRECEDENCE}
 * style ordering) and only acts when no Authorization header is already
 * present. Only active while {@code tianshu.auth.enabled=true}. The query
 * parameter is stripped from the forwarded request URI so the token does not
 * leak into downstream access logs or the WS session state.
 */
public class BearerTokenHoistFilter implements WebFilter, Ordered {

    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 50; // before security chain (-100)

    private final AuthTokenService tokenService;

    public BearerTokenHoistFilter(AuthTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (tokenService == null || !tokenService.isEnabled()) {
            return chain.filter(exchange);
        }
        HttpHeaders headers = exchange.getRequest().getHeaders();
        String existing = headers.getFirst(HttpHeaders.AUTHORIZATION);
        if (existing != null && !existing.isBlank()) {
            return chain.filter(exchange);
        }
        ServerHttpRequest request = exchange.getRequest();
        String token = request.getQueryParams().getFirst("token");
        if (token == null || token.isBlank()) {
            token = request.getQueryParams().getFirst("access_token");
        }
        if (token == null || token.isBlank()) {
            return chain.filter(exchange);
        }

        // Rebuild the query string without the token parameter(s).
        URI uri = request.getURI();
        Map<String, String> kept = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : request.getQueryParams().toSingleValueMap().entrySet()) {
            if (!"token".equals(e.getKey()) && !"access_token".equals(e.getKey())) {
                kept.put(e.getKey(), e.getValue());
            }
        }
        StringBuilder qs = new StringBuilder();
        kept.forEach((k, v) -> {
            if (qs.length() > 0) qs.append('&');
            qs.append(k).append('=').append(v == null ? "" : v);
        });
        StringBuilder newUri = new StringBuilder();
        newUri.append(uri.getRawPath());
        if (qs.length() > 0) newUri.append('?').append(qs);

        ServerHttpRequest mutated = request.mutate()
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token.trim())
            .path(newUri.toString())
            .build();
        return chain.filter(exchange.mutate().request(mutated).build());
    }
}
