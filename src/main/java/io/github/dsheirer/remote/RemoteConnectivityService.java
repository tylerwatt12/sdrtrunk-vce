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
package io.github.dsheirer.remote;

import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.channel.ChannelAdministrationService;
import io.github.dsheirer.channel.ChannelDefinition;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelEvent;
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.controller.channel.event.ChannelStartProcessingRequest;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.p25.phase1.P25P1NACPreloadDataContent;
import io.github.dsheirer.module.decode.p25.phase2.DecodeConfigP25Phase2;
import io.github.dsheirer.module.decode.p25.phase2.enumeration.ScrambleParameters;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.remote.RemoteLinkSettingsStore.Outbound;
import io.github.dsheirer.remote.RemoteLinkSettingsStore.Settings;
import io.github.dsheirer.remote.RemoteLinkSettingsStore.TrustedSender;
import io.github.dsheirer.remote.RemoteWireProtocol.Frame;
import io.github.dsheirer.remote.RemoteWireProtocol.Type;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.source.Source;
import io.github.dsheirer.source.SourceException;
import io.github.dsheirer.source.config.SourceConfigRemote;
import io.github.dsheirer.source.config.SourceConfigTuner;
import io.github.dsheirer.source.config.SourceConfigTunerMultipleFrequency;
import io.github.dsheirer.source.config.SourceConfiguration;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;
import javafx.collections.ListChangeListener;

/**
 * Receiver-owned remote link control plane.  Runtime identities and discovery are kept separate from host-owned
 * channel configuration.  The only producer-side operation is a bounded bit-buffer copy and queue offer.
 */
