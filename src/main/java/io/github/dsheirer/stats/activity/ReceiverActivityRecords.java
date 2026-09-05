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

import io.github.dsheirer.audio.call.LogicalCallId;
import io.github.dsheirer.channel.metadata.activity.ChannelTag;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshot;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import java.util.List;
import java.util.TreeSet;

/**
 * Immutable records passed from decoder/UI threads to the SQLite writer.
 */
final class ReceiverActivityRecords
{
    static final int MAXIMUM_PATCH_MEMBER_TALKGROUPS = 64;

    private ReceiverActivityRecords()
    {
    }

    enum ReceiverKind
    {
        TRUNKED_SITE,
        CONVENTIONAL_P25,
        CONVENTIONAL_DMR,
        CONVENTIONAL_NXDN,
        CONVENTIONAL_ANALOG
    }

    enum Action
    {
        ACKNOWLEDGE(1),
        ACTIVE(2),
        BUSY(3),
        CALL(4),
        CHECK(5),
        CHECK_ACK(6),
        CONTINUE(7),
        DATA(8),
        DENIAL(9),
        EMERGENCY(10),
        GPS(11),
        GRANT(12),
        JOIN(13),
        LOGOUT(14),
        PAGE(15),
        PATCH(16),
        PATCH_CANCEL(17),
        PATCH_CREATE(18),
        QUEUED(19),
        REGISTER(20),
        REQUEST(21),
        STATUS(22),
        UNKNOWN(23);

        private final int mCode;

        Action(int code)
        {
            mCode = code;
        }

        /**
         * Stable database code.  The value must never be derived from the enum's declaration order.
         */
        int code()
        {
            return mCode;
        }
    }

    enum CallOutput
    {
        RECORDED,
        STREAMED
    }

    /**
     * One globally resolved trunked call.  The coordinator supplies this only after all eligible receiver legs have
     * been grouped and one winner has been selected.  The process-local logical call id is used only for bounded
     * output idempotency and is never stored in SQLite.
     */
    record ResolvedLogicalCall(LogicalCallId logicalCallId, long callStartEpochMilliseconds, String configurationId,
                               String protocol, IdentityDomain identityDomain, Integer wacn, Integer systemId,
                               int destinationId, String destinationKind,
                               List<Integer> patchMemberTalkgroupIds, Integer sourceRadioId, boolean encrypted,
                               Integer encryptionAlgorithmId, Integer encryptionKeyId,
                               P25TargetIdentity p25TargetIdentity,
                               List<P25PatchMemberIdentity> p25PatchMemberIdentities,
                               List<P25SiteIdentity> learnedP25Sites)
        implements ReceiverActivityRecord
    {
        ResolvedLogicalCall
        {
            if(logicalCallId == null || callStartEpochMilliseconds <= 0 ||
                configurationId == null || configurationId.isBlank())
            {
                throw new IllegalArgumentException("Resolved logical call requires an id and start timestamp");
            }

            identityDomain = identityDomain != null ? identityDomain : IdentityDomain.STANDARD;
            patchMemberTalkgroupIds = distinctPositiveTalkgroups(patchMemberTalkgroupIds, destinationId);
            p25TargetIdentity = p25TargetIdentity != null ? p25TargetIdentity : P25TargetIdentity.UNKNOWN;
            p25PatchMemberIdentities = normalizeP25PatchMemberIdentities(p25PatchMemberIdentities,
                patchMemberTalkgroupIds);
            learnedP25Sites = learnedP25Sites == null ? List.of() : learnedP25Sites.stream()
                .filter(java.util.Objects::nonNull).distinct().sorted(java.util.Comparator
                    .comparingInt(P25SiteIdentity::wacn).thenComparingInt(P25SiteIdentity::system)
                    .thenComparingInt(P25SiteIdentity::rfss).thenComparingInt(P25SiteIdentity::site))
                .toList();
        }

        @Override
        public long observedAtEpochMilliseconds()
        {
            return callStartEpochMilliseconds;
        }
    }

