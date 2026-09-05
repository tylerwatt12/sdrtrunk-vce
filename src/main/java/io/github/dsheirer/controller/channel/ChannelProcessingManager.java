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
package io.github.dsheirer.controller.channel;

import com.google.common.eventbus.Subscribe;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.audio.call.AudioCallEvent;
import io.github.dsheirer.channel.metadata.ChannelMetadata;
import io.github.dsheirer.channel.metadata.activity.ChannelActivityModel;
import io.github.dsheirer.configuration.ChannelConfigurationPolicy;
import io.github.dsheirer.channel.quality.ControlChannelQualityMonitor;
import io.github.dsheirer.channel.quality.ControlChannelQualitySnapshot;
import io.github.dsheirer.channel.state.AbstractChannelState;
import io.github.dsheirer.controller.channel.event.ChannelStartProcessingRequest;
import io.github.dsheirer.controller.channel.event.ChannelStopProcessingRequest;
import io.github.dsheirer.controller.channel.event.PostChannelModuleEventRequest;
import io.github.dsheirer.controller.channel.event.PreloadDataContent;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.IdentifierUpdateNotification;
import io.github.dsheirer.identifier.decoder.DecoderLogicalChannelNameIdentifier;
import io.github.dsheirer.identifier.decoder.TrafficChannelIdentifier;
import io.github.dsheirer.metadata.site.ProtocolSiteMetadataEvent;
import io.github.dsheirer.metadata.site.ProtocolSiteMetadataListener;
import io.github.dsheirer.metadata.site.SiteMetadataEvent;
import io.github.dsheirer.metadata.site.SiteMetadataListener;
import io.github.dsheirer.module.Module;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.module.decode.DecoderFactory;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.dmr.DMRChannelConfigurationTransitionNotification;
import io.github.dsheirer.module.decode.dmr.DMRRestChannelHandoffRequest;
import io.github.dsheirer.module.decode.dmr.DMRTrafficChannelManager;
import io.github.dsheirer.module.decode.dmr.DMRTrafficChannelManager.PreparedRestChannelHandoff;
import io.github.dsheirer.module.decode.event.IDecodeEvent;
import io.github.dsheirer.module.log.EventLogManager;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.record.RecorderFactory;
import io.github.dsheirer.sample.Broadcaster;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.source.Source;
import io.github.dsheirer.source.SourceEvent;
import io.github.dsheirer.source.SourceException;
import io.github.dsheirer.source.config.SourceConfigTuner;
import io.github.dsheirer.source.config.SourceConfigTunerMultipleFrequency;
import io.github.dsheirer.source.tuner.channel.TunerChannelSource;
import io.github.dsheirer.source.tuner.channel.rotation.ChannelRotationMonitorPauseRequest;
import io.github.dsheirer.source.tuner.channel.rotation.ChannelRotationMonitorResumeRequest;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import io.github.dsheirer.util.ThreadPool;
import io.github.dsheirer.util.concurrent.ObserverThreadFactory;
import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Channel processing manager handles all starting and stopping of channel decoding.  A processing chain is created
 * for each channel that is enabled.  The processing chain contains all of the components needed to decode a specific
 * channel and protocol along with all logging and baseband or bitstream recording.  Audio recording is handled outside
 * of this class by the RecorderManager.
 */
public class ChannelProcessingManager implements Listener<ChannelEvent>
{
    private static final String DIVIDER = "-------------------------------------------------------------------------\n";
    private static final String ERROR_STOPPING_CHANNEL_LABEL = "Error stopping channel [";
    private static final Logger mLog = LoggerFactory.getLogger(ChannelProcessingManager.class);
    private static final String TUNER_UNAVAILABLE_DESCRIPTION = "TUNER UNAVAILABLE";
    private static final String CONFIGURATION_UNAVAILABLE_DESCRIPTION = "CHANNEL CONFIGURATION UNAVAILABLE";
    private static final long DMR_REST_CHANNEL_RETRY_DELAY_MILLISECONDS = 500;
    private static final long SITE_METADATA_DRAIN_RETRY_NANOS = TimeUnit.MILLISECONDS.toNanos(1);
    private static final long SITE_METADATA_IDLE_NANOS = TimeUnit.MILLISECONDS.toNanos(100);
    private static final int SITE_METADATA_INGRESS_CAPACITY = 256;
    private static final int SITE_METADATA_P25 = 1;
    private static final int SITE_METADATA_PROTOCOL = 2;
    private static final AtomicLong PROCESSING_INCARNATION_SEQUENCE = new AtomicLong();
    private Map<Channel,ProcessingChain> mProcessingChainsMap = new ConcurrentHashMap<>();
    private Lock mLock = new ReentrantLock();

    private ChannelSourceEventErrorListener mSourceErrorListener = new ChannelSourceEventErrorListener();
    private List<Listener<AudioCallEvent>> mAudioCallListeners = new CopyOnWriteArrayList<>();
    private List<Listener<IDecodeEvent>> mDecodeEventListeners = new CopyOnWriteArrayList<>();
    private List<BiConsumer<Channel,IDecodeEvent>> mChannelDecodeEventListeners = new CopyOnWriteArrayList<>();
    private List<Listener<ControlChannelQualitySnapshot>> mControlChannelQualityListeners = new CopyOnWriteArrayList<>();
    private List<SiteMetadataListener> mSiteMetadataListeners = new CopyOnWriteArrayList<>();
    private List<ProtocolSiteMetadataListener> mProtocolSiteMetadataListeners = new CopyOnWriteArrayList<>();
    private Broadcaster<ChannelEvent> mChannelEventBroadcaster = new Broadcaster<>();

    private ChannelActivityModel mChannelActivityModel;
    private EventLogManager mEventLogManager;
    private TunerManager mTunerManager;
    private AliasModel mAliasModel;
    private UserPreferences mUserPreferences;
    private List<Long> mLoggedFrequencies = new ArrayList<>();
    private final SiteMetadataIngressQueue mSiteMetadataIngress;
    private final AtomicInteger mSiteMetadataSubmissions = new AtomicInteger();
    private final AtomicLong mAcceptedSiteMetadata = new AtomicLong();
    private final AtomicLong mProcessedSiteMetadata = new AtomicLong();
    private final AtomicLong mDroppedSiteMetadata = new AtomicLong();
    private final Map<Channel,DMRRestChannelAttempt> mDmrRestChannelAttempts = new HashMap<>();
    private final DMRRestChannelHandoffCoordinator mDmrRestChannelHandoffCoordinator;
    private final long mDmrRestChannelRetryDelayMilliseconds;
    private final Object mShutdownLock = new Object();
    private volatile boolean mShuttingDown;
    private volatile boolean mClosed;
    private volatile boolean mSiteMetadataWorkerRunning = true;
    private volatile Thread mSiteMetadataWorker;

    /**
     * Constructs the channel processing manager
     *
     * @param eventLogManager for adding event loggers to channels
     * @param tunerManager for obtaining a tuner channel source for the channel
     * @param aliasModel for aliasing of identifiers produced by the channel
     * @param userPreferences for user defined behavior and settings
     */
    public ChannelProcessingManager(EventLogManager eventLogManager, TunerManager tunerManager, AliasModel aliasModel,
                                    UserPreferences userPreferences)
    {
        this(eventLogManager, tunerManager, aliasModel, userPreferences,
            DMR_REST_CHANNEL_RETRY_DELAY_MILLISECONDS);
    }

    /**
     * Test seam for deterministic replacement-source retry timing.
     */
    ChannelProcessingManager(EventLogManager eventLogManager, TunerManager tunerManager, AliasModel aliasModel,
                             UserPreferences userPreferences, long dmrRestChannelRetryDelayMilliseconds)
    {
        this(eventLogManager, tunerManager, aliasModel, userPreferences, dmrRestChannelRetryDelayMilliseconds,
            SITE_METADATA_INGRESS_CAPACITY);
    }

    /** Test seam for deterministic bounded site-metadata saturation. */
    ChannelProcessingManager(EventLogManager eventLogManager, TunerManager tunerManager, AliasModel aliasModel,
                             UserPreferences userPreferences, long dmrRestChannelRetryDelayMilliseconds,
                             int siteMetadataIngressCapacity)
    {
        if(dmrRestChannelRetryDelayMilliseconds <= 0)
        {
            throw new IllegalArgumentException("DMR rest-channel retry delay must be positive");
        }

        mEventLogManager = eventLogManager;
        mTunerManager = tunerManager;
        mAliasModel = aliasModel;
        mUserPreferences = userPreferences;
        mChannelActivityModel = new ChannelActivityModel(aliasModel, userPreferences.getNowPlayingPreference());
        mChannelActivityModel.setActiveChannelSupplier(this::getActiveChannelActivitySnapshot);
        mSiteMetadataIngress = new SiteMetadataIngressQueue(siteMetadataIngressCapacity);
        mDmrRestChannelRetryDelayMilliseconds = dmrRestChannelRetryDelayMilliseconds;
        mDmrRestChannelHandoffCoordinator = new DMRRestChannelHandoffCoordinator(
            this::snapshotDmrRestChannelHandoffOwners, this::processDmrRestChannelHandoff);
        mSiteMetadataWorker = new ObserverThreadFactory("site metadata observers")
            .newThread(this::runSiteMetadataWorker);
        mSiteMetadataWorker.start();
    }

    public ChannelActivityModel getChannelActivityModel()
    {
        return mChannelActivityModel;
    }

