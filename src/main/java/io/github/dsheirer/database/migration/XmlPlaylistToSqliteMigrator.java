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

package io.github.dsheirer.database.migration;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.xml.JacksonXmlModule;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.audio.broadcast.BroadcastConfiguration;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.configuration.ConfigurationState;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.map.ChannelMap;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.alias.AliasDatabaseStore;
import io.github.dsheirer.database.configuration.ConfigurationDatabaseStore;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Conventional;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.source.config.SourceConfigTuner;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One-way migration from the legacy playlist XML file to the global SQLite database.
 */
public class XmlPlaylistToSqliteMigrator
{
    private static final Logger mLog = LoggerFactory.getLogger(XmlPlaylistToSqliteMigrator.class);
    private static final String PLAYLIST_DIRECTORY = "playlist";
    private static final String DEFAULT_PLAYLIST = "default.xml";
    private static final String LEGACY_PLAYLIST = "playlist_v2.xml";
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private XmlPlaylistToSqliteMigrator()
    {
    }

    public static Optional<MigrationResult> migrateDefaultIfDatabaseMissing(UserPreferences userPreferences)
        throws IOException, SQLException
    {
        Path databasePath = SdrTrunkDatabasePath.getDatabasePath(userPreferences);

        if(Files.exists(databasePath))
        {
            return Optional.empty();
        }

        Optional<Path> playlist = discoverPlaylist(userPreferences.getDirectoryPreference().getDirectoryApplicationRoot());

        if(playlist.isEmpty())
        {
            return Optional.empty();
        }

        MigrationResult result = migrate(playlist.get(), databasePath);
        mLog.info("Migrated legacy SDRTrunk XML playlist [{}] to SQLite [{}]: aliases [{}], streams [{}], " +
                "channel maps [{}], channels [{}], P25 conventional conversions [{}]",
            result.sourceXml(), result.database(), result.aliasCount(), result.streamCount(), result.channelMapCount(),
            result.channelCount(), result.p25ConventionalConversions());
        return Optional.of(result);
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

    public static MigrationResult migrate(Path sourceXml, Path databasePath) throws IOException, SQLException
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

        Files.createDirectories(normalizedDatabase.getParent());
        Path temporaryDatabase = normalizedDatabase.resolveSibling(normalizedDatabase.getFileName() + ".migrating-" +
            TIMESTAMP.format(LocalDateTime.now()) + ".tmp");
        deleteDatabaseFiles(temporaryDatabase);

        try
        {
            ConfigurationState state = readConfigurationState(normalizedXml);
            int p25ConventionalConversions = convertSingleFrequencyP25Channels(state);

            SdrTrunkDatabaseStartup.prepareGlobalDatabase(temporaryDatabase);
            new AliasDatabaseStore(temporaryDatabase).replaceAliases(state.getAliases());
            new ConfigurationDatabaseStore(temporaryDatabase).replaceConfigurationState(state);
            checkpoint(temporaryDatabase);
            validateMigration(temporaryDatabase, state);
            moveDatabase(temporaryDatabase, normalizedDatabase);

            return new MigrationResult(normalizedXml, normalizedDatabase, state.getAliases().size(),
                state.getBroadcastConfigurations().size(), state.getChannelMaps().size(), state.getChannels().size(),
                p25ConventionalConversions);
        }
        catch(IOException | SQLException | RuntimeException e)
        {
            deleteDatabaseFiles(temporaryDatabase);
            throw e;
        }
    }

    static ConfigurationState readConfigurationState(Path sourceXml) throws IOException
    {
        try(InputStream inputStream = Files.newInputStream(sourceXml))
        {
            XmlPlaylist playlist = xmlMapper().readValue(inputStream, XmlPlaylist.class);
            ConfigurationState state = new ConfigurationState();
            state.setVersion(ConfigurationManager.CONFIGURATION_CURRENT_VERSION);
            state.setAliases(nonNull(playlist.getAliases()));
            state.setBroadcastConfigurations(nonNull(playlist.getBroadcastConfigurations()));
            state.setChannelMaps(nonNull(playlist.getChannelMaps()));
            state.setChannels(nonNull(playlist.getChannels()));
            return state;
        }
    }

