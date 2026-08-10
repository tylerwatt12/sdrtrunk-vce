/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.database.importer;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler;
import com.fasterxml.jackson.databind.jsontype.TypeIdResolver;
import com.fasterxml.jackson.dataformat.xml.JacksonXmlModule;
import com.fasterxml.jackson.dataformat.xml.XmlFactory;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasFactory;
import io.github.dsheirer.alias.id.AliasID;
import io.github.dsheirer.alias.id.AliasIDType;
import io.github.dsheirer.alias.id.broadcast.BroadcastChannel;
import io.github.dsheirer.alias.id.dcs.Dcs;
import io.github.dsheirer.alias.id.legacy.fleetsync.FleetsyncID;
import io.github.dsheirer.alias.id.legacy.mdc.MDC1200ID;
import io.github.dsheirer.alias.id.legacy.nonrecordable.NonRecordable;
import io.github.dsheirer.alias.id.legacy.siteID.SiteID;
import io.github.dsheirer.alias.id.legacy.talkgroup.LegacyTalkgroupID;
import io.github.dsheirer.alias.id.priority.Priority;
import io.github.dsheirer.alias.id.radio.Radio;
import io.github.dsheirer.alias.id.radio.RadioRange;
import io.github.dsheirer.alias.id.record.Record;
import io.github.dsheirer.alias.id.talkgroup.P25FullyQualifiedTalkgroup;
import io.github.dsheirer.alias.id.talkgroup.StreamAsTalkgroup;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.alias.id.talkgroup.TalkgroupRange;
import io.github.dsheirer.alias.id.tone.TonesID;
import io.github.dsheirer.audio.broadcast.BroadcastConfiguration;
import io.github.dsheirer.audio.broadcast.BroadcastFormat;
import io.github.dsheirer.audio.broadcast.BroadcastServerType;
import io.github.dsheirer.audio.broadcast.icecast.IcecastConfiguration;
import io.github.dsheirer.audio.broadcast.shoutcast.v1.ShoutcastV1Configuration;
import io.github.dsheirer.configuration.ChannelConfigurationPolicy;
import io.github.dsheirer.configuration.ConfigurationState;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.database.DatabaseFileInstaller;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.alias.AliasDatabaseStore;
import io.github.dsheirer.database.configuration.ConfigurationDatabaseStore;
import io.github.dsheirer.database.configuration.ConfigurationSnapshotDatabaseStore;
import io.github.dsheirer.identifier.tone.AmbeTone;
import io.github.dsheirer.identifier.tone.Tone;
import io.github.dsheirer.identifier.tone.ToneSequence;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.config.AuxDecodeConfiguration;
import io.github.dsheirer.module.decode.config.DecodeConfiguration;
import io.github.dsheirer.module.decode.dcs.DCSCode;
import io.github.dsheirer.module.decode.nbfm.DecodeConfigNBFM;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Conventional;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.decode.p25.phase1.Modulation;
import io.github.dsheirer.module.log.EventLogType;
import io.github.dsheirer.module.log.config.EventLogConfiguration;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.record.RecorderType;
import io.github.dsheirer.record.config.RecordConfiguration;
import io.github.dsheirer.source.config.SourceConfigTuner;
import io.github.dsheirer.source.tuner.channel.ChannelSpecification;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * One-way import from a legacy playlist XML file into a new SQLite database.
 */
