/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.service.radioreference;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasAdministrationService;
import io.github.dsheirer.alias.AliasFactory;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasMatchRegistry;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.decode.DecoderFactory;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.config.DecodeConfiguration;
import io.github.dsheirer.module.decode.dmr.DMRChannelMode;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.dmr.channel.TimeslotFrequency;
import io.github.dsheirer.module.decode.nbfm.DecodeConfigNBFM;
import io.github.dsheirer.module.decode.nxdn.DecodeConfigNXDN;
import io.github.dsheirer.module.decode.nxdn.NXDNChannelMode;
import io.github.dsheirer.module.decode.nxdn.channel.ChannelFrequency;
import io.github.dsheirer.module.decode.nxdn.layer3.type.TransmissionMode;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.decode.p25.phase1.Modulation;
import io.github.dsheirer.module.decode.p25.phase2.DecodeConfigP25Phase2;
import io.github.dsheirer.module.decode.p25.phase2.enumeration.ScrambleParameters;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.service.radioreference.RadioReferenceDirectoryService.BoundedPage;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.ConventionalFrequency;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.RemoteTalkgroup;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.RemoteTalkgroupCategory;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.TrunkedSiteChannel;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.TrunkedSiteDetails;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.TrunkedSystemDetails;
import io.github.dsheirer.source.config.SourceConfigTuner;
import io.github.dsheirer.source.config.SourceConfigTunerMultipleFrequency;
import java.awt.GraphicsEnvironment;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * UI-neutral RadioReference preview/apply service.
 *
 * <p>Remote rows are always reloaded through {@link RadioReferenceDirectoryService} when a mutation is applied, so
 * the browser never supplies authoritative names, decoder types, frequencies, or talkgroup metadata.  All local
 * model access is serialized on the same configuration thread used by the desktop editors.  No database or remote
 * work runs on a receiver or decoder callback.</p>
 */
public final class RadioReferenceImportService
{
    private static final Logger mLog = LoggerFactory.getLogger(RadioReferenceImportService.class);
    public static final int MAXIMUM_IMPORT_ITEMS = 500;
    private static final long CONFIGURATION_TIMEOUT_SECONDS = 15;
    private static final DesktopConfigurationDispatcher JAVAFX_CONFIGURATION_DISPATCHER =
        new DesktopConfigurationDispatcher()
        {
            @Override
            public boolean isDispatchThread()
            {
                return Platform.isFxApplicationThread();
            }

            @Override
            public void dispatch(Runnable task)
            {
                Platform.runLater(task);
            }
        };

    private final RadioReferenceDirectoryService mDirectory;
    private final ConfigurationManager mConfigurationManager;
    private final AliasAdministrationService mAliasAdministrationService;
    private final boolean mUseDesktopThread;
    private final DesktopConfigurationDispatcher mDesktopConfigurationDispatcher;
    private final long mConfigurationTimeoutNanos;

    public RadioReferenceImportService(RadioReferenceDirectoryService directory,
                                       ConfigurationManager configurationManager)
    {
        this(directory, configurationManager, !GraphicsEnvironment.isHeadless());
    }

    RadioReferenceImportService(RadioReferenceDirectoryService directory,
                                ConfigurationManager configurationManager, boolean useDesktopThread)
    {
        this(directory, configurationManager, configurationManager.getAliasAdministrationService(),
            useDesktopThread);
    }

    RadioReferenceImportService(RadioReferenceDirectoryService directory,
                                ConfigurationManager configurationManager,
                                AliasAdministrationService aliasAdministrationService,
                                boolean useDesktopThread)
    {
        this(directory, configurationManager, aliasAdministrationService, useDesktopThread,
            JAVAFX_CONFIGURATION_DISPATCHER, Duration.ofSeconds(CONFIGURATION_TIMEOUT_SECONDS));
    }

    RadioReferenceImportService(RadioReferenceDirectoryService directory,
                                ConfigurationManager configurationManager,
                                AliasAdministrationService aliasAdministrationService,
                                DesktopConfigurationDispatcher desktopConfigurationDispatcher,
                                Duration configurationTimeout)
    {
        this(directory, configurationManager, aliasAdministrationService, true,
            desktopConfigurationDispatcher, configurationTimeout);
    }

    private RadioReferenceImportService(RadioReferenceDirectoryService directory,
                                        ConfigurationManager configurationManager,
                                        AliasAdministrationService aliasAdministrationService,
                                        boolean useDesktopThread,
                                        DesktopConfigurationDispatcher desktopConfigurationDispatcher,
                                        Duration configurationTimeout)
    {
        mDirectory = Objects.requireNonNull(directory);
        mConfigurationManager = Objects.requireNonNull(configurationManager);
        mAliasAdministrationService = Objects.requireNonNull(aliasAdministrationService);
        mUseDesktopThread = useDesktopThread;
        mDesktopConfigurationDispatcher = Objects.requireNonNull(desktopConfigurationDispatcher);
        Duration timeout = Objects.requireNonNull(configurationTimeout);
        if(timeout.isZero() || timeout.isNegative())
        {
            throw new IllegalArgumentException("Configuration timeout must be positive");
        }
        mConfigurationTimeoutNanos = timeout.toNanos();
    }

