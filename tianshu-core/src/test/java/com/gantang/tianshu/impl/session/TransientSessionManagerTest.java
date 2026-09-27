package com.gantang.tianshu.impl.session;

import com.gantang.tianshu.api.session.Message;
import com.gantang.tianshu.api.session.Session;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P2-4: the transient (sub-agent) session must honour the same contract as
 * {@code AbstractSession}: attachments persisted, markDone = resumable
 * (SUSPENDED), a single MessageStore instance, and an unmodifiable metadata view.
 */
class TransientSessionManagerTest {

    private Session newSession() {
        return new TransientSessionManager()
            .getOrCreate("sub:u:x", "u", "agent", Map.of("k", "v"));
    }

    @Test
    void addUserMessage_preservesAttachments() {
        Session s = newSession();
        s.addUserMessage("look at this", Map.of("image", "https://x/y.png"));
        Message m = s.getHistory(1).get(0);
        assertEquals(Map.of("image", "https://x/y.png"), m.attachments(),
            "attachments must be persisted, not dropped (P2-4)");
    }

    @Test
    void markDone_isResumable_suspended_notClosed() {
        Session s = newSession();
        s.markDone();
        assertEquals(Session.State.SUSPENDED, s.state(),
            "markDone must mean resumable (SUSPENDED), aligning with AbstractSession (P2-4)");
        s.close();
        assertEquals(Session.State.CLOSED, s.state());
    }

    @Test
    void messages_returnsSingleInstance() {
        Session s = newSession();
        assertSame(s.messages(), s.messages(),
            "messages() must return the same store instance, not a new one each call (P2-4)");
    }

    @Test
    void metadata_returnsUnmodifiableView() {
        Session s = newSession();
        assertThrows(UnsupportedOperationException.class,
            () -> s.metadata().put("x", "y"),
            "metadata() must be an unmodifiable view (P2-4)");
        // but updateMetadata still mutates through the sanctioned path
        s.updateMetadata("x", "y");
        assertEquals("y", s.metadata().get("x"));
    }

    @Test
    void messageStore_reflectsAppendsAcrossAccessorCalls() {
        Session s = newSession();
        s.addSystemMessage("sys");
        s.addUserMessage("hi", null);
        List<Message> all = s.messages().getAll();
        assertEquals(2, all.size());
        // A second accessor call sees the same backing data (same instance).
        assertEquals(2, s.messages().size());
    }
}
