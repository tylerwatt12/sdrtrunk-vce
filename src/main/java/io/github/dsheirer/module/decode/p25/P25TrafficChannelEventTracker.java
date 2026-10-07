/*
 * *****************************************************************************
 * Copyright (C) 2014-2024 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */

package io.github.dsheirer.module.decode.p25;

import io.github.dsheirer.channel.IChannelDescriptor;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.identifier.radio.FullyQualifiedRadioIdentifier;
import io.github.dsheirer.identifier.radio.RadioIdentifier;
import io.github.dsheirer.identifier.radio.ResolvedRadioIdentity;
import io.github.dsheirer.identifier.talkgroup.FullyQualifiedTalkgroupIdentifier;
import io.github.dsheirer.identifier.talkgroup.TalkgroupIdentifier;
import io.github.dsheirer.module.decode.traffic.RadioSystemKey;
import io.github.dsheirer.module.decode.traffic.TrunkedIdentityDomain;
import io.github.dsheirer.module.decode.p25.identifier.channel.APCO25Channel;
import io.github.dsheirer.module.decode.p25.identifier.patch.APCO25PatchGroup;
import io.github.dsheirer.protocol.Protocol;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wrapper to track the state of a traffic channel event to manage updates from the control channel and the traffic
 * channel and to assist in determining when the communicants of a traffic channel have changed, indicating the need
 * for a new event.
 */
public class P25TrafficChannelEventTracker
{
    private static final Logger LOGGER = LoggerFactory.getLogger(P25TrafficChannelEventTracker.class);
    private static final long STALE_EVENT_THRESHOLD_MS = 2000;
    static final long CONTROL_CONTINUATION_THRESHOLD_MS = 10000;
    private static final long MAX_TDMA_DATA_CHANNEL_EVENT_DURATION_MS = 15000;
    private P25ChannelGrantEvent mEvent;
    private final String mRadioSystemKey;
    private long mLastObservationTimestamp;
    private boolean mStarted = false;
    private boolean mComplete = false;
    private P25CallStartEvent mConventionalCallStart;
    private List<Identifier> mConventionalActivityIdentifiers;
    private boolean mConventionalCompletionPublished;

    /**
     * Constructs an instance
     * @param event to track for the traffic channel.
     */
    public P25TrafficChannelEventTracker(P25ChannelGrantEvent event)
    {
        this(event, null);
    }

    /**
     * @param radioSystemKey immutable serving-system key captured when this physical call began
     */
    public P25TrafficChannelEventTracker(P25ChannelGrantEvent event, String radioSystemKey)
    {
        mEvent = event;
        mRadioSystemKey = event.captureRadioSystemKey(radioSystemKey != null ?
            RadioSystemKey.nativeFor(Protocol.APCO25, TrunkedIdentityDomain.STANDARD, radioSystemKey) : null);
        mLastObservationTimestamp = event.getTimeStart();
    }

    public String getRadioSystemKey()
    {
        return mRadioSystemKey;
    }

    /** Captured once before the conventional tracker is published to asynchronous observers. */
    void setConventionalCallStart(P25CallStartEvent callStart)
    {
        mConventionalCallStart = callStart;
        mConventionalActivityIdentifiers = callStart.identifiers();
    }

    /** No database state or acknowledgement is consulted on the decoding thread. */
    P25ConventionalCallUpdateEvent conventionalCallUpdate()
    {
        if(mConventionalCallStart == null ||
            (sameConventionalIdentifiers() &&
                (!isComplete() || mConventionalCompletionPublished)))
        {
            return null;
        }

        P25CallStartEvent snapshot = mConventionalCallStart.withIdentifiers(getEvent());
        mConventionalActivityIdentifiers = snapshot.identifiers();
        mConventionalCompletionPublished |= isComplete();
        return new P25ConventionalCallUpdateEvent(snapshot, isComplete());
    }

