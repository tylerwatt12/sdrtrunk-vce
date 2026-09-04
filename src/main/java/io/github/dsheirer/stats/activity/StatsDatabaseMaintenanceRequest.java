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

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Requests database maintenance on the single statistics database writer.
 */
public final class StatsDatabaseMaintenanceRequest
{
    private final ReceiverActivityMaintenance.Operation mOperation;
    private final String mConfigurationId;
    private final CompletableFuture<ReceiverActivityMaintenance.Result> mResult = new CompletableFuture<>();

    private StatsDatabaseMaintenanceRequest(ReceiverActivityMaintenance.Operation operation, String configurationId)
    {
        mOperation = Objects.requireNonNull(operation, "Maintenance operation is required");
        mConfigurationId = configurationId;

        if(operation == ReceiverActivityMaintenance.Operation.CLEAR_SITE_STATS &&
            (configurationId == null || configurationId.isBlank()))
        {
            throw new IllegalArgumentException("Channel configuration ID is required");
        }
        else if(operation != ReceiverActivityMaintenance.Operation.CLEAR_SITE_STATS && configurationId != null)
        {
            throw new IllegalArgumentException("Channel configuration ID is only valid for CLEAR_SITE_STATS");
        }
    }

    public static StatsDatabaseMaintenanceRequest forOperation(ReceiverActivityMaintenance.Operation operation)
    {
        return new StatsDatabaseMaintenanceRequest(operation, null);
    }

    public static StatsDatabaseMaintenanceRequest clearSite(String configurationId)
    {
        return new StatsDatabaseMaintenanceRequest(ReceiverActivityMaintenance.Operation.CLEAR_SITE_STATS,
            configurationId);
    }

    public ReceiverActivityMaintenance.Operation operation()
    {
        return mOperation;
    }

    public String configurationId()
    {
        return mConfigurationId;
    }

    public CompletableFuture<ReceiverActivityMaintenance.Result> result()
    {
        return mResult;
    }
}
