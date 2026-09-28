/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats;

import java.net.URI;

/**
 * Current embedded-web state used by desktop navigation controls.
 *
 * @param running true when the embedded web server is listening
 * @param port configured/listening port
 * @param https true when the embedded server is using TLS
 * @param summaryLoggingActive true when summary statistics are updating
 */
public record StatsWebNavigationState(boolean running, int port, boolean https, boolean summaryLoggingActive)
{
    /**
     * Loopback address used by desktop controls to open the embedded web interface.
     */
    public URI baseUri()
    {
        return URI.create((https ? "https" : "http") + "://127.0.0.1:" + port + "/");
    }
}
