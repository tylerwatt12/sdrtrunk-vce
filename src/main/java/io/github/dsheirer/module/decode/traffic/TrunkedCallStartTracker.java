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
package io.github.dsheirer.module.decode.traffic;

import io.github.dsheirer.channel.IChannelDescriptor;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.identifier.encryption.EncryptionKeyIdentifier;
import io.github.dsheirer.identifier.patch.PatchGroupIdentifier;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.nxdn.DecodeConfigNXDN;
import io.github.dsheirer.protocol.Protocol;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Tracks the current logical call on each trunked RF resource and creates exactly one immutable call-start
 * notification. Repeated grants and source/talker changes update the current observation without creating another
 * call. A destination change, explicit end, or gap beyond the configured continuation threshold starts a new call.
 */
public class TrunkedCallStartTracker
{
    private static final int MAXIMUM_ACTIVE_OBSERVATIONS = 2048;
    private static final long MINIMUM_ENTRY_RETENTION_MILLISECONDS = 60000;

    private final long mContinuationThresholdMilliseconds;
    private final long mEntryRetentionMilliseconds;
    private final Map<ResourceKey,ActiveCall> mObservations = new LinkedHashMap<>(32, 0.75f, true);
    private final Map<ResourceKey,Long> mEndedObservations = new LinkedHashMap<>(32, 0.75f, true);

    /**
     * @param continuationThresholdMilliseconds maximum gap between observations belonging to the same call
     */
    public TrunkedCallStartTracker(long continuationThresholdMilliseconds)
    {
        if(continuationThresholdMilliseconds < 1)
        {
            throw new IllegalArgumentException("Continuation threshold must be positive");
        }

        mContinuationThresholdMilliseconds = continuationThresholdMilliseconds;
        mEntryRetentionMilliseconds = Math.max(MINIMUM_ENTRY_RETENTION_MILLISECONDS,
            continuationThresholdMilliseconds * 2);
    }

    /**
     * Observes trunked call signalling.
     *
     * @return one call-start notification for a new voice call, or null for a continuation/non-voice observation
     */
    public synchronized TrunkedCallStartEvent observe(Channel parentChannel, Protocol protocol,
                                                      IChannelDescriptor channelDescriptor, Integer timeslot,
                                                      IdentifierCollection identifiers, DecodeEventType eventType,
                                                      long timestamp)
    {
        return observeWithAttribution(parentChannel, protocol, channelDescriptor, timeslot, identifiers, eventType,
            timestamp).callStart();
    }

    /**
     * Observes trunked call signalling and returns either a new physical call or a one-time late attribution for the
     * active call. A result never contains both.
     */
    public synchronized ObservationResult observeWithAttribution(Channel parentChannel, Protocol protocol,
                                                                  IChannelDescriptor channelDescriptor,
                                                                  Integer timeslot,
                                                                  IdentifierCollection identifiers,
                                                                  DecodeEventType eventType,
                                                                  long timestamp)
    {
        return observeWithAttribution(parentChannel, protocol, channelDescriptor, timeslot, identifiers, eventType,
            timestamp, null);
    }

    /**
     * Observes one call using the processing chain's effective system identity at the instant the physical call
     * starts. The key may identify a native system or the saved-channel fallback used before native identity is known.
     */
    public synchronized ObservationResult observeWithAttribution(Channel parentChannel, Protocol protocol,
                                                                  IChannelDescriptor channelDescriptor,
                                                                  Integer timeslot,
                                                                  IdentifierCollection identifiers,
                                                                  DecodeEventType eventType,
                                                                  long timestamp,
                                                                  String radioSystemKey)
    {
        return observe(parentChannel, protocol, channelDescriptor, timeslot, identifiers, eventType, timestamp,
            true, radioSystemKey);
    }

