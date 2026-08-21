/*
 * *****************************************************************************
 * Copyright (C) 2014-2025 Dennis Sheirer
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

import com.google.common.eventbus.Subscribe;
import io.github.dsheirer.channel.state.ChangeChannelTimeoutEvent;
import io.github.dsheirer.channel.state.DecoderStateEvent;
import io.github.dsheirer.channel.state.DecoderStateEvent.Event;
import io.github.dsheirer.channel.state.State;
import io.github.dsheirer.channel.state.TimeslotDecoderState;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.Channel.ChannelType;
import io.github.dsheirer.controller.channel.ChannelConfigurationChangeNotification;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.alias.DmrTalkerAliasIdentifier;
import io.github.dsheirer.identifier.integer.IntegerIdentifier;
import io.github.dsheirer.identifier.radio.RadioIdentifier;
import io.github.dsheirer.identifier.talkgroup.TalkgroupIdentifier;
import io.github.dsheirer.log.LoggingSuppressor;
import io.github.dsheirer.message.EmptyTimeslotPlaceholderMessage;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.message.TimeslotMessage;
import io.github.dsheirer.metadata.site.ProtocolSiteMetadataPublisher;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.dmr.channel.DMRAbsoluteChannel;
import io.github.dsheirer.module.decode.dmr.channel.DMRChannel;
import io.github.dsheirer.module.decode.dmr.channel.DMRLsn;
import io.github.dsheirer.module.decode.dmr.event.DMRDecodeEvent;
import io.github.dsheirer.module.decode.dmr.message.DMRMessage;
import io.github.dsheirer.module.decode.dmr.message.data.DataMessage;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.CSBKMessage;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.Opcode;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.UnknownCSBKMessage;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.hytera.HyteraTrafficChannelTalkerStatus;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.motorola.CapacityMaxAdvantageModeVoiceChannelUpdate;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.motorola.CapacityMaxAloha;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.motorola.CapacityMaxOpenModeVoiceChannelUpdate;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.motorola.CapacityPlusNeighbors;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.motorola.CapacityPlusSiteStatus;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.motorola.ConnectPlusDataChannelGrant;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.motorola.ConnectPlusVoiceChannelUser;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.standard.Aloha;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.standard.Protect;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.standard.acknowledge.Acknowledge;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.standard.ahoy.Ahoy;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.standard.ahoy.AuthenticateRegisterRadioCheck;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.standard.ahoy.ServiceRadioCheck;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.standard.ahoy.StunReviveKill;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.standard.announcement.Announcement;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.standard.announcement.VoteNowAdvice;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.standard.grant.ChannelGrant;
import io.github.dsheirer.module.decode.dmr.message.data.header.HeaderMessage;
import io.github.dsheirer.module.decode.dmr.message.data.header.hytera.HyteraDataEncryptionHeader;
import io.github.dsheirer.module.decode.dmr.message.data.lc.LCMessage;
import io.github.dsheirer.module.decode.dmr.message.data.lc.full.GPSInformation;
import io.github.dsheirer.module.decode.dmr.message.data.lc.full.GroupVoiceChannelUser;
import io.github.dsheirer.module.decode.dmr.message.data.lc.full.TalkerAliasComplete;
import io.github.dsheirer.module.decode.dmr.message.data.lc.full.UnitToUnitVoiceChannelUser;
import io.github.dsheirer.module.decode.dmr.message.data.lc.full.hytera.HyteraGroupVoiceChannelUser;
import io.github.dsheirer.module.decode.dmr.message.data.lc.full.hytera.HyteraUnitToUnitVoiceChannelUser;
import io.github.dsheirer.module.decode.dmr.message.data.lc.full.motorola.CapacityMaxTalkerAlias;
import io.github.dsheirer.module.decode.dmr.message.data.lc.full.motorola.CapacityMaxTalkerAliasContinuation;
import io.github.dsheirer.module.decode.dmr.message.data.lc.full.motorola.CapacityMaxVoiceChannelUser;
import io.github.dsheirer.module.decode.dmr.message.data.lc.full.motorola.CapacityPlusEncryptedVoiceChannelUser;
import io.github.dsheirer.module.decode.dmr.message.data.lc.full.motorola.CapacityPlusWideAreaVoiceChannelUser;
import io.github.dsheirer.module.decode.dmr.message.data.lc.full.motorola.MotorolaGroupVoiceChannelUser;
import io.github.dsheirer.module.decode.dmr.message.data.lc.shorty.CapacityPlusRestChannel;
import io.github.dsheirer.module.decode.dmr.message.data.packet.DMRPacketMessage;
import io.github.dsheirer.module.decode.dmr.message.data.packet.UDTShortMessageService;
import io.github.dsheirer.module.decode.dmr.message.data.terminator.Terminator;
import io.github.dsheirer.module.decode.dmr.message.type.ServiceOptions;
import io.github.dsheirer.module.decode.dmr.message.type.Reason;
import io.github.dsheirer.module.decode.dmr.message.voice.VoiceEMBMessage;
import io.github.dsheirer.module.decode.dmr.message.voice.VoiceMessage;
import io.github.dsheirer.module.decode.dmr.message.voice.embedded.EmbeddedEncryptionParameters;
import io.github.dsheirer.module.decode.dmr.message.voice.embedded.EmbeddedParameters;
import io.github.dsheirer.module.decode.dmr.sync.DMRSyncPattern;
import io.github.dsheirer.module.decode.dmr.telemetry.DMRNetworkConfigurationSnapshot;
import io.github.dsheirer.module.decode.event.DecodeEvent;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.event.IDecodeEvent;
import io.github.dsheirer.module.decode.event.PlottableDecodeEvent;
import io.github.dsheirer.module.decode.ip.hytera.rrs.HyteraRrsPacket;
import io.github.dsheirer.module.decode.ip.hytera.sds.HyteraUnknownPacket;
import io.github.dsheirer.module.decode.ip.hytera.shortdata.HyteraShortDataPacket;
import io.github.dsheirer.module.decode.ip.hytera.sms.HyteraSmsPacket;
import io.github.dsheirer.module.decode.ip.mototrbo.ars.ARSPacket;
import io.github.dsheirer.module.decode.ip.mototrbo.lrrp.LRRPPacket;
import io.github.dsheirer.module.decode.ip.mototrbo.tms.TMSPacket;
import io.github.dsheirer.module.decode.ip.mototrbo.xcmp.XCMPPacket;
import io.github.dsheirer.preference.encryption.VoiceEncryptionDisplay;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.source.tuner.channel.rotation.AddChannelRotationActiveStateRequest;
import io.github.dsheirer.util.PacketUtil;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.jdesktop.swingx.mapviewer.GeoPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decoder state for an DMR channel.  Maintains the call/data/idle state of the channel and produces events by
 * monitoring the decoded message stream.
 */
public class DMRDecoderState extends TimeslotDecoderState
{
    private static final Logger mLog = LoggerFactory.getLogger(DMRDecoderState.class);
    private static final LoggingSuppressor LOGGING_SUPPRESSOR = new LoggingSuppressor(mLog);
        private static final String NO_TCM_CHANNEL_GRANT_MESSAGE =
            "No DMR Traffic Channel Manager available for channel grant-";
    private static final AddChannelRotationActiveStateRequest CAPACITY_PLUS_ACTIVE_STATE_REQUEST =
            new AddChannelRotationActiveStateRequest(State.ACTIVE);
    private final AtomicReference<OperationalMode> mOperationalMode = new AtomicReference<>();
    private final DMRTrafficChannelManager mTrafficChannelEventManager;
    private DecodeEvent mCurrentCallEvent;
    private boolean mCurrentCallEncrypted;
    private boolean mIgnoreCRCChecksums;
    private final boolean mTrunkingEnabled;
    private DMRDecoderState mSisterDecoderState;
    private volatile Runnable mBeforeChannelGrantAuthorityCheckForTest;
    private volatile Runnable mAfterChannelGrantAuthorityAcquiredForTest;

    /**
     * Constructs an DMR decoder state with an optional traffic channel manager.
     * @param channel with configuration details
     * @param timeslot for this decoder state (1 or 2)
     * @param trafficChannelManager for handling traffic channel grants.
     */
    public DMRDecoderState(Channel channel, int timeslot, DMRTrafficChannelManager trafficChannelManager)
    {
        super(timeslot);
        mTrafficChannelEventManager = trafficChannelManager;
        DecodeConfigDMR config = channel.getDecodeConfiguration() instanceof DecodeConfigDMR dmrConfig ?
            dmrConfig : null;
        mTrunkingEnabled = config != null && config.isTrunked();

        //The decoder state passes all messages to the network configuration monitor, so we only construct
        //the monitor for timeslot 1.
        DMRNetworkConfigurationMonitor networkConfigurationMonitor = null;
        ProtocolSiteMetadataPublisher siteMetadataPublisher = null;

        if(timeslot == 1)
        {
            networkConfigurationMonitor = new DMRNetworkConfigurationMonitor(
                config != null ? config.getTimeslotMap() : List.of());

            if(mTrunkingEnabled)
            {
                siteMetadataPublisher = new ProtocolSiteMetadataPublisher(channel,
                    this::snapshotNetworkConfiguration, this::hasInterModuleEventBus, event -> {
                        var eventBus = getInterModuleEventBus();

                        if(eventBus != null)
                        {
                            eventBus.post(event);
                        }
                });
            }
        }

        mOperationalMode.set(new OperationalMode(0, channel, trafficChannelManager,
            trafficChannelManager != null ? new AllocationAuthority() : null, networkConfigurationMonitor, null,
            siteMetadataPublisher, null, null, null));

        //For RAS protected systems, allows user to ignore CRC checksums and still decode the system
        if(config != null)
        {
            mIgnoreCRCChecksums = config.getIgnoreCRCChecksums();
        }
    }

    /**
     * Registers a reference to the decoder state that is processing the sisten timeslot.  If this state is covering
     * timeslot 1, then the argument will be the state covering timeslot 2, and vice-versa.
     */
    public void setSisterDecoderState(DMRDecoderState state)
    {
        mSisterDecoderState = state;
    }