    /**
     * One successful local output for a previously resolved logical call.  This queue message contains the resolved
     * projection so the database can update compact aggregates without persisting a per-call row or identifier.
     */
    record LogicalCallOutput(ResolvedLogicalCall call, CallOutput output) implements ReceiverActivityRecord
    {
        LogicalCallOutput
        {
            if(call == null || output == null)
            {
                throw new IllegalArgumentException("Logical call output requires a resolved call and output kind");
            }
        }

        @Override
        public long observedAtEpochMilliseconds()
        {
            return call.callStartEpochMilliseconds();
        }
    }

    /**
     * Identity-number interpretation when a protocol has multiple on-air address domains.
     */
    enum IdentityDomain
    {
        STANDARD,
        NXDN_TYPE_C,
        NXDN_TYPE_D
    }

    /**
     * How a P25 destination number was presented over the air.  This remains unknown for non-P25 protocols and for
     * older records that did not carry enough information to distinguish an ordinary talkgroup from an ISSI alias.
     */
    enum P25IdentityState
    {
        UNKNOWN(0),
        ORDINARY(1),
        STABLE_FULLY_QUALIFIED(2),
        AMBIGUOUS(3);

        private final int mCode;

        P25IdentityState(int code)
        {
            mCode = code;
        }

        int code()
        {
            return mCode;
        }
    }

    /**
     * Compact P25 destination evidence.  A fully-qualified destination keeps its home identity while the existing
     * numeric destination remains the local on-system alias used by the summary row.
     */
    record P25TargetIdentity(P25IdentityState state, Integer homeWacn, Integer homeSystemId,
                             Integer homeTalkgroupId)
    {
        static final P25TargetIdentity UNKNOWN =
            new P25TargetIdentity(P25IdentityState.UNKNOWN, null, null, null);
        static final P25TargetIdentity ORDINARY =
            new P25TargetIdentity(P25IdentityState.ORDINARY, null, null, null);
        static final P25TargetIdentity AMBIGUOUS =
            new P25TargetIdentity(P25IdentityState.AMBIGUOUS, null, null, null);

        P25TargetIdentity
        {
            state = state != null ? state : P25IdentityState.UNKNOWN;

            if(state == P25IdentityState.STABLE_FULLY_QUALIFIED)
            {
                if(homeWacn == null || homeWacn < 0 || homeWacn > 0xFFFFF ||
                    homeSystemId == null || homeSystemId < 0 || homeSystemId > 0xFFF ||
                    homeTalkgroupId == null || homeTalkgroupId <= 0 || homeTalkgroupId >= 0xFFFF)
                {
                    state = P25IdentityState.UNKNOWN;
                    homeWacn = null;
                    homeSystemId = null;
                    homeTalkgroupId = null;
                }
            }
            else
            {
                homeWacn = null;
                homeSystemId = null;
                homeTalkgroupId = null;
            }
        }

        static P25TargetIdentity fullyQualified(int homeWacn, int homeSystemId, int homeTalkgroupId)
        {
            return new P25TargetIdentity(P25IdentityState.STABLE_FULLY_QUALIFIED, homeWacn, homeSystemId,
                homeTalkgroupId);
        }

        int stateCode()
        {
            return state.code();
        }

        boolean isStableFullyQualified()
        {
            return state == P25IdentityState.STABLE_FULLY_QUALIFIED;
        }
    }

    /**
     * P25 identity evidence for one patch member before its identifier is flattened to a local integer.  Keeping this
     * beside the compact integer list lets the summary projection distinguish a plain member from a fully-qualified
     * member without inferring either one from the local alias alone.
     */
    record P25PatchMemberIdentity(int localTalkgroupId, P25TargetIdentity targetIdentity)
    {
        P25PatchMemberIdentity
        {
            if(localTalkgroupId <= 0)
            {
                throw new IllegalArgumentException("P25 patch member talkgroup must be positive");
            }

            targetIdentity = targetIdentity != null ? targetIdentity : P25TargetIdentity.UNKNOWN;
            if(targetIdentity.state() == P25IdentityState.UNKNOWN)
            {
                throw new IllegalArgumentException("P25 patch member identity must be qualified");
            }
        }
    }