    private static ObjectMapper xmlMapper()
    {
        JacksonXmlModule xmlModule = new JacksonXmlModule();
        xmlModule.setDefaultUseWrapper(false);
        ObjectMapper objectMapper = new XmlMapper(xmlModule)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        objectMapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        return objectMapper;
    }

    private static <T> List<T> nonNull(List<T> values)
    {
        return values != null ? values : new ArrayList<>();
    }

    private static int convertSingleFrequencyP25Channels(ConfigurationState state)
    {
        int conversions = 0;

        for(Channel channel: state.getChannels())
        {
            if(channel.getDecodeConfiguration() instanceof DecodeConfigP25Phase1 &&
                channel.getSourceConfiguration() instanceof SourceConfigTuner)
            {
                channel.setDecodeConfiguration(new DecodeConfigP25Conventional());
                conversions++;
            }
        }

        return conversions;
    }

    private static void validateMigration(Path databasePath, ConfigurationState expected) throws IOException, SQLException
    {
        List<Alias> aliases = new AliasDatabaseStore(databasePath).loadAliases();
        ConfigurationState actual = new ConfigurationDatabaseStore(databasePath).loadConfigurationState();

        int expectedIdentifierCount = countAliasIdentifiers(expected.getAliases());
        int actualIdentifierCount = countAliasIdentifiers(aliases);
        int expectedActionCount = countAliasActions(expected.getAliases());
        int actualActionCount = countAliasActions(aliases);

        if(aliases.size() != expected.getAliases().size() || actualIdentifierCount != expectedIdentifierCount ||
            actualActionCount != expectedActionCount ||
            actual.getBroadcastConfigurations().size() != expected.getBroadcastConfigurations().size() ||
            actual.getChannelMaps().size() != expected.getChannelMaps().size() ||
            actual.getChannels().size() != expected.getChannels().size())
        {
            throw new IOException("Migrated SQLite validation failed: expected aliases=" + expected.getAliases().size() +
                " aliasIdentifiers=" + expectedIdentifierCount + " aliasActions=" + expectedActionCount +
                " streams=" + expected.getBroadcastConfigurations().size() + " channelMaps=" +
                expected.getChannelMaps().size() + " channels=" + expected.getChannels().size() +
                " but loaded aliases=" + aliases.size() + " aliasIdentifiers=" + actualIdentifierCount +
                " aliasActions=" + actualActionCount + " streams=" + actual.getBroadcastConfigurations().size() + " channelMaps=" +
                actual.getChannelMaps().size() + " channels=" + actual.getChannels().size());
        }
    }

    private static int countAliasIdentifiers(List<Alias> aliases)
    {
        return aliases.stream().mapToInt(alias -> alias.getAliasIdentifiers().size()).sum();
    }

    private static int countAliasActions(List<Alias> aliases)
    {
        return aliases.stream().mapToInt(alias -> alias.getAliasActions().size()).sum();
    }

