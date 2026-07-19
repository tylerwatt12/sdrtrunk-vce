/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.access;

/**
 * Immutable identity classification used by the feature-access gateway.  It intentionally carries no session or
 * per-viewer state.
 */
public enum AuthorizationSubject
{
    ANONYMOUS,
    AUTHENTICATED_ADMIN;

    public boolean isAuthenticatedAdmin()
    {
        return this == AUTHENTICATED_ADMIN;
    }
}