public class LegacyXmlConfigurationImporter
{
    private static final String PLAYLIST_DIRECTORY = "playlist";
    private static final String DEFAULT_PLAYLIST = "default.xml";
    private static final String LEGACY_PLAYLIST = "playlist_v2.xml";
    private static final int MINIMUM_PLAYLIST_VERSION = 1;
    private static final int MAXIMUM_PLAYLIST_VERSION = 4;
    private static final long P25_TRUNKED_BAND_MINIMUM_HZ = 700_000_000L;
    private static final long P25_TRUNKED_BAND_MAXIMUM_HZ = 1_000_000_000L;
    private static final int P25_TRUNKED_TALKGROUP_COUNT = 3;
    private static final int P25_MAXIMUM_TALKGROUP = 65_535;
    private static final Pattern LEGACY_P25_MATCHER = Pattern.compile("[A-Fa-f\\d]{4}|[A-Fa-f\\d]{6}");
    private static final Pattern LEGACY_FLEETSYNC_MATCHER = Pattern.compile("(\\d{3})-(\\d{4})");
    private static final Pattern LEGACY_MDC1200_MATCHER = Pattern.compile("[A-Fa-f\\d]{4}");
    private static final Set<String> RETIRED_DECODER_CONFIG_TYPES = Set.of(
        "decodeConfigAM", "decodeConfigLTRStandard", "decodeConfigLTRNet", "decodeConfigPassport");
    private static final Set<String> RETIRED_ALIAS_IDENTIFIER_TYPES = Set.of(
        "min", "p25FullyQualifiedRadio", "uniqueID");
    private static final Set<String> RETIRED_BROADCAST_CONFIG_TYPES = Set.of("shoutcastV2Configuration");

    private LegacyXmlConfigurationImporter()
    {
    }

    public static Optional<Path> discoverPlaylist(Path applicationRoot)
    {
        if(applicationRoot == null)
        {
            return Optional.empty();
        }

        Path playlistDirectory = applicationRoot.resolve(PLAYLIST_DIRECTORY);
        Path current = playlistDirectory.resolve(DEFAULT_PLAYLIST);

        if(Files.isRegularFile(current))
        {
            return Optional.of(current);
        }

        Path legacy = playlistDirectory.resolve(LEGACY_PLAYLIST);

        if(Files.isRegularFile(legacy))
        {
            return Optional.of(legacy);
        }

        return Optional.empty();
    }

    public static void importPlaylist(Path sourceXml, Path databasePath) throws IOException, SQLException
    {
        Path normalizedXml = sourceXml.toAbsolutePath().normalize();
        Path normalizedDatabase = databasePath.toAbsolutePath().normalize();

        if(!Files.isRegularFile(normalizedXml))
        {
            throw new IOException("Legacy SDRTrunk playlist XML does not exist: " + normalizedXml);
        }

        if(Files.exists(normalizedDatabase))
        {
            throw new IOException("Refusing to overwrite existing SDRTrunk SQLite database: " + normalizedDatabase);
        }

        ConfigurationState state = readConfigurationState(normalizedXml);
        convertLikelyConventionalP25Channels(state);
        DatabaseFileInstaller.install(normalizedDatabase, temporaryDatabase -> {
            SdrTrunkDatabaseStartup.createGlobalDatabase(temporaryDatabase);
            new ConfigurationSnapshotDatabaseStore(temporaryDatabase).replace(state);
            validateMigration(temporaryDatabase, state);
        });

    }

    public static ConfigurationState readConfigurationState(Path sourceXml) throws IOException
    {
        int playlistVersion = readPlaylistVersion(sourceXml);

        try(InputStream inputStream = Files.newInputStream(sourceXml))
        {
            XmlPlaylist playlist = xmlMapper().readValue(inputStream, XmlPlaylist.class);
            ConfigurationState state = new ConfigurationState();
            state.setAliases(convertAliases(nonNull(playlist.getAliases()), playlistVersion));
            state.setBroadcastConfigurations(new ArrayList<>(nonNull(playlist.getBroadcastConfigurations()).stream()
                .filter(configuration -> !(configuration instanceof RetiredBroadcastConfiguration))
                .toList()));
            List<Channel> channels = new ArrayList<>(nonNull(playlist.getChannels()));
            sanitizeChannelConfigurationLists(channels);

            if(playlistVersion <= 2)
            {
                removeVersionOneChannelSettings(channels);
            }

            state.setChannels(new ArrayList<>(channels.stream()
                .filter(ChannelConfigurationPolicy::isActive)
                .toList()));
            AliasListDefinitionResolver.normalizeLegacyState(state);
            return state;
        }
    }

