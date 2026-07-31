/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
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

package io.github.dsheirer.module.decode.nxdn;

import io.github.dsheirer.channel.IChannelDescriptor;
import io.github.dsheirer.channel.metadata.activity.ChannelActivityModel;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelEvent;
import io.github.dsheirer.controller.channel.IChannelEventListener;
import io.github.dsheirer.controller.channel.IChannelEventProvider;
import io.github.dsheirer.controller.channel.event.ChannelStartProcessingRequest;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.alias.TalkerAliasManager;
import io.github.dsheirer.identifier.encryption.EncryptionKeyIdentifier;
import io.github.dsheirer.identifier.radio.RadioIdentifier;
import io.github.dsheirer.module.decode.event.DecodeEvent;
import io.github.dsheirer.module.decode.event.DecodeEventDuplicateDetector;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.event.IDecodeEvent;
import io.github.dsheirer.module.decode.event.IDecodeEventProvider;
import io.github.dsheirer.module.decode.nxdn.channel.ChannelFrequency;
import io.github.dsheirer.module.decode.nxdn.channel.NXDNChannel;
import io.github.dsheirer.module.decode.nxdn.channel.NXDNChannelDFA;
import io.github.dsheirer.module.decode.nxdn.channel.NXDNChannelFake;
import io.github.dsheirer.module.decode.nxdn.channel.NXDNChannelLookup;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNRadioIdentifier;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNTalkerAliasIdentifier;
import io.github.dsheirer.module.decode.nxdn.layer3.call.DataCallAssignment;
import io.github.dsheirer.module.decode.nxdn.layer3.call.VoiceCall;
import io.github.dsheirer.module.decode.nxdn.layer3.call.VoiceCallAssignment;
import io.github.dsheirer.module.decode.nxdn.layer3.call.VoiceCallAssignmentDuplicateTraffic;
import io.github.dsheirer.module.decode.nxdn.layer3.type.AudioCodec;
import io.github.dsheirer.module.decode.nxdn.layer3.type.CallTimer;
import io.github.dsheirer.module.decode.nxdn.layer3.type.CallType;
import io.github.dsheirer.module.decode.nxdn.layer3.type.ChannelAccessInformation;
import io.github.dsheirer.module.decode.nxdn.layer3.type.DataCallOption;
import io.github.dsheirer.module.decode.nxdn.layer3.type.Duplex;
import io.github.dsheirer.module.decode.nxdn.layer3.type.TransmissionMode;
import io.github.dsheirer.module.decode.nxdn.layer3.type.VoiceCallOption;
import io.github.dsheirer.module.decode.traffic.TrafficChannelManager;
import io.github.dsheirer.module.decode.traffic.TrunkedCallStartEvent;
import io.github.dsheirer.module.decode.traffic.TrunkedCallStartTracker;
import io.github.dsheirer.module.decode.traffic.TrunkedIdentityDomain;
import io.github.dsheirer.module.decode.traffic.TrunkedTalkerAliasEvent;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.source.config.SourceConfigTuner;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages trunked system traffic channel activations
 */
