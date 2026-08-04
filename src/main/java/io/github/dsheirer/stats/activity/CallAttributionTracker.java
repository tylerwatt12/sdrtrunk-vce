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

import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded runtime tracker that attributes identity/encryption facts learned after a P25 call-start snapshot.
 */
class CallAttributionTracker
{
    private static final int MAXIMUM_ACTIVE_CALLS = 2048;
    private static final long RETENTION_MILLISECONDS = 60000;
    private final Map<ResourceKey,ActiveCall> mActiveCalls = new LinkedHashMap<>(32, 0.75f, true);

    synchronized void register(P25ActivityLogRecords.ActivityEvent callStart)
    {
        if(!isTrunkedVoiceObservation(callStart) ||
            callStart.action() != P25ActivityLogRecords.Action.CALL || !callStart.countedCall())
        {
            return;
        }

        cleanup(callStart.observedAtEpochMilliseconds());
        boolean encrypted = callStart.encrypted() || callStart.encryptionAlgorithmId() != null ||
            callStart.encryptionKeyId() != null;
        mActiveCalls.put(ResourceKey.from(callStart), new ActiveCall(
            Destination.from(callStart), eligibleSource(callStart), encrypted,
            callStart.encryptionAlgorithmId(), callStart.encryptionKeyId(),
            callStart.observedAtEpochMilliseconds(),
            callStart.observedAtEpochMilliseconds(), callStart.identityDomain()));
        trim();
    }

    synchronized AttributionResult enrich(
        P25ActivityLogRecords.ActivityEvent continuation)
    {
        if(!isTrunkedVoiceObservation(continuation) || continuation.countedCall())
        {
            return AttributionResult.NOT_TRACKED;
        }

        cleanup(continuation.observedAtEpochMilliseconds());
        ResourceKey key = ResourceKey.from(continuation);
        ActiveCall previous = mActiveCalls.get(key);

        if(previous == null || continuation.observedAtEpochMilliseconds() < previous.lastObservedAt())
        {
            return previous != null ? AttributionResult.TRACKED_WITHOUT_CHANGE : AttributionResult.NOT_TRACKED;
        }

        Destination observedDestination = Destination.from(continuation);
        Integer observedSource = eligibleSource(continuation);

        //A known target change belongs to the next physical call-start notification.
        if(previous.destination().isKnown() && observedDestination.isKnown() &&
            !previous.destination().sameIdentity(observedDestination))
        {
            mActiveCalls.remove(key);
            return AttributionResult.TRACKED_WITHOUT_CHANGE;
        }

        boolean destinationBecameKnown = !previous.destination().isKnown() && observedDestination.isKnown();
        boolean sourceBecameKnown = previous.sourceRadioId() == null && observedSource != null;
        boolean observedEncrypted = continuation.encrypted() || continuation.encryptionAlgorithmId() != null ||
            continuation.encryptionKeyId() != null;
        boolean encryptionBecameKnown = !previous.encrypted() && observedEncrypted;
        Integer updatedEncryptionAlgorithm = firstKnown(previous.encryptionAlgorithmId(),
            continuation.encryptionAlgorithmId());
        Integer updatedEncryptionKey = firstKnown(previous.encryptionKeyId(), continuation.encryptionKeyId());
        boolean encryptionDetailsBecameKnown =
            !Objects.equals(previous.encryptionAlgorithmId(), updatedEncryptionAlgorithm) ||
                !Objects.equals(previous.encryptionKeyId(), updatedEncryptionKey);
        Destination updatedDestination = observedDestination.isKnown() ?
            observedDestination : previous.destination();
        Integer updatedSource = previous.sourceRadioId() != null ? previous.sourceRadioId() : observedSource;
        P25ActivityLogRecords.IdentityDomain updatedDomain =
            previous.identityDomain() != P25ActivityLogRecords.IdentityDomain.STANDARD ?
                previous.identityDomain() : continuation.identityDomain();
        mActiveCalls.put(key, new ActiveCall(updatedDestination, updatedSource,
            previous.encrypted() || observedEncrypted, updatedEncryptionAlgorithm, updatedEncryptionKey,
            previous.callStart(),
            continuation.observedAtEpochMilliseconds(), updatedDomain));
        trim();

        if(!destinationBecameKnown && !sourceBecameKnown && !encryptionBecameKnown &&
            !encryptionDetailsBecameKnown)
        {
            return AttributionResult.TRACKED_WITHOUT_CHANGE;
        }

        return new AttributionResult(true, new P25ActivityLogRecords.TrunkedCallAttribution(previous.callStart(),
            continuation.contextKey(), continuation.guid(), continuation.frequencyHertz(), continuation.timeslot(),
            updatedDestination.identityId(),
            updatedDestination.kind(), updatedDestination.patchMembers(), updatedSource,
            updatedEncryptionAlgorithm, updatedEncryptionKey,
            destinationBecameKnown, sourceBecameKnown, encryptionBecameKnown, previous.encrypted(),
            updatedDomain));
    }