    /**
     * Processes a map of active talkgroups received from the sister timeslot decoder state so that we can recover
     * the current channel identifier to enable populating call events with accurate current channel info.
     *
     * This is used for Capacity Plus systems to recover the current channel.
     *
     * @param idMap to process
     * @param lsnMap to process
     */
    public DMRChannel processActiveTalkgroups(Map<Integer, IntegerIdentifier> idMap, Map<Integer, DMRLsn> lsnMap)
    {
        if(mTrunkingEnabled && mCurrentCallEvent != null)
        {
            List<Identifier> toIds = mCurrentCallEvent.getIdentifierCollection().getIdentifiers(Role.TO);

            if(toIds.size() >= 1)
            {
                Identifier to = toIds.get(0);

                if(to instanceof TalkgroupIdentifier talkgroup)
                {
                    Integer tgValue = talkgroup.getValue();

                    for(Map.Entry<Integer,IntegerIdentifier> entry: idMap.entrySet())
                    {
                        if(tgValue != null && tgValue.equals(entry.getValue().getValue()))
                        {
                            DMRLsn lsn = lsnMap.get(entry.getKey());
                            setCurrentChannel(lsn);
                            return lsn;
                        }
                    }
                }
            }
        }

        return null;
    }

    @Override
    protected void broadcast(IDecodeEvent event)
    {
        super.broadcast(event);

        if(mOperationalMode.get().channel().isTrafficChannel() && mTrafficChannelEventManager != null)
        {
            mTrafficChannelEventManager.receiveTrafficChannelEvent(event);
        }
    }

    /**
     * Indicates if the message is valid or if the Ignore CRC Checksums feature is enabled.
     * @param message to check
     * @return true if ignore CRC checksums or if the message is valid, meaning the message has passed CRC check.
     */
    private boolean isValid(IMessage message)
    {
        return message != null && (mIgnoreCRCChecksums || message.isValid());
    }

    /**
     * Sets the initial call event for a traffic channel.
     * @param decodeEvent to use as the initial call event.
     */
    public void setCurrentCallEvent(DecodeEvent decodeEvent)
    {
        mCurrentCallEvent = decodeEvent;
        setCurrentChannel(decodeEvent.getChannelDescriptor());
    }

    /**
     * Processes channel configuration change notifications received over the processing chain event bus.  This is
     * primarily used for Capacity+ systems when the standard channel is converted to a traffic channel.  In response,
     * we nullify the manager reference used for channel conversions and allocations.  The reporting-only reference is
     * retained so that completed traffic events and talker aliases can still reach the owning control channel.
     *
     * @param notification of channel configuration change
     */
    @Subscribe
    public void channelChanged(ChannelConfigurationChangeNotification notification)
    {
        if(notification.getChannel().isTrafficChannel())
        {
            mOperationalMode.updateAndGet(mode -> mode.asTraffic(notification.getChannel()));
            broadcast(new ChangeChannelTimeoutEvent(this, ChannelType.TRAFFIC, 1000, getTimeslot()));
        }
    }

    /**
     * Revokes allocation authority before the lifecycle owner changes processing-chain ownership.  This is a single
     * lock-free publication and does not run lifecycle work on the decoder callback.
     */
    @Subscribe
    public void suspendChannelConfigurationTransition(
        DMRChannelConfigurationTransitionNotification.Suspend notification)
    {
        OperationalMode operationalMode = mOperationalMode.get();

        if(operationalMode.isSuspendedBy(notification))
        {
            notification.acknowledge(this);
        }
        else if(operationalMode.canSuspendAllocationAuthority(notification))
        {
            AllocationAuthority authority = operationalMode.allocationAuthority();

            //A conversion never waits for a decoder callback.  If any manager dispatch is already in flight, this
            //subscriber withholds its acknowledgment and the lifecycle owner aborts/retries the conversion.
            if(authority.revokeIfIdle())
            {
                OperationalMode suspended = mOperationalMode.updateAndGet(mode ->
                    mode.hasSameAllocationAuthority(operationalMode) ? mode.asSuspended(notification) : mode);

                if(suspended.isSuspendedBy(notification))
                {
                    notification.acknowledge(this);
                }
            }
        }
    }

    /**
     * Restores allocation authority only for the exact suspension that is still active.  Restoration always uses a
     * fresh generation so a callback captured before suspension cannot regain authority through an ABA transition.
     */
    @Subscribe
    public void rollbackChannelConfigurationTransition(
        DMRChannelConfigurationTransitionNotification.Rollback notification)
    {
        mOperationalMode.updateAndGet(mode -> mode.rollbackAllocationAuthority(notification.getSuspension()));
    }

    /**
     * Identifies the decoder type
     */
    @Override
    public DecoderType getDecoderType()
    {
        return DecoderType.DMR;
    }

    /**
     * Performs a full reset to prepare this object for reuse on a new channel
     */
    @Override
    public void reset()
    {
        super.reset();
        resetState();
        setCurrentFrequency(0);
    }

    /**
     * Resets any temporal state details
     */
    @Override
    protected void resetState()
    {
        super.resetState();
        closeCurrentCallEvent(System.currentTimeMillis());
    }

    /**
     * Primary message processing method.
     */
    @Override
    public void receive(IMessage message)
    {
        OperationalMode operationalMode = mOperationalMode.get();

        if(message.getTimeslot() == getTimeslot())
        {
            if(message instanceof VoiceMessage voice)
            {
                processVoice(voice);
            }
            else if(message instanceof DataMessage data)
            {
                processData(data, operationalMode);
            }
            else if(isValid(message) && message instanceof LCMessage lcMessage)
            {
                processLinkControl(lcMessage, false, operationalMode);
            }
            else if(isValid(message) && message instanceof DMRPacketMessage packet)
            {
                processPacket(packet);
            }
            else if(message instanceof UDTShortMessageService sms)
            {
                processSMS(sms);
            }
            else if(isValid(message) && message instanceof DMRMessage)
            {
                broadcast(new DecoderStateEvent(this, Event.CONTINUATION, State.ACTIVE, getTimeslot()));
            }
            else if(message instanceof EmptyTimeslotPlaceholderMessage)
            {
                broadcast(new DecoderStateEvent(this, Event.CONTINUATION, State.ACTIVE, getTimeslot()));
            }
        }
        //SLCO messages on timeslot 0 to catch capacity plus rest channel events
        else if(isValid(message) && message.getTimeslot() == 0 && message instanceof LCMessage lcMessage)
        {
            processLinkControl(lcMessage, false, operationalMode);
        }

        //Pass the message to the network configuration monitor, if this decoder state has a non-null instance
        DMRNetworkConfigurationMonitor networkConfigurationMonitor = operationalMode.networkConfigurationMonitor();

        if(networkConfigurationMonitor != null && isValid(message) && message instanceof DMRMessage dmrMessage)
        {
            networkConfigurationMonitor.process(dmrMessage);
            ProtocolSiteMetadataPublisher siteMetadataPublisher = operationalMode.siteMetadataPublisher();

            if(siteMetadataPublisher != null)
            {
                siteMetadataPublisher.publish(dmrMessage.getTimestamp());
            }
        }

        offerRestChannelHandoff(operationalMode);
    }

    private DMRNetworkConfigurationSnapshot snapshotNetworkConfiguration()
    {
        OperationalMode operationalMode = mOperationalMode.get();
        DMRNetworkConfigurationMonitor monitor = operationalMode.networkConfigurationMonitor();
        DMRNetworkConfigurationSnapshot snapshot = monitor != null ? monitor.getSnapshot() : null;

        if(snapshot != null)
        {
            OperationalMode updated = operationalMode.withNetworkConfigurationSnapshot(snapshot);

            if(mOperationalMode.compareAndSet(operationalMode, updated))
            {
                AllocationAuthority authority = acquireCurrentAllocationAuthority(updated);

                if(authority != null)
                {
                    try
                    {
                        updated.allocationManager().updateNetworkConfigurationSnapshot(snapshot);
                    }
                    finally
                    {
                        authority.release();
                    }
                }
            }
        }

        return snapshot;
    }

    /**
     * Processes Capacity Plus rest channel notifications to detect when the rest channel has changed.  When a new
     * rest channel is specified that is different from the current channel, notify the traffic channel manager so that
     * it can convert the current channel to a traffic channel and start a new channel for the rest channel, using the
     * current channel's configuration details and the specified rest channel frequency.  This approach ensures that
     * there is no disruption to the currently processing channel and that the original channel configuration can be
     * recreated to follow the rest channel.
     *
     * @param restChannel currently indicated
     */
    private void updateRestChannel(DMRChannel restChannel, OperationalMode operationalMode)
    {
        Channel channel = operationalMode.channel();
        DMRTrafficChannelManager trafficChannelManager = operationalMode.allocationManager();
        long currentFrequency = getCurrentFrequency();

        //Only respond if this is a standard/control channel (not a traffic channel).
        if(channel.isStandardChannel() && currentFrequency > 0 &&
            restChannel.getDownlinkFrequency() > 0 &&
            restChannel.getDownlinkFrequency() != currentFrequency && trafficChannelManager != null &&
            hasCurrentAllocationAuthority(operationalMode))
        {
            OperationalMode current = mOperationalMode.get();

            if(current.hasSameAllocationAuthority(operationalMode))
            {
                RestChannelHandoffCandidate candidate = new RestChannelHandoffCandidate(
                    operationalMode.authorityGeneration(), channel, trafficChannelManager, currentFrequency,
                    restChannel);
                mOperationalMode.compareAndSet(current, current.withRestChannelHandoffCandidate(candidate));
            }
        }
    }

    /**
     * Preloads the initial channel grant event for a dynamically allocated traffic channel.  Only the decoder state
     * for the granted timeslot maintains the event.  The sister state receives the matching alternate-timeslot channel
     * descriptor so that both states have the same repeater context.
     *
     * @param preloadData containing the request-scoped initial grant event
     */
    @Subscribe
    public void preload(DMRChannelGrantPreloadData preloadData)
    {
        if(preloadData.hasData())
        {
            DecodeEvent event = preloadData.getChannelGrantEvent();

            if(event.getTimeslot() == getTimeslot())
            {
                setCurrentCallEvent(event);

                if(mSisterDecoderState != null && event.getChannelDescriptor() instanceof DMRChannel dmrChannel)
                {
                    mSisterDecoderState.setCurrentChannel(dmrChannel.getSisterTimeslot());
                }
            }
        }
    }

    /**
     * Seeds a replacement rest-channel monitor from immutable learned state.  The old mutable monitor is never linked
     * to the new chain.
     */
    @Subscribe
    public void preload(DMRRestChannelNetworkConfigurationPreloadData preloadData)
    {
        if(getTimeslot() == 1 && preloadData.hasData())
        {
            OperationalMode operationalMode = mOperationalMode.get();
            DMRNetworkConfigurationMonitor monitor = operationalMode.networkConfigurationMonitor();

            if(monitor != null)
            {
                monitor.seed(preloadData.getSnapshot());
                OperationalMode updated = operationalMode.withNetworkConfigurationSnapshot(preloadData.getSnapshot());

                if(mOperationalMode.compareAndSet(operationalMode, updated))
                {
                    AllocationAuthority authority = acquireCurrentAllocationAuthority(updated);

                    if(authority != null)
                    {
                        try
                        {
                            updated.allocationManager().updateNetworkConfigurationSnapshot(preloadData.getSnapshot());
                        }
                        finally
                        {
                            authority.release();
                        }
                    }
                }
            }
        }
    }