    private List<ChannelActivityModel.ActiveChannel> getActiveChannelActivitySnapshot()
    {
        List<ChannelActivityModel.ActiveChannel> active = new ArrayList<>(mProcessingChainsMap.size());

        for(Map.Entry<Channel,ProcessingChain> entry: mProcessingChainsMap.entrySet())
        {
            Channel channel = entry.getKey();
            ProcessingChain chain = entry.getValue();

            if(channel != null && chain != null)
            {
                List<ChannelMetadata> metadata = chain.getChannelState() != null ?
                    chain.getChannelState().getChannelMetadata() : List.of();
                active.add(new ChannelActivityModel.ActiveChannel(channel, metadata, chain));
            }
        }

        return List.copyOf(active);
    }

    /**
     * Indicates if a processing chain is constructed for the channel and that
     * the processing chain is currently processing.
     */
    private boolean isProcessing(Channel channel)
    {
        boolean isProcessing = false;

        mLock.lock();

        try
        {
            isProcessing = mProcessingChainsMap.containsKey(channel) && mProcessingChainsMap.get(channel).isProcessing();
        }
        finally
        {
            mLock.unlock();
        }

        return isProcessing;
    }

    /**
     * Indicates if any channels are currently processing.
     * @return true if channels are processing.
     */
    public boolean isProcessing()
    {
        return !mProcessingChainsMap.isEmpty();
    }

    /**
     * Returns the current processing chain associated with the channel, or
     * null if a processing chain is not currently setup for the channel
     */
    public ProcessingChain getProcessingChain(Channel channel)
    {
        return mProcessingChainsMap.get(channel);
    }

    /**
     * Returns active processing chains for a saved channel configuration. A null frequency selects every chain for
     * the configuration; an exact frequency selects only matching traffic or conventional chains.
     */
    public List<ProcessingChain> getProcessingChainsByConfiguration(String configurationId, Long frequency)
    {
        if(configurationId == null || configurationId.isBlank())
        {
            return List.of();
        }

        //The backing ConcurrentHashMap provides a weakly consistent bounded snapshot without acquiring the channel
        //lifecycle lock.  Browser diagnostics and history readers must never delay channel start, stop, or hunting.
        List<ProcessingChain> matches = new ArrayList<>();

        for(Map.Entry<Channel,ProcessingChain> entry: mProcessingChainsMap.entrySet())
        {
            Channel channel = entry.getKey();
            ProcessingChain chain = entry.getValue();

            if(channel == null || chain == null ||
                !configurationId.equals(channel.getConfigurationId()))
            {
                continue;
            }

            if(frequency != null)
            {
                Source source = chain.getSource();

                if(source != null && source.getFrequency() == frequency)
                {
                    matches.add(chain);
                }
            }
            else
            {
                matches.add(chain);
            }
        }

        return List.copyOf(matches);
    }

    /**
     * Returns the channel associated with the processing chain
     *
     * @param processingChain
     * @return channel associated with the processing chain or null
     */
    public Channel getChannel(ProcessingChain processingChain)
    {
        Channel channel = null;

        if(processingChain != null)
        {
            mLock.lock();

            try
            {
                for(Map.Entry<Channel,ProcessingChain> entry : mProcessingChainsMap.entrySet())
                {
                    if(entry.getValue() == processingChain)
                    {
                        channel = entry.getKey();
                        break;
                    }
                }
            }
            finally
            {
                mLock.unlock();
            }
        }

        return channel;
    }

    /**
     * Retrieves the channel associated with the processing chain that is consuming from the tuner channel source
     * @param tunerChannelSource to find the channel
     * @return channel
     */
    private Channel getChannel(TunerChannelSource tunerChannelSource)
    {
        Channel channel = null;

        mLock.lock();

        try
        {
            for(Map.Entry<Channel,ProcessingChain> entry : mProcessingChainsMap.entrySet())
            {
                if(entry.getValue().hasSource(tunerChannelSource))
                {
                    channel = entry.getKey();
                    break;
                }
            }
        }
        finally
        {
            mLock.unlock();
        }

        return channel;
    }

    /**
     * Primary method for receiving requests to start and stop a channel
     *
     * @param event that requests either enable/start or disable/stop a channel.
     */
    @Override
    public void receive(ChannelEvent event)
    {
        Channel channel = event.getChannel();

        switch(event.getEvent())
        {
            case REQUEST_ENABLE:
                if(!mClosed && !mShuttingDown && !isProcessing(channel))
                {
                    try
                    {
                        startProcessing(new ChannelStartProcessingRequest(event.getChannel()));
                    }
                    catch(ChannelException ce)
                    {
                        if(channel.getSourceConfiguration() instanceof SourceConfigTuner sourceconfigtuner)
                        {
                            long frequency = sourceconfigtuner.getFrequency();

                            if(!mLoggedFrequencies.contains(frequency))
                            {
                                mLoggedFrequencies.add(frequency);
                                mLog.error("Error starting requested channel [{}:{}] - {}", channel.getName(), frequency,
                                    ce.getMessage());
                            }
                        }
                        else if(channel.getSourceConfiguration() instanceof SourceConfigTunerMultipleFrequency sourceConfigTunerMultipleFrequency)
                        {
                            List<Long> frequencies = sourceConfigTunerMultipleFrequency.getFrequencies();

                            if(!frequencies.isEmpty() && !mLoggedFrequencies.contains(frequencies.get(0)))
                            {
                                mLoggedFrequencies.add(frequencies.get(0));
                                mLog.error("Error starting requested channel [{}:{}] - {}", channel.getName(), frequencies,
                                    ce.getMessage());
                            }
                        }
                        else
                        {
                            mLog.error("Error starting requested channel [{}] - {}", channel.getName(), ce.getMessage());
                        }
                    }
                }
                break;
            case REQUEST_DISABLE, NOTIFICATION_DELETE:
                if(channel != null)
                {
                    try
                    {
                        stopProcessing(channel);
                    }
                    catch(Exception e)
                    {
                        mLog.error(ERROR_STOPPING_CHANNEL_LABEL + channel + "]", e);
                    }
                }
                else
                {
                    try
                    {
                        throw new IllegalArgumentException("Request to disable channel must have non-null channel");
                    }
                    catch(IllegalArgumentException iae)
                    {
                        mLog.error("Caught a [" + event.getEvent() + "] non-standard channel event - logging stack trace.  " +
                                "This should not happen, please send this error to the developer.", iae);
                    }
                }
                break;
            case NOTIFICATION_CONFIGURATION_CHANGE:
                mChannelActivityModel.channelConfigurationChanged(channel);
                break;
            default:
                break;
        }
    }

    /**
     * Starts the specified channel.
     * @param channel to start
     * @throws ChannelException if the channel can't be started
     */
    public void start(Channel channel) throws ChannelException
    {
        if(mClosed || mShuttingDown)
        {
            throw new ChannelException("Channel processing manager is shutting down or closed");
        }

        startProcessing(new ChannelStartProcessingRequest(channel));
    }

    /**
     * Accepts a Capacity Plus rest-channel handoff from a decoder callback.  This subscriber performs only a fixed,
     * bounded offer; conversion, tuner allocation, logging/recording construction and startup run on the dedicated
     * lifecycle worker.
     */
    @Subscribe
    public void requestDmrRestChannelHandoff(DMRRestChannelHandoffRequest request)
    {
        if(mClosed || mShuttingDown || !mDmrRestChannelHandoffCoordinator.offer(request))
        {
            request.owner().completeRestHandoff(request);
        }
    }

    /**
     * Discovers each DMR site's authoritative latest-value mailbox on the lifecycle worker.  Live parent chains cover
     * newly nominated moves; active attempts keep detached managers discoverable through replacement retries.
     */
    private synchronized Iterable<DMRTrafficChannelManager> snapshotDmrRestChannelHandoffOwners()
    {
        Set<DMRTrafficChannelManager> owners = new LinkedHashSet<>();

        for(ProcessingChain processingChain: mProcessingChainsMap.values())
        {
            for(Module module: processingChain.getModules())
            {
                if(module instanceof DMRTrafficChannelManager owner)
                {
                    owners.add(owner);
                }
            }
        }

        for(DMRRestChannelAttempt attempt: mDmrRestChannelAttempts.values())
        {
            owners.add(attempt.getPrepared().handoff().owner());
        }

        return List.copyOf(owners);
    }

    private synchronized void processDmrRestChannelHandoff(DMRRestChannelHandoffRequest request)
    {
        try
        {
            DMRTrafficChannelManager owner = request.owner();

            if(mClosed || mShuttingDown || !owner.isPendingRestHandoff(request))
            {
                owner.completeRestHandoff(request);
                return;
            }

            DMRRestChannelAttempt existing = mDmrRestChannelAttempts.get(request.parentChannel());

            if(existing != null)
            {
                if(existing.matches(request) && existing.isActive())
                {
                    attemptDmrRestChannelStart(existing);
                }
                else
                {
                    owner.completeRestHandoff(request);
                }

                return;
            }

            ProcessingChain expectedChain = mProcessingChainsMap.get(request.parentChannel());

            if(!isExpectedDmrRestHandoffChain(request, expectedChain))
            {
                owner.completeRestHandoff(request);
                return;
            }

            PreparedRestChannelHandoff prepared = owner.prepareRestChannelHandoff(request);

            if(prepared == null)
            {
                owner.completeRestHandoff(request);
                return;
            }

            DMRRestChannelAttempt attempt = new DMRRestChannelAttempt(prepared, expectedChain,
                snapshotDmrRestChannelSiblingTrafficChains(request.parentChannel()));
            mDmrRestChannelAttempts.put(request.parentChannel(), attempt);

            if(!convertDmrRestChannelToTraffic(attempt))
            {
                abortDmrRestChannelAttempt(attempt);
                return;
            }

            attemptDmrRestChannelStart(attempt);
        }
        catch(RuntimeException exception)
        {
            mLog.error("Error processing DMR rest-channel handoff", exception);
            DMRRestChannelAttempt attempt = null;

            try
            {
                attempt = mDmrRestChannelAttempts.get(request.parentChannel());
            }
            catch(RuntimeException cleanupException)
            {
                mLog.error("Error locating DMR rest-channel attempt after handler failure", cleanupException);
            }

            if(attempt != null && attempt.matches(request))
            {
                abortDmrRestChannelAttempt(attempt);
            }
            else
            {
                completeDmrRestChannelRequest(request);
            }
        }
    }

