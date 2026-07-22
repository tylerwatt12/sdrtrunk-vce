/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */

package io.github.dsheirer.web.access;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import org.eclipse.jetty.server.Request;

/**
 * Resolves an application-owned authenticated subject for an HTTP, SSE, or media request.  Session cookies and login
 * mechanics remain outside feature handlers, allowing every transport to consume the same small authorization model.
 */
@FunctionalInterface
public interface WebRequestSubjectResolver
{
    AuthorizationSubject resolve(Request request);

    /**
     * Resolves both the request subject and a cheap check that remains valid for the lifetime of a long-lived HTTP
     * response.  Stateless resolvers inherit a permanent check.  Session-backed resolvers override this so logout,
     * credential reset, or session expiration revokes an already-open SSE stream.
     */
    default WebAuthorization resolveAuthorization(Request request)
    {
        return WebAuthorization.permanent(resolve(request));
    }

    static WebRequestSubjectResolver anonymous()
    {
        return request -> AuthorizationSubject.ANONYMOUS;
    }

    record WebAuthorization(AuthorizationSubject subject, BooleanSupplier sessionIsValid)
    {
        public WebAuthorization
        {
            Objects.requireNonNull(subject, "Web authorization subject cannot be null");
            Objects.requireNonNull(sessionIsValid, "Web session validity check cannot be null");
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

        public static WebAuthorization permanent(AuthorizationSubject subject)
        {
            return new WebAuthorization(subject, () -> true);
        }
    }
}