    private boolean sameConventionalIdentifiers()
    {
        int capturedIndex = 0;
        for(Identifier identifier: getEvent().getIdentifierCollection().getIdentifiers())
        {
            if(identifier instanceof APCO25Channel)
            {
                continue;
            }
            if(capturedIndex >= mConventionalActivityIdentifiers.size()) return false;
            Identifier captured = mConventionalActivityIdentifiers.get(capturedIndex++);
            if(!Objects.equals(identifier, captured)) return false;
            //PatchGroup equality compares its primary ID; mutable membership also belongs to the captured facts.
            if(identifier instanceof APCO25PatchGroup current && captured instanceof APCO25PatchGroup previous &&
                current.getValue() != null && previous.getValue() != null &&
                (current.getValue().getVersion() != previous.getValue().getVersion() ||
                    !current.getValue().getPatchedTalkgroupIdentifiers().equals(
                        previous.getValue().getPatchedTalkgroupIdentifiers()) ||
                    !current.getValue().getPatchedRadioIdentifiers().equals(
                        previous.getValue().getPatchedRadioIdentifiers())))
            {
                return false;
            }
        }
        return capturedIndex == mConventionalActivityIdentifiers.size();
    }

    /**
     * Access the underlying traffic channel event that is being tracked.
     * @return event.
     */
    public P25ChannelGrantEvent getEvent()
    {
        return mEvent;
    }

    /**
     * Indicates if this event is stale relative to the provided timestamp.  Staleness is determined by the time delta
     * between the most recent control or traffic observation and timestamp argument.
     * @param timestamp to check for staleness
     * @return true if the delta time exceeds a threshold.
     */
    public boolean isStale(long timestamp)
    {
        return timestamp - mLastObservationTimestamp > STALE_EVENT_THRESHOLD_MS;
    }

    /**
     * Control-channel grant-update messages often contain only a destination.  They can be spaced farther apart than
     * the normal event matching window, so an incomplete call gets a bounded continuation window.
     */
    public boolean isStaleControlContinuation(long timestamp)
    {
        return timestamp - mLastObservationTimestamp > CONTROL_CONTINUATION_THRESHOLD_MS;
    }

    /**
     * Indicates if the TDMA data channel duration exceeds the threshold (15 seconds)
     */
    public boolean exceedsMaxTDMADataDuration()
    {
        return getEvent().getDuration() > MAX_TDMA_DATA_CHANNEL_EVENT_DURATION_MS;
    }

    /**
     * Adds the identifier to the tracked event if the event's identifier collection does not already have it.
     * @param identifier to add
     */
    public void addIdentifierIfMissing(Identifier identifier)
    {
        if(identifier != null)
        {
            Identifier existing = getEvent().getIdentifierCollection().getIdentifier(
                identifier.getIdentifierClass(), identifier.getForm(), identifier.getRole());

            if(getEvent().getIdentifierCollection().hasIdentifier(identifier) &&
                !(existing instanceof FullyQualifiedRadioIdentifier oldRadio &&
                    identifier instanceof FullyQualifiedRadioIdentifier newRadio &&
                    newRadio.getWorkingAddress() != null &&
                    !newRadio.getWorkingAddress().equals(oldRadio.getWorkingAddress())))
            {
                return;
            }

            //An abbreviated grant must not erase the home identity learned from traffic signalling. Otherwise the
            //next fully qualified observation could silently replace a known, conflicting home identity.
            if(isFullyQualified(existing) && !isFullyQualified(identifier) &&
                matchesCallIdentifier(existing, identifier))
            {
                return;
            }

            MutableIdentifierCollection mic = new MutableIdentifierCollection(getEvent().getIdentifierCollection()
                    .getIdentifiers());
            mic.update(identifier);
            getEvent().setIdentifierCollection(mic);
        }
    }

    /**
     * Adds the additional details to the tracked event if the current event details are null or if they do not contain
     * the additional details.
     * @param additionalDetails
     */
    public void addDetailsIfMissing(String additionalDetails)
    {
        if(additionalDetails != null && !additionalDetails.isEmpty())
        {
            if(getEvent().getDetails() == null)
            {
                getEvent().setDetails(additionalDetails);
            }
            else if(!getEvent().getDetails().endsWith(additionalDetails))
            {
                getEvent().setDetails(getEvent().getDetails() + " " + additionalDetails);
            }
        }
    }

