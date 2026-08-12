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
 * @param detailedHistoryActive true when detailed activity history is updating
 */
public record StatsWebNavigationState(boolean running, int port, boolean https, boolean summaryLoggingActive,
                                      boolean detailedHistoryActive)
{
    /**
     * Compatibility constructor for callers that represent the original plain-HTTP server state.
     */
    public StatsWebNavigationState(boolean running, int port, boolean summaryLoggingActive,
                                   boolean detailedHistoryActive)
    {
        this(running, port, false, summaryLoggingActive, detailedHistoryActive);
    }

    /**
     * Loopback address used by desktop controls to open the embedded web interface.
     */
    public URI baseUri()
    {
        return URI.create((https ? "https" : "http") + "://127.0.0.1:" + port + "/");
    }

    /**
     * Loopback address for the web Alias editor.
     */
    public URI aliasEditorUri()
    {
        return baseUri().resolve("?view=aliases");
    }
}
