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
package io.github.dsheirer.service.radioreference;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasAdministrationService;
import io.github.dsheirer.alias.AliasImportService;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.AliasMatchRegistry;
import io.github.dsheirer.channel.ChannelAdministrationService;
import io.github.dsheirer.channel.ChannelDefinition;
import io.github.dsheirer.channel.ChannelProtocolRegistry;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.service.radioreference.RadioReferenceDirectoryService.BoundedPage;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.ConventionalFrequency;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.RemoteTalkgroup;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.RemoteTalkgroupCategory;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.TrunkedSiteChannel;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.TrunkedSiteDetails;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.TrunkedSystemDetails;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * UI-neutral RadioReference import preview and apply boundary.
 *
 * <p>Preview data is loaded from RadioReference once and retained briefly in memory. Apply consumes that exact
 * server-held plan and delegates persistence, revision checks, and running-channel restart behavior to the existing
 * channel and Alias administration services. Nothing from a browser is treated as an authoritative frequency,
 * decoder, talkgroup, or encryption value.</p>
 */
public final class RadioReferenceImportService
{
    private static final Logger mLog = LoggerFactory.getLogger(RadioReferenceImportService.class);
    private static final Duration DEFAULT_PREVIEW_LIFETIME = Duration.ofMinutes(5);
    private static final int MAXIMUM_PENDING_PREVIEWS = 16;
    private static final int MAXIMUM_TALKGROUP_CATALOGS = 16;
    private static final int MAXIMUM_TALKGROUP_PREVIEW_ROWS = 500;
    private static final long NAMING_LOOKUP_NANOS = Duration.ofSeconds(25).toNanos();

    private final DirectoryAccess mDirectory;
    private final ChannelAdministrationService mChannels;
    private final AliasAdministrationService mAliases;
    private final AliasImportService mAliasImporter;
    private final Clock mClock;
    private final long mPreviewLifetimeMillis;
    private final LongSupplier mNamingNanoTime;
    private final Map<String,PendingChannel> mPendingChannels = new HashMap<>();
    private final Map<String,PendingTalkgroups> mPendingTalkgroups = new HashMap<>();
    private final Map<String,PendingEnrichment> mPendingEnrichments = new HashMap<>();
    private final Map<String,PendingNameLookup> mPendingNameLookups = new HashMap<>();
    private final Map<String,RemoteCatalog> mTalkgroupCatalogs = new LinkedHashMap<>();
    private final Map<Integer,RemoteCatalog> mSystemTalkgroupCatalogs = new LinkedHashMap<>();
    private final Map<Integer,TrunkedSystemDetails> mSystemDetails = new LinkedHashMap<>();
    private final Map<Integer,List<TrunkedSiteDetails>> mSiteCatalogs = new LinkedHashMap<>();
    private final Map<Integer,CompletableFuture<RemoteCatalog>> mTalkgroupLoads = new HashMap<>();
    private final Map<Integer,CompletableFuture<TrunkedSystemDetails>> mSystemLoads = new HashMap<>();
    private final Map<Integer,CompletableFuture<List<TrunkedSiteDetails>>> mSiteLoads = new HashMap<>();
    private long mSessionGeneration;

    public RadioReferenceImportService(RadioReferenceDirectoryService directory,
                                       ConfigurationManager configurationManager)
    {
        this(new DirectoryAdapter(directory), configurationManager.getChannelAdministrationService(),
            configurationManager.getAliasAdministrationService(), Clock.systemUTC(), DEFAULT_PREVIEW_LIFETIME);
    }

    RadioReferenceImportService(DirectoryAccess directory, ChannelAdministrationService channels,
                                AliasAdministrationService aliases, Clock clock, Duration previewLifetime)
    { this(directory, channels, aliases, clock, previewLifetime, System::nanoTime); }

    RadioReferenceImportService(DirectoryAccess directory, ChannelAdministrationService channels,
        AliasAdministrationService aliases, Clock clock, Duration previewLifetime, LongSupplier namingNanoTime)
    {
        mNamingNanoTime = Objects.requireNonNull(namingNanoTime);
        mDirectory = Objects.requireNonNull(directory);
        mChannels = Objects.requireNonNull(channels);
        mAliases = Objects.requireNonNull(aliases);
        mAliasImporter = new AliasImportService(aliases);
        mClock = Objects.requireNonNull(clock);
        Duration lifetime = Objects.requireNonNull(previewLifetime);
        if(lifetime.isZero() || lifetime.isNegative())
        {
            throw new IllegalArgumentException("Preview lifetime must be positive");
        }
        mPreviewLifetimeMillis = lifetime.toMillis();
    }

    /** Builds one create-or-refresh plan for a trunked site. */
    public ChannelPreview previewSite(SiteImportRequest request) throws RadioReferenceDirectoryException
    {
        Objects.requireNonNull(request, "Site import request cannot be null");
        int systemId = positive(request.systemId(), "system_id");
        int siteId = positive(request.siteId(), "site_id");
        TrunkedSystemDetails system = requireSystem(systemId);
        TrunkedSiteDetails site = requireSite(systemId, siteId);
        DecoderPlan decoder = siteDecoder(system, site);
        if(!decoder.supported())
        {
            throw new IllegalArgumentException(decoder.reason());
        }

        FrequencySet frequencySet = Objects.requireNonNull(request.frequencySet(),
            "Frequency selection is required");
        if((normalized(system.flavor()).contains("capacity plus") ||
            normalized(system.flavor()).contains("conventional networked")) &&
            (frequencySet == FrequencySet.CONTROL || frequencySet == FrequencySet.CONTROL_AND_ALTERNATES))
        {
            throw new IllegalArgumentException("This system has no dedicated control channel; choose All or Selected");
        }
        List<Long> frequencies = selectSiteFrequencies(site, frequencySet, request.selectedFrequenciesHz());
        List<Long> allFrequencies = allSiteFrequencies(site);
        long minimum = allFrequencies.stream().mapToLong(Long::longValue).min().orElseThrow();
        long maximum = allFrequencies.stream().mapToLong(Long::longValue).max().orElseThrow();
        String systemName = fallback(request.systemName(), system.name());
        String siteName = fallback(request.siteName(), site.name());
        String channelName = fallback(request.channelName(), RadioReferenceChannelNames.trunked(systemName, siteName, frequencies.getFirst()).name());
        LocalChannelSnapshot local = localSnapshot();
        long requestedAliasListId = requireCompatibleAliasList(request.aliasListId(), decoder.protocolId(),
            local.options());
        ChannelDefinition created = siteDefinition(decoder, system, site, systemName, siteName, channelName,
            requestedAliasListId, frequencies, minimum, maximum);
        ExistingChannel existing = matchingChannel(local.catalog(), created);
        ChannelDefinition candidate = created;
        List<Long> previous = List.of();
        ChannelAction action = ChannelAction.CREATE;
        String existingId = null;

        if(existing != null)
        {
            ChannelAdministrationService.Entry entry = mChannels.get(existing.configurationId());
            requireSameRevision(local.catalog().revision(), entry.revision());
            previous = entry.channel().source().frequenciesHz();
            candidate = sourceOnlyReplacement(entry.channel(), frequencies, minimum, maximum);
            action = ChannelAction.UPDATE;
            existingId = existing.configurationId();
        }

        requireSameRevision(local.catalog().revision(), mChannels.currentRevision());
        String modulation = decoder.decoderType() == DecoderType.P25_PHASE1 ?
            detectP25Modulation(site.name(), null, site.modulation()) : null;
        return storeChannel(local.catalog().revision(), action, existingId, candidate, modulation, previous);
    }

    /** Builds one create-or-refresh plan for one conventional frequency. */
    public ChannelPreview previewConventional(ConventionalImportRequest request)
        throws RadioReferenceDirectoryException
    {
        Objects.requireNonNull(request, "Conventional import request cannot be null");
        int subCategoryId = positive(request.subCategoryId(), "sub_category_id");
        int frequencyId = positive(request.frequencyId(), "frequency_id");
        List<ConventionalFrequency> rows = mDirectory.conventionalFrequenciesById(subCategoryId,
            List.of(frequencyId));
        if(rows.size() != 1 || rows.getFirst().id() != frequencyId)
        {
            throw new IllegalArgumentException("The selected RadioReference frequency is unavailable");
        }

        ConventionalFrequency frequency = rows.getFirst();
        RadioReferenceChannelNames names = mDirectory.conventionalNames(request.ownerKind(), request.ownerId(),
            subCategoryId, frequency);
        String protocolId = conventionalProtocol(frequency.mode());
        LocalChannelSnapshot local = localSnapshot();
        long aliasListId = request.aliasListId() == null ? defaultAliasList(protocolId, local.options()) :
            requireCompatibleAliasList(request.aliasListId(), protocolId, local.options());
        ChannelDefinition template = mChannels.template(protocolId);
        Map<String,Object> settings = new LinkedHashMap<>(template.settings());
        applyConventionalSettings(protocolId, frequency.mode(), settings);
        String name = fallback(request.channelName(), names.name());
        ChannelDefinition created = new ChannelDefinition(null, protocolId,
            textOrNull(fallback(request.systemName(), names.system())),
            textOrNull(fallback(request.siteName(), names.site())), name, null, aliasListId,
            new ChannelDefinition.Source(List.of(frequency.downlinkHz()), null, null, null, null, null), settings,
            List.of(), template.eventLogs(), template.recorders(), template.auxiliaryDecoders(),
            ChannelDefinition.Observed.EMPTY);
        ExistingChannel existing = matchingChannel(local.catalog(), created);
        ChannelDefinition candidate = created;
        List<Long> previous = List.of();
        ChannelAction action = ChannelAction.CREATE;
        String existingId = null;

        if(existing != null)
        {
            ChannelAdministrationService.Entry entry = mChannels.get(existing.configurationId());
            requireSameRevision(local.catalog().revision(), entry.revision());
            previous = entry.channel().source().frequenciesHz();
            candidate = sourceOnlyReplacement(entry.channel(), List.of(frequency.downlinkHz()), null, null);
            action = ChannelAction.UPDATE;
            existingId = existing.configurationId();
        }

        requireSameRevision(local.catalog().revision(), mChannels.currentRevision());
        return storeChannel(local.catalog().revision(), action, existingId, candidate, null, previous);
    }

    /** Consumes one channel preview. ChannelAdministrationService owns atomic persistence and lifecycle recovery. */
    public ChannelImportResult applyChannel(String previewId)
    {
        PendingChannel pending = consumeChannel(previewId);
        ChannelAdministrationService.MutationResult result = pending.action() == ChannelAction.CREATE ?
            mChannels.create(pending.channel()) :
            mChannels.update(pending.existingConfigurationId(), pending.channel(), pending.revision());
        return new ChannelImportResult(result.revision(), pending.action(),
            result.configurationIds().getFirst());
    }

