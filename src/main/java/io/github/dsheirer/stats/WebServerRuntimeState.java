/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats;

import java.util.Objects;

/**
 * Actual runtime state of the embedded web listener.
 *
 * <p>This state intentionally describes the listener that is currently reachable, which can differ from requested
 * preferences after a failed rebind.  Status text is selected by the server lifecycle and never contains raw
 * exception details.</p>
 *
 * @param running true when a listener is active
 * @param port active listener port, or zero when stopped
 * @param anyIpEnabled true when the active listener accepts non-loopback connections
 * @param https true when the active listener uses TLS
 * @param certificateFingerprint SHA-256 leaf-certificate fingerprint for HTTPS, otherwise null
 * @param statusMessage sanitized operator-facing lifecycle status
 */
public record WebServerRuntimeState(boolean running, int port, boolean anyIpEnabled, boolean https,
                                    String certificateFingerprint, String statusMessage)
{
    public WebServerRuntimeState
    {
        if(running && (port < 1 || port > 65_535) || !running && port != 0)
        {
            throw new IllegalArgumentException("Runtime web server port does not match listener state");
        }

        if(!running && (anyIpEnabled || https || certificateFingerprint != null))
        {
            throw new IllegalArgumentException("A stopped web listener cannot expose active transport state");
        }

        if(!https && certificateFingerprint != null || running && https &&
            (certificateFingerprint == null || certificateFingerprint.isBlank()))
        {
            throw new IllegalArgumentException("Runtime web server fingerprint does not match TLS state");
        }

        statusMessage = Objects.requireNonNull(statusMessage, "Runtime web server status cannot be null");

        if(statusMessage.isBlank() || statusMessage.length() > 240 || statusMessage.indexOf('\n') >= 0 ||
            statusMessage.indexOf('\r') >= 0)
        {
            throw new IllegalArgumentException("Runtime web server status must be a short single-line message");
        }
    }
}
