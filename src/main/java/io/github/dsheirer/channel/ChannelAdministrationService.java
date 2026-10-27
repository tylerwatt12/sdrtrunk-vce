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

package io.github.dsheirer.channel;

import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasConfigurationSnapshot;
import io.github.dsheirer.alias.AliasAdministrationService;
import io.github.dsheirer.channel.quality.ControlChannelQualityMonitor;
import io.github.dsheirer.channel.quality.ControlChannelQualitySnapshot;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshotProvider;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25;
import io.github.dsheirer.module.decode.traffic.RadioSystemKey;
import io.github.dsheirer.scanlist.ScanListConfiguration;
import io.github.dsheirer.configuration.ChannelConfigurationPolicy;
import io.github.dsheirer.configuration.ChannelConfigurationSnapshot;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelException;
import io.github.dsheirer.dsp.squelch.NoiseSquelch;
import io.github.dsheirer.dsp.squelch.NoiseSquelchState;
import io.github.dsheirer.module.decode.nbfm.NBFMDecoder;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.stats.activity.ReceiverActivityMaintenance;
import io.github.dsheirer.stats.activity.StatsDatabaseMaintenanceRequest;
import io.github.dsheirer.stats.TrunkedDiscoveryEvidence;
import io.github.dsheirer.source.config.SourceConfigRemote;
import io.github.dsheirer.source.config.SourceConfigTuner;
import io.github.dsheirer.source.config.SourceConfigTunerMultipleFrequency;
import io.github.dsheirer.remote.RemoteLinkSettingsStore;
import io.github.dsheirer.service.radioreference.RadioSystemNameCatalog;
import io.github.dsheirer.source.tuner.manager.DiscoveredTuner;
import io.github.dsheirer.util.ThreadPool;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Headless command boundary for web-first channel administration. No JavaFX editor class participates in reads,
 * validation, persistence, cloning, ordering, or lifecycle commands.
 */
public final class ChannelAdministrationService
{
    public static final int MAXIMUM_BULK_CHANNELS = 100;
    private static final long JSON_SAFE_INTEGER_MASK = (1L << 53) - 1L;
    private static final long SQUELCH_PREVIEW_LEASE_SECONDS = 10L;
    private static final long CONTROL_QUALITY_FRESHNESS_MILLISECONDS = 5_000L;
    private static final Map<String,Boolean> REMOTE_ORIGIN = Map.of("remote", true);
    private final ConfigurationManager mConfigurationManager;
    private final ChannelProtocolRegistry mProtocolRegistry;
    private final ChannelDefinitionCodec mCodec;
    private final Function<String,String> mDiscoverySystemNames;
    /** One web mutation or receiver lifecycle batch at a time; saturation fails without blocking request workers. */
    private final Semaphore mCommandAdmission = new Semaphore(1);
    private final Map<String,StatsDatabaseMaintenanceRequest> mStatisticsClears = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String,SquelchPreviewLease> mSquelchPreviews = new HashMap<>();
    private volatile Supplier<Map<String,String>> mTunerDisplayNames = Map::of;
    private volatile CommittedConfiguration mCommittedConfiguration =
        new CommittedConfiguration(new Catalog(0, List.of()), List.of());
    public ChannelAdministrationService(ConfigurationManager configurationManager)
    {
        this(configurationManager, new ChannelProtocolRegistry(), RadioSystemNameCatalog.bundled()::nameFor);
    }

    ChannelAdministrationService(ConfigurationManager configurationManager, ChannelProtocolRegistry protocolRegistry,
                                 Function<String,String> discoverySystemNames)
    {
        mConfigurationManager = Objects.requireNonNull(configurationManager);
        mProtocolRegistry = Objects.requireNonNull(protocolRegistry);
        mCodec = new ChannelDefinitionCodec(mProtocolRegistry);
        mDiscoverySystemNames = Objects.requireNonNull(discoverySystemNames);
    }

    public ChannelProtocolRegistry protocolRegistry()
    {
        return mProtocolRegistry;
    }

    public long currentRevision()
    {
        requireInitialized();
        return committedCatalog().revision();
    }

    public Catalog catalog()
    {
        Catalog catalog = currentCatalog();
        //History belongs to the request worker, outside the configuration/FX boundary and all receiver callbacks.
        Map<String,Long> lastControl = ChannelControlFrequencyHistory.read(
            mConfigurationManager.getDatabasePath(), catalog.channels());
        return new Catalog(catalog.revision(), catalog.channels().stream().map(channel ->
        {
            Long frequency = lastControl.get(channel.configurationId());
            return frequency != null ? channel.withDisplayFrequency(frequency, "last-control") : channel;
        }).toList());
    }

    /** Called by the configuration owner only after a complete committed model publication. */
    public void publishCommittedCatalog()
    {
        List<Channel> channels = List.copyOf(mConfigurationManager.getChannelModel().getChannels());
        Map<String,Integer> order = effectiveAutoStartOrder(channels);
        mCommittedConfiguration = new CommittedConfiguration(new Catalog(revision(), channels.stream().map(channel ->
            summary(channel, order.get(channel.getConfigurationId()))).toList()),
            mConfigurationManager.getAliasModel().aliasListDefinitions().stream()
                .map(definition -> new AliasListOption(definition.getId(), definition.getName(),
                    definition.getFamily().name())).toList());
    }

    private Catalog committedCatalog()
    {
        //All administration adapters read the manager's one published configuration view.
        return committedConfiguration().catalog();
    }

    private CommittedConfiguration committedConfiguration()
    {
        return mConfigurationManager.getChannelAdministrationService().mCommittedConfiguration;
    }

    private record CommittedConfiguration(Catalog catalog, List<AliasListOption> aliasLists)
    {
        private CommittedConfiguration { aliasLists = List.copyOf(aliasLists); }
    }

    /** Immutable committed configuration with current nonblocking receiver state. */
    public Catalog currentCatalog()
    {
        requireInitialized();
        Catalog committed = committedCatalog();
        Map<String,Channel> live = index(mConfigurationManager.getChannelModel().getChannels());
        return new Catalog(committed.revision(), committed.channels().stream().map(channel ->
        {
            Channel current = live.get(channel.configurationId());
            Long frequency = current != null ? activeControlFrequency(current, channel.channelKind()) : null;
            return channel.withRuntimeState(isProcessing(channel.configurationId()) ?
                ProcessingState.RUNNING : ProcessingState.STOPPED, frequency);
        }).toList());
    }

    /** Web navigation does not wait for the configuration owner or consult retained history. */
    public boolean hasRunningP25TrunkedChannel()
    {
        requireInitialized();
        return committedCatalog().channels().stream().anyMatch(channel ->
            "TRUNKED".equals(channel.channelKind()) &&
                ("p25-phase1".equals(channel.protocolId()) || "p25-phase2".equals(channel.protocolId())) &&
                isProcessing(channel.configurationId()));
    }

    public Entry get(String configurationId)
    {
        String id = requireConfigurationId(configurationId);
        return onConfigurationThread(() ->
        {
            Channel channel = requireChannel(id);
            Integer order = effectiveAutoStartOrder(mConfigurationManager.getChannelModel().getChannels()).get(id);
            return new Entry(revision(), mCodec.fromChannel(channel), processingState(channel), order);
        });
    }

    /** Captures the saved channel and its parent control source without consulting temporary traffic channels. */
    public P25EnrichmentSnapshot p25EnrichmentSnapshot(String configurationId)
    {
        String id = requireConfigurationId(configurationId);
        return onConfigurationThread(() ->
        {
            Channel channel = requireChannel(id);
            ChannelDefinition definition = mCodec.fromChannel(channel);
            requireCurrentP25Identity(channel, channel.getP25SiteIdentity());
            var chain = mConfigurationManager.getChannelProcessingManager().getProcessingChain(channel);
            var source = chain != null && chain.isProcessing() ? chain.getSource() : null;
            long currentFrequency = source != null ? source.getFrequency() : 0;
            Long frequency = currentFrequency > 0 ? currentFrequency : null;
            Integer order = effectiveAutoStartOrder(mConfigurationManager.getChannelModel().getChannels()).get(id);
            return new P25EnrichmentSnapshot(new Entry(revision(), definition, processingState(channel), order),
                frequency);
        });
    }

    /** Reads bounded immutable telemetry from the request side; no decoder callback waits for this check. */
    private void requireCurrentP25Identity(Channel channel, P25SiteIdentity expected)
    {
        if(expected == null) return;
        var chain = mConfigurationManager.getChannelProcessingManager().getProcessingChain(channel);
        if(chain == null || !chain.isProcessing()) return;
        for(var module: chain.getModules())
        {
            if(module instanceof P25NetworkConfigurationSnapshotProvider provider)
            {
                P25SiteIdentity current = P25SiteIdentity.from(provider.getP25NetworkConfigurationSnapshot());
                if(current != null && !expected.equals(current))
                    throw new IllegalStateException("The decoded site does not match this channel's saved identity; check the channel configuration before looking up names");
            }
        }
    }

    public Options options()
    {
        requireInitialized();
        CommittedConfiguration committed = committedConfiguration();
        List<String> tuners = mConfigurationManager.getTunerManager() != null ?
            mConfigurationManager.getTunerManager().getPreferredTunerNames() : List.of();
        Map<String,String> labels;
        try { labels = mTunerDisplayNames.get(); }
        catch(RuntimeException unavailable) { labels = Map.of(); }
        Map<String,String> currentLabels = labels;
        // Receiver hotplug state remains current; configuration choices come from one completed publication.
        return new Options(committed.catalog().revision(), committed.aliasLists(), tuners,
            tuners.stream().map(value -> new TunerOption(value, currentLabels.getOrDefault(value, value))).toList());
    }

