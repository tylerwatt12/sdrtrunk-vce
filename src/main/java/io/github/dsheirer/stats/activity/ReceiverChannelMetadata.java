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
                               long lastSeenEpochMilliseconds, Integer nac, Integer rfss, Integer site,
                               Long currentControlHertz)
{
    static ReceiverChannelMetadata from(ReceiverActivityRecords.ActivityEvent activity)
    {
        return new ReceiverChannelMetadata(activity.configurationId(), activity.observedAtEpochMilliseconds(),
            activity.observedAtEpochMilliseconds(), activity.nac(), activity.rfss(), activity.site(), null);
    }

    static ReceiverChannelMetadata from(ReceiverActivityRecords.SiteSnapshot snapshot)
    {
        return new ReceiverChannelMetadata(snapshot.configurationId(), snapshot.observedAtEpochMilliseconds(),
            snapshot.observedAtEpochMilliseconds(), snapshot.nac(), snapshot.rfss(), snapshot.site(),
            snapshot.currentControlHertz());
    }

    static ReceiverChannelMetadata from(ReceiverActivityRecords.DmrConventionalCall call)
    {
        return new ReceiverChannelMetadata(call.configurationId(), call.callStartEpochMilliseconds(),
            call.callEndEpochMilliseconds(), null, null, null, null);
    }

    static ReceiverChannelMetadata from(ReceiverActivityRecords.NxdnConventionalCall call)
    {
        return new ReceiverChannelMetadata(call.configurationId(), call.callStartEpochMilliseconds(),
            call.callEndEpochMilliseconds(), null, null, null, null);
    }

    static ReceiverChannelMetadata from(ReceiverActivityRecords.ResolvedLogicalCall call)
    {
        return new ReceiverChannelMetadata(call.configurationId(), call.callStartEpochMilliseconds(),
            call.callStartEpochMilliseconds(), null, null, null, null);
    }

    static ReceiverChannelMetadata from(TrunkedSiteSchema.Snapshot snapshot)
    {
        return new ReceiverChannelMetadata(snapshot.configurationId(), snapshot.observedAtEpochMilliseconds(),
            snapshot.observedAtEpochMilliseconds(), null, null, null, snapshot.currentControlHertz());
    }
}