    /**
     * Unknown enum values deserialize as null so that a retired option does not abort the whole playlist import. Keep
     * those placeholders out of current configuration, and retain only decoders that are active auxiliary decoders.
     */
    private static void sanitizeChannelConfigurationLists(List<Channel> channels)
    {
        for(Channel channel: channels)
        {
            if(channel == null)
            {
                continue;
            }

            AuxDecodeConfiguration auxDecodeConfiguration = channel.getAuxDecodeConfiguration();

            if(auxDecodeConfiguration != null)
            {
                if(auxDecodeConfiguration.getAuxDecoders() == null)
                {
                    auxDecodeConfiguration.setAuxDecoders(new ArrayList<>());
                }
                else
                {
                    auxDecodeConfiguration.getAuxDecoders().removeIf(
                        decoder -> !DecoderType.AUX_DECODERS.contains(decoder));
                }
            }

            RecordConfiguration recordConfiguration = channel.getRecordConfiguration();

            if(recordConfiguration != null)
            {
                if(recordConfiguration.getRecorders() == null)
                {
                    recordConfiguration.setRecorders(new ArrayList<>());
                }
                else
                {
                    recordConfiguration.getRecorders().removeIf(recorder -> recorder == null);
                }
            }

            EventLogConfiguration eventLogConfiguration = channel.getEventLogConfiguration();

            if(eventLogConfiguration != null)
            {
                if(eventLogConfiguration.getLoggers() == null)
                {
                    eventLogConfiguration.setLoggers(new ArrayList<>());
                }
                else
                {
                    eventLogConfiguration.getLoggers().removeIf(logger -> logger == null);
                }
            }
        }
    }

    private static int readPlaylistVersion(Path sourceXml) throws IOException
    {
        XMLInputFactory factory = secureXmlInputFactory();

        try(InputStream inputStream = Files.newInputStream(sourceXml))
        {
            XMLStreamReader reader = factory.createXMLStreamReader(inputStream);

            try
            {
                while(reader.hasNext())
                {
                    if(reader.next() == XMLStreamConstants.START_ELEMENT)
                    {
                        String namespace = reader.getNamespaceURI();

                        if(!"playlist".equals(reader.getLocalName()) ||
                            (namespace != null && !namespace.isEmpty()))
                        {
                            throw new IOException("The selected XML is not an SDRTrunk playlist.");
                        }

                        String value = reader.getAttributeValue(null, "version");
                        int version = MINIMUM_PLAYLIST_VERSION;

                        if(value != null)
                        {
                            if(value.isBlank())
                            {
                                throw new IOException("SDRTrunk playlist version must be a whole number from 1 through " +
                                    MAXIMUM_PLAYLIST_VERSION + ".");
                            }

                            try
                            {
                                version = Integer.parseInt(value.trim());
                            }
                            catch(NumberFormatException e)
                            {
                                throw new IOException("SDRTrunk playlist version must be a whole number from 1 through " +
                                    MAXIMUM_PLAYLIST_VERSION + ".", e);
                            }
                        }

                        if(version > MAXIMUM_PLAYLIST_VERSION)
                        {
                            throw new IOException("SDRTrunk playlist version " + version +
                                " is newer than this importer supports (versions 1 through " +
                                MAXIMUM_PLAYLIST_VERSION + ").");
                        }

                        if(version < MINIMUM_PLAYLIST_VERSION)
                        {
                            throw new IOException("Unsupported SDRTrunk playlist version " + version +
                                ". This importer supports versions 1 through " + MAXIMUM_PLAYLIST_VERSION + ".");
                        }

                        return version;
                    }
                }
            }
            finally
            {
                reader.close();
            }
        }
        catch(XMLStreamException e)
        {
            throw new IOException("Unable to read the SDRTrunk playlist version.", e);
        }

        throw new IOException("The selected XML does not contain an SDRTrunk playlist.");
    }

    private static XMLInputFactory secureXmlInputFactory()
    {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        return factory;
    }

    private static List<Alias> convertAliases(List<LegacyAlias> legacyAliases, int playlistVersion)
    {
        List<Alias> aliases = new ArrayList<>();

        for(LegacyAlias legacyAlias: legacyAliases)
        {
            if(legacyAlias != null)
            {
                aliases.addAll(legacyAlias.toAliases(playlistVersion));
            }
        }

        return aliases;
    }

