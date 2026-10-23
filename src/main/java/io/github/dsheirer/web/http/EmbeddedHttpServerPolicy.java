/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.http;

/** Process-wide safety policy for the JDK embedded HTTP server. */
public final class EmbeddedHttpServerPolicy
{
    static final String MAXIMUM_REQUEST_TIME_PROPERTY = "sun.net.httpserver.maxReqTime";
    static final String MAXIMUM_IDLE_CONNECTIONS_PROPERTY = "sun.net.httpserver.maxIdleConnections";
    private static final String MAXIMUM_REQUEST_TIME_SECONDS = "5";
    // Six browser connections for each of the bounded 256 visitor sessions; request admission remains separate.
    private static final String MAXIMUM_IDLE_CONNECTIONS = "1536";

    private EmbeddedHttpServerPolicy()
    {
    }

    /**
     * Must run before the first JDK {@code HttpServer} or {@code HttpsServer} is created.  The JDK caches these values
     * when its server implementation initializes. Its own timeout task closes the underlying socket, which safely
     * interrupts an incomplete header or request body without consuming another application executor.
     * The idle budget keeps ordinary browser asset connections reusable when many visitors load the site together.
     */
    public static void configureBeforeServerInitialization()
    {
        System.getProperties().putIfAbsent(MAXIMUM_REQUEST_TIME_PROPERTY, MAXIMUM_REQUEST_TIME_SECONDS);
        System.getProperties().putIfAbsent(MAXIMUM_IDLE_CONNECTIONS_PROPERTY, MAXIMUM_IDLE_CONNECTIONS);
    }
}