    /** Request-side labels only; the preferred tuner values retain their allocation identity. */
    public void setTunerDisplayNames(Supplier<Map<String,String>> names)
    {
        mTunerDisplayNames = Objects.requireNonNull(names);
    }

    /**
     * Applies a short-lived runtime-only squelch preview.  The saved channel is never changed, and the original
     * settings are restored explicitly by the browser or automatically when its lease expires.
     */
    public synchronized SquelchPreviewResult previewSquelch(String configurationId, String leaseId,
                                                            float open, float close,
                                                            int hysteresisOpen, int hysteresisClose)
    {
        String id = requireConfigurationId(configurationId);

        if(!Float.isFinite(open) || !Float.isFinite(close) || open < NoiseSquelch.MINIMUM_NOISE_THRESHOLD ||
            close > NoiseSquelch.MAXIMUM_NOISE_THRESHOLD || open > close ||
            hysteresisOpen < NoiseSquelch.MINIMUM_HYSTERESIS_THRESHOLD ||
            hysteresisClose > NoiseSquelch.MAXIMUM_HYSTERESIS_THRESHOLD || hysteresisOpen > hysteresisClose)
        {
            throw new IllegalArgumentException("Squelch preview settings are invalid");
        }

        NBFMDecoder decoder = findAnalogDecoder(id);
        SquelchPreviewLease lease = mSquelchPreviews.get(id);

        if(lease == null)
        {
            if(leaseId != null && !leaseId.isBlank())
            {
                throw new IllegalStateException("The squelch preview expired; adjust the control again");
            }

            NoiseSquelchState original = decoder.getNoiseSquelchState();
            lease = new SquelchPreviewLease(UUID.randomUUID().toString(), decoder, original);
            mSquelchPreviews.put(id, lease);
        }
        else if(!lease.id().equals(leaseId) || lease.decoder() != decoder)
        {
            throw new IllegalStateException("Another squelch preview is already active for this channel");
        }

        decoder.previewSquelch(open, close, hysteresisOpen, hysteresisClose);
        SquelchPreviewLease activeLease = lease;
        lease.renew(ThreadPool.SCHEDULED.schedule(() -> expireSquelchPreview(id, activeLease.id()),
            SQUELCH_PREVIEW_LEASE_SECONDS, TimeUnit.SECONDS));
        return new SquelchPreviewResult(lease.id(),
            System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(SQUELCH_PREVIEW_LEASE_SECONDS));
    }

    public synchronized void restoreSquelchPreview(String configurationId, String leaseId)
    {
        String id = requireConfigurationId(configurationId);
        SquelchPreviewLease lease = mSquelchPreviews.get(id);

        if(lease != null && lease.id().equals(leaseId))
        {
            mSquelchPreviews.remove(id);
            lease.restore();
        }
    }

    private synchronized void expireSquelchPreview(String configurationId, String leaseId)
    {
        SquelchPreviewLease lease = mSquelchPreviews.get(configurationId);

        if(lease != null && lease.id().equals(leaseId))
        {
            mSquelchPreviews.remove(configurationId);
            lease.restore();
        }
    }