    /** Describes the supported decoder choice without changing local configuration. */
    public SystemPreview systemPreview(int systemId) throws RadioReferenceDirectoryException
    {
        TrunkedSystemDetails system = mDirectory.trunkedSystemDetails(systemId);
        DecoderPlan plan = systemPlan(system);
        return new SystemPreview(system, plan.decoderType(), plan.protocol(), plan.supported(), plan.reason());
    }

    /** Describes one site and the safe default control-channel import selection. */
    public SitePreview sitePreview(int systemId, int siteId) throws RadioReferenceDirectoryException
    {
        TrunkedSystemDetails system = mDirectory.trunkedSystemDetails(systemId);
        TrunkedSiteDetails site = requireSite(systemId, siteId);
        DecoderPlan plan = sitePlan(system, site, null);
        List<Long> controlFrequencies = controlFrequencies(site);
        return new SitePreview(system, site, plan.decoderType(), plan.protocol(), plan.supported(),
            plan.decoderType() == DecoderType.P25_PHASE1, controlFrequencies, plan.reason());
    }

    /** Creates one trunked control-channel configuration from a freshly reloaded site. */
    public ChannelImportResult importSiteChannel(SiteChannelImport request)
        throws RadioReferenceDirectoryException
    {
        Objects.requireNonNull(request, "Site import request cannot be null");
        TrunkedSystemDetails system = mDirectory.trunkedSystemDetails(positive(request.systemId(), "system_id"));
        TrunkedSiteDetails site = requireSite(system.id(), positive(request.siteId(), "site_id"));
        DecoderPlan plan = sitePlan(system, site, request.decoderType());

        if(!plan.supported())
        {
            throw new IllegalArgumentException(plan.reason());
        }

        List<Long> frequencies = selectedSiteFrequencies(site, request.frequencyHz());
        Modulation modulation = modulation(plan.decoderType(), request.p25Modulation());
        return onConfigurationThread(() -> {
            AliasListDefinition aliasList = resolveAliasList(request.aliasListId(), plan.decoderType());
            Channel channel = new Channel();
            channel.setSystem(nonBlank(request.systemName(), system.name()));
            channel.setSite(nonBlank(request.siteName(), site.name()));
            channel.setName(nonBlank(request.channelName(), site.name()));
            if(aliasList != null)
            {
                channel.setAliasListName(aliasList.getName());
            }
            channel.setDecodeConfiguration(createTrunkedDecodeConfiguration(plan.decoderType(), modulation,
                system, site));
            channel.setSourceConfiguration(sourceConfiguration(frequencies, site.channels()));
            addChannels(List.of(channel));
            return new ChannelImportResult(List.of(channel.getRadresGuid()), 1);
        });
    }

    /**
     * Returns a page of remote talkgroups annotated against one exact local Alias List.  The status comparison only
     * covers RadioReference-owned fields; local appearance, actions, recording, playback and streaming settings are
     * deliberately ignored.
     */
    public TalkgroupPreviewPage talkgroupPreview(int systemId, long aliasListId, Integer categoryId, String search,
                                                 int offset, int limit) throws RadioReferenceDirectoryException
    {
        TrunkedSystemDetails system = mDirectory.trunkedSystemDetails(systemId);
        DecoderPlan plan = systemPlan(system);

        if(!plan.supported())
        {
            throw new IllegalArgumentException(plan.reason());
        }

        BoundedPage<RemoteTalkgroup> page = mDirectory.talkgroups(systemId, categoryId, search, offset, limit);
        List<RemoteTalkgroupCategory> categories = loadAllCategories(systemId);
        Map<Integer,String> categoryNames = new LinkedHashMap<>();
        categories.forEach(category -> categoryNames.put(category.id(), category.name()));
        return onConfigurationThread(() -> {
            AliasListDefinition definition = resolveAliasList(aliasListId, plan.decoderType());
            List<TalkgroupPreview> items = page.items().stream()
                .map(talkgroup -> preview(definition, plan.protocol(), talkgroup,
                    categoryNames.get(talkgroup.categoryId()), categoryNames.containsKey(talkgroup.categoryId())))
                .toList();
            return new TalkgroupPreviewPage(items, page.offset(), page.nextOffset(), page.totalItems(),
                mAliasAdministrationService.currentRevision(), categories);
        });
    }