    private void offerRestChannelHandoff(OperationalMode callbackMode)
    {
        OperationalMode current = mOperationalMode.get();
        RestChannelHandoffCandidate candidate = current.restChannelHandoffCandidate();

        if(candidate == null || !current.hasSameAllocationAuthority(callbackMode) ||
            candidate.authorityGeneration() != current.authorityGeneration())
        {
            return;
        }

        OperationalMode cleared = current.withRestChannelHandoffCandidate(null);

        if(mOperationalMode.compareAndSet(current, cleared))
        {
            AllocationAuthority authority = acquireCurrentAllocationAuthority(cleared);

            if(authority != null)
            {
                try
                {
                    candidate.manager().requestRestChannelHandoff(candidate.channel(), candidate.currentFrequency(),
                        candidate.restChannel(), cleared.latestNetworkConfigurationSnapshot());
                }
                finally
                {
                    authority.release();
                }
            }
        }
    }

    /**
     * Immutable decoder ownership and network-observation state.  Replacing this single reference is the conversion
     * publication point, so callbacks never combine a traffic channel with stale control-channel allocation authority.
     */
    private record OperationalMode(long authorityGeneration, Channel channel,
                                   DMRTrafficChannelManager allocationManager,
                                   AllocationAuthority allocationAuthority,
                                   DMRNetworkConfigurationMonitor networkConfigurationMonitor,
                                   DMRNetworkConfigurationSnapshot latestNetworkConfigurationSnapshot,
                                   ProtocolSiteMetadataPublisher siteMetadataPublisher,
                                   RestChannelHandoffCandidate restChannelHandoffCandidate,
                                   DMRChannelConfigurationTransitionNotification.Suspend authoritySuspension,
                                   OperationalMode rollbackMode)
    {
        private boolean hasAllocationAuthority()
        {
            return channel != null && channel.isStandardChannel() && allocationManager != null &&
                allocationAuthority != null;
        }

        private boolean isSuspendedBy(DMRChannelConfigurationTransitionNotification.Suspend suspension)
        {
            return authoritySuspension == suspension && allocationManager == null;
        }

        private boolean hasSameAllocationAuthority(OperationalMode other)
        {
            return other != null && hasAllocationAuthority() && other.hasAllocationAuthority() &&
                authorityGeneration == other.authorityGeneration && channel == other.channel &&
                allocationManager == other.allocationManager && allocationAuthority == other.allocationAuthority;
        }

        private OperationalMode asTraffic(Channel trafficChannel)
        {
            return new OperationalMode(authorityGeneration + 1, trafficChannel, null, null, null, null, null, null,
                null, null);
        }

        private boolean canSuspendAllocationAuthority(
            DMRChannelConfigurationTransitionNotification.Suspend suspension)
        {
            return authoritySuspension == null && hasAllocationAuthority() &&
                suspension.getTargetChannel().isTrafficChannel();
        }

        private OperationalMode asSuspended(DMRChannelConfigurationTransitionNotification.Suspend suspension)
        {
            OperationalMode rollback = new OperationalMode(authorityGeneration, channel, allocationManager,
                allocationAuthority, networkConfigurationMonitor, latestNetworkConfigurationSnapshot,
                siteMetadataPublisher, null, null, null);
            return new OperationalMode(authorityGeneration + 1, channel, null, null, networkConfigurationMonitor,
                latestNetworkConfigurationSnapshot, siteMetadataPublisher, null, suspension, rollback);
        }

        private OperationalMode rollbackAllocationAuthority(
            DMRChannelConfigurationTransitionNotification.Suspend suspension)
        {
            if(authoritySuspension != suspension || rollbackMode == null)
            {
                return this;
            }

            DMRNetworkConfigurationSnapshot snapshot = latestNetworkConfigurationSnapshot != null ?
                latestNetworkConfigurationSnapshot : rollbackMode.latestNetworkConfigurationSnapshot();
            return new OperationalMode(authorityGeneration + 1, rollbackMode.channel(),
                rollbackMode.allocationManager(), new AllocationAuthority(),
                rollbackMode.networkConfigurationMonitor(), snapshot, rollbackMode.siteMetadataPublisher(), null,
                null, null);
        }

        private OperationalMode withNetworkConfigurationSnapshot(DMRNetworkConfigurationSnapshot snapshot)
        {
            return new OperationalMode(authorityGeneration, channel, allocationManager, allocationAuthority,
                networkConfigurationMonitor, snapshot, siteMetadataPublisher, restChannelHandoffCandidate,
                authoritySuspension, rollbackMode);
        }

        private OperationalMode withRestChannelHandoffCandidate(RestChannelHandoffCandidate candidate)
        {
            return new OperationalMode(authorityGeneration, channel, allocationManager, allocationAuthority,
                networkConfigurationMonitor, latestNetworkConfigurationSnapshot, siteMetadataPublisher, candidate,
                authoritySuspension, rollbackMode);
        }
    }

    /**
     * Nonblocking lease for allocation-manager side effects.  An atomic increment lets concurrent callbacks acquire
     * without losing functional work to CAS contention.  Suspension succeeds only from the idle value, so an
     * acknowledged suspension proves no dispatch is in flight.
     */
    private static final class AllocationAuthority
    {
        private static final long REVOKED = Long.MIN_VALUE;
        private final AtomicLong mInFlightDispatches = new AtomicLong();

        private boolean tryAcquire()
        {
            long previous = mInFlightDispatches.getAndIncrement();

            if(previous >= 0)
            {
                return true;
            }

            mInFlightDispatches.decrementAndGet();
            return false;
        }

        private void release()
        {
            mInFlightDispatches.decrementAndGet();
        }

        private boolean revokeIfIdle()
        {
            return mInFlightDispatches.compareAndSet(0, REVOKED);
        }

    }

    private record RestChannelHandoffCandidate(long authorityGeneration, Channel channel,
                                                DMRTrafficChannelManager manager, long currentFrequency,
                                                DMRChannel restChannel)
    {
    }

    /**
     * Processes a short data message carrying SMS text
     * @param sms
     */
    private void processSMS(UDTShortMessageService sms)
    {
        broadcast(new DecoderStateEvent(this, Event.START, State.DATA, getTimeslot()));

        DecodeEvent smsEvent = DMRDecodeEvent.builder(DecodeEventType.SMS, sms.getTimestamp())
                .channel(getCurrentChannel())
                .details("MESSAGE: " + sms.getSMS())
                .identifiers(new IdentifierCollection(sms.getIdentifiers()))
                .timeslot(getTimeslot())
                .build();
        broadcast(smsEvent);
    }

    /**
     * Processes a packet message
     */
    private void processPacket(DMRPacketMessage packet)
    {
        broadcast(new DecoderStateEvent(this, Event.START, State.DATA, getTimeslot()));

        //Hytera SDS Long SMS message
        if(packet.getPacket() instanceof HyteraSmsPacket hyteraSmsPacket)
        {
            MutableIdentifierCollection mic = new MutableIdentifierCollection(packet.getIdentifiers());
            mic.remove(Form.RADIO);
            mic.remove(Form.TALKGROUP);
            mic.update(hyteraSmsPacket.getSource());
            mic.update(hyteraSmsPacket.getDestination());

            DecodeEvent smsEvent = DMRDecodeEvent.builder(DecodeEventType.SMS, packet.getTimestamp())
                    .channel(getCurrentChannel())
                    .identifiers(mic)
                    .timeslot(getTimeslot())
                    .details("SMS:" + hyteraSmsPacket.getSMS())
                    .build();
            broadcast(smsEvent);
        }
        //Hytera Radio Registration Service (RRS)
        else if(packet.getPacket() instanceof HyteraRrsPacket rrs)
        {
            MutableIdentifierCollection mic = new MutableIdentifierCollection(packet.getIdentifiers());

            StringBuilder sb = new StringBuilder();
            sb.append("HYTERA RRS REGISTER RADIO:");
            sb.append(rrs.getDestination());
            DecodeEvent shortDataEvent = DMRDecodeEvent.builder(DecodeEventType.RADIO_REGISTRATION_SERVICE,
                    packet.getTimestamp())
                    .channel(getCurrentChannel())
                    .identifiers(mic)
                    .timeslot(getTimeslot())
                    .details(sb.toString())
                    .build();
            broadcast(shortDataEvent);
        }
        //Hytera Short Data
        else if(packet.getPacket() instanceof HyteraShortDataPacket hsdp)
        {
            MutableIdentifierCollection mic = new MutableIdentifierCollection(packet.getIdentifiers());

            StringBuilder sb = new StringBuilder();
            sb.append("HYTERA");

            if(hsdp.getPacketSequence().isEncrypted())
            {
                HyteraDataEncryptionHeader hdeh = (HyteraDataEncryptionHeader)hsdp.getPacketSequence().getProprietaryDataHeader();
                sb.append(" ENCRYPTED ALGORITHM:").append(hdeh.getAlgorithm());
                sb.append(" KEY:").append(VoiceEncryptionDisplay.formatKeyId(hdeh.getKeyId()));
                sb.append(" IV:").append(hdeh.getIV());
            }

            sb.append(" SHORT DATA:").append(hsdp.getMessage().toHexString());

            DecodeEvent shortDataEvent = DMRDecodeEvent.builder(DecodeEventType.SDM, packet.getTimestamp())
                    .channel(getCurrentChannel())
                    .identifiers(mic)
                    .timeslot(getTimeslot())
                    .details(sb.toString())
                    .build();
            broadcast(shortDataEvent);
        }
        //Unknown Hytera Long Data Service Token Message
        else if(packet.getPacket() instanceof HyteraUnknownPacket hyteraUnknownPacket)
        {
            MutableIdentifierCollection mic = new MutableIdentifierCollection(packet.getIdentifiers());

            DecodeEvent unknownTokenEvent = DMRDecodeEvent.builder(DecodeEventType.UNKNOWN_PACKET, packet.getTimestamp())
                    .channel(getCurrentChannel())
                    .identifiers(mic)
                    .timeslot(getTimeslot())
                    .details("HYTERA LONG DATA UNK TOKEN MSG:" + hyteraUnknownPacket.getHeader().toString())
                    .build();
            broadcast(unknownTokenEvent);
        }
        //Motorola ARS
        else if(packet.getPacket() instanceof ARSPacket ars)
        {
            MutableIdentifierCollection mic = new MutableIdentifierCollection(packet.getIdentifiers());

            DecodeEvent shortDataEvent = DMRDecodeEvent.builder(DecodeEventType.RADIO_REGISTRATION_SERVICE, packet.getTimestamp())
                    .channel(getCurrentChannel())
                    .identifiers(mic)
                    .timeslot(getTimeslot())
                    .details(ars.toString())
                    .build();
            broadcast(shortDataEvent);
        }
        //Motorola LRRP
        else if(packet.getPacket() instanceof LRRPPacket lrrp)
        {
            MutableIdentifierCollection mic = new MutableIdentifierCollection(packet.getIdentifiers());

            DecodeEvent shortDataEvent = DMRDecodeEvent.builder(DecodeEventType.LRRP, packet.getTimestamp())
                    .channel(getCurrentChannel())
                    .identifiers(mic)
                    .timeslot(getTimeslot())
                    .details(lrrp.toString())
                    .build();
            broadcast(shortDataEvent);
        }
        //Motorola TMS
        else if(packet.getPacket() instanceof TMSPacket tms)
        {
            MutableIdentifierCollection mic = new MutableIdentifierCollection(packet.getIdentifiers());

            DecodeEvent shortDataEvent = DMRDecodeEvent.builder(DecodeEventType.TEXT_MESSAGE, packet.getTimestamp())
                    .channel(getCurrentChannel())
                    .identifiers(mic)
                    .timeslot(getTimeslot())
                    .details(tms.toString())
                    .build();
            broadcast(shortDataEvent);
        }
        //Motorola XCMP
        else if(packet.getPacket() instanceof XCMPPacket xcmp)
        {
            MutableIdentifierCollection mic = new MutableIdentifierCollection(packet.getIdentifiers());

            DecodeEvent shortDataEvent = DMRDecodeEvent.builder(DecodeEventType.XCMP, packet.getTimestamp())
                    .channel(getCurrentChannel())
                    .identifiers(mic)
                    .timeslot(getTimeslot())
                    .details(xcmp.toString())
                    .build();
            broadcast(shortDataEvent);
        }
        else
        {
            DecodeEvent packetEvent = DMRDecodeEvent.builder(DecodeEventType.DATA_PACKET, packet.getTimestamp())
                    .channel(getCurrentChannel())
                    .identifiers(new IdentifierCollection(packet.getIdentifiers()))
                    .timeslot(getTimeslot())
                    .details(packet.toString())
                    .build();

            broadcast(packetEvent);

        }

        GeoPosition geoPosition = PacketUtil.extractGeoPosition(packet.getPacket());

        if (geoPosition != null)
        {
            PlottableDecodeEvent plottableDecodeEvent = PlottableDecodeEvent.plottableBuilder(DecodeEventType.GPS, packet.getTimestamp())
                    .channel(getCurrentChannel())
                    .identifiers(getMergedIdentifierCollection(packet.getIdentifiers()))
                    .protocol(Protocol.LRRP)
                    .location(geoPosition)
                    .build();

            broadcast(plottableDecodeEvent);
        }
    }

