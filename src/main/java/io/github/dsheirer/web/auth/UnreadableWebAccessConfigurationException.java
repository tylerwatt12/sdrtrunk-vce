/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.auth;

import java.io.IOException;

/**
 * Indicates that the current web-access setting exists but cannot be decoded or validated safely.
 */
public final class UnreadableWebAccessConfigurationException extends IOException
{
    UnreadableWebAccessConfigurationException(Throwable cause)
    {
        super("The stored web access configuration is unreadable", cause);
    }
}
