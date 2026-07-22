/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */

package io.github.dsheirer.web.access;

import org.eclipse.jetty.server.Request;

/**
 * Optional admission policy for an embedded-web request.  Production network exposure is controlled by Jetty's
 * configured socket binding; it is not inferred from private-address or VPN ranges.
 */
@FunctionalInterface
public interface RemoteAddressAdmissionPolicy
{
    boolean isAllowed(Request request);

    /**
     * Production policy.  Any client that can reach the configured bind address is admitted to the route layer;
     * authentication and per-feature policy still apply normally.
     */
    static RemoteAddressAdmissionPolicy allowAll()
    {
        return request -> request != null;
    }
}
