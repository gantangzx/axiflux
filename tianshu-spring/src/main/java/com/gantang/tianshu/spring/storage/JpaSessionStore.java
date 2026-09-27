package com.gantang.tianshu.spring.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gantang.tianshu.api.session.Message;
import com.gantang.tianshu.api.session.Session;
import com.gantang.tianshu.api.session.SessionManager;
import com.gantang.tianshu.api.tool.ToolCall;
import com.gantang.tianshu.api.tool.ToolResult;
import com.gantang.tianshu.storage.entity.MessageEntity;
import com.gantang.tianshu.storage.entity.SessionEntity;
import com.gantang.tianshu.storage.repository.MessageRepository;
import com.gantang.tianshu.storage.repository.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JPA-backed {@link SessionManager}.
 * <p>
 * Sessions and messages are persisted through {@link SessionRepository} and
 * {@link MessageRepository}. In-flight sessions are cached in memory; every
 * mutation is written through to the database.
 * <p>
 * This is the production storage backend for durable sessions. If Redis is
 * available, prefer {@link RedisSessionStore} for hot short-term memory and
 * use this only for long-lived audit history.
 */
public class JpaSessionStore implements SessionManager {

    private static final Logger log = LoggerFactory.getLogger(JpaSessionStore.class);

    private final SessionRepository sessionRepo;
    private final MessageRepository messageRepo;
    private final ObjectMapper om;
    private final Map<String, JpaSession> localCache = new ConcurrentHashMap<>();
    private final TransactionTemplate tx;

    public JpaSessionStore(SessionRepository sessionRepo,
                           MessageRepository messageRepo,
                           ObjectMapper objectMapper,
                           TransactionTemplate transactionTemplate) {
        this.sessionRepo = Objects.requireNonNull(sessionRepo);
        this.messageRepo = Objects.requireNonNull(messageRepo);
        this.om = Objects.requireNonNull(objectMapper);
        this.tx = Objects.requireNonNull(transactionTemplate);
    }

    // =============== SessionManager API ===============

    @Override
    public Session getOrCreate(String sessionId, String userId, String agentId,
                               Map<String, Object> metadata) {
        JpaSession cached = localCache.get(sessionId);
        if (cached != null && cached.state() != Session.State.CLOSED) {
            log.debug("session cache hit id={} user={}", sessionId, userId);
            return cached;
        }
        // Explicit transaction: this bean is constructed with `new` by the
        // auto-configuration, so it is not a Spring proxy and @Transactional on
        // this method would silently do nothing. The find-or-insert below must be
        // atomic, so it goes through the template.
        try {
            return tx.execute(status -> loadOrInsert(sessionId, userId, agentId, metadata));
        } catch (org.springframework.dao.DataIntegrityViolationException race) {
            // Two callers raced getOrCreate on the same new sessionId and this
            // thread lost the insert. The failed transaction has rolled back;
            // retry once — findById now sees the winner's committed row (P1-7).
            log.debug("getOrCreate lost insert race for id={}, reloading winner's row", sessionId);
            return tx.execute(status -> loadOrInsert(sessionId, userId, agentId, metadata));
        }
    }

    private Session loadOrInsert(String sessionId, String userId, String agentId,
                                 Map<String, Object> metadata) {
        Optional<SessionEntity> existing = sessionRepo.findById(sessionId);
        if (existing.isPresent()) {
            SessionEntity ent = existing.get();
            long messageCount = messageRepo.countBySessionId(sessionId);
            log.info("session loaded from DB id={} user={} state={} messages={}",
                sessionId, ent.getUserId(), ent.getState(), messageCount);
            JpaSession session = new JpaSession(
                ent.getId(), ent.getUserId(), ent.getAgentId(),
                Session.State.valueOf(ent.getState()),
                ent.getMetadata() != null ? new HashMap<>(ent.getMetadata()) : new HashMap<>()
            );
            hydrateMessages(session);
            localCache.put(sessionId, session);
            return session;
        }

        log.info("session created id={} user={} agent={}", sessionId, userId, agentId);
        SessionEntity ent = new SessionEntity();
        ent.setId(sessionId);
        ent.setUserId(userId);
        ent.setAgentId(agentId);
        ent.setState(Session.State.ACTIVE.name());
        ent.setMetadata(metadata != null ? new HashMap<>(metadata) : new HashMap<>());
        // Flush inside the transaction so a primary-key race surfaces here as a
        // DataIntegrityViolationException and can be retried by getOrCreate.
        sessionRepo.saveAndFlush(ent);

        JpaSession session = new JpaSession(
            sessionId, userId, agentId, Session.State.ACTIVE,
            metadata != null ? new HashMap<>(metadata) : new HashMap<>()
        );
        localCache.put(sessionId, session);
        return session;
    }