    /**
     * Processes voice messages
     */
    private void processVoice(VoiceMessage message)
    {
        if(message.getSyncPattern().isMobileSyncPattern())
        {
            if(message.getSyncPattern().isDirect())
            {
                updateCurrentCall(DecodeEventType.CALL, "DIRECT MODE", message.getTimestamp());
            }
            else
            {
                updateCurrentCall(DecodeEventType.CALL, "REPEATER", message.getTimestamp());
            }
        }
        else
        {
            broadcast(new DecoderStateEvent(this, Event.CONTINUATION, State.CALL, getTimeslot()));
        }

        if(message.getSyncPattern() == DMRSyncPattern.BS_VOICE_FRAME_F && message instanceof VoiceEMBMessage voiceEmb &&
            voiceEmb.hasEmbeddedParameters())
        {
            EmbeddedParameters embedded = voiceEmb.getEmbeddedParameters();

            if(embedded.getShortBurst() instanceof EmbeddedEncryptionParameters arc4)
            {
                updateEncryptedCall(arc4, true, voiceEmb.getTimestamp());
            }
        }
    }

    /**
     * Processes a voice header message
     */
    private void processHeader(HeaderMessage header, OperationalMode operationalMode)
    {
        switch(header.getSlotType().getDataType())
        {
            case VOICE_HEADER:
                broadcast(new DecoderStateEvent(this, Event.START, State.CALL, getTimeslot()));
                break;
            case PI_HEADER, MBC_HEADER, DATA_HEADER, USB_DATA, MBC_ENC_HEADER, DATA_ENC_HEADER,
                CHANNEL_CONTROL_ENC_HEADER:
                broadcast(new DecoderStateEvent(this, Event.START, State.DATA, getTimeslot()));
                break;
            default:
                break;
        }

        //Process the link control message to get the identifiers
        LCMessage lc = header.getLCMessage();

        if(isValid(lc))
        {
            processLinkControl(lc, false, operationalMode);
        }
    }

    /**
     * Process Data Messages
     *
     * Note: invalid messages are allowed to pass to this method.  Messages are selectively checked for isValid()
     * to overcome RAS implementation in certain systems.
     */
    private void processData(DataMessage message, OperationalMode operationalMode)
    {
        switch(message.getSlotType().getDataType())
        {
            case CSBK:
                if(isValid(message) && message instanceof CSBKMessage csbk)
                {
                    processCSBK(csbk, operationalMode);
                }
                break;
            case VOICE_HEADER:
                if(message instanceof HeaderMessage header)
                {
                    processVoiceHeader(header, operationalMode);
                }
                break;
            case USB_DATA:
                break;
            case PI_HEADER, MBC_HEADER, DATA_HEADER, MBC_ENC_HEADER, DATA_ENC_HEADER,
                CHANNEL_CONTROL_ENC_HEADER:
                if(message instanceof HeaderMessage header)
                {
                    processHeader(header, operationalMode);
                }
                break;
            case SLOT_IDLE:
                closeCurrentCallEvent(message.getTimestamp());
                getIdentifierCollection().remove(IdentifierClass.USER);
                broadcast(new DecoderStateEvent(this, Event.CONTINUATION, State.ACTIVE, getTimeslot()));
                DMRNetworkConfigurationMonitor networkConfigurationMonitor =
                    operationalMode.networkConfigurationMonitor();

                if(networkConfigurationMonitor != null)
                {
                    networkConfigurationMonitor.process(message);
                }
                break;
            case TLC:
                if(message instanceof Terminator terminator)
                {
                    processTerminator(terminator, operationalMode);
                }
                break;
            case RATE_1_OF_2_DATA, RATE_3_OF_4_DATA, RATE_1_DATA:
                broadcast(new DecoderStateEvent(this, Event.START, State.DATA, getTimeslot()));
                break;
            case MBC_BLOCK, RESERVED_15, UNKNOWN:
            default:
                broadcast(new DecoderStateEvent(this, Event.CONTINUATION, State.ACTIVE, getTimeslot()));
                break;
        }
    }

    /**
     * Process terminator with link control messages
     */
    private void processTerminator(Terminator terminator)
    {
        processTerminator(terminator, mOperationalMode.get());
    }

    private void processTerminator(Terminator terminator, OperationalMode operationalMode)
    {
        LCMessage lcMessage = terminator.getLCMessage();

        if(isValid(lcMessage))
        {
            processLinkControl(lcMessage, true, operationalMode);
        }

        closeCurrentCallEvent(terminator.getTimestamp());
        getIdentifierCollection().remove(Role.FROM);
        broadcast(new DecoderStateEvent(this, Event.CONTINUATION, State.ACTIVE, getTimeslot()));
    }

    /**
     * Process a voice header message
     */
    private void processVoiceHeader(HeaderMessage voiceHeader, OperationalMode operationalMode)
    {
        LCMessage lcMessage = voiceHeader.getLCMessage();

        if(isValid(lcMessage))
        {
            processLinkControl(lcMessage, false, operationalMode);
        }
    }

    private void processCSBK(CSBKMessage csbk)
    {
        processCSBK(csbk, mOperationalMode.get());
    }