    /** Applies a revision-checked, bounded talkgroup add/update selection. */
    public TalkgroupImportResult importTalkgroups(TalkgroupImport request)
        throws RadioReferenceDirectoryException
    {
        Objects.requireNonNull(request, "Talkgroup import request cannot be null");
        int systemId = positive(request.systemId(), "system_id");
        Set<Integer> ids = boundedPositiveIds(request.talkgroupIds(), "talkgroup_ids");
        TrunkedSystemDetails system = mDirectory.trunkedSystemDetails(systemId);
        DecoderPlan plan = systemPlan(system);
        if(!plan.supported())
        {
            throw new IllegalArgumentException(plan.reason());
        }
        List<RemoteTalkgroup> remote = mDirectory.talkgroupsById(systemId, ids);
        Map<Integer,String> categories = new LinkedHashMap<>();
        loadAllCategories(systemId).forEach(category -> categories.put(category.id(), category.name()));
        PreparedTalkgroupImport prepared = onConfigurationThread(() -> prepareTalkgroupImport(
            request.aliasListId(), plan, remote, categories));

        if(prepared.updated() > 0 && !request.confirmUpdates())
        {
            throw new ConfirmationRequiredException();
        }

        if(prepared.changes().isEmpty())
        {
            long current = mAliasAdministrationService.currentRevision();
            if(current != request.revision())
            {
                throw new AliasAdministrationService.StaleRevisionException(request.revision(), current);
            }
            return new TalkgroupImportResult(current, 0, 0, prepared.identical());
        }

        AliasAdministrationService.MutationResult result =
            mAliasAdministrationService.saveAliases(prepared.changes(), request.revision());
        return new TalkgroupImportResult(result.revision(), prepared.added(), prepared.updated(),
            prepared.identical());
    }

    /** Creates conventional channel configurations for freshly reloaded frequency rows. */
    public ChannelImportResult importConventional(ConventionalImport request)
        throws RadioReferenceDirectoryException
    {
        Objects.requireNonNull(request, "Conventional import request cannot be null");
        int subCategoryId = positive(request.subCategoryId(), "sub_category_id");
        Set<Integer> selectedIds = boundedPositiveIds(request.frequencyIds(), "frequency_ids");
        List<ConventionalFrequency> selected = mDirectory.conventionalFrequenciesById(subCategoryId, selectedIds);
        if(selected.size() != selectedIds.size())
        {
            throw new IllegalArgumentException("One or more selected RadioReference frequencies are unavailable");
        }
        return onConfigurationThread(() -> {
            List<Channel> channels = new ArrayList<>();
            for(ConventionalFrequency frequency: selected)
            {
                DecoderType decoderType = conventionalDecoder(frequency.mode());
                if(decoderType == null)
                {
                    throw new IllegalArgumentException("RadioReference mode [" + frequency.mode() +
                        "] is not supported");
                }
                AliasListDefinition definition = resolveAliasList(request.aliasListId(), decoderType);
                Channel channel = new Channel();
                channel.setSystem(text(request.systemName()));
                channel.setSite(text(request.siteName()));
                channel.setName(firstNonBlank(frequency.alphaTag(), frequency.description(),
                    Long.toString(frequency.downlinkHz())));
                if(definition != null)
                {
                    channel.setAliasListName(definition.getName());
                }
                channel.setDecodeConfiguration(createConventionalDecodeConfiguration(decoderType,
                    frequency.mode()));
                SourceConfigTuner source = new SourceConfigTuner();
                source.setFrequency(frequency.downlinkHz());
                channel.setSourceConfiguration(source);
                channels.add(channel);
            }
            addChannels(channels);
            return new ChannelImportResult(channels.stream().map(Channel::getRadresGuid).toList(), channels.size());
        });
    }

    private PreparedTalkgroupImport prepareTalkgroupImport(long aliasListId, DecoderPlan plan,
                                                            List<RemoteTalkgroup> remote,
                                                            Map<Integer,String> categories)
    {
        AliasListDefinition definition = resolveAliasList(aliasListId, plan.decoderType());
        List<Alias> changes = new ArrayList<>();
        int added = 0;
        int updated = 0;
        int identical = 0;

        for(RemoteTalkgroup talkgroup: remote)
        {
            String group = categories.get(talkgroup.categoryId());
            boolean groupAvailable = categories.containsKey(talkgroup.categoryId());
            Alias existing = findExactAlias(definition.getId(), plan.protocol(), talkgroup.value());
            ImportStatus status = importStatus(existing, talkgroup, group, groupAvailable);
            switch(status)
            {
                case NOT_PRESENT -> {
                    Alias alias = new Alias(talkgroupName(talkgroup));
                    alias.setAliasListDefinition(definition);
                    alias.setDescription(blankToNull(talkgroup.description()));
                    alias.setGroup(blankToNull(group));
                    alias.setMatchIdentifier(new io.github.dsheirer.alias.id.talkgroup.Talkgroup(plan.protocol(),
                        talkgroup.value()));
                    changes.add(alias);
                    added++;
                }
                case DIFFERENT -> {
                    Alias replacement = AliasFactory.copyOf(existing);
                    replacement.setId(existing.getId());
                    replacement.setName(talkgroupName(talkgroup));
                    replacement.setDescription(blankToNull(talkgroup.description()));
                    if(groupAvailable)
                    {
                        replacement.setGroup(blankToNull(group));
                    }
                    changes.add(replacement);
                    updated++;
                }
                case IDENTICAL -> identical++;
            }
        }

        return new PreparedTalkgroupImport(List.copyOf(changes), added, updated, identical);
    }

