/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */

package io.github.dsheirer.web.access;

import org.eclipse.jetty.server.Request;

/**
 * Resolves an application-owned authenticated subject for an HTTP, SSE, or media request.  Session cookies and login
 * mechanics remain outside feature handlers, allowing every transport to consume the same small authorization model.
 */
@FunctionalInterface
public interface WebRequestSubjectResolver
{
    AuthorizationSubject resolve(Request request);

    static WebRequestSubjectResolver anonymous()
    {
        return request -> AuthorizationSubject.ANONYMOUS;
    }
}
