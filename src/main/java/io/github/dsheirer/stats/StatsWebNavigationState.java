/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats;

/**
 * Current embedded-web state used by desktop navigation controls.
 *
 * @param running true when the embedded web server is listening
 * @param port configured/listening port
 * @param summaryLoggingActive true when summary statistics are updating
 * @param detailedHistoryActive true when detailed activity history is updating
 */
public record StatsWebNavigationState(boolean running, int port, boolean summaryLoggingActive,
                                      boolean detailedHistoryActive)
{
}
