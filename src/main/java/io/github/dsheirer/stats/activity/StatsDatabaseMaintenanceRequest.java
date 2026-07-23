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
    private final P25ActivityLogMaintenance.Operation mOperation;
    private final String mSiteGuid;
    private final CompletableFuture<P25ActivityLogMaintenance.Result> mResult = new CompletableFuture<>();

    private StatsDatabaseMaintenanceRequest(P25ActivityLogMaintenance.Operation operation, String siteGuid)
    {
        mOperation = Objects.requireNonNull(operation, "Maintenance operation is required");
        mSiteGuid = siteGuid;

        if(operation == P25ActivityLogMaintenance.Operation.CLEAR_SITE_STATS &&
            (siteGuid == null || siteGuid.isBlank()))
        {
            throw new IllegalArgumentException("Site GUID is required");
        }
        else if(operation != P25ActivityLogMaintenance.Operation.CLEAR_SITE_STATS && siteGuid != null)
        {
            throw new IllegalArgumentException("Site GUID is only valid for CLEAR_SITE_STATS");
        }
    }

    public static StatsDatabaseMaintenanceRequest forOperation(P25ActivityLogMaintenance.Operation operation)
    {
        return new StatsDatabaseMaintenanceRequest(operation, null);
    }

    public static StatsDatabaseMaintenanceRequest clearSite(String guid)
    {
        return new StatsDatabaseMaintenanceRequest(P25ActivityLogMaintenance.Operation.CLEAR_SITE_STATS, guid);
    }

    public P25ActivityLogMaintenance.Operation operation()
    {
        return mOperation;
    }

    public String siteGuid()
    {
        return mSiteGuid;
    }

    public CompletableFuture<P25ActivityLogMaintenance.Result> result()
    {
        return mResult;
    }
}