    private boolean isExpectedDmrRestHandoffChain(DMRRestChannelHandoffRequest request,
                                                   ProcessingChain processingChain)
    {
        if(processingChain == null || !request.parentChannel().isStandardChannel() ||
            mProcessingChainsMap.get(request.parentChannel()) != processingChain ||
            processingChain.getModules().stream().noneMatch(module -> module == request.owner()))
        {
            return false;
        }

        Source source = processingChain.getSource();
        return source != null && source.getFrequency() == request.currentFrequency();
    }

    private List<ProcessingChainMapping> snapshotDmrRestChannelSiblingTrafficChains(Channel parentChannel)
    {
        List<ProcessingChainMapping> siblings = new ArrayList<>();
        String configurationId = parentChannel.getConfigurationId();

        for(Map.Entry<Channel,ProcessingChain> entry: mProcessingChainsMap.entrySet())
        {
            Channel channel = entry.getKey();
            ProcessingChain processingChain = entry.getValue();

            if(channel != null && processingChain != null && channel.isTrafficChannel() &&
                configurationId.equals(channel.getConfigurationId()))
            {
                siblings.add(new ProcessingChainMapping(channel, processingChain));
            }
        }

        return List.copyOf(siblings);
    }

    private boolean convertDmrRestChannelToTraffic(DMRRestChannelAttempt attempt)
    {
        PreparedRestChannelHandoff prepared = attempt.getPrepared();
        DMRRestChannelHandoffRequest handoff = prepared.handoff();
        ProcessingChain processingChain = attempt.getExpectedChain();
        Channel parentChannel = handoff.parentChannel();
        Channel trafficChannel = prepared.trafficChannel();

        if(!isExpectedDmrRestHandoffChain(handoff, processingChain))
        {
            return false;
        }

        ChannelRotationMonitorPauseRequest pauseRequest = new ChannelRotationMonitorPauseRequest();
        boolean conversionCommitted = false;
        boolean parentMappingRemoved = false;
        boolean trafficMappingInstalled = false;
        long parentProcessingIncarnation = 0;
        boolean dmrConversionNotificationPosted = false;
        DMRChannelConfigurationTransitionNotification.Suspend dmrSuspension = null;
        AbstractChannelState.ChannelConfigurationTransition channelStateTransition = null;

        try
        {
            processingChain.getEventBus().post(pauseRequest);

            if(!pauseRequest.isSourceStableAt(handoff.currentFrequency()) ||
                !isExpectedDmrRestHandoffChain(handoff, processingChain))
            {
                return false;
            }

            //Revoke both decoder states' control-channel allocation authority before changing ownership.  Event-bus
            //subscriber failures are isolated by Guava, so require an explicit acknowledgment from every DMR timeslot.
            dmrSuspension = new DMRChannelConfigurationTransitionNotification.Suspend(trafficChannel);
            processingChain.getEventBus().post(dmrSuspension);

            if(!dmrSuspension.isAcknowledged(getTrafficIdentifierTimeslots(parentChannel).length))
            {
                mLog.error("Unable to suspend all DMR decoder allocation authority before rest-channel conversion " +
                    "[acknowledged={}]", dmrSuspension.getAcknowledgedSubscriberCount());
                return false;
            }

            //Only mutate manager accounting after every decoder state has acknowledged that allocation authority is
            //closed.  This prevents an in-flight grant from observing the prepared traffic allocation before the CPM
            //map conversion can become authoritative.
            if(!handoff.owner().commitRestChannelHandoff(prepared))
            {
                return false;
            }

            //Publish conversion intent before changing the map.  Decoder callbacks use this lock-free marker to hold
            //an overlapping teardown until the traffic key exists; they never wait for this lifecycle worker.
            channelStateTransition = processingChain.beginChannelConfigurationTransition(trafficChannel);

            RemovedProcessingMapping parentMapping = removeProcessingChainMapping(parentChannel, processingChain);

            if(parentMapping == null)
            {
                return false;
            }

            parentMappingRemoved = true;
            parentProcessingIncarnation = parentMapping.processingIncarnation();
            attachDetachedOwnerChannelEventRoute(attempt);
            processingChain.removeTrafficChannelManager();

            if(installProcessingChainMapping(trafficChannel, processingChain) == 0)
            {
                return false;
            }

            trafficMappingInstalled = true;
            prepared.markConverted();
            attempt.setConverted();
            conversionCommitted = true;
            //Publish converted flags before any observer work.  An abort or overlapping TEARDOWN then lets the normal
            //traffic stop path own the final false value instead of leaving either channel with stale UI state.
            setConvertedChannelProcessingFlags(parentChannel, trafficChannel);

            //The traffic key is authoritative now.  Publish functional channel/type and revoke the decoder's parent
            //allocation role before lower-priority activity and identifier projection.
            processingChain.publishChannelConfigurationTransition(channelStateTransition);
            processingChain.channelConfigurationChanged(new ChannelConfigurationChangeNotification(trafficChannel));
            dmrConversionNotificationPosted = true;

            //The old chain now owns traffic only. Stop and detach control-only observers after the functional
            //identity is committed so they cannot rotate its source or publish quality for the former parent.
            processingChain.removeControlChannelModules();
            mChannelActivityModel.channelStopped(parentChannel);
            mChannelActivityModel.channelStarted(trafficChannel,
                processingChain.getChannelState().getChannelMetadata(), processingChain);

            for(int timeslot: getTrafficIdentifierTimeslots(trafficChannel))
            {
                IdentifierUpdateNotification notification = new IdentifierUpdateNotification(
                    TrafficChannelIdentifier.create(), IdentifierUpdateNotification.Operation.ADD, timeslot);
                processingChain.getChannelState().updateChannelStateIdentifiers(notification);
            }

            processingChain.completeChannelConfigurationTransition(channelStateTransition);
            channelStateTransition = null;
            return true;
        }
        finally
        {
            if(!conversionCommitted)
            {
                rollbackPausedDmrRestChannelConversion(attempt, parentMappingRemoved, trafficMappingInstalled,
                    parentProcessingIncarnation, channelStateTransition, dmrSuspension);
            }
            else
            {
                if(!dmrConversionNotificationPosted)
                {
                    try
                    {
                        processingChain.channelConfigurationChanged(
                            new ChannelConfigurationChangeNotification(trafficChannel));
                    }
                    catch(RuntimeException exception)
                    {
                        mLog.error("Error committing DMR decoder mode during handoff cleanup", exception);
                    }
                }

                if(channelStateTransition != null)
                {
                    //An observer projection failed after the map conversion became irreversible.  Publish/complete the
                    //functional state before the outer handler aborts and stops the converted chain.
                    try
                    {
                        if(!channelStateTransition.isPublished())
                        {
                            processingChain.publishChannelConfigurationTransition(channelStateTransition);
                        }

                        processingChain.completeChannelConfigurationTransition(channelStateTransition);
                    }
                    catch(RuntimeException exception)
                    {
                        mLog.error("Error completing DMR channel-state transition during handoff cleanup", exception);
                    }
                }
            }
        }
    }

    private void rollbackPausedDmrRestChannelConversion(DMRRestChannelAttempt attempt, boolean parentMappingRemoved,
                                                        boolean trafficMappingInstalled,
                                                        long parentProcessingIncarnation,
                                                        AbstractChannelState.ChannelConfigurationTransition transition,
                                                        DMRChannelConfigurationTransitionNotification.Suspend dmrSuspension)
    {
        PreparedRestChannelHandoff prepared = attempt.getPrepared();
        DMRRestChannelHandoffRequest handoff = prepared.handoff();
        ProcessingChain processingChain = attempt.getExpectedChain();

        try
        {
            if(trafficMappingInstalled)
            {
                removeProcessingChainMapping(prepared.trafficChannel(), processingChain);
            }
        }
        catch(RuntimeException exception)
        {
            mLog.error("Error removing rolled-back DMR traffic-channel mapping", exception);
        }

        try
        {
            if(processingChain.getModules().stream().noneMatch(module -> module == handoff.owner()))
            {
                processingChain.addModule(handoff.owner());
            }
        }
        catch(RuntimeException exception)
        {
            mLog.error("Error restoring DMR traffic-channel manager after handoff rollback", exception);
        }

        try
        {
            if(parentMappingRemoved && !restoreProcessingChainMapping(handoff.parentChannel(), processingChain,
                parentProcessingIncarnation))
            {
                mLog.error("Unable to restore DMR parent-channel mapping after handoff rollback");
            }
        }
        catch(RuntimeException exception)
        {
            mLog.error("Error restoring DMR parent-channel mapping after handoff rollback", exception);
        }

        try
        {
            processingChain.rollbackChannelConfigurationTransition(transition);
        }
        catch(RuntimeException exception)
        {
            mLog.error("Error rolling back DMR channel-state transition", exception);
        }

        boolean accountingRestored = false;

        try
        {
            //Always return the claimed pooled channel and clear the target-frequency token before reopening decoder
            //allocation authority.  Both operations are identity-scoped and idempotent when the outer abort repeats
            //them.  This is safe whether manager commit never ran, returned false, or completed and must be reversed.
            handoff.owner().releaseRestChannelReservation(prepared);
            handoff.owner().completeRestHandoff(handoff);
            accountingRestored = true;
        }
        catch(RuntimeException exception)
        {
            mLog.error("Error restoring DMR manager accounting before decoder-authority rollback", exception);
        }

        boolean decoderAuthorityRestored = dmrSuspension == null;

        try
        {
            if(accountingRestored && dmrSuspension != null)
            {
                processingChain.getEventBus().post(dmrSuspension.rollback());
                decoderAuthorityRestored = true;
            }
        }
        catch(RuntimeException exception)
        {
            mLog.error("Error restoring DMR decoder authority after manager-accounting rollback", exception);
        }

        try
        {
            removeDetachedOwnerChannelEventRoute(attempt);
        }
        catch(RuntimeException exception)
        {
            mLog.error("Error removing temporary DMR channel-event route after handoff rollback", exception);
        }

        try
        {
            if(accountingRestored && decoderAuthorityRestored)
            {
                processingChain.getEventBus().post(new ChannelRotationMonitorResumeRequest());
            }
            else
            {
                mLog.error("DMR rest-channel rollback left decoder authority and source rotation closed because " +
                    "manager accounting or decoder authority could not be restored safely");
            }
        }
        catch(RuntimeException exception)
        {
            mLog.error("Error resuming DMR channel rotation after handoff rollback", exception);
        }
    }

