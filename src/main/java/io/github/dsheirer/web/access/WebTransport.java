/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.access;

/**
 * Transport presenting a protected web feature.  Transport is recorded in decisions but does not alter policy.
 */
public enum WebTransport
{
    HTTP,
    SSE,
    MEDIA,
    WEBSOCKET
}