    private static void checkpoint(Path databasePath) throws SQLException
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        }
    }

    private static void moveDatabase(Path source, Path target) throws IOException
    {
        try
        {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        }
        catch(AtomicMoveNotSupportedException e)
        {
            Files.move(source, target);
        }
    }

    private static void deleteDatabaseFiles(Path databasePath) throws IOException
    {
        Files.deleteIfExists(databasePath);
        Files.deleteIfExists(databasePath.resolveSibling(databasePath.getFileName() + "-wal"));
        Files.deleteIfExists(databasePath.resolveSibling(databasePath.getFileName() + "-shm"));
    }

    public static void main(String[] args) throws Exception
    {
        CliOptions options = CliOptions.parse(args);

        if(options.help())
        {
            printUsage();
            return;
        }

        UserPreferences userPreferences = new UserPreferences();
        Path applicationRoot = options.applicationRoot() != null ? options.applicationRoot().toAbsolutePath().normalize() :
            userPreferences.getDirectoryPreference().getDirectoryApplicationRoot().toAbsolutePath().normalize();
        Path sourceXml = options.sourceXml() != null ? options.sourceXml().toAbsolutePath().normalize() :
            discoverPlaylist(applicationRoot).orElseThrow(() ->
                new IOException("No legacy playlist XML found under " + applicationRoot.resolve(PLAYLIST_DIRECTORY)));
        Path database = options.databasePath() != null ? options.databasePath().toAbsolutePath().normalize() :
            defaultOutputPath(applicationRoot, options.install());

        MigrationResult result = migrate(sourceXml, database);
        System.out.println("Migrated SDRTrunk XML playlist to SQLite");
        System.out.println("  XML: " + result.sourceXml());
        System.out.println("  SQLite: " + result.database());
        System.out.println("  aliases: " + result.aliasCount());
        System.out.println("  streams: " + result.streamCount());
        System.out.println("  channel maps: " + result.channelMapCount());
        System.out.println("  channels: " + result.channelCount());
        System.out.println("  P25 conventional conversions: " + result.p25ConventionalConversions());
    }

    private static Path defaultOutputPath(Path applicationRoot, boolean install)
    {
        Path databaseDirectory = applicationRoot.resolve(SdrTrunkDatabasePath.DATABASE_DIRECTORY);

        if(install)
        {
            return databaseDirectory.resolve(SdrTrunkDatabasePath.DATABASE_FILENAME);
        }

        return databaseDirectory.resolve("sdrtrunk-migrated-" + TIMESTAMP.format(LocalDateTime.now()) + ".sqlite");
    }

    private static void printUsage()
    {
        System.out.println("""
            Usage: XmlPlaylistToSqliteMigrator [options]

            Options:
              --app-root <path>   SDRTrunk application root. Defaults to the configured application root.
              --xml <path>        Legacy playlist XML. Defaults to app-root/playlist/default.xml, then playlist_v2.xml.
              --output <path>     SQLite output file. Refuses to overwrite.
              --install           Write to app-root/database/sdrtrunk.sqlite. Refuses to overwrite.
              --help              Show this help.
            """);
    }

    public record MigrationResult(Path sourceXml, Path database, int aliasCount, int streamCount, int channelMapCount,
                                  int channelCount, int p25ConventionalConversions)
    {
    }

    private record CliOptions(Path applicationRoot, Path sourceXml, Path databasePath, boolean install, boolean help)
    {
        private static CliOptions parse(String[] args)
        {
            Path applicationRoot = null;
            Path sourceXml = null;
            Path databasePath = null;
            boolean install = false;
            boolean help = false;

            for(int x = 0; x < args.length; x++)
            {
                String arg = args[x];

                switch(arg)
                {
                    case "--app-root" -> applicationRoot = Path.of(nextValue(args, ++x, arg));
                    case "--xml" -> sourceXml = Path.of(nextValue(args, ++x, arg));
                    case "--output" -> databasePath = Path.of(nextValue(args, ++x, arg));
                    case "--install" -> install = true;
                    case "--help", "-h" -> help = true;
                    default -> throw new IllegalArgumentException("Unrecognized argument: " + arg);
                }
            }

            if(install && databasePath != null)
            {
                throw new IllegalArgumentException("Use --install or --output, not both");
            }

            return new CliOptions(applicationRoot, sourceXml, databasePath, install, help);
        }

        private static String nextValue(String[] args, int index, String option)
        {
            if(index >= args.length)
            {
                throw new IllegalArgumentException("Missing value for " + option);
            }

            return args[index];
        }
    }

    @JacksonXmlRootElement(localName = "playlist")
    private static class XmlPlaylist
    {
        private int mVersion = ConfigurationManager.CONFIGURATION_CURRENT_VERSION;
        private List<Alias> mAliases = new ArrayList<>();
        private List<BroadcastConfiguration> mBroadcastConfigurations = new ArrayList<>();
        private List<ChannelMap> mChannelMaps = new ArrayList<>();
        private List<Channel> mChannels = new ArrayList<>();

        @JacksonXmlProperty(isAttribute = true, localName = "version")
        public int getVersion()
        {
            return mVersion;
        }

        public void setVersion(int version)
        {
            mVersion = version;
        }

        @JacksonXmlProperty(isAttribute = false, localName = "alias")
        public List<Alias> getAliases()
        {
            return mAliases;
        }

        public void setAliases(List<Alias> aliases)
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

        @JacksonXmlProperty(isAttribute = false, localName = "channel_map")
        public List<ChannelMap> getChannelMaps()
        {
            return mChannelMaps;
        }

        public void setChannelMaps(List<ChannelMap> channelMaps)
        {
            mChannelMaps = channelMaps;
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
}