    private void processCSBK(CSBKMessage csbk, OperationalMode operationalMode)
    {
        switch(csbk.getOpcode())
        {
            case STANDARD_ACKNOWLEDGE_RESPONSE_INBOUND_PAYLOAD, STANDARD_ACKNOWLEDGE_RESPONSE_OUTBOUND_PAYLOAD:
                if(csbk instanceof Acknowledge acknowledge)
                {
                    broadcast(getDecodeEvent(csbk, acknowledgeEventType(acknowledge.getReason()),
                            acknowledge.getReason().toString()));
                }
                broadcast(new DecoderStateEvent(this, Event.CONTINUATION, State.ACTIVE, getTimeslot()));
                break;
            case STANDARD_ACKNOWLEDGE_RESPONSE_INBOUND_TSCC, STANDARD_ACKNOWLEDGE_RESPONSE_OUTBOUND_TSCC:
                if(csbk instanceof Acknowledge acknowledge)
                {
                    broadcast(getDecodeEvent(csbk, acknowledgeEventType(acknowledge.getReason()),
                            acknowledge.getReason().toString()));
                }
                broadcast(new DecoderStateEvent(this, Event.CONTINUATION, State.CONTROL, getTimeslot()));
                break;
            case STANDARD_AHOY:
                if(csbk instanceof Ahoy ahoy)
                {
                    switch(ahoy.getServiceKind())
                    {
                        case AUTHENTICATE_REGISTER_RADIO_CHECK_SERVICE:
                            if(csbk instanceof AuthenticateRegisterRadioCheck command)
                            {
                                DecodeEventType type = "RADIO CHECK".equals(command.getCommand()) ?
                                    DecodeEventType.RADIO_CHECK : DecodeEventType.COMMAND;
                                broadcast(getDecodeEvent(csbk, type, command.getCommand()));
                            }
                            break;
                        case CANCEL_CALL_SERVICE:
                            broadcast(getDecodeEvent(csbk, DecodeEventType.COMMAND, "CANCEL CALL"));
                            break;
                        case SUPPLEMENTARY_SERVICE:
                            if(csbk instanceof StunReviveKill stunrevivekill)
                            {
                                broadcast(getDecodeEvent(csbk, DecodeEventType.COMMAND,
                                        stunrevivekill.getCommand() + " RADIO"));
                            }
                            break;
                        case FULL_DUPLEX_MS_TO_MS_PACKET_CALL_SERVICE, FULL_DUPLEX_MS_TO_MS_VOICE_CALL_SERVICE,
                            INDIVIDUAL_VOICE_CALL_SERVICE, INDIVIDUAL_PACKET_CALL_SERVICE,
                            INDIVIDUAL_UDT_SHORT_DATA_CALL_SERVICE, TALKGROUP_PACKET_CALL_SERVICE,
                            TALKGROUP_UDT_SHORT_DATA_CALL_SERVICE, TALKGROUP_VOICE_CALL_SERVICE:
                            if(csbk instanceof ServiceRadioCheck src)
                            {
                                broadcast(getDecodeEvent(csbk, DecodeEventType.RADIO_CHECK,
                                        src.getServiceDescription() + " SERVICE FOR " +
                                        (src.isTalkgroupTarget() ? "TALKGROUP" : "RADIO")));
                            }
                            break;
                        default:
                            break;
                    }
                }
                broadcast(new DecoderStateEvent(this, Event.CONTINUATION, State.CONTROL, getTimeslot()));
                break;
            case STANDARD_ALOHA:
                if(csbk instanceof Aloha aloha && aloha.hasRadioIdentifier())
                {
                    broadcast(getDecodeEvent(csbk, DecodeEventType.RESPONSE, "Aloha Acknowledge"));
                    resetState();
                }
                broadcast(new DecoderStateEvent(this, Event.CONTINUATION, State.CONTROL, getTimeslot()));
                break;
            case STANDARD_ANNOUNCEMENT:
                if(csbk instanceof Announcement announcement)
                {
                    switch(announcement.getAnnouncementType())
                    {
                        case MASS_REGISTRATION:
                            broadcast(getDecodeEvent(csbk, DecodeEventType.REGISTER, "MASS REGISTRATION"));
                            break;
                        case VOTE_NOW_ADVICE:
                            if(csbk instanceof VoteNowAdvice votenowadvice)
                            {
                                broadcast(getDecodeEvent(csbk, DecodeEventType.COMMAND,
                                        "VOTE NOW FOR " + votenowadvice.getVotedSystemIdentityCode()));
                            }
                            break;
                        default:
                            break;
                    }
                }
                broadcast(new DecoderStateEvent(this, Event.CONTINUATION, State.CONTROL, getTimeslot()));
                break;
            case STANDARD_CLEAR:
                broadcast(new DecoderStateEvent(this, Event.END, State.CALL, getTimeslot()));
                resetState();
                break;
            case STANDARD_PREAMBLE:
                getIdentifierCollection().update(csbk.getIdentifiers());
                broadcast(new DecoderStateEvent(this, Event.CONTINUATION, State.DATA, getTimeslot()));
                break;
            case STANDARD_PROTECT:
                if(csbk instanceof Protect protect)
                {
                    broadcast(getDecodeEvent(csbk, DecodeEventType.COMMAND,
                            "PROTECT: " + protect.getProtectKind()));
                }
                broadcast(new DecoderStateEvent(this, Event.CONTINUATION, State.CALL, getTimeslot()));
                break;
            case HYTERA_08_ANNOUNCEMENT, HYTERA_68_ANNOUNCEMENT, HYTERA_68_XPT_SITE_STATE:
                break;
            case HYTERA_08_TRAFFIC_CHANNEL_TALKER_STATUS:
                if(csbk instanceof HyteraTrafficChannelTalkerStatus status)
                {
                    if(status.isChannelActive())
                    {
                        getIdentifierCollection().update(status.getIdentifiers());
                        updateCurrentCall(DecodeEventType.CALL_GROUP, "HYTERA TIER 3 CALL", status.getTimestamp());
                    }
                    else
                    {
                        getIdentifierCollection().remove(Role.FROM);
                        getIdentifierCollection().update(status.getDestinationRadio());
                    }
                }
                break;
            case MOTOROLA_CAPPLUS_NEIGHBOR_REPORT:
                if(csbk instanceof CapacityPlusNeighbors capacityplusneighbors)
                {
                    //Update state and rest channel
                    updateRestChannel(capacityplusneighbors.getRestChannel(), operationalMode);
                }
                break;
            case MOTOROLA_CAPPLUS_SITE_STATUS:
                if(csbk instanceof CapacityPlusSiteStatus cpss)
                {

                    //Channel rotation monitor normally uses only CONTROL state, so when we detect that we're a
                    //Capacity plus system, add ACTIVE as an active state to the monitor.  This can be requested repeatedly.
                    var eventBus = getInterModuleEventBus();

                    if(mTrunkingEnabled && eventBus != null)
                    {
                        eventBus.post(CAPACITY_PLUS_ACTIVE_STATE_REQUEST);
                    }

                    broadcast(new DecoderStateEvent(this, Event.CONTINUATION, State.ACTIVE, getTimeslot()));

                    //Update state and rest channel
                    updateRestChannel(cpss.getRestChannel(), operationalMode);

                    //If the sister timeslot hasn't identified the current channel, attempt to identify the channel
                    //from the current call activity map.
                    if(cpss.hasVoiceTalkgroups() && mSisterDecoderState != null && mSisterDecoderState.getCurrentChannel() == null)
                    {
                        DMRChannel sisterChannel = mSisterDecoderState
                                .processActiveTalkgroups(cpss.getActiveIdentifierMap(), cpss.getActiveLsnMap());

                        //If the returned channel is non-null, set our own channel
                        if(getCurrentChannel() == null && sisterChannel != null)
                        {
                            setCurrentChannel(sisterChannel.getSisterTimeslot());
                        }
                    }
                }
                break;
            case MOTOROLA_CONPLUS_NEIGHBOR_REPORT:
                broadcast(new DecoderStateEvent(this, Event.CONTINUATION, State.CONTROL, getTimeslot()));
                break;
            case STANDARD_BROADCAST_TALKGROUP_VOICE_CHANNEL_GRANT, STANDARD_DUPLEX_PRIVATE_DATA_CHANNEL_GRANT,
                STANDARD_DUPLEX_PRIVATE_VOICE_CHANNEL_GRANT, STANDARD_PRIVATE_DATA_CHANNEL_GRANT_SINGLE_ITEM,
                STANDARD_PRIVATE_VOICE_CHANNEL_GRANT, STANDARD_TALKGROUP_DATA_CHANNEL_GRANT_MULTI_ITEM,
                STANDARD_TALKGROUP_DATA_CHANNEL_GRANT_SINGLE_ITEM, STANDARD_TALKGROUP_VOICE_CHANNEL_GRANT:
                if(csbk instanceof ChannelGrant channelGrant)
                {
                    DMRChannel channel = channelGrant.getChannel();

                    if(operationalMode.hasAllocationAuthority())
                    {
                        IdentifierCollection mergedIdentifiers = getMergedIdentifierCollection(csbk.getIdentifiers());
                        processChannelGrant(operationalMode, channel, mergedIdentifiers, csbk.getOpcode(),
                            csbk.getTimestamp(), csbk.isEncrypted());
                    }
                    else
                    {
                        LOGGING_SUPPRESSOR.error("NoTCM" + csbk.getOpcode().name(), 2,
                                NO_TCM_CHANNEL_GRANT_MESSAGE + csbk.getOpcode().name());
                    }
                }
                else
                {
                    //Log when a CSBK that is not the Unknown CSBK is processed, to detect when new opcodes are added
                    //that are not ChannelGrant subclass implementations.
                    if(!(csbk instanceof UnknownCSBKMessage) && csbk.isValid())
                    {
                        mLog.error("Unrecognized DMR channel grant CSBK ignored: {}", csbk.getClass());
                    }
                }
                break;
            case MOTOROLA_CAPMAX_ALOHA:
                if(csbk instanceof CapacityMaxAloha cmAloha && cmAloha.hasRadioIdentifier())
                {
                    broadcast(getDecodeEvent(csbk, DecodeEventType.RESPONSE, "Aloha Acknowledge"));
                    resetState();
                }
                broadcast(new DecoderStateEvent(this, Event.CONTINUATION, State.CONTROL, getTimeslot()));
                break;
            case MOTOROLA_CAPMAX_CHANNEL_UPDATE_OPEN_MODE:
                if(csbk instanceof CapacityMaxOpenModeVoiceChannelUpdate update)
                {
                    if(operationalMode.hasAllocationAuthority())
                    {
                        if(update.hasTimeslot1())
                        {
                            MutableIdentifierCollection mic = new MutableIdentifierCollection(getIdentifierCollection().getIdentifiers());
                            mic.remove(IdentifierClass.USER);
                            mic.update(update.getChannelTS1());
                            mic.update(update.getTalkgroupTS1());
                            processChannelGrant(operationalMode, update.getChannelTS1(), mic, csbk.getOpcode(),
                                    csbk.getTimestamp(), csbk.isEncrypted());
                        }
                        if(update.hasTimeslot2())
                        {
                            MutableIdentifierCollection mic = new MutableIdentifierCollection(getIdentifierCollection().getIdentifiers());
                            mic.remove(IdentifierClass.USER);
                            mic.update(update.getChannelTS2());
                            mic.update(update.getTalkgroupTS2());
                            processChannelGrant(operationalMode, update.getChannelTS2(), mic, csbk.getOpcode(),
                                    csbk.getTimestamp(), csbk.isEncrypted());
                        }
                    }
                    else
                    {
                        LOGGING_SUPPRESSOR.error("NoTCM" + csbk.getOpcode().name(), 2,
                                NO_TCM_CHANNEL_GRANT_MESSAGE + csbk.getOpcode().name());
                    }
                }
                broadcast(new DecoderStateEvent(this, Event.CONTINUATION, State.CONTROL, getTimeslot()));
                break;
            case MOTOROLA_CAPMAX_CHANNEL_UPDATE_ADVANTAGE_MODE:
                if(csbk instanceof CapacityMaxAdvantageModeVoiceChannelUpdate update)
                {
                    if(operationalMode.hasAllocationAuthority())
                    {
                        if(update.hasChannel1Timeslot1())
                        {
                            MutableIdentifierCollection mic = new MutableIdentifierCollection(getIdentifierCollection().getIdentifiers());
                            mic.remove(IdentifierClass.USER);
                            mic.update(update.getChannel1TS1());
                            mic.update(update.getTalkgroupCH1TS1());
                            processChannelGrant(operationalMode, update.getChannel1TS1(), mic, csbk.getOpcode(),
                                    csbk.getTimestamp(), csbk.isEncrypted());
                        }

                        if(update.hasChannel1Timeslot2())
                        {
                            MutableIdentifierCollection mic = new MutableIdentifierCollection(getIdentifierCollection().getIdentifiers());
                            mic.remove(IdentifierClass.USER);
                            mic.update(update.getChannel1TS2());
                            mic.update(update.getTalkgroupCH1TS2());
                            processChannelGrant(operationalMode, update.getChannel1TS2(), mic, csbk.getOpcode(),
                                    csbk.getTimestamp(), csbk.isEncrypted());
                        }

                        if(update.hasChannel2Timeslot1())
                        {
                            MutableIdentifierCollection mic = new MutableIdentifierCollection(getIdentifierCollection().getIdentifiers());
                            mic.remove(IdentifierClass.USER);
                            mic.update(update.getChannel2TS1());
                            mic.update(update.getTalkgroupCH2TS1());
                            processChannelGrant(operationalMode, update.getChannel2TS1(), mic, csbk.getOpcode(),
                                    csbk.getTimestamp(), csbk.isEncrypted());
                        }

                        if(update.hasChannel2Timeslot2())
                        {
                            MutableIdentifierCollection mic = new MutableIdentifierCollection(getIdentifierCollection().getIdentifiers());
                            mic.remove(IdentifierClass.USER);
                            mic.update(update.getChannel2TS2());
                            mic.update(update.getTalkgroupCH2TS2());
                            processChannelGrant(operationalMode, update.getChannel2TS2(), mic, csbk.getOpcode(),
                                    csbk.getTimestamp(), csbk.isEncrypted());
                        }
                    }
                    else
                    {
                        LOGGING_SUPPRESSOR.error("NoTCM" + csbk.getOpcode().name(), 2,
                                NO_TCM_CHANNEL_GRANT_MESSAGE + csbk.getOpcode().name());
                    }
                }
                broadcast(new DecoderStateEvent(this, Event.CONTINUATION, State.CONTROL, getTimeslot()));
                break;
            case MOTOROLA_CONPLUS_DATA_CHANNEL_GRANT:
                if(csbk instanceof ConnectPlusDataChannelGrant cpdcg)
                {
                    DMRChannel channel = cpdcg.getChannel();

                    if(operationalMode.hasAllocationAuthority())
                    {
                        IdentifierCollection mergedIdentifiers = getMergedIdentifierCollection(csbk.getIdentifiers());
                        processChannelGrant(operationalMode, channel, mergedIdentifiers, csbk.getOpcode(),
                            csbk.getTimestamp(), csbk.isEncrypted());
                    }
                    else
                    {
                        LOGGING_SUPPRESSOR.error("NoTCM" + csbk.getOpcode().name(), 2,
                                NO_TCM_CHANNEL_GRANT_MESSAGE + csbk.getOpcode().name());
                    }
                }
                broadcast(new DecoderStateEvent(this, Event.CONTINUATION, State.CONTROL, getTimeslot()));
                break;
            case MOTOROLA_CONPLUS_REGISTRATION_REQUEST:
                DecodeEvent event = DMRDecodeEvent.builder(DecodeEventType.REQUEST, csbk.getTimestamp())
                    .channel(getCurrentChannel())
                    .details("Registration Request")
                    .identifiers(new IdentifierCollection(csbk.getIdentifiers()))
                    .timeslot(getTimeslot())
                    .build();
                broadcast(event);
                broadcast(new DecoderStateEvent(this, Event.CONTINUATION, State.CONTROL, getTimeslot()));
                break;
            case MOTOROLA_CONPLUS_REGISTRATION_RESPONSE:
                DecodeEvent regRespEvent = DMRDecodeEvent.builder(DecodeEventType.RESPONSE, csbk.getTimestamp())
                    .channel(getCurrentChannel())
                    .details("Registration Response")
                    .identifiers(new IdentifierCollection(csbk.getIdentifiers()))
                    .timeslot(getTimeslot())
                    .build();
                broadcast(regRespEvent);
                broadcast(new DecoderStateEvent(this, Event.CONTINUATION, State.CONTROL, getTimeslot()));
                break;
            case MOTOROLA_CONPLUS_VOICE_CHANNEL_USER:
                if(csbk instanceof ConnectPlusVoiceChannelUser cpvcu)
                {
                    DMRChannel channel = cpvcu.getChannel();

                    if(operationalMode.hasAllocationAuthority())
                    {
                        IdentifierCollection mergedIdentifiers = getMergedIdentifierCollection(csbk.getIdentifiers());
                        processChannelGrant(operationalMode, channel, mergedIdentifiers, csbk.getOpcode(),
                            csbk.getTimestamp(), csbk.isEncrypted());
                    }
                    else
                    {
                        LOGGING_SUPPRESSOR.error("NoTCM" + csbk.getOpcode().name(), 2,
                                NO_TCM_CHANNEL_GRANT_MESSAGE + csbk.getOpcode().name());
                    }
                }
                broadcast(new DecoderStateEvent(this, Event.CONTINUATION, State.CONTROL, getTimeslot()));
                break;
            case MOTOROLA_CONPLUS_TALKGROUP_AFFILIATION:
                DecodeEvent affiliateEvent = DMRDecodeEvent.builder(DecodeEventType.AFFILIATE, csbk.getTimestamp())
                    .channel(getCurrentChannel())
                    .details("TALKGROUP AFFILIATION")
                    .identifiers(new IdentifierCollection(csbk.getIdentifiers()))
                    .timeslot(getTimeslot())
                    .build();
                broadcast(affiliateEvent);
                broadcast(new DecoderStateEvent(this, Event.CONTINUATION, State.CONTROL, getTimeslot()));
                break;
            default:
                broadcast(new DecoderStateEvent(this, Event.CONTINUATION, State.ACTIVE, getTimeslot()));
                break;
        }
    }

