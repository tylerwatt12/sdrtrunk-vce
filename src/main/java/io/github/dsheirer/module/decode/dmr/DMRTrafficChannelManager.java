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
package io.github.dsheirer.module.decode.dmr;

import com.google.common.eventbus.EventBus;
import io.github.dsheirer.channel.metadata.activity.ChannelActivityModel;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.Channel.ChannelType;
import io.github.dsheirer.controller.channel.ChannelEvent;
import io.github.dsheirer.controller.channel.ChannelEvent.Event;
import io.github.dsheirer.controller.channel.IChannelEventListener;
import io.github.dsheirer.controller.channel.IChannelEventProvider;
import io.github.dsheirer.controller.channel.event.ChannelStartProcessingRequest;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.alias.TalkerAliasIdentifier;
import io.github.dsheirer.identifier.alias.TalkerAliasManager;
import io.github.dsheirer.identifier.radio.RadioIdentifier;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.module.decode.config.DecodeConfiguration;
import io.github.dsheirer.module.decode.dmr.channel.DMRChannel;
import io.github.dsheirer.module.decode.dmr.telemetry.DMRNetworkConfigurationSnapshot;
import io.github.dsheirer.module.decode.dmr.event.DMRDecodeEvent;
import io.github.dsheirer.module.decode.dmr.identifier.DMRRadio;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.Opcode;
import io.github.dsheirer.module.decode.event.DecodeEvent;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.event.IDecodeEvent;
import io.github.dsheirer.module.decode.event.IDecodeEventProvider;
import io.github.dsheirer.module.decode.traffic.TrafficChannelManager;
import io.github.dsheirer.module.decode.traffic.TrunkedCallStartEvent;
import io.github.dsheirer.module.decode.traffic.TrunkedCallStartTracker;
import io.github.dsheirer.module.decode.traffic.TrunkedIdentityDomain;
import io.github.dsheirer.module.decode.traffic.TrunkedTalkerAliasEvent;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.source.SourceType;
import io.github.dsheirer.source.config.SourceConfigTuner;
import io.github.dsheirer.source.config.SourceConfigTunerMultipleFrequency;
import io.github.dsheirer.source.tuner.channel.rotation.FrequencyLockChangeRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Monitors channel grant and channel grant update messages to allocate traffic channels to capture
 * traffic channel activity.
 *
 * Creates and reuses a limited set of Channel instances each with a TRAFFIC channel type.  Since each of
 * the traffic channels will be using the same decoder type and configuration options, we reuse each of the
 * channel instances to allow the ChannelProcessingManager to reuse a cached set of processing chains that
 * are created as each channel is activated.
 *
 * Each traffic channel is activated by sending a ChannelEvent via the channel model.  The channel processing
 * manager receives the activation request.  If successful, a processing chain is activated for the traffic
 * channel.  Otherwise, a channel event is broadcast indicating that the channel could not be activated.  On
 * teardown of an activated traffic channel, a channel event is broadcast to indicate the traffic channels
 * is no longer active.
 *
 * This manager monitors channel events watching for events related to managed traffic channels.
 */
