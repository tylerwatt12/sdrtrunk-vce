/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.stats.activity;

/**
 * Configured and effective state for summary statistics and detailed event history.
 */
public record P25ActivityLogStatus(boolean summaryConfigured, boolean detailedHistoryConfigured,
                                   boolean summaryActive, boolean detailedHistoryActive, int retentionDays,
                                   State state, String databasePath, long lastSuccessfulWriteMs,
                                   long recordsWritten, long recordsDropped, String lastError)
{
    public enum State
    {
        DISABLED,
        STARTING,
        RUNNING,
        STOPPED,
        FAILED
    }
}