    private static void removeVersionOneChannelSettings(List<Channel> channels)
    {
        for(Channel channel: channels)
        {
            if(channel == null)
            {
                continue;
            }

            RecordConfiguration recordConfiguration = channel.getRecordConfiguration();

            if(recordConfiguration != null && recordConfiguration.getRecorders() != null)
            {
                recordConfiguration.getRecorders().removeIf(recorder -> recorder == RecorderType.AUDIO);
            }

            EventLogConfiguration eventLogConfiguration = channel.getEventLogConfiguration();

            if(eventLogConfiguration != null && eventLogConfiguration.getLoggers() != null)
            {
                eventLogConfiguration.getLoggers().removeIf(logger -> logger == EventLogType.BINARY_MESSAGE);
            }
        }
    }

    private static ObjectMapper xmlMapper()
    {
        JacksonXmlModule xmlModule = new JacksonXmlModule();
        xmlModule.setDefaultUseWrapper(false);
        ObjectMapper objectMapper = new XmlMapper(new XmlFactory(secureXmlInputFactory()), xmlModule)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL, true)
            .configure(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, true);
        objectMapper.addMixIn(AuxDecodeConfiguration.class, LegacyAuxDecodeConfigurationMixin.class);
        objectMapper.addMixIn(DecodeConfigNBFM.class, LegacyNbfmConfigurationMixin.class);
        objectMapper.addMixIn(RadioRange.class, LegacyRadioRangeMixin.class);
        objectMapper.addMixIn(Dcs.class, LegacyDcsMixin.class);
        objectMapper.addMixIn(TonesID.class, LegacyTonesIdMixin.class);
        objectMapper.addMixIn(ToneSequence.class, LegacyToneSequenceMixin.class);
        objectMapper.addMixIn(Tone.class, LegacyToneMixin.class);
        objectMapper.addMixIn(BroadcastConfiguration.class, LegacyBroadcastConfigurationMixin.class);
        objectMapper.addMixIn(IcecastConfiguration.class, LegacyIcecastConfigurationMixin.class);
        objectMapper.addMixIn(ShoutcastV1Configuration.class, LegacyShoutcastV1ConfigurationMixin.class);
        objectMapper.addMixIn(RecordConfiguration.class, LegacyRecordConfigurationMixin.class);
        objectMapper.addMixIn(EventLogConfiguration.class, LegacyEventLogConfigurationMixin.class);
        objectMapper.addHandler(new RetiredTypeHandler());
        objectMapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        return objectMapper;
    }

    private static <T> List<T> nonNull(List<T> values)
    {
        return values != null ? values : new ArrayList<>();
    }

    static int convertLikelyConventionalP25Channels(ConfigurationState state)
    {
        int conversions = 0;

        for(Channel channel: state.getChannels())
        {
            if(channel.getDecodeConfiguration() instanceof DecodeConfigP25Phase1 p25Phase1 &&
                channel.getSourceConfiguration() instanceof SourceConfigTuner tuner &&
                !hasTrunkedP25Indicators(channel, p25Phase1, tuner, state.getAliases()))
            {
                channel.setDecodeConfiguration(new DecodeConfigP25Conventional());
                conversions++;
            }
        }

        return conversions;
    }

    private static boolean hasTrunkedP25Indicators(Channel channel, DecodeConfigP25Phase1 p25Phase1,
                                                   SourceConfigTuner tuner, List<Alias> aliases)
    {
        long frequency = tuner.getFrequency();

        return p25Phase1.getModulation() == Modulation.CQPSK ||
            (frequency >= P25_TRUNKED_BAND_MINIMUM_HZ && frequency < P25_TRUNKED_BAND_MAXIMUM_HZ) ||
            countDistinctP25Talkgroups(channel.getAliasListName(), aliases) >= P25_TRUNKED_TALKGROUP_COUNT;
    }

    private static int countDistinctP25Talkgroups(String aliasListName, List<Alias> aliases)
    {
        Set<Integer> talkgroups = new HashSet<>();

        for(Alias alias: aliases)
        {
            if(alias != null && aliasListName != null && alias.getAliasListName() != null &&
                alias.getAliasListName().equalsIgnoreCase(aliasListName))
            {
                if(alias.getMatchIdentifier() instanceof Talkgroup talkgroup &&
                    talkgroup.getProtocol() == Protocol.APCO25)
                {
                    talkgroups.add(talkgroup.getValue());

                    if(talkgroups.size() >= P25_TRUNKED_TALKGROUP_COUNT)
                    {
                        return talkgroups.size();
                    }
                }
            }
        }

        return talkgroups.size();
    }

    private static void validateMigration(Path databasePath, ConfigurationState expected) throws IOException, SQLException
    {
        AliasDatabaseStore aliasStore = new AliasDatabaseStore(databasePath);
        var definitions = aliasStore.loadAliasListDefinitions();
        List<Alias> aliases = aliasStore.loadAliases(definitions);
        ConfigurationState actual = new ConfigurationDatabaseStore(databasePath).loadConfigurationState();

        int expectedIdentifierCount = countAliasIdentifiers(expected.getAliases());
        int actualIdentifierCount = countAliasIdentifiers(aliases);

        boolean identitiesValid = aliases.stream().allMatch(alias -> alias.getId() > 0 && alias.getAliasListId() > 0);

        if(definitions.size() != expected.getAliasListDefinitions().size() ||
            aliases.size() != expected.getAliases().size() || actualIdentifierCount != expectedIdentifierCount ||
            actual.getBroadcastConfigurations().size() != expected.getBroadcastConfigurations().size() ||
            actual.getChannels().size() != expected.getChannels().size() || !identitiesValid)
        {
            throw new IOException("Migrated SQLite validation failed: expected aliasLists=" +
                expected.getAliasListDefinitions().size() + " aliases=" + expected.getAliases().size() +
                " aliasIdentifiers=" + expectedIdentifierCount +
                " streams=" + expected.getBroadcastConfigurations().size() + " channels=" +
                expected.getChannels().size() +
                " but loaded aliasLists=" + definitions.size() + " aliases=" + aliases.size() +
                " aliasIdentifiers=" + actualIdentifierCount +
                " streams=" + actual.getBroadcastConfigurations().size() +
                " channels=" + actual.getChannels().size() + " identitiesValid=" + identitiesValid);
        }
    }

    private static int countAliasIdentifiers(List<Alias> aliases)
    {
        return (int)aliases.stream().filter(alias -> alias.getMatchIdentifier() != null).count();
    }

    @JacksonXmlRootElement(localName = "playlist")
    private static class XmlPlaylist
    {
        private List<LegacyAlias> mAliases = new ArrayList<>();
        private List<BroadcastConfiguration> mBroadcastConfigurations = new ArrayList<>();
        private List<Channel> mChannels = new ArrayList<>();

        @JacksonXmlProperty(isAttribute = false, localName = "alias")
        public List<LegacyAlias> getAliases()
        {
            return mAliases;
        }

        public void setAliases(List<LegacyAlias> aliases)
        {
            mAliases = aliases;
        }

        @JacksonXmlProperty(isAttribute = false, localName = "stream")
        public List<BroadcastConfiguration> getBroadcastConfigurations()
        {
            return mBroadcastConfigurations;
        }

        public void setBroadcastConfigurations(List<BroadcastConfiguration> broadcastConfigurations)
        {
            mBroadcastConfigurations = broadcastConfigurations;
        }

        @JacksonXmlProperty(isAttribute = false, localName = "channel")
        public List<Channel> getChannels()
        {
            return mChannels;
        }

        public void setChannels(List<Channel> channels)
        {
            mChannels = channels;
        }
    }

    /**
     * Import-only representation of the old XML alias shape. It is converted immediately to one plain Alias for each
     * match entry, so the runtime model never needs a multi-identifier compatibility buffer.
     */
    private static class LegacyAlias
    {
        @JacksonXmlProperty(isAttribute = true, localName = "name")
        private String mName;
        @JacksonXmlProperty(isAttribute = true, localName = "list")
        private String mAliasListName;
        @JacksonXmlProperty(isAttribute = true, localName = "description")
        private String mDescription;
        @JacksonXmlProperty(isAttribute = true, localName = "group")
        private String mGroup;
        @JacksonXmlProperty(isAttribute = true, localName = "color")
        private int mColor;
        @JacksonXmlProperty(isAttribute = true, localName = "iconName")
        private String mIconName;
        @JacksonXmlProperty(isAttribute = false, localName = "stream_talkgroup_alias")
        private StreamAsTalkgroup mStreamTalkgroupAlias;
        @JacksonXmlProperty(isAttribute = false, localName = "id")
        private List<AliasID> mIdentifiers = new ArrayList<>();

        private List<Alias> toAliases(int playlistVersion)
        {
            Alias template = new Alias(mName);
            template.setAliasListName(mAliasListName);
            template.setDescription(mDescription);
            template.setGroup(mGroup);
            template.setColor(mColor);
            template.setIconName(mIconName);
            template.setStreamTalkgroupAlias(mStreamTalkgroupAlias);
            List<AliasID> matchers = new ArrayList<>();

            for(AliasID identifier: nonNull(mIdentifiers))
            {
                if(identifier == null)
                {
                    continue;
                }

                switch(identifier)
                {
                    case RetiredAliasIdentifier ignored -> { }
                    case BroadcastChannel broadcastChannel -> template.addBroadcastChannel(broadcastChannel);
                    case Record ignored -> template.setRecordable(true);
                    case Priority priority -> template.setCallPriority(priority.getPriority());
                    case NonRecordable ignored -> {
                        if(playlistVersion > 2)
                        {
                            template.setRecordable(false);
                        }
                    }
                    case StreamAsTalkgroup streamAsTalkgroup -> template.setStreamTalkgroupAlias(streamAsTalkgroup);
                    default -> matchers.addAll(upgradeMatcher(identifier, playlistVersion));
                }
            }

            List<Alias> aliases = new ArrayList<>(matchers.size());

            if(matchers.isEmpty())
            {
                return List.of(template);
            }

            for(int index = 0; index < matchers.size(); index++)
            {
                Alias alias = index == 0 ? template : AliasFactory.copyOf(template);
                alias.setMatchIdentifier(matchers.get(index));
                aliases.add(alias);
            }

            return aliases;
        }

        /**
         * Applies the stock SDRTrunk playlist v1-v4 matcher upgrades that remain useful in VCE. Retired protocols are
         * deliberately left to the normal import filter instead of restoring their runtime support.
         */
        private static List<AliasID> upgradeMatcher(AliasID identifier, int playlistVersion)
        {
            //Fully-qualified talkgroup matching is retired. Drop this matcher instead of guessing that its stored
            //home talkgroup is the correct local talkgroup on every monitored system.
            if(identifier instanceof P25FullyQualifiedTalkgroup)
            {
                return List.of();
            }

            if(playlistVersion <= 2 && identifier instanceof SiteID)
            {
                return List.of();
            }

            AliasID upgraded = identifier;

            if(playlistVersion <= 2)
            {
                upgraded = upgradeVersionOneMatcher(identifier);
            }

            if(playlistVersion <= 3)
            {
                return upgradeVersionThreeP25Matcher(upgraded);
            }

            return List.of(upgraded);
        }

        private static AliasID upgradeVersionOneMatcher(AliasID identifier)
        {
            if(identifier instanceof LegacyTalkgroupID legacyTalkgroup)
            {
                String value = legacyTalkgroup.getTalkgroup();

                if(value != null && LEGACY_P25_MATCHER.matcher(value).matches())
                {
                    return new Talkgroup(Protocol.APCO25, Integer.parseInt(value, 16));
                }
            }
            else if(identifier instanceof FleetsyncID fleetsync)
            {
                String value = fleetsync.getIdent();
                Matcher matcher = value != null ? LEGACY_FLEETSYNC_MATCHER.matcher(value) : null;

                if(matcher != null && matcher.matches())
                {
                    int fleet = Integer.parseInt(matcher.group(1));
                    int ident = Integer.parseInt(matcher.group(2));
                    return new Talkgroup(Protocol.FLEETSYNC, (fleet << 12) + ident);
                }
            }
            else if(identifier instanceof MDC1200ID mdc1200)
            {
                String value = mdc1200.getIdent();

                if(value != null && LEGACY_MDC1200_MATCHER.matcher(value).matches())
                {
                    return new Talkgroup(Protocol.MDC1200, Integer.parseInt(value, 16));
                }
            }

            return identifier;
        }

        private static List<AliasID> upgradeVersionThreeP25Matcher(AliasID identifier)
        {
            if(identifier instanceof Talkgroup talkgroup && talkgroup.getProtocol() == Protocol.APCO25 &&
                talkgroup.getValue() > P25_MAXIMUM_TALKGROUP)
            {
                return List.of(new Radio(Protocol.APCO25, talkgroup.getValue()));
            }

            if(identifier instanceof TalkgroupRange range && range.getProtocol() == Protocol.APCO25)
            {
                int minimum = range.getMinTalkgroup();
                int maximum = range.getMaxTalkgroup();

                if(minimum > P25_MAXIMUM_TALKGROUP)
                {
                    return List.of(radioMatcher(minimum, maximum));
                }

                if(minimum <= P25_MAXIMUM_TALKGROUP && maximum > P25_MAXIMUM_TALKGROUP)
                {
                    return List.of(talkgroupMatcher(minimum, P25_MAXIMUM_TALKGROUP),
                        radioMatcher(P25_MAXIMUM_TALKGROUP + 1, maximum));
                }
            }

            return List.of(identifier);
        }

        private static AliasID talkgroupMatcher(int minimum, int maximum)
        {
            return minimum == maximum ? new Talkgroup(Protocol.APCO25, minimum) :
                new TalkgroupRange(Protocol.APCO25, minimum, maximum);
        }

        private static AliasID radioMatcher(int minimum, int maximum)
        {
            return minimum == maximum ? new Radio(Protocol.APCO25, minimum) :
                new RadioRange(Protocol.APCO25, minimum, maximum);
        }
    }

    /**
     * Import-only recognition for removed polymorphic types. These objects are discarded before configuration reaches
     * the runtime model or SQLite store.
     */
    private static class RetiredTypeHandler extends DeserializationProblemHandler
    {
        @Override
        public JavaType handleUnknownTypeId(DeserializationContext context, JavaType baseType, String subTypeId,
                                            TypeIdResolver idResolver, String failureMessage)
        {
            if(baseType.hasRawClass(DecodeConfiguration.class) &&
                RETIRED_DECODER_CONFIG_TYPES.contains(subTypeId))
            {
                return context.constructType(RetiredDecodeConfiguration.class);
            }

            if(baseType.hasRawClass(AliasID.class) && RETIRED_ALIAS_IDENTIFIER_TYPES.contains(subTypeId))
            {
                return context.constructType(RetiredAliasIdentifier.class);
            }

            if(baseType.hasRawClass(BroadcastConfiguration.class) &&
                RETIRED_BROADCAST_CONFIG_TYPES.contains(subTypeId))
            {
                return context.constructType(RetiredBroadcastConfiguration.class);
            }

            return null;
        }
    }

    private static class RetiredDecodeConfiguration extends DecodeConfiguration
    {
        @Override
        public DecoderType getDecoderType()
        {
            return null;
        }

        @Override
        public ChannelSpecification getChannelSpecification()
        {
            return null;
        }
    }

    private static class RetiredAliasIdentifier extends AliasID
    {
        @Override
        public AliasIDType getType()
        {
            return null;
        }

        @Override
        public boolean matches(AliasID id)
        {
            return false;
        }

        @Override
        public boolean isValid()
        {
            return false;
        }

    }

    private static class RetiredBroadcastConfiguration extends BroadcastConfiguration
    {
        @Override
        public BroadcastConfiguration copyOf()
        {
            return new RetiredBroadcastConfiguration();
        }

        @Override
        public BroadcastServerType getBroadcastServerType()
        {
            return BroadcastServerType.UNKNOWN;
        }
    }

    private abstract static class LegacyRecordConfigurationMixin
    {
        @JacksonXmlProperty(isAttribute = false, localName = "recorder")
        abstract List<RecorderType> getRecorders();
    }

    private abstract static class LegacyEventLogConfigurationMixin
    {
        @JacksonXmlProperty(isAttribute = false, localName = "logger")
        abstract List<EventLogType> getLoggers();
    }

    private abstract static class LegacyAuxDecodeConfigurationMixin
    {
        @JacksonXmlProperty(isAttribute = false, localName = "aux_decoder")
        abstract List<DecoderType> getAuxDecoders();
    }

    private abstract static class LegacyNbfmConfigurationMixin
    {
        @JacksonXmlProperty(isAttribute = true, localName = "audioFilter")
        abstract boolean isAudioFilter();

        @JacksonXmlProperty(isAttribute = true, localName = "squelchNoiseOpenThreshold")
        abstract float getSquelchNoiseOpenThreshold();

        @JacksonXmlProperty(isAttribute = true, localName = "squelchNoiseCloseThreshold")
        abstract float getSquelchNoiseCloseThreshold();

        @JacksonXmlProperty(isAttribute = true, localName = "squelchHysteresisOpenThreshold")
        abstract int getSquelchHysteresisOpenThreshold();

        @JacksonXmlProperty(isAttribute = true, localName = "squelchHysteresisCloseThreshold")
        abstract int getSquelchHysteresisCloseThreshold();

        @JacksonXmlProperty(isAttribute = true, localName = "squelchTailRemovalEnabled")
        abstract boolean isSquelchTailRemovalEnabled();

        @JacksonXmlProperty(isAttribute = true, localName = "squelchTailRemovalMs")
        abstract int getSquelchTailRemovalMs();

        @JacksonXmlProperty(isAttribute = true, localName = "squelchHeadRemovalMs")
        abstract int getSquelchHeadRemovalMs();

        @JacksonXmlProperty(isAttribute = true, localName = "lowPassEnabled")
        abstract boolean isLowPassEnabled();

        @JacksonXmlProperty(isAttribute = true, localName = "lowPassCutoff")
        abstract int getLowPassCutoff();

        @JacksonXmlProperty(isAttribute = true, localName = "voiceEnhanceAmount")
        abstract float getVoiceEnhanceAmount();

        @JacksonXmlProperty(isAttribute = true, localName = "bassBoostDb")
        abstract float getBassBoostDb();

        @JacksonXmlProperty(isAttribute = true, localName = "outputGain")
        abstract float getOutputGain();
    }

    private abstract static class LegacyRadioRangeMixin
    {
        @JacksonXmlProperty(isAttribute = true, localName = "min")
        abstract int getMinRadio();

        @JacksonXmlProperty(isAttribute = true, localName = "max")
        abstract int getMaxRadio();
    }

    private abstract static class LegacyDcsMixin
    {
        @JacksonXmlProperty(isAttribute = true, localName = "code")
        abstract DCSCode getDCSCode();
    }

    private abstract static class LegacyTonesIdMixin
    {
        @JacksonXmlProperty(isAttribute = false, localName = "toneSequence")
        abstract ToneSequence getToneSequence();
    }

    private abstract static class LegacyToneSequenceMixin
    {
        @JacksonXmlProperty(isAttribute = false, localName = "tone")
        abstract List<Tone> getTones();
    }

    private abstract static class LegacyToneMixin
    {
        @JacksonXmlProperty(isAttribute = true, localName = "value")
        abstract AmbeTone getAmbeTone();
    }

    private abstract static class LegacyBroadcastConfigurationMixin
    {
        @JacksonXmlProperty(isAttribute = false, localName = "format")
        abstract BroadcastFormat getBroadcastFormat();
    }

    private abstract static class LegacyIcecastConfigurationMixin
    {
        @JacksonXmlProperty(isAttribute = true, localName = "bitrate")
        abstract int getBitRate();
    }

    private abstract static class LegacyShoutcastV1ConfigurationMixin
    {
        @JacksonXmlProperty(isAttribute = true, localName = "bitrate")
        abstract int getBitRate();
    }

}
