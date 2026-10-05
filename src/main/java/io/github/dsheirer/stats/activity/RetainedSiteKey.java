/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.stats.activity;

/**
 * Stable identity of the site currently represented by a saved channel's retained snapshot.
 * The channel configuration ID remains the owner; this key prevents a stale delete from clearing
 * a different site after the channel has been reassigned.
 */
public final class RetainedSiteKey
{
    private RetainedSiteKey()
    {
    }

    public static String conventional(long channelId, long channelFirstSeenMs)
    {
        return "conventional:" + channelId + ':' + channelFirstSeenMs;
    }

    /** Stable saved-channel owner when a trunked channel has no current site snapshot. */
    public static String snapshotless(long channelId, Long radioSystemId, long channelFirstSeenMs)
    {
        return "trunked-unsited" + owner(channelId, radioSystemId, channelFirstSeenMs);
    }

    public static String p25(Integer rfss, Integer site, long channelId, Long radioSystemId,
                             long snapshotFirstSeenMs)
    {
        return "p25:" + value(rfss) + ':' + value(site) + owner(channelId, radioSystemId,
            snapshotFirstSeenMs);
    }

    public static String trunked(int protocolCode, int variantCode, int locationCategoryCode,
                                 Integer networkId, Integer systemId, Integer siteId, Integer ran,
                                 Integer modelCode, long channelId, Long radioSystemId,
                                 long snapshotFirstSeenMs)
    {
        if(protocolCode != 3 && protocolCode != 4)
        {
            throw new IllegalArgumentException("Only DMR and NXDN have this site identity");
        }

        return (protocolCode == 3 ? "dmr:" : "nxdn:") + variantCode + ':' + locationCategoryCode + ':' +
            value(networkId) + ':' + value(systemId) + ':' + value(siteId) + ':' + value(ran) + ':' +
            value(modelCode) + owner(channelId, radioSystemId, snapshotFirstSeenMs);
    }

    private static String owner(long channelId, Long radioSystemId, long snapshotFirstSeenMs)
    {
        return ":" + channelId + ':' + (radioSystemId == null ? "x" : radioSystemId) + ':' + snapshotFirstSeenMs;
    }

    private static String value(Integer number)
    {
        return number == null ? "x" : Integer.toString(number);
    }
}
