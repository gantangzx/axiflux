package com.gantang.axiflux.spring.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gantang.reaxon.api.session.Session;
import com.gantang.reaxon.api.session.SessionManager;
import com.gantang.axiflux.storage.entity.SessionEntity;
import com.gantang.axiflux.storage.repository.MessageRepository;
import com.gantang.axiflux.storage.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link JpaSessionStore}.
 *
 * <p>The transaction manager is a mock but the {@link TransactionTemplate} is
 * real, so callbacks genuinely run and {@code getTransaction}/{@code commit}
 * can be ordered against the repository calls. That is what makes the
 * "work happens inside a transaction" assertions meaningful rather than a
 * check that some annotation is present.
 *
 * <p>Not covered here: the {@code @Version} optimistic-locking column and the
 * V7 migration, which need a real database. No embedded DB or Docker is
 * available in this build, so those remain unverified.
 */
class JpaSessionStoreTest {

    private SessionRepository sessionRepo;
    private MessageRepository messageRepo;
    private PlatformTransactionManager txManager;
    private JpaSessionStore store;

    @BeforeEach
    void setUp() {
        sessionRepo = mock(SessionRepository.class);
        messageRepo = mock(MessageRepository.class);
        txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(messageRepo.findBySessionIdOrderByCreatedAtAscIdAsc(anyString())).thenReturn(List.of());
        store = new JpaSessionStore(sessionRepo, messageRepo, new ObjectMapper(),
            new TransactionTemplate(txManager));
    }

    private static SessionEntity entity(String id, String user, String state) {
        SessionEntity e = new SessionEntity();
        e.setId(id);
        e.setUserId(user);
        e.setAgentId("agent-1");
        e.setState(state);
        e.setMetadata(new java.util.HashMap<>());
        e.setLastActiveAt(Instant.now());
        return e;
    }

    private static Session stubSession(String id, String user) {
        Session s = mock(Session.class);
        when(s.sessionId()).thenReturn(id);
        when(s.userId()).thenReturn(user);
        when(s.agentId()).thenReturn("agent-1");
        when(s.state()).thenReturn(Session.State.ACTIVE);
        when(s.metadata()).thenReturn(Map.of("k", "v"));
        return s;
    }

    // =============== transactional integrity ===============

    @Test
    void saveRunsItsReadModifyWriteInsideOneTransaction() {
        when(sessionRepo.findById("s1")).thenReturn(Optional.empty());

        StepVerifier.create(store.save(stubSession("s1", "u1"))).verifyComplete();

        InOrder order = inOrder(txManager, sessionRepo);
        order.verify(txManager).getTransaction(any());
        order.verify(sessionRepo).findById("s1");
        order.verify(sessionRepo).save(any(SessionEntity.class));
        order.verify(txManager).commit(any(TransactionStatus.class));
    }

    @Test
    void saveDoesNothingUntilSubscribed() {
        // The old code annotated save() with @Transactional while returning
        // Mono.fromRunnable. The runnable body executes at subscribe time, long
        // after the annotation's transaction has committed and closed, so the
        // writes landed outside it. Laziness is the reason that was broken, and
        // the reason the transaction now has to be opened inside the runnable.
        Mono<Void> pending = store.save(stubSession("s1", "u1"));

        verifyNoInteractions(sessionRepo, txManager);

        StepVerifier.create(pending).verifyComplete();
        verify(sessionRepo).save(any(SessionEntity.class));
    }

    @Test
    void deleteDoesNothingUntilSubscribed() {
        Mono<Void> pending = store.delete("s1");

        verifyNoInteractions(sessionRepo);

        StepVerifier.create(pending).verifyComplete();
        verify(sessionRepo).deleteById("s1");
    }