    private void attachDetachedOwnerChannelEventRoute(DMRRestChannelAttempt attempt)
    {
        if(attempt.attachDetachedOwnerChannelEventRoute())
        {
            mChannelEventBroadcaster.addListener(attempt.getDetachedOwnerChannelEventRoute());
        }
    }

    private void removeDetachedOwnerChannelEventRoute(DMRRestChannelAttempt attempt)
    {
        if(attempt != null && attempt.detachDetachedOwnerChannelEventRoute())
        {
            mChannelEventBroadcaster.removeListener(attempt.getDetachedOwnerChannelEventRoute());
        }
    }

    private static void setConvertedChannelProcessingFlags(Channel parentChannel, Channel trafficChannel)
    {
        setChannelProcessingFlag(parentChannel, false);
        setChannelProcessingFlag(trafficChannel, true);
    }

    private void attemptDmrRestChannelStart(DMRRestChannelAttempt attempt)
    {
        DMRRestChannelHandoffRequest handoff = attempt.getPrepared().handoff();

        if(!attempt.isActive() || mClosed || mShuttingDown || !handoff.owner().isPendingRestHandoff(handoff) ||
            mDmrRestChannelAttempts.get(handoff.parentChannel()) != attempt)
        {
            abortDmrRestChannelAttempt(attempt);
            return;
        }

        try
        {
            startProcessing(attempt.getPrepared().startRequest(), true);
            ProcessingChain replacement = mProcessingChainsMap.get(handoff.parentChannel());

            if(replacement == null || !replacement.isProcessing() ||
                replacement.getModules().stream().noneMatch(module -> module == handoff.owner()))
            {
                throw new ChannelException("Replacement DMR rest channel did not start");
            }

            completeDmrRestChannelAttempt(attempt);
        }
        catch(TunerUnavailableChannelException exception)
        {
            if(isRunnable(handoff.parentChannel()) && !mClosed && !mShuttingDown)
            {
                scheduleDmrRestChannelRetry(attempt);
            }
            else
            {
                abortDmrRestChannelAttempt(attempt);
            }
        }
        catch(ChannelException exception)
        {
            if(isRunnable(handoff.parentChannel()) && !mClosed && !mShuttingDown)
            {
                mLog.error("Error starting replacement DMR rest channel; preserving the former REST call and " +
                    "retrying", exception);
                scheduleDmrRestChannelRetry(attempt);
            }
            else
            {
                abortDmrRestChannelAttempt(attempt);
            }
        }
        catch(RuntimeException exception)
        {
            mLog.error("Error starting replacement DMR rest channel", exception);
            abortDmrRestChannelAttempt(attempt);
        }
    }

    private void scheduleDmrRestChannelRetry(DMRRestChannelAttempt attempt)
    {
        attempt.schedule(() ->
        {
            if(attempt.isActive() && !mClosed && !mShuttingDown &&
                !mDmrRestChannelHandoffCoordinator.offerRetry(attempt.getPrepared().handoff()))
            {
                scheduleDmrRestChannelRetry(attempt);
            }
        }, mDmrRestChannelRetryDelayMilliseconds);
    }

    private void completeDmrRestChannelAttempt(DMRRestChannelAttempt attempt)
    {
        DMRRestChannelHandoffRequest handoff = attempt.getPrepared().handoff();

        if(attempt.deactivate())
        {
            removeDetachedOwnerChannelEventRoute(attempt);
            mDmrRestChannelAttempts.remove(handoff.parentChannel(), attempt);
            handoff.owner().completeSuccessfulRestHandoff(handoff);
        }
    }

    private void abortDmrRestChannelAttempt(DMRRestChannelAttempt attempt)
    {
        if(attempt == null || !attempt.deactivate())
        {
            return;
        }

        PreparedRestChannelHandoff prepared = attempt.getPrepared();
        DMRRestChannelHandoffRequest handoff = prepared.handoff();

        try
        {
            if(attempt.isConverted())
            {
                stopDmrRestChannelSiblingTrafficChains(attempt);

                if(mProcessingChainsMap.get(prepared.trafficChannel()) == attempt.getExpectedChain())
                {
                    try
                    {
                        stopProcessing(prepared.trafficChannel());
                    }
                    catch(Exception exception)
                    {
                        mLog.error("Error stopping converted DMR traffic channel during handoff abort", exception);
                    }
                }

                try
                {
                    attempt.getExpectedChain().unrouteDecodeEventsFrom(handoff.owner());
                    processDetachedTrafficTeardown(attempt);
                }
                catch(RuntimeException exception)
                {
                    mLog.error("Error processing converted DMR traffic-channel teardown during handoff abort", exception);
                }
            }
            else
            {
                try
                {
                    handoff.owner().releaseRestChannelReservation(prepared);
                }
                catch(RuntimeException exception)
                {
                    mLog.error("Error releasing DMR rest-channel reservation during handoff abort", exception);
                }
            }
        }
        finally
        {
            removeDetachedOwnerChannelEventRoute(attempt);
            mDmrRestChannelAttempts.remove(handoff.parentChannel(), attempt);
            completeDmrRestChannelRequest(handoff);
        }
    }

    private void stopDmrRestChannelSiblingTrafficChains(DMRRestChannelAttempt attempt)
    {
        for(ProcessingChainMapping sibling: attempt.getSiblingTrafficChains())
        {
            if(mProcessingChainsMap.get(sibling.channel()) == sibling.processingChain())
            {
                try
                {
                    stopProcessing(sibling.channel());
                }
                catch(Exception exception)
                {
                    mLog.error("Error stopping sibling DMR traffic channel during rest-channel handoff abort",
                        exception);
                }
            }
        }
    }

    private void completeDmrRestChannelRequest(DMRRestChannelHandoffRequest request)
    {
        try
        {
            request.owner().completeRestHandoff(request);
        }
        catch(RuntimeException exception)
        {
            mLog.error("Error completing DMR rest-channel handoff request", exception);
        }
    }

    /**
     * Request to start processing a channel received over the Guava event bus.
     *
     * Note: since this is received over the event bus, we handle any channel exceptions inside this method.
     */
    @Subscribe
    public void startChannelRequest(ChannelStartProcessingRequest request)
    {
        if(!mClosed && !mShuttingDown && !isProcessing(request.getChannel()))
        {
            try
            {
                startProcessing(request);
            }
            catch(ChannelException _)
            {
                //The requester owns any retry policy. DMR rest-channel retries use the bounded handoff coordinator.
            }
        }
    }

    /**
     * Request to stop a channel that is currently processing
     * @param request with the tuner channel source feeding the channel to be stopped.
     */
    @Subscribe
    public void stopChannelRequest(ChannelStopProcessingRequest request)
    {
        Channel channel = getChannel(request.getTunerChannelSource());

        if(channel != null)
        {
            try
            {
                stop(channel);
            }
            catch(ChannelException ce)
            {
                mLog.error(ERROR_STOPPING_CHANNEL_LABEL + channel + "]", ce);
            }
        }
    }

    /**
     * Stops the specified channel
     * @param channel to stop
     */
    public void stop(Channel channel) throws ChannelException
    {
        stopProcessing(channel);
    }

    /**
     * Starts a channel processing
     * @param request containing channel and other details
     * @throws ChannelException if a source is not available for the channel
     */
    private synchronized void startProcessing(ChannelStartProcessingRequest request) throws ChannelException
    {
        startProcessing(request, false);
    }

