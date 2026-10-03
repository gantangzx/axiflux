package com.gantang.axiflux.spring.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import com.gantang.axiflux.spring.auth.AuthWebFilter;

import java.util.NoSuchElementException;

/**
 * Global translation of uncaught controller errors into the uniform
 * {@link ApiResponse} envelope with a meaningful status code.
 *
 * <p>Registered as a bean by {@code AxifluxWebConfiguration} (the starter is
 * consumed from a different base package, so component scanning does not pick
 * up advice annotations automatically).
 *
 * <ul>
 *   <li>{@link ResponseStatusException} — status/reason preserved (4xx reason
 *       surfaced, 5xx collapsed to the generic reason phrase to avoid leaking
 *       internals).</li>
 *   <li>{@link IllegalArgumentException} — 400 by default; a "not found"
 *       message (the common {@code switchIfEmpty(Mono.error(...))} pattern)
 *       becomes 404.</li>
 *   <li>{@link NoSuchElementException} — 404.</li>
 *   <li>Anything else — 500 with a generic message; details go to the log.</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Optional funnel tracker; null when the funnel feature is not wired. */
    private final com.gantang.axiflux.spring.observability.FunnelTrackerSpi funnel;

    public GlobalExceptionHandler() {
        this(null);
    }

    public GlobalExceptionHandler(com.gantang.axiflux.spring.observability.FunnelTrackerSpi funnel) {
        this.funnel = funnel;
    }

    @ExceptionHandler(ResponseStatusException.class)
    public Mono<ResponseEntity<ApiResponse<Void>>> handleStatus(ResponseStatusException e) {
        HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
        if (status == null) status = HttpStatus.INTERNAL_SERVER_ERROR;
        String reason = status.is4xxClientError() && e.getReason() != null
            ? e.getReason()
            : status.getReasonPhrase();
        return Mono.just(ResponseEntity.status(status).body(ApiResponse.error(reason)));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Mono<ResponseEntity<ApiResponse<Void>>> handleIllegalArgument(IllegalArgumentException e) {
        String msg = e.getMessage() != null ? e.getMessage() : "bad request";
        HttpStatus status = msg.toLowerCase().contains("not found")
            ? HttpStatus.NOT_FOUND
            : HttpStatus.BAD_REQUEST;
        // infra P2-3: the raw message may carry internal absolute paths or source
        // URLs (SkillInstallService, file tools); scrub them before the 400 leaves
        // the process. The full detail stays in the server log.
        log.debug("IllegalArgumentException surfaced to client (sanitized): {}", msg);
        return Mono.just(ResponseEntity.status(status).body(ApiResponse.error(sanitize(msg))));
    }

    /** Windows drive paths ({@code C:\dev\secret\app}) -> {@code C:\...} */
    private static final java.util.regex.Pattern WINDOWS_PATH =
        java.util.regex.Pattern.compile("[A-Za-z]:\\\\[^\\s()\\[\\]{}'\",;]+");
    /** Unix absolute paths ({@code /home/user/app/target/x.jar}) -> {@code /...} */
    private static final java.util.regex.Pattern UNIX_PATH =
        java.util.regex.Pattern.compile("(?<![A-Za-z0-9:])/[^\\s()\\[\\]{}'\",;]*");
    /** URLs ({@code https://internal-host:8443/path?token=...}) -> {@code scheme://...} */
    private static final java.util.regex.Pattern URL =
        java.util.regex.Pattern.compile("[A-Za-z][A-Za-z0-9+.-]*://[^\\s()\\[\\]{}'\",;]+");

    /**
     * Collapse absolute filesystem paths and URLs in an error message so a 400/404
     * response cannot leak the deployment layout or internal endpoints (infra P2-3).
     */
    static String sanitize(String msg) {
        if (msg == null || msg.isBlank()) return "bad request";
        String out = URL.matcher(msg).replaceAll(m -> {
            String s = m.group();
            int i = s.indexOf("://");
            return s.substring(0, i) + "://…";
        });
        out = WINDOWS_PATH.matcher(out).replaceAll(m ->
            java.util.regex.Matcher.quoteReplacement(m.group().substring(0, 2) + "\\…"));
        out = UNIX_PATH.matcher(out).replaceAll("/…");
        return out;
    }

    @ExceptionHandler(NoSuchElementException.class)
    public Mono<ResponseEntity<ApiResponse<Void>>> handleNotFound(NoSuchElementException e) {
        String msg = e.getMessage() != null ? e.getMessage() : "not found";
        return Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(msg)));
    }

    /**
     * Domain failures that carry their own HTTP status (PlanGate 402, Org 4xx,
     * Quota 429). One handler replaces the per-controller catch-and-rethrow;
     * the status and a client-safe message come from the exception.
     */
    @ExceptionHandler(com.gantang.axiflux.spring.service.HttpDomainException.class)
    public Mono<ResponseEntity<ApiResponse<Void>>> handleDomain(
            com.gantang.axiflux.spring.service.HttpDomainException e,
            org.springframework.web.server.ServerWebExchange exchange) {
        HttpStatus status = HttpStatus.resolve(e.status());
        if (status == null) status = HttpStatus.BAD_REQUEST;
        String msg = e.getMessage() != null ? e.getMessage() : status.getReasonPhrase();
        log.debug("domain exception surfaced to client: status={} msg={}", status.value(), msg);
        // Funnel: a 402 (plan gate) or 429 (quota) is a monetizable hit point.
        if ((status.value() == 402 || status.value() == 429) && funnel != null) {
            String user = exchange.getRequest().getHeaders()
                .getFirst(com.gantang.axiflux.spring.auth.AuthWebFilter.H_USER);
            String reason = status.value() == 429 ? "quota" : "gate";
            funnel.quotaHit(user, reason);
        }
        return Mono.just(ResponseEntity.status(status).body(ApiResponse.error(msg)));
    }

    /**
     * A core-layer {@code advanced_models} gate rejection: the caller forced a
     * premium model on a plan that does not cover it. Map to 402 and record the
     * monetizable hit, mirroring {@link #handleDomain}.
     */
    @ExceptionHandler(com.gantang.reaxon.impl.llm.router.AdvancedModelGateException.class)
    public Mono<ResponseEntity<ApiResponse<Void>>> handleAdvancedModel(
            com.gantang.reaxon.impl.llm.router.AdvancedModelGateException e,
            org.springframework.web.server.ServerWebExchange exchange) {
        String msg = e.getMessage() != null ? e.getMessage() : "upgrade required";
        if (funnel != null) {
            String user = exchange.getRequest().getHeaders().getFirst(AuthWebFilter.H_USER);
            funnel.quotaHit(user, "gate");
        }
        return Mono.just(ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
            .body(ApiResponse.error(msg)));
    }

    @ExceptionHandler(Exception.class)
    public Mono<ResponseEntity<ApiResponse<Void>>> handleAll(Exception e) {
        log.error("unhandled controller error: {}", e.toString(), e);
        return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResponse.error("internal server error")));
    }
}