    /**
     * Dispatches a grant only while the callback's captured control-channel authority is still current.  The identity
     * check is the lock-free linearization point with a concurrent standard-to-traffic conversion.
     */
    private void processChannelGrant(OperationalMode operationalMode, DMRChannel channel,
                                     IdentifierCollection identifiers, Opcode opcode, long timestamp,
                                     boolean encrypted)
    {
        Runnable interleave = mBeforeChannelGrantAuthorityCheckForTest;

        if(interleave != null)
        {
            mBeforeChannelGrantAuthorityCheckForTest = null;
            interleave.run();
        }

        AllocationAuthority authority = acquireCurrentAllocationAuthority(operationalMode);

        if(authority != null)
        {
            try
            {
                Runnable acquiredInterleave = mAfterChannelGrantAuthorityAcquiredForTest;

                if(acquiredInterleave != null)
                {
                    mAfterChannelGrantAuthorityAcquiredForTest = null;
                    acquiredInterleave.run();
                }

                operationalMode.allocationManager().processChannelGrant(channel, identifiers, opcode, timestamp,
                    encrypted);
            }
            finally
            {
                authority.release();
            }
        }
    }

    private AllocationAuthority acquireCurrentAllocationAuthority(OperationalMode operationalMode)
    {
        if(operationalMode == null || !operationalMode.hasAllocationAuthority())
        {
            return null;
        }

        AllocationAuthority authority = operationalMode.allocationAuthority();

        if(!authority.tryAcquire())
        {
            return null;
        }

        if(hasCurrentAllocationAuthority(operationalMode))
        {
            return authority;
        }

        authority.release();
        return null;
    }

    private boolean hasCurrentAllocationAuthority(OperationalMode operationalMode)
    {
        return operationalMode != null && operationalMode.hasAllocationAuthority() &&
            mOperationalMode.get().hasSameAllocationAuthority(operationalMode);
    }

    /**
     * Deterministic package-level race seam.  Production has no interleave action.
     */
    void setBeforeChannelGrantAuthorityCheckForTest(Runnable interleave)
    {
        mBeforeChannelGrantAuthorityCheckForTest = interleave;
    }

    void setAfterChannelGrantAuthorityAcquiredForTest(Runnable interleave)
    {
        mAfterChannelGrantAuthorityAcquiredForTest = interleave;
    }

    boolean hasAllocationAuthorityForTest()
    {
        return mOperationalMode.get().hasAllocationAuthority();
    }

    private DecodeEvent getDecodeEvent(CSBKMessage csbk, DecodeEventType decodeEventType, String details) {
        return DMRDecodeEvent.builder(decodeEventType, csbk.getTimestamp())
                .channel(getCurrentChannel())
                .identifiers(new IdentifierCollection(csbk.getIdentifiers()))
                .timeslot(getTimeslot())
                .details(details)
                .build();
    }

    private static DecodeEventType acknowledgeEventType(Reason reason)
    {
        return switch(reason)
        {
            case TS_REGISTRATION_ACCEPTED, TS_SUBSCRIPTION_SERVICE_REGISTRATION_ACCEPTED ->
                DecodeEventType.REGISTER;
            case TS_REGISTRATION_REFUSED, TS_REGISTRATION_DENIED -> DecodeEventType.DENIAL;
            default -> DecodeEventType.RESPONSE;
        };
    }

    /**
     * Creates a copy of the current identifier collection, removes any USER class identifiers and loads the identifiers
     * argument values into the collection.
     * @param identifiers to load into the collection.
     * @return copy identifier collection.
     */
    private IdentifierCollection getMergedIdentifierCollection(List<Identifier> identifiers)
    {
        MutableIdentifierCollection mic = new MutableIdentifierCollection(getIdentifierCollection().getIdentifiers());
        mic.remove(IdentifierClass.USER);
        mic.update(identifiers);
        return mic;
    }

    /**
     * Processes Link Control Messages
     * @param isTerminator set to true when the link control is carried by a terminator
     */
    private void processLinkControl(LCMessage message, boolean isTerminator)
    {
        processLinkControl(message, isTerminator, mOperationalMode.get());
    }