    private synchronized void startProcessing(ChannelStartProcessingRequest request, boolean strictFunctionalStartup)
        throws ChannelException
    {
        Channel channel = request.getChannel();

        if(mClosed || mShuttingDown)
        {
            throw new ChannelException("Channel processing manager is shutting down or closed");
        }

        if(isProcessing(channel))
        {
            return;
        }

        if(!isRunnable(channel))
        {
            mChannelEventBroadcaster.broadcast(new ChannelEvent(channel,
                ChannelEvent.Event.NOTIFICATION_PROCESSING_START_REJECTED, CONFIGURATION_UNAVAILABLE_DESCRIPTION));
            throw new ChannelException("Channel source or decoder is retired or unsupported");
        }

        //Ensure that we can get a source before we construct a new processing chain
        Source source = null;

        try
        {
            String threadName = "sdrtrunk channel [" + channel.getChannelID() + "/" +
                    channel.getDecodeConfiguration().getDecoderType().getShortDisplayString() + "]";
            source = mTunerManager.getSource(channel.getSourceConfiguration(),
                channel.getDecodeConfiguration().getChannelSpecification(), threadName);
        }
        catch(SourceException se)
        {
            mLog.debug("Error obtaining source for channel [" + channel.getName() + "]", se);
        }

        if(source == null)
        {
            setChannelProcessingFlag(channel, false);

            mChannelEventBroadcaster.broadcast(new ChannelEvent(channel,
                ChannelEvent.Event.NOTIFICATION_PROCESSING_START_REJECTED, TUNER_UNAVAILABLE_DESCRIPTION));

            throw new TunerUnavailableChannelException();
        }

        ProcessingChain processingChain = null;
        boolean sourceAssignedToChain = false;
        DMRTrafficChannelManager deferredDmrDecodeEventProvider = strictFunctionalStartup &&
            channel.isStandardChannel() && request.getTrafficChannelManager() instanceof DMRTrafficChannelManager owner ?
            owner : null;

        try
        {
            processingChain = new ProcessingChain(channel, mAliasModel);
            ProcessingChain observerChain = processingChain;

            //Register to receive event bus requests/notifications
            processingChain.getEventBus().register(ChannelProcessingManager.this);

            mChannelEventBroadcaster.addListener(processingChain);

            /* Register global listeners */
            processingChain.addAudioCallListener(event ->
                mChannelActivityModel.receiveAudioCallEvent(observerChain.getCurrentChannel(), event));

            for(Listener<AudioCallEvent> listener : mAudioCallListeners)
            {
                processingChain.addAudioCallListener(listener);
            }

            for(Listener<IDecodeEvent> listener : mDecodeEventListeners)
            {
                processingChain.addDecodeEventListener(listener);
            }

            for(BiConsumer<Channel,IDecodeEvent> listener : mChannelDecodeEventListeners)
            {
                processingChain.addDecodeEventListener(event ->
                    listener.accept(observerChain.getCurrentChannel(), event));
            }

            //Add a listener to detect source error state that indicates the channel should be shutdown.
            //Note: processing chain will only add this once.
            processingChain.addSourceEventListener(mSourceErrorListener);

            //Register this manager to receive channel events from traffic channel manager modules within
            //the processing chain
            processingChain.addChannelEventListener(this);

            /* Processing Modules */
            List<Module> modules = DecoderFactory.getModules(channel, mAliasModel, mUserPreferences,
                request.getTrafficChannelManager(), request.getChannelDescriptor(), source.getSampleRate(),
                mChannelActivityModel);

            if(supportsControlChannelQuality(channel))
            {
                modules.add(new ControlChannelQualityMonitor(channel, source.getFrequency(),
                    this::receiveControlChannelQuality));
            }

            for(Module module: modules)
            {
                if(module == deferredDmrDecodeEventProvider)
                {
                    processingChain.addModuleWithDeferredDecodeEventRouting(module);
                }
                else
                {
                    processingChain.addModule(module);
                }
            }

            //Post preload data from the request to the event bus.  Modules that can handle preload data will annotate
            //their processor method with @Subscribe to receive each specific preload data content class.
            for(PreloadDataContent<?> preloadDataContent: request.getPreloadDataContents())
            {
                Object preloadEvent = Objects.requireNonNull(preloadDataContent);
                processingChain.getEventBus().post(preloadEvent);
            }

            //Setup event logging
            List<Module> loggers = mEventLogManager.getLoggers(channel);

            if(!loggers.isEmpty())
            {
                processingChain.addModules(loggers);
            }

            //Add recorders
            processingChain.addModules(RecorderFactory.getRecorders(mUserPreferences, channel));

            //Set the samples source
            processingChain.setSource(source);
            sourceAssignedToChain = true;

            //Inject the channel identifier for traffic channels and preload user identifiers
            if(channel.isTrafficChannel())
            {
                int[] trafficTimeslots = getTrafficIdentifierTimeslots(channel);

                for(int timeslot: trafficTimeslots)
                {
                    IdentifierUpdateNotification trafficNotification = new IdentifierUpdateNotification(
                        TrafficChannelIdentifier.create(), IdentifierUpdateNotification.Operation.ADD, timeslot);
                    processingChain.getChannelState().updateChannelStateIdentifiers(trafficNotification);

                    if(request.hasChannelDescriptor())
                    {
                        DecoderLogicalChannelNameIdentifier identifier =
                            DecoderLogicalChannelNameIdentifier.create(request.getChannelDescriptor().toString(),
                                request.getChannelDescriptor().getProtocol());
                        IdentifierUpdateNotification notification = new IdentifierUpdateNotification(identifier,
                            IdentifierUpdateNotification.Operation.ADD, timeslot);
                        processingChain.getChannelState().updateChannelStateIdentifiers(notification);
                    }

                    if(request.hasIdentifierCollection())
                    {
                        //Inject scramble parameters
                        for(Identifier<?> scrambleParameters: request.getIdentifierCollection()
                            .getIdentifiers(Form.SCRAMBLE_PARAMETERS))
                        {
                            //Broadcast scramble parameters to both timeslots
                            IdentifierUpdateNotification scrambleNotification = new IdentifierUpdateNotification(scrambleParameters,
                                IdentifierUpdateNotification.Operation.ADD, timeslot);
                            processingChain.getChannelState().updateChannelStateIdentifiers(scrambleNotification);
                        }
                    }
                }

                if(request.hasIdentifierCollection())
                {
                    for(Identifier<?> userIdentifier : request.getIdentifierCollection().getIdentifiers(IdentifierClass.USER))
                    {
                        int timeslot = trafficTimeslots.length > 1 ? request.getIdentifierCollection().getTimeslot() : 0;
                        IdentifierUpdateNotification notification = new IdentifierUpdateNotification(userIdentifier,
                            IdentifierUpdateNotification.Operation.ADD, timeslot);
                        processingChain.getChannelState().updateChannelStateIdentifiers(notification);
                    }
                }
            }

            if(addProcessingChain(channel, processingChain))
            {
                if(strictFunctionalStartup || channel.isTrafficChannel())
                {
                    processingChain.startStrict();
                }
                else
                {
                    processingChain.start();
                }

                if(deferredDmrDecodeEventProvider != null)
                {
                    processingChain.routeDecodeEventsFrom(deferredDmrDecodeEventProvider);
                }

                setChannelProcessingFlag(channel, true);

                mChannelEventBroadcaster.broadcast(new ChannelEvent(channel, ChannelEvent.Event.NOTIFICATION_PROCESSING_START));
            }
            else
            {
                mLog.warn("Channel [{}] processing chain not added because it already exists", channel.getName());
                processingChain.removeEventLoggingModules();
                processingChain.removeRecordingModules();
                mChannelEventBroadcaster.broadcast(new ChannelEvent(channel, ChannelEvent.Event.NOTIFICATION_PROCESSING_STOP));
                mChannelEventBroadcaster.removeListener(processingChain);
                processingChain.getEventBus().unregister(ChannelProcessingManager.this);
                processingChain.dispose();
            }
        }
        catch(RuntimeException exception)
        {
            //A replacement REST chain temporarily hosts the existing site traffic manager.  If functional startup
            //fails, detach that shared manager before disposing the candidate so its stop() method cannot tear down
            //the preserved former-REST call and sibling traffic channels.  The handoff attempt keeps the manager's
            //temporary event routes alive until a later retry succeeds or the configured channel is stopped.
            if(strictFunctionalStartup && channel.isStandardChannel() && processingChain != null &&
                request.getTrafficChannelManager() instanceof DMRTrafficChannelManager dmrTrafficChannelManager &&
                processingChain.getModules().stream().anyMatch(module -> module == dmrTrafficChannelManager))
            {
                processingChain.removeModule(dmrTrafficChannelManager);
            }

            boolean chainOwnedSource = sourceAssignedToChain ||
                (processingChain != null && processingChain.getSource() == source);
            cleanupFailedChannelStart(channel, processingChain, source, chainOwnedSource);
            throw new ChannelException("Error constructing or starting channel processing chain", exception);
        }
    }

    private void cleanupFailedChannelStart(Channel channel, ProcessingChain processingChain, Source source,
                                           boolean sourceAssignedToChain)
    {
        if(processingChain == null)
        {
            releaseUnassignedSourceAfterFailedStart(source);
            notifyChannelStartRejected(channel);
            return;
        }

        if(removeProcessingChainMapping(channel, processingChain) != null)
        {
            mChannelActivityModel.channelStopped(channel);

            for(ChannelMetadata channelMetadata: processingChain.getChannelState().getChannelMetadata())
            {
                channelMetadata.removeUpdateEventListener();
            }
        }

        try
        {
            mChannelEventBroadcaster.removeListener(processingChain);
            processingChain.getEventBus().unregister(ChannelProcessingManager.this);
        }
        catch(RuntimeException cleanupException)
        {
            mLog.warn("Error detaching failed channel processing chain", cleanupException);
        }

        try
        {
            processingChain.dispose();
        }
        catch(RuntimeException cleanupException)
        {
            mLog.warn("Error disposing failed channel processing chain", cleanupException);
        }

        if(!sourceAssignedToChain)
        {
            releaseUnassignedSourceAfterFailedStart(source);
        }

        notifyChannelStartRejected(channel);
    }

    private void notifyChannelStartRejected(Channel channel)
    {
        setChannelProcessingFlag(channel, false);
        mChannelEventBroadcaster.broadcast(new ChannelEvent(channel,
            ChannelEvent.Event.NOTIFICATION_PROCESSING_START_REJECTED, CONFIGURATION_UNAVAILABLE_DESCRIPTION));
    }

    private void releaseUnassignedSourceAfterFailedStart(Source source)
    {
        try
        {
            source.stop();
        }
        catch(RuntimeException cleanupException)
        {
            mLog.warn("Error stopping source after channel startup failure", cleanupException);
        }

        try
        {
            source.dispose();
        }
        catch(RuntimeException cleanupException)
        {
            mLog.warn("Error disposing source after channel startup failure", cleanupException);
        }
    }