    private NBFMDecoder findAnalogDecoder(String configurationId)
    {
        return mConfigurationManager.getChannelProcessingManager()
            .getProcessingChainsByConfiguration(configurationId, null).stream()
            .filter(chain -> chain.isProcessing())
            .flatMap(chain -> chain.getModules().stream())
            .filter(NBFMDecoder.class::isInstance)
            .map(NBFMDecoder.class::cast)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("The analog channel is not currently running"));
    }

    public ChannelDefinition template(String protocolId)
    {
        ChannelProtocolRegistry.Profile profile = mProtocolRegistry.require(protocolId);
        return onConfigurationThread(() ->
        {
            AliasListDefinition aliasList = mConfigurationManager.getAliasModel().aliasListDefinitions().stream()
                .filter(candidate -> candidate.getFamily() == profile.aliasFamily()).findFirst().orElse(null);
            return new ChannelDefinition(null, profile.id(), null, null, "Channel", null,
                aliasList != null ? aliasList.getId() : AliasListDefinition.UNASSIGNED_ID,
                new ChannelDefinition.Source(List.of(), null, null, null, null, null),
                profile.defaultSettings(), List.of(), List.of(), List.of(), List.of(),
                ChannelDefinition.Observed.EMPTY);
        });
    }

    public MutationResult create(ChannelDefinition definition)
    {
        return create(definition, false);
    }

    /** Spectrum handoffs may reject an already saved local frequency within the same creation transaction. */
    public MutationResult create(ChannelDefinition definition, boolean rejectExistingFrequency)
    {
        return admitted(() -> mutate(() -> {}, false, channels ->
        {
            AliasListDefinition aliasList = requireAliasList(definition.aliasListId());
            Channel created = mCodec.toChannel(definition, aliasList, null);
            if(rejectExistingFrequency && (created.getSourceConfiguration() instanceof SourceConfigTuner ||
                created.getSourceConfiguration() instanceof SourceConfigTunerMultipleFrequency))
            {
                Set<Long> requestedFrequencies = new HashSet<>(definition.source().frequenciesHz());
                for(Channel existing: channels)
                {
                    if(!(existing.getSourceConfiguration() instanceof SourceConfigTuner) &&
                        !(existing.getSourceConfiguration() instanceof SourceConfigTunerMultipleFrequency)) continue;
                    for(long frequency: mCodec.fromChannel(existing).source().frequenciesHz())
                    {
                        if(requestedFrequencies.contains(frequency))
                            throw new IllegalStateException(String.format(java.util.Locale.ROOT,
                                "A channel already uses %.6f MHz.", frequency / 1_000_000.0));
                    }
                }
            }
            channels.add(created);
            requireUniqueRadioResolveIds(channels);
            requireUniqueRemoteSource(created, channels);
            return new MutationTarget(Set.of(created.getConfigurationId()), List.of(created.getConfigurationId()));
        }));
    }

    /** Saved frequencies remain reserved even when their channel is stopped. */
    public List<DiscoveryFrequencyMatch> discoveryFrequencyMatches(long frequencyHz)
    {
        if(frequencyHz <= 0) throw new IllegalArgumentException("Frequency must be positive");
        return onConfigurationThread(() -> discoveryFrequencyMatches(frequencyHz,
            mConfigurationManager.getChannelModel().getChannels()));
    }

    private List<DiscoveryFrequencyMatch> discoveryFrequencyMatches(long frequencyHz, List<Channel> channels)
    {
            List<DiscoveryFrequencyMatch> matches = new ArrayList<>();
            for(Channel channel: channels)
            {
                ChannelDefinition definition = mCodec.fromChannel(channel);
                int halfBandwidth = channel.getDecodeConfiguration().getChannelSpecification().getBandwidth() / 2;
                Set<Long> frequencies = new HashSet<>(definition.source().frequenciesHz());
                frequencies.addAll(definition.observed().learnedControlFrequenciesHz());
                definition.frequencyMap().forEach(entry -> frequencies.add(entry.downlinkHz()));
                if(frequencies.stream().anyMatch(value -> value > 0 && Math.abs(value - frequencyHz) <= halfBandwidth))
                    matches.add(new DiscoveryFrequencyMatch(channel.getConfigurationId(), channel.getName(),
                        channel.getSystem(), channel.getSite(), "configured"));
            }
            return List.copyOf(matches);
    }

    public DiscoveryReview discoveryReview(String protocolId, long frequencyHz, String preferredTuner,
                                           P25SiteIdentity identity, String modulation)
    {
        return onConfigurationThread(() ->
        {
            ChannelProtocolRegistry.Profile profile = discoveryProfile(protocolId);
            List<DiscoveryAliasList> lists = discoveryAliasLists(profile, identity);
            String system = null;
            String site = identity != null ? String.format("Site %02X-%02X", identity.rfss(), identity.site()) : null;
            if(identity != null)
            {
                List<Channel> known = mConfigurationManager.getChannelModel().getChannels().stream()
                    .filter(channel -> channel.getP25SiteIdentity() != null &&
                        channel.getP25SiteIdentity().wacn() == identity.wacn() &&
                        channel.getP25SiteIdentity().system() == identity.system()).toList();
                system = known.stream().map(Channel::getSystem).filter(value -> value != null && !value.isBlank())
                    .findFirst().orElse(null);
                if(system == null || system.isBlank())
                    system = discoverySystemName(RadioSystemKey.p25(identity),
                        String.format("P25 %05X-%03X", identity.wacn(), identity.system()));
                site = known.stream().filter(channel -> identity.equals(channel.getP25SiteIdentity()))
                    .map(Channel::getSite).filter(value -> value != null && !value.isBlank()).findFirst().orElse(site);
            }
            Long suggested = identity != null ? suggestedDiscoveryAliasListId(lists, system) :
                lists.size() == 1 ? lists.getFirst().id() : null;
            Map<String,Object> settings = new LinkedHashMap<>(profile.defaultSettings());
            if(identity != null)
            {
                settings.put("modulation", modulation);
                settings.put("learn_announced_control_channels", true);
            }
            ChannelDefinition template = new ChannelDefinition(null, protocolId, system, site,
                identity != null ? io.github.dsheirer.service.radioreference.RadioReferenceChannelNames.trunked(system, site, frequencyHz).name() : String.format(java.util.Locale.ROOT, "%s %.6f MHz", profile.label(),
                    frequencyHz / 1_000_000.0), null, suggested != null ? suggested : 0,
                new ChannelDefinition.Source(List.of(frequencyHz), null, null,
                    identity != null ? frequencyHz : null, preferredTuner, null), settings,
                List.of(), List.of(), List.of(), List.of(), ChannelDefinition.Observed.EMPTY);
            String newListName = discoveryAliasListName(system, "Analog Channels");
            return new DiscoveryReview(revision(), template, lists, suggested, newListName);
        });
    }

    /** A serving site remains configured even when its control frequency changes. */
    public DiscoveryFrequencyMatch discoverySiteMatch(P25SiteIdentity identity)
    {
        Objects.requireNonNull(identity);
        return onConfigurationThread(() -> mConfigurationManager.getChannelModel().getChannels().stream()
            .filter(channel -> identity.equals(channel.getP25SiteIdentity()))
            .map(channel -> new DiscoveryFrequencyMatch(channel.getConfigurationId(), channel.getName(),
                channel.getSystem(), channel.getSite(), "site")).findFirst().orElse(null));
    }

    /** Builds a review only from confirmed receiver evidence, keeping unresolvable channel numbers unmapped. */
    public DiscoveryReview discoveryTrunkedReview(String protocolId, long frequencyHz, String preferredTuner,
                                                   TrunkedDiscoveryEvidence evidence)
    {
        requireTrunkedEvidence(protocolId, evidence);
        if(frequencyHz <= 0) throw new IllegalArgumentException("Frequency must be positive");
        if("p25-phase1".equals(protocolId))
            return discoveryReview(protocolId, frequencyHz, preferredTuner, evidence.identity().p25(),
                (String)evidence.settings().get("modulation"));
        return onConfigurationThread(() ->
        {
            ChannelProtocolRegistry.Profile profile = mProtocolRegistry.require(protocolId);
            List<DiscoveryAliasList> lists = discoveryTrunkedAliasLists(profile, evidence, mConfigurationManager.getChannelModel().getChannels(),
                mConfigurationManager.getAliasModel().aliasListDefinitions());
            List<Channel> known = knownTrunkedChannels(evidence, false, mConfigurationManager.getChannelModel().getChannels());
            String system = known.stream().map(Channel::getSystem)
                .filter(value -> value != null && !value.isBlank()).findFirst()
                .orElse(null);
            if(system == null || system.isBlank())
                system = discoverySystemName(evidence.identity().radioSystemKey(), evidence.systemName());
            Long suggested = suggestedDiscoveryAliasListId(lists, system);
            String site = knownTrunkedChannels(evidence, true, mConfigurationManager.getChannelModel().getChannels()).stream().map(Channel::getSite)
                .filter(value -> value != null && !value.isBlank()).findFirst().orElse(evidence.siteName());
            ChannelDefinition template = new ChannelDefinition(null, protocolId, system, site,
                io.github.dsheirer.service.radioreference.RadioReferenceChannelNames.trunked(system, site, frequencyHz).name(), null,
                suggested != null ? suggested : 0,
                new ChannelDefinition.Source(List.of(frequencyHz), null, null, frequencyHz, preferredTuner, null),
                discoveryTrunkedSettings(profile, Map.of(), evidence), evidence.frequencyMap(),
                List.of(), List.of(), List.of(), ChannelDefinition.Observed.EMPTY);
            // Without a native system identity, the frequency distinguishes otherwise identically named systems.
            String newListName = discoveryAliasListName(
                evidence.identity().radioSystemKey() != null || !known.isEmpty() ? system : null,
                String.format(java.util.Locale.ROOT, "%s %.6f", profile.label(), frequencyHz / 1_000_000.0));
            return new DiscoveryReview(revision(), template, lists, suggested, newListName);
        });
    }

    /** Catalog names are new-channel suggestions only; saved labels remain authoritative. */
    private String discoverySystemName(String radioSystemKey, String fallback)
    {
        String name = radioSystemKey != null ? mDiscoverySystemNames.apply(radioSystemKey) : null;
        return name != null && !name.isBlank() ? name : fallback;
    }

    /** Confirmed native DMR/NXDN identity belongs to the saved channel, independently of activity history. */
    public DiscoveryFrequencyMatch discoveryTrunkedSiteMatch(TrunkedDiscoveryEvidence evidence)
    {
        requireTrunkedEvidence(evidence != null ? evidence.protocolId() : null, evidence);
        if("p25-phase1".equals(evidence.protocolId())) return discoverySiteMatch(evidence.identity().p25());
        return onConfigurationThread(() -> knownTrunkedChannels(evidence, true, mConfigurationManager.getChannelModel().getChannels()).stream()
            .map(channel -> new DiscoveryFrequencyMatch(channel.getConfigurationId(), channel.getName(),
                channel.getSystem(), channel.getSite(), "site")).findFirst().orElse(null));
    }

    private static void requireTrunkedEvidence(String protocolId, TrunkedDiscoveryEvidence evidence)
    {
        if(protocolId == null || !Set.of("p25-phase1", "dmr", "nxdn").contains(protocolId) || evidence == null ||
            !protocolId.equals(evidence.protocolId()) || !evidence.verified())
            throw new IllegalArgumentException("A confirmed trunked serving-site identity is required");
        if("p25-phase1".equals(protocolId) && evidence.identity().p25() == null)
            throw new IllegalArgumentException("A confirmed P25 identity is required");
        if(!"p25-phase1".equals(protocolId))
        {
            if(!"TRUNKED".equals(evidence.settings().get("channel_mode")))
                throw new IllegalArgumentException("Confirmed trunked decoder settings are required");
            TrunkedDiscoveryIdentity.from(evidence);
        }
    }

    private Map<String,Object> discoveryTrunkedSettings(ChannelProtocolRegistry.Profile profile,
                                                        Map<String,Object> submitted,
                                                        TrunkedDiscoveryEvidence evidence)
    {
        Map<String,Object> settings = new LinkedHashMap<>(submitted);
        // The review may edit ordinary decoder options, but cannot change the verified protocol/variant.
        settings.putAll(evidence.settings());
        return mProtocolRegistry.validateSettings(profile, settings);
    }

    private List<Channel> knownTrunkedChannels(TrunkedDiscoveryEvidence evidence, boolean siteOnly,
                                              List<Channel> channels)
    {
        return channels.stream().filter(channel ->
            ChannelConfigurationPolicy.requireChannelKind(channel) == ChannelConfigurationPolicy.ChannelKind.TRUNKED &&
            channel.getTrunkedDiscoveryIdentity() != null &&
            mProtocolRegistry.require(channel.getDecodeConfiguration().getDecoderType()).id().equals(evidence.protocolId()) &&
            channel.getTrunkedDiscoveryIdentity().matches(evidence, siteOnly)).toList();
    }

    private List<DiscoveryAliasList> discoveryTrunkedAliasLists(ChannelProtocolRegistry.Profile profile,
        TrunkedDiscoveryEvidence evidence, List<Channel> channels, List<AliasListDefinition> definitions)
    {
        Set<Long> matchingIds = knownTrunkedChannels(evidence, false, channels).stream().map(Channel::getAliasListId)
            .collect(java.util.stream.Collectors.toSet());
        Set<Long> conflictingIds = channels.stream().filter(channel -> confirmedDiscoverySystem(channel) &&
            (channel.getTrunkedDiscoveryIdentity() == null || !channel.getTrunkedDiscoveryIdentity().matches(evidence, false)))
            .map(Channel::getAliasListId).collect(java.util.stream.Collectors.toSet());
        return eligibleDiscoveryAliasLists(profile, matchingIds, conflictingIds, definitions);
    }

    private ChannelProtocolRegistry.Profile discoveryProfile(String protocolId)
    {
        if(!Set.of("p25-phase1", "am", "nbfm").contains(protocolId))
            throw new IllegalArgumentException("This protocol is not available for spectrum channel creation");
        return mProtocolRegistry.require(protocolId);
    }

    private List<DiscoveryAliasList> discoveryAliasLists(ChannelProtocolRegistry.Profile profile,
                                                       P25SiteIdentity identity)
    {
        return discoveryAliasLists(profile, identity, mConfigurationManager.getChannelModel().getChannels(),
            mConfigurationManager.getAliasModel().aliasListDefinitions());
    }

    private List<DiscoveryAliasList> discoveryAliasLists(ChannelProtocolRegistry.Profile profile,
        P25SiteIdentity identity, List<Channel> channels, List<AliasListDefinition> definitions)
    {
        Set<Long> matchingIds = new HashSet<>();
        Set<Long> conflictingIds = new HashSet<>();
        if(identity != null)
            for(Channel channel: channels)
            {
                P25SiteIdentity known = channel.getP25SiteIdentity();
                if(known != null && known.wacn() == identity.wacn() && known.system() == identity.system())
                    matchingIds.add(channel.getAliasListId());
                else if(confirmedDiscoverySystem(channel)) conflictingIds.add(channel.getAliasListId());
            }
        return eligibleDiscoveryAliasLists(profile, matchingIds, conflictingIds, definitions);
    }

    /** Unknown/manual assignments do not prove that an administrator-owned list belongs to another system. */
    private static boolean confirmedDiscoverySystem(Channel channel)
    {
        return channel.getP25SiteIdentity() != null || channel.getTrunkedDiscoveryIdentity() != null &&
            channel.getTrunkedDiscoveryIdentity().identity().radioSystemKey() != null;
    }

    private static List<DiscoveryAliasList> eligibleDiscoveryAliasLists(ChannelProtocolRegistry.Profile profile,
        Set<Long> matchingIds, Set<Long> conflictingIds, List<AliasListDefinition> definitions)
    {
        return definitions.stream()
            .filter(list -> list.getFamily() == profile.aliasFamily() && !conflictingIds.contains(list.getId()))
            .map(list -> new DiscoveryAliasList(list.getId(), list.getName(), matchingIds.contains(list.getId())))
            .sorted(Comparator.comparing(DiscoveryAliasList::name, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    /** Suggestions never change the explicit durable-ID/new-list choice submitted by the administrator. */
    public static Long suggestedDiscoveryAliasListId(List<DiscoveryAliasList> lists, String system)
    {
        List<DiscoveryAliasList> matched = lists.stream().filter(DiscoveryAliasList::matched).toList();
        if(matched.size() == 1) return matched.getFirst().id();
        if(system != null && !system.isBlank())
            return lists.stream().filter(list -> discoveryAliasListName(system, null).equalsIgnoreCase(list.name()))
                .map(DiscoveryAliasList::id).findFirst().orElse(null);
        return null;
    }

    /** Identity is supplied by the receiver's probe, never by a browser channel document. */
    public DiscoveryCreated createDiscovered(ChannelDefinition definition, P25SiteIdentity identity,
                                               String newAliasListName, long expectedRevision)
    {
        return createDiscovered(definition, identity, newAliasListName, expectedRevision, true);
    }

    public DiscoveryCreated createDiscovered(ChannelDefinition definition, P25SiteIdentity identity,
                                               String newAliasListName, long expectedRevision, boolean autoStart)
    {
        return createSingleDiscovery(new DiscoveryRequest(definition, identity, null, newAliasListName, autoStart), expectedRevision);
    }

    public DiscoveryCreated createTrunkedDiscovered(ChannelDefinition definition, TrunkedDiscoveryEvidence evidence,
                                                     String newAliasListName, long expectedRevision, boolean autoStart)
    {
        requireTrunkedEvidence(definition.protocolId(), evidence);
        return createSingleDiscovery(new DiscoveryRequest(definition, evidence.identity().p25(), evidence,
            newAliasListName, autoStart), expectedRevision);
    }

    private DiscoveryCreated createSingleDiscovery(DiscoveryRequest request, long revision)
    {
        DiscoveryBatch batch = createDiscoveredBatch(List.of(request), revision, () -> false);
        DiscoverySave saved = batch.results().getFirst();
        if(saved.failure() != null) throw saved.failure();
        if(batch.publicationFailure() != null)
            throw new ConfigurationManager.ConfigurationPublicationException(batch.publicationFailure().getMessage(),
                batch.publicationFailure(), saved.created().configurationId(), saved.created().aliasListId());
        return saved.created();
    }

    /** One admitted, bounded transaction for valid rows; rejected rows retain their individual retry reason. */
    public DiscoveryBatch createDiscoveredBatch(List<DiscoveryRequest> requests, long expectedRevision,
                                                 java.util.function.BooleanSupplier cancelled)
    {
        if(requests == null || requests.isEmpty() || requests.size() > MAXIMUM_BULK_CHANNELS)
            throw new IllegalArgumentException("Choose between 1 and " + MAXIMUM_BULK_CHANNELS + " channels");
        List<DiscoveryRequest> rows = List.copyOf(requests);
        Objects.requireNonNull(cancelled);
        return admitted(() -> onConfigurationThread(() -> mConfigurationManager.applyConfigurationMutation(() ->
        {
            mConfigurationManager.flushConfiguration();
            requireRevision(expectedRevision);
            List<Channel> channels = detachedChannels(false);
            AliasConfigurationSnapshot aliases = mConfigurationManager.createDetachedAliasConfigurationSnapshot();
            List<DiscoverySave> results = new ArrayList<>();
            Set<String> createdIds = new LinkedHashSet<>();
            Map<DiscoveryListChoice,Long> createdAliasLists = new LinkedHashMap<>();
            boolean aliasesChanged = false;
            for(DiscoveryRequest row: rows)
            {
                try
                {
                    if(cancelled.getAsBoolean()) throw new IllegalStateException("The search was cancelled");
                    DiscoveryListChoice choice = discoveryListChoice(row);
                    Long sharedId = choice != null ? createdAliasLists.get(choice) : null;
                    PreparedDiscovery prepared = prepareDiscovery(sharedId != null ? withDiscoveryAliasList(row, sharedId) : row,
                        channels, aliases);
                    aliases = prepared.aliases();
                    Channel created = prepared.channel();
                    channels.add(created);
                    createdIds.add(created.getConfigurationId());
                    aliasesChanged |= prepared.created().aliasListCreated();
                    if(choice != null && prepared.created().aliasListCreated())
                        createdAliasLists.put(choice, prepared.created().aliasListId());
                    results.add(new DiscoverySave(prepared.created(), null));
                }
                catch(IllegalArgumentException | IllegalStateException rejected)
                {
                    results.add(new DiscoverySave(null, rejected));
                }
            }
            if(createdIds.isEmpty()) return new DiscoveryBatch(results, null);
            if(cancelled.getAsBoolean()) throw new IllegalStateException("The search was cancelled");
            try
            {
                if(aliasesChanged)
                    mConfigurationManager.commitAndPublishDiscoveredChannels(aliases,
                        new ChannelConfigurationSnapshot(channels), createdIds);
                else
                    mConfigurationManager.commitAndPublishChannelConfiguration(new ChannelConfigurationSnapshot(channels),
                        createdIds, false);
            }
            catch(ConfigurationManager.ConfigurationCommitException exception)
            {
                throw new PersistenceException("The channels could not be saved", exception);
            }
            catch(ConfigurationManager.ConfigurationPublicationException exception)
            {
                // Every successful row is already committed; callers must never retry those identities.
                return new DiscoveryBatch(results, exception);
            }
            return new DiscoveryBatch(results, null);
        })));
    }

    private record DiscoveryListChoice(String system, String name) {}

    /** One explicit new-list choice serves all sites of that system in this admitted batch only. */
    private static DiscoveryListChoice discoveryListChoice(DiscoveryRequest request)
    {
        if(request.definition().aliasListId() != 0) return null;
        String system = request.evidence() != null ? request.evidence().groupId() :
            request.p25Identity() != null ? RadioSystemKey.p25(request.p25Identity()) : null;
        if(system == null) return null;
        String name = request.newAliasListName() != null ? request.newAliasListName().strip() :
            discoveryAliasListName(request.definition().system(), request.definition().name());
        return new DiscoveryListChoice(system, name.toLowerCase(java.util.Locale.ROOT));
    }

    private static DiscoveryRequest withDiscoveryAliasList(DiscoveryRequest request, long aliasListId)
    {
        ChannelDefinition source = request.definition();
        ChannelDefinition definition = new ChannelDefinition(source.configurationId(), source.protocolId(), source.system(),
            source.site(), source.name(), source.radioResolveId(), aliasListId, source.source(), source.settings(),
            source.frequencyMap(), source.eventLogs(), source.recorders(), source.auxiliaryDecoders(), source.observed());
        return new DiscoveryRequest(definition, request.p25Identity(), request.evidence(), null, request.autoStart());
    }

    private PreparedDiscovery prepareDiscovery(DiscoveryRequest request, List<Channel> channels,
                                                 AliasConfigurationSnapshot aliases)
    {
        ChannelDefinition definition = Objects.requireNonNull(request.definition());
        P25SiteIdentity identity = request.p25Identity();
        TrunkedDiscoveryEvidence evidence = request.evidence();
        if(evidence != null)
        {
            requireTrunkedEvidence(definition.protocolId(), evidence);
            if(!Objects.equals(identity, evidence.identity().p25()))
                throw new IllegalArgumentException("The confirmed serving-site identity changed");
        }
        ChannelProtocolRegistry.Profile profile = evidence != null ?
            mProtocolRegistry.require(definition.protocolId()) : discoveryProfile(definition.protocolId());
        if("p25-phase1".equals(profile.id()) != (identity != null))
            throw new IllegalArgumentException("A confirmed P25 identity is required");
        if(definition.source().frequenciesHz().size() != 1 ||
            !discoveryFrequencyMatches(definition.source().frequenciesHz().getFirst(), channels).isEmpty())
            throw new IllegalStateException("This frequency already belongs to a saved channel");
        if(identity != null && channels.stream().anyMatch(channel -> identity.equals(channel.getP25SiteIdentity())))
            throw new IllegalStateException("This P25 site already has a saved channel");
        if(evidence != null && identity == null && !knownTrunkedChannels(evidence, true, channels).isEmpty())
            throw new IllegalStateException("This trunked site already has a saved channel");
        List<DiscoveryAliasList> compatibleLists = evidence != null && identity == null ?
            discoveryTrunkedAliasLists(profile, evidence, channels, aliases.definitions()) :
            discoveryAliasLists(profile, identity, channels, aliases.definitions());
        boolean newList = definition.aliasListId() == 0;
        AliasListDefinition selected;
        if(newList)
        {
            String name = request.newAliasListName() != null ? request.newAliasListName().strip() :
                discoveryAliasListName(definition.system(), definition.name());
            if(name.isBlank() || name.length() > AliasAdministrationService.MAX_ALIAS_LIST_NAME_LENGTH)
                throw new IllegalArgumentException("Alias List name must contain between 1 and " +
                    AliasAdministrationService.MAX_ALIAS_LIST_NAME_LENGTH + " characters");
            AliasListDefinition existing = aliases.definitions().stream()
                .filter(list -> name.equalsIgnoreCase(list.getName())).findFirst().orElse(null);
            if(existing != null)
            {
                throw new IllegalArgumentException("An Alias List named [" + name +
                    "] already exists. Use that list or choose a different name.");
            }
            else
            {
                selected = new AliasListDefinition(name, profile.aliasFamily());
                selected.setId(mConfigurationManager.nextAliasListIds(aliases.definitions().stream()
                    .map(AliasListDefinition::getId).toList(), 1).getFirst());
            }
        }
        else
        {
            if(compatibleLists.stream().noneMatch(list -> list.id() == definition.aliasListId()))
                throw new IllegalArgumentException("Choose a compatible Alias List for this system");
            selected = aliases.definitions().stream().filter(list -> list.getId() == definition.aliasListId()).findFirst().orElseThrow();
        }
        ChannelDefinition normalized = new ChannelDefinition(null, definition.protocolId(), definition.system(),
            definition.site(), definition.name(), null, selected.getId(), definition.source(),
            evidence != null ? discoveryTrunkedSettings(profile, definition.settings(), evidence) : definition.settings(),
            evidence != null ? evidence.frequencyMap() : List.of(), List.of(), List.of(), List.of(), ChannelDefinition.Observed.EMPTY);
        Channel created = mCodec.toChannel(normalized, selected, null);
        if(created.getDecodeConfiguration() instanceof DecodeConfigP25 p25)
        {
            p25.setLearnAnnouncedControlChannels(true);
            created.setP25SiteIdentity(identity);
        }
        else if(evidence != null) created.setTrunkedDiscoveryIdentity(TrunkedDiscoveryIdentity.from(evidence));
        created.setAutoStart(request.autoStart());
        created.setAutoStartOrder(request.autoStart() ? effectiveAutoStartIds(channels).size() + 1 : null);
        if(newList)
        {
            List<AliasListDefinition> definitions = new ArrayList<>(aliases.definitions());
            definitions.add(selected);
            ScanListConfiguration scans = aliases.scanLists();
            Set<Long> defaultMembership = Set.of(scans.defaultScanList().getId());
            Map<Long,Set<Long>> unmatched = new HashMap<>(scans.unmatchedAliasListMemberships());
            Map<Long,Set<Long>> newAliases = new HashMap<>(scans.newAliasListMemberships());
            unmatched.put(selected.getId(), defaultMembership);
            newAliases.put(selected.getId(), defaultMembership);
            aliases = new AliasConfigurationSnapshot(definitions, aliases.aliases(),
                new ScanListConfiguration(scans.scanLists(), scans.aliasMemberships(), unmatched, newAliases));
        }
        return new PreparedDiscovery(created, aliases,
            new DiscoveryCreated(created.getConfigurationId(), selected.getId(), newList));
    }

    public record DiscoveryRequest(ChannelDefinition definition, P25SiteIdentity p25Identity,
        TrunkedDiscoveryEvidence evidence, String newAliasListName, boolean autoStart) {}
    public record DiscoverySave(DiscoveryCreated created, RuntimeException failure) {}
    public record DiscoveryBatch(List<DiscoverySave> results,
        ConfigurationManager.ConfigurationPublicationException publicationFailure)
    {
        public DiscoveryBatch { results = List.copyOf(results); }
    }
    private record PreparedDiscovery(Channel channel, AliasConfigurationSnapshot aliases, DiscoveryCreated created) {}

    public record DiscoveryFrequencyMatch(String configurationId, String name, String system, String site, String kind) {}
    public record DiscoveryAliasList(long id, String name, boolean matched) {}
    public record DiscoveryReview(long revision, ChannelDefinition template, List<DiscoveryAliasList> aliasLists,
                                  Long suggestedAliasListId, String defaultNewAliasListName) {}
    public record DiscoveryCreated(String configurationId, long aliasListId, boolean aliasListCreated)
    {
        public DiscoveryCreated(String configurationId, long aliasListId) { this(configurationId, aliasListId, false); }
    }

    /** Shorten only generated suggestions; explicitly supplied names keep the ordinary validation contract. */
    public static String discoveryAliasListName(String system, String fallback)
    {
        String name = system != null && !system.isBlank() ? system.strip() :
            fallback != null && !fallback.isBlank() ? fallback.strip() : "Channels";
        int end = Math.min(name.length(), AliasAdministrationService.MAX_ALIAS_LIST_NAME_LENGTH);
        if(end < name.length() && Character.isHighSurrogate(name.charAt(end - 1)) &&
            Character.isLowSurrogate(name.charAt(end))) end--;
        return name.substring(0, end).stripTrailing();
    }

    public MutationResult update(String configurationId, ChannelDefinition definition, long expectedRevision)
    {
        return update(configurationId, definition, () -> requireRevision(expectedRevision), null, null, true,
            channel -> {});
    }

    public MutationResult update(String configurationId, ChannelDefinition definition, String editRevision,
                                 ChannelDefinition original, boolean allowRestart)
    {
        if(editRevision == null || editRevision.isBlank())
            throw new IllegalArgumentException("Channel edit version is required");
        return update(configurationId, definition, () -> {}, editRevision, original, allowRestart, channel -> {});
    }

    /** Keeps a verified RadioReference rename bound to the same learned P25 serving site through restart. */
    public MutationResult updateP25Names(String configurationId, ChannelDefinition definition, long expectedRevision,
                                         P25SiteIdentity expectedIdentity)
    {
        Objects.requireNonNull(expectedIdentity);
        return update(configurationId, definition, () -> requireRevision(expectedRevision), null, null, true, channel ->
        {
            String protocol = mCodec.fromChannel(channel).protocolId();
            if((!"p25-phase1".equals(protocol) && !"p25-phase2".equals(protocol)) ||
                ChannelConfigurationPolicy.requireChannelKind(channel) != ChannelConfigurationPolicy.ChannelKind.TRUNKED ||
                !expectedIdentity.equals(channel.getP25SiteIdentity()))
                throw new IllegalStateException("The channel identity changed; look up RadioReference names again");
            requireCurrentP25Identity(channel, expectedIdentity);
        });
    }

    private MutationResult update(String configurationId, ChannelDefinition definition, Runnable precondition,
                                   String editRevision, ChannelDefinition originalDefinition, boolean allowRestart,
                                   Consumer<Channel> validation)
    {
        String id = requireConfigurationId(configurationId);
        return admitted(() ->
        {
            Channel original = onConfigurationThread(() ->
            {
                precondition.run();
                Channel existing = requireChannel(id);
                validation.accept(existing);
                // Reject invalid edits and duplicate remote routes before interrupting a running channel.
                ChannelDefinition merged = editRevision != null ? ChannelEdit.merge(id, editRevision,
                    originalDefinition, definition, mCodec.fromChannel(existing)) : definition;
                Channel candidate = mCodec.toChannel(merged, requireAliasList(merged.aliasListId()), existing);
                requireUniqueRemoteSource(candidate, mConfigurationManager.getChannelModel().getChannels());
                return existing;
            });
            boolean restart = isProcessing(id);
            if(restart && !allowRestart) throw new RestartRequiredException();
            boolean stopped = false;
            try
            {
                if(restart && isProcessing(id))
                {
                    mConfigurationManager.getChannelProcessingManager().stop(original);
                    stopped = true;
                }
                MutationResult result = updateStopped(id, definition, precondition, editRevision,
                    originalDefinition, allowRestart, validation);
                if(stopped)
                {
                    Channel replacement = onConfigurationThread(() -> requireChannel(id));
                    mConfigurationManager.getChannelProcessingManager().start(replacement);
                }
                return result;
            }
            catch(RuntimeException | ChannelException exception)
            {
                if(stopped)
                {
                    try
                    {
                        Channel current = onConfigurationThread(() -> requireChannel(id));
                        if(!isProcessing(id)) mConfigurationManager.getChannelProcessingManager().start(current);
                    }
                    catch(RuntimeException | ChannelException recoveryFailure)
                    {
                        exception.addSuppressed(recoveryFailure);
                        throw new LifecycleException("The channel change was saved, but its previous running state " +
                            "could not be restored", exception);
                    }
                }
                if(exception instanceof RuntimeException runtime) throw runtime;
                throw new LifecycleException("Unable to preserve the channel's running state", exception);
            }
        });
    }

    private MutationResult updateStopped(String id, ChannelDefinition definition, Runnable precondition,
                                          String editRevision, ChannelDefinition originalDefinition,
                                          boolean allowRestart, Consumer<Channel> validation)
    {
        return mutate(precondition, false, channels ->
        {
            int index = requireChannelIndex(channels, id);
            Channel existing = channels.get(index);
            validation.accept(existing);
            if(isProcessing(id))
            {
                if(!allowRestart) throw new RestartRequiredException();
                throw new ChannelRunningException(id);
            }
            ChannelDefinition merged = editRevision != null ? ChannelEdit.merge(id, editRevision,
                originalDefinition, definition, mCodec.fromChannel(existing)) : definition;
            ChannelDefinition normalized = new ChannelDefinition(id, merged.protocolId(), merged.system(),
                merged.site(), merged.name(), merged.radioResolveId(), merged.aliasListId(),
                merged.source(), merged.settings(), merged.frequencyMap(), merged.eventLogs(),
                merged.recorders(), merged.auxiliaryDecoders(), merged.observed());
            Channel replacement = mCodec.toChannel(normalized, requireAliasList(merged.aliasListId()), existing);
            channels.set(index, replacement);
            requireUniqueRadioResolveIds(channels);
            requireUniqueRemoteSource(replacement, channels);
            return new MutationTarget(Set.of(id), List.of(id));
        });
    }

    public MutationResult cloneChannels(Collection<String> configurationIds, long expectedRevision)
    {
        List<String> ids = boundedConfigurationIds(configurationIds);
        return admitted(() -> mutate(expectedRevision, false, channels ->
        {
            Map<String,Channel> byId = index(channels);
            List<String> createdIds = new ArrayList<>();
            for(String id: ids)
            {
                Channel source = requireChannel(byId, id);
                Channel clone = source.copyOf();
                clone.setRadioResolveId(null);
                clone.setP25SiteIdentity(null);
                if(clone.isAutoStart())
                {
                    clone.setAutoStartOrder(effectiveAutoStartIds(channels).size() + 1);
                }
                channels.add(clone);
                requireUniqueRemoteSource(clone, channels);
                createdIds.add(clone.getConfigurationId());
            }
            requireUniqueRadioResolveIds(channels);
            return new MutationTarget(Set.copyOf(createdIds), createdIds);
        }));
    }

    public MutationResult deleteChannels(Collection<String> configurationIds, long expectedRevision)
    {
        List<String> ids = boundedConfigurationIds(configurationIds);
        return admitted(() ->
        {
            List<Channel> selected = onConfigurationThread(() ->
            {
                requireRevision(expectedRevision);
                return ids.stream().map(this::requireChannel).toList();
            });
            List<Channel> stopped = new ArrayList<>();
            try
            {
                for(Channel channel: selected)
                {
                    if(isProcessing(channel.getConfigurationId()))
                    {
                        mConfigurationManager.getChannelProcessingManager().stop(channel);
                        stopped.add(channel);
                    }
                }
                return mutate(expectedRevision, false, channels ->
                {
                    for(String id: ids)
                    {
                        int index = requireChannelIndex(channels, id);
                        if(isProcessing(id)) throw new ChannelRunningException(id);
                        channels.remove(index);
                    }
                    return new MutationTarget(Set.copyOf(ids), ids);
                });
            }
            catch(RuntimeException | ChannelException exception)
            {
                for(Channel channel: stopped)
                {
                    try
                    {
                        if(!isProcessing(channel.getConfigurationId()))
                            mConfigurationManager.getChannelProcessingManager().start(channel);
                    }
                    catch(ChannelException recoveryFailure)
                    {
                        exception.addSuppressed(recoveryFailure);
                    }
                }
                if(exception instanceof RuntimeException runtime) throw runtime;
                throw new LifecycleException("Unable to stop channels before deletion", exception);
            }
        });
    }

    /**
     * Removes the selected sender's persisted channels and credentials together. The caller must prevent new
     * remote catalog/open work for that sender, and the callback must only expose the newly committed settings.
     */
    public MutationResult removeRemoteSender(String senderId, RemoteLinkSettingsStore.Settings nextSettings,
                                             Runnable afterCommit, Runnable beforeRollback)
    {
        String id = requireConfigurationId(senderId);
        Objects.requireNonNull(nextSettings);
        Objects.requireNonNull(afterCommit);
        Objects.requireNonNull(beforeRollback);
        return admitted(() ->
        {
            record Selection(long revision, List<Channel> channels) {}
            Selection selected = onConfigurationThread(() -> new Selection(revision(),
                mConfigurationManager.getChannelModel().getChannels().stream()
                    .filter(channel -> channel.getSourceConfiguration() instanceof SourceConfigRemote remote &&
                        id.equals(remote.getSenderId())).toList()));
            List<Channel> stopped = new ArrayList<>();
            AtomicBoolean committed = new AtomicBoolean();
            try
            {
                for(Channel channel: selected.channels())
                {
                    if(isProcessing(channel.getConfigurationId()))
                    {
                        mConfigurationManager.getChannelProcessingManager().stop(channel);
                        stopped.add(channel);
                    }
                }
                return onConfigurationThread(() -> mConfigurationManager.applyConfigurationMutation(() ->
                {
                    requireRevision(selected.revision());
                    List<Channel> channels = detachedChannels(false);
                    List<String> removed = selected.channels().stream().map(Channel::getConfigurationId).toList();
                    for(String channelId: removed)
                    {
                        if(isProcessing(channelId)) throw new ChannelRunningException(channelId);
                        channels.remove(requireChannelIndex(channels, channelId));
                    }
                    try
                    {
                        mConfigurationManager.commitAndPublishRemoteSenderRemoval(
                            new ChannelConfigurationSnapshot(channels), Set.copyOf(removed), id, nextSettings, () ->
                            {
                                committed.set(true);
                                afterCommit.run();
                            });
                    }
                    catch(ConfigurationManager.ConfigurationCommitException exception)
                    {
                        throw new PersistenceException("Unable to remove remote sender", exception);
                    }
                    return new MutationResult(revision(), removed, removed.size());
                }));
            }
            catch(RuntimeException | ChannelException exception)
            {
                // Once SQLite committed, a publication failure must never restart channels that were deleted.
                if(!committed.get())
                {
                    try { beforeRollback.run(); }
                    catch(RuntimeException recoveryFailure) { exception.addSuppressed(recoveryFailure); }
                    for(Channel channel: stopped)
                    {
                        try
                        {
                            if(!isProcessing(channel.getConfigurationId()))
                                mConfigurationManager.getChannelProcessingManager().start(channel);
                        }
                        catch(ChannelException recoveryFailure)
                        {
                            exception.addSuppressed(recoveryFailure);
                        }
                    }
                }
                if(exception instanceof RuntimeException runtime) throw runtime;
                throw new LifecycleException("Unable to stop remote channels before removal", exception);
            }
        });
    }

    public MutationResult moveAutoStart(String configurationId, Direction direction, long expectedRevision)
    {
        String id = requireConfigurationId(configurationId);
        Objects.requireNonNull(direction, "Auto-start direction cannot be null");
        return admitted(() -> mutate(expectedRevision, true, channels ->
        {
            requireChannel(channels, id);
            List<String> enabled = new ArrayList<>(effectiveAutoStartIds(channels));
            int position = enabled.indexOf(id);
            if(direction == Direction.EARLIER)
            {
                if(position < 0) enabled.add(id);
                else if(position > 0) java.util.Collections.swap(enabled, position, position - 1);
            }
            else if(position >= 0 && position < enabled.size() - 1)
            {
                java.util.Collections.swap(enabled, position, position + 1);
            }
            else if(position == enabled.size() - 1)
            {
                enabled.remove(position);
            }

            Set<String> changed = applyAutoStartOrder(channels, enabled);
            return new MutationTarget(changed, List.of(id));
        }));
    }

    /** Enables selected channels at the end of the queue, or disables them and compacts the remaining order. */
    public MutationResult setAutoStart(Collection<String> configurationIds, boolean enabled, long expectedRevision)
    {
        List<String> ids = boundedConfigurationIds(configurationIds);
        return admitted(() -> mutate(expectedRevision, true, channels ->
        {
            ids.forEach(id -> requireChannel(channels, id));
            List<String> autoStartIds = new ArrayList<>(effectiveAutoStartIds(channels));
            Set<String> selected = Set.copyOf(ids);

            if(enabled)
            {
                Set<String> alreadyEnabled = new HashSet<>(autoStartIds);
                ids.forEach(id ->
                {
                    if(alreadyEnabled.add(id)) autoStartIds.add(id);
                });
            }
            else
            {
                autoStartIds.removeIf(selected::contains);
            }

            Set<String> changed = applyAutoStartOrder(channels, autoStartIds);
            return new MutationTarget(changed, ids);
        }));
    }

    public BatchResult setProcessing(Collection<String> configurationIds, boolean start)
    {
        return setProcessing(configurationIds, start, null);
    }

    public LifecycleResult startAtCurrentCenter(String configurationId, DiscoveredTuner selectedTuner)
    {
        return setProcessing(List.of(configurationId), true, Objects.requireNonNull(selectedTuner)).results().getFirst();
    }

    private BatchResult setProcessing(Collection<String> configurationIds, boolean start, DiscoveredTuner selectedTuner)
    {
        List<String> ids = boundedConfigurationIds(configurationIds);
        return admitted(() ->
        {
            Map<String,Channel> channels = onConfigurationThread(() ->
            {
                Map<String,Channel> selected = new LinkedHashMap<>();
                ids.forEach(id -> {
                    if(start && mStatisticsClears.containsKey(id))
                        throw new IllegalStateException("Channel statistics are being cleared; start it after cleanup finishes");
                    selected.put(id, requireChannel(id));
                });
                return selected;
            });
            List<LifecycleResult> results = new ArrayList<>();
            for(String id: ids)
            {
                Channel channel = channels.get(id);
                try
                {
                    if(start)
                    {
                        if(!isProcessing(id))
                        {
                            if(selectedTuner != null)
                                mConfigurationManager.getChannelProcessingManager().startAtCurrentCenter(channel, selectedTuner);
                            else mConfigurationManager.getChannelProcessingManager().start(channel);
                        }
                    }
                    else if(isProcessing(id))
                    {
                        mConfigurationManager.getChannelProcessingManager().stop(channel);
                    }
                    results.add(new LifecycleResult(id, true, processingState(channel), null));
                }
                catch(ChannelException exception)
                {
                    results.add(new LifecycleResult(id, false, processingState(channel), safeMessage(exception)));
                }
            }
            return new BatchResult(revision(), results);
        });
    }

    /** Reserves a stopped channel for an asynchronous cleanup. The configuration remains available for other commands. */
    public void prepareStatisticsMaintenance(StatsDatabaseMaintenanceRequest request)
    {
        String configurationId = request.operation() == ReceiverActivityMaintenance.Operation.CLEAR_CHANNEL_STATS ?
            request.configurationId() : request.deletionTarget() instanceof StatsDatabaseMaintenanceRequest.Channel target ?
                target.configurationId() : null;
        if(configurationId == null) return;
        String id = requireConfigurationId(configurationId);
        admitted(() ->
        {
            onConfigurationThread(() ->
            {
                requireChannel(id);
                if(isProcessing(id)) throw new ChannelRunningException(id);
                StatsDatabaseMaintenanceRequest current = mStatisticsClears.putIfAbsent(id, request);
                if(current != null && current != request)
                    throw new IllegalStateException("Channel statistics cleanup is already running");
                return null;
            });
            request.result().whenComplete((result, failure) -> mStatisticsClears.remove(id, request));
            return null;
        });
    }

    private MutationResult mutate(long expectedRevision, boolean autoStartOnly,
                                  java.util.function.Function<List<Channel>,MutationTarget> operation)
    {
        return mutate(() -> requireRevision(expectedRevision), autoStartOnly, operation);
    }

    private MutationResult mutate(Runnable precondition, boolean autoStartOnly,
                                  java.util.function.Function<List<Channel>,MutationTarget> operation)
    {
        return onConfigurationThread(() -> mConfigurationManager.applyConfigurationMutation(() ->
        {
            precondition.run();
            List<Channel> channels = detachedChannels(autoStartOnly);
            MutationTarget target = operation.apply(channels);
            if(target.changedIds().isEmpty())
            {
                return new MutationResult(revision(), target.resultIds(), 0);
            }
            try
            {
                mConfigurationManager.commitAndPublishChannelConfiguration(
                    new ChannelConfigurationSnapshot(channels), target.changedIds(), autoStartOnly);
            }
            catch(ConfigurationManager.ConfigurationCommitException exception)
            {
                throw new PersistenceException("Unable to save channel configuration", exception);
            }
            return new MutationResult(revision(), target.resultIds(), target.changedIds().size());
        }));
    }

    private List<Channel> detachedChannels(boolean deepCopy)
    {
        List<Channel> copies = new ArrayList<>();
        for(Channel live: mConfigurationManager.getChannelModel().getChannels())
        {
            copies.add(deepCopy ? live.copyOfPreservingIdentity() : live);
        }
        return copies;
    }

    private ChannelSummary summary(Channel channel, Integer autoStartOrder)
    {
        AliasListDefinition aliasList = mConfigurationManager.getAliasModel()
            .getAliasListDefinition(channel.getAliasListId());
        Map<String,Boolean> remoteOrigin = channel.getSourceConfiguration() instanceof SourceConfigRemote ?
            REMOTE_ORIGIN : null;
        try
        {
            ChannelDefinition definition = mCodec.fromChannel(channel);
            String channelKind = ChannelConfigurationPolicy.requireChannelKind(channel).name();
            Long activeFrequency = activeControlFrequency(channel, channelKind);
            Long displayFrequency = activeFrequency != null ? activeFrequency :
                definition.source().frequenciesHz().stream().filter(frequency -> frequency > 0).findFirst().orElse(null);
            return new ChannelSummary(channel.getConfigurationId(), definition.protocolId(),
                mProtocolRegistry.require(definition.protocolId()).label(),
                channelKind, channel.getSystem(), channel.getSite(),
                channel.getName(), definition.source().frequenciesHz(), processingState(channel), autoStartOrder,
                channel.getAliasListId(), aliasList != null ? aliasList.getName() : channel.getAliasListName(), true,
                null, remoteOrigin, displayFrequency, activeFrequency != null ? "active-control" : "configured");
        }
        catch(RuntimeException exception)
        {
            String decoder = channel.getDecodeConfiguration() != null ?
                channel.getDecodeConfiguration().getDecoderType().getDisplayString() : "Unsupported";
            return new ChannelSummary(channel.getConfigurationId(), "unsupported", decoder, "UNSUPPORTED",
                channel.getSystem(), channel.getSite(), channel.getName(), List.copyOf(channel.getFrequencyList()),
                processingState(channel), autoStartOrder, channel.getAliasListId(),
                aliasList != null ? aliasList.getName() : channel.getAliasListName(), false,
                "This compatibility channel can be viewed, started, stopped, reordered, cloned, or deleted, but " +
                    "its decoder configuration cannot be edited on the web.", remoteOrigin,
                channel.getFrequencyList().stream().filter(frequency -> frequency > 0).findFirst().orElse(null),
                "configured");
        }
    }

    private Long activeControlFrequency(Channel channel, String channelKind)
    {
        if(!"TRUNKED".equals(channelKind)) return null;
        //Use the configured parent itself, never a temporary traffic chain sharing its configuration ID.
        var chain = mConfigurationManager.getChannelProcessingManager().getProcessingChain(channel);
        var source = chain != null && chain.isProcessing() ? chain.getSource() : null;
        long frequency = source != null ? source.getFrequency() : 0;
        if(frequency <= 0) return null;
        long now = System.currentTimeMillis();
        for(var module: chain.getModules())
        {
            if(module instanceof ControlChannelQualityMonitor monitor)
            {
                var quality = monitor.getCurrentSnapshot();
                if(isConfirmedActiveControl(channel, frequency, quality, now) &&
                    source.getFrequency() == frequency && monitor.getCurrentSnapshot() == quality)
                {
                    return frequency;
                }
            }
        }
        return null;
    }

    static boolean isConfirmedActiveControl(Channel channel, long frequency, ControlChannelQualitySnapshot quality,
                                             long now)
    {
        return quality != null && quality.active() && quality.channel() == channel && quality.frequencyHz() == frequency &&
            quality.lastValidDecodeMs() > 0 && quality.lastValidDecodeMs() <= now && quality.matchesCurrentChannel() &&
            quality.observedAtMs() <= now &&
            now - quality.observedAtMs() <= CONTROL_QUALITY_FRESHNESS_MILLISECONDS &&
            now - quality.lastValidDecodeMs() <= CONTROL_QUALITY_FRESHNESS_MILLISECONDS;
    }

    private ProcessingState processingState(Channel channel)
    {
        return isProcessing(channel.getConfigurationId()) ? ProcessingState.RUNNING : ProcessingState.STOPPED;
    }

    private boolean isProcessing(String configurationId)
    {
        return !mConfigurationManager.getChannelProcessingManager()
            .getProcessingChainsByConfiguration(configurationId, null).isEmpty();
    }

    static Map<String,Integer> effectiveAutoStartOrder(List<Channel> channels)
    {
        List<String> enabled = effectiveAutoStartIds(channels);
        Map<String,Integer> positions = new HashMap<>();
        for(int index = 0; index < enabled.size(); index++) positions.put(enabled.get(index), index + 1);
        return Map.copyOf(positions);
    }

    static List<String> effectiveAutoStartIds(List<Channel> channels)
    {
        Map<String,Integer> tableOrder = new HashMap<>();
        for(int index = 0; index < channels.size(); index++)
        {
            tableOrder.put(channels.get(index).getConfigurationId(), index);
        }
        return channels.stream().filter(Channel::isAutoStart).sorted(Comparator
            .comparingInt((Channel channel) -> channel.getAutoStartOrder() != null &&
                channel.getAutoStartOrder() > 0 ? channel.getAutoStartOrder() : Integer.MAX_VALUE)
            .thenComparingInt(channel -> tableOrder.get(channel.getConfigurationId()))
            .thenComparing(Channel::getConfigurationId)).map(Channel::getConfigurationId).toList();
    }

    static Set<String> applyAutoStartOrder(List<Channel> channels, List<String> orderedIds)
    {
        Map<String,Integer> assigned = new HashMap<>();
        for(int index = 0; index < orderedIds.size(); index++) assigned.put(orderedIds.get(index), index + 1);
        Set<String> changed = new HashSet<>();
        for(Channel channel: channels)
        {
            Integer order = assigned.get(channel.getConfigurationId());
            boolean enabled = order != null;
            if(channel.getAutoStart() != enabled || !Objects.equals(channel.getAutoStartOrder(), order))
            {
                channel.setAutoStart(enabled);
                channel.setAutoStartOrder(order);
                changed.add(channel.getConfigurationId());
            }
        }
        return Set.copyOf(changed);
    }

    private AliasListDefinition requireAliasList(long aliasListId)
    {
        AliasListDefinition definition = mConfigurationManager.getAliasModel().getAliasListDefinition(aliasListId);
        if(definition == null) throw new NotFoundException("Alias List [" + aliasListId + "] was not found");
        return definition;
    }

    private Channel requireChannel(String configurationId)
    {
        return requireChannel(index(mConfigurationManager.getChannelModel().getChannels()), configurationId);
    }

    private static Channel requireChannel(List<Channel> channels, String configurationId)
    {
        return channels.stream().filter(channel -> configurationId.equals(channel.getConfigurationId()))
            .findFirst().orElseThrow(() -> new NotFoundException("Channel [" + configurationId + "] was not found"));
    }

    private static Channel requireChannel(Map<String,Channel> channels, String configurationId)
    {
        Channel channel = channels.get(configurationId);
        if(channel == null) throw new NotFoundException("Channel [" + configurationId + "] was not found");
        return channel;
    }

    private static int requireChannelIndex(List<Channel> channels, String configurationId)
    {
        for(int index = 0; index < channels.size(); index++)
        {
            if(configurationId.equals(channels.get(index).getConfigurationId())) return index;
        }
        throw new NotFoundException("Channel [" + configurationId + "] was not found");
    }

    private static Map<String,Channel> index(Collection<Channel> channels)
    {
        Map<String,Channel> result = new LinkedHashMap<>();
        channels.forEach(channel -> result.put(channel.getConfigurationId(), channel));
        return result;
    }

    private static void requireUniqueRadioResolveIds(List<Channel> channels)
    {
        Set<String> ids = new HashSet<>();
        for(Channel channel: channels)
        {
            String id = channel.hasRadioResolveId() ? channel.getRadioResolveId() : null;
            if(id != null && !ids.add(id))
            {
                throw new IllegalArgumentException("RadioResolve ID is already assigned to another channel");
            }
        }
    }

    private static void requireUniqueRemoteSource(Channel candidate, List<Channel> channels)
    {
        if(!(candidate.getSourceConfiguration() instanceof SourceConfigRemote remote)) return;

        for(Channel channel: channels)
        {
            if(!Objects.equals(channel.getConfigurationId(), candidate.getConfigurationId()) &&
                channel.getSourceConfiguration() instanceof SourceConfigRemote other &&
                Objects.equals(remote.getSenderId(), other.getSenderId()) &&
                Objects.equals(remote.getFeedId(), other.getFeedId()))
            {
                throw new IllegalArgumentException("Remote channel is already configured for this sender and feed");
            }
        }
    }

    private void requireRevision(long expectedRevision)
    {
        long actual = revision();
        if(expectedRevision != actual) throw new StaleRevisionException(expectedRevision, actual);
    }

    private long revision()
    {
        return mConfigurationManager.getChannelConfigurationRevision() & JSON_SAFE_INTEGER_MASK;
    }

    private static String requireConfigurationId(String value)
    {
        try
        {
            return UUID.fromString(value).toString();
        }
        catch(RuntimeException exception)
        {
            throw new IllegalArgumentException("Channel configuration ID must be a UUID");
        }
    }

    private static List<String> boundedConfigurationIds(Collection<String> values)
    {
        if(values == null || values.isEmpty() || values.size() > MAXIMUM_BULK_CHANNELS)
        {
            throw new IllegalArgumentException("Select between 1 and " + MAXIMUM_BULK_CHANNELS + " channels");
        }
        Set<String> unique = new LinkedHashSet<>();
        values.forEach(value -> unique.add(requireConfigurationId(value)));
        if(unique.size() != values.size()) throw new IllegalArgumentException("Channel selection contains duplicates");
        return List.copyOf(unique);
    }

    private <T> T admitted(Supplier<T> operation)
    {
        if(!mCommandAdmission.tryAcquire()) throw new ConfigurationBusyException();
        try
        {
            return operation.get();
        }
        finally
        {
            mCommandAdmission.release();
        }
    }

    private <T> T onConfigurationThread(Supplier<T> operation)
    {
        requireInitialized();
        try
        {
            return mConfigurationManager.executeConfigurationTask(() ->
            {
                requireInitialized();
                return operation.get();
            });
        }
        catch(io.github.dsheirer.configuration.ConfigurationExecutor.BusyException exception)
        {
            throw new ConfigurationBusyException();
        }
    }

    private void requireInitialized()
    {
        if(!mConfigurationManager.isInitialized()) throw new NotInitializedException();
    }

    private static String safeMessage(Exception exception)
    {
        String message = exception.getMessage();
        return message != null && !message.isBlank() ? message : "Channel lifecycle command failed";
    }

    public enum ProcessingState { STOPPED, RUNNING }
    public enum Direction { EARLIER, LATER }

    public record Catalog(long revision, List<ChannelSummary> channels)
    {
        public Catalog { channels = List.copyOf(channels); }
    }

    public record ChannelSummary(String configurationId, String protocolId, String protocolLabel, String channelKind,
                                 String system, String site, String name, List<Long> frequenciesHz,
                                 ProcessingState processingState, Integer autoStartOrder, long aliasListId,
                                 String aliasListName, boolean editable, String restrictionMessage,
                                 Map<String,Boolean> remoteOrigin, Long displayFrequencyHz,
                                 String displayFrequencySource)
    {
        public ChannelSummary
        {
            frequenciesHz = List.copyOf(frequenciesHz);
            remoteOrigin = remoteOrigin != null && Boolean.TRUE.equals(remoteOrigin.get("remote")) ?
                REMOTE_ORIGIN : null;
        }

        private ChannelSummary withRuntimeState(ProcessingState state, Long activeFrequency)
        {
            Long frequency = activeFrequency != null ? activeFrequency :
                frequenciesHz.stream().filter(value -> value > 0).findFirst().orElse(null);
            return new ChannelSummary(configurationId, protocolId, protocolLabel, channelKind, system, site, name,
                frequenciesHz, state, autoStartOrder, aliasListId, aliasListName, editable, restrictionMessage,
                remoteOrigin, frequency, activeFrequency != null ? "active-control" : "configured");
        }

        private ChannelSummary withDisplayFrequency(long frequency, String source)
        {
            return new ChannelSummary(configurationId, protocolId, protocolLabel, channelKind, system, site, name,
                frequenciesHz, processingState, autoStartOrder, aliasListId, aliasListName, editable,
                restrictionMessage, remoteOrigin, frequency, source);
        }
    }

    public record Entry(long revision, ChannelDefinition channel, ProcessingState processingState,
                        Integer autoStartOrder, String editRevision)
    {
        public Entry(long revision, ChannelDefinition channel, ProcessingState processingState,
                     Integer autoStartOrder)
        {
            this(revision, channel, processingState, autoStartOrder, ChannelEdit.version(channel));
        }
    }
    public record P25EnrichmentSnapshot(Entry entry, Long servingFrequencyHz) {}
    public record AliasListOption(long id, String name, String family) {}
    public record TunerOption(String value, String label) {}
    public record Options(long revision, List<AliasListOption> aliasLists, List<String> tuners,
                          List<TunerOption> tunerOptions)
    {
        public Options(long revision, List<AliasListOption> aliasLists, List<String> tuners)
        {
            this(revision, aliasLists, tuners, tuners.stream().map(value -> new TunerOption(value, value)).toList());
        }

        public Options
        {
            aliasLists = List.copyOf(aliasLists);
            tuners = List.copyOf(tuners);
            tunerOptions = List.copyOf(tunerOptions);
        }
    }
    public record MutationResult(long revision, List<String> configurationIds, int affected)
    {
        public MutationResult { configurationIds = List.copyOf(configurationIds); }
    }
    public record LifecycleResult(String configurationId, boolean success, ProcessingState state, String message) {}
    public record BatchResult(long revision, List<LifecycleResult> results)
    {
        public BatchResult { results = List.copyOf(results); }
    }
    public record SquelchPreviewResult(String leaseId, long expiresAtEpochMs) {}

    private static final class SquelchPreviewLease
    {
        private final String mId;
        private final NBFMDecoder mDecoder;
        private final NoiseSquelchState mOriginal;
        private ScheduledFuture<?> mExpiry;

        private SquelchPreviewLease(String id, NBFMDecoder decoder, NoiseSquelchState original)
        {
            mId = id;
            mDecoder = decoder;
            mOriginal = original;
        }

        private String id()
        {
            return mId;
        }

        private NBFMDecoder decoder()
        {
            return mDecoder;
        }

        private void renew(ScheduledFuture<?> expiry)
        {
            if(mExpiry != null)
            {
                mExpiry.cancel(false);
            }

            mExpiry = expiry;
        }

        private void restore()
        {
            if(mExpiry != null)
            {
                mExpiry.cancel(false);
                mExpiry = null;
            }

            mDecoder.previewSquelch(mOriginal.noiseOpenThreshold(), mOriginal.noiseCloseThreshold(),
                mOriginal.hysteresisOpenThreshold(), mOriginal.hysteresisCloseThreshold());
        }
    }

    private record MutationTarget(Set<String> changedIds, List<String> resultIds)
    {
        private MutationTarget
        {
            changedIds = Set.copyOf(changedIds);
            resultIds = List.copyOf(resultIds);
        }
    }

    public static class NotFoundException extends RuntimeException
    {
        public NotFoundException(String message) { super(message); }
    }
    public static class StaleRevisionException extends RuntimeException
    {
        public StaleRevisionException(long expected, long actual)
        {
            super("Expected channel revision " + expected + " but found " + actual);
        }
    }
    public static class StaleChannelException extends RuntimeException {}
    public static class RestartRequiredException extends RuntimeException {}
    public static class ChannelRunningException extends RuntimeException
    {
        public ChannelRunningException(String id) { super("Channel [" + id + "] is running"); }
    }
    public static class ConfigurationBusyException extends RuntimeException {}
    public static class NotInitializedException extends RuntimeException {}
    public static class PersistenceException extends RuntimeException
    {
        public PersistenceException(String message, Throwable cause) { super(message, cause); }
    }
    public static class LifecycleException extends RuntimeException
    {
        public LifecycleException(String message, Throwable cause) { super(message, cause); }
    }
}
