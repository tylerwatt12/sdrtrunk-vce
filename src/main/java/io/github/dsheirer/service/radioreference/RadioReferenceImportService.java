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
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
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
    private static final int MAXIMUM_TALKGROUP_PREVIEW_ROWS = 500;

    private final DirectoryAccess mDirectory;
    private final ChannelAdministrationService mChannels;
    private final AliasAdministrationService mAliases;
    private final AliasImportService mAliasImporter;
    private final Clock mClock;
    private final long mPreviewLifetimeMillis;
    private final Map<String,PendingChannel> mPendingChannels = new HashMap<>();
    private final Map<String,PendingTalkgroups> mPendingTalkgroups = new HashMap<>();

    public RadioReferenceImportService(RadioReferenceDirectoryService directory,
                                       ConfigurationManager configurationManager)
    {
        this(new DirectoryAdapter(directory), configurationManager.getChannelAdministrationService(),
            configurationManager.getAliasAdministrationService(), Clock.systemUTC(), DEFAULT_PREVIEW_LIFETIME);
    }

    RadioReferenceImportService(DirectoryAccess directory, ChannelAdministrationService channels,
                                AliasAdministrationService aliases, Clock clock, Duration previewLifetime)
    {
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
        List<Long> frequencies = selectSiteFrequencies(site, frequencySet, request.selectedFrequenciesHz());
        List<Long> allFrequencies = allSiteFrequencies(site);
        long minimum = allFrequencies.stream().mapToLong(Long::longValue).min().orElseThrow();
        long maximum = allFrequencies.stream().mapToLong(Long::longValue).max().orElseThrow();
        String systemName = fallback(request.systemName(), system.name());
        String siteName = fallback(request.siteName(), site.name());
        String channelName = fallback(request.channelName(), "Control");
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
        String protocolId = conventionalProtocol(frequency.mode());
        LocalChannelSnapshot local = localSnapshot();
        long aliasListId = defaultAliasList(protocolId, local.options());
        ChannelDefinition template = mChannels.template(protocolId);
        Map<String,Object> settings = new LinkedHashMap<>(template.settings());
        applyConventionalSettings(protocolId, frequency.mode(), settings);
        String name = fallback(request.channelName(), firstNonBlank(frequency.alphaTag(), frequency.description(),
            Long.toString(frequency.downlinkHz())));
        ChannelDefinition created = new ChannelDefinition(null, protocolId, textOrNull(request.systemName()),
            textOrNull(request.siteName()), name, null, aliasListId,
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
            mChannels.create(pending.channel(), pending.revision()) :
            mChannels.update(pending.existingConfigurationId(), pending.channel(), pending.revision());
        return new ChannelImportResult(result.revision(), pending.action(),
            result.configurationIds().getFirst());
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

    /** Annotates the bounded catalog once so browser searches do not repeat an upstream request. */
    public TalkgroupPage talkgroupCatalog(int systemId, long aliasListId)
        throws RadioReferenceDirectoryException
    {
        TrunkedSystemDetails system = requireSystem(positive(systemId, "system_id"));
        DecoderPlan decoder = systemDecoder(system);
        requireSupported(decoder);
        requireCompatibleTalkgroupList(aliasListId, decoder.decoderType());
        List<RemoteTalkgroup> catalog = mDirectory.allTalkgroups(systemId);
        List<RemoteTalkgroupCategory> categories = optionalCategories(systemId);
        AliasRows rows = aliasRows(aliasListId, decoder.protocol(), catalog, categories);
        return new TalkgroupPage(rows.revision(), rows.items(), 0, null, rows.items().size(), categories);
    }

    /** Builds one revision-bound Alias import plan for selected talkgroups or the whole system. */
    public TalkgroupImportPreview previewTalkgroups(TalkgroupImportRequest request)
        throws RadioReferenceDirectoryException
    {
        Objects.requireNonNull(request, "Talkgroup import request cannot be null");
        int systemId = positive(request.systemId(), "system_id");
        TrunkedSystemDetails system = requireSystem(systemId);
        DecoderPlan decoder = systemDecoder(system);
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
            remote = mDirectory.allTalkgroups(systemId);
        }
        else
        {
            Set<Integer> selected = boundedPositiveIds(request.talkgroupIds(), "talkgroup_ids");
            remote = mDirectory.talkgroupsById(systemId, selected);
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

        List<RemoteTalkgroupCategory> categories = optionalCategories(systemId);
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
        List<TalkgroupRow> rows = annotatedRows(remote, prepared.categoriesById(), aliasPreview.rows());
        int rowCount = rows.size();
        List<TalkgroupRow> displayed = rows.subList(0, Math.min(rowCount, MAXIMUM_TALKGROUP_PREVIEW_ROWS));
        return storeTalkgroups(options.aliasList().getId(), aliasPreview, plan, request.all(), rowCount, displayed);
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
                settings.put("channel_mode", "TRUNKED");
                frequencyMap = frequencyMap(site, false);
            }
            case NXDN -> {
                settings.put("channel_mode", "TRUNKED");
                settings.put("transmission_mode", nxdnTransmissionMode(system.flavor()));
                frequencyMap = frequencyMap(site, true);
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
        return new AliasRows(preview.revision(), annotatedRows(remote, prepared.categoriesById(), preview.rows()));
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

    private static List<TalkgroupRow> annotatedRows(List<RemoteTalkgroup> remote,
                                                    Map<Integer,String> categories,
                                                    List<AliasImportService.Row> rows)
    {
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
                row.changes()));
        }
        return List.copyOf(result);
    }

    private List<RemoteTalkgroupCategory> optionalCategories(int systemId)
        throws RadioReferenceDirectoryException
    {
        List<RemoteTalkgroupCategory> result = new ArrayList<>();
        int offset = 0;
        try
        {
            while(true)
            {
                BoundedPage<RemoteTalkgroupCategory> page = mDirectory.talkgroupCategories(systemId, offset,
                    RadioReferenceDirectoryService.MAXIMUM_RESULT_LIMIT);
                result.addAll(page.items());
                if(result.size() > AliasAdministrationService.MAX_BULK_ALIASES)
                {
                    throw new IllegalArgumentException("RadioReference returned too many talkgroup categories");
                }
                if(page.nextOffset() == null)
                {
                    return List.copyOf(result);
                }
                offset = page.nextOffset();
            }
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

    private TrunkedSystemDetails requireSystem(int systemId) throws RadioReferenceDirectoryException
    {
        TrunkedSystemDetails system = mDirectory.trunkedSystemDetails(systemId);
        if(system == null || system.id() != systemId)
        {
            throw new IllegalArgumentException("RadioReference system was not found");
        }
        return system;
    }

    private TrunkedSiteDetails requireSite(int systemId, int siteId) throws RadioReferenceDirectoryException
    {
        int offset = 0;
        while(true)
        {
            BoundedPage<TrunkedSiteDetails> page = mDirectory.trunkedSites(systemId, offset,
                RadioReferenceDirectoryService.MAXIMUM_RESULT_LIMIT);
            TrunkedSiteDetails match = page.items().stream().filter(site -> site.id() == siteId).findFirst()
                .orElse(null);
            if(match != null)
            {
                return match;
            }
            if(page.nextOffset() == null)
            {
                throw new IllegalArgumentException("RadioReference site was not found");
            }
            offset = page.nextOffset();
        }
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
    }

    private void requirePreviewCapacity()
    {
        if(mPendingChannels.size() + mPendingTalkgroups.size() >= MAXIMUM_PENDING_PREVIEWS)
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
        return switch(text(flavor))
        {
            case "NEXEDGE 9600", "Conventional Networked" -> "M9600";
            case "Icom IDAS Type D", "Kenwood Type D" -> "TYPE_D";
            default -> "M4800";
        };
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

    public record ConventionalImportRequest(int subCategoryId, int frequencyId, String systemName, String siteName,
                                            String channelName)
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

    public record TalkgroupImportRequest(int systemId, long aliasListId, boolean all, List<Integer> talkgroupIds)
    {
        public TalkgroupImportRequest
        {
            talkgroupIds = talkgroupIds == null ? List.of() : List.copyOf(talkgroupIds);
        }
    }

    public record TalkgroupRow(RemoteTalkgroup talkgroup, String category, TalkgroupStatus status,
                               Long existingAliasId, List<AliasImportService.Change> changes)
    {
        public TalkgroupRow
        {
            changes = List.copyOf(changes);
        }
    }

    public record TalkgroupPage(long revision, List<TalkgroupRow> items, int offset, Integer nextOffset,
                                int totalItems, List<RemoteTalkgroupCategory> categories)
    {
        public TalkgroupPage
        {
            items = List.copyOf(items);
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
        TrunkedSystemDetails trunkedSystemDetails(int systemId) throws RadioReferenceDirectoryException;
        BoundedPage<TrunkedSiteDetails> trunkedSites(int systemId, int offset, int limit)
            throws RadioReferenceDirectoryException;
        BoundedPage<RemoteTalkgroup> talkgroups(int systemId, Integer categoryId, String search, int offset, int limit)
            throws RadioReferenceDirectoryException;
        List<RemoteTalkgroup> talkgroupsById(int systemId, Collection<Integer> ids)
            throws RadioReferenceDirectoryException;
        List<RemoteTalkgroup> allTalkgroups(int systemId) throws RadioReferenceDirectoryException;
        BoundedPage<RemoteTalkgroupCategory> talkgroupCategories(int systemId, int offset, int limit)
            throws RadioReferenceDirectoryException;
        List<ConventionalFrequency> conventionalFrequenciesById(int subCategoryId, Collection<Integer> ids)
            throws RadioReferenceDirectoryException;
    }

    private record DirectoryAdapter(RadioReferenceDirectoryService service) implements DirectoryAccess
    {
        private DirectoryAdapter
        {
            Objects.requireNonNull(service);
        }

        @Override public TrunkedSystemDetails trunkedSystemDetails(int systemId)
            throws RadioReferenceDirectoryException { return service.trunkedSystemDetails(systemId); }
        @Override public BoundedPage<TrunkedSiteDetails> trunkedSites(int systemId, int offset, int limit)
            throws RadioReferenceDirectoryException { return service.trunkedSites(systemId, offset, limit); }
        @Override public BoundedPage<RemoteTalkgroup> talkgroups(int systemId, Integer categoryId, String search,
            int offset, int limit) throws RadioReferenceDirectoryException
            { return service.talkgroups(systemId, categoryId, search, offset, limit); }
        @Override public List<RemoteTalkgroup> talkgroupsById(int systemId, Collection<Integer> ids)
            throws RadioReferenceDirectoryException { return service.talkgroupsById(systemId, ids); }
        @Override public List<RemoteTalkgroup> allTalkgroups(int systemId)
            throws RadioReferenceDirectoryException { return service.allTalkgroups(systemId); }
        @Override public BoundedPage<RemoteTalkgroupCategory> talkgroupCategories(int systemId, int offset, int limit)
            throws RadioReferenceDirectoryException { return service.talkgroupCategories(systemId, offset, limit); }
        @Override public List<ConventionalFrequency> conventionalFrequenciesById(int subCategoryId,
            Collection<Integer> ids) throws RadioReferenceDirectoryException
            { return service.conventionalFrequenciesById(subCategoryId, ids); }
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
    private record PendingTalkgroups(AliasImportService.Plan plan, AliasImportService.Preview preview,
                                     long expiresAtEpochMs) {}
}
