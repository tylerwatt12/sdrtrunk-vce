/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.web.auth;

import java.io.IOException;

/**
 * Indicates that the current-value web administrator setting exists but cannot be decoded or validated.
 */
final class UnreadableWebAdminCredentialException extends IOException
{
    UnreadableWebAdminCredentialException(Throwable cause)
    {
        super("The stored web administrator credential is unreadable", cause);
    }
}
