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
import java.util.TreeSet;

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
        CONVENTIONAL_DMR,
        CONVENTIONAL_NXDN,
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
                         String targetKind, List<Integer> patchMemberTalkgroupIds, Long frequencyHertz, String lcn,
                         Integer timeslot, boolean encrypted, Integer encryptionAlgorithmId, Integer encryptionKeyId,
                         Integer wacn, Integer systemId, Integer nac, Integer rfss, Integer site, String channelName,
                         String decoder, String talkerAlias, boolean countedCall, String dedupeKey,
                         RadioAffiliationUpdate affiliationUpdate)
        implements P25ActivityLogRecord
    {
        ActivityEvent
        {
            patchMemberTalkgroupIds = distinctPositiveTalkgroups(patchMemberTalkgroupIds,
                positiveInteger(targetId));
        }

        ActivityEvent(long observedAtEpochMilliseconds, String contextKey, String guid, ContextKind contextKind,
                      String protocol, Action action, String eventType, String sourceRadioId, String targetId,
                      String targetKind, Long frequencyHertz, String lcn, Integer timeslot, boolean encrypted,
                      Integer encryptionAlgorithmId, Integer encryptionKeyId, Integer wacn, Integer systemId,
                      Integer nac, Integer rfss, Integer site, String channelName, String decoder,
                      String talkerAlias, boolean countedCall, String dedupeKey,
                      RadioAffiliationUpdate affiliationUpdate)
        {
            this(observedAtEpochMilliseconds, contextKey, guid, contextKind, protocol, action, eventType,
                sourceRadioId, targetId, targetKind, List.of(), frequencyHertz, lcn, timeslot, encrypted,
                encryptionAlgorithmId, encryptionKeyId, wacn, systemId, nac, rfss, site, channelName, decoder,
                talkerAlias, countedCall, dedupeKey, affiliationUpdate);
        }
    }

    /**
     * One-time identity/encryption enrichment for an already-counted trunked call.
     */
    record TrunkedCallAttribution(long callStartEpochMilliseconds, String contextKey, String guid,
                                  Long frequencyHertz, Integer timeslot,
                                  int destinationId, String destinationKind,
                                  List<Integer> patchMemberTalkgroupIds, Integer sourceRadioId,
                                  boolean destinationBecameKnown, boolean sourceBecameKnown,
                                  boolean encryptionBecameKnown, boolean encryptedBeforeObservation)
        implements P25ActivityLogRecord
    {
        TrunkedCallAttribution
        {
            patchMemberTalkgroupIds = distinctPositiveTalkgroups(patchMemberTalkgroupIds, destinationId);
        }

        @Override
        public long observedAtEpochMilliseconds()
        {
            return callStartEpochMilliseconds;
        }
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
     * One successful completed-call output. This protocol-neutral ephemeral writer message is aggregated directly
     * into compact summaries and time buckets; it is never stored as an individual database row. The call-start
     * timestamp keeps tracked, recorded, and streamed series aligned to the same call hour. The legacy
     * {@code talkgroupId} component carries the numeric destination for radio/private calls too; targetKind controls
     * how that value is interpreted and keeps talkgroup-specific projections gated.
     */
    record CompletedCallOutput(long callStartEpochMilliseconds, String contextKey, String guid,
                               Long frequencyHertz, Integer timeslot, int talkgroupId, String targetKind,
                               List<Integer> patchMemberTalkgroupIds, Integer sourceRadioId, CallOutput output)
        implements P25ActivityLogRecord
    {
        CompletedCallOutput
        {
            patchMemberTalkgroupIds = distinctPositiveTalkgroups(patchMemberTalkgroupIds, talkgroupId);
        }

        CompletedCallOutput(long callStartEpochMilliseconds, String contextKey, String guid,
                            Long frequencyHertz, Integer timeslot, int talkgroupId, String targetKind,
                            List<Integer> patchMemberTalkgroupIds, CallOutput output)
        {
            this(callStartEpochMilliseconds, contextKey, guid, frequencyHertz, timeslot, talkgroupId, targetKind,
                patchMemberTalkgroupIds, null, output);
        }

        CompletedCallOutput(long callStartEpochMilliseconds, String guid, int talkgroupId, String targetKind,
                            List<Integer> patchMemberTalkgroupIds, CallOutput output)
        {
            this(callStartEpochMilliseconds, guid != null && !guid.isBlank() ? "GUID:" + guid : null, guid,
                null, null, talkgroupId, targetKind, patchMemberTalkgroupIds, null, output);
        }

        CompletedCallOutput(long callStartEpochMilliseconds, String guid, int talkgroupId, CallOutput output)
        {
            this(callStartEpochMilliseconds, guid, talkgroupId, "TALKGROUP", List.of(), output);
        }

        int destinationId()
        {
            return talkgroupId;
        }

        @Override
        public long observedAtEpochMilliseconds()
        {
            return callStartEpochMilliseconds;
        }
    }

    /**
     * One completed conventional DMR call. This writer message always updates compact summaries and may also retain
     * one optional detailed row.
     */
    record DmrConventionalCall(long callStartEpochMilliseconds, long callEndEpochMilliseconds, String contextKey,
                               String guid, String channelName, String aliasListName, long frequencyHertz,
                               int timeslot, DmrTargetKind targetKind, Integer talkgroupId, Integer sourceRadioId,
                               Integer targetRadioId, boolean encrypted)
        implements P25ActivityLogRecord
    {
        @Override
        public long observedAtEpochMilliseconds()
        {
            return callEndEpochMilliseconds;
        }
    }

    enum DmrTargetKind
    {
        GROUP,
        PRIVATE,
        UNKNOWN
    }

    /**
     * One completed conventional NXDN call. This writer message always updates compact conventional summaries and
     * may also retain one optional detailed row.
     */
    record NxdnConventionalCall(long callStartEpochMilliseconds, long callEndEpochMilliseconds, String contextKey,
                                String guid, String channelName, String aliasListName, long frequencyHertz,
                                NxdnTargetKind targetKind, Integer talkgroupId, Integer sourceRadioId,
                                Integer targetRadioId, boolean encrypted)
        implements P25ActivityLogRecord
    {
        @Override
        public long observedAtEpochMilliseconds()
        {
            return callEndEpochMilliseconds;
        }
    }

    enum NxdnTargetKind
    {
        GROUP,
        PRIVATE,
        UNKNOWN
    }

    private static List<Integer> distinctPositiveTalkgroups(List<Integer> talkgroups, Integer excludedTalkgroup)
    {
        if(talkgroups == null || talkgroups.isEmpty())
        {
            return List.of();
        }

        TreeSet<Integer> distinct = new TreeSet<>();

        for(Integer talkgroup: talkgroups)
        {
            if(talkgroup != null && talkgroup > 0 && !talkgroup.equals(excludedTalkgroup))
            {
                distinct.add(talkgroup);
            }
        }

        return List.copyOf(distinct);
    }

    private static Integer positiveInteger(String value)
    {
        if(value == null)
        {
            return null;
        }

        try
        {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : null;
        }
        catch(NumberFormatException e)
        {
            return null;
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
                     List<P25NetworkConfigurationSnapshot.PatchGroup> patchGroups)
        {
            this(observedAtEpochMilliseconds, guid, contextKind, snapshotHash, protocol, channelName, aliasListName,
                decoder, wacn, systemId, nac, rfss, site, lra, tdma, siteStatus, primaryFrequencyHertz,
                currentControlHertz, channels, neighborSites, frequencyBands, patchGroups, List.of());
        }

        SiteSnapshot(long observedAtEpochMilliseconds, String guid, ContextKind contextKind, String snapshotHash,
                     String protocol, String channelName, String aliasListName, String decoder,
                     Integer wacn, Integer systemId, Integer nac, Integer rfss, Integer site,
                     Long primaryFrequencyHertz, Long currentControlHertz,
                     List<P25NetworkConfigurationSnapshot.Channel> channels,
                     List<P25NetworkConfigurationSnapshot.NeighborSite> neighborSites,
                     List<P25NetworkConfigurationSnapshot.FrequencyBand> frequencyBands,
                     List<P25NetworkConfigurationSnapshot.PatchGroup> patchGroups)
        {
            this(observedAtEpochMilliseconds, guid, contextKind, snapshotHash, protocol, channelName, aliasListName,
                decoder, wacn, systemId, nac, rfss, site, null, null, null, primaryFrequencyHertz,
                currentControlHertz, channels, neighborSites, frequencyBands, patchGroups, List.of());
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