    @Override
    public Mono<Void> save(Session session) {
        return Mono.<Void>fromRunnable(() -> tx.executeWithoutResult(status -> {
            SessionEntity ent = sessionRepo.findById(session.sessionId())
                .orElseGet(() -> {
                    SessionEntity e = new SessionEntity();
                    e.setId(session.sessionId());
                    e.setUserId(session.userId());
                    e.setAgentId(session.agentId());
                    return e;
                });
            ent.setState(session.state().name());
            ent.setMetadata(new HashMap<>(session.metadata()));
            ent.setLastActiveAt(Instant.now());
            sessionRepo.save(ent);
        })).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Void> delete(String sessionId) {
        return Mono.<Void>fromRunnable(() -> {
            long msgs = messageRepo.countBySessionId(sessionId);
            localCache.remove(sessionId);
            tx.executeWithoutResult(status -> {
                messageRepo.deleteBySessionId(sessionId);
                sessionRepo.deleteById(sessionId);
            });
            log.info("session deleted id={} messagesRemoved={}", sessionId, msgs);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Optional<Session> get(String sessionId) {
        JpaSession cached = localCache.get(sessionId);
        if (cached != null && cached.state() != Session.State.CLOSED) return Optional.of(cached);
        // Evict any stale CLOSED entry so the next call reads from the DB.
        if (cached != null) localCache.remove(sessionId);
        return sessionRepo.findById(sessionId).map(ent -> {
            JpaSession s = new JpaSession(
                ent.getId(), ent.getUserId(), ent.getAgentId(),
                Session.State.valueOf(ent.getState()),
                ent.getMetadata() != null ? new HashMap<>(ent.getMetadata()) : new HashMap<>()
            );
            hydrateMessages(s);
            localCache.put(sessionId, s);
            return (Session) s;
        });
    }

    @Override
    public Flux<Session> listByUser(String userId) {
        return warnIfTruncated(listByUser(userId, 0, DEFAULT_PAGE_SIZE), userId);
    }

    @Override
    public Flux<Session> listActiveByUser(String userId) {
        return warnIfTruncated(listActiveByUser(userId, 0, DEFAULT_PAGE_SIZE), userId);
    }

    @Override
    public Flux<Session> listByUser(String userId, int page, int size) {
        // Sub-agent exclusion is pushed into SQL so page sizes stay honest (P1-6).
        return Flux.defer(() -> Flux.fromIterable(
                sessionRepo.findUserFacingByUserId(userId, pageOf(page, size))))
            .map(this::toSession)
            .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Flux<Session> listActiveByUser(String userId, int page, int size) {
        return Flux.defer(() -> Flux.fromIterable(
                sessionRepo.findUserFacingByUserIdAndState(userId, Session.State.ACTIVE.name(), pageOf(page, size))))
            .map(this::toSession)
            .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Page request without a Sort: the native queries in {@link SessionRepository}
     * embed their own deterministic ORDER BY (last_active_at DESC, id ASC). Native
     * SQL cannot translate entity property names, so a Sort on the Pageable would
     * be appended verbatim (s.lastActiveAt) and fail against the real column names.
     */
    private static PageRequest pageOf(int page, int size) {
        return PageRequest.of(Math.max(0, page), Math.max(1, size));
    }

    /**
     * The unbounded list forms cap at {@link #DEFAULT_PAGE_SIZE}. A full page
     * means there may be more that the caller will never see, so say so rather
     * than truncating silently.
     */
    private Flux<Session> warnIfTruncated(Flux<Session> page, String userId) {
        return page.collectList().flatMapMany(list -> {
            if (list.size() >= DEFAULT_PAGE_SIZE) {
                log.warn("session list for user={} truncated at {} rows; "
                        + "use the paged listByUser(userId, page, size) to see the rest",
                    userId, DEFAULT_PAGE_SIZE);
            }
            return Flux.fromIterable(list);
        });
    }

    // =============== helpers ===============

    private Session toSession(SessionEntity ent) {
        JpaSession cached = localCache.get(ent.getId());
        if (cached != null) return cached;
        JpaSession s = new JpaSession(
            ent.getId(), ent.getUserId(), ent.getAgentId(),
            Session.State.valueOf(ent.getState()),
            ent.getMetadata() != null ? new HashMap<>(ent.getMetadata()) : new HashMap<>()
        );
        hydrateMessages(s);
        localCache.put(ent.getId(), s);
        return s;
    }

    private void hydrateMessages(JpaSession s) {
        List<MessageEntity> stored = messageRepo.findBySessionIdOrderByCreatedAtAscIdAsc(s.sessionId());
        for (MessageEntity me : stored) {
            s.hydrate(toDomain(me));
        }
    }

    private Message toDomain(MessageEntity e) {
        List<ToolCall> tc = List.of();
        if (e.getToolCalls() != null && !e.getToolCalls().isEmpty()) {
            tc = e.getToolCalls().stream()
                .map(this::mapToToolCall)
                .filter(Objects::nonNull)
                .toList();
        }
        Map<String, Object> att = e.getAttachments() != null ? e.getAttachments() : Map.of();
        Message.Role role = Message.Role.valueOf(e.getRole());
        return new Message(e.getId(), role, e.getContent(), tc,
                           e.getToolCallId(), att, e.getCreatedAt(), e.getReasoning());
    }

    @SuppressWarnings("unchecked")
    private ToolCall mapToToolCall(Map<String, Object> m) {
        try {
            String callId = (String) m.get("callId");
            String name = (String) m.getOrDefault("toolName", m.get("name"));
            Object argsRaw = m.get("arguments");
            Map<String, Object> args = argsRaw instanceof Map
                ? (Map<String, Object>) argsRaw
                : om.convertValue(argsRaw, new TypeReference<Map<String, Object>>() {});
            return new ToolCall(callId, name, args != null ? args : Map.of());
        } catch (Exception ex) {
            log.warn("Failed to decode tool call from persisted message: {}", ex.getMessage());
            return null;
        }
    }

    private MessageEntity toEntity(String sessionId, Message m) {
        MessageEntity e = new MessageEntity();
        e.setId(m.id());
        e.setSessionId(sessionId);
        e.setRole(m.role().name());
        e.setContent(sanitizeForDb(m.content()));
        e.setToolCallId(m.toolCallId());
        if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
            List<Map<String, Object>> jsonCalls = m.toolCalls().stream()
                .map(tc -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("callId", tc.callId());
                    map.put("toolName", tc.toolName());
                    map.put("arguments", tc.arguments());
                    return map;
                }).toList();
            e.setToolCalls(jsonCalls);
            e.setToolName(m.toolCalls().stream()
                .map(ToolCall::toolName)
                .findFirst()
                .orElse(null));
        }
        if (m.attachments() != null && !m.attachments().isEmpty()) {
            e.setAttachments(new HashMap<>(m.attachments()));
        }
        if (m.reasoning() != null && !m.reasoning().isBlank()) {
            e.setReasoning(sanitizeForDb(m.reasoning()));
        }
        return e;
    }

    /**
     * Strip characters that are illegal in PostgreSQL {@code text} columns.
     * Most notably NUL ({@code \u0000}) which otherwise aborts the whole INSERT
     * and silently loses the message (e.g. GBK-misdecoded Windows tool output).
     */
    private static String sanitizeForDb(String s) {
        if (s == null || s.indexOf('\u0000') < 0) return s;
        return s.replace("\u0000", "");
    }

    // =============== inner types ===============
    private class JpaSession extends com.gantang.tianshu.impl.session.AbstractSession {

        private final JpaMessageStore messageStore = new JpaMessageStore();

        JpaSession(String id, String uid, String agid, State state, Map<String, Object> meta) {
            super(id, uid, agid, Instant.now(), meta != null ? new HashMap<>(meta) : new HashMap<>());
            this.state = state;  // restore persisted lifecycle state
        }

        @Override
        public JpaMessageStore messages() {
            return messageStore;
        }

        /** Load a persisted message into the read cache without re-persisting it. */
        void hydrate(Message m) {
            messageStore.appendSilent(m);
        }

        @Override
        public List<Message> getHistorySince(Instant since) {
            return messageRepo.findSince(sessionId, since).stream()
                .map(JpaSessionStore.this::toDomain)
                .toList();
        }

        @Override
        protected void onStateChange(State newState) {
            tx.executeWithoutResult(status ->
                sessionRepo.updateState(sessionId, newState.name(), Instant.now()));
            if (newState == State.CLOSED) {
                localCache.remove(sessionId);
            }
        }

        @Override
        protected void onMetadataChange(String key, Object value) {
            tx.executeWithoutResult(status -> {
                sessionRepo.findById(sessionId).ifPresent(ent -> {
                    Map<String, Object> md = ent.getMetadata() != null
                        ? new HashMap<>(ent.getMetadata()) : new HashMap<>();
                    md.put(key, value);
                    ent.setMetadata(md);
                    sessionRepo.save(ent);
                });
            });
        }

        /** In-memory mirror of persisted messages. Writes go through to JPA. */
        private class JpaMessageStore extends com.gantang.tianshu.impl.session.InMemoryMessageStore {

            @Override
            public void append(Message message) {
                MessageEntity ent = toEntity(sessionId, message);
                tx.executeWithoutResult(status -> {
                    messageRepo.save(ent);
                    sessionRepo.touch(sessionId, Instant.now());
                });
                super.append(message);
            }

            @Override
            public void clear() {
                super.clear();
                tx.executeWithoutResult(status -> messageRepo.deleteBySessionId(sessionId));
            }
        }
    }
}
