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
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasFactory;
import io.github.dsheirer.alias.id.AliasID;
import io.github.dsheirer.alias.id.AliasIDType;
import io.github.dsheirer.alias.id.broadcast.BroadcastChannel;
import io.github.dsheirer.alias.id.legacy.nonrecordable.NonRecordable;
import io.github.dsheirer.alias.id.priority.Priority;
import io.github.dsheirer.alias.id.record.Record;
import io.github.dsheirer.alias.id.talkgroup.StreamAsTalkgroup;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.audio.broadcast.BroadcastConfiguration;
import io.github.dsheirer.configuration.ChannelConfigurationPolicy;
import io.github.dsheirer.configuration.ConfigurationState;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.database.DatabaseFileInstaller;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.alias.AliasDatabaseStore;
import io.github.dsheirer.database.configuration.ConfigurationDatabaseStore;
import io.github.dsheirer.database.configuration.ConfigurationSnapshotDatabaseStore;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Conventional;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.decode.p25.phase1.Modulation;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.config.DecodeConfiguration;
import io.github.dsheirer.protocol.Protocol;
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

/**
 * One-way import from a legacy playlist XML file into a new SQLite database.
 */
public class LegacyXmlConfigurationImporter
{
    private static final String PLAYLIST_DIRECTORY = "playlist";
    private static final String DEFAULT_PLAYLIST = "default.xml";
    private static final String LEGACY_PLAYLIST = "playlist_v2.xml";
    private static final long P25_TRUNKED_BAND_MINIMUM_HZ = 700_000_000L;
    private static final long P25_TRUNKED_BAND_MAXIMUM_HZ = 1_000_000_000L;
    private static final int P25_TRUNKED_TALKGROUP_COUNT = 3;
    private static final Set<String> RETIRED_DECODER_CONFIG_TYPES = Set.of(
        "decodeConfigAM", "decodeConfigLTRStandard", "decodeConfigLTRNet", "decodeConfigPassport");
    private static final Set<String> RETIRED_ALIAS_IDENTIFIER_TYPES = Set.of("min", "uniqueID");

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
        try(InputStream inputStream = Files.newInputStream(sourceXml))
        {
            XmlPlaylist playlist = xmlMapper().readValue(inputStream, XmlPlaylist.class);
            ConfigurationState state = new ConfigurationState();
            state.setAliases(convertAliases(nonNull(playlist.getAliases())));
            state.setBroadcastConfigurations(nonNull(playlist.getBroadcastConfigurations()));
            state.setChannels(new ArrayList<>(nonNull(playlist.getChannels()).stream()
                .filter(ChannelConfigurationPolicy::isActive)
                .toList()));
            AliasListDefinitionResolver.normalizeLegacyState(state);
            return state;
        }
    }

    private static List<Alias> convertAliases(List<LegacyAlias> legacyAliases)
    {
        List<Alias> aliases = new ArrayList<>();

        for(LegacyAlias legacyAlias: legacyAliases)
        {
            if(legacyAlias != null)
            {
                aliases.addAll(legacyAlias.toAliases());
            }
        }

        return aliases;
    }

    private static ObjectMapper xmlMapper()
    {
        JacksonXmlModule xmlModule = new JacksonXmlModule();
        xmlModule.setDefaultUseWrapper(false);
        ObjectMapper objectMapper = new XmlMapper(xmlModule)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL, true);
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
            if(alias != null && alias.matchesAliasList(aliasListName))
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
        @JacksonXmlProperty(isAttribute = true, localName = "stream_talkgroup_alias")
        private StreamAsTalkgroup mStreamTalkgroupAlias;
        @JacksonXmlProperty(isAttribute = false, localName = "id")
        private List<AliasID> mIdentifiers = new ArrayList<>();

        private List<Alias> toAliases()
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
                    case NonRecordable ignored -> template.setRecordable(false);
                    case StreamAsTalkgroup streamAsTalkgroup -> template.setStreamTalkgroupAlias(streamAsTalkgroup);
                    default -> matchers.add(identifier);
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

        @Override
        public boolean isAudioIdentifier()
        {
            return false;
        }
    }

}