    /**
     * Enriches an already-tracked call from traffic-channel signalling. This method cannot start a call, so a late
     * traffic decode by itself can never create or double-count a physical call.
     */
    public synchronized ObservationResult enrichActiveCall(Channel parentChannel, Protocol protocol,
                                                            IChannelDescriptor channelDescriptor,
                                                            Integer timeslot,
                                                            IdentifierCollection identifiers,
                                                            DecodeEventType eventType,
                                                            long timestamp)
    {
        return enrichActiveCall(parentChannel, protocol, channelDescriptor, timeslot, identifiers, eventType,
            timestamp, null);
    }

    public synchronized ObservationResult enrichActiveCall(Channel parentChannel, Protocol protocol,
                                                            IChannelDescriptor channelDescriptor,
                                                            Integer timeslot,
                                                            IdentifierCollection identifiers,
                                                            DecodeEventType eventType,
                                                            long timestamp,
                                                            String radioSystemKey)
    {
        return observe(parentChannel, protocol, channelDescriptor, timeslot, identifiers, eventType, timestamp,
            false, radioSystemKey);
    }

    private ObservationResult observe(Channel parentChannel, Protocol protocol,
                                      IChannelDescriptor channelDescriptor, Integer timeslot,
                                      IdentifierCollection identifiers, DecodeEventType eventType,
                                      long timestamp, boolean allowCallStart, String radioSystemKey)
    {
        if(parentChannel == null || protocol != Protocol.DMR && protocol != Protocol.NXDN || eventType == null ||
            !eventType.isVoiceCallEvent() || timestamp <= 0)
        {
            return ObservationResult.EMPTY;
        }

        cleanup(timestamp);
        TrunkedChannelDescriptorSnapshot channelSnapshot =
            TrunkedChannelDescriptorSnapshot.capture(channelDescriptor);
        Integer effectiveTimeslot = timeslot != null ? timeslot :
            channelSnapshot != null ? channelSnapshot.timeslot() : null;
        ResourceKey resourceKey = ResourceKey.from(channelSnapshot, effectiveTimeslot);
        if(resourceKey == null)
        {
            return ObservationResult.EMPTY;
        }
        Long endedAt = mEndedObservations.get(resourceKey);

        //Control and traffic decoder streams can deliver an older observation after an explicit call end.
        if(endedAt != null && (timestamp <= endedAt || !allowCallStart))
        {
            return ObservationResult.EMPTY;
        }

        if(endedAt != null)
        {
            mEndedObservations.remove(resourceKey);
        }

        ActiveCall previous = mObservations.get(resourceKey);

        if(previous == null && !allowCallStart)
        {
            return ObservationResult.EMPTY;
        }

        //A delayed observation must not rewind the current call or create a false target-change call.
        if(previous != null && timestamp < previous.lastObservedAtMilliseconds())
        {
            return ObservationResult.EMPTY;
        }

        TrunkedIdentityDomain identityDomain = identityDomain(parentChannel, protocol);
        if(protocol == Protocol.NXDN &&
            !TrunkedIdentityEligibility.nxdnIdentifiersMatchDomain(identifiers, identityDomain))
        {
            return ObservationResult.EMPTY;
        }
        TargetIdentity target = TargetIdentity.fromTarget(protocol, identityDomain,
            identifiers != null ? identifiers.getToIdentifier() : null);
        TargetIdentity source = TargetIdentity.fromSource(protocol, identityDomain,
            identifiers != null ? identifiers.getFromIdentifier() : null);
        EncryptionDetails observedEncryption = EncryptionDetails.from(identifiers);
        boolean encrypted = DecodeEventType.VOICE_CALLS_ENCRYPTED.contains(eventType) ||
            observedEncryption.isKnown();
        boolean targetChanged = previous != null && previous.target().isKnown() && target.isKnown() &&
            !previous.target().sameIdentity(target);
        boolean newCall = previous == null || targetChanged ||
            timestamp - previous.lastObservedAtMilliseconds() > mContinuationThresholdMilliseconds;

        if(newCall)
        {
            if(!allowCallStart)
            {
                return ObservationResult.EMPTY;
            }

            String effectiveRadioSystemKey = effectiveRadioSystemKey(parentChannel, protocol, identityDomain,
                radioSystemKey);
            mObservations.put(resourceKey, new ActiveCall(target, source, encrypted,
                observedEncryption.algorithmId(), observedEncryption.keyId(), timestamp, timestamp,
                effectiveRadioSystemKey));
            enforceMaximumSize(mObservations);
            return new ObservationResult(new TrunkedCallStartEvent(parentChannel, protocol, channelSnapshot,
                effectiveTimeslot, identifiers, eventType, timestamp, effectiveRadioSystemKey), null);
        }

        boolean destinationBecameKnown = !previous.target().isKnown() && target.isKnown();
        boolean sourceBecameKnown = !previous.source().isKnown() && source.isKnown();
        boolean encryptionBecameKnown = !previous.encrypted() && encrypted;
        Integer updatedEncryptionAlgorithm = firstKnown(previous.encryptionAlgorithmId(),
            observedEncryption.algorithmId());
        Integer updatedEncryptionKey = firstKnown(previous.encryptionKeyId(), observedEncryption.keyId());
        boolean encryptionDetailsBecameKnown =
            !Objects.equals(previous.encryptionAlgorithmId(), updatedEncryptionAlgorithm) ||
                !Objects.equals(previous.encryptionKeyId(), updatedEncryptionKey);
        TargetIdentity updatedTarget = target.isKnown() ? target : previous.target();
        //The first known source owns the call attribution. Later talker changes enrich live display state but do not
        //create additional counted source identities.
        TargetIdentity updatedSource = previous.source().isKnown() ? previous.source() : source;
        IdentifierCollection mergedIdentifiers = mergedIdentifiers(identifiers, updatedTarget, updatedSource);
        mObservations.put(resourceKey, new ActiveCall(updatedTarget, updatedSource,
            previous.encrypted() || encrypted, updatedEncryptionAlgorithm, updatedEncryptionKey,
            previous.callStartEpochMilliseconds(), timestamp, previous.radioSystemKey()));
        enforceMaximumSize(mObservations);

        if(!destinationBecameKnown && !sourceBecameKnown && !encryptionBecameKnown &&
            !encryptionDetailsBecameKnown)
        {
            return ObservationResult.EMPTY;
        }

        return new ObservationResult(null, new TrunkedCallAttributionEvent(parentChannel, protocol,
            channelSnapshot, effectiveTimeslot, previous.callStartEpochMilliseconds(), mergedIdentifiers,
            destinationBecameKnown, sourceBecameKnown, encryptionBecameKnown,
            updatedEncryptionAlgorithm, updatedEncryptionKey, previous.encrypted(),
            previous.radioSystemKey()));
    }

