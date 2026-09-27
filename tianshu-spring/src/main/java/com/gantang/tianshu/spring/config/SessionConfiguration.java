package com.gantang.tianshu.spring.config;

import com.gantang.tianshu.api.session.SessionManager;
import com.gantang.tianshu.impl.session.InMemorySessionManager;
import com.gantang.tianshu.impl.session.RoutingSessionManager;
import com.gantang.tianshu.impl.session.SessionTitleHook;
import com.gantang.tianshu.spring.config.props.SessionProperties;
import com.gantang.tianshu.spring.storage.RedisSessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Locale;

/**
 * Session manager selection: JPA (PostgreSQL) when the repositories are on the
 * classpath and wired, Redis when a {@link StringRedisTemplate} exists, and an
 * in-memory fallback otherwise. Every variant routes sub-agent ({@code sub:}
 * prefix) sessions to a transient in-memory store.
 */
@Configuration(proxyBeanMethods = false)
public class SessionConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SessionConfiguration.class);

    @Bean
    @Primary
    @ConditionalOnSessionProvider(value = {"jpa", "auto"}, matchIfMissing = true)
    @ConditionalOnBean({com.gantang.tianshu.storage.repository.SessionRepository.class,
                        com.gantang.tianshu.storage.repository.MessageRepository.class})
    public SessionManager jpaSessionManager(
            com.gantang.tianshu.storage.repository.SessionRepository sessionRepo,
            com.gantang.tianshu.storage.repository.MessageRepository messageRepo,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper,
            PlatformTransactionManager txManager) {
        log.info("SessionManager: JPA-backed (persistent sessions in PostgreSQL)");
        SessionManager store = new com.gantang.tianshu.spring.storage.JpaSessionStore(
            sessionRepo, messageRepo, objectMapper, new TransactionTemplate(txManager));
        // Sub-agent ("sub:" prefix) sessions are transient: routed to an
        // in-memory store and destroyed when the child run finishes.
        return new RoutingSessionManager(store);
    }

    @Bean
    @Primary
    @ConditionalOnSessionProvider({"redis", "auto"})
    @ConditionalOnBean(StringRedisTemplate.class)
    @ConditionalOnMissingBean(SessionManager.class) // auto: JPA wins when both backends exist
    public SessionManager redisSessionManager(StringRedisTemplate redisTemplate) {
        log.info("SessionManager: Redis-backed");
        // Sub-agent ("sub:" prefix) sessions stay in memory and never reach Redis.
        return new RoutingSessionManager(new RedisSessionStore(redisTemplate));
    }

    @Bean
    @Primary
    @ConditionalOnMissingBean(SessionManager.class)
    public SessionManager inMemorySessionManager(ObjectProvider<Environment> envProvider) {
        Environment env = envProvider.getIfAvailable();
        String raw = env != null ? env.getProperty("tianshu.session.provider") : null;
        if (raw != null && !raw.isBlank()) {
            String v = raw.trim().toLowerCase(Locale.ROOT);
            switch (v) {
                case "jpa", "redis" -> log.warn(
                    "tianshu.session.provider={} was requested but its backend beans are unavailable "
                    + "(no DataSource/JPA repositories or no StringRedisTemplate); falling back to "
                    + "in-memory sessions - session state will NOT survive restarts", v);
                case "auto", "inmemory" -> log.info(
                    "SessionManager: in-memory (no persistent backend available)");
                default -> throw new IllegalStateException(
                    "Invalid tianshu.session.provider='" + raw + "'; valid values: auto|jpa|redis|inmemory");
            }
        } else {
            log.info("tianshu.session.provider not set and no JPA/Redis backend available; "
                + "using in-memory sessions (set tianshu.session.provider=jpa|redis for persistence)");
        }
        // Sub-agent ("sub:" prefix) sessions are routed to the transient store;
        // with no persistent backend the delegate is in-memory as well.
        return new RoutingSessionManager(new InMemorySessionManager());
    }

    /**
     * Console-style conversation naming: the first user message becomes the
     * session title, so the session list reads as questions instead of ids.
     * Disable with {@code tianshu.session.auto-title=false}.
     */
    @Bean
    @ConditionalOnProperty(name = "tianshu.session.auto-title", havingValue = "true", matchIfMissing = true)
    public SessionTitleHook sessionTitleHook(SessionProperties sessionProps) {
        log.info("SessionTitleHook: conversations are named after their first user message");
        return new SessionTitleHook(sessionProps.getTitleMaxLength());
    }
}
