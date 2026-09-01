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
record ReceiverContextMetadata(String contextKey, String guid, P25ActivityLogRecords.ContextKind contextKind,
                               String protocol, String channelName, String aliasListName, String decoder,
                               boolean configuredMetadataObserved, long firstSeenEpochMilliseconds,
                               long lastSeenEpochMilliseconds, Integer systemKey, Integer nac, Integer rfss,
                               Integer site, Long primaryFrequencyHertz, Long currentControlHertz)
{
    static ReceiverContextMetadata from(P25ActivityLogRecords.ActivityEvent activity, Integer systemKey)
    {
        boolean conventional = activity.contextKind() != P25ActivityLogRecords.ContextKind.TRUNKED_SITE;
        String channelName = conventional || activity.configuredMetadataObserved() ? activity.channelName() : null;
        return new ReceiverContextMetadata(activity.contextKey(), activity.guid(), activity.contextKind(),
            activity.protocol(), channelName, blankToNull(activity.aliasListName()), activity.decoder(),
            activity.configuredMetadataObserved(), activity.observedAtEpochMilliseconds(),
            activity.observedAtEpochMilliseconds(),
            conventional ? null : systemKey, conventional ? activity.nac() : null,
            conventional ? activity.rfss() : null, conventional ? activity.site() : null,
            conventional ? activity.frequencyHertz() : null, null);
    }

    static ReceiverContextMetadata from(P25ActivityLogRecords.SiteSnapshot snapshot, Integer systemKey)
    {
        return new ReceiverContextMetadata(ReceiverContextKey.guid(snapshot.guid()), snapshot.guid(),
            snapshot.contextKind(), snapshot.protocol(), snapshot.channelName(), blankToNull(snapshot.aliasListName()),
            snapshot.decoder(), true, snapshot.observedAtEpochMilliseconds(), snapshot.observedAtEpochMilliseconds(),
            systemKey, snapshot.nac(), snapshot.rfss(), snapshot.site(), snapshot.primaryFrequencyHertz(),
            snapshot.currentControlHertz());
    }

    static ReceiverContextMetadata from(P25ActivityLogRecords.DmrConventionalCall call)
    {
        return new ReceiverContextMetadata(call.contextKey(), call.guid(),
            P25ActivityLogRecords.ContextKind.CONVENTIONAL_DMR, "DMR", call.channelName(),
            blankToNull(call.aliasListName()), "DMR", true, call.callStartEpochMilliseconds(),
            call.callEndEpochMilliseconds(), null, null, null, null, call.frequencyHertz(), null);
    }

    static ReceiverContextMetadata from(P25ActivityLogRecords.NxdnConventionalCall call)
    {
        return new ReceiverContextMetadata(call.contextKey(), call.guid(),
            P25ActivityLogRecords.ContextKind.CONVENTIONAL_NXDN, "NXDN", call.channelName(),
            blankToNull(call.aliasListName()), "NXDN", true, call.callStartEpochMilliseconds(),
            call.callEndEpochMilliseconds(), null, null, null, null, call.frequencyHertz(), null);
    }

    static ReceiverContextMetadata from(TrunkedSiteSchema.Snapshot snapshot)
    {
        String protocol = snapshot.protocolCode() == TrunkedSiteSchema.PROTOCOL_DMR ? "DMR" :
            snapshot.protocolCode() == TrunkedSiteSchema.PROTOCOL_NXDN ? "NXDN" : null;
        return new ReceiverContextMetadata(ReceiverContextKey.guid(snapshot.guid()), snapshot.guid(),
            P25ActivityLogRecords.ContextKind.TRUNKED_SITE, protocol, snapshot.channelName(),
            blankToNull(snapshot.aliasListName()), snapshot.decoder(), true, snapshot.observedAtEpochMilliseconds(),
            snapshot.observedAtEpochMilliseconds(), null, null, null, null, snapshot.primaryFrequencyHertz(),
            snapshot.currentControlHertz());
    }

    private static String blankToNull(String value)
    {
        return value != null && !value.isBlank() ? value : null;
    }
}
