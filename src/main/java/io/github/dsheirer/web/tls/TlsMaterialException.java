/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.web.tls;

/**
 * Indicates that local TLS material could not be parsed, validated, generated, or installed.
 */
public class TlsMaterialException extends Exception
{
    public TlsMaterialException(String message)
    {
        super(message);
    }

    public TlsMaterialException(String message, Throwable cause)
    {
        super(message, cause);
    }
}
