package com.gantang.reaxon.api.session;

import java.util.function.Consumer;

/**
 * A {@link SessionManager} that broadcasts session-deletion events to
 * registered listeners. Consumers (Spring wiring, cache invalidators, agent
 * forget hooks) depend on this contract instead of the concrete impl so the
 * capability probe stays explicit and fail-loud.
 *
 * <p>P1-4: {@code RoutingSessionManager} implements this; session managers
 * that don't notify on delete simply do not.
 */
public interface SessionDeleteBroadcaster extends SessionManager {

    /**
     * Register a listener invoked after a session has been deleted. The
     * session id is the only argument; listeners must be side-effect free
     * with respect to the broadcaster itself (a thrown exception must not
     * stop other listeners or the deletion itself).
     */
    void addDeleteListener(Consumer<String> listener);
}