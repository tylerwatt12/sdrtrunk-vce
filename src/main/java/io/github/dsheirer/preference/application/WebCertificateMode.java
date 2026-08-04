/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.preference.application;

import java.util.Locale;

/**
 * Ownership mode for the embedded web server's managed TLS certificate and private key.
 */
public enum WebCertificateMode
{
    AUTOMATIC,
    CUSTOM;

    static WebCertificateMode fromStoredValue(String value)
    {
        if(value == null || value.isBlank())
        {
            return AUTOMATIC;
        }

        try
        {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        }
        catch(IllegalArgumentException exception)
        {
            return AUTOMATIC;
        }
    }
}
