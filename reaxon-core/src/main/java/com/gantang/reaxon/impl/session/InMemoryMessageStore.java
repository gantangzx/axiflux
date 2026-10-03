package com.gantang.reaxon.impl.session;

import com.gantang.reaxon.api.session.Message;
import com.gantang.reaxon.api.session.Session;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Thread-safe in-memory {@link Session.MessageStore} backed by a synchronized
 * list. Persistent stores can extend this and override {@link #append(Message)}
 * / {@link #clear()} to mirror writes to the database while keeping the
 * in-memory read cache for free.
 */
public class InMemoryMessageStore implements Session.MessageStore {

    protected final List<Message> messages = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void append(Message message) {
        messages.add(message);
    }

    /** Load a persisted message into the read cache without re-persisting it. */
    public void appendSilent(Message message) {
        messages.add(message);
    }

    @Override
    public List<Message> getRecent(int count) {
        synchronized (messages) {
            int from = Math.max(0, messages.size() - count);
            return new ArrayList<>(messages.subList(from, messages.size()));
        }
    }

    @Override
    public List<Message> getAll() {
        synchronized (messages) {
            return new ArrayList<>(messages);
        }
    }

    @Override
    public void clear() {
        messages.clear();
    }

    @Override
    public int size() {
        return messages.size();
    }
}
