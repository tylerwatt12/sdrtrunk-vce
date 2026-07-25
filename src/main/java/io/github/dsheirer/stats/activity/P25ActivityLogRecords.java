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

import io.github.dsheirer.channel.metadata.activity.ChannelTag;
import io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshot;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import java.util.List;

/**
 * Immutable records passed from decoder/UI threads to the SQLite writer.
 */
final class P25ActivityLogRecords
{
    private P25ActivityLogRecords()
    {
    }

    enum ContextKind
    {
        TRUNKED_SITE,
        CONVENTIONAL_P25,
        CONVENTIONAL_ANALOG
    }

    enum Action
    {
        ACKNOWLEDGE,
        ACTIVE,
        BUSY,
        CALL,
        CHECK,
        CHECK_ACK,
        CONTINUE,
        DATA,
        DENIAL,
        EMERGENCY,
        GPS,
        GRANT,
        JOIN,
        LOGOUT,
        PAGE,
        PATCH,
        PATCH_CANCEL,
        PATCH_CREATE,
        QUEUED,
        REGISTER,
        REQUEST,
        STATUS,
        UNKNOWN
    }

    enum CallOutput
    {
        RECORDED,
        STREAMED
    }

    /**
     * One current talkgroup per radio.  A null talkgroup clears the current affiliation.
     */
    record RadioAffiliationUpdate(int radioId, Integer talkgroupId)
    {
    }

    record ActivityEvent(long observedAtEpochMilliseconds, String contextKey, String guid, ContextKind contextKind,
                         String protocol, Action action, String eventType, String sourceRadioId, String targetId,
                         String targetKind, Long frequencyHertz, String lcn, Integer timeslot, boolean encrypted,
                         Integer encryptionAlgorithmId, Integer encryptionKeyId, Integer wacn, Integer systemId,
                         Integer nac, Integer rfss, Integer site, String channelName, String decoder,
                         String talkerAlias, boolean countedCall, String dedupeKey,
                         RadioAffiliationUpdate affiliationUpdate)
        implements P25ActivityLogRecord
    {
    }

    /**
     * Confirmed service use for the durable site-channel inventory.  Activity events remain independent so a
     * candidate that is not yet confirmed never removes grant/call history.
     */
    record ChannelFact(long observedAtEpochMilliseconds, String guid, String lcn, long frequencyHertz,
                       ChannelTag serviceTag, boolean tdma, int timeslots)
        implements P25ActivityLogRecord
    {
    }

    /**
     * Late over-the-air talker alias update for an already-counted call.
     */
    record TalkerAliasUpdate(long observedAtEpochMilliseconds, String contextKey, String guid, Integer wacn,
                             Integer systemId, int radioId, String talkerAlias)
        implements P25ActivityLogRecord
    {
    }

    /**
     * One successful completed-call output. This ephemeral writer message is aggregated directly into compact
     * summaries and time buckets; it is never stored as an individual database row. The call-start timestamp keeps
     * tracked, recorded, and streamed series aligned to the same call hour.
     */
    record CompletedCallOutput(long callStartEpochMilliseconds, String guid, int talkgroupId, CallOutput output)
        implements P25ActivityLogRecord
    {
        @Override
        public long observedAtEpochMilliseconds()
        {
            return callStartEpochMilliseconds;
        }
    }

    record SiteSnapshot(long observedAtEpochMilliseconds, String guid, ContextKind contextKind, String snapshotHash,
                        String protocol, String channelName, String aliasListName, String decoder,
                        Integer wacn, Integer systemId, Integer nac, Integer rfss, Integer site,
                        Integer lra, Boolean tdma, P25NetworkConfigurationSnapshot.SiteStatus siteStatus,
                        Long primaryFrequencyHertz, Long currentControlHertz,
                        List<P25NetworkConfigurationSnapshot.Channel> channels,
                        List<P25NetworkConfigurationSnapshot.NeighborSite> neighborSites,
                        List<P25NetworkConfigurationSnapshot.FrequencyBand> frequencyBands,
                        List<P25NetworkConfigurationSnapshot.PatchGroup> patchGroups,
                        List<P25NetworkConfigurationSnapshot.TalkerAlias> talkerAliases,
                        List<P25NetworkConfigurationSnapshot.ForeignSystemBand> foreignSystemBands)
        implements P25ActivityLogRecord
    {
        SiteSnapshot(long observedAtEpochMilliseconds, String guid, ContextKind contextKind, String snapshotHash,
                     String protocol, String channelName, String aliasListName, String decoder,
                     Integer wacn, Integer systemId, Integer nac, Integer rfss, Integer site,
                     Integer lra, Boolean tdma, P25NetworkConfigurationSnapshot.SiteStatus siteStatus,
                     Long primaryFrequencyHertz, Long currentControlHertz,
                     List<P25NetworkConfigurationSnapshot.Channel> channels,
                     List<P25NetworkConfigurationSnapshot.NeighborSite> neighborSites,
                     List<P25NetworkConfigurationSnapshot.FrequencyBand> frequencyBands,
                     List<P25NetworkConfigurationSnapshot.PatchGroup> patchGroups,
                     List<P25NetworkConfigurationSnapshot.TalkerAlias> talkerAliases)
        {
            this(observedAtEpochMilliseconds, guid, contextKind, snapshotHash, protocol, channelName, aliasListName,
                decoder, wacn, systemId, nac, rfss, site, lra, tdma, siteStatus, primaryFrequencyHertz,
                currentControlHertz, channels, neighborSites, frequencyBands, patchGroups, talkerAliases, List.of());
        }

        SiteSnapshot(long observedAtEpochMilliseconds, String guid, ContextKind contextKind, String snapshotHash,
                     String protocol, String channelName, String aliasListName, String decoder,
                     Integer wacn, Integer systemId, Integer nac, Integer rfss, Integer site,
                     Long primaryFrequencyHertz, Long currentControlHertz,
                     List<P25NetworkConfigurationSnapshot.Channel> channels,
                     List<P25NetworkConfigurationSnapshot.NeighborSite> neighborSites,
                     List<P25NetworkConfigurationSnapshot.FrequencyBand> frequencyBands,
                     List<P25NetworkConfigurationSnapshot.PatchGroup> patchGroups,
                     List<P25NetworkConfigurationSnapshot.TalkerAlias> talkerAliases)
        {
            this(observedAtEpochMilliseconds, guid, contextKind, snapshotHash, protocol, channelName, aliasListName,
                decoder, wacn, systemId, nac, rfss, site, null, null, null, primaryFrequencyHertz,
                currentControlHertz, channels, neighborSites, frequencyBands, patchGroups, talkerAliases, List.of());
        }
    }

    record ControlChannelQuality(long observedAtEpochMilliseconds, String guid, long frequencyHertz,
                                 Double signalDbfs, Double averageSignalDbfs, Double minimumSignalDbfs,
                                 Double maximumSignalDbfs, Double decodeHealthPercent, long validFrames,
                                 long invalidFrames, long correctedBits, long syncLossBits, long droppedBits,
                                 long lastValidDecodeMs)
        implements P25ActivityLogRecord
    {
    }

    record TrunkedSiteSnapshot(long observedAtEpochMilliseconds, TrunkedSiteSchema.Snapshot snapshot)
        implements P25ActivityLogRecord
    {
    }
}