    private void processLinkControl(LCMessage message, boolean isTerminator, OperationalMode operationalMode)
    {
        switch(message.getOpcode())
        {
            case FULL_ENCRYPTION_PARAMETERS:
                if(message instanceof io.github.dsheirer.module.decode.dmr.message.data.lc.full.EncryptionParameters ep &&
                    mCurrentCallEvent != null)
                {
                    mCurrentCallEncrypted = true;
                    mCurrentCallEvent.setDecodeEventType(encryptedCallType(
                        mCurrentCallEvent.getEventType(), false));
                    mCurrentCallEvent.setDetails(ep.getDetails());
                    mCurrentCallEvent.end(message.getTimestamp());
                    broadcast(mCurrentCallEvent);
                }
                break;
            case SHORT_CAPACITY_PLUS_REST_CHANNEL_NOTIFICATION:
                if(message instanceof CapacityPlusRestChannel capacityplusrestchannel)
                {
                    updateRestChannel(capacityplusrestchannel.getRestChannel(), operationalMode);
                }
                break;
            case FULL_CAPACITY_PLUS_ENCRYPTED_VOICE_CHANNEL_USER:
                if(message instanceof CapacityPlusEncryptedVoiceChannelUser cpgvcu)
                {
                    if(isTerminator)
                    {
                        getIdentifierCollection().update(cpgvcu.getTalkgroup());
                    }
                    else
                    {
                        getIdentifierCollection().update(message.getIdentifiers());
                        ServiceOptions serviceOptions = cpgvcu.getServiceOptions();
                        updateCurrentCall(serviceOptions.isEncrypted() ? DecodeEventType.CALL_GROUP_ENCRYPTED :
                                DecodeEventType.CALL_GROUP, serviceOptions.toString(), message.getTimestamp());
                    }
                }
                break;
            case FULL_MOTOROLA_GROUP_VOICE_CHANNEL_USER:
                if(message instanceof MotorolaGroupVoiceChannelUser cpgvcu)
                {
                    if(isTerminator)
                    {
                        getIdentifierCollection().update(cpgvcu.getTalkgroup());
                    }
                    else
                    {
                        getIdentifierCollection().update(message.getIdentifiers());
                        ServiceOptions serviceOptions = cpgvcu.getServiceOptions();
                        updateCurrentCall(serviceOptions.isEncrypted() ? DecodeEventType.CALL_GROUP_ENCRYPTED :
                            DecodeEventType.CALL_GROUP, serviceOptions.toString(), message.getTimestamp());
                    }
                }
                break;
            case FULL_CAPACITY_MAX_GROUP_VOICE_CHANNEL_USER:
                if(message instanceof CapacityMaxVoiceChannelUser cmvcu)
                {
                    if(isTerminator)
                    {
                        getIdentifierCollection().update(cmvcu.getTalkgroup());
                    }
                    else
                    {
                        getIdentifierCollection().update(message.getIdentifiers());
                        ServiceOptions serviceOptions = cmvcu.getServiceOptions();
                        updateCurrentCall(serviceOptions.isEncrypted() ? DecodeEventType.CALL_GROUP_ENCRYPTED :
                                DecodeEventType.CALL_GROUP, serviceOptions.toString(), message.getTimestamp());
                    }
                }
                break;
            case FULL_CAPACITY_MAX_TALKER_ALIAS:
                if(message instanceof CapacityMaxTalkerAlias alias)
                {
                    //If we have a talker alias identifier, append this value.
                    Identifier existing = getIdentifierCollection()
                        .getIdentifier(IdentifierClass.USER, Form.TALKER_ALIAS, Role.FROM);
                    DmrTalkerAliasIdentifier baseAlias = alias.getTalkerAliasIdentifier();
                    boolean newBaseAlias = !(existing instanceof DmrTalkerAliasIdentifier talkerAlias) ||
                        !talkerAlias.equals(baseAlias);

                    if(existing instanceof DmrTalkerAliasIdentifier talkerAlias &&
                            !talkerAlias.equals(baseAlias) &&
                            !talkerAlias.getValue().contains(baseAlias.getValue()))
                    {
                        //Concatenate the existing talker alias fragment with the base alias value.
                        DmrTalkerAliasIdentifier updated = DmrTalkerAliasIdentifier
                                .create(baseAlias.getValue() + talkerAlias.getValue());
                        getIdentifierCollection().update(updated);

                        processTalkerAlias(updated, message.getTimestamp());
                    }
                    else
                    {
                        getIdentifierCollection().update(baseAlias);

                        //The base message carries up to six characters.  Short aliases are complete without a
                        //continuation, so publish the first observation now and suppress repeated base messages.
                        if(alias.getLength() <= 6 && newBaseAlias)
                        {
                            processTalkerAlias(baseAlias, message.getTimestamp());
                        }
                    }

                    if(mCurrentCallEvent != null)
                    {
                        broadcast(mCurrentCallEvent);
                    }
                }
                break;
            case FULL_CAPACITY_MAX_TALKER_ALIAS_CONTINUATION:
                if(message instanceof CapacityMaxTalkerAliasContinuation alias)
                {
                    //If we have a talker alias identifier, append this value.
                    Identifier existing = getIdentifierCollection().getIdentifier(IdentifierClass.USER, Form.TALKER_ALIAS, Role.FROM);

                    if(existing instanceof DmrTalkerAliasIdentifier talkerAlias &&
                            !talkerAlias.equals(alias.getTalkerAliasIdentifier()) &&
                            !talkerAlias.getValue().contains(alias.getTalkerAliasIdentifier().getValue()))
                    {
                        //Concatenate the existing talker alias value with the updated continuation fragment.
                        DmrTalkerAliasIdentifier updated = DmrTalkerAliasIdentifier.create(talkerAlias.getValue() +
                                alias.getTalkerAliasIdentifier().getValue());
                        getIdentifierCollection().update(updated);

                        processTalkerAlias(updated, message.getTimestamp());
                    }
                    else
                    {
                        //Temporarily place the continuation fragment alias into the identifier collection.
                        getIdentifierCollection().update(alias.getTalkerAliasIdentifier());
                    }

                    if(mCurrentCallEvent != null)
                    {
                        broadcast(mCurrentCallEvent);
                    }
                }
                break;
            case FULL_CAPACITY_PLUS_WIDE_AREA_VOICE_CHANNEL_USER:
                if(message instanceof CapacityPlusWideAreaVoiceChannelUser cpwavcu)
                {
                    updateRestChannel(cpwavcu.getRestChannel(), operationalMode);

                    if(isTerminator)
                    {
                        getIdentifierCollection().update(cpwavcu.getTalkgroup());
                    }
                    else
                    {
                        getIdentifierCollection().update(message.getIdentifiers());
                        ServiceOptions serviceOptions = cpwavcu.getServiceOptions();
                        updateCurrentCall(serviceOptions.isEncrypted() ? DecodeEventType.CALL_GROUP_ENCRYPTED :
                                DecodeEventType.CALL_GROUP, serviceOptions.toString(), message.getTimestamp());
                    }
                }
                break;
            case FULL_HYTERA_GROUP_VOICE_CHANNEL_USER:
                if(message instanceof HyteraGroupVoiceChannelUser hgvcu)
                {

                    if(isTerminator)
                    {
                        getIdentifierCollection().update(hgvcu.getTalkgroup());
                    }
                    else
                    {
                        getIdentifierCollection().update(message.getIdentifiers());
                        ServiceOptions serviceOptions = hgvcu.getServiceOptions();
                        updateCurrentCall(serviceOptions.isEncrypted() ? DecodeEventType.CALL_GROUP_ENCRYPTED :
                            DecodeEventType.CALL_GROUP, serviceOptions.toString(), message.getTimestamp());

                    }
                }
                break;
            case FULL_HYTERA_TERMINATOR, FULL_STANDARD_TERMINATOR_DATA:
                getIdentifierCollection().update(message.getIdentifiers());
                break;
            case FULL_HYTERA_UNIT_TO_UNIT_VOICE_CHANNEL_USER:
                if(message instanceof HyteraUnitToUnitVoiceChannelUser huuvcu)
                {

                    if(isTerminator)
                    {
                        getIdentifierCollection().update(huuvcu.getTargetRadio());
                    }
                    else
                    {
                        getIdentifierCollection().update(message.getIdentifiers());
                        ServiceOptions serviceOptions = huuvcu.getServiceOptions();
                        updateCurrentCall(serviceOptions.isEncrypted() ? DecodeEventType.CALL_UNIT_TO_UNIT_ENCRYPTED :
                            DecodeEventType.CALL_UNIT_TO_UNIT, serviceOptions.toString(), message.getTimestamp());
                    }
                }
                break;
            case FULL_STANDARD_GROUP_VOICE_CHANNEL_USER:
                if(message instanceof GroupVoiceChannelUser gvcu)
                {
                    if(isTerminator)
                    {
                        getIdentifierCollection().update(gvcu.getTalkgroup());
                    }
                    else
                    {
                        getIdentifierCollection().update(message.getIdentifiers());
                        ServiceOptions serviceOptions = gvcu.getServiceOptions();
                        updateCurrentCall(serviceOptions.isEncrypted() ? DecodeEventType.CALL_GROUP_ENCRYPTED :
                            DecodeEventType.CALL_GROUP, serviceOptions.toString(), message.getTimestamp());
                    }
                }
                break;
            case FULL_STANDARD_UNIT_TO_UNIT_VOICE_CHANNEL_USER:
                if(message instanceof UnitToUnitVoiceChannelUser uuvcu)
                {

                    if(isTerminator)
                    {
                        getIdentifierCollection().update(uuvcu.getTargetRadio());
                    }
                    else
                    {
                        getIdentifierCollection().update(message.getIdentifiers());
                        ServiceOptions serviceOptions = uuvcu.getServiceOptions();
                        updateCurrentCall(serviceOptions.isEncrypted() ? DecodeEventType.CALL_UNIT_TO_UNIT_ENCRYPTED :
                            DecodeEventType.CALL_UNIT_TO_UNIT, serviceOptions.toString(), message.getTimestamp());
                    }
                }
                break;
            case FULL_STANDARD_GPS_INFO, FULL_HYTERA_GPS_INFO:
                if(message instanceof GPSInformation gps)
                {
                    PlottableDecodeEvent plottableGPS = PlottableDecodeEvent.plottableBuilder(DecodeEventType.GPS, message.getTimestamp())
                            .channel(getCurrentChannel())
                            .details("LOCATION:" + gps.getGPSLocation())
                            .identifiers(new IdentifierCollection(getIdentifierCollection().getIdentifiers()))
                            .protocol(Protocol.DMR)
                            .location(gps.getPosition())
                            .build();

                    broadcast(plottableGPS);
                }
                break;
            case FULL_STANDARD_TALKER_ALIAS_COMPLETE:
                if(message instanceof TalkerAliasComplete tac && tac.hasTalkerAliasIdentifier())
                {
                    getIdentifierCollection().update(tac.getTalkerAliasIdentifier());
                    processTalkerAlias(tac.getTalkerAliasIdentifier(), message.getTimestamp());
                }
                break;
            default:
                break;
        }
    }

    private void processTalkerAlias(DmrTalkerAliasIdentifier alias, long timestamp)
    {
        Identifier from = getIdentifierCollection().getFromIdentifier();

        if(mTrafficChannelEventManager != null && from instanceof RadioIdentifier radio)
        {
            mTrafficChannelEventManager.processTalkerAlias(alias, radio, getIdentifierCollection().copyOf(), timestamp);
        }
    }

