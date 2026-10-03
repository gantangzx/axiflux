package com.gantang.axiflux.spring.storage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gantang.reaxon.api.session.Message;
import com.gantang.reaxon.api.session.Session;
import com.gantang.reaxon.api.session.SessionManager;
import com.gantang.reaxon.api.tool.ToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redis-backed session manager.
 * Short-term memory stored as Redis List (sliding window).
 * Session metadata stored as Redis Hash.
 */
public class RedisSessionStore implements SessionManager {

    private static final Logger log = LoggerFactory.getLogger(RedisSessionStore.class);

    private static final String KEY_MEMORY = "session:memory:";
    private static final String KEY_STATE  = "session:state:";
    private static final String KEY_ACTIVE = "user:active:";
    private static final Duration SESSION_TTL = Duration.ofHours(24);

    private final StringRedisTemplate redis;
    private final ObjectMapper om;
    private final Map<String, Session> localCache = new ConcurrentHashMap<>();

    public RedisSessionStore(StringRedisTemplate redisTemplate) {
        this.redis = Objects.requireNonNull(redisTemplate);
        this.om = new ObjectMapper();
        om.findAndRegisterModules();
    }

    @Override
    public Session getOrCreate(String sessionId, String userId, String agentId,
                              Map<String, Object> metadata) {
        Session cached = localCache.get(sessionId);
        if (cached != null && cached.state() != Session.State.CLOSED) {
            return cached;
        }
        String stateKey = KEY_STATE + sessionId;
        String existingState = redis.opsForHash().get(stateKey, "state") != null
            ? String.valueOf(redis.opsForHash().get(stateKey, "state"))
            : null;
        if (existingState != null) {
            Session session = rebuildSession(sessionId, userId, agentId);
            localCache.put(sessionId, session);
            return session;
        }
        Session session = new RedisSession(sessionId, userId, agentId, metadata);
        localCache.put(sessionId, session);
        persistState(session);
        redis.opsForSet().add(KEY_ACTIVE + userId, sessionId);
        return session;
    }