    /**
     * Compares the TO role identifier(s) from the tracked event and the identifier collection argument for equality
     * and also checks this event for staleness.
     *
     * @param toCompare containing a TO identifier
     * @param timestamp to check for staleness
     * @return true if both collections contain a TO identifier and the TO identifiers are the same value
     */
    public boolean isSameCallCheckingToOnly(IdentifierCollection toCompare, long timestamp)
    {
        Identifier currentTO = getEvent().getIdentifierCollection().getToIdentifier();
        Identifier nextTO = toCompare.getToIdentifier();
        return !isComplete() && matchesCallIdentifier(currentTO, nextTO) && !isStale(timestamp);
    }

    /**
     * Matches a source-less control-channel update to an incomplete event using the longer continuation window.
     */
    public boolean isSameControlContinuationCheckingToOnly(IdentifierCollection toCompare, long timestamp)
    {
        Identifier currentTO = getEvent().getIdentifierCollection().getToIdentifier();
        Identifier nextTO = toCompare.getToIdentifier();
        return !isComplete() && matchesCallIdentifier(currentTO, nextTO) &&
            !isStaleControlContinuation(timestamp);
    }

    /**
     * Indicates if the tracked event from identifier is non null and that it is different to the from argument.
     * @param fromToCompare against the current event from identifier.
     * @return true if they are different.
     */
    public boolean isDifferentTalker(Identifier fromToCompare)
    {
        if(fromToCompare == null)
        {
            return false;
        }

        Identifier fromCurrent = getEvent().getIdentifierCollection().getFromIdentifier();
        return fromCurrent != null && !matchesCallIdentifier(fromCurrent, fromToCompare);
    }


    /**
     * Checks the call for staleness and verifies that the call event TO identifier is equal to the TO identifier
     * in the provided identifier collection.
     * @param toCompareIC containing a TO and optionally a FROM identifier to compare.
     * @param timestamp to check for staleness
     * @return true if the call identifiers are the same and the call is not stale.
     */
    public boolean isSameCallCheckingToAndFrom(IdentifierCollection toCompareIC, long timestamp)
    {
        if(!isComplete() && !isStale(timestamp))
        {
            Identifier currentTO = getEvent().getIdentifierCollection().getToIdentifier();
            Identifier nextTO = toCompareIC.getToIdentifier();

            if(matchesCallIdentifier(currentTO, nextTO))
            {
                Identifier existingFROM = getEvent().getIdentifierCollection().getFromIdentifier();

                //If the FROM identifier hasn't yet been established, then this is the same call.  We also ignore the
                //talker alias as a call identifier since on L3Harris systems they can transmit the talker alias before
                //they transmit the radio ID.
                if(existingFROM == null || existingFROM.getForm() == Form.TALKER_ALIAS)
                {
                    return true;
                }

                Identifier nextFROM = toCompareIC.getFromIdentifier();

                //Sometime the GROUP_VOICE_CHANNEL_USER has a zero valued FROM address which is not valid, so if the
                //nextFROM is null, we consider the call to be the same.  Likewise, if the nextFROM is a talker alias,
                //that's also the same call.
                if(nextFROM == null || (nextFROM != null && nextFROM.getForm() == Form.TALKER_ALIAS))
                {
                    return true;
                }

                return matchesCallIdentifier(existingFROM, nextFROM);
            }
        }

        return false;
    }