    private static IdentifierCollection mergedIdentifiers(IdentifierCollection identifiers,
                                                          TargetIdentity target, TargetIdentity source)
    {
        MutableIdentifierCollection merged = identifiers != null ?
            new MutableIdentifierCollection(identifiers.getIdentifiers()) : new MutableIdentifierCollection();

        if(target.identifier() != null)
        {
            merged.update(target.identifier());
        }

        if(source.identifier() != null)
        {
            merged.update(source.identifier());
        }

        return new IdentifierCollection(merged.getIdentifiers());
    }

    /**
     * Explicitly ends the call on an RF resource so that an immediate subsequent call to the same target counts.
     */
    public synchronized void end(IChannelDescriptor channelDescriptor, Integer timeslot, long timestamp)
    {
        ResourceKey key = ResourceKey.from(TrunkedChannelDescriptorSnapshot.capture(channelDescriptor), timeslot);
        if(key == null)
        {
            return;
        }
        ActiveCall current = mObservations.get(key);

        //A delayed end must not terminate a newer call already active on the same resource.
        if(timestamp <= 0 || current != null && timestamp < current.lastObservedAtMilliseconds())
        {
            return;
        }

        mObservations.remove(key);
        mEndedObservations.merge(key, timestamp, Math::max);
        enforceMaximumSize(mEndedObservations);
    }