    synchronized void clear()
    {
        mActiveCalls.clear();
    }

    private void cleanup(long timestamp)
    {
        Iterator<Map.Entry<ResourceKey,ActiveCall>> iterator = mActiveCalls.entrySet().iterator();

        while(iterator.hasNext())
        {
            ActiveCall call = iterator.next().getValue();

            if(timestamp >= call.lastObservedAt() &&
                timestamp - call.lastObservedAt() > RETENTION_MILLISECONDS)
            {
                iterator.remove();
            }
        }
    }

    private void trim()
    {
        Iterator<ResourceKey> iterator = mActiveCalls.keySet().iterator();

        while(mActiveCalls.size() > MAXIMUM_ACTIVE_CALLS && iterator.hasNext())
        {
            iterator.next();
            iterator.remove();
        }
    }

    private static boolean isTrunkedVoiceObservation(P25ActivityLogRecords.ActivityEvent activity)
    {
        if(activity == null ||
            activity.contextKind() != P25ActivityLogRecords.ContextKind.TRUNKED_SITE ||
            activity.contextKey() == null || activity.contextKey().isBlank() ||
            activity.observedAtEpochMilliseconds() <= 0)
        {
            return false;
        }

        try
        {
            return activity.eventType() != null &&
                DecodeEventType.valueOf(activity.eventType()).isVoiceCallEvent();
        }
        catch(IllegalArgumentException e)
        {
            return false;
        }
    }

    private static Integer positive(String value)
    {
        if(value == null || value.isBlank())
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

    private static Integer eligibleSource(P25ActivityLogRecords.ActivityEvent activity)
    {
        Integer source = activity != null ? positive(activity.sourceRadioId()) : null;
        int protocol = activity != null ? TrunkedIdentityPolicy.protocolFamilyCode(activity.protocol()) : 0;
        return TrunkedIdentityPolicy.isDirectoryRadio(protocol,
            activity != null ? activity.identityDomain() : P25ActivityLogRecords.IdentityDomain.STANDARD, source) ?
            source : null;
    }

    private static Integer firstKnown(Integer existing, Integer observed)
    {
        return existing != null ? existing : observed;
    }

    private record ResourceKey(String contextKey, String channel, int timeslot)
    {
        private static ResourceKey from(P25ActivityLogRecords.ActivityEvent activity)
        {
            String channel = activity.lcn() != null && !activity.lcn().isBlank() ?
                activity.lcn().strip() : activity.frequencyHertz() != null && activity.frequencyHertz() > 0 ?
                Long.toString(activity.frequencyHertz()) : "UNKNOWN";
            return new ResourceKey(activity.contextKey(), channel,
                activity.timeslot() != null ? activity.timeslot() : -1);
        }
    }

    private record ActiveCall(Destination destination, Integer sourceRadioId, boolean encrypted,
                              Integer encryptionAlgorithmId, Integer encryptionKeyId,
                              long callStart, long lastObservedAt,
                              P25ActivityLogRecords.IdentityDomain identityDomain)
    {
    }

    record AttributionResult(boolean tracked, P25ActivityLogRecords.TrunkedCallAttribution attribution)
    {
        private static final AttributionResult NOT_TRACKED = new AttributionResult(false, null);
        private static final AttributionResult TRACKED_WITHOUT_CHANGE = new AttributionResult(true, null);
    }

    private record Destination(int identityId, String kind, List<Integer> patchMembers)
    {
        private boolean isKnown()
        {
            return identityId > 0 && (Form.TALKGROUP.name().equals(kind) ||
                Form.PATCH_GROUP.name().equals(kind) || Form.RADIO.name().equals(kind));
        }

        private boolean sameIdentity(Destination other)
        {
            return other != null && identityId == other.identityId() && Objects.equals(kind, other.kind());
        }

        private static Destination from(P25ActivityLogRecords.ActivityEvent activity)
        {
            Integer identity = activity != null ? positive(activity.targetId()) : null;
            Integer kind = activity != null ? TrunkedIdentityPolicy.identityKindCode(activity.targetKind()) : null;
            int protocol = activity != null ? TrunkedIdentityPolicy.protocolFamilyCode(activity.protocol()) : 0;

            if(identity != null && kind != null &&
                TrunkedIdentityPolicy.isDirectoryIdentity(protocol, activity.identityDomain(), kind, identity))
            {
                return new Destination(identity, activity.targetKind(),
                    activity.patchMemberTalkgroupIds() != null ?
                        List.copyOf(activity.patchMemberTalkgroupIds()) : List.of());
            }

            return new Destination(0, null, List.of());
        }
    }
}