public class NXDNTrafficChannelManager extends TrafficChannelManager implements IDecodeEventProvider,
        IChannelEventListener, IChannelEventProvider
{
    private static final Logger LOGGER = LoggerFactory.getLogger(NXDNTrafficChannelManager.class);
    public static final String MAX_TRAFFIC_CHANNELS_EXCEEDED = "MAX TRAFFIC CHANNELS EXCEEDED";
    public static final String CHANNEL_START_REJECTED = "CHANNEL START REJECTED";
    static final long MAX_ACTIVITY_PROGRESS_INTERVAL_MILLISECONDS = 500L;
    private final Channel mParentChannel;
    private final DecodeEventDuplicateDetector mDuplicateDetector = new DecodeEventDuplicateDetector();
    private final List<ChannelFrequency> mChannelFrequencies = new ArrayList<>();
    private final Lock mLock = new ReentrantLock();
    private final Map<Long, Channel> mAllocatedTrafficChannelMap = new HashMap<>();
    private final Map<Long, NXDNChannelEventTracker> mEventTrackerMap = new HashMap<>();
    private final Map<Long, Long> mLastActivityProgressMap = new HashMap<>();
    private final TrunkedCallStartTracker mCallStartTracker = new TrunkedCallStartTracker(3000);
    private final Queue<Channel> mAvailableTrafficChannelQueue = new LinkedTransferQueue<>();
    private final TalkerAliasManager mTalkerAliasManager = new TalkerAliasManager();
    private final TrafficChannelTeardownMonitor mTrafficChannelTeardownMonitor = new TrafficChannelTeardownMonitor();
    private List<Channel> mManagedTrafficChannels;
    private Listener<IDecodeEvent> mDecodeEventListener;
    private Listener<ChannelEvent> mChannelEventListener;
    private ChannelAccessInformation mChannelAccessInformation;
    private boolean mIgnoreDataCalls;
    private boolean mIgnoreEncryptedCalls;
    private final boolean mTrunkingEnabled;
    private ChannelActivityModel mChannelActivityModel;
    private volatile boolean mTrunkedActivityObserved;

    /**
     * Constructs an instance
     *
     * @param parentChannel configuration for the parent channel
     */
    public NXDNTrafficChannelManager(Channel parentChannel)
    {
        mParentChannel = parentChannel;
        mTrunkingEnabled = parentChannel.getDecodeConfiguration() instanceof DecodeConfigNXDN configNXDN &&
            configNXDN.isTrunked();

        if(parentChannel.getDecodeConfiguration() instanceof DecodeConfigNXDN configNXDN)
        {
            mIgnoreDataCalls = configNXDN.isIgnoreDataCalls();
            mIgnoreEncryptedCalls = configNXDN.isIgnoreEncryptedCalls();
            mChannelFrequencies.addAll(configNXDN.getChannelMap());
            createTrafficChannels(configNXDN);
        }
    }

    /**
     * Talker alias manager
     */
    public TalkerAliasManager getTalkerAliasManager()
    {
        return mTalkerAliasManager;
    }

    /**
     * Shared activity model used by the desktop and web Systems views.
     */
    public void setChannelActivityModel(ChannelActivityModel channelActivityModel)
    {
        mChannelActivityModel = channelActivityModel;
    }

    /**
     * Sets the channel access info from the control channel.
     *
     * @param info from the control channel
     */
    public void setChannelAccessInformation(ChannelAccessInformation info)
    {
        if(mChannelAccessInformation == null)
        {
            mChannelAccessInformation = info;
        }
    }

    /**
     * Creates up to the maximum number of traffic channels for use in allocating traffic channels.
     *
     * @param decodeConfig to use for each traffic channel
     */
    private void createTrafficChannels(DecodeConfigNXDN decodeConfig)
    {
        if(mManagedTrafficChannels == null)
        {
            int trafficChannelPoolSize = decodeConfig.getTrafficChannelPoolSize();
            List<Channel> trafficChannelList = new ArrayList<>();

            if(mTrunkingEnabled && trafficChannelPoolSize > 0)
            {
                for(int x = 0; x < trafficChannelPoolSize; x++)
                {
                    Channel trafficChannel = new Channel("T-" + mParentChannel.getName(), Channel.ChannelType.TRAFFIC);
                    trafficChannel.setAliasListName(mParentChannel.getAliasListName());
                    trafficChannel.setSystem(mParentChannel.getSystem());
                    trafficChannel.setSite(mParentChannel.getSite());
                    trafficChannel.setConfigurationId(mParentChannel.getConfigurationId());
                    trafficChannel.setRadresGuid(mParentChannel.getRadresGuid());
                    trafficChannel.setDecodeConfiguration(copyDecodeConfiguration(decodeConfig));
                    trafficChannel.setEventLogConfiguration(mParentChannel.getEventLogConfiguration());
                    trafficChannel.setRecordConfiguration(mParentChannel.getRecordConfiguration());
                    trafficChannelList.add(trafficChannel);
                }
            }

            mAvailableTrafficChannelQueue.addAll(trafficChannelList);
            mManagedTrafficChannels = Collections.unmodifiableList(trafficChannelList);
        }
    }

    /**
     * Creates an independent configuration for each pooled traffic channel.  DFA grants can specify a different
     * transmission mode for each allocation, so sharing the parent configuration would also mutate the control
     * channel and every other channel in the pool.
     */
    private static DecodeConfigNXDN copyDecodeConfiguration(DecodeConfigNXDN source)
    {
        DecodeConfigNXDN copy = new DecodeConfigNXDN(source.getTransmissionMode());
        copy.setChannelMode(source.getChannelMode());
        copy.setTrafficChannelPoolSize(source.getTrafficChannelPoolSize());
        copy.setIgnoreDataCalls(source.isIgnoreDataCalls());
        copy.setIgnoreEncryptedCalls(source.isIgnoreEncryptedCalls());
        copy.setChannelMap(new ArrayList<>(source.getChannelMap()));
        copy.setEncoding(source.getEncoding());
        return copy;
    }

    /**
     * Retrieves the current event tracker for the specified channel and if the tracker is stale relative to the
     * timestamp, returns null, otherwise returns the current tracker.
     *
     * @param frequency to look up the tracker
     * @param timestamp to compare for staleness
     * @return tracker or null
     */
    private NXDNChannelEventTracker getTrackerRemoveIfStale(long frequency, long timestamp)
    {
        NXDNChannelEventTracker tracker = mEventTrackerMap.get(frequency);

        if(tracker != null && tracker.isStale(timestamp))
        {
            removeTracker(frequency);
            tracker = null;
        }

        return tracker;
    }

    /**
     * Broadcasts the decode event from the tracker.
     *
     * @param tracker containing a decode event.
     */
    public void broadcast(NXDNChannelEventTracker tracker)
    {
        broadcast(tracker.getEvent());
    }

    /**
     * Broadcasts an initial or update decode event to any registered listener.
     */
    public void broadcast(DecodeEvent decodeEvent)
    {
        broadcast(decodeEvent, true);
    }

    private void broadcast(DecodeEvent decodeEvent, boolean publishActivity)
    {
        if(publishActivity)
        {
            publishChannelActivity(decodeEvent);
        }

        if(mDecodeEventListener != null)
        {
            if(decodeEvent.getEventType() == DecodeEventType.DATA_CALL && mDuplicateDetector.isDuplicate(decodeEvent,
                    System.currentTimeMillis()))
            {
                return;
            }

            mDecodeEventListener.receive(decodeEvent);
        }
        else
        {
            System.out.println("Decode event listener is null");
        }
    }

    private void publishChannelActivity(DecodeEvent decodeEvent)
    {
        if(!mTrunkedActivityObserved || mChannelActivityModel == null || decodeEvent == null ||
            decodeEvent.getChannelDescriptor() == null)
        {
            return;
        }

        if(decodeEvent.getChannelDescriptor() instanceof NXDNChannel nxdnChannel && !nxdnChannel.isValid())
        {
            return;
        }

        long frequency = decodeEvent.getChannelDescriptor().getDownlinkFrequency();
        Channel trafficChannel = mAllocatedTrafficChannelMap.get(frequency);
        mChannelActivityModel.trunkedTrafficEvent(mParentChannel, trafficChannel,
            decodeEvent.getChannelDescriptor(), null, decodeEvent.getIdentifierCollection(), decodeEvent.getEventType(),
            getCurrentControlFrequency());
    }

    /**
     * Selects the decode event type based on the call type and encryption.
     *
     * @param callType for the call
     * @param encryption for the call
     * @return decode event type
     */
    private DecodeEventType getType(CallType callType, EncryptionKeyIdentifier encryption)
    {
        boolean encrypted = encryption.isEncrypted();
        return switch(callType)
        {
            case GROUP_BROADCAST, GROUP_CONFERENCE ->
                    encrypted ? DecodeEventType.CALL_GROUP_ENCRYPTED : DecodeEventType.CALL_GROUP;
            case INDIVIDUAL ->
                    encrypted ? DecodeEventType.CALL_UNIT_TO_UNIT_ENCRYPTED : DecodeEventType.CALL_UNIT_TO_UNIT;
            case INTERCONNECT, SPEED_DIAL ->
                    encrypted ? DecodeEventType.CALL_INTERCONNECT_ENCRYPTED : DecodeEventType.CALL_INTERCONNECT;
            default -> encrypted ? DecodeEventType.CALL_ENCRYPTED : DecodeEventType.CALL;
        };
    }

    /**
     * Creates a traffic channel event tracker
     *
     * @param eventType for the decode event
     * @param ic collection of from/to identifiers
     * @param channel to track
     * @param timestamp for the event
     * @return tracker
     */
    private NXDNChannelEventTracker createTracker(DecodeEventType eventType, IdentifierCollection ic,
                                                  NXDNChannel channel, long timestamp)
    {
        long frequency = channel != null ? channel.getDownlinkFrequency() : 0;

        DecodeEvent event = NXDNDecodeEvent.builder(eventType, timestamp)
                .timeslot(0)
                .channel(channel)
                .identifiers(ic)
                .build();
        NXDNChannelEventTracker tracker = new NXDNChannelEventTracker(event);
        mEventTrackerMap.put(frequency, tracker);
        return tracker;
    }

    /**
     * Process a data call assignment
     *
     * @param dca to process
     */
    public void processDataCallAssignment(DataCallAssignment dca)
    {
        if(mTrunkingEnabled && dca.hasChannel() && dca.getChannel().getDownlinkFrequency() > 0)
        {
            mTrunkedActivityObserved = true;
            DecodeEventType eventType = dca.getEncryptionKeyIdentifier().isEncrypted() ?
                    DecodeEventType.DATA_CALL_ENCRYPTED : DecodeEventType.DATA_CALL;
            processDataCall(dca.getIdentifiers(), dca.getChannel(), eventType, dca.getTimestamp(), dca.getCallOption(),
                    dca.getCallTimer());
        }
    }

    /**
     * Process a voice call assignment or notification
     * @param identifiers for the call
     * @param channel for the call
     */
    private void processDataCall(List<Identifier> identifiers, NXDNChannel channel, DecodeEventType eventType,
                                 long timestamp, DataCallOption dco, CallTimer callTimer)
    {
        if(channel.getDownlinkFrequency() > 0)
        {
            mLock.lock();

            try
            {
                NXDNChannelEventTracker tracker = getTrackerRemoveIfStale(channel.getDownlinkFrequency(), timestamp);
                MutableIdentifierCollection ic = new MutableIdentifierCollection(identifiers);
                if(tracker != null)
                {
                    if(tracker.isSameCallCheckingToAndFrom(ic, timestamp))
                    {
                        tracker.updateDurationControl(timestamp);
                        broadcast(tracker);
                    }
                    else
                    {
                        removeTracker(channel.getDownlinkFrequency());
                        tracker = null;
                    }
                }

                if(tracker == null)
                {
                    tracker = createTracker(eventType, ic, channel, timestamp);
                    Duplex duplex = dco.getDuplex();
                    TransmissionMode mode = dco.getTransmissionMode();
                    tracker.addDetails("TIMER:" + callTimer + " " + duplex + " " + mode);
                }

                if(mIgnoreDataCalls)
                {
                    tracker.prefixDetails("IGNORED DATA");
                }
                else
                {
                    if(!tracker.isTrafficChannelAllocated() && channel.isValid() &&
                            channel.getDownlinkFrequency() != getCurrentControlFrequency() &&
                            !mAllocatedTrafficChannelMap.containsKey(channel.getDownlinkFrequency()))
                    {
                        //Retrieve a channel from the traffic channel queue
                        Channel traffic = mAvailableTrafficChannelQueue.poll();

                        if(traffic != null)
                        {
                            requestTrafficChannelStart(traffic, channel, ic, tracker);
                        }
                        else
                        {
                            tracker.addDetails(MAX_TRAFFIC_CHANNELS_EXCEEDED + " " + tracker.getEvent().getDetails());
                        }
                    }
                }

                broadcast(tracker);
            }
            finally
            {
                mLock.unlock();
            }
        }
    }

    /**
     * Process a voice call assignment indicating there is a call on another channel.
     *
     * @param vca to process
     */
    public void processVoiceCallAssignment(VoiceCallAssignment vca)
    {
        if(mTrunkingEnabled && vca.hasChannel())
        {
            mTrunkedActivityObserved = true;
            processVoiceCall(vca.getIdentifiers(), vca.getChannel(), vca.getCallType(), vca.getEncryptionKeyIdentifier(),
                    vca.getTimestamp(), vca.getCallOption(), vca.getCallTimer());
        }
    }

    /**
     * Process a voice call assignment duplicate notification from a traffic channel
     *
     * @param vca to process
     */
    public void processVoiceCallAssignment(VoiceCallAssignmentDuplicateTraffic vca)
    {
        if(!mTrunkingEnabled)
        {
            return;
        }

        NXDNChannel channel = vca.getChannel();

        if(channel == null || channel.getDownlinkFrequency() == 0)
        {
            //If the channel is null or not configured, use the talkgroup value so we can track the call event
            channel = new NXDNChannelFake(vca.getDestination().getValue());
        }
        else
        {
            mTrunkedActivityObserved = true;
        }

        processVoiceCall(vca.getIdentifiers(), channel, vca.getCallType(), vca.getEncryptionKeyIdentifier(),
                vca.getTimestamp(), vca.getCallOption(), vca.getCallTimer());
    }

    /**
     * Process a traffic channel voice call message
     * @param voiceCall message
     * @param channel for the call, can be null
     */
    public void processVoiceCall(VoiceCall voiceCall, IChannelDescriptor channel)
    {
        NXDNChannel nxdn = null;
        if(channel instanceof NXDNChannel nxdnChannel)
        {
            nxdn = nxdnChannel;
        }

        processVoiceCall(voiceCall.getIdentifiers(), nxdn, voiceCall.getCallType(), voiceCall.getEncryptionKeyIdentifier(),
                voiceCall.getTimestamp(), voiceCall.getCallOption(), CallTimer.UNSPECIFIED);
    }

    /**
     * Adds or updates the talker alias for the tracked call event.
     * @param channel for the call
     * @param talkerAlias to add or update
     * @param timestamp for the talker alias message
     */
    public void processTalkerAlias(IChannelDescriptor channel, NXDNTalkerAliasIdentifier talkerAlias,
                                   RadioIdentifier radio, long timestamp)
    {
        if(talkerAlias == null || talkerAlias.getValue() == null ||
            talkerAlias.getValue().toString().isBlank() || radio == null || radio.getRole() != Role.FROM)
        {
            return;
        }

        mLock.lock();

        try
        {
            long frequency = channel != null ? channel.getDownlinkFrequency() : 0;
            NXDNChannelEventTracker tracker = getTrackerRemoveIfStale(frequency, timestamp);
            IdentifierCollection context = new IdentifierCollection(List.of(radio, talkerAlias));

            if(tracker != null)
            {
                tracker.addIdentifierIfMissing(talkerAlias);
                context = new IdentifierCollection(tracker.getEvent().getIdentifierCollection().getIdentifiers());
                broadcast(tracker);
            }

            getTalkerAliasManager().update(radio, talkerAlias);

            if(mTrunkingEnabled)
            {
                MyEventBus.getGlobalEventBus().post(new TrunkedTalkerAliasEvent(mParentChannel, Protocol.NXDN,
                    radio, talkerAlias, context, talkerAliasIdentityDomain(radio),
                    timestamp > 0 ? timestamp : System.currentTimeMillis()));
            }
        }
        finally
        {
            mLock.unlock();
        }
    }

    private TrunkedIdentityDomain talkerAliasIdentityDomain(RadioIdentifier radio)
    {
        if(radio instanceof NXDNRadioIdentifier nxdnRadio && nxdnRadio.isTypeD() ||
            mParentChannel.getDecodeConfiguration() instanceof DecodeConfigNXDN config &&
                config.getTransmissionMode() != null && config.getTransmissionMode().isTypeD())
        {
            return TrunkedIdentityDomain.NXDN_TYPE_D;
        }

        return TrunkedIdentityDomain.NXDN_TYPE_C;
    }

    /**
     * Updates the end time for an ongoing call to reflect progress
     * @param channel descriptor for the traffic channel
     */
    public void processCallProgressUpdate(IChannelDescriptor channel, long timestamp)
    {
        mLock.lock();
        try
        {
            long frequency = channel != null ? channel.getDownlinkFrequency() : 0;
            NXDNChannelEventTracker tracker = getTrackerRemoveIfStale(frequency, timestamp);

            if(tracker != null)
            {
                tracker.updateDurationTraffic(timestamp);
                mCallStartTracker.touch(channel, null, timestamp);
                //Duration-only audio frame updates still belong in decode-event history.  Rate-limit Systems updates
                //so active calls keep their grant age-out alive without queuing a full snapshot for every audio frame.
                broadcast(tracker.getEvent(), shouldPublishActivityProgress(frequency, timestamp));
            }
        }
        finally
        {
            mLock.unlock();
        }
    }

    boolean shouldPublishActivityProgress(long frequency, long timestamp)
    {
        Long previous = mLastActivityProgressMap.get(frequency);

        if(previous == null || timestamp < previous ||
            timestamp - previous >= getActivityProgressIntervalMilliseconds())
        {
            mLastActivityProgressMap.put(frequency, timestamp);
            return true;
        }

        return false;
    }

    /**
     * Keeps progress snapshots bounded while ensuring that an active call is refreshed before the Systems row's
     * configured traffic-grant age-out expires.
     */
    long getActivityProgressIntervalMilliseconds()
    {
        if(mChannelActivityModel != null)
        {
            return Math.max(1L, Math.min(MAX_ACTIVITY_PROGRESS_INTERVAL_MILLISECONDS,
                mChannelActivityModel.getTrafficGrantAgeOutMilliseconds() / 2L));
        }

        return MAX_ACTIVITY_PROGRESS_INTERVAL_MILLISECONDS;
    }

    /**
     * Ends the current call and removes the event tracker
     * @param channel for the call
     * @param timestamp for the end call
     */
    public void processEndCall(IChannelDescriptor channel, long timestamp)
    {
        mLock.lock();

        try
        {
            long frequency = channel != null ? channel.getDownlinkFrequency() : 0;
            NXDNChannelEventTracker tracker = getTrackerRemoveIfStale(frequency, timestamp);

            if(tracker != null)
            {
                tracker.completeTraffic(timestamp);
                broadcast(tracker);
                removeTracker(frequency);
            }

            mCallStartTracker.end(channel, null, timestamp);
        }
        finally
        {
            mLock.unlock();
        }
    }

    /**
     * Process a voice call assignment or notification
     * @param identifiers for the call
     * @param channel for the call
     */
    void processVoiceCall(List<Identifier> identifiers, NXDNChannel channel, CallType callType,
                          EncryptionKeyIdentifier encryption, long timestamp, VoiceCallOption vco,
                          CallTimer callTimer)
    {
        if(!mTrunkingEnabled)
        {
            return;
        }

        mLock.lock();

        long frequency = channel != null ? channel.getDownlinkFrequency() : 0;

        try
        {
            NXDNChannelEventTracker tracker = getTrackerRemoveIfStale(frequency, timestamp);
            MutableIdentifierCollection ic = new MutableIdentifierCollection(identifiers);
            mTalkerAliasManager.enrich(ic);
            DecodeEventType eventType = getType(callType, encryption);
            TrunkedCallStartTracker.ObservationResult callObservation = isLogicalVoiceChannel(channel) ?
                mCallStartTracker.observeWithAttribution(mParentChannel, Protocol.NXDN, channel, null, ic,
                    eventType, timestamp) : new TrunkedCallStartTracker.ObservationResult(null, null);
            TrunkedCallStartEvent callStart = callObservation.callStart();

            if(callStart != null)
            {
                MyEventBus.getGlobalEventBus().post(callStart);
            }

            if(callObservation.attribution() != null)
            {
                MyEventBus.getGlobalEventBus().post(callObservation.attribution());
            }

            if(tracker != null)
            {
                if(tracker.isSameCallCheckingToAndFrom(ic, timestamp))
                {
                    tracker.updateDurationControl(timestamp);
                }
                else
                {
                    removeTracker(frequency);
                    tracker = null;
                }
            }

            if(tracker == null)
            {
                tracker = createTracker(eventType, ic, channel, timestamp);
                AudioCodec audioCodec = vco.getCodec();
                TransmissionMode mode = vco.getTransmissionMode();

                if(encryption.isEncrypted())
                {
                    tracker.addDetails(encryption + " TIMER:" + callTimer + " " + audioCodec + " " + mode);
                }
                else
                {
                    tracker.addDetails("TIMER:" + callTimer + " " + audioCodec + " " + mode);
                }
            }

            boolean ignoreEncrypted = mIgnoreEncryptedCalls && encryption.isEncrypted();

            if(ignoreEncrypted)
            {
                tracker.prefixDetails("IGNORED ENCRYPTED");
            }
            else if(!tracker.isTrafficChannelAllocated() && channel != null && channel.isValid() &&
                    channel.getDownlinkFrequency() != getCurrentControlFrequency() &&
                    !mAllocatedTrafficChannelMap.containsKey(frequency))
            {
                //Retrieve a channel from the traffic channel queue
                Channel traffic = mAvailableTrafficChannelQueue.poll();

                if(traffic != null)
                {
                    requestTrafficChannelStart(traffic, channel, ic, tracker);
                }
                else
                {
                    tracker.addDetails(MAX_TRAFFIC_CHANNELS_EXCEEDED + " " + tracker.getEvent().getDetails());
                }
            }

            broadcast(tracker);
        }
        finally
        {
            mLock.unlock();
        }
    }

    /**
     * Lookup and DFA assignments have a stable logical channel identity before their frequency maps resolve. Fake
     * channels are duplicate-traffic placeholders and must never create another physical call.
     */
    private static boolean isLogicalVoiceChannel(NXDNChannel channel)
    {
        return channel instanceof NXDNChannelLookup || channel instanceof NXDNChannelDFA;
    }

    /**
     * Sends a channel start request to the ChannelProcessingManager.
     * Note: this method is not thread safe and the calling method must protect access using mLock.
     *
     * @param trafficChannel to use for the traffic channel
     * @param nxdnChannel that describes the traffic channel downlink frequency
     * @param ic containing identifiers for the call
     * @param tracker to update with channel allocation flag
     */
    private void requestTrafficChannelStart(Channel trafficChannel, NXDNChannel nxdnChannel,
                                            IdentifierCollection ic, NXDNChannelEventTracker tracker)
    {
        if(nxdnChannel != null && nxdnChannel.getDownlinkFrequency() > 0 && getInterModuleEventBus() != null)
        {
            if(trafficChannel.getDecodeConfiguration() instanceof DecodeConfigNXDN trafficConfig &&
                mParentChannel.getDecodeConfiguration() instanceof DecodeConfigNXDN parentConfig)
            {
                TransmissionMode mode = parentConfig.getTransmissionMode();

                if(nxdnChannel instanceof NXDNChannelDFA dfa && dfa.getBandwidth() != null)
                {
                    mode = dfa.getBandwidth().getTransmissionMode();
                }

                trafficConfig.setTransmissionMode(mode);
            }

            SourceConfigTuner sourceConfig = new SourceConfigTuner();
            sourceConfig.setFrequency(nxdnChannel.getDownlinkFrequency());
            if(mParentChannel.getSourceConfiguration() instanceof SourceConfigTuner parentConfigTuner)
            {
                sourceConfig.setPreferredTuner(parentConfigTuner.getPreferredTuner());
            }
            trafficChannel.setSourceConfiguration(sourceConfig);
            mAllocatedTrafficChannelMap.put(nxdnChannel.getDownlinkFrequency(), trafficChannel);
            ChannelStartProcessingRequest startChannelRequest = new ChannelStartProcessingRequest(trafficChannel,
                    nxdnChannel, ic, this);
            startChannelRequest.addPreloadDataContent(new NXDNChannelInfoPreloadData(mChannelAccessInformation, mChannelFrequencies));
            getInterModuleEventBus().post(startChannelRequest);
            tracker.setTrafficChannelAllocated(true);
        }
        else
        {
            //Return the channel to the traffic channel pool if we didn't start it.
            mAvailableTrafficChannelQueue.add(trafficChannel);
        }
    }

    @Override
    public Listener<ChannelEvent> getChannelEventListener()
    {
        return mTrafficChannelTeardownMonitor;
    }

    /**
     * Broadcasts the channel event ot an optionally registered listener
     * @param event to broadcast
     */
    private void broadcast(ChannelEvent event)
    {
        if(mChannelEventListener != null)
        {
            mChannelEventListener.receive(event);
        }
    }

    @Override
    public void setChannelEventListener(Listener<ChannelEvent> listener)
    {
        mChannelEventListener = listener;
    }

    @Override
    public void removeChannelEventListener()
    {
        mChannelEventListener = null;
    }

    /**
     * Process the current control frequency to ensure we don't allocated traffic channels for the same frequency.
     * @param previous frequency for the control channel (to remove from allocated channels)
     * @param current frequency for the control channel (to add to allocated channels)
     * @param channel for the current control channel
     */
    @Override
    protected void processControlFrequencyUpdate(long previous, long current, Channel channel)
    {
        if(!mTrunkingEnabled)
        {
            return;
        }

        mLock.lock();

        try
        {
            //Clear any traffic channel that is already allocated on the new/current control frequency
            if(mAllocatedTrafficChannelMap.containsKey(current))
            {
                broadcast(new ChannelEvent(mAllocatedTrafficChannelMap.get(current), ChannelEvent.Event.REQUEST_DISABLE));
            }
        }
        finally
        {
            mLock.unlock();
        }

        if(mTrunkedActivityObserved && mChannelActivityModel != null)
        {
            mChannelActivityModel.trunkedCurrentControl(mParentChannel, current);
        }

    }

    @Override
    public void reset()
    {
        mCallStartTracker.clear();
    }

    @Override
    public void start()
    {

    }

    @Override
    public void stop()
    {
        mLock.lock();

        try
        {
            mAvailableTrafficChannelQueue.clear();
            mCallStartTracker.clear();

            List<Channel> channels = new ArrayList<>(mAllocatedTrafficChannelMap.values());

            //Issue a disable request for each traffic channel
            for(Channel channel: channels)
            {
                LOGGER.info("Stopping NXDN traffic channel: " + channel);
                broadcast(new ChannelEvent(channel, ChannelEvent.Event.REQUEST_DISABLE));
            }
        }
        finally
        {
            mLock.unlock();
        }
    }

    @Override
    public void addDecodeEventListener(Listener<IDecodeEvent> listener)
    {
        mDecodeEventListener = listener;
    }

    @Override
    public void removeDecodeEventListener(Listener<IDecodeEvent> listener)
    {
        mDecodeEventListener = null;
    }

    /**
     * Remotes the event tracker from the tracker map
     * @param frequency for the tracker
     */
    private void removeTracker(long frequency)
    {
        mLock.lock();

        try
        {
            mEventTrackerMap.remove(frequency);
            mLastActivityProgressMap.remove(frequency);
        }
        finally
        {
            mLock.unlock();
        }
    }

    /**
     * Monitors channel teardown events to detect when traffic channel processing has ended or channel start has been
     * rejected.  Reclaims the traffic channel instance for reuse by future traffic channel grants.
     */
    public class TrafficChannelTeardownMonitor implements Listener<ChannelEvent>
    {
        /**
         * Process channel events from the ChannelProcessingManager to account for owned child traffic channels.
         * Note: this method sees events for ALL channels and not just P25 channels managed by this instance.
         *
         * @param channelEvent to process
         */
        @Override
        public void receive(ChannelEvent channelEvent)
        {
            Channel channel = channelEvent.getChannel();

            if(mManagedTrafficChannels.contains(channel))
            {
                mLock.lock();

                try
                {
                    switch(channelEvent.getEvent())
                    {
                        case NOTIFICATION_PROCESSING_STOP:
                            mAllocatedTrafficChannelMap.entrySet()
                                    .stream()
                                    .filter(entry -> entry.getValue() == channel)
                                    .map(Map.Entry::getKey)
                                    .findFirst()
                                    .ifPresent(frequency -> {
                                        mAllocatedTrafficChannelMap.remove(frequency);
                                        //Don't remove the tracker.  There's a chance the control channel can still
                                        //reference the now terminating traffic channel and cause a residual event
                                        //creation and channel allocation and we'll use the tracker to keep that from
                                        //happening.
                                        //removeTracker(frequency);
                                        mAvailableTrafficChannelQueue.add(channel);
                                    });
                            break;
                        case NOTIFICATION_PROCESSING_START_REJECTED:
                            mAllocatedTrafficChannelMap.entrySet().stream()
                                    .filter(entry -> entry.getValue() == channel)
                                    .map(Map.Entry::getKey)
                                    .findFirst()
                                    .ifPresent(rejectedFrequency -> {
                                        mAllocatedTrafficChannelMap.remove(rejectedFrequency);
                                        mAvailableTrafficChannelQueue.add(channel);

                                        //Leave the event in the map so that it doesn't get recreated.  The channel
                                        //processing manager set the 'tuner not available' in the details already
                                        NXDNChannelEventTracker tracker = mEventTrackerMap.get(rejectedFrequency);

                                        if(tracker != null)
                                        {
                                            if(!tracker.getEvent().getDetails().contains(CHANNEL_START_REJECTED))
                                            {
                                                tracker.addDetails(CHANNEL_START_REJECTED + " " + channelEvent.getDescription() +
                                                        (tracker.getEvent().getDetails() != null ? " - " + tracker.getEvent().getDetails() : ""));
                                            }
                                            tracker.setTrafficChannelAllocated(false);
                                            broadcast(tracker);
                                        }
                                    });
                            break;
                    }
                }
                finally
                {
                    mLock.unlock();
                }
            }
        }
    }

}