    /**
     * One tracked carrier/slot can alternate abbreviated local addresses and fully qualified traffic identities.
     * Match those representations by their observed local address only when one home identity is unavailable. Two
     * known home identities remain authoritative, and patch/non-P25 identifiers retain their existing equality.
     */
    private boolean matchesCallIdentifier(Identifier first, Identifier second)
    {
        if(first == null || second == null)
        {
            return false;
        }

        if(first.equals(second))
        {
            return true;
        }

        if(first.getIdentifierClass() != second.getIdentifierClass() || first.getForm() != second.getForm() ||
            first.getRole() != second.getRole() || first.getProtocol() != second.getProtocol() ||
            first.getProtocol() != Protocol.APCO25 && first.getProtocol() != Protocol.APCO25_PHASE2)
        {
            return false;
        }

        if(first instanceof RadioIdentifier firstRadio && second instanceof RadioIdentifier secondRadio)
        {
            ResolvedRadioIdentity firstIdentity = ResolvedRadioIdentity.from(firstRadio);
            return firstIdentity != null && firstIdentity.matchesWithinScope(ResolvedRadioIdentity.from(secondRadio),
                mRadioSystemKey);
        }

        if(first instanceof TalkgroupIdentifier firstGroup && second instanceof TalkgroupIdentifier secondGroup)
        {
            if((firstGroup instanceof FullyQualifiedTalkgroupIdentifier) ==
                (secondGroup instanceof FullyQualifiedTalkgroupIdentifier))
            {
                return false;
            }

            return firstGroup.getValue() > 0 && firstGroup.getValue().equals(secondGroup.getValue());
        }

        return false;
    }

    private static boolean isFullyQualified(Identifier identifier)
    {
        return identifier instanceof FullyQualifiedRadioIdentifier ||
            identifier instanceof FullyQualifiedTalkgroupIdentifier;
    }

    /**
     * Indicates if the event has been marked as complete by traffic channel signalling.
     * @return complete status.
     */
    public boolean isComplete()
    {
        return mComplete;
    }

    /**
     * Indicates if the event has been marked as started by the traffic channel for HDU or LDU signalling.
     * @return started status.
     */
    public boolean isStarted()
    {
        return mStarted;
    }

    /**
     * Updates the event duration using signalling from the control channel.
     *
     * Note: once the traffic channel starts updating the event timing, attempts to update timing from the control
     * channel are ignored.
     *
     * @param timestamp to use as the current end timestamp for the event.
     * @return true if the timestamp was updated.
     */
    public boolean updateDurationControl(long timestamp)
    {
        if(!isComplete())
        {
            observe(timestamp);

            if(!isStarted())
            {
                getEvent().update(timestamp);
                return true;
            }
        }

        return false;
    }

    /**
     * Updates the event duration using signalling from the traffic channel.
     *
     * Note: once the event is being updated from the traffic channel, any attempts to update from the control channel
     * are ignored.
     * @param timestamp to assign.
     */
    public void updateDurationTraffic(long timestamp)
    {
        if(!isComplete())
        {
            mStarted = true;
            observe(timestamp);
            getEvent().update(timestamp);
        }
        else
        {
            LOGGER.warn("Attempt to update event call event duration from traffic channel against an event that is marked as complete");
        }
    }

    /**
     * Mark the event as complete and assign final end timestamp to the event.
     *
     * Note: further attempts to complete an already complete event are ignored.
     * @param timestamp to assign.
     * @return true if the timestamp was updated
     */
    public boolean completeTraffic(long timestamp)
    {
        if(!isComplete())
        {
            mComplete = true;
            observe(timestamp);
            getEvent().end(timestamp);
            return true;
        }

        return false;
    }

    private void observe(long timestamp)
    {
        if(timestamp > mLastObservationTimestamp)
        {
            mLastObservationTimestamp = timestamp;
        }
    }

    /**
     * Updates the details for the tracked event.
     * @param details to update
     */
    public void setDetails(String details)
    {
        getEvent().setDetails(details);
    }

    /**
     * Updates the channel descriptor for the tracked event.
     * @param channelDescriptor to update.
     */
    public void addChannelDescriptorIfMissing(IChannelDescriptor channelDescriptor)
    {
        if(channelDescriptor != null && getEvent().getChannelDescriptor() == null)
        {
            getEvent().setChannelDescriptor(channelDescriptor);
        }
    }
}
