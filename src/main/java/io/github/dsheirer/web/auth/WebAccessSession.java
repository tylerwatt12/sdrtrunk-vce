/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.auth;

import java.util.Objects;

/**
 * Immutable snapshot of one in-memory browser session.  Secret values are redacted from {@link #toString()}.
 */
public record WebAccessSession(String sessionId, String csrfToken, WebAccessAccount account)
{
    public WebAccessSession
    {
        Objects.requireNonNull(sessionId, "Session identifier cannot be null");
        Objects.requireNonNull(csrfToken, "CSRF token cannot be null");
        Objects.requireNonNull(account, "Session account cannot be null");

        if(sessionId.isBlank() || csrfToken.isBlank())
        {
            throw new IllegalArgumentException("Invalid web access session snapshot");
        }
    }

    @Override
    public String toString()
    {
        return "WebAccessSession[sessionId=<redacted>, csrfToken=<redacted>, account=" + account + "]";
    }
}