    private TalkgroupPreview preview(AliasListDefinition definition, Protocol protocol, RemoteTalkgroup talkgroup,
                                     String category, boolean categoryAvailable)
    {
        Alias existing = findExactAlias(definition.getId(), protocol, talkgroup.value());
        ImportStatus status = importStatus(existing, talkgroup, category, categoryAvailable);
        List<FieldChange> changes = new ArrayList<>();
        if(existing != null)
        {
            addChange(changes, "name", existing.getName(), talkgroupName(talkgroup));
            addChange(changes, "description", existing.getDescription(), talkgroup.description());
            if(categoryAvailable)
            {
                addChange(changes, "group", existing.getGroup(), category);
            }
        }
        return new TalkgroupPreview(talkgroup, category, status, existing != null ? existing.getId() : null,
            List.copyOf(changes));
    }

    private Alias findExactAlias(long aliasListId, Protocol protocol, int value)
    {
        return mConfigurationManager.getAliasModel().getAliases().stream()
            .filter(alias -> alias.getAliasListId() == aliasListId)
            .filter(alias -> alias.getMatchIdentifier() instanceof io.github.dsheirer.alias.id.talkgroup.Talkgroup)
            .filter(alias -> {
                io.github.dsheirer.alias.id.talkgroup.Talkgroup id =
                    (io.github.dsheirer.alias.id.talkgroup.Talkgroup)alias.getMatchIdentifier();
                return id.getProtocol() == protocol && id.getValue() == value;
            }).findFirst().orElse(null);
    }

    private static ImportStatus importStatus(Alias existing, RemoteTalkgroup talkgroup, String category,
                                             boolean categoryAvailable)
    {
        if(existing == null)
        {
            return ImportStatus.NOT_PRESENT;
        }
        return normalizedEquals(existing.getName(), talkgroupName(talkgroup)) &&
            normalizedEquals(existing.getDescription(), talkgroup.description()) &&
            (!categoryAvailable || normalizedEquals(existing.getGroup(), category)) ?
            ImportStatus.IDENTICAL : ImportStatus.DIFFERENT;
    }

    private static String talkgroupName(RemoteTalkgroup talkgroup)
    {
        return firstNonBlank(talkgroup.alphaTag(), Integer.toString(talkgroup.value()));
    }

    private AliasListDefinition resolveAliasList(Long aliasListId, DecoderType decoderType)
    {
        if(aliasListId == null)
        {
            return null;
        }
        return resolveAliasList(aliasListId.longValue(), decoderType);
    }

    private AliasListDefinition resolveAliasList(long aliasListId, DecoderType decoderType)
    {
        if(aliasListId <= 0)
        {
            throw new IllegalArgumentException("alias_list_id must be positive");
        }
        AliasListDefinition definition = mConfigurationManager.getAliasModel().aliasListDefinitions().stream()
            .filter(candidate -> candidate.getId() == aliasListId).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Alias List was not found"));
        if(!AliasMatchRegistry.isChannelCompatible(definition, decoderType))
        {
            throw new IllegalArgumentException("Alias List is not compatible with " + decoderType);
        }
        return definition;
    }