    private static void setChannelProcessingFlag(Channel channel, boolean processing)
    {
        if(GraphicsEnvironment.isHeadless())
        {
            channel.setProcessing(processing);
            return;
        }

        try
        {
            Platform.runLater(() -> channel.setProcessing(processing));
        }
        catch(IllegalStateException exception)
        {
            //JavaFX has not been initialized (for example, a headless service or unit test).
            channel.setProcessing(processing);
        }
    }

    /**
     * Internal channel-state keys are zero for single-slot decoders and one/two for DMR and P25 Phase 2.
     */
    static int[] getTrafficIdentifierTimeslots(Channel channel)
    {
        if(channel != null && channel.getDecodeConfiguration() != null)
        {
            int[] timeslots = channel.getDecodeConfiguration().getTimeslots();

            if(timeslots != null && timeslots.length > 0)
            {
                return timeslots;
            }
        }

        return new int[]{0};
    }

    /**
     * Thread-safe add processing chain and attach its metadata directly to the bounded activity model.
     * @param channel for the processing chain
     * @param processingChain to add
     * @return true if processing chain was added or false if it was not added due to there already
     * being a processing chain registered for that channel.
     */
    private boolean addProcessingChain(Channel channel, ProcessingChain processingChain)
    {
        boolean added = false;

        mLock.lock();

        try
        {
            added = installProcessingChainMapping(channel, processingChain) != 0;

            if(added)
            {
                //Publish the chain before offering its observer lifecycle event.  If the bounded activity ingress is
                //full, its worker can now reconcile the dropped start from the authoritative map.
                getChannelActivityModel().channelStarted(channel,
                    processingChain.getChannelState().getChannelMetadata(), processingChain);

                for(ChannelMetadata metadata: processingChain.getChannelState().getChannelMetadata())
                {
                    metadata.setUpdateEventListener(mChannelActivityModel);
                }
            }
        }
        finally
        {
            mLock.unlock();
        }

        return added;
    }

    /**
     * Thread-safe remove processing chain.
     * @param channel for identifying the processing chain
     * @return the removed processing chain or null
     */
    private ProcessingChain removeProcessingChain(Channel channel)
    {
        ProcessingChain removed = null;

        mLock.lock();

        try
        {
            RemovedProcessingMapping mapping = removeProcessingChainMapping(channel, null);
            removed = mapping != null ? mapping.processingChain() : null;

            if(removed != null)
            {
                getChannelActivityModel().channelStopped(channel);

                for(ChannelMetadata channelMetadata: removed.getChannelState().getChannelMetadata())
                {
                    channelMetadata.removeUpdateEventListener();
                }
            }
        }
        finally
        {
            mLock.unlock();
        }

        return removed;
    }

    /**
     * Installs one authoritative channel-to-chain mapping and activates a fresh transient incarnation before the
     * mapping can be observed. Zero means another chain already owns the channel.
     */
    private long installProcessingChainMapping(Channel channel, ProcessingChain processingChain)
    {
        return installProcessingChainMapping(channel, processingChain, nextProcessingIncarnation()) ?
            channel.getProcessingIncarnation() : 0;
    }

    /** Restores an interrupted map transition without making the unchanged processing chain look like a restart. */
    private boolean restoreProcessingChainMapping(Channel channel, ProcessingChain processingChain,
                                                   long processingIncarnation)
    {
        return processingIncarnation != 0 &&
            installProcessingChainMapping(channel, processingChain, processingIncarnation);
    }

    private boolean installProcessingChainMapping(Channel channel, ProcessingChain processingChain,
                                                   long processingIncarnation)
    {
        boolean[] installed = new boolean[1];
        mProcessingChainsMap.compute(channel, (_, existing) ->
        {
            if(existing == null)
            {
                channel.activateProcessingIncarnation(processingIncarnation);
                installed[0] = true;
                return processingChain;
            }

            return existing;
        });
        return installed[0];
    }

    /** Closes the incarnation before atomically removing its authoritative map entry. */
    private RemovedProcessingMapping removeProcessingChainMapping(Channel channel, ProcessingChain expected)
    {
        RemovedProcessingMapping[] removed = new RemovedProcessingMapping[1];
        mProcessingChainsMap.computeIfPresent(channel, (_, existing) ->
        {
            if(expected == null || existing == expected)
            {
                long processingIncarnation = channel.getProcessingIncarnation();
                channel.deactivateProcessingIncarnation(processingIncarnation);
                removed[0] = new RemovedProcessingMapping(existing, processingIncarnation);
                return null;
            }

            return existing;
        });
        return removed[0];
    }

    private static long nextProcessingIncarnation()
    {
        return PROCESSING_INCARNATION_SEQUENCE.updateAndGet(current ->
            current == Long.MAX_VALUE ? 1 : current + 1);
    }

    /**
     * Stops the channel/processing chain.
     *
     * @param channel to stop
     */
    private void stopProcessing(Channel channel) throws ChannelException
    {
        if(requiresDmrRestChannelLifecycleSerialization(channel))
        {
            //Capacity Plus rest-channel conversion already owns this lifecycle monitor and can call this method
            //reentrantly. Do not acquire the shutdown lock here or that path can invert shutdown's lock order.
            synchronized(this)
            {
                stopProcessingInternal(channel, true);
            }
        }
        else
        {
            //P25 and NXDN traffic managers synchronously request lifecycle changes while holding protocol-owned
            //locks. Do not hold a manager-wide lock across their stop notifications or those callbacks can invert
            //the lock order with a concurrent traffic-channel start or teardown.
            stopProcessingInternal(channel, false);
        }
    }

    /**
     * Stops a claimed processing-chain incarnation. DMR stops share the lifecycle monitor with Capacity Plus
     * rest-channel conversion; other protocols must not hold that monitor while notifying their traffic managers.
     */
    private void stopProcessingInternal(Channel channel, boolean handleDmrRestChannelLifecycle)
        throws ChannelException
    {
        //mDmrRestChannelAttempts is owned by the DMR lifecycle monitor. Non-DMR stops deliberately avoid both that
        //monitor and this mutable map so their synchronous stop notifications cannot invert a traffic-manager lock.
        DMRRestChannelAttempt detachedTrafficAttempt = handleDmrRestChannelLifecycle ?
            findDmrRestChannelAttemptByTrafficChannel(channel) : null;

        if(handleDmrRestChannelLifecycle)
        {
            cancelDmrRestChannelHandoff(channel);
        }

        ProcessingChain processingChain = removeProcessingChain(channel);

        if(processingChain != null)
        {
            if(GraphicsEnvironment.isHeadless())
            {
                channel.setProcessing(false);
            }
            else
            {
                //When we're in non-headless mode we have to change the processing property on the JavaFX
                //event thread.  However, if it hasn't yet been initialized (ie an FX window opened), we'll
                //get an ISE.  In that case, just set the property to false because there won't be any
                //property listeners being triggered.
                try
                {
                    Platform.runLater(() -> {
                        try
                        {
                            channel.setProcessing(false);
                        }
                        catch(Exception e)
                        {
                            mLog.error("Error during channel stop while setting processing to false [{}] - continuing channel stop process",
                                channel, e);
                        }
                    });
                }
                catch(IllegalStateException _)
                {
                    channel.setProcessing(false);
                }
            }

            try
            {
                processingChain.stop();
                processingChain.removeEventLoggingModules();
                processingChain.removeRecordingModules();

                //Notify all processing chains that this channel is shutting down so that if this is a traffic channel,
                //the owning parent channel's traffic channel manager can cleanup it's accounting.
                mChannelEventBroadcaster.broadcast(new ChannelEvent(channel, ChannelEvent.Event.NOTIFICATION_PROCESSING_STOP));
                mChannelEventBroadcaster.removeListener(processingChain);

                //Unregister for event bus requests and notifications
                processingChain.getEventBus().unregister(ChannelProcessingManager.this);
                processingChain.dispose();
            }
            catch(Exception e)
            {
                mLog.error("Error during shutdown of processing chain for channel [" + channel.getName() + "}", e);
            }
        }

        if(detachedTrafficAttempt != null)
        {
            detachedTrafficAttempt.getExpectedChain().unrouteDecodeEventsFrom(
                detachedTrafficAttempt.getPrepared().handoff().owner());
            processDetachedTrafficTeardown(detachedTrafficAttempt);
        }
    }

    static boolean requiresDmrRestChannelLifecycleSerialization(Channel channel)
    {
        return channel != null && channel.getDecodeConfiguration() != null &&
            channel.getDecodeConfiguration().getDecoderType() == DecoderType.DMR;
    }

    private void cancelDmrRestChannelHandoff(Channel channel)
    {
        DMRRestChannelAttempt attempt = mDmrRestChannelAttempts.get(channel);

        if(attempt != null)
        {
            abortDmrRestChannelAttempt(attempt);
            return;
        }

        ProcessingChain processingChain = mProcessingChainsMap.get(channel);

        if(processingChain != null)
        {
            for(Module module: processingChain.getModules())
            {
                if(module instanceof DMRTrafficChannelManager manager)
                {
                    manager.cancelRestHandoff(channel);
                }
            }
        }
    }

    private DMRRestChannelAttempt findDmrRestChannelAttemptByTrafficChannel(Channel channel)
    {
        ProcessingChain processingChain = mProcessingChainsMap.get(channel);

        for(DMRRestChannelAttempt attempt: mDmrRestChannelAttempts.values())
        {
            if(attempt.getPrepared().trafficChannel() == channel &&
                attempt.getExpectedChain() == processingChain)
            {
                return attempt;
            }
        }

        return null;
    }