    @Override
    public Mono<Void> save(Session session) {
        // Offload the blocking Redis round-trips from the Netty event loop, and
        // make the delete+rewrite of the message list atomic via MULTI/EXEC so a
        // crash or concurrent append can never observe an empty/partial window.
        return Mono.<Void>fromRunnable(() -> {
            persistState(session);
            persistMessages(session);
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()).then();
    }

    @Override
    public Mono<Void> delete(String sessionId) {
        Session removed = localCache.remove(sessionId);
        redis.delete(KEY_STATE + sessionId);
        redis.delete(KEY_MEMORY + sessionId);
        // Unregister from the owner's active set so the set does not grow
        // monotonically with dead session ids (P1-5).
        String owner = removed != null ? removed.userId() : readOwner(sessionId);
        if (owner != null) redis.opsForSet().remove(KEY_ACTIVE + owner, sessionId);
        return Mono.empty();
    }

    @Override
    public Optional<Session> get(String sessionId) {
        Session cached = localCache.get(sessionId);
        if (cached != null) return Optional.of(cached);
        if (!Boolean.TRUE.equals(redis.hasKey(KEY_STATE + sessionId))) return Optional.empty();
        Session restored = rebuildSession(sessionId, null, null);
        localCache.put(sessionId, restored);
        return Optional.of(restored);
    }

    @Override
    public Flux<Session> listByUser(String userId) {
        return listByUser(userId, 0, DEFAULT_PAGE_SIZE);
    }

    @Override
    public Flux<Session> listActiveByUser(String userId) {
        return listByUser(userId).filter(s -> s.state() == Session.State.ACTIVE);
    }

    @Override
    public Flux<Session> listByUser(String userId, int page, int size) {
        Set<String> ids = redis.opsForSet().members(KEY_ACTIVE + userId);
        if (ids == null || ids.isEmpty()) return Flux.empty();
        // Redis sets have no order, so sort the ids to make paging deterministic.
        return Flux.fromIterable(new TreeSet<>(ids))
            .skip((long) Math.max(0, page) * Math.max(1, size))
            .take(Math.max(1, size))
            .map(id -> get(id).orElse(null))
            .filter(Objects::nonNull)
            // Backstop: hide transient sub-agent sessions from user-facing lists
            // (current routing keeps them out of Redis; this covers legacy keys).
            .filter(s -> !com.gantang.reaxon.impl.session.RoutingSessionManager.KIND_SUBAGENT
                .equals(s.metadata().get(com.gantang.reaxon.impl.session.RoutingSessionManager.META_KIND)));
    }

    private void persistState(Session session) {
        String key = KEY_STATE + session.sessionId();
        Map<String, String> map = new HashMap<>();
        map.put("userId",  session.userId());
        map.put("agentId", session.agentId());
        map.put("state",   session.state().name());
        session.metadata().forEach((k, v) -> map.put("meta:" + k, String.valueOf(v)));
        redis.opsForHash().putAll(key, map);
        redis.expire(key, SESSION_TTL);
    }

    private void persistMessages(Session session) {
        String key = KEY_MEMORY + session.sessionId();
        List<Message> msgs = session.messages().getAll();
        List<String> json = msgs.stream().map(this::serializeMessage).toList();
        // Atomic delete+push inside one MULTI/EXEC so readers and concurrent
        // appenders never observe the empty intermediate state (P1-3).
        redis.execute(new org.springframework.data.redis.core.SessionCallback<Object>() {
            @Override @SuppressWarnings({"unchecked", "rawtypes"})
            public Object execute(org.springframework.data.redis.core.RedisOperations ops) {
                ops.multi();
                ops.delete(key);
                if (!json.isEmpty()) {
                    ops.opsForList().rightPushAll(key, json);
                    ops.expire(key, SESSION_TTL);
                }
                return ops.exec();
            }
        });
    }

    /** Best-effort owner lookup for SREM when the session is not in localCache. */
    private String readOwner(String sessionId) {
        Object uid = redis.opsForHash().get(KEY_STATE + sessionId, "userId");
        return uid != null ? String.valueOf(uid) : null;
    }

    private Session rebuildSession(String sessionId, String userId, String agentId) {
        Map<Object, Object> raw = redis.opsForHash().entries(KEY_STATE + sessionId);
        Map<String, Object> meta = new HashMap<>();
        raw.forEach((k, v) -> {
            String key = String.valueOf(k);
            if (key.startsWith("meta:")) meta.put(key.substring(5), v);
        });
        String storedUserId = String.valueOf(raw.getOrDefault("userId", userId));
        String storedAgentId = String.valueOf(raw.getOrDefault("agentId", agentId));
        String stateStr = String.valueOf(raw.getOrDefault("state", "ACTIVE"));
        RedisSession session = new RedisSession(sessionId, storedUserId, storedAgentId, meta,
            Session.State.valueOf(stateStr));
        List<String> storedMessages = redis.opsForList().range(KEY_MEMORY + sessionId, 0, -1);
        if (storedMessages != null) {
            storedMessages.stream()
                .map(this::deserializeMessage)
                .filter(Objects::nonNull)
                .forEach(((RedisSession) session)::hydrate);
        }
        return session;
    }

    private String serializeMessage(Message msg) {
        try {
            return om.writeValueAsString(msg);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private Message deserializeMessage(String json) {
        try {
            return om.readValue(json, Message.class);
        } catch (Exception e) {
            log.warn("Skipping malformed persisted session message: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    // --- Session implementation with write-through to Redis ---

    private class RedisSession extends com.gantang.reaxon.impl.session.AbstractSession {

        private final RedisMessageStore messageStore;

        RedisSession(String id, String uid, String agid, Map<String, Object> meta) {
            this(id, uid, agid, meta, State.ACTIVE);
        }

        RedisSession(String id, String uid, String agid, Map<String, Object> meta, State st) {
            super(id, uid, agid, Instant.now(), meta != null ? new HashMap<>(meta) : new HashMap<>());
            this.state = st;  // restore persisted lifecycle state
            this.messageStore = new RedisMessageStore(id);
        }

        @Override
        public com.gantang.reaxon.impl.session.InMemoryMessageStore messages() {
            return messageStore;
        }

        /** Load a persisted message into the read cache without re-persisting it. */
        void hydrate(Message m) {
            messageStore.appendSilent(m);
        }

        @Override
        protected void onStateChange(State newState) {
            String key = KEY_STATE + sessionId();
            redis.opsForHash().put(key, "state", newState.name());
            redis.expire(key, SESSION_TTL);
            if (newState == State.CLOSED) {
                localCache.remove(sessionId());
                // A closed session is no longer active: drop it from the owner's
                // active set so the set stays proportional to live sessions (P1-5).
                redis.opsForSet().remove(KEY_ACTIVE + userId(), sessionId());
            }
        }

        @Override
        protected void onMetadataChange(String key, Object value) {
            String stateKey = KEY_STATE + sessionId();
            if (value == null) {
                redis.opsForHash().delete(stateKey, "meta:" + key);
            } else {
                redis.opsForHash().put(stateKey, "meta:" + key, String.valueOf(value));
            }
            redis.expire(stateKey, SESSION_TTL);
        }
    }

    /**
     * In-memory mirror of persisted messages. Writes go through to Redis on every
     * append, so a crash/restart or a second node rebuilding the session sees the
     * full transcript — previously messages lived only in the process-local cache.
     */
    private class RedisMessageStore extends com.gantang.reaxon.impl.session.InMemoryMessageStore {
        private final String sid;

        RedisMessageStore(String sessionId) {
            this.sid = sessionId;
        }

        @Override
        public void append(Message message) {
            String key = KEY_MEMORY + sid;
            redis.opsForList().rightPush(key, serializeMessage(message));
            // Sliding TTL on both keys keeps active sessions alive.
            redis.expire(key, SESSION_TTL);
            redis.expire(KEY_STATE + sid, SESSION_TTL);
            super.append(message);
        }

        @Override
        public void clear() {
            super.clear();
            redis.delete(KEY_MEMORY + sid);
        }
    }
}