    enum RadioPresenceEvidence
    {
        REGISTRATION(1),
        AFFILIATION(2);

        private final int mCode;

        RadioPresenceEvidence(int code)
        {
            mCode = code;
        }

        int code()
        {
            return mCode;
        }
    }

    /**
     * Authoritative radio state learned from an accepted registration or affiliation exchange. Every confirmed
     * update supplies site-local presence; a talkgroup, when present, independently confirms current affiliation.
     * A cleared update removes both states. Calls and other radio observations never create this record.
     */
    record RadioPresenceUpdate(int radioId, Integer talkgroupId, RadioPresenceEvidence evidence, boolean cleared)
    {
        RadioPresenceUpdate
        {
            if(radioId <= 0 || talkgroupId != null && talkgroupId <= 0 ||
                cleared && (talkgroupId != null || evidence != null) || !cleared && evidence == null)
            {
                throw new IllegalArgumentException("Invalid authoritative radio presence update");
            }
        }

        static RadioPresenceUpdate confirmed(int radioId, Integer talkgroupId, RadioPresenceEvidence evidence)
        {
            return new RadioPresenceUpdate(radioId, talkgroupId, evidence, false);
        }

        static RadioPresenceUpdate cleared(int radioId)
        {
            return new RadioPresenceUpdate(radioId, null, null, true);
        }
    }

    record ActivityEvent(long observedAtEpochMilliseconds, String configurationId, ReceiverKind receiverKind,
                         String protocol, Action action, String eventType, String sourceRadioId, String targetId,
                         String targetKind, List<Integer> patchMemberTalkgroupIds, Long frequencyHertz, String lcn,
                         Integer timeslot, boolean encrypted, Integer encryptionAlgorithmId, Integer encryptionKeyId,
                         Integer wacn, Integer systemId, Integer nac, Integer rfss, Integer site,
                         String talkerAlias, boolean countedCall, String dedupeKey,
                         RadioPresenceUpdate radioPresenceUpdate, IdentityDomain identityDomain,
                         P25TargetIdentity p25TargetIdentity,
                         List<P25PatchMemberIdentity> p25PatchMemberIdentities)
        implements ReceiverActivityRecord
    {
        ActivityEvent
        {
            if(configurationId == null || configurationId.isBlank())
            {
                throw new IllegalArgumentException("Receiver activity requires a saved channel configuration ID");
            }
            patchMemberTalkgroupIds = distinctPositiveTalkgroups(patchMemberTalkgroupIds,
                positiveInteger(targetId));
            identityDomain = identityDomain != null ? identityDomain : IdentityDomain.STANDARD;
            p25TargetIdentity = p25TargetIdentity != null ? p25TargetIdentity : P25TargetIdentity.UNKNOWN;
            p25PatchMemberIdentities = normalizeP25PatchMemberIdentities(p25PatchMemberIdentities,
                patchMemberTalkgroupIds);
        }

    }