    @Test
    void deleteRemovesMessagesBeforeSessionInsideOneTransaction() {
        // Messages carry a FK to the session row, so the child rows have to go
        // first or the delete fails on the constraint.
        StepVerifier.create(store.delete("s1")).verifyComplete();

        InOrder order = inOrder(txManager, messageRepo, sessionRepo);
        order.verify(txManager).getTransaction(any());
        order.verify(messageRepo).deleteBySessionId("s1");
        order.verify(sessionRepo).deleteById("s1");
        order.verify(txManager).commit(any(TransactionStatus.class));
    }

    @Test
    void getOrCreateRunsItsFindOrInsertInsideOneTransaction() {
        when(sessionRepo.findById("s1")).thenReturn(Optional.empty());

        Session created = store.getOrCreate("s1", "u1", "agent-1", Map.of());

        assertEquals("s1", created.sessionId());
        InOrder order = inOrder(txManager, sessionRepo);
        order.verify(txManager).getTransaction(any());
        order.verify(sessionRepo).findById("s1");
        order.verify(sessionRepo).saveAndFlush(any(SessionEntity.class));
        order.verify(txManager).commit(any(TransactionStatus.class));
    }

    // =============== cache coherence ===============

    @Test
    void getOrCreateCacheHitSkipsTheDatabaseEntirely() {
        when(sessionRepo.findById("s1")).thenReturn(Optional.of(entity("s1", "u1", "ACTIVE")));

        Session first = store.getOrCreate("s1", "u1", "agent-1", Map.of());
        Session second = store.getOrCreate("s1", "u1", "agent-1", Map.of());

        assertSame(first, second, "second call should return the cached instance");
        verify(sessionRepo, times(1)).findById("s1");
    }

    @Test
    void getRefusesAClosedCachedSessionAndEvictsIt() {
        // A CLOSED session in the cache is a tombstone: handing it back would let
        // a caller keep writing to a session the DB considers finished. It has to
        // be evicted so the next read goes to the DB and sees the real state.
        when(sessionRepo.findById("s1")).thenReturn(Optional.of(entity("s1", "u1", "CLOSED")));

        store.getOrCreate("s1", "u1", "agent-1", Map.of());  // caches the CLOSED session
        Optional<Session> reread = store.get("s1");

        assertTrue(reread.isPresent());
        verify(sessionRepo, times(2)).findById("s1");
    }

    @Test
    void getReturnsTheCachedInstanceForAnOpenSession() {
        when(sessionRepo.findById("s1")).thenReturn(Optional.of(entity("s1", "u1", "ACTIVE")));

        Session cached = store.getOrCreate("s1", "u1", "agent-1", Map.of());
        Optional<Session> got = store.get("s1");

        assertTrue(got.isPresent());
        assertSame(cached, got.get());
        verify(sessionRepo, times(1)).findById("s1");
    }

    @Test
    void closeEvictsFromTheCacheSoTheNextReadSeesTheClosedState() {
        when(sessionRepo.findById("s1")).thenReturn(Optional.of(entity("s1", "u1", "ACTIVE")));
        Session session = store.getOrCreate("s1", "u1", "agent-1", Map.of());

        session.close();

        // Cache was evicted, so this must hit the DB rather than return the
        // in-memory object we just closed.
        store.get("s1");
        verify(sessionRepo, times(2)).findById("s1");
        verify(sessionRepo).updateState(eq("s1"), eq("CLOSED"), any(Instant.class));
    }

    @Test
    void getReturnsEmptyWhenTheSessionIsAbsentEverywhere() {
        when(sessionRepo.findById("nope")).thenReturn(Optional.empty());

        assertFalse(store.get("nope").isPresent());
    }

    // =============== pagination ===============

    @Test
    void windowedListPushesTheWindowIntoTheQueryAndLeavesTheSortToTheNativeSql() {
        when(sessionRepo.findUserFacingByUserId(eq("u1"), any(Pageable.class)))
            .thenReturn(List.of(entity("s1", "u1", "ACTIVE")));

        StepVerifier.create(store.listByUser("u1", 2, 25)).expectNextCount(1).verifyComplete();

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(sessionRepo).findUserFacingByUserId(eq("u1"), captor.capture());
        Pageable p = captor.getValue();
        assertEquals(2, p.getPageNumber());
        assertEquals(25, p.getPageSize());
        // The deterministic ORDER BY (last_active_at DESC, id ASC) lives inside the
        // native SQL — a Pageable sort would be appended verbatim with entity
        // property names and fail on the real columns. So the Pageable must be unsorted.
        assertTrue(p.getSort().isUnsorted());
    }