    private TrunkedSiteDetails requireSite(int systemId, int siteId) throws RadioReferenceDirectoryException
    {
        int offset = 0;
        do
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
                break;
            }
            offset = page.nextOffset();
        }
        while(true);
        throw new IllegalArgumentException("RadioReference site was not found");
    }

    private List<RemoteTalkgroupCategory> loadAllCategories(int systemId)
        throws RadioReferenceDirectoryException
    {
        List<RemoteTalkgroupCategory> categories = new ArrayList<>();
        int offset = 0;
        try
        {
            while(true)
            {
                BoundedPage<RemoteTalkgroupCategory> page = mDirectory.talkgroupCategories(systemId, offset,
                    RadioReferenceDirectoryService.MAXIMUM_RESULT_LIMIT);
                categories.addAll(page.items());
                if(categories.size() > MAXIMUM_IMPORT_ITEMS)
                {
                    throw new IllegalArgumentException("RadioReference category result is too large");
                }
                if(page.nextOffset() == null)
                {
                    return List.copyOf(categories);
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
                mLog.debug("Skipping optional RadioReference talkgroup category enrichment [{}]: {}",
                    exception.code(), exception.getMessage());
                return List.of();
            }

            throw exception;
        }
    }

    private static DecoderPlan systemPlan(TrunkedSystemDetails system)
    {
        String type = normalized(system.type());
        String flavor = normalized(system.flavor());
        String voice = normalized(system.voice());
        if(type.equals("dmr"))
        {
            return new DecoderPlan(Protocol.DMR, DecoderType.DMR, "");
        }
        if(type.equals("project 25"))
        {
            return new DecoderPlan(Protocol.APCO25,
                flavor.contains("phase ii") ? DecoderType.P25_PHASE2 : DecoderType.P25_PHASE1, "");
        }
        if(type.equals("motorola") && voice.contains("apco-25"))
        {
            return new DecoderPlan(Protocol.APCO25, DecoderType.P25_PHASE1, "");
        }
        if(type.equals("nxdn"))
        {
            return new DecoderPlan(Protocol.NXDN, DecoderType.NXDN, "");
        }
        return new DecoderPlan(Protocol.UNKNOWN, null,
            "RadioReference system type [" + text(system.type()) + "] is not supported");
    }

    private static DecoderPlan sitePlan(TrunkedSystemDetails system, TrunkedSiteDetails site,
                                        String requestedDecoder)
    {
        if(isHybridMotorolaP25(system))
        {
            return new DecoderPlan(Protocol.APCO25, null,
                "Motorola Type II control channels with P25 voice are not supported; " +
                    "P25 talkgroup aliases can still be imported");
        }

        DecoderPlan base = systemPlan(system);
        if(!base.supported())
        {
            return base;
        }
        DecoderType recommended = base.decoderType() == DecoderType.P25_PHASE2 && !site.tdmaControlChannel() ?
            DecoderType.P25_PHASE1 : base.decoderType();
        if(requestedDecoder == null || requestedDecoder.isBlank())
        {
            return new DecoderPlan(base.protocol(), recommended, "");
        }
        DecoderType requested;
        try
        {
            requested = DecoderType.valueOf(requestedDecoder.strip().toUpperCase(Locale.ROOT));
        }
        catch(IllegalArgumentException exception)
        {
            throw new IllegalArgumentException("decoder_type is invalid");
        }
        boolean allowed = requested == recommended || base.decoderType() == DecoderType.P25_PHASE2 &&
            (requested == DecoderType.P25_PHASE1 || requested == DecoderType.P25_PHASE2);
        if(!allowed)
        {
            throw new IllegalArgumentException("decoder_type is not compatible with this system");
        }
        return new DecoderPlan(base.protocol(), requested, "");
    }

    /**
     * Indicates a legacy Motorola Type II control channel that carries APCO-25 voice traffic.  The talkgroup
     * identities are P25-compatible, but the control channel itself cannot be decoded by a P25 control decoder.
     */
    private static boolean isHybridMotorolaP25(TrunkedSystemDetails system)
    {
        return normalized(system.type()).equals("motorola") && normalized(system.voice()).contains("apco-25");
    }

    private static Modulation modulation(DecoderType decoderType, String value)
    {
        if(decoderType != DecoderType.P25_PHASE1)
        {
            return null;
        }
        if(value == null)
        {
            throw new IllegalArgumentException("p25_modulation must be C4FM or CQPSK");
        }
        return switch(value.strip().toUpperCase(Locale.ROOT))
        {
            case "C4FM" -> Modulation.C4FM;
            case "CQPSK" -> Modulation.CQPSK;
            default -> throw new IllegalArgumentException("p25_modulation must be C4FM or CQPSK");
        };
    }

    private static DecodeConfiguration createTrunkedDecodeConfiguration(DecoderType decoderType,
                                                                         Modulation modulation,
                                                                         TrunkedSystemDetails system,
                                                                         TrunkedSiteDetails site)
    {
        return switch(decoderType)
        {
            case P25_PHASE1 -> {
                DecodeConfigP25Phase1 configuration = new DecodeConfigP25Phase1();
                configuration.setModulation(modulation);
                yield configuration;
            }
            case P25_PHASE2 -> {
                DecodeConfigP25Phase2 configuration = new DecodeConfigP25Phase2();
                configuration.setScrambleParameters(new ScrambleParameters(parseHex(system.wacn()),
                    parseHex(system.systemId()), parseHex(site.nac())));
                yield configuration;
            }
            case DMR -> {
                DecodeConfigDMR configuration = new DecodeConfigDMR();
                configuration.setChannelMode(DMRChannelMode.TRUNKED);
                List<TimeslotFrequency> map = new ArrayList<>();
                for(TrunkedSiteChannel siteChannel: site.channels())
                {
                    int channelNumber = siteChannelNumber(siteChannel);
                    if(channelNumber > 0)
                    {
                        TimeslotFrequency frequency = new TimeslotFrequency();
                        frequency.setNumber(channelNumber);
                        frequency.setDownlinkFrequency(siteChannel.frequencyHz());
                        map.add(frequency);
                    }
                }
                configuration.setTimeslotMap(map);
                yield configuration;
            }
            case NXDN -> {
                DecodeConfigNXDN configuration = new DecodeConfigNXDN(nxdnMode(system.flavor()));
                configuration.setChannelMode(NXDNChannelMode.TRUNKED);
                List<ChannelFrequency> map = new ArrayList<>();
                for(TrunkedSiteChannel siteChannel: site.channels())
                {
                    int channelNumber = siteChannelNumber(siteChannel);
                    if(channelNumber > 0)
                    {
                        map.add(new ChannelFrequency(channelNumber, siteChannel.frequencyHz(), 0));
                    }
                }
                configuration.setChannelMap(map);
                yield configuration;
            }
            default -> throw new IllegalArgumentException("Unsupported trunked decoder " + decoderType);
        };
    }

    /**
     * RadioReference uses channel ID as the authoritative DMR logical slot number or NXDN logical channel number
     * when it is parseable.  Older rows may omit that field or contain non-numeric text, in which case the dedicated
     * logical-channel-number field remains the fallback used by the desktop importer.
     */
    private static int siteChannelNumber(TrunkedSiteChannel siteChannel)
    {
        int channelNumber = siteChannel.logicalChannelNumber();
        if(siteChannel.channelId() != null)
        {
            try
            {
                channelNumber = Integer.parseInt(siteChannel.channelId());
            }
            catch(NumberFormatException exception)
            {
                //Keep the logical-channel-number fallback.
            }
        }
        return channelNumber;
    }

    private static DecodeConfiguration createConventionalDecodeConfiguration(DecoderType decoderType,
                                                                              String mode)
    {
        DecodeConfiguration configuration = DecoderFactory.getDecodeConfiguration(decoderType);
        if(configuration instanceof DecodeConfigNBFM nbfm && normalized(mode).equals("fm"))
        {
            nbfm.setBandwidth(DecodeConfigNBFM.Bandwidth.BW_25_0);
        }
        else if(configuration instanceof DecodeConfigDMR dmr)
        {
            dmr.setChannelMode(DMRChannelMode.CONVENTIONAL);
        }
        else if(configuration instanceof DecodeConfigNXDN nxdn)
        {
            nxdn.setChannelMode(NXDNChannelMode.CONVENTIONAL);
            nxdn.setTransmissionMode(normalized(mode).contains("96") ? TransmissionMode.M9600 :
                TransmissionMode.M4800);
        }
        return configuration;
    }

    private static DecoderType conventionalDecoder(String mode)
    {
        return switch(normalized(mode))
        {
            case "am" -> DecoderType.AM;
            case "fm", "fmn" -> DecoderType.NBFM;
            case "p25", "apco-25", "project 25", "project 25 phase i" -> DecoderType.P25_CONVENTIONAL;
            case "dmr" -> DecoderType.DMR;
            case "nxdn", "nxdn48", "nxdn96" -> DecoderType.NXDN;
            default -> null;
        };
    }

    private static SourceConfigTunerMultipleFrequency multipleSource(List<Long> frequencies,
                                                                     List<TrunkedSiteChannel> allChannels)
    {
        SourceConfigTunerMultipleFrequency source = new SourceConfigTunerMultipleFrequency();
        source.setFrequencies(frequencies);
        long minimum = allChannels.stream().mapToLong(TrunkedSiteChannel::frequencyHz).min()
            .orElse(frequencies.getFirst());
        long maximum = allChannels.stream().mapToLong(TrunkedSiteChannel::frequencyHz).max()
            .orElse(frequencies.getLast());
        source.setMinimumFrequency(minimum);
        source.setMaximumFrequency(maximum);
        return source;
    }

    private static io.github.dsheirer.source.config.SourceConfiguration sourceConfiguration(
        List<Long> frequencies, List<TrunkedSiteChannel> allChannels)
    {
        if(frequencies.size() == 1)
        {
            SourceConfigTuner source = new SourceConfigTuner();
            source.setFrequency(frequencies.getFirst());
            return source;
        }
        return multipleSource(frequencies, allChannels);
    }

    private static List<Long> selectedSiteFrequencies(TrunkedSiteDetails site, List<Long> requested)
    {
        Set<Long> available = new LinkedHashSet<>(controlFrequencies(site));
        List<Long> selected;
        if(requested == null || requested.isEmpty())
        {
            selected = controlFrequencies(site);
        }
        else
        {
            if(requested.size() > MAXIMUM_IMPORT_ITEMS || requested.stream().anyMatch(
                frequency -> frequency == null || frequency <= 0 || !available.contains(frequency)))
            {
                throw new IllegalArgumentException("frequency_hz contains an unavailable control-channel frequency");
            }
            selected = new ArrayList<>(new LinkedHashSet<>(requested));
        }
        if(selected.isEmpty())
        {
            throw new IllegalArgumentException("Select at least one control-channel frequency");
        }
        return List.copyOf(selected);
    }

    private static List<Long> controlFrequencies(TrunkedSiteDetails site)
    {
        List<Long> frequencies = site.channels().stream()
            .filter(channel -> channel.primaryControl() || channel.alternateControl())
            .sorted(Comparator.comparing(TrunkedSiteChannel::primaryControl).reversed()
                .thenComparingLong(TrunkedSiteChannel::frequencyHz))
            .map(TrunkedSiteChannel::frequencyHz).distinct().toList();
        return frequencies.isEmpty() ? site.channels().stream().map(TrunkedSiteChannel::frequencyHz)
            .filter(frequency -> frequency > 0).distinct().sorted().toList() : frequencies;
    }

    private static ChannelIdentity channelIdentity(Channel channel)
    {
        return new ChannelIdentity(normalizeNullable(channel.getSystem()), normalizeNullable(channel.getSite()),
            Set.copyOf(channel.getFrequencyList()));
    }

    private void addChannels(List<Channel> channels)
    {
        Set<ChannelIdentity> identities = new LinkedHashSet<>();
        mConfigurationManager.getChannelModel().getChannels().stream().map(RadioReferenceImportService::channelIdentity)
            .forEach(identities::add);

        for(Channel channel: channels)
        {
            if(!identities.add(channelIdentity(channel)))
            {
                throw new IllegalStateException(
                    "A channel with this system, site and frequency selection already exists");
            }
        }

        List<Channel> added = new ArrayList<>();
        try
        {
            for(Channel channel: channels)
            {
                mConfigurationManager.getChannelModel().addChannel(channel);
                added.add(channel);
            }
            mConfigurationManager.flushConfiguration();
        }
        catch(RuntimeException exception)
        {
            for(Channel channel: added)
            {
                try
                {
                    mConfigurationManager.getChannelModel().removeChannel(channel);
                }
                catch(RuntimeException rollbackException)
                {
                    exception.addSuppressed(rollbackException);
                }
            }

            throw new ConfigurationPersistenceException("Unable to save channel configuration", exception);
        }
    }

    private <T> T onConfigurationThread(Supplier<T> operation)
    {
        Objects.requireNonNull(operation);
        if(!mConfigurationManager.isInitialized())
        {
            throw new IllegalStateException("Configuration is still loading");
        }
        if(!mUseDesktopThread)
        {
            CompletableFuture<T> result = new CompletableFuture<>();
            mConfigurationManager.runHeadlessWebConfigurationTask(() -> {
                try
                {
                    result.complete(operation.get());
                }
                catch(Throwable throwable)
                {
                    result.completeExceptionally(throwable);
                }
            });
            return awaitResult(result);
        }
        if(mDesktopConfigurationDispatcher.isDispatchThread())
        {
            return operation.get();
        }

        CompletableFuture<T> result = new CompletableFuture<>();
        AtomicReference<DispatchState> state = new AtomicReference<>(DispatchState.QUEUED);
        mDesktopConfigurationDispatcher.dispatch(() -> {
            if(!state.compareAndSet(DispatchState.QUEUED, DispatchState.RUNNING))
            {
                return;
            }

            try
            {
                result.complete(operation.get());
            }
            catch(Throwable throwable)
            {
                result.completeExceptionally(throwable);
            }
        });

        try
        {
            return result.get(mConfigurationTimeoutNanos, TimeUnit.NANOSECONDS);
        }
        catch(TimeoutException exception)
        {
            if(state.compareAndSet(DispatchState.QUEUED, DispatchState.CANCELLED))
            {
                throw configurationBusy();
            }

            //The operation has started.  Wait for its unambiguous result instead of returning while it mutates.
            return joinStartedResult(result);
        }
        catch(InterruptedException exception)
        {
            if(state.compareAndSet(DispatchState.QUEUED, DispatchState.CANCELLED))
            {
                Thread.currentThread().interrupt();
                throw configurationBusy();
            }

            Thread.currentThread().interrupt();
            return joinStartedResult(result);
        }
        catch(ExecutionException exception)
        {
            throw configurationFailure(exception.getCause());
        }
    }

    private <T> T awaitResult(CompletableFuture<T> future)
    {
        try
        {
            return future.get(mConfigurationTimeoutNanos, TimeUnit.NANOSECONDS);
        }
        catch(InterruptedException exception)
        {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Configuration operation was interrupted", exception);
        }
        catch(TimeoutException exception)
        {
            throw new IllegalStateException("Configuration is busy; try again", exception);
        }
        catch(ExecutionException exception)
        {
            throw configurationFailure(exception.getCause());
        }
    }

    private static <T> T joinStartedResult(CompletableFuture<T> result)
    {
        try
        {
            return result.join();
        }
        catch(CompletionException exception)
        {
            throw configurationFailure(exception.getCause());
        }
    }

    private static IllegalStateException configurationBusy()
    {
        return new IllegalStateException("Configuration is busy; try again");
    }

    private static RuntimeException configurationFailure(Throwable cause)
    {
        if(cause instanceof RuntimeException runtimeException)
        {
            return runtimeException;
        }
        return new IllegalStateException("Configuration operation failed", cause);
    }

    private static TransmissionMode nxdnMode(String flavor)
    {
        return switch(text(flavor))
        {
            case "NEXEDGE 9600", "Conventional Networked" -> TransmissionMode.M9600;
            case "Icom IDAS Type D", "Kenwood Type D" -> TransmissionMode.TYPE_D;
            default -> TransmissionMode.M4800;
        };
    }

    private static int parseHex(String value)
    {
        try
        {
            return value == null || value.isBlank() ? 0 : Integer.parseInt(value.strip(), 16);
        }
        catch(NumberFormatException exception)
        {
            return 0;
        }
    }

    private static Set<Integer> boundedPositiveIds(Collection<Integer> ids, String field)
    {
        if(ids == null || ids.isEmpty() || ids.size() > MAXIMUM_IMPORT_ITEMS)
        {
            throw new IllegalArgumentException(field + " must contain 1-" + MAXIMUM_IMPORT_ITEMS + " items");
        }
        LinkedHashSet<Integer> result = new LinkedHashSet<>();
        for(Integer id: ids)
        {
            if(id == null || id <= 0 || !result.add(id))
            {
                throw new IllegalArgumentException(field + " must contain unique positive integers");
            }
        }
        return Set.copyOf(result);
    }

    private static int positive(int value, String field)
    {
        if(value <= 0)
        {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    private static void addChange(List<FieldChange> changes, String field, String previous, String updated)
    {
        if(!normalizedEquals(previous, updated))
        {
            changes.add(new FieldChange(field, blankToNull(previous), blankToNull(updated)));
        }
    }

    private static boolean normalizedEquals(String first, String second)
    {
        return Objects.equals(blankToNull(first), blankToNull(second));
    }

    private static String normalized(String value)
    {
        return text(value).toLowerCase(Locale.ROOT);
    }

    private static String normalizeNullable(String value)
    {
        String normalized = text(value).toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    private static String text(String value)
    {
        return value == null ? "" : value.strip();
    }

    private static String blankToNull(String value)
    {
        String normalized = text(value);
        return normalized.isEmpty() ? null : normalized;
    }

    private static String nonBlank(String requested, String fallback)
    {
        String value = text(requested);
        return value.isEmpty() ? text(fallback) : value;
    }

    private static String firstNonBlank(String... values)
    {
        for(String value: values)
        {
            if(value != null && !value.isBlank())
            {
                return value.strip();
            }
        }
        return "RadioReference Channel";
    }

    private record DecoderPlan(Protocol protocol, DecoderType decoderType, String reason)
    {
        private boolean supported()
        {
            return protocol != Protocol.UNKNOWN && decoderType != null;
        }
    }

    private record PreparedTalkgroupImport(List<Alias> changes, int added, int updated, int identical)
    {
    }

    private record ChannelIdentity(String system, String site, Set<Long> frequencies)
    {
    }

    interface DesktopConfigurationDispatcher
    {
        boolean isDispatchThread();

        void dispatch(Runnable task);
    }

    private enum DispatchState
    {
        QUEUED,
        RUNNING,
        CANCELLED
    }

    public record SystemPreview(TrunkedSystemDetails system, DecoderType recommendedDecoder, Protocol protocol,
                                boolean supported, String unsupportedReason)
    {
    }

    public record SitePreview(TrunkedSystemDetails system, TrunkedSiteDetails site,
                              DecoderType recommendedDecoder, Protocol protocol, boolean supported,
                              boolean p25ModulationRequired, List<Long> defaultControlFrequencies,
                              String unsupportedReason)
    {
        public SitePreview
        {
            defaultControlFrequencies = List.copyOf(defaultControlFrequencies);
        }
    }

    public record SiteChannelImport(int systemId, int siteId, Long aliasListId, String decoderType,
                                    String p25Modulation, List<Long> frequencyHz, String systemName,
                                    String siteName, String channelName)
    {
    }

    public record ChannelImportResult(List<String> channelGuids, int created)
    {
        public ChannelImportResult
        {
            channelGuids = List.copyOf(channelGuids);
        }
    }

    /** Distinguishes a failed channel mutation from a duplicate/configuration conflict. */
    public static final class ConfigurationPersistenceException extends IllegalStateException
    {
        private ConfigurationPersistenceException(String message, Throwable cause)
        {
            super(message, cause);
        }
    }

    /** Requires the caller to acknowledge replacement of RadioReference-owned Alias fields. */
    public static final class ConfirmationRequiredException extends IllegalStateException
    {
        private ConfirmationRequiredException()
        {
            super("Confirm the selected RadioReference Alias updates before importing");
        }
    }

    public enum ImportStatus
    {
        NOT_PRESENT,
        IDENTICAL,
        DIFFERENT
    }

    public record FieldChange(String field, String previousValue, String updatedValue)
    {
    }

    public record TalkgroupPreview(RemoteTalkgroup talkgroup, String category, ImportStatus status,
                                   Long existingAliasId, List<FieldChange> changes)
    {
        public TalkgroupPreview
        {
            changes = List.copyOf(changes);
        }
    }

    public record TalkgroupPreviewPage(List<TalkgroupPreview> items, int offset, Integer nextOffset,
                                       int totalItems, long revision,
                                       List<RemoteTalkgroupCategory> categories)
    {
        public TalkgroupPreviewPage
        {
            items = List.copyOf(items);
            categories = List.copyOf(categories);
        }
    }

    public record TalkgroupImport(int systemId, long aliasListId, long revision, List<Integer> talkgroupIds,
                                  boolean confirmUpdates)
    {
    }

    public record TalkgroupImportResult(long revision, int added, int updated, int identical)
    {
    }

    public record ConventionalImport(int subCategoryId, List<Integer> frequencyIds, Long aliasListId,
                                     String systemName, String siteName)
    {
    }
}
