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

    /**
     * Loopback address for editing one persisted Alias.
     *
     * @param aliasListId persisted Alias List identity
     * @param aliasId persisted Alias identity
     */
    public URI aliasEditorUri(long aliasListId, long aliasId)
    {
        if(aliasListId <= 0 || aliasId <= 0)
        {
            throw new IllegalArgumentException("Alias List and Alias IDs must be positive");
        }

        return baseUri().resolve("?view=aliases&list=" + aliasListId + "&alias=" + aliasId);
    }
}