    /**
     * Clears all timeslots associated with a known frequency.
     */
    public synchronized void endFrequency(long frequencyHertz, long timestamp)
    {
        if(frequencyHertz > 0 && timestamp > 0)
        {
            Iterator<Map.Entry<ResourceKey,ActiveCall>> iterator = mObservations.entrySet().iterator();

            while(iterator.hasNext())
            {
                Map.Entry<ResourceKey,ActiveCall> entry = iterator.next();

                if(entry.getKey().frequencyHertz() == frequencyHertz &&
                    timestamp >= entry.getValue().lastObservedAtMilliseconds())
                {
                    mEndedObservations.merge(entry.getKey(), timestamp, Math::max);
                    iterator.remove();
                }
            }

            enforceMaximumSize(mEndedObservations);
        }
    }

    /**
     * Advances an existing call from traffic-channel progress without creating a call when no start is tracked.
     */
    public synchronized void touch(IChannelDescriptor channelDescriptor, Integer timeslot, long timestamp)
    {
        if(timestamp <= 0)
        {
            return;
        }

        cleanup(timestamp);
        ResourceKey key = ResourceKey.from(TrunkedChannelDescriptorSnapshot.capture(channelDescriptor), timeslot);
        if(key == null)
        {
            return;
        }
        ActiveCall current = mObservations.get(key);

        if(current != null && timestamp >= current.lastObservedAtMilliseconds())
        {
            mObservations.put(key, new ActiveCall(current.target(), current.source(), current.encrypted(),
                current.encryptionAlgorithmId(), current.encryptionKeyId(),
                current.callStartEpochMilliseconds(), timestamp, current.radioSystemKey()));
        }
    }

    /**
     * Returns the immutable system key and start time captured when the matching physical call started. A completed
     * talker-alias message may arrive after the manager has learned a different current system, so both values must
     * come from the call rather than the manager's latest state. Ambiguous, stale, or next-call observations are
     * rejected.
     */
    public synchronized CallSystemIdentity identityForActiveCall(Channel parentChannel, Protocol protocol,
                                                                 IChannelDescriptor channelDescriptor,
                                                                 Integer timeslot,
                                                                 IdentifierCollection identifiers,
                                                                 long timestamp)
    {
        if(parentChannel == null || protocol != Protocol.DMR && protocol != Protocol.NXDN || timestamp <= 0)
        {
            return null;
        }

        cleanup(timestamp);
        ResourceKey resourceKey = ResourceKey.from(
            TrunkedChannelDescriptorSnapshot.capture(channelDescriptor), timeslot);
        if(resourceKey == null)
        {
            return null;
        }
        ActiveCall active = mObservations.get(resourceKey);
        boolean stale = active != null && timestamp >= active.lastObservedAtMilliseconds() &&
            timestamp - active.lastObservedAtMilliseconds() > mContinuationThresholdMilliseconds;
        if(active == null || timestamp < active.callStartEpochMilliseconds() || stale)
        {
            return null;
        }

        TrunkedIdentityDomain identityDomain = identityDomain(parentChannel, protocol);
        TargetIdentity observedTarget = TargetIdentity.fromTarget(protocol, identityDomain,
            identifiers != null ? identifiers.getToIdentifier() : null);
        TargetIdentity observedSource = TargetIdentity.fromSource(protocol, identityDomain,
            identifiers != null ? identifiers.getFromIdentifier() : null);

        boolean targetConflict = active.target().isKnown() && observedTarget.isKnown() &&
            !active.target().sameIdentity(observedTarget);
        boolean sourceConflict = active.source().isKnown() && observedSource.isKnown() &&
            !active.source().sameIdentity(observedSource);
        if(targetConflict || sourceConflict)
        {
            return null;
        }

        return new CallSystemIdentity(active.callStartEpochMilliseconds(), active.radioSystemKey());
    }

    /**
     * Clears all current call state.
     */
    public synchronized void clear()
    {
        mObservations.clear();
        mEndedObservations.clear();
    }

