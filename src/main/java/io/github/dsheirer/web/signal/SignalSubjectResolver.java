/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */

package io.github.dsheirer.web.signal;

import io.github.dsheirer.web.access.AuthorizationSubject;
import org.eclipse.jetty.websocket.server.ServerUpgradeRequest;

/**
 * Resolves the already-validated application identity associated with a WebSocket handshake.
 *
 * <p>The signal transport deliberately does not define a cookie, bearer token, or query-string authentication
 * scheme.  The application login service owns that choice and injects a resolver that maps its server-side session
 * state to this small authorization classification.</p>
 */
@FunctionalInterface
public interface SignalSubjectResolver
{
    AuthorizationSubject resolve(ServerUpgradeRequest request);

    /**
     * Resolver for deployments that have not enabled administrator authentication yet.
     */
    static SignalSubjectResolver anonymous()
    {
        return request -> AuthorizationSubject.ANONYMOUS;
    }
}