    private void processDetachedTrafficTeardown(DMRRestChannelAttempt attempt)
    {
        if(attempt.markTrafficTeardownProcessed())
        {
            attempt.getPrepared().handoff().owner()
                .processTrafficChannelTeardown(attempt.getPrepared().trafficChannel());
        }
    }

    synchronized int getPendingDmrRestChannelAttemptCount()
    {
        return mDmrRestChannelAttempts.size();
    }

    int getChannelEventListenerCount()
    {
        return mChannelEventBroadcaster.getListenerCount();
    }

    boolean isDmrRestChannelHandoffCoordinatorClosed()
    {
        return mDmrRestChannelHandoffCoordinator.isClosed();
    }

    /** Test seam for deterministically firing an already-scheduled REST follower retry. */
    boolean retryDmrRestChannelHandoff(DMRRestChannelHandoffRequest request)
    {
        return mDmrRestChannelHandoffCoordinator.offerRetry(request);
    }

    /**
     * Stops all currently processing channels.
     */
    public void shutdown()
    {
        synchronized(mShutdownLock)
        {
            if(mShuttingDown)
            {
                return;
            }

            mShuttingDown = true;

            try
            {
                List<Channel> channelsToStop;

                //Fence any start or DMR conversion that entered before the shutdown flag was published. Keep P25,
                //NXDN, and conventional stop callbacks outside of the shared lifecycle monitor.
                synchronized(this)
                {
                    for(Channel channel: new ArrayList<>(mDmrRestChannelAttempts.keySet()))
                    {
                        cancelDmrRestChannelHandoff(channel);
                    }

                    channelsToStop = new ArrayList<>(mProcessingChainsMap.keySet());
                }

                for(Channel channel : channelsToStop)
                {
                    try
                    {
                        stopProcessing(channel);
                    }
                    catch(ChannelException ce)
                    {
                        mLog.error("Error stopping channel [{}] - {}", channel.getName(), ce.getMessage());
                    }
                }
            }
            finally
            {
                if(!mClosed)
                {
                    mShuttingDown = false;
                }
            }
        }
    }

    /**
     * Permanently releases this manager after all channels have stopped.
     */
    public void close()
    {
        synchronized(this)
        {
            if(mClosed)
            {
                return;
            }

            mClosed = true;
        }

        shutdown();
        mDmrRestChannelHandoffCoordinator.close();
        mChannelActivityModel.close();
        mSiteMetadataWorkerRunning = false;
        signalSiteMetadataWorker();
    }

    @Subscribe
    public void process(PostChannelModuleEventRequest request)
    {
        if(request == null || !request.hasChannels() || !request.hasEvent())
        {
            return;
        }

        for(Channel channel: request.getChannels())
        {
            ProcessingChain processingChain = getProcessingChain(channel);

            if(processingChain != null)
            {
                processingChain.getEventBus().post(request.getEvent());
            }
        }
    }

    @Subscribe
    public void process(SiteMetadataEvent event)
    {
        submitSiteMetadata(SITE_METADATA_P25, event);
    }

    private void dispatchSiteMetadata(SiteMetadataEvent event)
    {
        if(event != null && !event.matchesCurrentChannel())
        {
            return;
        }

        mChannelActivityModel.receiveSiteMetadata(event);

        if(event != null)
        {
            dispatchProtocolSiteMetadata(event.asProtocolSiteMetadataEvent());
        }

        for(SiteMetadataListener listener: mSiteMetadataListeners)
        {
            listener.receiveSiteMetadata(event);
        }
    }

    /**
     * Receives protocol-neutral site configuration from DMR, NXDN, and future decoder modules.
     */
    @Subscribe
    public void process(ProtocolSiteMetadataEvent event)
    {
        submitSiteMetadata(SITE_METADATA_PROTOCOL, event);
    }

    /**
     * Performs a fixed-attempt handoff to the bounded sequential site-metadata observer. The in-flight counter lets a
     * lifecycle thread fence submissions without making decoder callbacks acquire a contended lock. Saturation drops
     * observer metadata; it never makes a decoder thread wait or perform listener work.
     */
    private void submitSiteMetadata(int type, Object event)
    {
        if(mClosed)
        {
            return;
        }

        mSiteMetadataSubmissions.incrementAndGet();

        try
        {
            if(!mClosed)
            {
                if(mSiteMetadataIngress.offer(type, event))
                {
                    mAcceptedSiteMetadata.incrementAndGet();
                    signalSiteMetadataWorker();
                }
                else
                {
                    mDroppedSiteMetadata.incrementAndGet();
                }
            }
        }
        finally
        {
            mSiteMetadataSubmissions.decrementAndGet();
            signalSiteMetadataWorker();
        }
    }

    private void runSiteMetadataWorker()
    {
        while(mSiteMetadataWorkerRunning || mSiteMetadataSubmissions.get() != 0 ||
            mSiteMetadataIngress.size() != 0)
        {
            SiteMetadataIngressQueue.Entry entry = mSiteMetadataIngress.poll();

            if(entry == null)
            {
                LockSupport.parkNanos(this, SITE_METADATA_IDLE_NANOS);
                continue;
            }

            try
            {
                if(entry.type() == SITE_METADATA_P25)
                {
                    dispatchSiteMetadata((SiteMetadataEvent)entry.event());
                }
                else if(entry.type() == SITE_METADATA_PROTOCOL)
                {
                    dispatchProtocolSiteMetadata((ProtocolSiteMetadataEvent)entry.event());
                }
            }
            catch(RuntimeException exception)
            {
                mLog.error("Error dispatching site metadata to observers", exception);
            }
            finally
            {
                mProcessedSiteMetadata.incrementAndGet();
            }
        }
    }

    private void signalSiteMetadataWorker()
    {
        Thread worker = mSiteMetadataWorker;

        if(worker != null)
        {
            LockSupport.unpark(worker);
        }
    }

    /**
     * Waits for every site-metadata callback accepted before {@link #close()} to finish. Callers must close this
     * manager first so new callbacks are rejected. This bounded lifecycle method must not run on a receiver thread.
     */
    public boolean awaitSiteMetadataDrain(long timeout, TimeUnit unit)
    {
        Objects.requireNonNull(unit, "unit cannot be null");

        if(!mClosed)
        {
            throw new IllegalStateException("Channel processing manager must be closed before draining site metadata");
        }

        long timeoutNanos = Math.max(0L, unit.toNanos(timeout));
        long startedNanos = System.nanoTime();

        while(mSiteMetadataSubmissions.get() != 0)
        {
            long remainingNanos = timeoutNanos - (System.nanoTime() - startedNanos);

            if(remainingNanos <= 0 || Thread.currentThread().isInterrupted())
            {
                return false;
            }

            LockSupport.parkNanos(this, Math.min(SITE_METADATA_DRAIN_RETRY_NANOS, remainingNanos));
        }

        long accepted = mAcceptedSiteMetadata.get();

        while(mProcessedSiteMetadata.get() < accepted || mSiteMetadataIngress.size() != 0)
        {
            long remainingNanos = timeoutNanos - (System.nanoTime() - startedNanos);

            if(remainingNanos <= 0 || Thread.currentThread().isInterrupted())
            {
                return false;
            }

            signalSiteMetadataWorker();
            LockSupport.parkNanos(this, Math.min(SITE_METADATA_DRAIN_RETRY_NANOS, remainingNanos));
        }

        signalSiteMetadataWorker();
        return true;
    }

    int getSiteMetadataIngressCapacity()
    {
        return mSiteMetadataIngress.capacity();
    }

    int getSiteMetadataIngressSize()
    {
        return mSiteMetadataIngress.size();
    }

    long getDroppedSiteMetadataCount()
    {
        return mDroppedSiteMetadata.get();
    }

    private void dispatchProtocolSiteMetadata(ProtocolSiteMetadataEvent event)
    {
        if(event != null && !event.matchesCurrentChannel())
        {
            return;
        }

        mChannelActivityModel.receiveProtocolSiteMetadata(event);

        for(ProtocolSiteMetadataListener listener: mProtocolSiteMetadataListeners)
        {
            listener.receiveProtocolSiteMetadata(event);
        }
    }

    public void addAudioCallListener(Listener<AudioCallEvent> listener)
    {
        mAudioCallListeners.add(listener);
    }

    public void removeAudioCallListener(Listener<AudioCallEvent> listener)
    {
        mAudioCallListeners.remove(listener);
    }

    /**
     * Adds a message listener that will be added to all channels to receive
     * any messages.
     */
    public void addDecodeEventListener(Listener<IDecodeEvent> listener)
    {
        mDecodeEventListeners.add(listener);
    }

    /**
     * Removes a message listener.
     */
    public void removeDecodeEventListener(Listener<IDecodeEvent> listener)
    {
        mDecodeEventListeners.remove(listener);
    }

    /**
     * Adds a decoded-event listener that also receives the configured channel owning the processing chain.
     */
    public void addChannelDecodeEventListener(BiConsumer<Channel,IDecodeEvent> listener)
    {
        mChannelDecodeEventListeners.add(listener);
    }

    public void removeChannelDecodeEventListener(BiConsumer<Channel,IDecodeEvent> listener)
    {
        mChannelDecodeEventListeners.remove(listener);
    }

    public void addControlChannelQualityListener(Listener<ControlChannelQualitySnapshot> listener)
    {
        if(listener != null)
        {
            mControlChannelQualityListeners.add(listener);
        }
    }

    public void removeControlChannelQualityListener(Listener<ControlChannelQualitySnapshot> listener)
    {
        mControlChannelQualityListeners.remove(listener);
    }

    private void receiveControlChannelQuality(ControlChannelQualitySnapshot snapshot)
    {
        if(snapshot == null || !snapshot.matchesCurrentChannel())
        {
            return;
        }

        mChannelActivityModel.receiveControlChannelQuality(snapshot);

        for(Listener<ControlChannelQualitySnapshot> listener: mControlChannelQualityListeners)
        {
            listener.receive(snapshot);
        }
    }