    private void cleanup(long timestamp)
    {
        Iterator<Map.Entry<ResourceKey,ActiveCall>> iterator = mObservations.entrySet().iterator();

        while(iterator.hasNext())
        {
            ActiveCall observation = iterator.next().getValue();

            if(timestamp >= observation.lastObservedAtMilliseconds() &&
                timestamp - observation.lastObservedAtMilliseconds() > mEntryRetentionMilliseconds)
            {
                iterator.remove();
            }
        }

        Iterator<Map.Entry<ResourceKey,Long>> endedIterator = mEndedObservations.entrySet().iterator();

        while(endedIterator.hasNext())
        {
            long endedAt = endedIterator.next().getValue();

            if(timestamp >= endedAt && timestamp - endedAt > mEntryRetentionMilliseconds)
            {
                endedIterator.remove();
            }
        }
    }

    private static <V> void enforceMaximumSize(Map<ResourceKey,V> observations)
    {
        Iterator<ResourceKey> iterator = observations.keySet().iterator();

        while(observations.size() > MAXIMUM_ACTIVE_OBSERVATIONS && iterator.hasNext())
        {
            iterator.next();
            iterator.remove();
        }
    }

    private static Integer firstKnown(Integer existing, Integer observed)
    {
        return existing != null ? existing : observed;
    }

    private static TrunkedIdentityDomain identityDomain(Channel parentChannel, Protocol protocol)
    {
        if(protocol != Protocol.NXDN)
        {
            return TrunkedIdentityDomain.STANDARD;
        }

        if(parentChannel != null && parentChannel.getDecodeConfiguration() instanceof DecodeConfigNXDN config &&
            config.getTransmissionMode() != null && config.getTransmissionMode().isTypeD())
        {
            return TrunkedIdentityDomain.NXDN_TYPE_D;
        }

        return TrunkedIdentityDomain.NXDN_TYPE_C;
    }

    private static String effectiveRadioSystemKey(Channel parentChannel, Protocol protocol,
                                                  TrunkedIdentityDomain identityDomain,
                                                  String radioSystemKey)
    {
        String configurationId = parentChannel != null ?
            io.github.dsheirer.controller.channel.ChannelConfigurationKey.configured(parentChannel) : null;
        return RadioSystemKey.effectiveForReceiver(protocol, identityDomain, configurationId,
            radioSystemKey);
    }

    public record ObservationResult(TrunkedCallStartEvent callStart, TrunkedCallAttributionEvent attribution)
    {
        private static final ObservationResult EMPTY = new ObservationResult(null, null);
    }

    private record ActiveCall(TargetIdentity target, TargetIdentity source, boolean encrypted,
                              Integer encryptionAlgorithmId, Integer encryptionKeyId,
                              long callStartEpochMilliseconds, long lastObservedAtMilliseconds,
                              String radioSystemKey)
    {
    }

    private record EncryptionDetails(Integer algorithmId, Integer keyId)
    {
        private static final EncryptionDetails UNKNOWN = new EncryptionDetails(null, null);

        private boolean isKnown()
        {
            return algorithmId != null || keyId != null;
        }

        private static EncryptionDetails from(IdentifierCollection identifiers)
        {
            Identifier identifier = identifiers != null ? identifiers.getEncryptionIdentifier() : null;

            if(identifier instanceof EncryptionKeyIdentifier encryption && encryption.isEncrypted() &&
                encryption.getValue() != null)
            {
                return new EncryptionDetails(encryption.getValue().getAlgorithm(),
                    encryption.getValue().getKey());
            }

            return UNKNOWN;
        }
    }

    private record TargetIdentity(Form form, Integer observedLocalId, Identifier identifier)
    {
        private boolean isKnown()
        {
            return form != null && observedLocalId != null;
        }

        private boolean sameIdentity(TargetIdentity other)
        {
            if(other == null || form != other.form())
            {
                return false;
            }

            return observedLocalId != null && observedLocalId > 0 &&
                observedLocalId.equals(other.observedLocalId());
        }