    /**
     * One-time identity/encryption enrichment for an already-counted trunked call.
     */
    record TrunkedCallAttribution(long callStartEpochMilliseconds, String configurationId,
                                  Long frequencyHertz, Integer timeslot,
                                  int destinationId, String destinationKind,
                                  List<Integer> patchMemberTalkgroupIds, Integer sourceRadioId,
                                  Integer encryptionAlgorithmId, Integer encryptionKeyId,
                                  boolean destinationBecameKnown, boolean sourceBecameKnown,
                                  boolean encryptionBecameKnown, boolean encryptedBeforeObservation,
                                  IdentityDomain identityDomain, P25TargetIdentity p25TargetIdentity,
                                  List<P25PatchMemberIdentity> p25PatchMemberIdentities)
        implements ReceiverActivityRecord
    {
        TrunkedCallAttribution
        {
            if(configurationId == null || configurationId.isBlank())
            {
                throw new IllegalArgumentException("Call attribution requires a saved channel configuration ID");
            }
            patchMemberTalkgroupIds = distinctPositiveTalkgroups(patchMemberTalkgroupIds, destinationId);
            identityDomain = identityDomain != null ? identityDomain : IdentityDomain.STANDARD;
            p25TargetIdentity = p25TargetIdentity != null ? p25TargetIdentity : P25TargetIdentity.UNKNOWN;
            p25PatchMemberIdentities = normalizeP25PatchMemberIdentities(p25PatchMemberIdentities,
                patchMemberTalkgroupIds);
        }

        boolean hasEncryptionDetails()
        {
            return encryptionAlgorithmId != null || encryptionKeyId != null;
        }

        boolean hasP25TargetIdentity()
        {
            return p25TargetIdentity.state() != P25IdentityState.UNKNOWN ||
                !p25PatchMemberIdentities.isEmpty();
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
    record ChannelFact(long observedAtEpochMilliseconds, String configurationId, String lcn, long frequencyHertz,
                       ChannelTag serviceTag, boolean tdma, int timeslots)
        implements ReceiverActivityRecord
    {
    }

    /**
     * Late over-the-air talker alias update for an already-counted call.
     */
    record TalkerAliasUpdate(long observedAtEpochMilliseconds, String configurationId, Integer wacn,
                             Integer systemId, int radioId, String talkerAlias, IdentityDomain identityDomain)
        implements ReceiverActivityRecord
    {
        TalkerAliasUpdate
        {
            identityDomain = identityDomain != null ? identityDomain : IdentityDomain.STANDARD;
        }

        TalkerAliasUpdate(long observedAtEpochMilliseconds, String configurationId, Integer wacn,
                          Integer systemId, int radioId, String talkerAlias)
        {
            this(observedAtEpochMilliseconds, configurationId, wacn, systemId, radioId, talkerAlias,
                IdentityDomain.STANDARD);
        }
    }

    /**
     * One successful completed-call output. This protocol-neutral ephemeral writer message is aggregated directly
     * into compact summaries and time buckets; it is never stored as an individual database row. The call-start
     * timestamp keeps tracked, recorded, and streamed series aligned to the same call hour. The destination kind
     * controls whether the numeric destination is a talkgroup, patch group, radio, or configured conventional
     * routing value; that value never owns the channel or radio-system identity.
     */
    record ConventionalCallOutput(long callStartEpochMilliseconds, String configurationId,
                               Long frequencyHertz, Integer timeslot, int destinationId, String targetKind,
                               List<Integer> patchMemberTalkgroupIds, Integer sourceRadioId, CallOutput output,
                               IdentityDomain identityDomain, P25TargetIdentity p25TargetIdentity,
                               List<P25PatchMemberIdentity> p25PatchMemberIdentities)
        implements ReceiverActivityRecord
    {
        ConventionalCallOutput
        {
            if(configurationId == null || configurationId.isBlank())
            {
                throw new IllegalArgumentException("Call output requires a saved channel configuration ID");
            }
            patchMemberTalkgroupIds = distinctPositiveTalkgroups(patchMemberTalkgroupIds, destinationId);
            identityDomain = identityDomain != null ? identityDomain : IdentityDomain.STANDARD;
            p25TargetIdentity = p25TargetIdentity != null ? p25TargetIdentity : P25TargetIdentity.UNKNOWN;
            p25PatchMemberIdentities = normalizeP25PatchMemberIdentities(p25PatchMemberIdentities,
                patchMemberTalkgroupIds);
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
    record DmrConventionalCall(long callStartEpochMilliseconds, long callEndEpochMilliseconds, String configurationId,
                               long frequencyHertz, int timeslot, DmrTargetKind targetKind, Integer talkgroupId, Integer sourceRadioId,
                               Integer targetRadioId, boolean encrypted)
        implements ReceiverActivityRecord
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
    record NxdnConventionalCall(long callStartEpochMilliseconds, long callEndEpochMilliseconds, String configurationId,
                                long frequencyHertz, NxdnTargetKind targetKind, Integer talkgroupId, Integer sourceRadioId,
                                Integer targetRadioId, boolean encrypted)
        implements ReceiverActivityRecord
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
            if(distinct.size() < MAXIMUM_PATCH_MEMBER_TALKGROUPS && talkgroup != null && talkgroup > 0 &&
                !talkgroup.equals(excludedTalkgroup))
            {
                distinct.add(talkgroup);
            }
        }

        return List.copyOf(distinct);
    }

    private static List<P25PatchMemberIdentity> normalizeP25PatchMemberIdentities(
        List<P25PatchMemberIdentity> identities, List<Integer> patchMemberTalkgroupIds)
    {
        if(identities == null || identities.isEmpty() || patchMemberTalkgroupIds.isEmpty())
        {
            return List.of();
        }

        java.util.Map<Integer,P25TargetIdentity> normalized = new java.util.TreeMap<>();
        for(P25PatchMemberIdentity identity: identities)
        {
            if(identity != null && patchMemberTalkgroupIds.contains(identity.localTalkgroupId()))
            {
                normalized.merge(identity.localTalkgroupId(), identity.targetIdentity(),
                    ReceiverActivityRecords::mergeP25TargetIdentity);
            }
        }

        return normalized.entrySet().stream()
            .map(entry -> new P25PatchMemberIdentity(entry.getKey(), entry.getValue()))
            .toList();
    }

    private static P25TargetIdentity mergeP25TargetIdentity(P25TargetIdentity first, P25TargetIdentity second)
    {
        return first.equals(second) ? first : P25TargetIdentity.AMBIGUOUS;
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

    record SiteSnapshot(long observedAtEpochMilliseconds, String configurationId, ReceiverKind receiverKind,
                        String snapshotHash, String protocol,
                        Integer wacn, Integer systemId, Integer nac, Integer rfss, Integer site,
                        Integer lra, Boolean activeRfssNetworkConnection, Boolean tdma,
                        P25NetworkConfigurationSnapshot.SiteStatus siteStatus,
                        Long primaryFrequencyHertz, Long currentControlHertz,
                        List<P25NetworkConfigurationSnapshot.Channel> channels,
                        List<P25NetworkConfigurationSnapshot.NeighborSite> neighborSites,
                        List<P25NetworkConfigurationSnapshot.FrequencyBand> frequencyBands,
                        List<P25NetworkConfigurationSnapshot.PatchGroup> patchGroups,
                        List<P25NetworkConfigurationSnapshot.ForeignSystemBand> foreignSystemBands)
        implements ReceiverActivityRecord
    {
        SiteSnapshot
        {
            if(observedAtEpochMilliseconds <= 0 || configurationId == null || configurationId.isBlank())
            {
                throw new IllegalArgumentException("Site snapshot requires a saved channel configuration ID");
            }
        }
    }

    record ControlChannelQuality(long observedAtEpochMilliseconds, String configurationId, long frequencyHertz,
                                 Double signalDbfs, Double averageSignalDbfs, Double minimumSignalDbfs,
                                 Double maximumSignalDbfs, Double decodeHealthPercent, long validFrames,
                                 long invalidFrames, long correctedBits, long syncLossBits, long droppedBits,
                                 long lastValidDecodeMs)
        implements ReceiverActivityRecord
    {
        ControlChannelQuality
        {
            if(observedAtEpochMilliseconds <= 0 || configurationId == null || configurationId.isBlank() ||
                frequencyHertz <= 0 ||
                validFrames < 0 || invalidFrames < 0 || correctedBits < 0 || syncLossBits < 0 || droppedBits < 0 ||
                lastValidDecodeMs < 0 || decodeHealthPercent != null &&
                    (!Double.isFinite(decodeHealthPercent) || decodeHealthPercent < 0.0 ||
                        decodeHealthPercent > 100.0))
            {
                throw new IllegalArgumentException("Invalid trunked control-channel quality observation");
            }
        }
    }

    record TrunkedSiteSnapshot(long observedAtEpochMilliseconds, TrunkedSiteSchema.Snapshot snapshot)
        implements ReceiverActivityRecord
    {
    }
}
