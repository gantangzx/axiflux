package com.gantang.axiflux.spring.web;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Audit infra P2-4: the access log line must contain the request path WITHOUT
 * the query string (so {@code ?token=} / {@code api_key=} credentials can never
 * leak into logs), and it does carry the remote IP — a deliberate PII access
 * log whose retention is a deployment concern (see the filter's javadoc).
 */
class RequestLoggingFilterTest {

    private static final WebFilterChain EMPTY_CHAIN = exchange -> Mono.empty();

    @Test
    void accessLogContainsPathButNeverTheQueryString() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/Axiflux/ws?token=super-secret-jwt&api_key=hunter2").build());

        Logger logger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            StepVerifier.create(new RequestLoggingFilter().filter(exchange, EMPTY_CHAIN))
                .verifyComplete();
        } finally {
            logger.detachAppender(appender);
        }

        assertFalse(appender.list.isEmpty(), "expected an access log line");
        String msg = appender.list.get(0).getFormattedMessage();
        assertTrue(msg.contains("/Axiflux/ws"), msg);
        assertFalse(msg.contains("token=super-secret-jwt"),
            "query credentials must never reach the access log: " + msg);
        assertFalse(msg.contains("api_key=hunter2"), msg);
        assertFalse(msg.contains("?"), "no query string at all should be logged: " + msg);
    }

    @Test
    void silentPrefixesProduceNoAccessLogLine() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/actuator/health").build());

        Logger logger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            StepVerifier.create(new RequestLoggingFilter().filter(exchange, EMPTY_CHAIN))
                .verifyComplete();
        } finally {
            logger.detachAppender(appender);
        }
        assertTrue(appender.list.isEmpty(), "actuator must stay out of the business access log");
    }

    @Test
    void serverErrorsAreLoggedAtErrorLevel() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/v1/chat").build());
        exchange.getResponse().setStatusCode(
            org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR);

        Logger logger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            StepVerifier.create(new RequestLoggingFilter().filter(exchange, EMPTY_CHAIN))
                .verifyComplete();
        } finally {
            logger.detachAppender(appender);
        }
        assertEquals(1, appender.list.size());
        assertEquals(Level.ERROR, appender.list.get(0).getLevel());
    }
}
