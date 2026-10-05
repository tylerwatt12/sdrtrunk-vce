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
public record ReceiverActivityStatus(boolean summaryConfigured, boolean detailedHistoryConfigured,
                                   boolean summaryActive, boolean detailedHistoryActive, int retentionDays,
                                   State state, String databasePath, long lastSuccessfulWriteMs,
                                   long recordsWritten, long recordsDropped, String lastError,
                                   StatisticsDropDiagnostics dropDiagnostics)
{
    public ReceiverActivityStatus(boolean summaryConfigured, boolean detailedHistoryConfigured,
                                  boolean summaryActive, boolean detailedHistoryActive, int retentionDays,
                                  State state, String databasePath, long lastSuccessfulWriteMs,
                                  long recordsWritten, long recordsDropped, String lastError)
    {
        this(summaryConfigured, detailedHistoryConfigured, summaryActive, detailedHistoryActive, retentionDays,
            state, databasePath, lastSuccessfulWriteMs, recordsWritten, recordsDropped, lastError,
            new StatisticsDropDiagnostics(0, 0, 0, recordsDropped, 0, 0, 0));
    }

    public enum State
    {
        DISABLED,
        STARTING,
        RUNNING,
        STOPPED,
        FAILED
    }
}
