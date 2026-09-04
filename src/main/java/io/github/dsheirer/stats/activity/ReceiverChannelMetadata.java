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

import io.github.dsheirer.stats.site.TrunkedSiteSchema;

/**
 * Common receiver identity and configured metadata shared by every supported trunked and conventional protocol.
 * Protocol-specific call and site facts remain on their existing records.
 */
record ReceiverChannelMetadata(String configurationId, long firstSeenEpochMilliseconds,
                               long lastSeenEpochMilliseconds)
{
    static ReceiverChannelMetadata from(ReceiverActivityRecords.ActivityEvent activity)
    {
        return observed(activity.configurationId(), activity.observedAtEpochMilliseconds());
    }

    static ReceiverChannelMetadata from(ReceiverActivityRecords.SiteSnapshot snapshot)
    {
        return observed(snapshot.configurationId(), snapshot.observedAtEpochMilliseconds());
    }

    static ReceiverChannelMetadata from(ReceiverActivityRecords.DmrConventionalCall call)
    {
        return new ReceiverChannelMetadata(call.configurationId(), call.callStartEpochMilliseconds(),
            call.callEndEpochMilliseconds());
    }

    static ReceiverChannelMetadata from(ReceiverActivityRecords.NxdnConventionalCall call)
    {
        return new ReceiverChannelMetadata(call.configurationId(), call.callStartEpochMilliseconds(),
            call.callEndEpochMilliseconds());
    }

    static ReceiverChannelMetadata from(ReceiverActivityRecords.ResolvedLogicalCall call)
    {
        return observed(call.configurationId(), call.callStartEpochMilliseconds());
    }

    static ReceiverChannelMetadata from(TrunkedSiteSchema.Snapshot snapshot)
    {
        return observed(snapshot.configurationId(), snapshot.observedAtEpochMilliseconds());
    }

    private static ReceiverChannelMetadata observed(String configurationId, long observedAt)
    {
        return new ReceiverChannelMetadata(configurationId, observedAt, observedAt);
    }
}