    /**
     * Indicates if a standard parent channel supports live trunked control-channel quality.  DMR and NXDN use the
     * same decoder for conventional and trunked operation, so their monitor can run before trunking is observed;
     * {@link ChannelActivityModel} only attaches those measurements after an actual trunked site session exists.
     */
    static boolean supportsControlChannelQuality(Channel channel)
    {
        DecoderType decoderType = channel != null && channel.getDecodeConfiguration() != null ?
            channel.getDecodeConfiguration().getDecoderType() : null;
        return channel != null && channel.isStandardChannel() &&
            (decoderType == DecoderType.P25_PHASE1 || decoderType == DecoderType.P25_PHASE2 ||
                decoderType == DecoderType.DMR || decoderType == DecoderType.NXDN);
    }

    /**
     * Indicates whether a saved channel has a decoder and source that can safely enter the source-allocation path.
     * This check is deliberately performed before requesting a tuner source so that retired compatibility
     * configurations cannot reserve tuner bandwidth or create a persistent retry loop.
     */
    static boolean isRunnable(Channel channel)
    {
        return ChannelConfigurationPolicy.isActive(channel);
    }

    public void addSiteMetadataListener(SiteMetadataListener listener)
    {
        mSiteMetadataListeners.add(listener);
    }

    public void removeSiteMetadataListener(SiteMetadataListener listener)
    {
        mSiteMetadataListeners.remove(listener);
    }

    /**
     * Adds one listener for P25, DMR, and NXDN site metadata. Legacy P25 listeners remain supported separately.
     */
    public void addProtocolSiteMetadataListener(ProtocolSiteMetadataListener listener)
    {
        mProtocolSiteMetadataListeners.add(listener);
    }

    public void removeProtocolSiteMetadataListener(ProtocolSiteMetadataListener listener)
    {
        mProtocolSiteMetadataListeners.remove(listener);
    }

    /**
     * Adds a listener to receive channel events from this manager
     */
    public void addChannelEventListener(Listener<ChannelEvent> listener)
    {
        mChannelEventBroadcaster.addListener(listener);
    }

    /**
     * Removes the listener from receiving channel events from this manager
     */
    public void removeChannelEventListener(Listener<ChannelEvent> listener)
    {
        mChannelEventBroadcaster.removeListener(listener);
    }

    private static class TunerUnavailableChannelException extends ChannelException
    {
        private static final long serialVersionUID = 1L;

        private TunerUnavailableChannelException()
        {
            super("No Tuner Available");
        }
    }

    private static class DMRRestChannelAttempt
    {
        private final PreparedRestChannelHandoff mPrepared;
        private final ProcessingChain mExpectedChain;
        private final List<ProcessingChainMapping> mSiblingTrafficChains;
        private final Listener<ChannelEvent> mDetachedOwnerChannelEventRoute;
        private ScheduledFuture<?> mScheduledFuture;
        private boolean mActive = true;
        private boolean mConverted;
        private boolean mTrafficTeardownProcessed;
        private boolean mDetachedOwnerChannelEventRouteAttached;

        private DMRRestChannelAttempt(PreparedRestChannelHandoff prepared, ProcessingChain expectedChain,
                                      List<ProcessingChainMapping> siblingTrafficChains)
        {
            mPrepared = prepared;
            mExpectedChain = expectedChain;
            mSiblingTrafficChains = siblingTrafficChains;
            Listener<ChannelEvent> ownerListener = prepared.handoff().owner().getChannelEventListener();
            mDetachedOwnerChannelEventRoute = ownerListener::receive;
        }

        private PreparedRestChannelHandoff getPrepared()
        {
            return mPrepared;
        }

        private ProcessingChain getExpectedChain()
        {
            return mExpectedChain;
        }

        private List<ProcessingChainMapping> getSiblingTrafficChains()
        {
            return mSiblingTrafficChains;
        }

        private Listener<ChannelEvent> getDetachedOwnerChannelEventRoute()
        {
            return mDetachedOwnerChannelEventRoute;
        }

        private synchronized boolean attachDetachedOwnerChannelEventRoute()
        {
            if(mDetachedOwnerChannelEventRouteAttached)
            {
                return false;
            }

            mDetachedOwnerChannelEventRouteAttached = true;
            return true;
        }

        private synchronized boolean detachDetachedOwnerChannelEventRoute()
        {
            if(!mDetachedOwnerChannelEventRouteAttached)
            {
                return false;
            }

            mDetachedOwnerChannelEventRouteAttached = false;
            return true;
        }

        private boolean matches(DMRRestChannelHandoffRequest request)
        {
            return mPrepared.handoff() == request;
        }

        private synchronized boolean isActive()
        {
            return mActive;
        }

        private synchronized void setConverted()
        {
            mConverted = true;
        }

        private synchronized boolean isConverted()
        {
            return mConverted;
        }

        private synchronized boolean markTrafficTeardownProcessed()
        {
            if(mTrafficTeardownProcessed)
            {
                return false;
            }

            mTrafficTeardownProcessed = true;
            return true;
        }

        private synchronized void schedule(Runnable retry, long delayMilliseconds)
        {
            if(!mActive || (mScheduledFuture != null && !mScheduledFuture.isDone()))
            {
                return;
            }

            mScheduledFuture = ThreadPool.SCHEDULED.schedule(() ->
            {
                synchronized(this)
                {
                    mScheduledFuture = null;

                    if(!mActive)
                    {
                        return;
                    }
                }

                retry.run();
            }, delayMilliseconds, TimeUnit.MILLISECONDS);
        }

        private synchronized boolean deactivate()
        {
            if(!mActive)
            {
                return false;
            }

            mActive = false;

            if(mScheduledFuture != null)
            {
                mScheduledFuture.cancel(false);
                mScheduledFuture = null;
            }

            return true;
        }
    }

    private record ProcessingChainMapping(Channel channel, ProcessingChain processingChain)
    {
    }

    private record RemovedProcessingMapping(ProcessingChain processingChain, long processingIncarnation)
    {
    }

    /**
     * Monitors all channels for an error in the source event that would require the
     * channel's processing chain to be stopped
     */
    private class ChannelSourceEventErrorListener implements Listener<SourceEvent>
    {
        @Override public void receive(SourceEvent sourceEvent)
        {
            if(sourceEvent.getEvent() == SourceEvent.Event.NOTIFICATION_ERROR_STATE && sourceEvent.getSource() != null)
            {
                Channel toShutdown = null;

                mLock.lock();

                try
                {
                    for(Map.Entry<Channel,ProcessingChain> entry: mProcessingChainsMap.entrySet())
                    {
                        if(entry.getValue().hasSource(sourceEvent.getSource()))
                        {
                            toShutdown = entry.getKey();
                            break;
                        }
                    }
                }
                finally
                {
                    mLock.unlock();
                }

                if(toShutdown != null)
                {
                    if(sourceEvent.getEvent() == SourceEvent.Event.NOTIFICATION_ERROR_STATE)
                    {
                        mLog.warn("Channel source error detected - stopping channel [{}]", toShutdown.getName());
                    }
                    else
                    {
                        mLog.warn("Source event error - stopping channel [{}]", toShutdown.getName());
                    }

                    try
                    {
                        stopProcessing(toShutdown);
                    }
                    catch(ChannelException ce)
                    {
                        mLog.error("Error stopping channel [{}] with source error - {}", toShutdown.getName(),
                                ce.getMessage());
                    }
                    catch(Exception e)
                    {
                        mLog.error(ERROR_STOPPING_CHANNEL_LABEL + toShutdown + "]", e);
                    }
                }
            }
        }
    }

    /**
     * Creates a diagnostic report.
     * @return report text.
     */
    public String getDiagnosticInformation()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("Channel Processing Manager - Diagnostics Report\n\n");
        sb.append(DIVIDER);
        sb.append("\tChannel to Processing Chain Map Contents\n");
        Map<Channel,ProcessingChain> mapCopy = new HashMap<>(mProcessingChainsMap);
        for(Map.Entry<Channel,ProcessingChain> entry: mapCopy.entrySet())
        {
            sb.append("\n\n--------------- CHANNEL:PROCESSING CHAIN MAP ENTRY --------------------\n");
            try
            {
                Channel channel = entry.getKey();
                sb.append("\tChannel: ").append(channel).append("\n");
                sb.append("\t\tSource Configuration: ").append(channel.getSourceConfiguration()).append("\n");
                ProcessingChain chain = entry.getValue();
                sb.append("\tProcessing Chain - Processing: ").append(chain.isProcessing()).append("\n");
                AbstractChannelState state = chain.getChannelState();
                sb.append("Channel State: ").append(state.getClass()).append("\n");
                sb.append(" Teardown Started:").append(state.isTeardownSequenceStarted()).append("\n");
                sb.append(" Teardown Completed:").append(state.isTeardownSequenceCompleted()).append("\n");
                for(ChannelMetadata metadata: state.getChannelMetadata())
                {
                    sb.append(metadata.getDescription()).append("\n");
                }

                Source source = chain.getSource();

                if(source != null)
                {
                    sb.append("Channel Source Class: ").append(source.getClass()).append("\n");
                    sb.append("\t\tTo String:").append(source).append("\n");
                    sb.append("\t\tHash:").append(Integer.toHexString(source.hashCode()).toUpperCase()).append("\n");
                }
                else
                {
                    sb.append("Channel Source: (null)\n");
                }
            }
            catch(Exception e)
            {
                sb.append("\tError while logging diagnostics of map entry - ").append(e.getMessage()).append("\n");
            }
        }

        return sb.toString();
    }
}