public final class RemoteConnectivityService implements RemoteLinkAdministrationService, P25RemoteBitstreamService,
    AutoCloseable
{
    private static final int MAXIMUM_SENDERS = 32;
    private static final int MAXIMUM_ACTIVE_OPENS_PER_SENDER = 96;
    private static final int MAXIMUM_TRAFFIC_OPENS_PER_FEED = 16;
    private static final int MAXIMUM_TRAFFIC_OPENS_GLOBALLY = 128;
    private static final int MAXIMUM_REMOTE_FEEDS_GLOBALLY = 128;
    private static final int OUTBOUND_PACKET_CAPACITY = 2_048;
    private static final long MAXIMUM_OUTBOUND_QUEUE_AGE_NANOS = TimeUnit.SECONDS.toNanos(2);
    private static final SecureRandom RANDOM = new SecureRandom();
    private final ConfigurationManager mConfiguration;
    private final ChannelAdministrationService mChannels;
    private final ChannelProcessingManager mProcessing;
    private final RemoteLinkSettingsStore mStore;
    private final ArrayBlockingQueue<OutboundEvent> mOutboundPackets =
        new ArrayBlockingQueue<>(OUTBOUND_PACKET_CAPACITY);
    private final Map<String,PeerState> mPeers = new ConcurrentHashMap<>();
    private final Map<Channel,ActiveChain> mActiveChains = new ConcurrentHashMap<>();
    private final Map<Channel,ExportStream> mExports = new ConcurrentHashMap<>();
    private final Map<FeedKey,RemoteP25BitstreamSource> mWaitingSources = new ConcurrentHashMap<>();
    private final Map<StreamKey,RemoteP25BitstreamSource> mOpeningSources = new ConcurrentHashMap<>();
    private final Map<StreamKey,BoundStream> mOpenSources = new ConcurrentHashMap<>();
    private final Object mPeerAdmissionLock = new Object();
    private final Object mOutboundLifecycleLock = new Object();
    private final Set<String> mCatalogSetupDirty = ConcurrentHashMap.newKeySet();
    private final Set<String> mCatalogSetupRunning = ConcurrentHashMap.newKeySet();
    private final Object mChannelIndexLock = new Object();
    private final Listener<ChannelEvent> mSavedChannelListener = this::indexSavedChannel;
    private final ListChangeListener<AliasListDefinition> mAliasListListener = change ->
    {
        for(String senderId: mPeers.keySet()) scheduleCatalogSetup(senderId);
    };
    private volatile Map<String,SavedChannel> mSavedChannels = Map.of();
    private final AtomicBoolean mClosed = new AtomicBoolean();
    private final AtomicBoolean mCatalogDirty = new AtomicBoolean();
    private volatile Settings mSettings;
    private volatile RemoteLinkTransport.Server mServer;
    private volatile RemoteLinkTransport.Connection mOutbound;
    private volatile ListenerState mListenerState = ListenerState.STOPPED;
    private volatile SenderConnectionState mOutboundState = SenderConnectionState.DISABLED;
    private volatile String mListenerStatus;
    private volatile String mOutboundStatus;
    private volatile long mLastOutboundConnectionMs;
    private volatile OriginSnapshot mOriginSnapshot = OriginSnapshot.EMPTY;
    private final Thread mPublisher;
    private final Thread mConnector;

    public RemoteConnectivityService(ConfigurationManager configuration, UserPreferences preferences)
        throws IOException
    {
        this(configuration, preferences, configuration.getChannelAdministrationService());
    }

    /** Headless test seam; production uses the configuration manager's normal administration boundary. */
    RemoteConnectivityService(ConfigurationManager configuration, UserPreferences preferences,
                              ChannelAdministrationService channels) throws IOException
    {
        mConfiguration = Objects.requireNonNull(configuration);
        mChannels = Objects.requireNonNull(channels);
        mProcessing = configuration.getChannelProcessingManager();
        mStore = new RemoteLinkSettingsStore(preferences.getDirectoryPreference().getDirectoryApplicationRoot());
        mSettings = mStore.load();
        mConfiguration.getChannelModel().addListener(mSavedChannelListener);
        mConfiguration.getAliasModel().aliasListDefinitions().addListener(mAliasListListener);
        mPublisher = Thread.ofPlatform().daemon().name("remote-p25-publisher").unstarted(this::publishLoop);
        mConnector = Thread.ofPlatform().daemon().name("remote-p25-connector").unstarted(this::connectLoop);
    }

    /** Start after saved channels are loaded, before auto-start processing begins. */
    public void start()
    {
        if(mClosed.get()) return;
        refreshListener();
        mPublisher.start();
        mConnector.start();
        publishOriginSnapshot();
    }

    @Override
    public Source acquireSource(Channel channel, String threadName) throws SourceException
    {
        return acquireSource(channel, new ChannelStartProcessingRequest(channel), threadName);
    }

    @Override
    public Source acquireSource(Channel channel, ChannelStartProcessingRequest request, String threadName)
        throws SourceException
    {
        if(!(channel.getSourceConfiguration() instanceof SourceConfigRemote remote))
        {
            throw new SourceException("Remote source configuration is required");
        }
        P25RemoteTrafficOpen traffic = request != null ? request.getRemoteTrafficOpen() : null;
        long frequency = traffic != null ? traffic.frequency() : remote.getFrequency();
        P25RemotePhase phase = traffic != null ? traffic.phase() :
            channel.getDecodeConfiguration().getDecoderType() == DecoderType.P25_PHASE2 ?
                P25RemotePhase.PHASE_2 : P25RemotePhase.PHASE_1;
        RemoteP25BitstreamSource source = new RemoteP25BitstreamSource(frequency, phase, threadName);
        if(traffic != null)
        {
            mOpeningSources.put(new StreamKey(remote.getSenderId(), traffic.streamId(), traffic.generation()), source);
        }
        else
        {
            mWaitingSources.put(new FeedKey(remote.getSenderId(), remote.getFeedId()), source);
        }
        return source;
    }

    @Override
    public void channelStarted(Channel channel, ChannelStartProcessingRequest request, ProcessingChain chain)
    {
        if(channel == null || chain == null) return;
        ActiveChain active = new ActiveChain(channel, request, chain);
        ActiveChain replaced = mActiveChains.put(channel, active);
        synchronized(mExports)
        {
            ExportStream previousExport = mExports.get(channel);
            if(previousExport != null && previousExport.chain != chain &&
                mExports.remove(channel, previousExport)) previousExport.close();
        }
        mCatalogDirty.set(true);
        if(channel.getSourceConfiguration() instanceof SourceConfigRemote remote)
        {
            if(channel.isStandardChannel() && replaced != null && replaced.chain() != chain)
            {
                for(Map.Entry<StreamKey,BoundStream> entry: Map.copyOf(mOpenSources).entrySet())
                {
                    BoundStream bound = entry.getValue();
                    if(bound.open.control() && bound.source == replaced.chain().getSource() &&
                        remote.getSenderId().equals(entry.getKey().senderId()) &&
                        remote.getFeedId().equals(bound.open.feedId()))
                    {
                        mOpenSources.remove(entry.getKey(), bound);
                    }
                }
            }
            if(channel.isStandardChannel() && channel.isAutoStart())
            {
                PeerState peer = mPeers.get(remote.getSenderId());
                if(peer != null) rebindActiveOpens(new FeedKey(remote.getSenderId(), remote.getFeedId()), peer);
            }
            return;
        }
        startExportIfEligible(active);
    }

    @Override
    public void channelStopped(Channel channel, ProcessingChain chain)
    {
        if(channel == null || chain == null) return;
        ActiveChain active = mActiveChains.get(channel);
        // A previous chain can finish stopping after its replacement has already started.
        if(active == null || active.chain() != chain || !mActiveChains.remove(channel, active)) return;
        mCatalogDirty.set(true);
        synchronized(mExports)
        {
            ExportStream export = mExports.get(channel);
            if(export != null && export.chain == chain && mExports.remove(channel, export)) export.close();
        }
        if(channel.getSourceConfiguration() instanceof SourceConfigRemote remote && channel.isStandardChannel())
        {
            if(chain.getSource() instanceof RemoteP25BitstreamSource source)
            {
                mWaitingSources.remove(new FeedKey(remote.getSenderId(), remote.getFeedId()), source);
            }
            for(Map.Entry<StreamKey,BoundStream> entry: Map.copyOf(mOpenSources).entrySet())
            {
                BoundStream bound = entry.getValue();
                if(bound.open.control() && remote.getSenderId().equals(entry.getKey().senderId()) &&
                    remote.getFeedId().equals(bound.open.feedId()) && bound.source == chain.getSource())
                {
                    mOpenSources.remove(entry.getKey(), bound);
                }
            }
            publishOriginSnapshot();
        }
    }

    private void refreshExports()
    {
        synchronized(mExports)
        {
            Settings settings = mSettings;
            RemoteLinkTransport.Connection connection = mOutbound;
            Set<String> selected = Set.copyOf(settings.outbound().exportedChannelIds());
            for(Map.Entry<Channel,ExportStream> entry: Map.copyOf(mExports).entrySet())
            {
                ActiveChain active = mActiveChains.get(entry.getKey());
                if(connection == null || !connection.isOpen() ||
                    !connection.sessionId().equals(entry.getValue().sessionId) ||
                    !selected.contains(entry.getKey().getConfigurationId()) ||
                    active == null || active.chain() != entry.getValue().chain)
                {
                    if(mExports.remove(entry.getKey(), entry.getValue())) entry.getValue().close();
                }
            }
            if(connection != null)
            {
                mActiveChains.values().stream()
                    .sorted(Comparator.comparing((ActiveChain active) -> active.channel().isStandardChannel())
                        .reversed())
                    .forEach(this::startExportIfEligible);
            }
        }
    }

    private void startExportIfEligible(ActiveChain active)
    {
        synchronized(mExports)
        {
            startExportIfEligibleLocked(active);
        }
    }

    private void startExportIfEligibleLocked(ActiveChain active)
    {
        Channel channel = active.channel();
        if(mActiveChains.get(channel) != active || mOutbound == null ||
            !mSettings.outbound().exportedChannelIds().contains(channel.getConfigurationId()) ||
            channel.getSourceConfiguration() instanceof SourceConfigRemote ||
            !(channel.getDecodeConfiguration().getDecoderType() == DecoderType.P25_PHASE1 ||
                channel.getDecodeConfiguration().getDecoderType() == DecoderType.P25_PHASE2) ||
            mExports.containsKey(channel))
        {
            return;
        }
        RemoteLinkTransport.Connection connection = mOutbound;
        if(connection == null) return;
        ExportStream export = new ExportStream(channel, active.chain(), connection.sessionId());
        if(mExports.putIfAbsent(channel, export) != null) return;
        long frequency = active.chain().getSource() != null ? active.chain().getSource().getFrequency() : 0L;
        if(frequency <= 0)
        {
            mExports.remove(channel, export);
            return;
        }
        Integer nac = null;
        if(active.request() != null)
        {
            for(var preload: active.request().getPreloadDataContents())
            {
                if(preload instanceof P25P1NACPreloadDataContent p25Nac) nac = p25Nac.getNAC();
            }
        }
        Integer wacn = null;
        Integer systemId = null;
        if(channel.getDecodeConfiguration() instanceof DecodeConfigP25Phase2 p2)
        {
            ScrambleParameters scramble = p2.getScrambleParameters();
            if(scramble != null)
            {
                nac = scramble.getNAC();
                wacn = scramble.getWACN();
                systemId = scramble.getSystem();
            }
        }
        if(channel.isTrafficChannel() &&
            (channel.getDecodeConfiguration().getDecoderType() == DecoderType.P25_PHASE1 &&
                (nac == null || nac == 0xF7E || nac == 0xF7F) ||
                channel.getDecodeConfiguration().getDecoderType() == DecoderType.P25_PHASE2 &&
                    (nac == null || wacn == null || systemId == null)))
        {
            mExports.remove(channel, export);
            return;
        }
        RemoteWireMessages.Open open = new RemoteWireMessages.Open(export.streamId,
            channel.getConfigurationId(), channel.isStandardChannel(),
            channel.getDecodeConfiguration().getDecoderType() == DecoderType.P25_PHASE2 ? "PHASE_2" : "PHASE_1",
            frequency, export.generation, System.currentTimeMillis(), nac, wacn, systemId);
        if(!mOutboundPackets.offer(new OutboundOpen(export.sessionId, open)))
        {
            mExports.remove(channel, export);
            if(connection != null) connection.close();
            return;
        }
        active.chain().addDemodulatedBitstreamListener(export.listener);
        if(mActiveChains.get(channel) != active || mOutbound != connection || !connection.isOpen())
        {
            mExports.remove(channel, export);
            export.close();
        }
    }

    @Override
    public synchronized RemoteLinkSnapshot snapshot()
    {
        Settings settings = mSettings;
        List<AliasListOption> aliases = mChannels.options().aliasLists().stream()
            .filter(alias -> "P25".equals(alias.family()))
            .map(alias -> new AliasListOption(alias.id(), alias.name())).toList();
        List<ExportChannelOption> exports = mSavedChannels.values().stream()
            .filter(SavedChannel::exportable).map(channel -> new ExportChannelOption(channel.id(),
                channel.name(), channel.system(), channel.site(), channel.decoderDisplay())).toList();
        List<SenderSnapshot> senders = settings.trustedSenders().stream()
            .map(sender -> senderSnapshot(sender, aliases)).toList();
        Outbound outbound = settings.outbound();
        return new RemoteLinkSnapshot(settings.revision(), new ListenerSnapshot(settings.listenerEnabled(),
            settings.bindAddress(), settings.listenPort(), mListenerState, mListenerStatus, List.of()),
            new SenderConnectionSnapshot(outbound.enabled(), outbound.host(), outbound.port(), outbound.senderId(),
                outbound.secret() != null && !outbound.secret().isBlank(), mOutboundState,
                mLastOutboundConnectionMs, mOutboundStatus, outbound.exportedChannelIds()),
            exports, aliases, senders);
    }

    @Override
    public OriginSnapshot originSnapshot()
    {
        return mOriginSnapshot;
    }

    @Override
    public synchronized RemoteLinkSnapshot updateListener(long expectedRevision, ListenerUpdate request)
    {
        requireRevision(expectedRevision);
        Objects.requireNonNull(request);
        String bind = boundedText(request.bindAddress(), 255, "Listener address");
        requirePort(request.port());
        Settings old = mSettings;
        replaceSettings(new Settings(old.revision() + 1, request.enabled(), bind, request.port(),
            old.trustedSenders(), old.outbound()));
        mListenerState = request.enabled() ? ListenerState.STARTING : ListenerState.STOPPED;
        mListenerStatus = null;
        Thread.ofVirtual().start(this::refreshListener);
        return snapshot();
    }

    @Override
    public synchronized RemoteLinkSnapshot updateSenderConnection(long expectedRevision,
                                                                     SenderConnectionUpdate request)
    {
        requireRevision(expectedRevision);
        Objects.requireNonNull(request);
        String host = boundedText(request.destinationHost(), 255, "Destination host");
        requirePort(request.destinationPort());
        String senderId = canonicalUuid(request.senderId());
        Set<String> exported = new HashSet<>();
        for(String channelId: request.exportedChannelConfigurationIds())
        {
            String id = canonicalUuid(channelId);
            if(!exported.add(id) || !isExportableChannel(id))
            {
                throw new IllegalArgumentException("Select distinct saved P25 trunked channels to export");
            }
        }
        if(exported.size() > RemoteWireMessages.MAXIMUM_FEEDS)
        {
            throw new IllegalArgumentException("Too many exported channels");
        }
        String secret = request.secret() != null && !request.secret().isBlank() ?
            boundedText(request.secret(), 256, "Sender key") : mSettings.outbound().secret();
        if(request.enabled() && (host.isBlank() || senderId == null || secret == null || secret.isBlank()))
        {
            throw new IllegalArgumentException("Host, sender ID, and key are required to connect");
        }
        Settings old = mSettings;
        replaceSettings(new Settings(old.revision() + 1, old.listenerEnabled(), old.bindAddress(), old.listenPort(),
            old.trustedSenders(), new Outbound(request.enabled(), host, request.destinationPort(), senderId, secret,
                List.copyOf(exported))));
        mOutboundState = request.enabled() ? SenderConnectionState.CONNECTING : SenderConnectionState.DISABLED;
        mOutboundStatus = null;
        mCatalogDirty.set(true);
        RemoteLinkTransport.Connection prior = mOutbound;
        if(prior != null) prior.close();
        refreshExports();
        return snapshot();
    }

    @Override
    public synchronized CreateSenderResult createSender(long expectedRevision, CreateSenderRequest request)
    {
        requireRevision(expectedRevision);
        Objects.requireNonNull(request);
        String name = boundedText(request.displayName(), 120, "Sender name");
        if(name.isBlank() || mSettings.trustedSenders().stream().filter(sender -> !sender.revoked()).count() >=
            MAXIMUM_SENDERS || mSettings.trustedSenders().size() >= 256)
        {
            throw new IllegalArgumentException("Sender name is required or sender limit was reached");
        }
        String id = UUID.randomUUID().toString();
        byte[] secretBytes = new byte[32];
        RANDOM.nextBytes(secretBytes);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);
        Settings old = mSettings;
        List<TrustedSender> senders = new ArrayList<>(old.trustedSenders());
        senders.add(new TrustedSender(id, name, secret, false, null, System.currentTimeMillis(), false));
        replaceSettings(new Settings(old.revision() + 1, old.listenerEnabled(), old.bindAddress(), old.listenPort(),
            senders, old.outbound()));
        return new CreateSenderResult(mSettings.revision(), id, name, secret);
    }

    @Override
    public synchronized RemoteLinkSnapshot updateSender(long expectedRevision, String senderId,
                                                         UpdateSenderRequest request)
    {
        requireRevision(expectedRevision);
        Objects.requireNonNull(request);
        if(request.defaultAliasListId() != null) requireP25AliasList(request.defaultAliasListId());
        String name = boundedText(request.displayName(), 120, "Sender name");
        if(name.isBlank()) throw new IllegalArgumentException("Sender name is required");
        replaceSender(senderId, sender -> new TrustedSender(sender.senderId(), name, sender.secret(),
            sender.autoAdopt(), request.defaultAliasListId(), sender.pairedAtMs(), sender.revoked()));
        scheduleCatalogSetup(canonicalUuid(senderId));
        publishOriginSnapshot();
        return snapshot();
    }

    @Override
    public synchronized RemoteLinkSnapshot revokeSender(long expectedRevision, String senderId)
    {
        requireRevision(expectedRevision);
        replaceSender(senderId, sender -> new TrustedSender(sender.senderId(), sender.displayName(), null,
            false, sender.defaultAliasListId(), sender.pairedAtMs(), true));
        PeerState peer = mPeers.get(canonicalUuid(senderId));
        if(peer != null && peer.connection != null) peer.connection.close();
        publishOriginSnapshot();
        return snapshot();
    }

    @Override
    public synchronized RemoteLinkSnapshot updateFeed(long expectedRevision, String senderId, String feedId,
                                                       UpdateFeedRequest request)
    {
        requireRevision(expectedRevision);
        requireP25AliasList(request.aliasListId());
        FeedKey key = new FeedKey(canonicalUuid(senderId), canonicalUuid(feedId));
        Channel channel = findHostChannel(key);
        if(channel == null) throw new NotFoundException();
        bumpRevision();
        updateRemoteChannel(channel, boundedText(request.displayName(), 120, "Channel name"),
            request.aliasListId(), request.enabled());
        publishOriginSnapshot();
        return snapshot();
    }

    private synchronized void replaceSender(String senderId, UnaryOperator<TrustedSender> update)
    {
        String id = canonicalUuid(senderId);
        Settings old = mSettings;
        List<TrustedSender> senders = new ArrayList<>(old.trustedSenders());
        for(int index = 0; index < senders.size(); index++)
        {
            if(id.equals(senders.get(index).senderId()))
            {
                senders.set(index, update.apply(senders.get(index)));
                replaceSettings(new Settings(old.revision() + 1, old.listenerEnabled(), old.bindAddress(),
                    old.listenPort(), senders, old.outbound()));
                return;
            }
        }
        throw new NotFoundException();
    }

    private void replaceSettings(Settings next)
    {
        try
        {
            mStore.save(next);
            mSettings = next;
        }
        catch(IOException exception)
        {
            throw new UnavailableException();
        }
    }

    private void requireRevision(long expected)
    {
        if(expected != mSettings.revision()) throw new StaleRevisionException();
    }

    private void bumpRevision()
    {
        Settings old = mSettings;
        replaceSettings(new Settings(old.revision() + 1, old.listenerEnabled(), old.bindAddress(),
            old.listenPort(), old.trustedSenders(), old.outbound()));
    }

    private static String boundedText(String value, int maximumLength, String label)
    {
        String text = value != null ? value.strip() : "";
        if(text.length() > maximumLength || text.chars().anyMatch(Character::isISOControl))
        {
            throw new IllegalArgumentException(label + " is invalid");
        }
        return text;
    }

    private static int requirePort(int port)
    {
        if(port < 1 || port > 65_535) throw new IllegalArgumentException("Port must be between 1 and 65535");
        return port;
    }

    private static String canonicalUuid(String value)
    {
        if(value == null || value.isBlank()) return null;
        try { return UUID.fromString(value).toString(); }
        catch(IllegalArgumentException exception) { throw new IllegalArgumentException("Identifier must be a UUID"); }
    }

    private void requireP25AliasList(Long aliasListId)
    {
        if(aliasListId == null || mChannels.options().aliasLists().stream().noneMatch(alias ->
            alias.id() == aliasListId && "P25".equals(alias.family())))
        {
            throw new IllegalArgumentException("Select a P25 Alias List");
        }
    }

    /** Existing sender preferences are hints, not prerequisites for receiving a feed. */
    static AliasListOption preferredAliasList(TrustedSender sender, List<AliasListOption> aliases)
    {
        if(sender.defaultAliasListId() != null)
        {
            for(AliasListOption alias: aliases)
            {
                if(alias.aliasListId() == sender.defaultAliasListId()) return alias;
            }
        }
        String factoryName = AliasListFamily.P25.getDefaultAliasListName();
        return aliases.stream().filter(alias -> factoryName.equals(alias.name()))
            .min(Comparator.comparingLong(AliasListOption::aliasListId))
            .orElseGet(() -> aliases.stream().min(Comparator.comparingLong(AliasListOption::aliasListId))
                .orElse(null));
    }

    private boolean exportable(Channel channel)
    {
        if(channel == null || !channel.isStandardChannel() || channel.getSourceConfiguration() instanceof
            SourceConfigRemote || channel.getDecodeConfiguration() == null) return false;
        DecoderType type = channel.getDecodeConfiguration().getDecoderType();
        return type == DecoderType.P25_PHASE1;
    }

    private boolean isExportableChannel(String configurationId)
    {
        SavedChannel saved = mSavedChannels.get(configurationId);
        return saved != null && saved.exportable();
    }

    private SenderSnapshot senderSnapshot(TrustedSender sender, List<AliasListOption> aliases)
    {
        PeerState peer = mPeers.get(sender.senderId());
        boolean connected = peer != null && peer.connection != null && peer.connection.isOpen();
        SenderState state = sender.revoked() ? SenderState.REVOKED : connected ? SenderState.CONNECTED :
            peer != null && peer.lastSeenAtMs > 0 ? SenderState.DISCONNECTED : SenderState.WAITING;
        List<FeedSnapshot> feeds = feedsFor(sender, peer, connected, aliases);
        return new SenderSnapshot(sender.senderId(), sender.displayName(), state, sender.secret() != null,
            sender.pairedAtMs(), peer != null ? peer.lastSeenAtMs : 0L, sender.defaultAliasListId(), null, feeds);
    }

    private List<FeedSnapshot> feedsFor(TrustedSender sender, PeerState peer, boolean connected,
                                        List<AliasListOption> aliases)
    {
        Map<String,ObservedFeed> observed = peer != null ? peer.feeds : Map.of();
        Map<String,SavedChannel> savedFeeds = new HashMap<>();
        for(SavedChannel channel: mSavedChannels.values())
        {
            if(sender.senderId().equals(channel.remoteSenderId()))
            {
                savedFeeds.put(channel.remoteFeedId(), channel);
            }
        }
        Set<String> feedIds = new HashSet<>(observed.keySet());
        feedIds.addAll(savedFeeds.keySet());
        boolean aliasListAvailable = preferredAliasList(sender, aliases) != null;
        return feedIds.stream().sorted().map(feedId ->
        {
            ObservedFeed feed = observed.get(feedId);
            SavedChannel channel = savedFeeds.get(feedId);
            boolean advertised = feed != null && feed.present;
            FeedState state = !advertised ? FeedState.REMOVED :
                !"P25_PHASE1".equals(feed.advertisement.decoder()) ||
                    feed.advertisement.frequency() <= 0L ? FeedState.UNSUPPORTED :
                    !connected ? FeedState.DISCONNECTED : channel == null ? FeedState.PENDING :
                        channel.autoStart() && feed.advertisement.running() && isControlLive(sender.senderId(),
                            feedId, channel.channel()) ? FeedState.CONNECTED : FeedState.DISCONNECTED;
            String statusMessage = switch(state)
            {
                case PENDING -> !aliasListAvailable ? "Create a P25 Alias List on this host to receive this feed" :
                    feed.setupError != null ? feed.setupError : "Setting up remote channel";
                case UNSUPPORTED -> "Only P25 Phase 1 control feeds with a valid frequency are supported";
                case DISCONNECTED -> channel != null && !channel.autoStart() ? "Host channel is disabled" :
                    channel != null && connected && advertised && feed.advertisement.running() &&
                        !mActiveChains.containsKey(channel.channel()) ? "Host channel is not running" :
                    connected && advertised && feed.advertisement.running() ?
                        "Waiting for decoded control packets" : null;
                default -> null;
            };
            return new FeedSnapshot(feedId, feed != null ? feed.advertisement.name() : null,
                channel != null ? channel.name() : null,
                feed != null ? feed.advertisement.decoder() : null,
                feed != null ? feed.advertisement.system() : null,
                feed != null ? feed.advertisement.site() : null,
                null, null, null, null,
                feed != null ? feed.advertisement.frequency() : 0L, state,
                channel != null && channel.autoStart(), channel != null ? channel.id() : null,
                channel != null ? channel.aliasListId() : null, feed != null ? feed.lastSeenAtMs : 0L,
                feed != null ? feed.lagMs : null, feed != null ? feed.droppedPackets.get() : 0L,
                feed != null ? feed.sequenceGaps.get() : 0L, statusMessage);
        }).toList();
    }

    private void publishOriginSnapshot()
    {
        Map<String,RemoteOrigin> origins = new HashMap<>();
        for(SavedChannel channel: mSavedChannels.values())
        {
            if(channel.remoteSenderId() != null)
            {
                TrustedSender sender = trusted(channel.remoteSenderId());
                PeerState peer = mPeers.get(channel.remoteSenderId());
                ObservedFeed feed = peer != null ? peer.feeds.get(channel.remoteFeedId()) : null;
                boolean connected = peer != null && peer.connection != null && peer.connection.isOpen();
                boolean ready = connected && feed != null && feed.present && channel.autoStart() &&
                    feed.advertisement.running() && isControlLive(channel.remoteSenderId(),
                        channel.remoteFeedId(), channel.channel());
                FeedState state = !connected ? FeedState.DISCONNECTED : feed == null || !feed.present ?
                    FeedState.REMOVED : ready ? FeedState.CONNECTED : FeedState.DISCONNECTED;
                DependencyState dependency = ready ? DependencyState.READY : connected ?
                    DependencyState.DEGRADED : DependencyState.MISSING;
                origins.put(channel.id(), new RemoteOrigin(channel.remoteSenderId(),
                    sender != null ? sender.displayName() : "Remote sender", channel.remoteFeedId(),
                    feed != null ? feed.advertisement.name() : channel.name(), state, dependency));
            }
        }
        OriginSnapshot previous = mOriginSnapshot;
        if(previous.revision() != mSettings.revision() ||
            !previous.byChannelConfigurationId().equals(origins))
        {
            mOriginSnapshot = new OriginSnapshot(mSettings.revision(), origins);
        }
    }

    private boolean isControlLive(String senderId, String feedId, Channel channel)
    {
        if(!mActiveChains.containsKey(channel)) return false;
        PeerState peer = mPeers.get(senderId);
        if(peer == null || peer.connection == null) return false;
        String sessionId = peer.connection.sessionId();
        long now = System.nanoTime();
        return mOpenSources.values().stream().anyMatch(bound -> bound.open.control() &&
            feedId.equals(bound.open.feedId()) && sessionId.equals(bound.sessionId) &&
            bound.lastDataAtNanos > 0L && now - bound.lastDataAtNanos < TimeUnit.SECONDS.toNanos(3));
    }

    private TrustedSender trusted(String senderId)
    {
        return mSettings.trustedSenders().stream().filter(sender -> sender.senderId().equals(senderId))
            .findFirst().orElse(null);
    }

    private Channel findHostChannel(FeedKey key)
    {
        SavedChannel saved = findHostChannelState(key);
        return saved != null ? saved.channel() : null;
    }

    private SavedChannel findHostChannelState(FeedKey key)
    {
        return mSavedChannels.values().stream().filter(channel ->
            key.senderId().equals(channel.remoteSenderId()) && key.feedId().equals(channel.remoteFeedId()))
            .findFirst().orElse(null);
    }

    private void createRemoteChannel(FeedKey key, ObservedFeed feed, String name, long aliasListId)
    {
        if(name == null || name.isBlank()) throw new IllegalArgumentException("Channel name is required");
        if(mSavedChannels.values().stream().filter(channel -> channel.remoteSenderId() != null).count() >=
            MAXIMUM_REMOTE_FEEDS_GLOBALLY)
        {
            throw new IllegalArgumentException("Maximum number of remote feeds reached");
        }
        if(!feed.present || !"P25_PHASE1".equals(feed.advertisement.decoder()) ||
            feed.advertisement.frequency() <= 0L)
        {
            throw new IllegalArgumentException("Only advertised P25 control channels with a known frequency can be used");
        }
        ChannelDefinition template = mChannels.template("p25-phase1");
        ChannelDefinition.Source source = new ChannelDefinition.Source(
            List.of(feed.advertisement.frequency()), null, null, null, null, null,
            "remote", key.senderId(), key.feedId());
        ChannelDefinition definition = new ChannelDefinition(null, template.protocolId(),
            feed.advertisement.system(), feed.advertisement.site(), name, null, aliasListId, source,
            template.settings(), List.of(), List.of(), List.of(), List.of(), ChannelDefinition.Observed.EMPTY);
        ChannelAdministrationService.MutationResult created = mChannels.create(definition, mChannels.currentRevision());
        String channelId = created.configurationIds().getFirst();
        mChannels.setAutoStart(List.of(channelId), true, mChannels.currentRevision());
        mChannels.setProcessing(List.of(channelId), true);
        PeerState peer = mPeers.get(key.senderId());
        if(peer != null && peer.connection != null)
        {
            rebindActiveOpens(key, peer);
        }
    }

    private void rebindActiveOpens(FeedKey key, PeerState peer)
    {
        RemoteLinkTransport.Connection connection = peer.connection;
        if(connection == null) return;
        peer.activeOpens.values().stream()
            .filter(active -> connection.sessionId().equals(active.sessionId()) &&
                key.feedId().equals(active.open().feedId()))
            .map(ActiveOpen::open)
            .sorted(Comparator.comparing(RemoteWireMessages.Open::control).reversed())
            .forEach(open -> acceptOpen(key.senderId(), connection, open));
    }

    private void updateRemoteChannel(Channel channel, String name, long aliasListId, boolean enabled)
    {
        if(name == null || name.isBlank()) throw new IllegalArgumentException("Channel name is required");
        ChannelDefinition current = mChannels.get(channel.getConfigurationId()).channel();
        ChannelDefinition updated = new ChannelDefinition(current.configurationId(), current.protocolId(),
            current.system(), current.site(), name, current.radioResolveId(), aliasListId, current.source(),
            current.settings(), current.frequencyMap(), current.eventLogs(), current.recorders(),
            current.auxiliaryDecoders(), current.observed());
        mChannels.update(channel.getConfigurationId(), updated, mChannels.currentRevision());
        mChannels.setAutoStart(List.of(channel.getConfigurationId()), enabled, mChannels.currentRevision());
        mChannels.setProcessing(List.of(channel.getConfigurationId()), enabled);
    }

    private synchronized void refreshListener()
    {
        RemoteLinkTransport.Server previous = mServer;
        mServer = null;
        if(previous != null) previous.close();
        Settings settings = mSettings;
        if(mClosed.get() || !settings.listenerEnabled())
        {
            mListenerState = ListenerState.STOPPED;
            mListenerStatus = null;
            return;
        }
        try
        {
            mServer = RemoteLinkTransport.listen(settings.bindAddress(), settings.listenPort(), senderId ->
            {
                TrustedSender trusted = trusted(senderId);
                return trusted != null && !trusted.revoked() ? trusted.secret() : null;
            }, inboundListener());
            mListenerState = ListenerState.LISTENING;
            mListenerStatus = null;
        }
        catch(IOException exception)
        {
            mListenerState = ListenerState.ERROR;
            mListenerStatus = "Unable to listen at the selected address and port";
        }
    }

    private RemoteLinkTransport.Listener inboundListener()
    {
        return new RemoteLinkTransport.Listener()
        {
            @Override
            public void connected(String senderId, RemoteLinkTransport.Connection connection)
            {
                synchronized(RemoteConnectivityService.this)
                {
                    TrustedSender sender = trusted(senderId);
                    if(mClosed.get() || sender == null || sender.revoked() || sender.secret() == null)
                    {
                        throw new IllegalStateException("Remote sender is no longer trusted");
                    }
                    PeerState peer = mPeers.computeIfAbsent(senderId, ignored -> new PeerState());
                    peer.connection = connection;
                    peer.lastSeenAtMs = System.currentTimeMillis();
                }
                publishOriginSnapshot();
            }

            @Override
            public void frame(String senderId, RemoteLinkTransport.Connection connection, Frame frame)
            {
                receiveInbound(senderId, connection, frame);
            }

            @Override
            public void disconnected(String senderId, RemoteLinkTransport.Connection connection)
            {
                PeerState peer = mPeers.get(senderId);
                closePeerStreams(senderId, connection.sessionId());
                if(peer != null && peer.connection == connection)
                {
                    peer.connection = null;
                    peer.lastSeenAtMs = System.currentTimeMillis();
                    publishOriginSnapshot();
                }
            }
        };
    }

    private RemoteLinkTransport.Listener outboundListener()
    {
        return new RemoteLinkTransport.Listener()
        {
            @Override
            public void connected(String senderId, RemoteLinkTransport.Connection connection)
            {
                // The connector assigns the connection after authentication returns.
            }

            @Override
            public void frame(String senderId, RemoteLinkTransport.Connection connection, Frame frame)
            {
                if(mOutbound == connection && frame.type() == Type.REJECT)
                {
                    mOutboundStatus = "Host rejected this remote feed";
                }
            }

            @Override
            public void disconnected(String senderId, RemoteLinkTransport.Connection connection)
            {
                boolean wasCurrent;
                synchronized(mOutboundLifecycleLock)
                {
                    wasCurrent = mOutbound == connection;
                    if(wasCurrent)
                    {
                        mOutbound = null;
                        mOutboundState = SenderConnectionState.DISCONNECTED;
                    }
                }
                if(wasCurrent)
                {
                    refreshExports();
                    // A replacement session may already be queuing its OPEN frames. Retire only this session.
                    mOutboundPackets.removeIf(event -> connection.sessionId().equals(event.sessionId()));
                }
            }
        };
    }

    private void connectLoop()
    {
        while(!mClosed.get())
        {
            Settings settings = mSettings;
            Outbound outbound = settings.outbound();
            if(!outbound.enabled())
            {
                mOutboundState = SenderConnectionState.DISABLED;
            }
            else if(mOutbound == null)
            {
                RemoteLinkTransport.Connection connection = null;
                try
                {
                    mOutboundState = SenderConnectionState.CONNECTING;
                    connection = RemoteLinkTransport.connect(outbound.host(),
                        outbound.port(), outbound.senderId(), outbound.secret(), outboundListener());
                    if(mClosed.get() || !outbound.equals(mSettings.outbound()) || !connection.isOpen())
                    {
                        connection.close();
                    }
                    else
                    {
                        mCatalogDirty.set(false);
                        sendCatalog(connection);
                        if(!connection.isOpen() || !outbound.equals(mSettings.outbound()))
                        {
                            connection.close();
                            mOutboundState = SenderConnectionState.DISCONNECTED;
                        }
                        else
                        {
                            // CATALOG is queued before exposing this connection to any OPEN/DATA producer.
                            boolean installed;
                            synchronized(mOutboundLifecycleLock)
                            {
                                installed = !mClosed.get() && outbound.equals(mSettings.outbound()) &&
                                    connection.isOpen();
                                if(installed)
                                {
                                    mOutbound = connection;
                                    mOutboundState = SenderConnectionState.CONNECTED;
                                    mOutboundStatus = null;
                                    mLastOutboundConnectionMs = System.currentTimeMillis();
                                }
                            }
                            if(installed)
                            {
                                refreshExports();
                            }
                            else connection.close();
                        }
                    }
                }
                catch(IOException | RuntimeException exception)
                {
                    if(connection != null) connection.close();
                    mOutboundState = SenderConnectionState.DISCONNECTED;
                    mOutboundStatus = "Cannot connect or authenticate with the host";
                }
            }
            try
            {
                Thread.sleep(2_000L);
            }
            catch(InterruptedException exception)
            {
                if(mClosed.get()) break;
            }
        }
    }

    private void sendCatalog(RemoteLinkTransport.Connection connection)
    {
        try
        {
            Set<String> selected = Set.copyOf(mSettings.outbound().exportedChannelIds());
            List<RemoteWireMessages.Feed> feeds = new ArrayList<>();
            for(SavedChannel channel: mSavedChannels.values())
            {
                if(selected.contains(channel.id()) && channel.exportable())
                {
                    ActiveChain active = mActiveChains.get(channel.channel());
                    long frequency = active != null && active.chain().getSource() != null ?
                        active.chain().getSource().getFrequency() : channel.sourceFrequency();
                    feeds.add(new RemoteWireMessages.Feed(channel.id(), wireLabel(channel.system()),
                        wireLabel(channel.site()), wireLabel(channel.name()), channel.decoderName(), frequency,
                        active != null));
                }
            }
            if(!connection.offer(new Frame(Type.CATALOG, RemoteWireMessages.json(
                new RemoteWireMessages.Catalog(feeds))))) connection.close();
        }
        catch(IOException | RuntimeException exception)
        {
            connection.close();
        }
    }

    /** Bound advertised text independently of saved channel labels and the fixed wire frame size. */
    private static String wireLabel(String value)
    {
        if(value == null) return "";
        String text = value.strip();
        StringBuilder safe = new StringBuilder(Math.min(text.length(), 120));
        for(int index = 0; index < text.length();)
        {
            int codePoint = text.codePointAt(index);
            if(safe.length() + Character.charCount(codePoint) > 120) break;
            safe.appendCodePoint(Character.isISOControl(codePoint) ? ' ' : codePoint);
            index += Character.charCount(codePoint);
        }
        return safe.toString();
    }

    private void refreshExportFrequencies()
    {
        synchronized(mExports)
        {
            for(Map.Entry<Channel,ExportStream> entry: Map.copyOf(mExports).entrySet())
            {
                ActiveChain active = mActiveChains.get(entry.getKey());
                if((active == null || active.chain() != entry.getValue().chain ||
                    active.chain().getSource() == null ||
                    active.chain().getSource().getFrequency() != entry.getValue().frequency) &&
                    mExports.remove(entry.getKey(), entry.getValue()))
                {
                    entry.getValue().close();
                    mCatalogDirty.set(true);
                    if(active != null) startExportIfEligible(active);
                }
            }
        }
    }

    private static long sourceFrequency(SourceConfiguration source)
    {
        if(source instanceof SourceConfigTuner tuner) return tuner.getFrequency();
        if(source instanceof SourceConfigTunerMultipleFrequency multiple)
        {
            if(multiple.getPreferredFrequency() > 0) return multiple.getPreferredFrequency();
            return multiple.getFrequencies().isEmpty() ? 0L : multiple.getFrequencies().getFirst();
        }
        return 0L;
    }

    private void publishLoop()
    {
        while(!mClosed.get())
        {
            try
            {
                OutboundEvent event = mOutboundPackets.poll(1, TimeUnit.SECONDS);
                publishOriginSnapshot();
                RemoteLinkTransport.Connection connection = mOutbound;
                if(connection == null) continue;
                if(mCatalogDirty.compareAndSet(true, false)) sendCatalog(connection);
                refreshExportFrequencies();
                if(event == null) continue;
                if(!connection.sessionId().equals(event.sessionId())) continue;
                if(event instanceof OutboundData data &&
                    System.nanoTime() - data.queuedAtNanos() > MAXIMUM_OUTBOUND_QUEUE_AGE_NANOS)
                {
                    connection.close();
                    continue;
                }
                Frame frame = switch(event)
                {
                    case OutboundOpen open -> new Frame(Type.OPEN, RemoteWireMessages.json(open.open()));
                    case OutboundData data -> new Frame(Type.DATA, RemoteWireMessages.encodeData(data.data()));
                    case OutboundClose close -> new Frame(Type.CLOSE, RemoteWireMessages.json(close.close()));
                };
                if(!connection.offer(frame)) connection.close();
            }
            catch(InterruptedException exception)
            {
                if(mClosed.get()) break;
            }
            catch(IOException | RuntimeException exception)
            {
                RemoteLinkTransport.Connection connection = mOutbound;
                if(connection != null) connection.close();
            }
        }
    }

    private void receiveInbound(String senderId, RemoteLinkTransport.Connection connection, Frame frame)
    {
        PeerState peer = mPeers.get(senderId);
        if(peer == null || peer.connection != connection) return;
        peer.lastSeenAtMs = System.currentTimeMillis();
        try
        {
            switch(frame.type())
            {
                case CATALOG -> acceptCatalog(senderId, peer,
                    RemoteWireMessages.parse(frame.payload(), RemoteWireMessages.Catalog.class));
                case OPEN -> acceptOpen(senderId, connection,
                    RemoteWireMessages.parse(frame.payload(), RemoteWireMessages.Open.class));
                case DATA -> acceptData(senderId, connection, RemoteWireMessages.decodeData(frame.payload()));
                case CLOSE -> acceptClose(senderId, connection,
                    RemoteWireMessages.parse(frame.payload(), RemoteWireMessages.Close.class));
                case GAP -> acceptGap(senderId,
                    RemoteWireMessages.parse(frame.payload(), RemoteWireMessages.Gap.class));
                default -> connection.close();
            }
        }
        catch(IOException | RuntimeException exception)
        {
            connection.close();
        }
    }

    private void acceptCatalog(String senderId, PeerState peer, RemoteWireMessages.Catalog catalog)
    {
        Map<String,ObservedFeed> previous = peer.feeds;
        Map<String,ObservedFeed> next = new HashMap<>();
        for(RemoteWireMessages.Feed feed: catalog.feeds())
        {
            ObservedFeed observed = previous.get(feed.feedId());
            if(observed == null) observed = new ObservedFeed(feed);
            else observed.refresh(feed);
            if(next.putIfAbsent(feed.feedId(), observed) != null)
            {
                throw new IllegalArgumentException("Duplicate remote feed ID");
            }
        }
        for(Map.Entry<String,ObservedFeed> entry: previous.entrySet())
        {
            if(!next.containsKey(entry.getKey()) &&
                findHostChannel(new FeedKey(senderId, entry.getKey())) != null)
            {
                entry.getValue().present = false;
                next.put(entry.getKey(), entry.getValue());
            }
        }
        peer.feeds = Map.copyOf(next);
        publishOriginSnapshot();
        scheduleCatalogSetup(senderId);
    }

    /** Coalesce catalog bursts into at most one background setup worker per trusted sender. */
    private void scheduleCatalogSetup(String senderId)
    {
        if(mClosed.get()) return;
        mCatalogSetupDirty.add(senderId);
        if(mCatalogSetupRunning.add(senderId))
        {
            Thread.ofVirtual().start(() ->
            {
                try
                {
                    while(!mClosed.get() && mCatalogSetupDirty.remove(senderId))
                    {
                        try { setupCatalogFeeds(senderId); }
                        catch(RuntimeException ignored)
                        {
                            markCatalogSetupUnavailable(senderId);
                        }
                    }
                }
                finally
                {
                    mCatalogSetupRunning.remove(senderId);
                    if(!mClosed.get() && mCatalogSetupDirty.contains(senderId)) scheduleCatalogSetup(senderId);
                }
            });
        }
    }

    private synchronized void setupCatalogFeeds(String senderId)
    {
        TrustedSender sender = trusted(senderId);
        if(sender == null || sender.revoked()) return;
        List<AliasListOption> aliases = mChannels.options().aliasLists().stream()
            .filter(alias -> "P25".equals(alias.family()))
            .map(alias -> new AliasListOption(alias.id(), alias.name())).toList();
        AliasListOption alias = preferredAliasList(sender, aliases);
        if(alias == null) return;
        PeerState peer = mPeers.get(senderId);
        if(peer == null || peer.connection == null || !peer.connection.isOpen()) return;
        for(ObservedFeed feed: peer.feeds.values())
        {
            FeedKey key = new FeedKey(senderId, feed.advertisement.feedId());
            if(feed.present && feed.advertisement.frequency() > 0L &&
                "P25_PHASE1".equals(feed.advertisement.decoder()) &&
                findHostChannel(key) == null)
            {
                try
                {
                    feed.setupError = null;
                    createRemoteChannel(key, feed, feed.advertisement.name().isBlank() ? "Remote P25" :
                        feed.advertisement.name(), alias.aliasListId());
                }
                catch(RuntimeException ignored)
                {
                    // Do not expose database exceptions, identifiers, or credentials to the page.
                    feed.setupError = "Unable to set up remote channel; check the channel limit and host configuration";
                }
            }
        }
        publishOriginSnapshot();
    }

    private void markCatalogSetupUnavailable(String senderId)
    {
        PeerState peer = mPeers.get(senderId);
        if(peer == null) return;
        for(ObservedFeed feed: peer.feeds.values())
        {
            if(feed.present && "P25_PHASE1".equals(feed.advertisement.decoder()) &&
                feed.advertisement.frequency() > 0L &&
                findHostChannel(new FeedKey(senderId, feed.advertisement.feedId())) == null)
            {
                feed.setupError = "Unable to set up remote channel; check host configuration";
            }
        }
    }

    private void acceptOpen(String senderId, RemoteLinkTransport.Connection connection, RemoteWireMessages.Open open)
    {
        PeerState peer = mPeers.get(senderId);
        ObservedFeed advertised = peer != null ? peer.feeds.get(open.feedId()) : null;
        if(advertised == null || !advertised.present ||
            !"P25_PHASE1".equals(advertised.advertisement.decoder())) return;
        StreamKey streamKey = new StreamKey(senderId, open.streamId(), open.generation());
        synchronized(mPeerAdmissionLock)
        {
            if(open.control() && peer.activeOpens.entrySet().stream().anyMatch(entry ->
                !streamKey.equals(entry.getKey()) && entry.getValue().open().control() &&
                    connection.sessionId().equals(entry.getValue().sessionId()) &&
                    open.feedId().equals(entry.getValue().open().feedId())))
            {
                throw new IllegalArgumentException("Duplicate remote control stream");
            }
            if(!peer.activeOpens.containsKey(streamKey))
            {
                if(peer.activeOpens.size() >= MAXIMUM_ACTIVE_OPENS_PER_SENDER)
                {
                    throw new IllegalArgumentException("Too many remote streams");
                }
                if(!open.control())
                {
                    long feedTraffic = peer.activeOpens.values().stream().filter(active ->
                        !active.open().control() && open.feedId().equals(active.open().feedId())).count();
                    long globalTraffic = mPeers.values().stream().flatMap(state ->
                        state.activeOpens.values().stream()).filter(active -> !active.open().control()).count();
                    if(feedTraffic >= MAXIMUM_TRAFFIC_OPENS_PER_FEED ||
                        globalTraffic >= MAXIMUM_TRAFFIC_OPENS_GLOBALLY)
                    {
                        throw new IllegalArgumentException("Remote traffic capacity reached");
                    }
                }
            }
            peer.activeOpens.put(streamKey, new ActiveOpen(open, connection.sessionId()));
        }
        FeedKey feedKey = new FeedKey(senderId, open.feedId());
        SavedChannel hostState = findHostChannelState(feedKey);
        if(hostState == null || !hostState.autoStart()) return;
        Channel hostChannel = hostState.channel();
        if(mOpenSources.containsKey(streamKey)) return;
        RemoteP25BitstreamSource source;
        if(open.control())
        {
            source = mWaitingSources.get(feedKey);
            if(source == null)
            {
                mChannels.setProcessing(List.of(hostChannel.getConfigurationId()), true);
                source = mWaitingSources.get(feedKey);
            }
            if(source != null && !isStartedSource(source, open, hostChannel))
            {
                mWaitingSources.remove(feedKey, source);
                source.stop();
                source = null;
            }
        }
        else
        {
            P25RemoteTrafficOpen traffic = new P25RemoteTrafficOpen(hostChannel.getConfigurationId(),
                open.streamId(), open.feedId(), false, open.generation(), P25RemotePhase.valueOf(open.phase()),
                open.frequency(), open.captureTimestamp(), open.nac(), open.wacn(), open.systemId());
            ChannelProcessingManager.RemoteTrafficOpenResult result = mProcessing.acceptRemoteTrafficOpen(traffic);
            if(result == ChannelProcessingManager.RemoteTrafficOpenResult.REJECTED)
            {
                peer.activeOpens.remove(streamKey);
                RemoteP25BitstreamSource failed = mOpeningSources.remove(streamKey);
                if(failed != null) failed.stop();
                return;
            }
            source = mOpeningSources.remove(streamKey);
            if(source != null && !isStartedSource(source, open, hostChannel))
            {
                source.stop();
                source = null;
            }
            if(source == null && result == ChannelProcessingManager.RemoteTrafficOpenResult.STARTING)
            {
                peer.activeOpens.remove(streamKey);
                mProcessing.acceptRemoteTrafficClose(hostChannel.getConfigurationId(), open.streamId(),
                    open.generation());
            }
        }
        if(source != null)
        {
            if(open.control()) source.updateFrequency(open.frequency());
            mOpenSources.putIfAbsent(streamKey, new BoundStream(open, source, hostChannel.getConfigurationId(),
                connection.sessionId()));
            publishOriginSnapshot();
        }
    }

    private boolean isStartedSource(RemoteP25BitstreamSource source, RemoteWireMessages.Open open,
                                    Channel hostChannel)
    {
        return mActiveChains.values().stream().anyMatch(active -> active.chain().getSource() == source &&
            (open.control() ? active.channel() == hostChannel : active.request() != null &&
                active.request().getRemoteTrafficOpen() != null &&
                open.streamId().equals(active.request().getRemoteTrafficOpen().streamId()) &&
                open.generation() == active.request().getRemoteTrafficOpen().generation()));
    }

    private void acceptData(String senderId, RemoteLinkTransport.Connection connection, RemoteWireMessages.Data data)
    {
        BoundStream bound = mOpenSources.get(new StreamKey(senderId, data.streamId(), data.generation()));
        if(bound == null || !bound.sessionId.equals(connection.sessionId())) return;
        if(data.sequence() != bound.expectedSequence)
        {
            PeerState peer = mPeers.get(senderId);
            ObservedFeed feed = peer != null ? peer.feeds.get(bound.open.feedId()) : null;
            if(feed != null) feed.sequenceGaps.incrementAndGet();
        }
        bound.expectedSequence = data.sequence() + 1;
        byte[] dibits = data.dibits();
        int symbolRate = "PHASE_2".equals(bound.open.phase()) ? 6_000 : 4_800;
        long hostCaptureTimestamp = System.currentTimeMillis() - dibits.length * 4_000L / symbolRate;
        // Localize decode-event time to this receiver so Call Matching is not dependent on clock sync between VPN
        // peers. Packet order is still controlled by the sender's sequence number, not wall-clock time.
        hostCaptureTimestamp = Math.max(bound.lastHostCaptureTimestampMs + 1L, hostCaptureTimestamp);
        bound.lastHostCaptureTimestampMs = hostCaptureTimestamp;
        if(!bound.source.offer(dibits, hostCaptureTimestamp, data.sequence()))
        {
            PeerState peer = mPeers.get(senderId);
            ObservedFeed feed = peer != null ? peer.feeds.get(bound.open.feedId()) : null;
            if(feed != null) feed.droppedPackets.incrementAndGet();
        }
        else
        {
            boolean firstPacket = bound.lastDataAtNanos == 0L;
            bound.lastDataAtNanos = System.nanoTime();
            if(firstPacket) publishOriginSnapshot();
        }
    }

    private void acceptClose(String senderId, RemoteLinkTransport.Connection connection, RemoteWireMessages.Close close)
    {
        StreamKey key = new StreamKey(senderId, close.streamId(), close.generation());
        PeerState peer = mPeers.get(senderId);
        if(peer != null)
        {
            ActiveOpen active = peer.activeOpens.get(key);
            if(active != null && connection.sessionId().equals(active.sessionId())) peer.activeOpens.remove(key, active);
        }
        BoundStream bound = mOpenSources.get(key);
        if(bound == null || !bound.sessionId.equals(connection.sessionId())) return;
        if(!mOpenSources.remove(key, bound)) return;
        if(bound.open.control())
        {
            resetIfUnbound(bound.source);
        }
        else
        {
            bound.source.close().thenRunAsync(() -> mProcessing.acceptRemoteTrafficClose(bound.hostParentId,
                close.streamId(), close.generation()));
        }
        publishOriginSnapshot();
    }

    private void acceptGap(String senderId, RemoteWireMessages.Gap gap)
    {
        BoundStream bound = mOpenSources.get(new StreamKey(senderId, gap.streamId(), gap.generation()));
        PeerState peer = mPeers.get(senderId);
        ObservedFeed feed = bound != null && peer != null ? peer.feeds.get(bound.open.feedId()) : null;
        if(feed != null) feed.sequenceGaps.addAndGet(Math.max(1L, gap.droppedPackets()));
    }

    private void closePeerStreams(String senderId, String sessionId)
    {
        PeerState peer = mPeers.get(senderId);
        if(peer != null)
        {
            peer.activeOpens.entrySet().removeIf(entry -> sessionId.equals(entry.getValue().sessionId()));
        }
        for(Map.Entry<StreamKey,BoundStream> entry: Map.copyOf(mOpenSources).entrySet())
        {
            BoundStream bound = entry.getValue();
            if(senderId.equals(entry.getKey().senderId()) && sessionId.equals(bound.sessionId) &&
                mOpenSources.remove(entry.getKey(), bound))
            {
                if(bound.open.control()) resetIfUnbound(bound.source);
                else bound.source.close().thenRunAsync(() -> mProcessing.acceptRemoteTrafficClose(
                    bound.hostParentId, entry.getKey().streamId(), entry.getKey().generation()));
            }
        }
    }

    private void resetIfUnbound(RemoteP25BitstreamSource source)
    {
        if(mOpenSources.values().stream().noneMatch(bound -> bound.source == source)) source.reset();
    }

    @Override
    public synchronized void close()
    {
        if(mClosed.compareAndSet(false, true))
        {
            RemoteLinkTransport.Server server = mServer;
            if(server != null) server.close();
            RemoteLinkTransport.Connection outbound;
            synchronized(mOutboundLifecycleLock)
            {
                outbound = mOutbound;
                mOutbound = null;
            }
            if(outbound != null) outbound.close();
            for(PeerState peer: mPeers.values()) if(peer.connection != null) peer.connection.close();
            mConfiguration.getChannelModel().removeListener(mSavedChannelListener);
            mConfiguration.getAliasModel().aliasListDefinitions().removeListener(mAliasListListener);
            refreshExports();
            mConnector.interrupt();
            mPublisher.interrupt();
        }
    }

    private record FeedKey(String senderId, String feedId)
    {
    }

    private record StreamKey(String senderId, String streamId, long generation)
    {
    }

    private static final class BoundStream
    {
        private final RemoteWireMessages.Open open;
        private final RemoteP25BitstreamSource source;
        private final String hostParentId;
        private final String sessionId;
        private long expectedSequence;
        private volatile long lastDataAtNanos;
        private long lastHostCaptureTimestampMs;

        private BoundStream(RemoteWireMessages.Open open, RemoteP25BitstreamSource source,
                            String hostParentId, String sessionId)
        {
            this.open = open;
            this.source = source;
            this.hostParentId = hostParentId;
            this.sessionId = sessionId;
        }
    }

    private static final class PeerState
    {
        private volatile RemoteLinkTransport.Connection connection;
        private volatile long lastSeenAtMs;
        private volatile Map<String,ObservedFeed> feeds = Map.of();
        private final Map<StreamKey,ActiveOpen> activeOpens = new ConcurrentHashMap<>();
    }

    private record ActiveOpen(RemoteWireMessages.Open open, String sessionId)
    {
    }

    private static final class ObservedFeed
    {
        private volatile RemoteWireMessages.Feed advertisement;
        private volatile long lastSeenAtMs;
        private final AtomicLong droppedPackets = new AtomicLong();
        private final AtomicLong sequenceGaps = new AtomicLong();
        private volatile boolean present = true;
        private volatile Long lagMs;
        private volatile String setupError;

        private ObservedFeed(RemoteWireMessages.Feed advertisement)
        {
            this.advertisement = advertisement;
            lastSeenAtMs = System.currentTimeMillis();
        }

        private void refresh(RemoteWireMessages.Feed current)
        {
            advertisement = current;
            present = true;
            lastSeenAtMs = System.currentTimeMillis();
            setupError = null;
        }
    }

    private record ActiveChain(Channel channel, ChannelStartProcessingRequest request, ProcessingChain chain)
    {
    }

    private void indexSavedChannel(ChannelEvent event)
    {
        Channel channel = event.getChannel();
        if(channel == null || !channel.isStandardChannel()) return;
        synchronized(mChannelIndexLock)
        {
            Map<String,SavedChannel> updated = new HashMap<>(mSavedChannels);
            if(event.getEvent() == ChannelEvent.Event.NOTIFICATION_DELETE)
            {
                updated.remove(channel.getConfigurationId());
            }
            else if(event.getEvent() == ChannelEvent.Event.NOTIFICATION_ADD ||
                event.getEvent() == ChannelEvent.Event.NOTIFICATION_CONFIGURATION_CHANGE)
            {
                updated.put(channel.getConfigurationId(), SavedChannel.capture(channel));
            }
            else return;
            mSavedChannels = Map.copyOf(updated);
            mCatalogDirty.set(true);
        }
        publishOriginSnapshot();
    }

    private record SavedChannel(Channel channel, String id, String system, String site, String name,
                                String decoderName, String decoderDisplay, long sourceFrequency,
                                String remoteSenderId, String remoteFeedId, boolean autoStart,
                                long aliasListId, boolean exportable)
    {
        private static SavedChannel capture(Channel channel)
        {
            SourceConfiguration source = channel.getSourceConfiguration();
            SourceConfigRemote remote = source instanceof SourceConfigRemote saved ? saved : null;
            DecoderType decoder = channel.getDecodeConfiguration() != null ?
                channel.getDecodeConfiguration().getDecoderType() : null;
            boolean supported = decoder == DecoderType.P25_PHASE1;
            return new SavedChannel(channel, channel.getConfigurationId(), channel.getSystem(), channel.getSite(),
                channel.getName(), decoder != null ? decoder.name() : "UNKNOWN",
                decoder != null ? decoder.getDisplayString() : "Unknown",
                RemoteConnectivityService.sourceFrequency(source),
                remote != null ? remote.getSenderId() : null, remote != null ? remote.getFeedId() : null,
                channel.isAutoStart(), channel.getAliasListId(), remote == null && supported);
        }
    }

    private sealed interface OutboundEvent permits OutboundOpen, OutboundData, OutboundClose
    {
        String sessionId();
    }

    private record OutboundOpen(String sessionId, RemoteWireMessages.Open open) implements OutboundEvent
    {
    }

    private record OutboundData(String sessionId, long queuedAtNanos, RemoteWireMessages.Data data)
        implements OutboundEvent
    {
    }

    private record OutboundClose(String sessionId, RemoteWireMessages.Close close) implements OutboundEvent
    {
    }

    private final class ExportStream
    {
        private final Channel channel;
        private final ProcessingChain chain;
        private final String sessionId;
        private final String streamId = UUID.randomUUID().toString();
        private final long generation = System.nanoTime() & Long.MAX_VALUE;
        private final long frequency;
        private final AtomicLong sequence = new AtomicLong();
        private final Listener<ByteBuffer> listener = this::receive;
        private volatile boolean closed;

        private ExportStream(Channel channel, ProcessingChain chain, String sessionId)
        {
            this.channel = channel;
            this.chain = chain;
            this.sessionId = sessionId;
            this.frequency = chain.getSource() != null ? chain.getSource().getFrequency() : 0L;
        }

        private void receive(ByteBuffer original)
        {
            if(closed || original == null || original.remaining() == 0) return;
            // Retuning belongs to the sender's tuner. Never label new-carrier bits with the old OPEN's frequency;
            // the publisher will close this stream and enqueue a fresh OPEN before admitting its first DATA.
            if(chain.getSource() == null || chain.getSource().getFrequency() != frequency) return;
            ByteBuffer view = original.asReadOnlyBuffer();
            int length = view.remaining();
            if(length > RemoteWireMessages.MAXIMUM_DIBIT_BYTES) return;
            byte[] bytes = new byte[length];
            view.get(bytes);
            if(closed || chain.getSource() == null || chain.getSource().getFrequency() != frequency) return;
            long number = sequence.getAndIncrement();
            int symbolRate = channel.getDecodeConfiguration().getDecoderType() == DecoderType.P25_PHASE2 ?
                6_000 : 4_800;
            long timestamp = System.currentTimeMillis() - length * 4_000L / symbolRate;
            if(!mOutboundPackets.offer(new OutboundData(sessionId, System.nanoTime(),
                new RemoteWireMessages.Data(streamId, generation,
                number, timestamp, bytes))))
            {
                // Sequence still advances; the receiver will observe a discontinuity in the next admitted packet.
            }
        }

        private void close()
        {
            closed = true;
            chain.removeDemodulatedBitstreamListener(listener);
            if(!mOutboundPackets.offer(new OutboundClose(sessionId,
                new RemoteWireMessages.Close(streamId, generation))))
            {
                RemoteLinkTransport.Connection connection = mOutbound;
                if(connection != null && sessionId.equals(connection.sessionId())) connection.close();
            }
        }
    }
}