    /**
     * Updates the current call with encryption information.
     * @param embeddedEncryptionParameters decoded from the Voice Frame F
     * @param isGroup true for group or false for individual call.
     */
    private void updateEncryptedCall(EmbeddedEncryptionParameters embeddedEncryptionParameters, boolean isGroup, long timestamp)
    {
        mCurrentCallEncrypted = true;

        if(mCurrentCallEvent != null)
        {
            mCurrentCallEvent.setDecodeEventType(encryptedCallType(
                mCurrentCallEvent.getEventType(), isGroup));
            String details = mCurrentCallEvent.getDetails();
            String encryptionDetails = embeddedEncryptionParameters.getEventDetails();

            if(details == null)
            {
                details = encryptionDetails;
            }
            else if(!details.contains(encryptionDetails) && !details.contains("ENCRYPTION"))
            {
                details += " " + encryptionDetails;
            }

            mCurrentCallEvent.setDetails(details);
            mCurrentCallEvent.setIdentifierCollection(getIdentifierCollection().copyOf());
            mCurrentCallEvent.end(timestamp);
            broadcast(mCurrentCallEvent);
        }
        else
        {
            mCurrentCallEvent = DMRDecodeEvent.builder(isGroup ? DecodeEventType.CALL_GROUP_ENCRYPTED :
                            DecodeEventType.CALL_ENCRYPTED, timestamp)
                    .channel(getCurrentChannel())
                    .details(embeddedEncryptionParameters.getEventDetails())
                    .identifiers(getIdentifierCollection().copyOf())
                    .timeslot(getTimeslot())
                    .build();
            broadcast(mCurrentCallEvent);
        }
    }

    /**
     * Updates or creates a current call event.
     *
     * @param type of call that will be used as an event description
     * @param details of the call (optional)
     * @param timestamp of the message indicating a call or continuation
     */
    private void updateCurrentCall(DecodeEventType type, String details, long timestamp)
    {
        if(mCurrentCallEvent != null &&
            callIdentityChanged(mCurrentCallEvent.getIdentifierCollection(), getIdentifierCollection()))
        {
            closeCurrentCallEvent(timestamp, false);
        }

        Event event = mCurrentCallEvent == null ? Event.START : Event.CONTINUATION;
        mCurrentCallEncrypted |= DecodeEventType.VOICE_CALLS_ENCRYPTED.contains(type);

        //Create a repeater channel descriptor if we don't have one
        if(mCurrentChannel == null && getCurrentFrequency() > 0)
        {
            mCurrentChannel = new DMRAbsoluteChannel(getTimeslot(), getTimeslot(), getCurrentFrequency(), 0);
        }

        if(mCurrentCallEvent == null)
        {
            mCurrentCallEvent = DMRDecodeEvent.builder(type, timestamp)
                .channel(getCurrentChannel())
                .details(details)
                .identifiers(getIdentifierCollection().copyOf())
                .timeslot(getTimeslot())
                .build();
        }
        else
        {
            if(DecodeEventType.VOICE_CALLS_ENCRYPTED.contains(type))
            {
                mCurrentCallEvent.setDecodeEventType(type);
            }

            if(mCurrentCallEvent.getDetails() == null)
            {
                mCurrentCallEvent.setDetails(details);
            }

            mCurrentCallEvent.setIdentifierCollection(getIdentifierCollection().copyOf());
            mCurrentCallEvent.end(timestamp);
        }

        broadcast(mCurrentCallEvent);

        if(type == DecodeEventType.CALL_GROUP_ENCRYPTED || type == DecodeEventType.CALL_UNIT_TO_UNIT_ENCRYPTED)
        {
            broadcast(new DecoderStateEvent(this, event, State.ENCRYPTED, getTimeslot()));
        }
        else
        {
            broadcast(new DecoderStateEvent(this, event, State.CALL, getTimeslot()));
        }
    }

    private static DecodeEventType encryptedCallType(DecodeEventType current, boolean groupFallback)
    {
        if(current == DecodeEventType.CALL_GROUP || current == DecodeEventType.CALL_GROUP_ENCRYPTED)
        {
            return DecodeEventType.CALL_GROUP_ENCRYPTED;
        }

        if(current == DecodeEventType.CALL_UNIT_TO_UNIT ||
            current == DecodeEventType.CALL_UNIT_TO_UNIT_ENCRYPTED)
        {
            return DecodeEventType.CALL_UNIT_TO_UNIT_ENCRYPTED;
        }

        return groupFallback ? DecodeEventType.CALL_GROUP_ENCRYPTED : DecodeEventType.CALL_ENCRYPTED;
    }

    /**
     * Ends/closes the current call event.
     *
     * @param timestamp of the message that indicates the event has ended.
     */
    private void closeCurrentCallEvent(long timestamp)
    {
        closeCurrentCallEvent(timestamp, true);
    }

    /**
     * Ends the current call, optionally refreshing its identifiers from the latest link-control state. Identity-change
     * rollover deliberately keeps the old snapshot while normal terminators can contribute late target identifiers.
     */
    private void closeCurrentCallEvent(long timestamp, boolean refreshIdentifiers)
    {
        if(mCurrentCallEvent != null)
        {
            if(refreshIdentifiers)
            {
                mCurrentCallEvent.setIdentifierCollection(completionIdentifiers(
                    mCurrentCallEvent.getIdentifierCollection(), getIdentifierCollection()));
            }

            mCurrentCallEvent.end(timestamp);
            broadcast(mCurrentCallEvent);
            publishConventionalCall(mCurrentCallEvent, timestamp);
            mCurrentCallEvent = null;
        }

        mCurrentCallEncrypted = false;
        getIdentifierCollection().remove(IdentifierClass.USER, Form.TALKER_ALIAS, Role.FROM);
        getIdentifierCollection().remove(IdentifierClass.USER, Form.TONE, Role.FROM);
    }

    /**
     * Publishes exactly one immutable completed-call snapshot for explicitly conventional standard channels.
     */
    private void publishConventionalCall(DecodeEvent call, long timestamp)
    {
        OperationalMode operationalMode = mOperationalMode.get();
        Channel channel = operationalMode.channel();

        if(call == null || channel == null || !channel.isStandardChannel() || mTrunkingEnabled)
        {
            return;
        }

        IdentifierCollection identifiers = call.getIdentifierCollection();
        Identifier source = identifiers != null ? identifiers.getFromIdentifier() : null;
        Identifier target = identifiers != null ? identifiers.getToIdentifier() : null;
        Integer sourceRadio = source instanceof RadioIdentifier radio ? positive(radio.getValue()) : null;
        Integer talkgroup = target instanceof TalkgroupIdentifier group ? positive(group.getValue()) : null;
        Integer targetRadio = target instanceof RadioIdentifier radio ? positive(radio.getValue()) : null;
        DMRConventionalCallEvent.TargetKind targetKind = talkgroup != null ?
            DMRConventionalCallEvent.TargetKind.GROUP : targetRadio != null ?
            DMRConventionalCallEvent.TargetKind.PRIVATE : targetKind(call.getEventType());
        long frequency = call.getChannelDescriptor() != null ?
            call.getChannelDescriptor().getDownlinkFrequency() : getCurrentFrequency();

        if(frequency <= 0)
        {
            frequency = getCurrentFrequency();
        }

        long endTimestamp = Math.max(timestamp, call.getTimeEnd());

        if(endTimestamp <= 0)
        {
            endTimestamp = System.currentTimeMillis();
        }

        long startTimestamp = call.getTimeStart() > 0 ? call.getTimeStart() : endTimestamp;
        boolean encrypted = mCurrentCallEncrypted ||
            DecodeEventType.VOICE_CALLS_ENCRYPTED.contains(call.getEventType());
        MyEventBus.getGlobalEventBus().post(new DMRConventionalCallEvent(startTimestamp, endTimestamp,
            channel.getConfigurationId(), channel.getRadresGuid(), channel.getName(), channel.getAliasListName(),
            frequency, getTimeslot(), targetKind, talkgroup, sourceRadio, targetRadio, encrypted));
    }

    private static DMRConventionalCallEvent.TargetKind targetKind(DecodeEventType eventType)
    {
        if(eventType == DecodeEventType.CALL_GROUP || eventType == DecodeEventType.CALL_GROUP_ENCRYPTED)
        {
            return DMRConventionalCallEvent.TargetKind.GROUP;
        }

        if(eventType == DecodeEventType.CALL_UNIT_TO_UNIT ||
            eventType == DecodeEventType.CALL_UNIT_TO_UNIT_ENCRYPTED)
        {
            return DMRConventionalCallEvent.TargetKind.PRIVATE;
        }

        return DMRConventionalCallEvent.TargetKind.UNKNOWN;
    }

    private static boolean callIdentityChanged(IdentifierCollection previous, IdentifierCollection current)
    {
        if(previous == null || current == null)
        {
            return false;
        }

        return identityChanged(previous.getFromIdentifier(), current.getFromIdentifier()) ||
            identityChanged(previous.getToIdentifier(), current.getToIdentifier());
    }

    private static IdentifierCollection completionIdentifiers(IdentifierCollection call,
                                                              IdentifierCollection latest)
    {
        if(call == null)
        {
            return latest != null ? new IdentifierCollection(latest.getIdentifiers()) : new IdentifierCollection();
        }

        MutableIdentifierCollection merged = new MutableIdentifierCollection(call.getIdentifiers());

        if(latest != null)
        {
            if(call.getFromIdentifier() == null && latest.getFromIdentifier() != null)
            {
                merged.update(latest.getFromIdentifier());
            }

            if(call.getToIdentifier() == null && latest.getToIdentifier() != null)
            {
                merged.update(latest.getToIdentifier());
            }
        }

        return merged.copyOf();
    }

    private static boolean identityChanged(Identifier previous, Identifier current)
    {
        return previous != null && current != null && !previous.equals(current);
    }

    private static Integer positive(Integer value)
    {
        return value != null && value > 0 && value <= 0xFFFFFF ? value : null;
    }

    @Override
    public void receiveDecoderStateEvent(DecoderStateEvent event)
    {
        switch(event.getEvent())
        {
            case REQUEST_RESET:
                resetState();
                break;
            case NOTIFICATION_SOURCE_FREQUENCY:
                OperationalMode operationalMode = mOperationalMode.get();
                Channel channel = operationalMode.channel();

                if(!mTrunkingEnabled && channel.isStandardChannel() &&
                    getCurrentFrequency() != event.getFrequency())
                {
                    closeCurrentCallEvent(System.currentTimeMillis());
                    setCurrentChannel(null);
                }

                setCurrentFrequency(event.getFrequency());

                //Only update the traffic channel manager if we're not a traffic channel.
                AllocationAuthority authority = acquireCurrentAllocationAuthority(operationalMode);

                if(authority != null)
                {
                    try
                    {
                        operationalMode.allocationManager().setCurrentControlFrequency(getCurrentFrequency(), channel);
                    }
                    finally
                    {
                        authority.release();
                    }
                }
                break;
            default:
                break;
        }
    }

    @Override
    public void start()
    {
        super.start();

        //Change the default (45-second) traffic channel timeout to 1 second
        if(mOperationalMode.get().channel().isTrafficChannel())
        {
            broadcast(new ChangeChannelTimeoutEvent(this, ChannelType.TRAFFIC, 1000, getTimeslot()));
        }
    }

    @Override
    public void init()
    {
        // No additional initialization is required for DMR decoder state.
    }
}