public class DMRTrafficChannelManager extends TrafficChannelManager implements IChannelEventListener,
    IChannelEventProvider, IDecodeEventProvider
{
    private static final Logger mLog = LoggerFactory.getLogger(DMRTrafficChannelManager.class);
    public static final String CHANNEL_START_REJECTED = " REJECTED - NO TUNER";
    public static final String DATA_CALL_IGNORED = "DATA CALL IGNORED";
    public static final String MAX_TRAFFIC_CHANNELS_EXCEEDED = "MAX TRAFFIC CHANNELS EXCEEDED";
    public static final String NO_FREQUENCY = "NO FREQUENCY - CHECK CONFIGURATION CHANNEL CONFIG LSN CHANNEL MAP";
    public static final long EVENT_TIME_STALE_THRESHOLD = 5000; //5 seconds
    private static final int REST_HANDOFF_CAS_ATTEMPTS = 4;

    private Queue<Channel> mAvailableTrafficChannels = new ConcurrentLinkedQueue<>();
    private List<Channel> mAllocatedTrafficChannels;
    private Map<Long,Channel> mAllocatedChannelFrequencyMap = new ConcurrentHashMap<>();
    private ReentrantLock mLock = new ReentrantLock();
    private Map<Long,IDecodeEvent> mCallEventsTS1 = new ConcurrentHashMap<>();
    private Map<Long,IDecodeEvent> mCallEventsTS2 = new ConcurrentHashMap<>();
    private final TrunkedCallStartTracker mCallStartTracker =
        new TrunkedCallStartTracker(EVENT_TIME_STALE_THRESHOLD);
    private volatile Listener<ChannelEvent> mChannelEventListener;
    private volatile Listener<IDecodeEvent> mDecodeEventListener;
    private TrafficChannelTeardownMonitor mTrafficChannelTeardownMonitor = new TrafficChannelTeardownMonitor();
    private TalkerAliasManager mTalkerAliasManager = new TalkerAliasManager();
    private Channel mParentChannel;
    private boolean mIgnoreDataCalls;
    private final boolean mTrunkingEnabled;
    private ChannelActivityModel mChannelActivityModel;
    private volatile boolean mTrunkedActivityObserved;
    private volatile DMRNetworkConfigurationSnapshot mLatestNetworkConfigurationSnapshot;
    private final AtomicLong mRestHandoffGeneration = new AtomicLong();
    private final AtomicReference<RestHandoffSlot> mPendingRestHandoff = new AtomicReference<>();
    private final AtomicReference<Runnable> mControlFrequencyUpdateInterleaveForTest = new AtomicReference<>();
    private final Channel mRestChannelReservationToken =
        new Channel("DMR Capacity Plus rest-channel reservation", ChannelType.STANDARD);

    /**
     * Monitors call events and allocates traffic decoder channels in response
     * to traffic channel allocation call events.  Manages a pool of reusable
     * traffic channel allocations.
     *
     * @param parentChannel that owns this traffic channel manager
     */
    public DMRTrafficChannelManager(Channel parentChannel)
    {
        mParentChannel = parentChannel;
        mTrunkingEnabled = parentChannel.getDecodeConfiguration() instanceof DecodeConfigDMR config &&
            config.isTrunked();
        mTrunkedActivityObserved = mTrunkingEnabled;

        if(parentChannel.getDecodeConfiguration() instanceof DecodeConfigDMR config)
        {
            mIgnoreDataCalls = config.getIgnoreDataCalls();
        }

        createTrafficChannels();
    }

    /**
     * Talker alias manager
     */
    public TalkerAliasManager getTalkerAliasManager()
    {
        return mTalkerAliasManager;
    }

    /**
     * Records a completed over-the-air alias only when its source radio is known.
     */
    public void processTalkerAlias(TalkerAliasIdentifier alias, RadioIdentifier radio,
                                   IdentifierCollection identifiers, long timestamp)
    {
        if(alias == null || alias.getValue() == null || alias.getValue().toString().isBlank() ||
            radio == null || radio.getRole() != Role.FROM)
        {
            return;
        }

        mTalkerAliasManager.update(radio, alias);

        if(mTrunkingEnabled)
        {
            IdentifierCollection context = identifiers != null ?
                new IdentifierCollection(identifiers.getIdentifiers()) : new IdentifierCollection();
            context.setTimeslot(identifiers != null ? identifiers.getTimeslot() : 0);
            MyEventBus.getGlobalEventBus().post(new TrunkedTalkerAliasEvent(mParentChannel, Protocol.DMR, radio,
                alias, context, TrunkedIdentityDomain.STANDARD,
                timestamp > 0 ? timestamp : System.currentTimeMillis()));
        }
    }

    /**
     * Shared activity model used by the desktop and web Systems views.
     */
    public void setChannelActivityModel(ChannelActivityModel channelActivityModel)
    {
        mChannelActivityModel = channelActivityModel;
    }

    /**
     * Sets the current parent control channel frequency so that channel grants for the current frequency do not
     * produce an additional traffic channel allocation.
     * @param previous for the current control channel (to remove from allocated channels)
     * @param current for current control channel (to add to allocated channels)
     * @param channel for the current control channel
     */
    public void processControlFrequencyUpdate(long previous, long current, Channel channel)
    {
        if(!mTrunkingEnabled || previous == current)
        {
            return;
        }

        Channel existing = mAllocatedChannelFrequencyMap.get(previous);
        Runnable interleave = mControlFrequencyUpdateInterleaveForTest.getAndSet(null);

        if(interleave != null)
        {
            interleave.run();
        }

        //Only remove the channel if it matches the current control channel.
        if(channel.equals(existing) && mAllocatedChannelFrequencyMap.remove(previous, channel))
        {
            EventBus eventBus = getInterModuleEventBus();

            if(eventBus != null)
            {
                eventBus.post(FrequencyLockChangeRequest.unlock(previous));
            }
        }

        installCurrentControlAllocation(current, channel);
        EventBus eventBus = getInterModuleEventBus();

        if(eventBus != null)
        {
            eventBus.post(FrequencyLockChangeRequest.lock(current));
        }

        if(mTrunkedActivityObserved && mChannelActivityModel != null)
        {
            mChannelActivityModel.trunkedCurrentControl(mParentChannel, current);
        }

        RestHandoffSlot pending = mPendingRestHandoff.get();

        if(pending != null &&
            channel.getSourceConfiguration() instanceof SourceConfigTunerMultipleFrequency sourceConfig)
        {
            sourceConfig.setPreferredFrequency(current);
        }
    }

    private void installCurrentControlAllocation(long frequency, Channel channel)
    {
        for(int attempt = 0; attempt < REST_HANDOFF_CAS_ATTEMPTS; attempt++)
        {
            Channel existing = mAllocatedChannelFrequencyMap.putIfAbsent(frequency, channel);

            if(existing == null || existing == channel || existing.isTrafficChannel())
            {
                return;
            }

            if(existing == mRestChannelReservationToken &&
                mAllocatedChannelFrequencyMap.replace(frequency, mRestChannelReservationToken, channel))
            {
                return;
            }
        }
    }

    private void restoreCurrentControlAllocation(long frequency)
    {
        if(getCurrentControlFrequency() == frequency)
        {
            installCurrentControlAllocation(frequency, mParentChannel);

            if(getCurrentControlFrequency() != frequency)
            {
                mAllocatedChannelFrequencyMap.remove(frequency, mParentChannel);
            }
        }
    }

    void setControlFrequencyUpdateInterleaveForTest(Runnable interleave)
    {
        mControlFrequencyUpdateInterleaveForTest.set(interleave);
    }

    /**
     * Creates up to the maximum number of traffic channels for use in allocating traffic channels.
     *
     * Note: this method uses lazy initialization and will only create the channels once.  Subsequent calls will be ignored.
     */
    private void createTrafficChannels()
    {
        if(mAllocatedTrafficChannels == null)
        {
            DecodeConfiguration decodeConfiguration = mParentChannel.getDecodeConfiguration();
            List<Channel> trafficChannelList = new ArrayList<>();

            if(mTrunkingEnabled && decodeConfiguration instanceof DecodeConfigDMR)
            {
                DecodeConfigDMR decodeConfig = (DecodeConfigDMR)decodeConfiguration;

                int maxTrafficChannels = decodeConfig.getTrafficChannelPoolSize();

                if(maxTrafficChannels > 0)
                {
                    for(int x = 0; x < maxTrafficChannels; x++)
                    {
                        Channel trafficChannel = new Channel("T-" + mParentChannel.getName(), ChannelType.TRAFFIC);
                        trafficChannel.setAliasListName(mParentChannel.getAliasListName());
                        trafficChannel.setSystem(mParentChannel.getSystem());
                        trafficChannel.setSite(mParentChannel.getSite());
                        trafficChannel.setConfigurationId(mParentChannel.getConfigurationId());
                        trafficChannel.setRadresGuid(mParentChannel.getRadresGuid());
                        trafficChannel.setDecodeConfiguration(decodeConfig);
                        trafficChannel.setEventLogConfiguration(mParentChannel.getEventLogConfiguration());
                        trafficChannel.setRecordConfiguration(mParentChannel.getRecordConfiguration());
                        trafficChannelList.add(trafficChannel);
                    }
                }
            }

            mAvailableTrafficChannels.addAll(trafficChannelList);

            //Keep track of the complete list so that we can check channel events to determine if the channel event
            //is for a traffic channel owned by this traffic channel manager.
            mAllocatedTrafficChannels = Collections.unmodifiableList(trafficChannelList);
        }
    }

    /**
     * Used with Capacity Plus systems to convert the existing standard channel to a traffic channel and then recreate
     * the original standard channel with the frequency specified for the new rest channel.
     * @param channel
     * @param currentFrequency of the standard channel
     * @param restChannel to identify the new channel frequency to start
     */
    public void requestRestChannelHandoff(Channel channel, long currentFrequency, DMRChannel restChannel)
    {
        requestRestChannelHandoff(channel, currentFrequency, restChannel, null);
    }

    /**
     * Offers a rest-channel move with the latest already-built immutable network snapshot.  A newer target may replace
     * a queued request, but cannot replace one that the lifecycle worker has claimed.
     */
    public void requestRestChannelHandoff(Channel channel, long currentFrequency, DMRChannel restChannel,
                                          DMRNetworkConfigurationSnapshot networkConfigurationSnapshot)
    {
        if(!mTrunkingEnabled || channel != mParentChannel || !channel.isStandardChannel() || restChannel == null ||
            currentFrequency <= 0 || restChannel.getDownlinkFrequency() <= 0)
        {
            return;
        }

        updateNetworkConfigurationSnapshot(networkConfigurationSnapshot);

        for(int attempt = 0; attempt < REST_HANDOFF_CAS_ATTEMPTS; attempt++)
        {
            RestHandoffSlot current = mPendingRestHandoff.get();

            if(current != null && (current.mRequest.matchesRestChannel(restChannel) || current.mClaimed))
            {
                return;
            }

            Channel reservedTrafficChannel = current != null ? current.mTrafficChannel :
                mAvailableTrafficChannels.poll();

            if(reservedTrafficChannel == null)
            {
                return;
            }

            long restFrequency = restChannel.getDownlinkFrequency();

            //Reserve the advertised rest frequency before publishing the request.  Decoder-side grant allocation sees
            //this concurrent-map entry and cannot consume the same frequency or pooled channel in the queueing gap.
            if(mAllocatedChannelFrequencyMap.putIfAbsent(restFrequency, mRestChannelReservationToken) != null)
            {
                if(current == null)
                {
                    mAvailableTrafficChannels.offer(reservedTrafficChannel);
                }
                else
                {
                    //The newer valid target supersedes the older queued target even when its RF is currently busy.
                    //Only the exact unclaimed slot can be invalidated; a concurrent lifecycle claim retains ownership.
                    invalidateUnclaimedRestHandoff(current);
                }

                return;
            }

            DMRNetworkConfigurationSnapshot effectiveSnapshot = networkConfigurationSnapshot != null ?
                networkConfigurationSnapshot : mLatestNetworkConfigurationSnapshot;
            DMRRestChannelHandoffRequest request;

            try
            {
                request = new DMRRestChannelHandoffRequest(this, channel, currentFrequency,
                    restChannel, effectiveSnapshot, mRestHandoffGeneration.incrementAndGet());
            }
            catch(RuntimeException exception)
            {
                mAllocatedChannelFrequencyMap.remove(restFrequency, mRestChannelReservationToken);

                if(current == null)
                {
                    mAvailableTrafficChannels.offer(reservedTrafficChannel);
                }

                mLog.error("Invalid DMR rest-channel handoff request", exception);
                return;
            }

            RestHandoffSlot replacement = new RestHandoffSlot(request, false, reservedTrafficChannel);

            if(mPendingRestHandoff.compareAndSet(current, replacement))
            {
                if(current != null)
                {
                    releaseRestFrequencyReservation(current.mRequest);
                }

                EventBus eventBus = getInterModuleEventBus();

                if(eventBus != null)
                {
                    try
                    {
                        eventBus.post(request);
                    }
                    catch(RuntimeException exception)
                    {
                        completeRestHandoff(request);
                        mLog.error("Error offering DMR rest-channel handoff", exception);
                    }
                }
                else
                {
                    completeRestHandoff(request);
                }

                return;
            }

            mAllocatedChannelFrequencyMap.remove(restFrequency, mRestChannelReservationToken);

            if(current == null)
            {
                mAvailableTrafficChannels.offer(reservedTrafficChannel);
            }
        }
    }

    private void invalidateUnclaimedRestHandoff(RestHandoffSlot queued)
    {
        if(queued != null && !queued.mClaimed && mPendingRestHandoff.compareAndSet(queued, null))
        {
            releaseRestFrequencyReservation(queued.mRequest);
            mAvailableTrafficChannels.offer(queued.mTrafficChannel);
        }
    }

    /**
     * Retains the latest already-built immutable metadata snapshot so either timeslot can nominate the next rest
     * channel without synchronizing on or sharing the decoder's mutable monitor.
     */
    public void updateNetworkConfigurationSnapshot(DMRNetworkConfigurationSnapshot snapshot)
    {
        if(snapshot != null)
        {
            mLatestNetworkConfigurationSnapshot = snapshot;
        }
    }

    /**
     * Indicates that this request is still the current handoff for this manager.
     */
    public boolean isPendingRestHandoff(DMRRestChannelHandoffRequest request)
    {
        RestHandoffSlot slot = mPendingRestHandoff.get();
        return slot != null && slot.mRequest == request;
    }

    /**
     * Current allocation for deterministic package-level accounting tests.
     */
    Channel getAllocatedChannel(long frequency)
    {
        return mAllocatedChannelFrequencyMap.get(frequency);
    }

    /**
     * Releases the pending slot after a handoff succeeds, is rejected, or is cancelled.
     */
    public void completeRestHandoff(DMRRestChannelHandoffRequest request)
    {
        completeRestHandoff(request, false);
    }

    /**
     * Completes a successful move and retains the new standard-channel frequency allocation.
     */
    public void completeSuccessfulRestHandoff(DMRRestChannelHandoffRequest request)
    {
        completeRestHandoff(request, true);
    }

    private void completeRestHandoff(DMRRestChannelHandoffRequest request, boolean keepRestFrequencyReservation)
    {
        for(int attempt = 0; attempt < REST_HANDOFF_CAS_ATTEMPTS; attempt++)
        {
            RestHandoffSlot slot = mPendingRestHandoff.get();

            if(slot == null || slot.mRequest != request)
            {
                return;
            }

            if(mPendingRestHandoff.compareAndSet(slot, null))
            {
                if(!slot.mClaimed)
                {
                    mAvailableTrafficChannels.offer(slot.mTrafficChannel);
                }

                if(!keepRestFrequencyReservation)
                {
                    releaseRestFrequencyReservation(request);
                }
                else
                {
                    mAllocatedChannelFrequencyMap.replace(request.restDownlinkFrequency(),
                        mRestChannelReservationToken, mParentChannel);
                }

                return;
            }
        }
    }

    /**
     * Cancels a pending handoff for the specified configured parent channel.
     */
    public void cancelRestHandoff(Channel channel)
    {
        RestHandoffSlot slot = mPendingRestHandoff.get();
        DMRRestChannelHandoffRequest pending = slot != null ? slot.mRequest : null;

        if(pending != null && pending.parentChannel() == channel)
        {
            completeRestHandoff(pending);
        }
    }

    /**
     * Explicitly accounts for a converted traffic channel that stops while the manager is detached during replacement
     * source retry or cancellation.
     */
    public void processTrafficChannelTeardown(Channel trafficChannel)
    {
        if(trafficChannel != null)
        {
            mTrafficChannelTeardownMonitor.receive(new ChannelEvent(trafficChannel,
                Event.NOTIFICATION_PROCESSING_STOP));
        }
    }

    /**
     * Performs the Capacity Plus chain conversion on the channel lifecycle worker.  This method reserves and converts
     * a traffic channel exactly once for the request generation.  Replacement source allocation happens later and is
     * never performed while the manager lock is held.
     */
    public PreparedRestChannelHandoff prepareRestChannelHandoff(DMRRestChannelHandoffRequest handoff)
    {
        RestHandoffSlot queued = mPendingRestHandoff.get();

        if(queued == null || queued.mRequest != handoff || queued.mClaimed ||
            !mPendingRestHandoff.compareAndSet(queued,
                new RestHandoffSlot(handoff, true, queued.mTrafficChannel)))
        {
            return null;
        }

        Channel trafficChannel = queued.mTrafficChannel;

        try
        {
            Channel channel = handoff.parentChannel();
            long currentFrequency = handoff.currentFrequency();
            DMRChannel restChannel = handoff.createRestChannel();
            long rest = restChannel.getDownlinkFrequency();

            if(mAllocatedChannelFrequencyMap.get(rest) != mRestChannelReservationToken ||
                channel.getSourceConfiguration().getSourceType() != SourceType.TUNER_MULTIPLE_FREQUENCIES)
            {
                mAvailableTrafficChannels.offer(trafficChannel);
                return null;
            }

            SourceConfigTunerMultipleFrequency originalSourceConfig =
                (SourceConfigTunerMultipleFrequency)channel.getSourceConfiguration();

            SourceConfigTuner trafficSourceConfig = new SourceConfigTuner();
            trafficSourceConfig.setFrequency(currentFrequency);
            trafficChannel.setSourceConfiguration(trafficSourceConfig);
            ChannelStartProcessingRequest startRequest = new ChannelStartProcessingRequest(channel, restChannel,
                null, this);

            if(handoff.networkConfigurationSnapshot() != null)
            {
                startRequest.addPreloadDataContent(
                    new DMRRestChannelNetworkConfigurationPreloadData(handoff.networkConfigurationSnapshot()));
            }

            return new PreparedRestChannelHandoff(handoff, trafficChannel, startRequest,
                originalSourceConfig.getPreferredFrequency());
        }
        catch(RuntimeException exception)
        {
            if(trafficChannel != null)
            {
                mAvailableTrafficChannels.offer(trafficChannel);
            }

            throw exception;
        }
    }

    /**
     * Commits the manager-owned accounting immediately before the lifecycle manager converts the exact live chain.
     */
    public boolean commitRestChannelHandoff(PreparedRestChannelHandoff prepared)
    {
        if(prepared == null || !isPendingRestHandoff(prepared.handoff()))
        {
            return false;
        }

        DMRRestChannelHandoffRequest handoff = prepared.handoff();
        SourceConfigTunerMultipleFrequency sourceConfig =
            (SourceConfigTunerMultipleFrequency)handoff.parentChannel().getSourceConfiguration();
        long restFrequency = handoff.restDownlinkFrequency();

        if(!mAllocatedChannelFrequencyMap.replace(handoff.currentFrequency(), mParentChannel,
            prepared.trafficChannel()))
        {
            return false;
        }

        prepared.setPreviousAllocation(mParentChannel);

        if(!sourceConfig.getFrequencies().contains(restFrequency))
        {
            sourceConfig.addFrequency(restFrequency);
        }

        sourceConfig.setPreferredFrequency(restFrequency);
        return true;
    }

    /**
     * Releases a reservation that was never converted into a live traffic chain.
     */
    public void releaseRestChannelReservation(PreparedRestChannelHandoff prepared)
    {
        releaseRestChannelReservation(prepared, null);
    }

    void releaseRestChannelReservation(PreparedRestChannelHandoff prepared, Runnable afterCurrentFrequencySnapshot)
    {
        if(prepared != null && !prepared.isConverted() && prepared.releaseReservation())
        {
            DMRRestChannelHandoffRequest handoff = prepared.handoff();
            Channel previousAllocation = prepared.previousAllocation();
            boolean currentFrequencyUnchanged = getCurrentControlFrequency() == handoff.currentFrequency();
            SourceConfigTunerMultipleFrequency sourceConfig =
                (SourceConfigTunerMultipleFrequency)handoff.parentChannel().getSourceConfiguration();

            if(afterCurrentFrequencySnapshot != null)
            {
                afterCurrentFrequencySnapshot.run();
            }

            boolean restored = false;

            if(previousAllocation != null && currentFrequencyUnchanged)
            {
                restored = mAllocatedChannelFrequencyMap.replace(handoff.currentFrequency(),
                    prepared.trafficChannel(), previousAllocation);

                if(restored && getCurrentControlFrequency() != handoff.currentFrequency())
                {
                    mAllocatedChannelFrequencyMap.remove(handoff.currentFrequency(), previousAllocation);
                    restored = false;
                }
            }
            else
            {
                mAllocatedChannelFrequencyMap.remove(handoff.currentFrequency(), prepared.trafficChannel());
                restoreCurrentControlAllocation(handoff.currentFrequency());
                restored = previousAllocation != null &&
                    getCurrentControlFrequency() == handoff.currentFrequency() &&
                    mAllocatedChannelFrequencyMap.get(handoff.currentFrequency()) == previousAllocation;
            }

            if(restored)
            {
                sourceConfig.setPreferredFrequency(prepared.previousPreferredFrequency());

                if(getCurrentControlFrequency() != handoff.currentFrequency())
                {
                    mAllocatedChannelFrequencyMap.remove(handoff.currentFrequency(), previousAllocation);
                    sourceConfig.setPreferredFrequency(getCurrentControlFrequency());
                }
            }
            else if(getCurrentControlFrequency() != handoff.currentFrequency())
            {
                sourceConfig.setPreferredFrequency(getCurrentControlFrequency());
            }

            mAvailableTrafficChannels.offer(prepared.trafficChannel());
        }
    }

    private void releaseRestFrequencyReservation(DMRRestChannelHandoffRequest request)
    {
        if(request != null)
        {
            mAllocatedChannelFrequencyMap.remove(request.restDownlinkFrequency(), mRestChannelReservationToken);
        }
    }

    private record RestHandoffSlot(DMRRestChannelHandoffRequest mRequest, boolean mClaimed,
                                   Channel mTrafficChannel)
    {
    }

    /**
     * Prepared state retained across replacement-source retries so conversion is never repeated.
     */
    public static final class PreparedRestChannelHandoff
    {
        private final DMRRestChannelHandoffRequest mHandoff;
        private final Channel mTrafficChannel;
        private final ChannelStartProcessingRequest mStartRequest;
        private final long mPreviousPreferredFrequency;
        private final AtomicBoolean mReservationReleased = new AtomicBoolean();
        private final AtomicBoolean mConverted = new AtomicBoolean();
        private final AtomicReference<Channel> mPreviousAllocation = new AtomicReference<>();

        private PreparedRestChannelHandoff(DMRRestChannelHandoffRequest handoff, Channel trafficChannel,
                                           ChannelStartProcessingRequest startRequest,
                                           long previousPreferredFrequency)
        {
            mHandoff = handoff;
            mTrafficChannel = trafficChannel;
            mStartRequest = startRequest;
            mPreviousPreferredFrequency = previousPreferredFrequency;
        }

        public DMRRestChannelHandoffRequest handoff()
        {
            return mHandoff;
        }

        public Channel trafficChannel()
        {
            return mTrafficChannel;
        }

        public ChannelStartProcessingRequest startRequest()
        {
            return mStartRequest;
        }

        public long previousPreferredFrequency()
        {
            return mPreviousPreferredFrequency;
        }

        private boolean releaseReservation()
        {
            return mReservationReleased.compareAndSet(false, true);
        }

        public void markConverted()
        {
            mConverted.set(true);
        }

        private boolean isConverted()
        {
            return mConverted.get();
        }

        private void setPreviousAllocation(Channel channel)
        {
            mPreviousAllocation.compareAndSet(null, channel);
        }

        private Channel previousAllocation()
        {
            return mPreviousAllocation.get();
        }
    }

    /**
     * Broadcasts an initial or update decode event to any registered listener.
     */
    public void broadcast(IDecodeEvent decodeEvent)
    {
        publishChannelActivity(decodeEvent);
        Listener<IDecodeEvent> listener = mDecodeEventListener;

        if(listener != null)
        {
            listener.receive(decodeEvent);
        }
    }

    private void publishChannelActivity(IDecodeEvent decodeEvent)
    {
        if(!mTrunkedActivityObserved || mChannelActivityModel == null || decodeEvent == null ||
            decodeEvent.getChannelDescriptor() == null)
        {
            return;
        }

        long frequency = decodeEvent.getChannelDescriptor().getDownlinkFrequency();
        Channel allocated = mAllocatedChannelFrequencyMap.get(frequency);
        Channel trafficChannel = allocated != null && allocated.isTrafficChannel() ? allocated : null;
        Integer timeslot = decodeEvent.hasTimeslot() ? decodeEvent.getTimeslot() :
            decodeEvent.getChannelDescriptor() instanceof DMRChannel dmrChannel ? dmrChannel.getTimeslot() : null;
        mChannelActivityModel.trunkedTrafficEvent(mParentChannel, trafficChannel,
            decodeEvent.getChannelDescriptor(), timeslot, decodeEvent.getIdentifierCollection(),
            decodeEvent.getEventType(), getCurrentControlFrequency());
    }

    /**
     * Receives a side observation of a decoder-owned traffic event.  The originating processing chain remains the
     * event's only product delivery route; this manager adds call attribution and channel-activity accounting without
     * rebroadcasting the same event through the replacement parent chain.
     * @param trafficChannelEvent observed on a managed traffic channel.
     */
    public void receiveTrafficChannelEvent(IDecodeEvent trafficChannelEvent)
    {
        if(mTrunkingEnabled && trafficChannelEvent != null &&
            trafficChannelEvent.getEventType() != null &&
            trafficChannelEvent.getEventType().isVoiceCallEvent())
        {
            Integer timeslot = trafficChannelEvent.hasTimeslot() ? trafficChannelEvent.getTimeslot() :
                trafficChannelEvent.getChannelDescriptor() instanceof DMRChannel dmrChannel ?
                    dmrChannel.getTimeslot() : null;
            long timestamp = Math.max(trafficChannelEvent.getTimeStart(), trafficChannelEvent.getTimeEnd());
            TrunkedCallStartTracker.ObservationResult observation = mCallStartTracker.enrichActiveCall(
                mParentChannel, Protocol.DMR, trafficChannelEvent.getChannelDescriptor(), timeslot,
                trafficChannelEvent.getIdentifierCollection(), trafficChannelEvent.getEventType(), timestamp);

            if(observation.attribution() != null)
            {
                MyEventBus.getGlobalEventBus().post(observation.attribution());
            }
        }

        publishChannelActivity(trafficChannelEvent);
    }

    /**
     * Processes channel grants to allocate traffic channels and track overall channel usage.  Generates
     * decode events for each new channel that is allocated.
     */
    public void processChannelGrant(DMRChannel channel, IdentifierCollection identifierCollection,
                                    Opcode opcode, long timestamp, boolean encrypted)
    {
        processChannelGrant(channel, identifierCollection, opcode, timestamp, encrypted, null, null);
    }

    /**
     * Package-level deterministic race seam.  Production callers use the public overload with no interleave action.
     */
    void processChannelGrant(DMRChannel channel, IdentifierCollection identifierCollection,
                             Opcode opcode, long timestamp, boolean encrypted, Runnable afterInitialAllocationLookup)
    {
        processChannelGrant(channel, identifierCollection, opcode, timestamp, encrypted,
            afterInitialAllocationLookup, null);
    }

    /**
     * Package-level deterministic seams around the two lock-free allocation decisions.
     */
    void processChannelGrant(DMRChannel channel, IdentifierCollection identifierCollection,
                             Opcode opcode, long timestamp, boolean encrypted, Runnable afterInitialAllocationLookup,
                             Runnable afterTrafficAllocationClaim)
    {
        if(!mTrunkingEnabled)
        {
            return;
        }

        mTrunkedActivityObserved = true;
        ChannelStartProcessingRequest trafficStartRequest = null;
        mLock.lock();

        try
        {
            DecodeEventType decodeEventType = getEventType(opcode, identifierCollection, encrypted);
            TrunkedCallStartTracker.ObservationResult callObservation =
                mCallStartTracker.observeWithAttribution(mParentChannel, Protocol.DMR, channel,
                    channel.getTimeslot(), identifierCollection, decodeEventType, timestamp);
            TrunkedCallStartEvent callStart = callObservation.callStart();

            if(callStart != null)
            {
                MyEventBus.getGlobalEventBus().post(callStart);
            }

            if(callObservation.attribution() != null)
            {
                MyEventBus.getGlobalEventBus().post(callObservation.attribution());
            }

            long downlinkFrequency = channel.getDownlinkFrequency();
            boolean currentControlFrequency = downlinkFrequency > 0 &&
                downlinkFrequency == getCurrentControlFrequency();
            boolean allocated = currentControlFrequency ||
                mAllocatedChannelFrequencyMap.containsKey(downlinkFrequency);

            if(!allocated && afterInitialAllocationLookup != null)
            {
                afterInitialAllocationLookup.run();
            }

            if(allocated)
            {
                //Traffic children maintain their own event state.  The parent control frequency is also tracked as
                //allocated, however, and can carry a call on its other timeslot.  Publish the repeated/current-RF grant
                //directly so that call remains visible after the parent is promoted out of Conventional activity.
                if((mAllocatedChannelFrequencyMap.get(downlinkFrequency) == mParentChannel ||
                    currentControlFrequency) && !isPendingRestFrequency(downlinkFrequency))
                {
                    publishChannelActivity(channel, identifierCollection, decodeEventType);
                }
            }
            else
            {
                long frequency = channel.getDownlinkFrequency();

                //If we don't have a frequency value for the channel, use the channel number as a place holder.  We won't
                //allocate a traffic channel for it, but we can track the related channel grant event.
                if(frequency == 0)
                {
                    frequency = channel.getChannelNumber();
                }

                Map<Long,IDecodeEvent> eventMap = channel.getTimeslot() == 1 ? mCallEventsTS1 : mCallEventsTS2;
                IDecodeEvent temp = eventMap.get(frequency);
                DecodeEvent event = (temp != null) ? (DecodeEvent)temp : null;

                if(isStale(event, timestamp, identifierCollection)) //Create new event
                {
                    event = DMRDecodeEvent.builder(decodeEventType, timestamp)
                            .channel(channel)
                            .details("CHANNEL GRANT" + (encrypted ? " ENCRYPTED" : ""))
                            .identifiers(identifierCollection)
                            .timeslot(channel.getTimeslot())
                            .build();
                    eventMap.put(frequency, event);
                }
                else if(event != null) //Update current event
                {
                    Identifier from = getIdentifier(identifierCollection, Role.FROM);

                    if(from != null)
                    {
                        Identifier currentFrom = getIdentifier(event.getIdentifierCollection(), Role.FROM);
                        if(currentFrom != null && !Objects.equals(from, currentFrom))
                        {
                            event.end(timestamp);

                            event = DMRDecodeEvent.builder(decodeEventType, timestamp)
                                    .channel(channel)
                                    .details("CONTINUE - CHANNEL GRANT" + (encrypted ? " ENCRYPTED" : ""))
                                    .identifiers(identifierCollection)
                                    .timeslot(channel.getTimeslot())
                                    .build();

                            eventMap.put(frequency, event);
                        }
                    }

                    //update the ending timestamp so that the duration value is correctly calculated
                    event.update(timestamp);
                }

                //If our channel doesn't have a frequency value, update the event to reflect this.
                if(channel.getDownlinkFrequency() == 0)
                {
                    if(event.getDetails() == null)
                    {
                        event.setDetails(NO_FREQUENCY);
                    }
                    else if(!event.getDetails().endsWith(NO_FREQUENCY))
                    {
                        event.setDetails(event.getDetails() + " - " + NO_FREQUENCY);
                    }

                    broadcast(event);
                    return;
                }

                if(mIgnoreDataCalls && opcode.isDataChannelGrantOpcode())
                {
                    if(event.getDetails() == null)
                    {
                        event.setDetails(DATA_CALL_IGNORED);
                    }
                    else if(!event.getDetails().endsWith(DATA_CALL_IGNORED))
                    {
                        event.setDetails(event.getDetails() + " - " + DATA_CALL_IGNORED);
                    }

                    broadcast(event);
                    return;
                }

                Channel trafficChannel = mAvailableTrafficChannels.poll();

                if(trafficChannel != null)
                {
                    if(mAllocatedChannelFrequencyMap.putIfAbsent(frequency, trafficChannel) == null)
                    {
                        if(afterTrafficAllocationClaim != null)
                        {
                            afterTrafficAllocationClaim.run();
                        }

                        if(getCurrentControlFrequency() == frequency)
                        {
                            if(mAllocatedChannelFrequencyMap.remove(frequency, trafficChannel))
                            {
                                restoreCurrentControlAllocation(frequency);
                                mAvailableTrafficChannels.offer(trafficChannel);
                            }
                        }
                        else if(mAllocatedChannelFrequencyMap.get(frequency) == trafficChannel)
                        {
                            SourceConfigTuner sourceConfig = new SourceConfigTuner();
                            sourceConfig.setFrequency(frequency);
                            trafficChannel.setSourceConfiguration(sourceConfig);
                            //Preload the channel grant event for this traffic-channel start only.
                            ChannelStartProcessingRequest request = new ChannelStartProcessingRequest(trafficChannel,
                                channel, identifierCollection, this);
                            request.addPreloadDataContent(new DMRChannelGrantPreloadData(event));
                            trafficStartRequest = request;
                        }
                    }
                    else
                    {
                        //A concurrent rest-channel handoff or grant claimed this frequency after the initial lookup.
                        //Return the untouched pooled channel instead of overwriting that allocation.
                        mAvailableTrafficChannels.offer(trafficChannel);
                    }
                }
                else
                {
                    if(event.getDetails() == null)
                    {
                        event.setDetails(MAX_TRAFFIC_CHANNELS_EXCEEDED);
                    }
                    else if(!event.getDetails().endsWith(MAX_TRAFFIC_CHANNELS_EXCEEDED))
                    {
                        event.setDetails(event.getDetails() + " - " + MAX_TRAFFIC_CHANNELS_EXCEEDED);
                    }
                }

                broadcast(event);
            }
        }
        catch(Exception e)
        {
            //This shouldn't happen, but we'll log if it ever does.
            mLog.error("Error while processing channel grant event - traffic channel lock is released", e);
        }
        finally
        {
            mLock.unlock();
        }

        if(trafficStartRequest != null)
        {
            EventBus eventBus = getInterModuleEventBus();

            if(eventBus != null)
            {
                try
                {
                    if(trafficStartRequest.getChannel().getSourceConfiguration() instanceof
                        SourceConfigTuner sourceConfig)
                    {
                        eventBus.post(FrequencyLockChangeRequest.lock(sourceConfig.getFrequency()));
                    }

                    eventBus.post(trafficStartRequest);
                }
                catch(RuntimeException exception)
                {
                    mLog.error("Error offering DMR traffic-channel start request", exception);
                    processTrafficChannelTeardown(trafficStartRequest.getChannel());
                }
            }
            else
            {
                processTrafficChannelTeardown(trafficStartRequest.getChannel());
            }
        }
    }

    private boolean isPendingRestFrequency(long frequency)
    {
        RestHandoffSlot slot = mPendingRestHandoff.get();
        return slot != null && slot.mRequest.restDownlinkFrequency() == frequency;
    }

    private void publishChannelActivity(DMRChannel channel, IdentifierCollection identifiers,
                                        DecodeEventType eventType)
    {
        if(mChannelActivityModel == null || channel == null)
        {
            return;
        }

        Channel allocated = mAllocatedChannelFrequencyMap.get(channel.getDownlinkFrequency());
        Channel trafficChannel = allocated != null && allocated.isTrafficChannel() ? allocated : null;
        mChannelActivityModel.trunkedTrafficEvent(mParentChannel, trafficChannel, channel, channel.getTimeslot(),
            identifiers, eventType, getCurrentControlFrequency());
    }


    /**
     * Creates a call event type description for the specified opcode and service options
     */
    private DecodeEventType getEventType(Opcode opcode, IdentifierCollection identifierCollection, boolean encrypted)
    {
        DecodeEventType type = null;

        switch(opcode)
        {
            case STANDARD_TALKGROUP_VOICE_CHANNEL_GRANT,
                STANDARD_BROADCAST_TALKGROUP_VOICE_CHANNEL_GRANT,
                MOTOROLA_CAPMAX_CHANNEL_UPDATE_OPEN_MODE,
                MOTOROLA_CAPMAX_CHANNEL_UPDATE_ADVANTAGE_MODE:
                type = encrypted ? DecodeEventType.CALL_GROUP_ENCRYPTED : DecodeEventType.CALL_GROUP;
                break;

            case MOTOROLA_CONPLUS_VOICE_CHANNEL_USER:
                Identifier to = identifierCollection.getToIdentifier();

                if(to instanceof DMRRadio)
                {
                    type = encrypted ? DecodeEventType.CALL_UNIT_TO_UNIT_ENCRYPTED : DecodeEventType.CALL_UNIT_TO_UNIT;
                }
                else
                {
                    type = encrypted ? DecodeEventType.CALL_GROUP_ENCRYPTED : DecodeEventType.CALL_GROUP;
                }
                break;

            case STANDARD_PRIVATE_VOICE_CHANNEL_GRANT,
                STANDARD_DUPLEX_PRIVATE_VOICE_CHANNEL_GRANT:
                type = encrypted ? DecodeEventType.CALL_UNIT_TO_UNIT_ENCRYPTED : DecodeEventType.CALL_UNIT_TO_UNIT;
                break;

            case STANDARD_PRIVATE_DATA_CHANNEL_GRANT_SINGLE_ITEM,
                STANDARD_TALKGROUP_DATA_CHANNEL_GRANT_SINGLE_ITEM,
                STANDARD_DUPLEX_PRIVATE_DATA_CHANNEL_GRANT,
                STANDARD_PRIVATE_DATA_CHANNEL_GRANT_MULTI_ITEM,
                STANDARD_TALKGROUP_DATA_CHANNEL_GRANT_MULTI_ITEM,
                MOTOROLA_CONPLUS_DATA_CHANNEL_GRANT:
                type = encrypted ? DecodeEventType.DATA_CALL_ENCRYPTED : DecodeEventType.DATA_CALL;
                break;
            default:
                break;
        }

        if(type == null)
        {
            mLog.debug("Unrecognized opcode for determining decode event type: " + opcode.name());
            type = DecodeEventType.CALL;
        }

        return type;
    }

    /**
     * Channel event listener to receive notifications that a traffic channel has ended processing and we
     * can reclaim the traffic channel for reuse.
     *
     * @return listener for processing channel events.
     */
    @Override
    public Listener<ChannelEvent> getChannelEventListener()
    {
        return mTrafficChannelTeardownMonitor;
    }

    /**
     * Broadcasts a channel event to a registered external listener (for action).
     */
    private void broadcast(ChannelEvent channelEvent)
    {
        Listener<ChannelEvent> listener = mChannelEventListener;

        if(listener != null)
        {
            listener.receive(channelEvent);
        }
    }

    /**
     * Sets the external channel event listener to receive channel events from this traffic channel manager
     */
    @Override
    public void setChannelEventListener(Listener<ChannelEvent> listener)
    {
        mChannelEventListener = listener;
    }

    /**
     * Removes the channel event listener
     */
    @Override
    public void removeChannelEventListener()
    {
        mChannelEventListener = null;
    }

    /**
     * Compares the TO role identifier(s) from each collection for equality
     *
     * @param collection1 containing a TO identifier
     * @param collection2 containing a TO identifier
     * @return true if both collections contain a TO identifier and the TO identifiers are the same value
     */
    private boolean isSameCall(IdentifierCollection collection1, IdentifierCollection collection2)
    {
        Identifier toIdentifier1 = getIdentifier(collection1, Role.TO);
        Identifier toIdentifier2 = getIdentifier(collection2, Role.TO);
        return Objects.equals(toIdentifier1, toIdentifier2);
    }

    /**
     * Indicates if the event is a stale event, meaning that the event is null, or the event start exceeds the max
     * valid call duration threshold, or if the event identifiers don't match the current identifiers.
     *
     * @param event to check for staleness
     * @param timestamp to check the event against
     * @param currentIdentifiers to compare against the event
     * @return true if the event is stale.
     */
    private boolean isStale(IDecodeEvent event, long timestamp, IdentifierCollection currentIdentifiers)
    {
        if(event == null)
        {
            return true;
        }

        if(timestamp - event.getTimeEnd() > EVENT_TIME_STALE_THRESHOLD)
        {
            return true;
        }

        return !isSameCall(event.getIdentifierCollection(), currentIdentifiers);
    }


    /**
     * Retrieves the first identifier with a TO role.
     *
     * @param collection containing a TO identifier
     * @return TO identifier or null
     */
    private Identifier getIdentifier(IdentifierCollection collection, Role role)
    {
        List<Identifier> identifiers = collection.getIdentifiers(role);

        if(identifiers.size() >= 1)
        {
            return identifiers.get(0);
        }

        return null;
    }

    /**
     * Implements the IDecodeEventProvider interface to provide channel events to an external listener.
     */
    @Override
    public void addDecodeEventListener(Listener<IDecodeEvent> listener)
    {
        mDecodeEventListener = listener;
    }

    /**
     * Removes the external decode event listener
     */
    @Override
    public void removeDecodeEventListener(Listener<IDecodeEvent> listener)
    {
        mDecodeEventListener = null;
    }

    @Override
    public void reset()
    {
        mCallStartTracker.clear();
    }

    /**
     * Starts this traffic channel manager.
     *
     * Note: for Capacity+ systems, this traffic channel manager will be reused when the current channel is in use and
     * a new rest channel is nominated.  This traffic channel manager instance will be transferred to the new standard
     * channel created to monitor the new rest channel.  As such, this manager will have a list of currently allocated
     * traffic channels.  Broadcast frequency lock requests for each allocated traffic channel frequency so that the
     * new rest channel rotation manager doesn't rotate onto frequencies already being monitored as traffic channels.
     */
    @Override
    public void start()
    {
        EventBus eventBus = getInterModuleEventBus();

        if(eventBus != null)
        {
            for(Long frequency: mAllocatedChannelFrequencyMap.keySet())
            {
                eventBus.post(FrequencyLockChangeRequest.lock(frequency));
            }
        }
    }

    @Override
    public void stop()
    {
        List<Channel> channels;
        mLock.lock();

        try
        {
            mAvailableTrafficChannels.clear();
            mCallStartTracker.clear();
            channels = new ArrayList<>(mAllocatedChannelFrequencyMap.values());
        }
        finally
        {
            mLock.unlock();
        }

        //Issue a disable request for each traffic channel
        for(Channel channel: channels)
        {
            broadcast(new ChannelEvent(channel, Event.REQUEST_DISABLE));
        }
    }

    /**
     * Monitors channel teardown events to detect when traffic channel processing has ended.  Reclaims the
     * channel instance for reuse by future traffic channel grants.
     */
    public class TrafficChannelTeardownMonitor implements Listener<ChannelEvent>
    {
        /**
         * Removes any call events that are associated with the specified frequency.
         * @param frequency that was removed
         */
        private void removeCallEvents(long frequency)
        {
            mLock.lock();

            try
            {
                IDecodeEvent timeslotOne = mCallEventsTS1.remove(frequency);
                IDecodeEvent timeslotTwo = mCallEventsTS2.remove(frequency);
                long endedAt = System.currentTimeMillis();

                if(timeslotOne != null)
                {
                    mCallStartTracker.end(timeslotOne.getChannelDescriptor(), 1, endedAt);
                }

                if(timeslotTwo != null)
                {
                    mCallStartTracker.end(timeslotTwo.getChannelDescriptor(), 2, endedAt);
                }
            }
            finally
            {
                mLock.unlock();
            }
        }

        /**
         * Updates any call events related to the frequency to indicate the channel start was rejected.
         * @param frequency for the channel.
         */
        private void setChannelStartRejected(long frequency)
        {
            mLock.lock();

            try
            {
                IDecodeEvent event = mCallEventsTS1.get(frequency);

                if(event instanceof DecodeEvent decodeEvent1)
                {
                    if(decodeEvent1.getDetails() == null)
                    {
                        decodeEvent1.setDetails(CHANNEL_START_REJECTED);
                    }
                    else if(!decodeEvent1.getDetails().endsWith(CHANNEL_START_REJECTED))
                    {
                        decodeEvent1.setDetails(event.getDetails() + "-" + CHANNEL_START_REJECTED);
                    }

                    broadcast(decodeEvent1);
                }

                event = mCallEventsTS2.get(frequency);

                if(event instanceof DecodeEvent decodeEvent2)
                {
                    if(decodeEvent2.getDetails() == null)
                    {
                        decodeEvent2.setDetails(CHANNEL_START_REJECTED);
                    }
                    else if(!decodeEvent2.getDetails().endsWith(CHANNEL_START_REJECTED))
                    {
                        decodeEvent2.setDetails(decodeEvent2.getDetails() + "-" + CHANNEL_START_REJECTED);
                    }

                    broadcast(decodeEvent2);
                }
            }
            finally
            {
                mLock.unlock();
            }
        }

        /**
         /**
         * Process channel events from the ChannelProcessingManager to account for owned child traffic channels.
         * Note: this method sees events for ALL channels and not just DMR channels managed by this instance.
         *
         * @param channelEvent to process
         */
        @Override
        public void receive(ChannelEvent channelEvent)
        {
            Channel channel = channelEvent.getChannel();

            //Only process the event if it's one of the traffic channels managed by this instance.
            if(mAllocatedTrafficChannels.contains(channel))
            {
                switch(channelEvent.getEvent())
                {
                    case NOTIFICATION_PROCESSING_STOP:
                        long frequencyToRemove = 0;

                        mLock.lock();

                        try
                        {
                            for(Map.Entry<Long,Channel> entry: mAllocatedChannelFrequencyMap.entrySet())
                            {
                                if(channel == entry.getValue() && entry.getKey() != null)
                                {
                                    frequencyToRemove = entry.getKey();
                                    removeCallEvents(entry.getKey());
                                    break;
                                }
                            }

                            if(frequencyToRemove > 0)
                            {
                                mAllocatedChannelFrequencyMap.remove(frequencyToRemove, channel);
                                restoreCurrentControlAllocation(frequencyToRemove);

                                //Unlock the frequency in the channel rotation monitor
                                EventBus eventBus = getInterModuleEventBus();

                                if(eventBus != null && getCurrentControlFrequency() != frequencyToRemove)
                                {
                                    eventBus.post(FrequencyLockChangeRequest.unlock(frequencyToRemove));

                                    if(getCurrentControlFrequency() == frequencyToRemove)
                                    {
                                        eventBus.post(FrequencyLockChangeRequest.lock(frequencyToRemove));
                                    }
                                }
                            }

                            //Add the traffic channel back to the queue to be reused
                            if(!mAvailableTrafficChannels.contains(channel))
                            {
                                mAvailableTrafficChannels.add(channel);
                            }
                        }
                        finally
                        {
                            mLock.unlock();
                        }
                        break;
                    case NOTIFICATION_PROCESSING_START_REJECTED:
                        mLock.lock();
                        try
                        {
                            long frequencyToUpdate = 0;

                            for(Map.Entry<Long,Channel> entry: mAllocatedChannelFrequencyMap.entrySet())
                            {
                                if(channel == entry.getValue() && entry.getKey() != null)
                                {
                                    frequencyToUpdate = entry.getKey();
                                    setChannelStartRejected(entry.getKey());
                                    break;
                                }
                            }

                            if(frequencyToUpdate > 0)
                            {
                                mAllocatedChannelFrequencyMap.remove(frequencyToUpdate, channel);
                                restoreCurrentControlAllocation(frequencyToUpdate);

                                //Unlock the frequency in the channel rotation monitor
                                EventBus eventBus = getInterModuleEventBus();

                                if(eventBus != null && getCurrentControlFrequency() != frequencyToUpdate)
                                {
                                    eventBus.post(FrequencyLockChangeRequest.unlock(frequencyToUpdate));

                                    if(getCurrentControlFrequency() == frequencyToUpdate)
                                    {
                                        eventBus.post(FrequencyLockChangeRequest.lock(frequencyToUpdate));
                                    }
                                }
                            }

                            //Add the traffic channel back to the queue to be reused
                            if(!mAvailableTrafficChannels.contains(channel))
                            {
                                mAvailableTrafficChannels.add(channel);
                            }
                        }
                        finally
                        {
                            mLock.unlock();
                        }
                        break;
                    default:
                        break;
                }
            }
        }
    }
}