    @Test
    void windowedActiveListFiltersOnStateInTheQuery() {
        when(sessionRepo.findUserFacingByUserIdAndState(eq("u1"), eq("ACTIVE"), any(Pageable.class)))
            .thenReturn(List.of(entity("s1", "u1", "ACTIVE")));

        StepVerifier.create(store.listActiveByUser("u1", 0, 10)).expectNextCount(1).verifyComplete();

        verify(sessionRepo).findUserFacingByUserIdAndState(eq("u1"), eq("ACTIVE"), any(Pageable.class));
        verify(sessionRepo, never()).findUserFacingByUserId(anyString(), any(Pageable.class));
    }

    @Test
    void negativePageAndZeroSizeAreClampedToAValidWindow() {
        when(sessionRepo.findUserFacingByUserId(eq("u1"), any(Pageable.class))).thenReturn(List.of());

        StepVerifier.create(store.listByUser("u1", -5, 0)).verifyComplete();

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(sessionRepo).findUserFacingByUserId(eq("u1"), captor.capture());
        assertEquals(0, captor.getValue().getPageNumber(), "negative page must clamp to 0");
        assertEquals(1, captor.getValue().getPageSize(), "size 0 would make PageRequest throw");
    }

    @Test
    void unboundedListAsksForExactlyOneDefaultSizedPage() {
        when(sessionRepo.findUserFacingByUserId(eq("u1"), any(Pageable.class))).thenReturn(List.of());

        StepVerifier.create(store.listByUser("u1")).verifyComplete();

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(sessionRepo).findUserFacingByUserId(eq("u1"), captor.capture());
        assertEquals(0, captor.getValue().getPageNumber());
        assertEquals(SessionManager.DEFAULT_PAGE_SIZE, captor.getValue().getPageSize());
    }

    @Test
    void unboundedListStillEmitsEveryRowOfTheCappedPage() {
        // The cap is a cap, not a silent drop: all DEFAULT_PAGE_SIZE rows reach
        // the caller (and a warning is logged that more may exist).
        List<SessionEntity> full = new ArrayList<>();
        for (int i = 0; i < SessionManager.DEFAULT_PAGE_SIZE; i++) {
            full.add(entity("s" + i, "u1", "ACTIVE"));
        }
        when(sessionRepo.findUserFacingByUserId(eq("u1"), any(Pageable.class))).thenReturn(full);

        StepVerifier.create(store.listByUser("u1"))
            .expectNextCount(SessionManager.DEFAULT_PAGE_SIZE)
            .verifyComplete();
    }

    @Test
    void unboundedActiveListAsksForExactlyOneDefaultSizedPage() {
        when(sessionRepo.findUserFacingByUserIdAndState(eq("u1"), eq("ACTIVE"), any(Pageable.class)))
            .thenReturn(List.of());

        StepVerifier.create(store.listActiveByUser("u1")).verifyComplete();

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(sessionRepo).findUserFacingByUserIdAndState(eq("u1"), eq("ACTIVE"), captor.capture());
        assertEquals(SessionManager.DEFAULT_PAGE_SIZE, captor.getValue().getPageSize());
    }

    @Test
    void listsDoNotTouchTheDatabaseUntilSubscribed() {
        // Flux.defer wrapping is what keeps the blocking repository call off the
        // caller's thread; without it the query would run during assembly.
        store.listByUser("u1", 0, 10);
        store.listActiveByUser("u1", 0, 10);

        verifyNoInteractions(sessionRepo);
    }
}