    /** Names-only lookup for saved channels and unsaved editor drafts. Record choices remain server-owned. */
    public EnrichmentPreview previewChannelNames(String configurationId, Long expectedRevision,
        ChannelDefinition draft, Integer stateId, String lookupId, String candidateId)
        throws RadioReferenceDirectoryException
    {
        ChannelAdministrationService.Entry entry = null;
        if(configurationId != null && !configurationId.isBlank())
        {
            entry = mChannels.get(configurationId);
            if(expectedRevision == null) throw new IllegalArgumentException("Channel revision is required");
            requireSameRevision(expectedRevision, entry.revision());
        }
        if(draft == null && entry == null) throw new IllegalArgumentException("Choose a channel to look up");
        ChannelDefinition channel = draft != null ? draft : entry.channel();
        ChannelProtocolRegistry.Profile profile = mChannels.protocolRegistry().require(channel.protocolId());
        boolean conventional = profile.channelKind() == io.github.dsheirer.configuration.ChannelConfigurationPolicy.ChannelKind.CONVENTIONAL ||
            "CONVENTIONAL".equals(channel.settings().getOrDefault("channel_mode", profile.defaultSettings().get("channel_mode")));
        long frequency = namingFrequency(channel);
        long revision = entry != null ? entry.revision() : mChannels.currentRevision();
        boolean running = entry != null && entry.processingState() == ChannelAdministrationService.ProcessingState.RUNNING;
        String savedId = entry != null ? entry.channel().configurationId() : null;
        requireCatalogSession();
        long generation;
        synchronized(this) { generation = mSessionGeneration; }
        long directoryGeneration = mDirectory.sessionGeneration();
        if(lookupId != null && !lookupId.isBlank())
        {
            PendingNameLookup lookup;
            synchronized(this)
            {
                cleanupExpired(mClock.millis());
                lookup = mPendingNameLookups.get(lookupId);
                if(lookup == null || lookup.generation() != generation || lookup.directoryGeneration() != directoryGeneration)
                    throw new PreviewNotFoundException();
            }
            if(!Objects.equals(savedId, lookup.configurationId()) || revision != lookup.revision() ||
                !sameNamingSource(channel, lookup.channel()))
                throw new IllegalStateException("The channel changed; look up RadioReference names again");
            EnrichmentCandidate selected = lookup.candidates().stream()
                .filter(candidate -> Objects.equals(candidate.candidateId(), candidateId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Choose a RadioReference record from this lookup"));
            synchronized(this)
            {
                requireCurrentNamesSession(generation, directoryGeneration);
                return storeNamesPreview(channel, savedId, revision, running,
                    new RadioReferenceChannelNames(selected.system(), selected.site(), selected.name()), selected.sourceUrl(), null,
                    lookupId, lookup.candidates(), lookup.identity(), generation, directoryGeneration).withSystemId(selected.rrSystemId());
            }
        }
        if(candidateId != null) throw new IllegalArgumentException("Look up RadioReference records before choosing one");
        // Only saved receiver identity can verify a match. Draft observed fields are never identity proof.
        P25SiteIdentity requiredIdentity = null;
        if(entry != null && p25Trunked(entry.channel()) && enrichmentIdentity(entry.channel()) != null &&
            sameNamingSource(channel, entry.channel()))
        {
            EnrichmentPreview verified = previewChannelEnrichment(savedId, revision, stateId);
            if("ambiguous".equals(verified.state()))
            {
                // Offer only records that independently match the same trusted identity; the user chooses the label.
                requiredIdentity = enrichmentIdentity(entry.channel());
            }
            else
            {
                if(draft == null || !("matched".equals(verified.state()) || "no_changes".equals(verified.state())))
                    return verified;
                synchronized(this)
                {
                    requireCurrentNamesSession(generation, directoryGeneration);
                    if(verified.previewId() != null && mPendingEnrichments.remove(verified.previewId()) == null)
                        throw new PreviewNotFoundException();
                    return storeNamesPreview(channel, savedId, revision, running, verified.names(), verified.systemUrl(),
                        verified.siteUrl(), null, List.of(), enrichmentIdentity(entry.channel()), generation, directoryGeneration)
                        .withSystemId(verified.rrSystemId());
                }
            }
        }
        if(stateId == null || stateId <= 0)
            return namesResult(savedId, revision, running, "location_required",
                "Choose a state or province in RadioReference settings, then look up this channel again.", null, List.of());
        RadioReferenceDiscoveryResolver.Identity requiredDirectoryIdentity = requiredIdentity != null ?
            new RadioReferenceDiscoveryResolver.Identity(channel.protocolId(), "", frequency, requiredIdentity.wacn(),
                requiredIdentity.system(), requiredIdentity.rfss(), requiredIdentity.site(), null, null) : null;
        long namingDeadline = mNamingNanoTime.getAsLong() + NAMING_LOOKUP_NANOS;
        var page = mDirectory.searchStateFrequencies(stateId, frequency, 0, 32, namingBudget(namingDeadline));
        namingBudget(namingDeadline);
        if(page.totalItems() > 32)
            return namesResult(savedId, revision, running, "ambiguous",
                "Too many RadioReference records use this frequency. Open RadioReference to choose a record.", null, List.of());
        Map<String,EnrichmentCandidate> candidates = new LinkedHashMap<>();
        Map<Integer,TrunkedSystemDetails> systems = new HashMap<>();
        Map<Integer,List<TrunkedSiteDetails>> sites = new HashMap<>();
        Map<String,RadioReferenceDirectoryService.FrequencyDetails> conventionalDetails = new HashMap<>();
        for(var row: page.items())
        {
            if(Math.round(row.outputMhz() * 1_000_000.0) != frequency) continue;
            if(row.matchType() == RadioReferenceDirectoryService.MatchType.CONVENTIONAL)
            {
                if(!conventional) continue;
                String detailKey = row.subCategoryId() + ":" + row.agencyId() + ":" + row.countyId() + ":" + row.modeCode();
                var details = conventionalDetails.get(detailKey);
                if(details == null)
                {
                    details = mDirectory.frequencyDetails(frequency, null, 0, row.subCategoryId(), row.agencyId(),
                        row.countyId(), row.modeCode(), namingBudget(namingDeadline));
                    namingBudget(namingDeadline);
                    conventionalDetails.put(detailKey, details);
                }
                String mode = firstNonBlank(details.modeName(), row.modeName(), row.modeCode());
                String remoteProtocol;
                try { remoteProtocol = conventionalProtocol(mode); }
                catch(IllegalArgumentException unsupported) { continue; }
                if(!channel.protocolId().equals(remoteProtocol) || remoteProtocol.equals("nxdn") &&
                    !Objects.equals(channel.settings().getOrDefault("transmission_mode", "M4800"),
                        normalized(mode).contains("96") ? "M9600" : "M4800")) continue;
                var owner = details.conventionalContext();
                if(owner == null || details.subCategory().isBlank() && details.category().isBlank())
                    return namesResult(savedId, revision, running, "unavailable",
                        "RadioReference category names are unavailable. Try this lookup again.", null, List.of());
                String countyName = owner.countyName();
                String agencyName = owner.agencyName();
                String stateName = owner.stateName();
                var suggested = RadioReferenceChannelNames.conventional(countyName, agencyName, stateName,
                    details.category(), details.subCategory(), row.description(), row.alphaTag(), frequency);
                String system = suggested.system();
                String site = suggested.site();
                String name = suggested.name();
                String context = java.util.stream.Stream.of(countyName, agencyName, details.category(),
                    details.subCategory(), row.alphaTag(), mode, row.tone(), row.callsign()).filter(value -> value != null && !value.isBlank())
                    .distinct().collect(java.util.stream.Collectors.joining(" · "));
                String key = "conventional:" + row.subCategoryId() + ':' + system + ':' + site + ':' + name;
                candidates.putIfAbsent(key, new EnrichmentCandidate(UUID.randomUUID().toString(), system, site, name,
                    remoteProtocol, frequency, context, row.radioReferenceUrl(), null, null));
            }
            else if(row.systemId() != null && row.systemId() > 0)
            {
                int systemId = row.systemId();
                TrunkedSystemDetails system = systems.get(systemId);
                if(system == null)
                {
                    system = mDirectory.trunkedSystemDetails(systemId, namingBudget(namingDeadline));
                    namingBudget(namingDeadline);
                    systems.put(systemId, system);
                }
                boolean remoteConventional = normalized(system.flavor()).contains("conventional networked");
                if(conventional != remoteConventional) continue;
                List<TrunkedSiteDetails> siteRows = sites.get(systemId);
                if(siteRows == null)
                {
                    siteRows = mDirectory.allTrunkedSites(systemId, namingBudget(namingDeadline));
                    namingBudget(namingDeadline);
                    sites.put(systemId, siteRows);
                }
                for(var site: siteRows)
                {
                    if(site.channels().stream().noneMatch(value -> value.frequencyHz() == frequency) ||
                        row.siteNumber() != null && row.siteNumber() != site.number()) continue;
                    if(requiredDirectoryIdentity != null && !RadioReferenceDiscoveryResolver.match(requiredDirectoryIdentity,
                        List.of(new RadioReferenceDirectoryService.DiscoverySystem(system, List.of(site)))).matched()) continue;
                    DecoderPlan decoder = siteDecoder(system, site);
                    if(!decoder.supported() || !channel.protocolId().equals(decoder.protocolId()) ||
                        decoder.protocolId().equals("nxdn") && !Objects.equals(channel.settings().getOrDefault(
                            "transmission_mode", "M4800"), nxdnTransmissionMode(system.flavor()))) continue;
                    String systemName = text(system.name());
                    String siteName = text(site.name());
                    String name = RadioReferenceChannelNames.trunked(systemName, siteName, frequency).name();
                    String key = "trunked:" + systemId + ':' + site.id();
                    candidates.putIfAbsent(key, new EnrichmentCandidate(UUID.randomUUID().toString(), systemName,
                        siteName, name, decoder.protocolId(), frequency, firstNonBlank(row.countyName(), row.stateName()),
                        "https://www.radioreference.com/db/site/" + site.id(), systemId, null));
                }
            }
        }
        synchronized(this)
        {
            namingBudget(namingDeadline);
            requireCurrentNamesSession(generation, directoryGeneration);
            if(entry != null) requireSameRevision(revision, mChannels.get(savedId).revision());
            if(candidates.isEmpty()) return namesResult(savedId, revision, running, "no_match",
                "No RadioReference record matches this frequency and radio type in the selected region.", null, List.of());
            cleanupExpired(mClock.millis());
            requirePreviewCapacity();
            String id = UUID.randomUUID().toString();
            var choices = List.copyOf(candidates.values());
            mPendingNameLookups.put(id, new PendingNameLookup(savedId, revision, channel, choices, requiredIdentity,
                generation, directoryGeneration, mClock.millis() + mPreviewLifetimeMillis));
            return namesResult(savedId, revision, running, "choose_record",
                "Choose the RadioReference record to use for this channel.", id, choices);
        }
    }

    /** One total budget includes state search, queue waits, and every detail request; never return partial choices. */
    private Duration namingBudget(long deadline) throws RadioReferenceDirectoryException
    {
        long remaining = deadline - mNamingNanoTime.getAsLong();
        if(remaining <= 0) throw new RadioReferenceDirectoryException(RadioReferenceDirectoryException.Code.TIMEOUT);
        return Duration.ofNanos(remaining);
    }

    private synchronized void requireCurrentNamesSession(long generation, long directoryGeneration)
        throws RadioReferenceDirectoryException
    {
        requireCatalogSession();
        if(generation != mSessionGeneration || directoryGeneration != mDirectory.sessionGeneration())
            throw new PreviewNotFoundException();
    }

    private static long namingFrequency(ChannelDefinition channel)
    {
        if(channel.source().frequenciesHz().isEmpty() || channel.source().frequenciesHz().size() > 256)
            throw new IllegalArgumentException("Enter a frequency before looking up RadioReference names");
        Long preferred = channel.source().preferredFrequencyHz();
        Long frequency = preferred != null && channel.source().frequenciesHz().contains(preferred) ? preferred :
            channel.source().frequenciesHz().getFirst();
        if(frequency == null || frequency <= 0 || frequency > 100_000_000_000L)
            throw new IllegalArgumentException("Enter a valid frequency before looking up RadioReference names");
        return frequency;
    }

    private static boolean sameNamingSource(ChannelDefinition first, ChannelDefinition second)
    {
        return first.protocolId().equals(second.protocolId()) && first.source().equals(second.source()) &&
            Objects.equals(first.settings().get("channel_mode"), second.settings().get("channel_mode")) &&
            Objects.equals(first.settings().get("transmission_mode"), second.settings().get("transmission_mode"));
    }

    private EnrichmentPreview storeNamesPreview(ChannelDefinition channel, String configurationId, long revision,
        boolean running, RadioReferenceChannelNames names, String systemUrl, String siteUrl, String lookupId,
        List<EnrichmentCandidate> candidates, P25SiteIdentity identity, long generation, long directoryGeneration)
    {
        String system = enrichmentName(names.system(), channel.system());
        String site = enrichmentName(names.site(), channel.site());
        String name = enrichmentName(names.name(), channel.name());
        List<EnrichmentChange> changes = new ArrayList<>();
        addEnrichmentChange(changes, "system", channel.system(), system);
        addEnrichmentChange(changes, "site", channel.site(), site);
        addEnrichmentChange(changes, "name", channel.name(), name);
        String previewId = null;
        if(!changes.isEmpty())
        {
            cleanupExpired(mClock.millis());
            requirePreviewCapacity();
            previewId = UUID.randomUUID().toString();
            mPendingEnrichments.put(previewId, new PendingEnrichment(revision, configurationId, identity, system,
                site, name, channel, generation, directoryGeneration, mClock.millis() + mPreviewLifetimeMillis));
        }
        return new EnrichmentPreview(previewId, changes.isEmpty() ? "no_changes" : "matched",
            changes.isEmpty() ? "This channel already has the available RadioReference names." : "Review the names before saving.",
            configurationId, revision, changes, systemUrl, siteUrl, running, identity, lookupId, candidates,
            new RadioReferenceChannelNames(system, site, name));
    }

    private static EnrichmentPreview namesResult(String configurationId, long revision, boolean running,
        String state, String reason, String lookupId, List<EnrichmentCandidate> candidates)
    {
        return new EnrichmentPreview(null, state, reason, configurationId, revision, List.of(), null, null,
            running, null, lookupId, candidates, null);
    }

    public EnrichmentPreview previewChannelEnrichment(String configurationId, long expectedRevision, Integer stateId)
        throws RadioReferenceDirectoryException
    {
        ChannelAdministrationService.P25EnrichmentSnapshot snapshot = mChannels.p25EnrichmentSnapshot(configurationId);
        ChannelAdministrationService.Entry entry = snapshot.entry();
        requireSameRevision(expectedRevision, entry.revision());
        ChannelDefinition channel = entry.channel();
        P25SiteIdentity identity = enrichmentIdentity(channel);
        if(!p25Trunked(channel))
            return enrichmentResult(entry, null, "unsupported_protocol",
                "RadioReference name lookup is available for P25 trunked channels.", List.of(), null, null, null);
        if(identity == null)
            return enrichmentResult(entry, null, "identity_required",
                "Run this channel until its WACN, system, RFSS, and site have been learned, then try again.",
                List.of(), null, null, null);
        Long frequency = snapshot.servingFrequencyHz();
        if(frequency == null)
        {
            frequency = channel.source().preferredFrequencyHz();
            if(frequency == null && !channel.source().frequenciesHz().isEmpty())
                frequency = channel.source().frequenciesHz().getFirst();
        }
        if(frequency == null || frequency <= 0)
            return enrichmentResult(entry, null, "identity_required",
                "This channel needs a control frequency before RadioReference can verify its site.",
                List.of(), null, null, identity);

        requireCatalogSession();
        long generation;
        synchronized(this) { generation = mSessionGeneration; }
        long directoryGeneration = mDirectory.sessionGeneration();
        RadioReferenceDiscoveryResolver.Result result = mDirectory.resolveP25Identity(stateId,
            new RadioReferenceDiscoveryResolver.Identity(channel.protocolId(), "", frequency, identity.wacn(),
                identity.system(), identity.rfss(), identity.site(), null, null));
        synchronized(this)
        {
            requireCatalogSession();
            if(generation != mSessionGeneration || directoryGeneration != mDirectory.sessionGeneration())
                throw new PreviewNotFoundException();
            ChannelAdministrationService.Entry current = mChannels.p25EnrichmentSnapshot(configurationId).entry();
            requireSameRevision(entry.revision(), current.revision());
            if(!Objects.equals(identity, enrichmentIdentity(current.channel())))
                throw new IllegalStateException("The channel identity changed; look up RadioReference names again");
            if(!result.matched())
                return enrichmentResult(entry, null, result.state(), result.message(), List.of(), null, null, identity);

            RadioReferenceDiscoveryResolver.Match match = result.match();
            String system = enrichmentName(match.systemName(), channel.system());
            String site = enrichmentName(match.siteName(), channel.site());
            String name = channel.name();
            if(!text(match.systemName()).isEmpty() && !text(match.siteName()).isEmpty())
            {
                String proposed = RadioReferenceChannelNames.trunked(match.systemName(), match.siteName(), frequency).name();
                if(proposed.length() <= 256) name = proposed;
            }
            List<EnrichmentChange> changes = new ArrayList<>();
            addEnrichmentChange(changes, "system", channel.system(), system);
            addEnrichmentChange(changes, "site", channel.site(), site);
            addEnrichmentChange(changes, "name", channel.name(), name);
            if(changes.isEmpty())
                return enrichmentResult(entry, null, "no_changes", "This channel already has the available RadioReference names.",
                    changes, match.url(), match.siteUrl(), identity).withSystemId(match.rrSystemId());
            long now = mClock.millis();
            cleanupExpired(now);
            requirePreviewCapacity();
            String previewId = UUID.randomUUID().toString();
            mPendingEnrichments.put(previewId, new PendingEnrichment(entry.revision(), configurationId, identity,
                system, site, name, channel, generation, directoryGeneration, now + mPreviewLifetimeMillis));
            return enrichmentResult(entry, previewId, "matched", "Review the names before saving.", changes,
                match.url(), match.siteUrl(), identity).withSystemId(match.rrSystemId());
        }
    }

    /** Consumes the exact reviewed names without another RadioReference lookup. */
    public synchronized EnrichmentResult applyChannelEnrichment(String previewId, boolean replaceName)
        throws RadioReferenceDirectoryException
    {
        requireCatalogSession();
        cleanupExpired(mClock.millis());
        PendingEnrichment pending = mPendingEnrichments.remove(requirePreviewId(previewId));
        if(pending == null || pending.generation() != mSessionGeneration ||
            pending.directoryGeneration() != mDirectory.sessionGeneration()) throw new PreviewNotFoundException();
        if(pending.configurationId() == null)
            throw new IllegalArgumentException("Apply these names in the channel editor before saving");
        ChannelAdministrationService.Entry entry = pending.identity() != null ?
            mChannels.p25EnrichmentSnapshot(pending.configurationId()).entry() : mChannels.get(pending.configurationId());
        requireSameRevision(pending.revision(), entry.revision());
        ChannelDefinition current = entry.channel();
        if(!sameNamingSource(current, pending.channel()) || pending.identity() != null &&
            (!p25Trunked(current) || !pending.identity().equals(enrichmentIdentity(current))))
            throw new IllegalStateException("The channel identity changed; look up RadioReference names again");
        String name = replaceName ? pending.name() : current.name();
        if(Objects.equals(current.system(), pending.system()) && Objects.equals(current.site(), pending.site()) &&
            Objects.equals(current.name(), name)) return new EnrichmentResult(pending.configurationId(), entry.revision());
        ChannelDefinition replacement = new ChannelDefinition(current.configurationId(), current.protocolId(),
            pending.system(), pending.site(), name, current.radioResolveId(), current.aliasListId(), current.source(),
            current.settings(), current.frequencyMap(), current.eventLogs(), current.recorders(),
            current.auxiliaryDecoders(), current.observed());
        ChannelAdministrationService.MutationResult mutation = pending.identity() != null ?
            mChannels.updateP25Names(pending.configurationId(), replacement, entry.revision(), pending.identity()) :
            mChannels.update(pending.configurationId(), replacement, entry.revision());
        return new EnrichmentResult(pending.configurationId(), mutation.revision());
    }

    private static boolean p25Trunked(ChannelDefinition channel)
    {
        return "p25-phase1".equals(channel.protocolId()) || "p25-phase2".equals(channel.protocolId());
    }

    private static P25SiteIdentity enrichmentIdentity(ChannelDefinition channel)
    {
        if(!p25Trunked(channel)) return null;
        Map<String,Object> identity = channel.observed().p25SiteIdentity();
        try
        {
            return new P25SiteIdentity(identityInteger(identity, "wacn"), identityInteger(identity, "system"),
                identityInteger(identity, "rfss"), identityInteger(identity, "site"));
        }
        catch(IllegalArgumentException exception) { return null; }
    }

    private static int identityInteger(Map<String,Object> identity, String field)
    {
        Object value = identity.get(field);
        if(value instanceof Integer number) return number;
        throw new IllegalArgumentException("Incomplete learned P25 identity");
    }

    private static String enrichmentName(String proposed, String existing)
    {
        String name = text(proposed);
        return name.isEmpty() || name.length() > 256 ? existing : name;
    }

    private static void addEnrichmentChange(List<EnrichmentChange> changes, String field, String before, String after)
    {
        if(!Objects.equals(before, after)) changes.add(new EnrichmentChange(field, before, after));
    }

    private static EnrichmentPreview enrichmentResult(ChannelAdministrationService.Entry entry, String previewId,
        String state, String reason, List<EnrichmentChange> changes, String systemUrl, String siteUrl,
        P25SiteIdentity identity)
    {
        RadioReferenceChannelNames names = new RadioReferenceChannelNames(entry.channel().system(), entry.channel().site(), entry.channel().name());
        for(var change: changes)
        {
            names = switch(change.field())
            {
                case "system" -> new RadioReferenceChannelNames(change.after(), names.site(), names.name());
                case "site" -> new RadioReferenceChannelNames(names.system(), change.after(), names.name());
                case "name" -> new RadioReferenceChannelNames(names.system(), names.site(), change.after());
                default -> names;
            };
        }
        return new EnrichmentPreview(previewId, state, reason, entry.channel().configurationId(), entry.revision(),
            changes, systemUrl, siteUrl, entry.processingState() == ChannelAdministrationService.ProcessingState.RUNNING,
            identity, null, List.of(), names);
    }

    /** Returns one RadioReference talkgroup page annotated against one exact destination Alias List. */
    public TalkgroupPage talkgroups(int systemId, long aliasListId, Integer categoryId, String search, int offset,
                                    int limit) throws RadioReferenceDirectoryException
    {
        TrunkedSystemDetails system = requireSystem(positive(systemId, "system_id"));
        DecoderPlan decoder = systemDecoder(system);
        requireSupported(decoder);
        requireCompatibleTalkgroupList(aliasListId, decoder.decoderType());
        BoundedPage<RemoteTalkgroup> page = mDirectory.talkgroups(systemId, categoryId, search, offset, limit);
        List<RemoteTalkgroupCategory> categories = optionalCategories(systemId);
        AliasRows rows = aliasRows(aliasListId, decoder.protocol(), page.items(), categories);
        return new TalkgroupPage(rows.revision(), rows.items(), page.offset(), page.nextOffset(), page.totalItems(),
            categories);
    }

    /** Loads the catalog independently, then optionally annotates it against an Alias List. */
    public TalkgroupPage talkgroupCatalog(int systemId, Long aliasListId, String catalogId)
        throws RadioReferenceDirectoryException
    {
        return talkgroupCatalog(systemId, aliasListId, catalogId, false);
    }

    public TalkgroupPage talkgroupCatalog(int systemId, Long aliasListId, String catalogId, boolean refresh)
        throws RadioReferenceDirectoryException
    {
        positive(systemId, "system_id");
        RemoteCatalog catalog = refresh || catalogId == null || catalogId.isBlank() ?
            cachedCatalog(systemId, refresh, mSystemTalkgroupCatalogs, mTalkgroupLoads,
                () -> loadTalkgroupCatalog(systemId)) : requireTalkgroupCatalog(catalogId, systemId);
        DecoderPlan decoder = systemDecoder(catalog.system());
        requireSupported(decoder);
        if(aliasListId == null)
        {
            Map<Integer,String> categoryNames = new HashMap<>();
            catalog.categories().forEach(category -> categoryNames.put(category.id(), textOrNull(category.name())));
            List<TalkgroupRow> items = catalog.talkgroups().stream()
                .map(talkgroup -> new TalkgroupRow(talkgroup, categoryNames.get(talkgroup.categoryId()),
                    TalkgroupStatus.UNCOMPARED, null, List.of())).toList();
            return new TalkgroupPage(0, items, 0, null, items.size(), catalog.categories(), catalog.id());
        }
        requireCompatibleTalkgroupList(aliasListId, decoder.decoderType());
        AliasRows rows = aliasRows(aliasListId, decoder.protocol(), catalog.talkgroups(), catalog.categories());
        return new TalkgroupPage(rows.revision(), rows.items(), 0, null, rows.items().size(),
            catalog.categories(), catalog.id());
    }

    /** Captures upstream data once; preview and apply reuse it without further RadioReference reads. */
    private RemoteCatalog loadTalkgroupCatalog(int systemId) throws RadioReferenceDirectoryException
    {
        long started = System.nanoTime();
        TrunkedSystemDetails system = systemDetails(systemId, false);
        long systemLoaded = System.nanoTime();
        DecoderPlan decoder = systemDecoder(system);
        requireSupported(decoder);
        List<RemoteTalkgroup> talkgroups = mDirectory.allTalkgroups(systemId);
        long talkgroupsLoaded = System.nanoTime();
        List<RemoteTalkgroupCategory> categories = optionalCategories(systemId);
        mLog.info("RadioReference catalog loaded: system {} ms, talkgroups {} ms, categories {} ms, {} rows",
            Duration.ofNanos(systemLoaded - started).toMillis(),
            Duration.ofNanos(talkgroupsLoaded - systemLoaded).toMillis(),
            Duration.ofNanos(System.nanoTime() - talkgroupsLoaded).toMillis(), talkgroups.size());
        RemoteCatalog catalog = new RemoteCatalog(UUID.randomUUID().toString(), system, talkgroups, categories);
        return catalog;
    }

    private synchronized RemoteCatalog requireTalkgroupCatalog(String catalogId, int systemId)
        throws RadioReferenceDirectoryException
    {
        requireCatalogSession();
        RemoteCatalog catalog = mTalkgroupCatalogs.get(catalogId);
        if(catalog == null || catalog.system().id() != systemId)
        {
            throw new IllegalArgumentException("RadioReference catalog is no longer available; reload talkgroups");
        }
        return catalog;
    }

    /** Discards account-scoped source snapshots and previews after a RadioReference login change. */
    public synchronized void clearSessionData()
    {
        mSessionGeneration++;
        mTalkgroupCatalogs.clear();
        mSystemTalkgroupCatalogs.clear();
        mSystemDetails.clear();
        mSiteCatalogs.clear();
        cancelCatalogLoads(mTalkgroupLoads);
        cancelCatalogLoads(mSystemLoads);
        cancelCatalogLoads(mSiteLoads);
        mPendingTalkgroups.clear();
        mPendingChannels.clear();
        mPendingEnrichments.clear();
        mPendingNameLookups.clear();
    }

    /** Builds one revision-bound Alias import plan for selected talkgroups or the whole system. */
    public TalkgroupImportPreview previewTalkgroups(TalkgroupImportRequest request)
        throws RadioReferenceDirectoryException
    {
        long started = System.nanoTime();
        Objects.requireNonNull(request, "Talkgroup import request cannot be null");
        int systemId = positive(request.systemId(), "system_id");
        RemoteCatalog catalog = requireTalkgroupCatalog(request.catalogId(), systemId);
        DecoderPlan decoder = systemDecoder(catalog.system());
        requireSupported(decoder);
        AliasAdministrationService.Options options = requireCompatibleTalkgroupList(request.aliasListId(),
            decoder.decoderType());
        List<RemoteTalkgroup> remote;
        if(request.all())
        {
            if(request.talkgroupIds() != null && !request.talkgroupIds().isEmpty())
            {
                throw new IllegalArgumentException("talkgroup_ids must be empty when importing all talkgroups");
            }
            remote = catalog.talkgroups();
        }
        else
        {
            Set<Integer> selected = boundedPositiveIds(request.talkgroupIds(), "talkgroup_ids");
            remote = catalog.talkgroups().stream().filter(row -> selected.contains(row.id())).toList();
            Set<Integer> loaded = remote.stream().map(RemoteTalkgroup::id)
                .collect(java.util.stream.Collectors.toSet());
            if(loaded.size() != selected.size() || !loaded.containsAll(selected))
            {
                throw new IllegalArgumentException("One or more selected RadioReference talkgroups are unavailable");
            }
        }
        if(remote.isEmpty())
        {
            throw new IllegalArgumentException("No RadioReference talkgroups were selected");
        }
        if(remote.size() > AliasAdministrationService.MAX_BULK_ALIASES)
        {
            throw new IllegalArgumentException("A talkgroup import cannot exceed " +
                AliasAdministrationService.MAX_BULK_ALIASES + " rows");
        }

        List<RemoteTalkgroupCategory> categories = catalog.categories();
        PreparedAliases prepared = prepareAliases(options.aliasList().getId(), decoder.protocol(), remote,
            categories);
        AliasImportService.Plan plan = mAliasImporter.preview(options.aliasList().getId(),
            AliasImportService.Mode.UPDATE_ADD, prepared.inputs(), null);
        AliasImportService.Preview aliasPreview = plan.preview();
        if(aliasPreview.counts().getOrDefault("error", 0L) > 0)
        {
            String error = aliasPreview.rows().stream().map(AliasImportService.Row::error)
                .filter(Objects::nonNull).findFirst().orElse("RadioReference talkgroup import is invalid");
            throw new IllegalArgumentException(error);
        }
        List<TalkgroupRow> rows = annotatedRows(options.aliasList().getId(), aliasPreview.revision(), remote,
            prepared.categoriesById(), aliasPreview.rows());
        int rowCount = rows.size();
        List<TalkgroupRow> displayed = rows.subList(0, Math.min(rowCount, MAXIMUM_TALKGROUP_PREVIEW_ROWS));
        TalkgroupImportPreview preview = storeTalkgroups(options.aliasList().getId(), aliasPreview, plan,
            request.all(), rowCount, displayed);
        mLog.debug("RadioReference preview prepared from loaded catalog: {} rows in {} ms", rowCount,
            Duration.ofNanos(System.nanoTime() - started).toMillis());
        return preview;
    }

    /** Consumes and atomically applies one talkgroup preview. */
    public TalkgroupImportResult applyTalkgroups(String previewId)
    {
        PendingTalkgroups pending = consumeTalkgroups(previewId);
        AliasAdministrationService.MutationResult result = mAliasImporter.apply(pending.plan());
        Map<String,Long> counts = pending.preview().counts();
        return new TalkgroupImportResult(result.revision(), result.aliasListId(), result.aliasIds(),
            counts.getOrDefault("added", 0L).intValue(), counts.getOrDefault("updated", 0L).intValue(),
            counts.getOrDefault("unchanged", 0L).intValue());
    }

    private LocalChannelSnapshot localSnapshot()
    {
        ChannelAdministrationService.Options options = mChannels.options();
        ChannelAdministrationService.Catalog catalog = mChannels.catalog();
        requireSameRevision(options.revision(), catalog.revision());
        return new LocalChannelSnapshot(options, catalog);
    }

    /** Checks that a saved trunked-system preference names a local talkgroup Alias List. */
    public boolean preferredTalkgroupAliasListExists(long aliasListId)
    {
        return aliasListId > 0 && mChannels.options().aliasLists().stream()
            .anyMatch(candidate -> candidate.id() == aliasListId &&
                (candidate.family().equals(AliasListFamily.P25.name()) ||
                    candidate.family().equals(AliasListFamily.DMR.name()) ||
                    candidate.family().equals(AliasListFamily.NXDN.name())));
    }

    private long requireCompatibleAliasList(long aliasListId, String protocolId,
                                            ChannelAdministrationService.Options options)
    {
        if(aliasListId <= 0)
        {
            throw new IllegalArgumentException("alias_list_id must be positive");
        }
        AliasListFamily family = mChannels.protocolRegistry().require(protocolId).aliasFamily();
        ChannelAdministrationService.AliasListOption match = options.aliasLists().stream()
            .filter(candidate -> candidate.id() == aliasListId).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Alias List was not found"));
        if(!match.family().equals(family.name()))
        {
            throw new IllegalArgumentException("Alias List is not compatible with " + protocolId);
        }
        return match.id();
    }

    private long defaultAliasList(String protocolId, ChannelAdministrationService.Options options)
    {
        AliasListFamily family = mChannels.protocolRegistry().require(protocolId).aliasFamily();
        return options.aliasLists().stream()
            .filter(candidate -> candidate.family().equals(family.name()) &&
                candidate.name().equalsIgnoreCase(family.getDefaultAliasListName()))
            .findFirst().or(() -> options.aliasLists().stream()
                .filter(candidate -> candidate.family().equals(family.name())).findFirst())
            .orElseThrow(() -> new IllegalStateException("Create a compatible " + family +
                " Alias List before importing this frequency")).id();
    }

    private AliasAdministrationService.Options requireCompatibleTalkgroupList(long aliasListId,
                                                                               DecoderType decoderType)
    {
        if(aliasListId <= 0)
        {
            throw new IllegalArgumentException("alias_list_id must be positive");
        }
        AliasAdministrationService.Options options = mAliases.options(aliasListId);
        if(!AliasMatchRegistry.isChannelCompatible(options.aliasList(), decoderType))
        {
            throw new IllegalArgumentException("Alias List is not compatible with " + decoderType);
        }
        return options;
    }

    private ChannelDefinition siteDefinition(DecoderPlan decoder, TrunkedSystemDetails system,
                                             TrunkedSiteDetails site, String systemName, String siteName,
                                             String channelName, long aliasListId, List<Long> frequencies,
                                             long minimum, long maximum)
    {
        ChannelDefinition template = mChannels.template(decoder.protocolId());
        Map<String,Object> settings = new LinkedHashMap<>(template.settings());
        List<ChannelDefinition.FrequencyMapEntry> frequencyMap = List.of();
        switch(decoder.decoderType())
        {
            case P25_PHASE1 -> settings.put("modulation",
                detectP25Modulation(site.name(), null, site.modulation()));
            case P25_PHASE2 -> {
                settings.put("auto_detect_scramble_parameters", false);
                settings.put("scramble_wacn", parseHex(system.wacn()));
                settings.put("scramble_system", parseHex(system.systemId()));
                settings.put("scramble_nac", parseHex(site.nac()));
            }
            case DMR -> {
                boolean conventional = normalized(system.flavor()).contains("conventional networked");
                settings.put("channel_mode", conventional ? "CONVENTIONAL" : "TRUNKED");
                if(!conventional)
                {
                    frequencyMap = frequencyMap(site, false);
                }
            }
            case NXDN -> {
                boolean conventional = normalized(system.flavor()).contains("conventional networked");
                settings.put("channel_mode", conventional ? "CONVENTIONAL" : "TRUNKED");
                settings.put("transmission_mode", nxdnTransmissionMode(system.flavor()));
                if(!conventional)
                {
                    frequencyMap = frequencyMap(site, true);
                }
            }
            default -> throw new IllegalArgumentException("Unsupported trunked decoder " + decoder.decoderType());
        }
        return new ChannelDefinition(null, decoder.protocolId(), systemName, siteName, channelName, null,
            aliasListId, new ChannelDefinition.Source(frequencies, minimum, maximum, null, null, null), settings,
            frequencyMap, template.eventLogs(), template.recorders(), template.auxiliaryDecoders(),
            ChannelDefinition.Observed.EMPTY);
    }

    private static List<ChannelDefinition.FrequencyMapEntry> frequencyMap(TrunkedSiteDetails site,
                                                                          boolean nxdn)
    {
        Map<Integer,ChannelDefinition.FrequencyMapEntry> result = new LinkedHashMap<>();
        for(TrunkedSiteChannel channel: site.channels())
        {
            int number = siteChannelNumber(channel);
            if(number > 0 && channel.frequencyHz() > 0 && (!nxdn || number <= 2048))
            {
                result.putIfAbsent(number,
                    new ChannelDefinition.FrequencyMapEntry(number, channel.frequencyHz(), 0));
            }
        }
        return List.copyOf(result.values());
    }

    private static int siteChannelNumber(TrunkedSiteChannel channel)
    {
        if(channel.channelId() != null && !channel.channelId().isBlank())
        {
            try
            {
                return Integer.parseInt(channel.channelId().trim());
            }
            catch(NumberFormatException exception)
            {
                //Use the dedicated RadioReference LCN when the display-oriented channel ID is not numeric.
            }
        }
        return channel.logicalChannelNumber();
    }

    private ExistingChannel matchingChannel(ChannelAdministrationService.Catalog catalog,
                                             ChannelDefinition requested)
    {
        List<ChannelAdministrationService.ChannelSummary> matches = catalog.channels().stream()
            .filter(ChannelAdministrationService.ChannelSummary::editable)
            .filter(channel -> Objects.equals(channel.protocolId(), requested.protocolId()) &&
                Objects.equals(textOrNull(channel.system()), textOrNull(requested.system())) &&
                Objects.equals(textOrNull(channel.site()), textOrNull(requested.site())) &&
                Objects.equals(textOrNull(channel.name()), textOrNull(requested.name())))
            .toList();
        if(matches.size() > 1)
        {
            throw new IllegalStateException("More than one saved channel has this system, site, name, and protocol");
        }
        return matches.isEmpty() ? null : new ExistingChannel(matches.getFirst().configurationId());
    }

    private static ChannelDefinition sourceOnlyReplacement(ChannelDefinition existing, List<Long> frequencies,
                                                           Long minimum, Long maximum)
    {
        ChannelDefinition.Source old = existing.source();
        Long preferred = old.preferredFrequencyHz() != null && frequencies.contains(old.preferredFrequencyHz()) ?
            old.preferredFrequencyHz() : null;
        ChannelProtocolRegistry.SourceMode sourceMode = switch(existing.protocolId())
        {
            case "am", "nbfm", "p25-conventional" -> ChannelProtocolRegistry.SourceMode.SINGLE;
            default -> ChannelProtocolRegistry.SourceMode.MULTIPLE;
        };
        ChannelDefinition.Source source = sourceMode == ChannelProtocolRegistry.SourceMode.SINGLE ?
            new ChannelDefinition.Source(frequencies, null, null, null, old.preferredTuner(), null) :
            new ChannelDefinition.Source(frequencies, minimum, maximum, preferred, old.preferredTuner(),
                old.rotationDelayMs());
        return new ChannelDefinition(existing.configurationId(), existing.protocolId(), existing.system(),
            existing.site(), existing.name(), existing.radioResolveId(), existing.aliasListId(), source,
            existing.settings(), existing.frequencyMap(), existing.eventLogs(), existing.recorders(),
            existing.auxiliaryDecoders(), existing.observed());
    }

    static List<Long> selectSiteFrequencies(TrunkedSiteDetails site, FrequencySet set,
                                            List<Long> selectedFrequencies)
    {
        List<TrunkedSiteChannel> available = site != null ? site.channels() : List.of();
        if(available.isEmpty())
        {
            throw new IllegalArgumentException("The RadioReference site has no frequencies");
        }
        LinkedHashSet<Long> selected = new LinkedHashSet<>();
        switch(set)
        {
            case CONTROL -> available.stream().filter(RadioReferenceImportService::isPrimaryControl)
                .map(TrunkedSiteChannel::frequencyHz).filter(value -> value > 0).forEach(selected::add);
            case CONTROL_AND_ALTERNATES -> {
                if(available.stream().noneMatch(RadioReferenceImportService::isPrimaryControl))
                {
                    throw new IllegalArgumentException("The RadioReference site has no primary control frequency");
                }
                available.stream().filter(RadioReferenceImportService::isPrimaryControl)
                    .map(TrunkedSiteChannel::frequencyHz).filter(value -> value > 0).forEach(selected::add);
                available.stream().filter(RadioReferenceImportService::isAlternateControl)
                    .map(TrunkedSiteChannel::frequencyHz).filter(value -> value > 0).forEach(selected::add);
            }
            case ALL -> available.stream().map(TrunkedSiteChannel::frequencyHz).filter(value -> value > 0)
                .forEach(selected::add);
            case SELECTED -> {
                if(selectedFrequencies == null || selectedFrequencies.isEmpty())
                {
                    throw new IllegalArgumentException("Select at least one site frequency");
                }
                Set<Long> allowed = new LinkedHashSet<>(allSiteFrequencies(site));
                for(Long frequency: selectedFrequencies)
                {
                    if(frequency == null || !allowed.contains(frequency) || !selected.add(frequency))
                    {
                        throw new IllegalArgumentException("Selected frequencies must be unique site frequencies");
                    }
                }
            }
        }
        if(set != FrequencySet.SELECTED && selectedFrequencies != null && !selectedFrequencies.isEmpty())
        {
            throw new IllegalArgumentException("selected_frequencies_hz is only valid for Selected mode");
        }
        if(selected.isEmpty())
        {
            throw new IllegalArgumentException(set == FrequencySet.CONTROL ?
                "The RadioReference site has no primary control frequency" :
                "The RadioReference site has no frequencies for this selection");
        }
        if(selected.size() > 256)
        {
            throw new IllegalArgumentException("A channel cannot exceed 256 frequencies");
        }
        return List.copyOf(selected);
    }

    private static List<Long> allSiteFrequencies(TrunkedSiteDetails site)
    {
        return site.channels().stream().map(TrunkedSiteChannel::frequencyHz).filter(value -> value > 0)
            .distinct().toList();
    }

    private static boolean isPrimaryControl(TrunkedSiteChannel channel)
    {
        return channel.primaryControl() || "d".equalsIgnoreCase(text(channel.use()));
    }

    private static boolean isAlternateControl(TrunkedSiteChannel channel)
    {
        return channel.alternateControl() || "a".equalsIgnoreCase(text(channel.use()));
    }

    /** Applies the agreed P25 Phase 1 detection rule used by preview and create. */
    public static String detectP25Modulation(String siteName, String siteDescription, String structuredModulation)
    {
        if(normalized(siteName).contains("simul") || normalized(siteDescription).contains("simul"))
        {
            return "CQPSK";
        }
        String structured = normalized(structuredModulation).toUpperCase(Locale.ROOT);
        if(structured.contains("CQPSK") || structured.contains("LSM"))
        {
            return "CQPSK";
        }
        return "C4FM";
    }

    private static DecoderPlan siteDecoder(TrunkedSystemDetails system, TrunkedSiteDetails site)
    {
        DecoderPlan base = systemDecoder(system);
        if(base.hybrid())
        {
            return new DecoderPlan(base.protocol(), null, null, true,
                "Motorola Type II control channels with P25 voice are not supported");
        }
        if(!base.supported())
        {
            return base;
        }
        if(base.decoderType() == DecoderType.P25_PHASE2 && !site.tdmaControlChannel())
        {
            return new DecoderPlan(base.protocol(), DecoderType.P25_PHASE1, "p25-phase1", false, "");
        }
        return base;
    }

    private static DecoderPlan systemDecoder(TrunkedSystemDetails system)
    {
        String type = normalized(system.type());
        String flavor = normalized(system.flavor());
        String voice = normalized(system.voice());
        if(type.equals("dmr"))
        {
            return new DecoderPlan(Protocol.DMR, DecoderType.DMR, "dmr", false, "");
        }
        if(type.equals("project 25"))
        {
            boolean phaseTwo = flavor.contains("phase ii");
            return new DecoderPlan(Protocol.APCO25,
                phaseTwo ? DecoderType.P25_PHASE2 : DecoderType.P25_PHASE1,
                phaseTwo ? "p25-phase2" : "p25-phase1", false, "");
        }
        if(type.equals("motorola") && voice.contains("apco-25"))
        {
            return new DecoderPlan(Protocol.APCO25, DecoderType.P25_PHASE1, "p25-phase1", true, "");
        }
        if(type.equals("nxdn"))
        {
            return new DecoderPlan(Protocol.NXDN, DecoderType.NXDN, "nxdn", false, "");
        }
        return new DecoderPlan(Protocol.UNKNOWN, null, null, false,
            "RadioReference system type [" + text(system.type()) + "] is not supported");
    }

    private static String conventionalProtocol(String mode)
    {
        return switch(normalized(mode))
        {
            case "am" -> "am";
            case "fm", "fmn" -> "nbfm";
            case "p25", "apco-25", "project 25", "project 25 phase i" -> "p25-conventional";
            case "dmr" -> "dmr";
            case "nxdn", "nxdn48", "nxdn96" -> "nxdn";
            default -> throw new IllegalArgumentException("RadioReference mode [" + text(mode) +
                "] is not supported");
        };
    }

    private static void applyConventionalSettings(String protocolId, String mode, Map<String,Object> settings)
    {
        switch(protocolId)
        {
            case "nbfm" -> {
                if(normalized(mode).equals("fm"))
                {
                    settings.put("bandwidth", "BW_25_0");
                }
            }
            case "dmr" -> settings.put("channel_mode", "CONVENTIONAL");
            case "nxdn" -> {
                settings.put("channel_mode", "CONVENTIONAL");
                settings.put("transmission_mode", normalized(mode).contains("96") ? "M9600" : "M4800");
            }
            default -> {
                //The protocol catalog defaults are the complete AM and P25 conventional configuration.
            }
        }
    }

    private AliasRows aliasRows(long aliasListId, Protocol protocol, List<RemoteTalkgroup> remote,
                                List<RemoteTalkgroupCategory> categories)
    {
        if(remote.isEmpty())
        {
            return new AliasRows(mAliases.currentRevision(), List.of());
        }
        PreparedAliases prepared = prepareAliases(aliasListId, protocol, remote, categories);
        AliasImportService.Preview preview = mAliasImporter.preview(aliasListId, AliasImportService.Mode.UPDATE_ADD,
            prepared.inputs(), null).preview();
        if(preview.counts().getOrDefault("error", 0L) > 0)
        {
            throw new IllegalArgumentException(preview.rows().stream().map(AliasImportService.Row::error)
                .filter(Objects::nonNull).findFirst().orElse("RadioReference talkgroup preview is invalid"));
        }
        return new AliasRows(preview.revision(), annotatedRows(aliasListId, preview.revision(), remote,
            prepared.categoriesById(), preview.rows()));
    }

    private PreparedAliases prepareAliases(long aliasListId, Protocol protocol, List<RemoteTalkgroup> remote,
                                            List<RemoteTalkgroupCategory> categories)
    {
        Map<Integer,String> categoriesById = new HashMap<>();
        categories.forEach(category -> categoriesById.put(category.id(), textOrNull(category.name())));
        AliasAdministrationService.Options options = mAliases.options(aliasListId);
        List<AliasImportService.Input> inputs = new ArrayList<>(remote.size());
        for(RemoteTalkgroup talkgroup: remote)
        {
            String category = categoriesById.get(talkgroup.categoryId());
            boolean categoryProvided = categoriesById.containsKey(talkgroup.categoryId());
            Alias alias = new Alias(firstNonBlank(talkgroup.alphaTag(), Integer.toString(talkgroup.value())));
            alias.setAliasListDefinition(options.aliasList());
            alias.setDescription(textOrNull(talkgroup.description()));
            alias.setGroup(category);
            alias.setMatchIdentifier(new io.github.dsheirer.alias.id.talkgroup.Talkgroup(protocol,
                talkgroup.value()));
            inputs.add(new AliasImportService.Input(alias, true, categoryProvided,
                talkgroup.encryptionState() == 2, null, null, null));
        }
        return new PreparedAliases(List.copyOf(inputs),
            Collections.unmodifiableMap(new HashMap<>(categoriesById)));
    }

    private List<TalkgroupRow> annotatedRows(long aliasListId, long revision, List<RemoteTalkgroup> remote,
                                             Map<Integer,String> categories, List<AliasImportService.Row> rows)
    {
        AliasAdministrationService.TransferSnapshot snapshot = mAliases.transferSnapshot(aliasListId);
        requireSameRevision(revision, snapshot.options().revision());
        Map<Long,CurrentAlias> currentAliases = new HashMap<>();
        snapshot.aliases().forEach(entry -> currentAliases.put(entry.alias().getId(),
            new CurrentAlias(entry.alias().getName(), entry.alias().getDescription(), entry.alias().getGroup())));
        if(remote.size() != rows.size())
        {
            throw new IllegalStateException("RadioReference talkgroup preview row mismatch");
        }
        List<TalkgroupRow> result = new ArrayList<>(remote.size());
        for(int index = 0; index < remote.size(); index++)
        {
            RemoteTalkgroup talkgroup = remote.get(index);
            AliasImportService.Row row = rows.get(index);
            TalkgroupStatus status = switch(row.result())
            {
                case "added" -> TalkgroupStatus.NOT_PRESENT;
                case "updated" -> TalkgroupStatus.DIFFERENT;
                case "unchanged" -> TalkgroupStatus.IDENTICAL;
                default -> throw new IllegalArgumentException(row.error() != null ? row.error() :
                    "RadioReference talkgroup preview is invalid");
            };
            result.add(new TalkgroupRow(talkgroup, categories.get(talkgroup.categoryId()), status, row.aliasId(),
                row.changes(), currentAliases.get(row.aliasId())));
        }
        return List.copyOf(result);
    }

    private List<RemoteTalkgroupCategory> optionalCategories(int systemId)
        throws RadioReferenceDirectoryException
    {
        try
        {
            List<RemoteTalkgroupCategory> result = mDirectory.allTalkgroupCategories(systemId);
            if(result.size() > AliasAdministrationService.MAX_BULK_ALIASES)
            {
                throw new IllegalArgumentException("RadioReference returned too many talkgroup categories");
            }
            return result;
        }
        catch(RadioReferenceDirectoryException exception)
        {
            if(exception.code() == RadioReferenceDirectoryException.Code.BUSY ||
                exception.code() == RadioReferenceDirectoryException.Code.TIMEOUT ||
                exception.code() == RadioReferenceDirectoryException.Code.UNAVAILABLE)
            {
                mLog.debug("Skipping optional RadioReference category enrichment [{}]", exception.code());
                return List.of();
            }
            throw exception;
        }
    }

    /** Account-scoped, bounded source catalogs; explicit refresh keeps the previous successful value on failure. */
    public TrunkedSystemDetails systemDetails(int systemId, boolean refresh)
        throws RadioReferenceDirectoryException
    {
        positive(systemId, "system_id");
        return cachedCatalog(systemId, refresh, mSystemDetails, mSystemLoads, () -> {
            TrunkedSystemDetails system = mDirectory.trunkedSystemDetails(systemId);
            if(system == null || system.id() != systemId)
                throw new IllegalArgumentException("RadioReference system was not found");
            return system;
        });
    }

    public List<TrunkedSiteDetails> siteCatalog(int systemId, boolean refresh)
        throws RadioReferenceDirectoryException
    {
        positive(systemId, "system_id");
        return cachedCatalog(systemId, refresh, mSiteCatalogs, mSiteLoads,
            () -> List.copyOf(mDirectory.allTrunkedSites(systemId)));
    }

    private <T> T cachedCatalog(int systemId, boolean refresh, Map<Integer,T> values,
                                Map<Integer,CompletableFuture<T>> loads, CatalogLoader<T> loader)
        throws RadioReferenceDirectoryException
    {
        requireCatalogSession();
        CompletableFuture<T> pending;
        boolean owner;
        long generation;
        synchronized(this)
        {
            if(!refresh && values.containsKey(systemId)) return values.get(systemId);
            generation = mSessionGeneration;
            pending = loads.get(systemId);
            if(pending != null && pending.isDone())
            {
                loads.remove(systemId, pending);
                pending = null;
            }
            owner = pending == null;
            if(owner)
            {
                if(loads.size() >= MAXIMUM_TALKGROUP_CATALOGS)
                    throw new RadioReferenceDirectoryException(RadioReferenceDirectoryException.Code.BUSY);
                pending = new CompletableFuture<>();
                loads.put(systemId, pending);
            }
        }
        Thread worker = null;
        if(owner)
        {
            CompletableFuture<T> shared = pending;
            worker = Thread.ofVirtual().name("radioreference-catalog").start(() -> {
                try
                {
                    T value = loader.load();
                    synchronized(this)
                    {
                        if(generation != mSessionGeneration || shared.isDone())
                            throw new IllegalStateException("RadioReference catalog load was cancelled; reload the catalog");
                        if(!values.containsKey(systemId) && values.size() >= MAXIMUM_TALKGROUP_CATALOGS)
                            values.remove(values.keySet().iterator().next());
                        if(value instanceof RemoteCatalog catalog)
                        {
                            if(mTalkgroupCatalogs.size() >= MAXIMUM_TALKGROUP_CATALOGS)
                            {
                                RemoteCatalog removed = mTalkgroupCatalogs.remove(
                                    mTalkgroupCatalogs.keySet().iterator().next());
                                mSystemTalkgroupCatalogs.remove(removed.system().id(), removed);
                            }
                            mTalkgroupCatalogs.put(catalog.id(), catalog);
                        }
                        values.put(systemId, value);
                        shared.complete(value);
                    }
                }
                catch(Exception exception) { shared.completeExceptionally(exception); }
                finally
                {
                    synchronized(this) { loads.remove(systemId, shared); }
                }
            });
        }
        try
        {
            T value = pending.get(60, TimeUnit.SECONDS);
            requireCatalogSession();
            synchronized(this)
            {
                if(generation != mSessionGeneration)
                    throw new IllegalStateException("RadioReference account changed; reload the catalog");
            }
            return value;
        }
        catch(InterruptedException exception)
        {
            if(worker != null)
            {
                pending.completeExceptionally(new RadioReferenceDirectoryException(
                    RadioReferenceDirectoryException.Code.INTERRUPTED));
                worker.interrupt();
            }
            Thread.currentThread().interrupt();
            throw new RadioReferenceDirectoryException(RadioReferenceDirectoryException.Code.INTERRUPTED);
        }
        catch(TimeoutException exception)
        {
            if(worker != null)
            {
                pending.completeExceptionally(new RadioReferenceDirectoryException(
                    RadioReferenceDirectoryException.Code.TIMEOUT));
                worker.interrupt();
            }
            throw new RadioReferenceDirectoryException(RadioReferenceDirectoryException.Code.TIMEOUT);
        }
        catch(ExecutionException exception)
        {
            if(exception.getCause() instanceof RadioReferenceDirectoryException failure) throw failure;
            if(exception.getCause() instanceof RuntimeException failure) throw failure;
            throw new RadioReferenceDirectoryException(RadioReferenceDirectoryException.Code.UNAVAILABLE);
        }
    }

    @FunctionalInterface
    private interface CatalogLoader<T> { T load() throws RadioReferenceDirectoryException; }

    private void requireCatalogSession() throws RadioReferenceDirectoryException
    {
        try { mDirectory.requireCatalogSession(); }
        catch(RadioReferenceDirectoryException exception)
        {
            if(exception.code() == RadioReferenceDirectoryException.Code.NOT_AUTHENTICATED ||
                exception.code() == RadioReferenceDirectoryException.Code.INVALID_CREDENTIALS ||
                exception.code() == RadioReferenceDirectoryException.Code.PREMIUM_REQUIRED ||
                exception.code() == RadioReferenceDirectoryException.Code.CLOSED)
                clearSessionData();
            throw exception;
        }
    }

    private static <T> void cancelCatalogLoads(Map<Integer,CompletableFuture<T>> loads)
    {
        loads.values().forEach(load -> load.completeExceptionally(
            new IllegalStateException("RadioReference account changed; reload the catalog")));
        loads.clear();
    }

    private TrunkedSystemDetails requireSystem(int systemId) throws RadioReferenceDirectoryException
    {
        requireCatalogSession();
        TrunkedSystemDetails system;
        synchronized(this) { system = mSystemDetails.get(systemId); }
        if(system == null) system = mDirectory.trunkedSystemDetails(systemId);
        if(system == null || system.id() != systemId)
        {
            throw new IllegalArgumentException("RadioReference system was not found");
        }
        return system;
    }

    private TrunkedSiteDetails requireSite(int systemId, int siteId) throws RadioReferenceDirectoryException
    {
        requireCatalogSession();
        List<TrunkedSiteDetails> sites;
        synchronized(this) { sites = mSiteCatalogs.get(systemId); }
        if(sites == null) sites = mDirectory.allTrunkedSites(systemId);
        return sites.stream().filter(site -> site.id() == siteId)
            .findFirst().orElseThrow(() -> new IllegalArgumentException("RadioReference site was not found"));
    }

    private synchronized ChannelPreview storeChannel(long revision, ChannelAction action, String existingId,
                                                     ChannelDefinition channel, String detectedModulation,
                                                     List<Long> previousFrequencies)
    {
        long now = mClock.millis();
        cleanupExpired(now);
        requirePreviewCapacity();
        String id = UUID.randomUUID().toString();
        long expires = now + mPreviewLifetimeMillis;
        mPendingChannels.put(id, new PendingChannel(revision, action, existingId, channel, expires));
        return new ChannelPreview(id, expires, revision, action, existingId, channel, detectedModulation,
            previousFrequencies);
    }

    private synchronized TalkgroupImportPreview storeTalkgroups(long aliasListId,
                                                               AliasImportService.Preview preview,
                                                               AliasImportService.Plan plan, boolean all,
                                                               int rowCount, List<TalkgroupRow> rows)
    {
        long now = mClock.millis();
        cleanupExpired(now);
        requirePreviewCapacity();
        String id = UUID.randomUUID().toString();
        long expires = now + mPreviewLifetimeMillis;
        mPendingTalkgroups.put(id, new PendingTalkgroups(plan, preview, expires));
        return new TalkgroupImportPreview(id, expires, aliasListId, preview.list(), preview.revision(), all,
            rowCount, rowCount > rows.size(), preview.counts(), rows);
    }

    private synchronized PendingChannel consumeChannel(String previewId)
    {
        String id = requirePreviewId(previewId);
        long now = mClock.millis();
        cleanupExpired(now);
        PendingChannel pending = mPendingChannels.remove(id);
        if(pending == null || pending.expiresAtEpochMs() <= now)
        {
            throw new PreviewNotFoundException();
        }
        return pending;
    }

    private synchronized PendingTalkgroups consumeTalkgroups(String previewId)
    {
        String id = requirePreviewId(previewId);
        long now = mClock.millis();
        cleanupExpired(now);
        PendingTalkgroups pending = mPendingTalkgroups.remove(id);
        if(pending == null || pending.expiresAtEpochMs() <= now)
        {
            throw new PreviewNotFoundException();
        }
        return pending;
    }

    private void cleanupExpired(long now)
    {
        mPendingChannels.values().removeIf(plan -> plan.expiresAtEpochMs() <= now);
        mPendingTalkgroups.values().removeIf(plan -> plan.expiresAtEpochMs() <= now);
        mPendingEnrichments.values().removeIf(plan -> plan.expiresAtEpochMs() <= now);
        mPendingNameLookups.values().removeIf(plan -> plan.expiresAtEpochMs() <= now);
    }

    private void requirePreviewCapacity()
    {
        if(mPendingChannels.size() + mPendingTalkgroups.size() + mPendingEnrichments.size() + mPendingNameLookups.size() >= MAXIMUM_PENDING_PREVIEWS)
        {
            throw new IllegalStateException("Too many RadioReference previews are awaiting confirmation");
        }
    }

    private static String requirePreviewId(String value)
    {
        try
        {
            return UUID.fromString(value).toString();
        }
        catch(RuntimeException exception)
        {
            throw new PreviewNotFoundException();
        }
    }

    private static void requireSupported(DecoderPlan plan)
    {
        if(!plan.supported())
        {
            throw new IllegalArgumentException(plan.reason());
        }
    }

    private static void requireSameRevision(long expected, long actual)
    {
        if(expected != actual)
        {
            throw new ChannelAdministrationService.StaleRevisionException(expected, actual);
        }
    }

    private static int positive(int value, String field)
    {
        if(value <= 0)
        {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    private static Set<Integer> boundedPositiveIds(Collection<Integer> ids, String field)
    {
        if(ids == null || ids.isEmpty() || ids.size() > AliasAdministrationService.MAX_BULK_ALIASES)
        {
            throw new IllegalArgumentException(field + " must contain 1-" +
                AliasAdministrationService.MAX_BULK_ALIASES + " items");
        }
        LinkedHashSet<Integer> result = new LinkedHashSet<>();
        for(Integer id: ids)
        {
            if(id == null || id <= 0 || !result.add(id))
            {
                throw new IllegalArgumentException(field + " must contain unique positive integers");
            }
        }
        return Collections.unmodifiableSet(result);
    }

    private static int parseHex(String value)
    {
        try
        {
            return value == null || value.isBlank() ? 0 : Integer.parseInt(value.trim(), 16);
        }
        catch(NumberFormatException exception)
        {
            return 0;
        }
    }

    private static String nxdnTransmissionMode(String flavor)
    {
        String normalized = normalized(flavor);
        if(normalized.contains("type d"))
        {
            return "TYPE_D";
        }
        return normalized.contains("9600") || normalized.contains("conventional networked") ?
            "M9600" : "M4800";
    }

    private static String fallback(String requested, String fallback)
    {
        String normalized = textOrNull(requested);
        return normalized != null ? normalized : firstNonBlank(fallback, "RadioReference Channel");
    }

    private static String firstNonBlank(String... values)
    {
        for(String value: values)
        {
            String normalized = textOrNull(value);
            if(normalized != null)
            {
                return normalized;
            }
        }
        return "RadioReference Channel";
    }

    private static String normalized(String value)
    {
        return text(value).toLowerCase(Locale.ROOT);
    }

    private static String text(String value)
    {
        return value == null ? "" : value.trim();
    }

    private static String textOrNull(String value)
    {
        String normalized = text(value);
        return normalized.isEmpty() ? null : normalized;
    }

    public enum FrequencySet
    {
        CONTROL,
        CONTROL_AND_ALTERNATES,
        SELECTED,
        ALL
    }

    public enum ChannelAction
    {
        CREATE,
        UPDATE
    }

    public enum TalkgroupStatus
    {
        UNCOMPARED,
        NOT_PRESENT,
        IDENTICAL,
        DIFFERENT
    }

    public record SiteImportRequest(int systemId, int siteId, long aliasListId, FrequencySet frequencySet,
                                    List<Long> selectedFrequenciesHz, String systemName, String siteName,
                                    String channelName)
    {
        public SiteImportRequest
        {
            selectedFrequenciesHz = selectedFrequenciesHz == null ? List.of() :
                List.copyOf(selectedFrequenciesHz);
        }
    }

    public record ConventionalImportRequest(RadioReferenceGateway.DetailKind ownerKind, int ownerId,
                                            int subCategoryId, int frequencyId, Long aliasListId,
                                            String systemName, String siteName, String channelName)
    {
    }

    public record ChannelPreview(String previewId, long expiresAtEpochMs, long revision, ChannelAction action,
                                 String existingConfigurationId, ChannelDefinition channel,
                                 String detectedModulation, List<Long> previousFrequenciesHz)
    {
        public ChannelPreview
        {
            previousFrequenciesHz = List.copyOf(previousFrequenciesHz);
        }
    }

    public record ChannelImportResult(long revision, ChannelAction action, String configurationId)
    {
    }

    public record EnrichmentChange(String field, String before, String after) {}
    public record EnrichmentCandidate(String candidateId, String system, String site, String name, String protocolId,
        long frequencyHz, String context, String sourceUrl, Integer rrSystemId, Long preferredAliasListId) {}
    public record EnrichmentPreview(String previewId, String state, String reason, String configurationId, long revision,
                                    List<EnrichmentChange> changes, String systemUrl, String siteUrl, boolean running,
                                    P25SiteIdentity identity, String lookupId, List<EnrichmentCandidate> candidates,
                                    RadioReferenceChannelNames names, Integer rrSystemId, Long preferredAliasListId)
    {
        public EnrichmentPreview
        {
            changes = List.copyOf(changes);
            candidates = List.copyOf(candidates != null ? candidates : List.of());
        }
        public EnrichmentPreview(String previewId, String state, String reason, String configurationId, long revision,
            List<EnrichmentChange> changes, String systemUrl, String siteUrl, boolean running, P25SiteIdentity identity)
        { this(previewId, state, reason, configurationId, revision, changes, systemUrl, siteUrl, running, identity,
            null, List.of(), null, null, null); }
        public EnrichmentPreview(String previewId, String state, String reason, String configurationId, long revision,
            List<EnrichmentChange> changes, String systemUrl, String siteUrl, boolean running, P25SiteIdentity identity,
            String lookupId, List<EnrichmentCandidate> candidates, RadioReferenceChannelNames names)
        { this(previewId, state, reason, configurationId, revision, changes, systemUrl, siteUrl, running, identity,
            lookupId, candidates, names, null, null); }
        public EnrichmentPreview withSystemId(Integer systemId)
        {
            return new EnrichmentPreview(previewId, state, reason, configurationId, revision, changes, systemUrl,
                siteUrl, running, identity, lookupId, candidates, names, systemId, preferredAliasListId);
        }
        public EnrichmentPreview withPreferredAliasLists(Map<Integer,Long> preferences)
        {
            return new EnrichmentPreview(previewId, state, reason, configurationId, revision, changes, systemUrl,
                siteUrl, running, identity, lookupId, candidates.stream().map(candidate -> new EnrichmentCandidate(
                    candidate.candidateId(), candidate.system(), candidate.site(), candidate.name(), candidate.protocolId(),
                    candidate.frequencyHz(), candidate.context(), candidate.sourceUrl(), candidate.rrSystemId(),
                    candidate.rrSystemId() != null ? preferences.get(candidate.rrSystemId()) : null)).toList(), names,
                rrSystemId, rrSystemId != null ? preferences.get(rrSystemId) : null);
        }
    }
    public record EnrichmentResult(String configurationId, long revision) {}

    public record TalkgroupImportRequest(int systemId, long aliasListId, boolean all, List<Integer> talkgroupIds,
                                         String catalogId)
    {
        public TalkgroupImportRequest
        {
            talkgroupIds = talkgroupIds == null ? List.of() : List.copyOf(talkgroupIds);
        }
    }

    public record CurrentAlias(String alphaTag, String description, String category) {}

    public record TalkgroupRow(RemoteTalkgroup talkgroup, String category, TalkgroupStatus status,
                               Long existingAliasId, List<AliasImportService.Change> changes,
                               CurrentAlias currentAlias)
    {
        public TalkgroupRow(RemoteTalkgroup talkgroup, String category, TalkgroupStatus status,
                            Long existingAliasId, List<AliasImportService.Change> changes)
        {
            this(talkgroup, category, status, existingAliasId, changes, null);
        }

        public TalkgroupRow
        {
            changes = List.copyOf(changes);
        }
    }

    public record TalkgroupPage(long revision, List<TalkgroupRow> items, int offset, Integer nextOffset,
                                int totalItems, List<RemoteTalkgroupCategory> categories, String catalogId)
    {
        public TalkgroupPage(long revision, List<TalkgroupRow> items, int offset, Integer nextOffset,
                             int totalItems, List<RemoteTalkgroupCategory> categories)
        {
            this(revision, items, offset, nextOffset, totalItems, categories, null);
        }

        public TalkgroupPage
        {
            items = List.copyOf(items);
            categories = List.copyOf(categories);
        }
    }

    private record RemoteCatalog(String id, TrunkedSystemDetails system, List<RemoteTalkgroup> talkgroups,
                                 List<RemoteTalkgroupCategory> categories)
    {
        private RemoteCatalog
        {
            talkgroups = List.copyOf(talkgroups);
            categories = List.copyOf(categories);
        }
    }

    public record TalkgroupImportPreview(String previewId, long expiresAtEpochMs, long aliasListId,
                                         String aliasListName, long revision, boolean all, int rowCount,
                                         boolean rowsTruncated, Map<String,Long> counts, List<TalkgroupRow> rows)
    {
        public TalkgroupImportPreview
        {
            counts = Map.copyOf(counts);
            rows = List.copyOf(rows);
        }
    }

    public record TalkgroupImportResult(long revision, Long aliasListId, List<Long> aliasIds, int added,
                                        int updated, int unchanged)
    {
        public TalkgroupImportResult
        {
            aliasIds = List.copyOf(aliasIds);
        }
    }

    public static final class PreviewNotFoundException extends RuntimeException
    {
        public PreviewNotFoundException()
        {
            super("RadioReference preview was not found, expired, or already applied");
        }
    }

    interface DirectoryAccess
    {
        default BoundedPage<RadioReferenceDirectoryService.FrequencyMatch> searchStateFrequencies(int stateId,
            long frequencyHz, int offset, int limit) throws RadioReferenceDirectoryException
        { return new BoundedPage<>(List.of(), offset, null, 0); }
        default RadioReferenceDirectoryService.FrequencyDetails frequencyDetails(long frequencyHz, Integer systemId,
            int siteNumber, int subCategoryId, int agencyId, int countyId, String rawMode)
            throws RadioReferenceDirectoryException
        { return new RadioReferenceDirectoryService.FrequencyDetails(rawMode, "", "", null); }
        default BoundedPage<RadioReferenceDirectoryService.FrequencyMatch> searchStateFrequencies(int stateId,
            long frequencyHz, int offset, int limit, Duration remainingBudget) throws RadioReferenceDirectoryException
        { return searchStateFrequencies(stateId, frequencyHz, offset, limit); }
        default RadioReferenceDirectoryService.FrequencyDetails frequencyDetails(long frequencyHz, Integer systemId,
            int siteNumber, int subCategoryId, int agencyId, int countyId, String rawMode, Duration remainingBudget)
            throws RadioReferenceDirectoryException
        { return frequencyDetails(frequencyHz, systemId, siteNumber, subCategoryId, agencyId, countyId, rawMode); }
        default TrunkedSystemDetails trunkedSystemDetails(int systemId, Duration remainingBudget)
            throws RadioReferenceDirectoryException { return trunkedSystemDetails(systemId); }
        default List<TrunkedSiteDetails> allTrunkedSites(int systemId, Duration remainingBudget)
            throws RadioReferenceDirectoryException { return allTrunkedSites(systemId); }
        default void requireCatalogSession() throws RadioReferenceDirectoryException {}
        default long sessionGeneration() { return 0; }
        default RadioReferenceDiscoveryResolver.Result resolveP25Identity(Integer stateId,
            RadioReferenceDiscoveryResolver.Identity identity)
        {
            return RadioReferenceDiscoveryResolver.Result.manual("unavailable", "RadioReference lookup is unavailable.");
        }
        TrunkedSystemDetails trunkedSystemDetails(int systemId) throws RadioReferenceDirectoryException;
        List<TrunkedSiteDetails> allTrunkedSites(int systemId)
            throws RadioReferenceDirectoryException;
        BoundedPage<RemoteTalkgroup> talkgroups(int systemId, Integer categoryId, String search, int offset, int limit)
            throws RadioReferenceDirectoryException;
        List<RemoteTalkgroup> allTalkgroups(int systemId) throws RadioReferenceDirectoryException;
        BoundedPage<RemoteTalkgroupCategory> talkgroupCategories(int systemId, int offset, int limit)
            throws RadioReferenceDirectoryException;
        List<RemoteTalkgroupCategory> allTalkgroupCategories(int systemId)
            throws RadioReferenceDirectoryException;
        List<ConventionalFrequency> conventionalFrequenciesById(int subCategoryId, Collection<Integer> ids)
            throws RadioReferenceDirectoryException;
        RadioReferenceChannelNames conventionalNames(RadioReferenceGateway.DetailKind ownerKind, int ownerId,
            int subCategoryId, ConventionalFrequency frequency) throws RadioReferenceDirectoryException;
    }

    record DirectoryAdapter(RadioReferenceDirectoryService service) implements DirectoryAccess
    {
        DirectoryAdapter
        {
            Objects.requireNonNull(service);
        }

        @Override public BoundedPage<RadioReferenceDirectoryService.FrequencyMatch> searchStateFrequencies(int stateId,
            long frequencyHz, int offset, int limit) throws RadioReferenceDirectoryException
        { return service.searchStateFrequencies(stateId, frequencyHz, offset, limit); }
        @Override public RadioReferenceDirectoryService.FrequencyDetails frequencyDetails(long frequencyHz,
            Integer systemId, int siteNumber, int subCategoryId, int agencyId, int countyId, String rawMode)
            throws RadioReferenceDirectoryException
        { return service.frequencyDetails(frequencyHz, systemId, siteNumber, subCategoryId, agencyId, countyId, rawMode); }
        @Override public BoundedPage<RadioReferenceDirectoryService.FrequencyMatch> searchStateFrequencies(int stateId,
            long frequencyHz, int offset, int limit, Duration remainingBudget) throws RadioReferenceDirectoryException
        { return service.searchStateFrequencies(stateId, frequencyHz, offset, limit, remainingBudget); }
        @Override public RadioReferenceDirectoryService.FrequencyDetails frequencyDetails(long frequencyHz,
            Integer systemId, int siteNumber, int subCategoryId, int agencyId, int countyId, String rawMode,
            Duration remainingBudget) throws RadioReferenceDirectoryException
        { return service.frequencyDetails(frequencyHz, systemId, siteNumber, subCategoryId, agencyId, countyId, rawMode,
            remainingBudget); }
        @Override public TrunkedSystemDetails trunkedSystemDetails(int systemId, Duration remainingBudget)
            throws RadioReferenceDirectoryException { return service.trunkedSystemDetails(systemId, remainingBudget); }
        @Override public List<TrunkedSiteDetails> allTrunkedSites(int systemId, Duration remainingBudget)
            throws RadioReferenceDirectoryException { return service.allTrunkedSites(systemId, remainingBudget); }
        @Override public long sessionGeneration() { return service.sessionGeneration(); }

        @Override public void requireCatalogSession() throws RadioReferenceDirectoryException
        {
            RadioReferenceDirectoryService.AccountState state = service.status().state();
            if(state != RadioReferenceDirectoryService.AccountState.VALID_PREMIUM)
                throw new RadioReferenceDirectoryException(
                    state == RadioReferenceDirectoryService.AccountState.EXPIRED_PREMIUM ?
                    RadioReferenceDirectoryException.Code.PREMIUM_REQUIRED :
                    RadioReferenceDirectoryException.Code.NOT_AUTHENTICATED);
        }

        @Override public TrunkedSystemDetails trunkedSystemDetails(int systemId)
            throws RadioReferenceDirectoryException { return service.trunkedSystemDetails(systemId); }
        @Override public RadioReferenceDiscoveryResolver.Result resolveP25Identity(Integer stateId,
            RadioReferenceDiscoveryResolver.Identity identity)
        {
            return new RadioReferenceDiscoveryResolver(service).resolve(stateId, identity);
        }
        @Override public List<TrunkedSiteDetails> allTrunkedSites(int systemId)
            throws RadioReferenceDirectoryException { return service.allTrunkedSites(systemId); }
        @Override public BoundedPage<RemoteTalkgroup> talkgroups(int systemId, Integer categoryId, String search,
            int offset, int limit) throws RadioReferenceDirectoryException
            { return service.talkgroups(systemId, categoryId, search, offset, limit); }
        @Override public List<RemoteTalkgroup> allTalkgroups(int systemId)
            throws RadioReferenceDirectoryException { return service.allTalkgroups(systemId); }
        @Override public BoundedPage<RemoteTalkgroupCategory> talkgroupCategories(int systemId, int offset, int limit)
            throws RadioReferenceDirectoryException { return service.talkgroupCategories(systemId, offset, limit); }
        @Override public List<RemoteTalkgroupCategory> allTalkgroupCategories(int systemId)
            throws RadioReferenceDirectoryException { return service.allTalkgroupCategories(systemId); }
        @Override public List<ConventionalFrequency> conventionalFrequenciesById(int subCategoryId,
            Collection<Integer> ids) throws RadioReferenceDirectoryException
            { return service.conventionalFrequenciesById(subCategoryId, ids); }
        @Override public RadioReferenceChannelNames conventionalNames(RadioReferenceGateway.DetailKind ownerKind,
            int ownerId, int subCategoryId, ConventionalFrequency frequency) throws RadioReferenceDirectoryException
            { return service.conventionalNames(ownerKind, ownerId, subCategoryId, frequency); }
    }

    private record LocalChannelSnapshot(ChannelAdministrationService.Options options,
                                        ChannelAdministrationService.Catalog catalog) {}
    private record ExistingChannel(String configurationId) {}
    private record DecoderPlan(Protocol protocol, DecoderType decoderType, String protocolId, boolean hybrid,
                               String reason)
    {
        private boolean supported() { return protocol != Protocol.UNKNOWN && decoderType != null; }
    }
    private record PreparedAliases(List<AliasImportService.Input> inputs, Map<Integer,String> categoriesById) {}
    private record AliasRows(long revision, List<TalkgroupRow> items) {}
    private record PendingChannel(long revision, ChannelAction action, String existingConfigurationId,
                                  ChannelDefinition channel, long expiresAtEpochMs) {}
    private record PendingEnrichment(long revision, String configurationId, P25SiteIdentity identity, String system,
                                      String site, String name, ChannelDefinition channel, long generation,
                                      long directoryGeneration, long expiresAtEpochMs) {}
    private record PendingNameLookup(String configurationId, long revision, ChannelDefinition channel,
        List<EnrichmentCandidate> candidates, P25SiteIdentity identity, long generation, long directoryGeneration,
        long expiresAtEpochMs) {}
    private record PendingTalkgroups(AliasImportService.Plan plan, AliasImportService.Preview preview,
                                     long expiresAtEpochMs) {}
}
