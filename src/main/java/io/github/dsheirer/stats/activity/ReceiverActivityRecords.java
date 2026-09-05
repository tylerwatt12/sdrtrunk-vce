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
import io.github.dsheirer.module.decode.traffic.RadioSystemIdentityKey;
import io.github.dsheirer.module.decode.traffic.RadioSystemKey;
import io.github.dsheirer.module.decode.traffic.TrunkedIdentityDomain;
import io.github.dsheirer.protocol.Protocol;
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
     * One physical P25 receiver leg's site-local identity evidence. The system call is counted once, while this
     * bounded provenance record keeps each observed local alias attached to the saved channel that heard it.
     */
    record P25SiteCallObservation(String configurationId, P25SiteIdentity site,
                                  Integer sourceObservedLocalId, Integer targetObservedLocalId,
                                  String targetKind, P25Identity p25TargetIdentity,
                                  P25Identity p25SourceIdentity, List<Integer> patchMemberTalkgroupIds,
                                  List<P25PatchMemberIdentity> p25PatchMemberIdentities)
    {
        P25SiteCallObservation
        {
            if(configurationId == null || configurationId.isBlank() || site == null)
            {
                throw new IllegalArgumentException("P25 site observation requires a saved channel and site");
            }
            p25TargetIdentity = p25TargetIdentity != null ? p25TargetIdentity : P25Identity.UNKNOWN;
            p25SourceIdentity = p25SourceIdentity != null ? p25SourceIdentity : P25Identity.UNKNOWN;
            patchMemberTalkgroupIds = distinctPositiveTalkgroups(patchMemberTalkgroupIds,
                targetObservedLocalId);
            p25PatchMemberIdentities = normalizeP25PatchMemberIdentities(p25PatchMemberIdentities,
                patchMemberTalkgroupIds);
        }
    }

    /**
     * One globally resolved trunked call.  The coordinator supplies this only after all eligible receiver legs have
     * been grouped and one winner has been selected.  The process-local logical call id is used only for bounded
     * output idempotency and is never stored in SQLite.
     */
    record ResolvedLogicalCall(LogicalCallId logicalCallId, long callStartEpochMilliseconds, String configurationId,
                               String protocol, TrunkedIdentityDomain identityDomain, Integer wacn, Integer systemId,
                               int destinationId, String destinationKind,
                               List<Integer> patchMemberTalkgroupIds, Integer sourceRadioId, boolean encrypted,
                               Integer encryptionAlgorithmId, Integer encryptionKeyId,
                               P25Identity p25TargetIdentity,
                               P25Identity p25SourceIdentity,
                               List<P25PatchMemberIdentity> p25PatchMemberIdentities,
                               List<P25SiteCallObservation> p25SiteObservations,
                               String radioSystemKey)
        implements ReceiverActivityRecord
    {
        ResolvedLogicalCall
        {
            if(logicalCallId == null || callStartEpochMilliseconds <= 0 ||
                configurationId == null || configurationId.isBlank())
            {
                throw new IllegalArgumentException("Resolved logical call requires an id and start timestamp");
            }

            identityDomain = identityDomain != null ? identityDomain : TrunkedIdentityDomain.STANDARD;
            patchMemberTalkgroupIds = distinctPositiveTalkgroups(patchMemberTalkgroupIds, destinationId);
            p25TargetIdentity = p25TargetIdentity != null ? p25TargetIdentity : P25Identity.UNKNOWN;
            p25SourceIdentity = p25SourceIdentity != null ? p25SourceIdentity : P25Identity.UNKNOWN;
            p25PatchMemberIdentities = normalizeP25PatchMemberIdentities(p25PatchMemberIdentities,
                patchMemberTalkgroupIds);
            radioSystemKey = validateRadioSystemKey(protocol, identityDomain, configurationId, radioSystemKey);
            p25SiteObservations = p25SiteObservations == null ? List.of() : p25SiteObservations.stream()
                .filter(java.util.Objects::nonNull).distinct().sorted(java.util.Comparator
                    .comparing(P25SiteCallObservation::configurationId)
                    .thenComparingInt(observation -> observation.site().rfss())
                    .thenComparingInt(observation -> observation.site().site()))
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
     * How a P25 destination number was presented over the air.  This remains unknown for non-P25 protocols and for
     * older records that did not carry enough information to distinguish an ordinary talkgroup from an ISSI alias.
     */
    enum P25IdentityState
    {
        UNKNOWN,
        ORDINARY,
        STABLE_FULLY_QUALIFIED
    }

    /**
     * Compact P25 identity evidence for either a group or a radio. A fully-qualified identity keeps its home tuple;
     * its decoded local value remains observation/display evidence and never becomes the durable identity key.
     */
    record P25Identity(P25IdentityState state, int identityKindCode, Integer homeWacn,
                       Integer homeSystemId, Integer homeIdentityId)
    {
        static final P25Identity UNKNOWN =
            new P25Identity(P25IdentityState.UNKNOWN, 0, null, null, null);
        static final P25Identity ORDINARY =
            new P25Identity(P25IdentityState.ORDINARY, 0, null, null, null);

        P25Identity
        {
            state = state != null ? state : P25IdentityState.UNKNOWN;

            if(state == P25IdentityState.STABLE_FULLY_QUALIFIED)
            {
                if(homeWacn == null || homeWacn < 0 || homeWacn > 0xFFFFF ||
                    homeSystemId == null || homeSystemId < 0 || homeSystemId > 0xFFF ||
                    homeIdentityId == null || homeIdentityId <= 0 ||
                    identityKindCode != RadioSystemIdentityKey.KIND_TALKGROUP &&
                        identityKindCode != RadioSystemIdentityKey.KIND_RADIO ||
                    identityKindCode == RadioSystemIdentityKey.KIND_TALKGROUP &&
                        homeIdentityId > RadioSystemIdentityKey.MAX_P25_GROUP_ID ||
                    identityKindCode == RadioSystemIdentityKey.KIND_RADIO &&
                        homeIdentityId > RadioSystemIdentityKey.MAX_P25_RADIO_ID)
                {
                    state = P25IdentityState.UNKNOWN;
                    identityKindCode = 0;
                    homeWacn = null;
                    homeSystemId = null;
                    homeIdentityId = null;
                }
            }
            else
            {
                identityKindCode = 0;
                homeWacn = null;
                homeSystemId = null;
                homeIdentityId = null;
            }
        }

        static P25Identity fullyQualifiedGroup(int homeWacn, int homeSystemId, int homeIdentityId)
        {
            if(homeIdentityId < 1 || homeIdentityId > RadioSystemIdentityKey.MAX_P25_GROUP_ID)
            {
                return UNKNOWN;
            }
            return new P25Identity(P25IdentityState.STABLE_FULLY_QUALIFIED,
                RadioSystemIdentityKey.KIND_TALKGROUP, homeWacn, homeSystemId,
                homeIdentityId);
        }

        static P25Identity fullyQualifiedRadio(int homeWacn, int homeSystemId, int homeIdentityId)
        {
            if(homeIdentityId < 1 || homeIdentityId > RadioSystemIdentityKey.MAX_P25_RADIO_ID)
            {
                return UNKNOWN;
            }
            return new P25Identity(P25IdentityState.STABLE_FULLY_QUALIFIED,
                RadioSystemIdentityKey.KIND_RADIO, homeWacn, homeSystemId,
                homeIdentityId);
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
    record P25PatchMemberIdentity(int localTalkgroupId, P25Identity targetIdentity)
    {
        P25PatchMemberIdentity
        {
            targetIdentity = targetIdentity != null ? targetIdentity : P25Identity.UNKNOWN;
            if(localTalkgroupId < 0 || localTalkgroupId > RadioSystemIdentityKey.MAX_P25_GROUP_ID ||
                !targetIdentity.isStableFullyQualified())
            {
                throw new IllegalArgumentException(
                    "P25 qualified patch-member evidence requires a stable home identity");
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
    record RadioPresenceUpdate(int radioId, Integer talkgroupId, RadioPresenceEvidence evidence, boolean cleared,
                               P25Identity radioIdentity, P25Identity talkgroupIdentity)
    {
        RadioPresenceUpdate
        {
            radioIdentity = radioIdentity != null ? radioIdentity : P25Identity.UNKNOWN;
            talkgroupIdentity = talkgroupIdentity != null ? talkgroupIdentity : P25Identity.UNKNOWN;
            boolean validRadio = radioId > 0 || radioId == 0 && radioIdentity.isStableFullyQualified() &&
                radioIdentity.identityKindCode() == RadioSystemIdentityKey.KIND_RADIO;
            boolean validTalkgroup = talkgroupId == null || talkgroupId > 0 || talkgroupId == 0 &&
                talkgroupIdentity.isStableFullyQualified() &&
                    talkgroupIdentity.identityKindCode() == RadioSystemIdentityKey.KIND_TALKGROUP;
            if(!validRadio || !validTalkgroup ||
                cleared && (talkgroupId != null || evidence != null) || !cleared && evidence == null)
            {
                throw new IllegalArgumentException("Invalid authoritative radio presence update");
            }

        }

        static RadioPresenceUpdate confirmed(int radioId, Integer talkgroupId, RadioPresenceEvidence evidence)
        {
            return new RadioPresenceUpdate(radioId, talkgroupId, evidence, false, P25Identity.ORDINARY,
                talkgroupId != null ? P25Identity.ORDINARY : P25Identity.UNKNOWN);
        }

        static RadioPresenceUpdate cleared(int radioId)
        {
            return new RadioPresenceUpdate(radioId, null, null, true, P25Identity.ORDINARY, P25Identity.UNKNOWN);
        }

        static RadioPresenceUpdate confirmed(int radioId, Integer talkgroupId, RadioPresenceEvidence evidence,
                                              P25Identity radioIdentity, P25Identity talkgroupIdentity)
        {
            return new RadioPresenceUpdate(radioId, talkgroupId, evidence, false, radioIdentity, talkgroupIdentity);
        }

        static RadioPresenceUpdate cleared(int radioId, P25Identity radioIdentity)
        {
            return new RadioPresenceUpdate(radioId, null, null, true, radioIdentity, P25Identity.UNKNOWN);
        }
    }

    record ActivityEvent(long observedAtEpochMilliseconds, String configurationId, ReceiverKind receiverKind,
                         String protocol, Action action, String eventType, String sourceRadioId, String targetId,
                         String targetKind, List<Integer> patchMemberTalkgroupIds, Long frequencyHertz, String lcn,
                         Integer timeslot, boolean encrypted, Integer encryptionAlgorithmId, Integer encryptionKeyId,
                         Integer wacn, Integer systemId, Integer nac, Integer rfss, Integer site,
                         String talkerAlias, boolean countedCall, String dedupeKey,
                         RadioPresenceUpdate radioPresenceUpdate, TrunkedIdentityDomain identityDomain,
                         P25Identity p25TargetIdentity,
                         P25Identity p25SourceIdentity,
                         List<P25PatchMemberIdentity> p25PatchMemberIdentities,
                         String radioSystemKey)
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
            identityDomain = identityDomain != null ? identityDomain : TrunkedIdentityDomain.STANDARD;
            p25TargetIdentity = p25TargetIdentity != null ? p25TargetIdentity : P25Identity.UNKNOWN;
            p25SourceIdentity = p25SourceIdentity != null ? p25SourceIdentity : P25Identity.UNKNOWN;
            p25PatchMemberIdentities = normalizeP25PatchMemberIdentities(p25PatchMemberIdentities,
                patchMemberTalkgroupIds);
            radioSystemKey = validateRadioSystemKey(protocol, identityDomain, configurationId, radioSystemKey);
        }

    }

    /**
     * One-time identity/encryption enrichment for an already-counted trunked call.
     */
    record TrunkedCallAttribution(long callStartEpochMilliseconds, String configurationId, String protocol,
                                  Long frequencyHertz, Integer timeslot,
                                  int destinationId, String destinationKind,
                                  List<Integer> patchMemberTalkgroupIds, Integer sourceRadioId,
                                  Integer encryptionAlgorithmId, Integer encryptionKeyId,
                                  boolean destinationBecameKnown, boolean sourceBecameKnown,
                                  boolean encryptionBecameKnown, boolean encryptedBeforeObservation,
                                  TrunkedIdentityDomain identityDomain, String radioSystemKey)
        implements ReceiverActivityRecord
    {
        TrunkedCallAttribution
        {
            if(configurationId == null || configurationId.isBlank())
            {
                throw new IllegalArgumentException("Call attribution requires a saved channel configuration ID");
            }
            patchMemberTalkgroupIds = distinctPositiveTalkgroups(patchMemberTalkgroupIds, destinationId);
            identityDomain = identityDomain != null ? identityDomain : TrunkedIdentityDomain.STANDARD;
            radioSystemKey = validateRadioSystemKey(protocol, identityDomain, configurationId, radioSystemKey);
        }

        boolean hasEncryptionDetails()
        {
            return encryptionAlgorithmId != null || encryptionKeyId != null;
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
    record TalkerAliasUpdate(long observedAtEpochMilliseconds, long callStartEpochMilliseconds,
                             String configurationId, String protocol, Integer wacn,
                             Integer systemId, int radioId, P25Identity p25RadioIdentity, String talkerAlias,
                             TrunkedIdentityDomain identityDomain, String radioSystemKey)
        implements ReceiverActivityRecord
    {
        TalkerAliasUpdate
        {
            if(observedAtEpochMilliseconds <= 0 || callStartEpochMilliseconds <= 0)
            {
                throw new IllegalArgumentException("Talker alias requires positive observation and call timestamps");
            }

            identityDomain = identityDomain != null ? identityDomain : TrunkedIdentityDomain.STANDARD;
            p25RadioIdentity = p25RadioIdentity != null ? p25RadioIdentity : P25Identity.UNKNOWN;
            radioSystemKey = radioSystemKey != null ?
                io.github.dsheirer.module.decode.traffic.RadioSystemKey.parse(radioSystemKey) : null;
        }

        TalkerAliasUpdate(long observedAtEpochMilliseconds, String configurationId, String protocol, Integer wacn,
                          Integer systemId, int radioId, String talkerAlias)
        {
            this(observedAtEpochMilliseconds, observedAtEpochMilliseconds, configurationId, protocol, wacn,
                systemId, radioId, P25Identity.UNKNOWN, talkerAlias,
                TrunkedIdentityDomain.STANDARD, null);
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
                               ReceiverKind receiverKind, String protocol,
                               Long frequencyHertz, Integer timeslot, int destinationId, String targetKind,
                               List<Integer> patchMemberTalkgroupIds, Integer sourceRadioId, CallOutput output,
                               TrunkedIdentityDomain identityDomain, P25Identity p25TargetIdentity,
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
            identityDomain = identityDomain != null ? identityDomain : TrunkedIdentityDomain.STANDARD;
            p25TargetIdentity = p25TargetIdentity != null ? p25TargetIdentity : P25Identity.UNKNOWN;
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
                                Integer targetRadioId, boolean encrypted, TrunkedIdentityDomain identityDomain)
        implements ReceiverActivityRecord
    {
        NxdnConventionalCall
        {
            identityDomain = identityDomain != null ? identityDomain : TrunkedIdentityDomain.NXDN_TYPE_C;
        }

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
        if(identities == null || identities.isEmpty())
        {
            return List.of();
        }

        //The flattened local list intentionally omits zero. A qualified patch member with local alias zero still
        //has a complete canonical home identity and must survive independently of that display-only list.
        return identities.stream().filter(java.util.Objects::nonNull)
            .filter(identity -> patchMemberTalkgroupIds.contains(identity.localTalkgroupId()) ||
                identity.localTalkgroupId() == 0 && identity.targetIdentity().isStableFullyQualified())
            .distinct()
            .sorted(java.util.Comparator.comparingInt(P25PatchMemberIdentity::localTalkgroupId)
                .thenComparingInt(identity -> identity.targetIdentity().homeWacn() != null ?
                    identity.targetIdentity().homeWacn() : -1)
                .thenComparingInt(identity -> identity.targetIdentity().homeSystemId() != null ?
                    identity.targetIdentity().homeSystemId() : -1)
                .thenComparingInt(identity -> identity.targetIdentity().homeIdentityId() != null ?
                    identity.targetIdentity().homeIdentityId() : -1))
            .limit(MAXIMUM_PATCH_MEMBER_TALKGROUPS)
            .toList();
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

    private static String validateRadioSystemKey(String protocol, TrunkedIdentityDomain identityDomain,
                                                 String configurationId, String radioSystemKey)
    {
        if(radioSystemKey == null)
        {
            return null;
        }

        Protocol protocolType = switch(protocol != null ? protocol.strip().toUpperCase(java.util.Locale.ROOT) : "")
        {
            case "DMR" -> Protocol.DMR;
            case "NXDN" -> Protocol.NXDN;
            case "APCO25", "APCO-25", "APCO25PHASE1" -> Protocol.APCO25;
            case "APCO25_PHASE2", "APCO-25 P2", "APCO25PHASE2" -> Protocol.APCO25_PHASE2;
            default -> Protocol.UNKNOWN;
        };
        return RadioSystemKey.validateForReceiver(protocolType, identityDomain, configurationId, radioSystemKey);
    }

    record SiteSnapshot(long observedAtEpochMilliseconds, String configurationId, ReceiverKind receiverKind,
                        String snapshotHash, String protocol,
                        Integer wacn, Integer systemId, Integer nac, Integer rfss, Integer site,
                        Integer lra, Boolean activeRfssNetworkConnection, Boolean tdma,
                        P25NetworkConfigurationSnapshot.SiteStatus siteStatus,
                        long sourceFrequencyHertz, Long primaryFrequencyHertz, Long currentControlHertz,
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

    record ControlChannelQuality(long observedAtEpochMilliseconds, String configurationId,
                                 String protocol, TrunkedIdentityDomain identityDomain, long frequencyHertz,
                                 Double signalDbfs, Double averageSignalDbfs, Double minimumSignalDbfs,
                                 Double maximumSignalDbfs, Double decodeHealthPercent, long validFrames,
                                 long invalidFrames, long correctedBits, long syncLossBits, long droppedBits,
                                 long lastValidDecodeMs)
        implements ReceiverActivityRecord
    {
        ControlChannelQuality
        {
            identityDomain = identityDomain != null ? identityDomain : TrunkedIdentityDomain.STANDARD;
            if(observedAtEpochMilliseconds <= 0 || configurationId == null || configurationId.isBlank() ||
                frequencyHertz <= 0 ||
                validFrames < 0 || invalidFrames < 0 || correctedBits < 0 || syncLossBits < 0 || droppedBits < 0 ||
                lastValidDecodeMs < 0 || decodeHealthPercent != null &&
                    (!Double.isFinite(decodeHealthPercent) || decodeHealthPercent < 0.0 ||
                        decodeHealthPercent > 100.0))
            {
                throw new IllegalArgumentException("Invalid trunked control-channel quality observation");
            }
            // Decoder timestamps follow sample time and can lead the wall-clock snapshot slightly.
            // A decode already included in this observation is no newer than the observation itself.
            lastValidDecodeMs = Math.min(lastValidDecodeMs, observedAtEpochMilliseconds);
        }
    }

    record TrunkedSiteSnapshot(long observedAtEpochMilliseconds, TrunkedSiteSchema.Snapshot snapshot)
        implements ReceiverActivityRecord
    {
    }
}
