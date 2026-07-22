/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */

package io.github.dsheirer.web.signal;

import io.github.dsheirer.web.access.AuthorizationSubject;
import java.util.Objects;
import java.util.function.BooleanSupplier;
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
     * Resolves both the handshake subject and a cheap check that remains valid for the lifetime of the socket.
     * Stateless test/development resolvers inherit a permanent check.  Session-backed resolvers override this so a
     * logout, credential reset, or session expiration revokes an already-open signal socket.
     */
    default SignalAuthorization resolveAuthorization(ServerUpgradeRequest request)
    {
        return SignalAuthorization.permanent(resolve(request));
    }

    /**
     * Resolver for deployments that have not enabled administrator authentication yet.
     */
    static SignalSubjectResolver anonymous()
    {
        return request -> AuthorizationSubject.ANONYMOUS;
    }

    record SignalAuthorization(AuthorizationSubject subject, BooleanSupplier sessionIsValid)
    {
        public SignalAuthorization
        {
            Objects.requireNonNull(subject, "Signal authorization subject cannot be null");
            Objects.requireNonNull(sessionIsValid, "Signal session validity check cannot be null");
        }

        public boolean isSessionValid()
        {
            try
            {
                return sessionIsValid.getAsBoolean();
            }
            catch(RuntimeException exception)
            {
                return false;
            }
        }

        public static SignalAuthorization permanent(AuthorizationSubject subject)
        {
            return new SignalAuthorization(subject, () -> true);
        }
    }
}
