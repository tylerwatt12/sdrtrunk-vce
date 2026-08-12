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
    private static final String MAXIMUM_REQUEST_TIME_SECONDS = "5";

    private EmbeddedHttpServerPolicy()
    {
    }

    /**
     * Must run before the first JDK {@code HttpServer} or {@code HttpsServer} is created.  The JDK caches this value
     * when its server implementation initializes.  Its own timeout task closes the underlying socket, which safely
     * interrupts an incomplete header or request body without consuming another application executor.
     */
    public static void configureBeforeServerInitialization()
    {
        System.getProperties().putIfAbsent(MAXIMUM_REQUEST_TIME_PROPERTY, MAXIMUM_REQUEST_TIME_SECONDS);
    }
}
