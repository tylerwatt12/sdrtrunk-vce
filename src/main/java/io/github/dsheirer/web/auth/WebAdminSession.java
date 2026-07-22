/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.auth;

import java.util.Objects;

/**
 * Immutable snapshot of one in-memory browser administrator session.
 */
public record WebAdminSession(String sessionId, String csrfToken, long createdAtEpochMillis, long lastSeenAtEpochMillis,
                              long expiresAtEpochMillis, long authGeneration)
{
    public WebAdminSession
    {
        Objects.requireNonNull(sessionId, "Session identifier cannot be null");
        Objects.requireNonNull(csrfToken, "CSRF token cannot be null");

        if(sessionId.isBlank() || csrfToken.isBlank() || createdAtEpochMillis < 0 ||
            lastSeenAtEpochMillis < createdAtEpochMillis || expiresAtEpochMillis < lastSeenAtEpochMillis ||
            authGeneration < 1)
        {
            throw new IllegalArgumentException("Invalid web administrator session snapshot");
        }
    }

    @Override
    public String toString()
    {
        return "WebAdminSession[sessionId=<redacted>, csrfToken=<redacted>, createdAtEpochMillis=" +
            createdAtEpochMillis + ", lastSeenAtEpochMillis=" + lastSeenAtEpochMillis +
            ", expiresAtEpochMillis=" + expiresAtEpochMillis + ", authGeneration=" + authGeneration + "]";
    }
}
