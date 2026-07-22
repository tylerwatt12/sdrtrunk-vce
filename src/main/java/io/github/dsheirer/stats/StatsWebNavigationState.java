/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats;

import java.net.URI;
import java.util.Objects;

/**
 * Current embedded-web state used by desktop navigation controls.
 *
 * @param running true when the embedded web server is listening
 * @param baseUri configured/listening local web address
 * @param summaryLoggingActive true when summary statistics are updating
 * @param detailedHistoryActive true when detailed activity history is updating
 */
public record StatsWebNavigationState(boolean running, URI baseUri, boolean summaryLoggingActive,
                                      boolean detailedHistoryActive)
{
    public StatsWebNavigationState
    {
        Objects.requireNonNull(baseUri, "Web base URI cannot be null");
    }

    /**
     * Compatibility constructor for existing HTTP-loopback callers and tests.
     */
    public StatsWebNavigationState(boolean running, int port, boolean summaryLoggingActive,
                                   boolean detailedHistoryActive)
    {
        this(running, URI.create("http://127.0.0.1:" + port + "/"), summaryLoggingActive,
            detailedHistoryActive);
    }

    public int port()
    {
        return baseUri.getPort();
    }

    public boolean https()
    {
        return "https".equalsIgnoreCase(baseUri.getScheme());
    }
}
