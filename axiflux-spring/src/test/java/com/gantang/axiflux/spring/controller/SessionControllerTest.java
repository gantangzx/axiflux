package com.gantang.axiflux.spring.controller;

import com.gantang.reaxon.api.session.Message;
import com.gantang.reaxon.api.session.Session;
import com.gantang.reaxon.api.session.SessionManager;
import com.gantang.axiflux.spring.auth.CallerGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SessionController owns the IDOR-sensitive surface: every per-session access
 * goes through ownedSession(), which must answer 404 for both "absent" and
 * "not yours" so a caller can never enumerate another user's sessions. These
 * tests pin that invariant plus the metadata/rename and message paging edges.
 */
class SessionControllerTest {

    private SessionManager sessions;
    private CallerGuard guard;
    private SessionController controller;

    @BeforeEach
    void setUp() {
        sessions = mock(SessionManager.class);
        guard = mock(CallerGuard.class);
        controller = new SessionController(sessions, guard);
    }

    private Session session(String id, String userId) {
        Session s = mock(Session.class);
        var store = mock(com.gantang.reaxon.api.session.Session.MessageStore.class);
        when(store.getAll()).thenReturn(java.util.List.of());
        when(store.size()).thenReturn(0);
        when(s.sessionId()).thenReturn(id);
        when(s.userId()).thenReturn(userId);
        when(s.agentId()).thenReturn("default");
        when(s.state()).thenReturn(Session.State.ACTIVE);
        when(s.metadata()).thenReturn(Map.of());
        when(s.messages()).thenReturn(store);
        return s;
    }

    private void own(String id, boolean owned) {
        when(guard.ownsSession(eq(id), any(), any())).thenReturn(owned);
    }

    // ===== ownership invariant (404 for both absent and foreign) =====

    @Test
    void getForeignSessionIs404() {
        own("s1", false);
        StepVerifier.create(controller.get("s1", "alice", ""))
            .expectErrorSatisfies(e -> {
                assertInstanceOf(ResponseStatusException.class, e);
                assertEquals(404, ((ResponseStatusException) e).getStatusCode().value());
            })
            .verify();
        // never even reaches the store when the guard denies
        verify(sessions, never()).get(anyString());
    }

    @Test
    void getOwnedSessionReturnsDetail() {
        own("s1", true);
        Session s = session("s1", "alice");
        when(sessions.get("s1")).thenReturn(Optional.of(s));
        StepVerifier.create(controller.get("s1", "alice", ""))
            .assertNext(resp -> {
                assertTrue(resp.success());
                assertEquals("s1", resp.data().get("sessionId"));
            })
            .verifyComplete();
    }

    @Test
    void deleteForeignIs404AndNeverDeletes() {
        own("s1", false);
        StepVerifier.create(controller.delete("s1", "bob", ""))
            .expectError(ResponseStatusException.class)
            .verify();
        verify(sessions, never()).delete(anyString());
    }

    @Test
    void closeOwnedSessionClosesAndSaves() {
        own("s1", true);
        Session s = session("s1", "alice");
        when(sessions.get("s1")).thenReturn(Optional.of(s));
        when(sessions.save(s)).thenReturn(Mono.empty());
        StepVerifier.create(controller.close("s1", "alice", ""))
            .assertNext(resp -> assertEquals("CLOSED", resp.data().get("state")))
            .verifyComplete();
        verify(s).close();
    }

    // ===== create =====

    @Test
    void createGeneratesIdWhenAbsent() {
        when(guard.context(any(), any(), any(), any()))
            .thenReturn(new CallerGuard.Caller("alice", null, ""));
        Session made = session("generated", "alice");
        when(sessions.getOrCreate(anyString(), eq("alice"), anyString(), anyMap()))
            .thenReturn(made);
        StepVerifier.create(controller.create(
                new SessionController.CreateRequest(null, "alice", null, null), "alice", ""))
            .assertNext(resp -> assertEquals(true, resp.data().get("created")))
            .verifyComplete();
    }

    // ===== rename =====

    @Test
    void renameForeignIs404() {
        own("s1", false);
        StepVerifier.create(controller.rename("s1",
                new SessionController.RenameRequest("x"), "bob", ""))
            .expectError(ResponseStatusException.class)
            .verify();
    }

    // ===== messages paging =====

    @Test
    void messagesPageSlicesFromTheEnd() {
        own("s1", true);
        Session s = session("s1", "alice");
        var store = s.messages();
        java.util.List<Message> all = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            final int n = i;
            Message m = mock(Message.class);
            when(m.id()).thenReturn("m" + n);
            when(m.role()).thenReturn(com.gantang.reaxon.api.session.Message.Role.USER);
            when(m.content()).thenReturn("c" + n);
            when(m.timestamp()).thenReturn(java.time.Instant.EPOCH.plusSeconds(n));
            all.add(m);
        }
        when(store.getAll()).thenReturn(all);
        when(sessions.get("s1")).thenReturn(Optional.of(s));

        StepVerifier.create(controller.messages("s1", 10, 0, "alice", ""))
            .assertNext(resp -> {
                @SuppressWarnings("unchecked")
                var items = (List<Map<String, Object>>) ((Map<String, Object>) resp.data()).get("messages");
                assertEquals(10, items.size());
                assertEquals("c20", items.get(0).get("content"), "newest page starts at c20");
                assertEquals(30, ((Map<String, Object>) resp.data()).get("total"));
                assertEquals(true, ((Map<String, Object>) resp.data()).get("hasEarlier"));
            })
            .verifyComplete();
    }
}