        private static TargetIdentity fromTarget(Protocol protocol, TrunkedIdentityDomain identityDomain,
                                                 Identifier identifier)
        {
            return from(protocol, identityDomain, identifier, true);
        }

        private static TargetIdentity fromSource(Protocol protocol, TrunkedIdentityDomain identityDomain,
                                                 Identifier identifier)
        {
            return from(protocol, identityDomain, identifier, false);
        }

        private static Integer integerValue(Identifier identifier)
        {
            if(identifier instanceof PatchGroupIdentifier patch && patch.getValue() != null &&
                patch.getValue().getPatchGroup() != null)
            {
                return patch.getValue().getPatchGroup().getValue();
            }

            return identifier != null && identifier.getValue() instanceof Number number ?
                number.intValue() : null;
        }

        private static TargetIdentity from(Protocol protocol, TrunkedIdentityDomain identityDomain,
                                           Identifier identifier, boolean destination)
        {
            if(identifier == null || destination && identifier.getForm() != Form.TALKGROUP &&
                identifier.getForm() != Form.PATCH_GROUP && identifier.getForm() != Form.RADIO ||
                !destination && identifier.getForm() != Form.RADIO)
            {
                return unknown();
            }

            Form form = identifier.getForm();
            return TrunkedIdentityEligibility.isEligible(protocol, identityDomain, form, integerValue(identifier)) ?
                new TargetIdentity(form, integerValue(identifier), identifier) : unknown();
        }

        private static TargetIdentity unknown()
        {
            return new TargetIdentity(null, null, null);
        }
    }

    private record ResourceKey(ResourceKind kind, long frequencyHertz,
                               int primaryChannelNumber, int secondaryChannelNumber, int timeslot)
    {
        private static ResourceKey from(TrunkedChannelDescriptorSnapshot snapshot, Integer timeslot)
        {
            if(snapshot == null)
            {
                return null;
            }

            int slot = timeslot != null ? timeslot :
                snapshot.timeslot() != null ? snapshot.timeslot() : -1;
            int primary = snapshot.primaryChannelNumber() != null ? snapshot.primaryChannelNumber() : -1;
            int secondary = snapshot.secondaryChannelNumber() != null ? snapshot.secondaryChannelNumber() : -1;

            //Logical channel identities remain stable when a frequency map becomes available.  Keep the key family
            //deliberately broader than the display snapshot: control and traffic observations can use different DMR
            //descriptor subclasses for the same physical resource.
            switch(snapshot.kind())
            {
                case DMR_CHANNEL, DMR_TIER_III, DMR_ABSOLUTE:
                    if(primary >= 0)
                    {
                        return new ResourceKey(ResourceKind.DMR_CHANNEL, 0L, primary, -1, slot);
                    }
                    break;
                case DMR_LSN, DMR_REST_LSN:
                    if(secondary >= 0)
                    {
                        return new ResourceKey(ResourceKind.DMR_CHANNEL, 0L, secondary, -1, slot);
                    }
                    break;
                case NXDN_LOOKUP:
                    if(primary >= 0)
                    {
                        return new ResourceKey(ResourceKind.NXDN_LOOKUP, 0L, primary, -1, slot);
                    }
                    break;
                case NXDN_DFA:
                    if(primary >= 0)
                    {
                        return new ResourceKey(ResourceKind.NXDN_DFA, 0L, primary, secondary, slot);
                    }
                    break;
                case NXDN_CHANNEL, UNKNOWN:
                    if(snapshot.downlinkFrequencyHertz() != null)
                    {
                        return new ResourceKey(ResourceKind.FREQUENCY, snapshot.downlinkFrequencyHertz(),
                            -1, -1, slot);
                    }
                    break;
                case NXDN_FAKE:
                    break;
            }

            //An unknown zero-frequency descriptor has no collision-free identity.  Refuse it instead of using
            //mutable display text as a call key.
            return null;
        }
    }

    private enum ResourceKind
    {
        DMR_CHANNEL,
        NXDN_LOOKUP,
        NXDN_DFA,
        FREQUENCY
    }
}
