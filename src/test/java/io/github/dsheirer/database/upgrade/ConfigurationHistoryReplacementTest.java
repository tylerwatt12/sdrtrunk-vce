/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.ActivityDatabasePath;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.configuration.ConfigurationRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ConfigurationHistoryReplacementTest
{
    @TempDir Path directory;
    @Test void failedReplacementRestoresFormat45Monolith() throws Exception
    { assertReplacementRestoresExactBackup(45); }

    @Test void failedReplacementRestoresFormat62Monolith() throws Exception
    { assertReplacementRestoresExactBackup(62); }

    @Test void failedReplacementRestoresFormat76Pair() throws Exception
    { assertReplacementRestoresExactBackup(76); }

    @Test void failedReplacementRestoresCurrentPairAndAudioOrderPreference() throws Exception
    { assertReplacementRestoresExactBackup(DatabaseFormatCatalog.CURRENT_VERSION); }

    @Test void failedReplacementRestoresFormat77PairAndAudioOrderPreference() throws Exception
    { assertReplacementRestoresExactBackup(77); }

    private void assertReplacementRestoresExactBackup(int version) throws Exception
    {
        Path root=directory.resolve("profile-" + version);
        Path path=SdrTrunkDatabasePath.getDatabasePath(root);
        Path current=switch(version)
        {
            case 45 -> Format45TestDatabase.create(path);
            case 62 -> Format62TestDatabase.create(path);
            case 76 -> Format76TestDatabase.create(path);
            case 77 -> Format77TestDatabase.create(path);
            case DatabaseFormatCatalog.CURRENT_VERSION -> CurrentFormatTestDatabase.create(path);
            default -> throw new IllegalArgumentException();
        };
        if(version >= 77)
        {
            assertTrue(new ConfigurationRepository(current).load().channels().stream()
                .anyMatch(channel -> channel.getUseAutoStartOrderForAudio()),"The format-77-and-later fixture is opted in");
            try(var connection=DriverManager.getConnection("jdbc:sqlite:" + current);
                var statement=connection.createStatement())
            {
                statement.executeUpdate("INSERT INTO configuration_broadcast_stream(configuration_id,sort_order,config_json) " +
                    "VALUES('00000000-0000-4000-8000-000000000077'," +
                    "(SELECT coalesce(max(sort_order),-1)+1 FROM configuration_broadcast_stream)," +
                    "json_object('type','broadcastifyCallConfiguration','name','Restored health opt-out'," +
                    "'healthReports',json('false')))");
            }
        }
        String originalDigest=dataDigest(current);
        Path history=ActivityDatabasePath.forConfiguration(current);
        String originalHistoryDigest=version >= 63 ? dataDigest(history) : null;
        Path selected=directory.resolve("selected-" + version + ".sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(selected);
        ApplicationMigrationService service=new ApplicationMigrationService(SqliteDatabaseSnapshot::create,
            ApplicationMigrationServiceTestSupport::runMigratorInProcess,promoted -> {
                throw new SQLException("injected paired replacement validation failure");
            });
        SQLException failure=assertThrows(SQLException.class,() -> service.replaceCurrentDatabase(selected,root,null));
        assertTrue(failure.getMessage().contains("injected paired replacement"));
        assertEquals(version >= 63,Files.exists(history));
        try(var connection=DriverManager.getConnection("jdbc:sqlite:" + current))
        { assertEquals(version,DatabaseFormatCatalog.inspect(connection).version()); }
        assertEquals(originalDigest,dataDigest(current));
        if(version >= 63) assertEquals(originalHistoryDigest,dataDigest(history));
        if(version >= 77)
            assertFalse(((io.github.dsheirer.audio.broadcast.broadcastify.BroadcastifyCallConfiguration)
                new ConfigurationRepository(current).load().broadcastConfigurations().stream()
                    .filter(provider -> provider.getConfigurationId().equals("00000000-0000-4000-8000-000000000077"))
                    .findFirst().orElseThrow()).isHealthReports(),"Restoration must retain an explicit health-report opt-out");
    }

    @Test void legacyPairValidationIsReadOnlyAndRejectsMixedIdentityRoleAndSchema() throws Exception
    {
        Path valid=Format76TestDatabase.create(directory.resolve("valid-pair/sdrtrunk.sqlite"));
        Path validHistory=ActivityDatabasePath.forConfiguration(valid);
        byte[] configurationBytes=Files.readAllBytes(valid),historyBytes=Files.readAllBytes(validHistory);
        ApplicationMigrationService.validateSupportedRestoredDatabase(valid);
        assertArrayEquals(configurationBytes,Files.readAllBytes(valid));
        assertArrayEquals(historyBytes,Files.readAllBytes(validHistory));

        for(String defect: java.util.List.of("identity","role","schema","version"))
        {
            Path configuration=Format76TestDatabase.create(directory.resolve(defect + "/sdrtrunk.sqlite"));
            Path history=ActivityDatabasePath.forConfiguration(configuration);
            try(var connection=DriverManager.getConnection("jdbc:sqlite:" + history);
                var mutation=connection.createStatement())
            {
                switch(defect)
                {
                    case "identity" -> mutation.executeUpdate("UPDATE database_metadata SET value='00000000-0000-0000-0000-000000000001' " +
                        "WHERE key='configuration_history_pair_id'");
                    case "role" -> mutation.executeUpdate("UPDATE database_metadata SET value='configuration' WHERE key='database_role'");
                    case "schema" -> mutation.execute("CREATE TABLE unexpected_restore(id INTEGER)");
                    case "version" -> mutation.executeUpdate("UPDATE database_metadata SET value='68' WHERE key='database_format_version'");
                    default -> throw new IllegalArgumentException();
                }
            }
            assertThrows(SQLException.class,() -> ApplicationMigrationService.validateSupportedRestoredDatabase(configuration),defect);
        }
    }

    @Test void restoredLegacyReaderRequiresExactAdmissionAndDoesNotRelaxCurrentLoading() throws Exception
    {
        Path database=Format62TestDatabase.create(directory.resolve("legacy-reader.sqlite"));
        ConfigurationRepository repository=new ConfigurationRepository(database);
        try(var connection=DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            var snapshot=repository.loadRestoredLegacyConfiguration(connection,62);
            assertFalse(snapshot.channels().isEmpty());
            assertTrue(snapshot.channels().stream().noneMatch(channel -> channel.getUseAutoStartOrderForAudio()));
            assertThrows(SQLException.class,() -> repository.load(connection),
                "The current loader must not accept a historical schema");
            assertThrows(SQLException.class,() -> repository.loadRestoredLegacyConfiguration(connection,45));
            assertThrows(SQLException.class,() -> repository.loadRestoredLegacyConfiguration(connection,63));
            try(var statement=connection.createStatement()) { statement.execute("CREATE TABLE unexpected_restore(id INTEGER)"); }
            assertThrows(SQLException.class,() -> repository.loadRestoredLegacyConfiguration(connection,62));
        }
    }

    @Test void everySupportedLegacyConfigurationValidatesWithoutChangingItsFrozenFiles() throws Exception
    {
        for(int version=45;version<DatabaseFormatCatalog.CURRENT_VERSION;version++)
        {
            Path database=directory.resolve("frozen-" + version + "/sdrtrunk.sqlite");
            Files.createDirectories(database.getParent());
            Class.forName(getClass().getPackageName() + ".Format" + version + "TestDatabase")
                .getMethod("create",Path.class).invoke(null,database);
            Path history=ActivityDatabasePath.forConfiguration(database);
            byte[] original=Files.readAllBytes(database);
            byte[] originalHistory=version>=63 ? Files.readAllBytes(history) : null;
            ApplicationMigrationService.validateSupportedRestoredDatabase(database);
            try(var connection=DriverManager.getConnection("jdbc:sqlite:" + database))
            {
                var snapshot=new ConfigurationRepository(database).loadRestoredLegacyConfiguration(connection,version);
                assertFalse(snapshot.channels().isEmpty(),"format " + version);
                if(version < 77)
                    assertTrue(snapshot.channels().stream().noneMatch(channel -> channel.getUseAutoStartOrderForAudio()),
                        "Legacy channels remain opted out in format " + version);
                else
                    assertTrue(snapshot.channels().stream().anyMatch(channel -> channel.getUseAutoStartOrderForAudio()),
                        "The saved audio-order opt-in survives legacy reading in format " + version);
            }
            assertArrayEquals(original,Files.readAllBytes(database),"configuration format " + version);
            if(version>=63) assertArrayEquals(originalHistory,Files.readAllBytes(history),"history format " + version);
        }
    }

    private static String dataDigest(Path database) throws Exception
    {
        var tables=new java.util.ArrayList<String>();
        var data=new java.util.ArrayList<String>();
        try(var connection=DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            try(var query=connection.createStatement();var rows=query.executeQuery(
                "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name"))
            { while(rows.next()) tables.add(rows.getString(1)); }
            for(String table:tables)
            {
                var contents=new java.util.ArrayList<String>();
                try(var query=connection.createStatement();var rows=query.executeQuery("SELECT * FROM \"" + table + "\""))
                {
                    while(rows.next())
                    {
                        var values=new java.util.ArrayList<Object>();
                        for(int column=1;column<=rows.getMetaData().getColumnCount();column++)
                        {
                            Object value=rows.getObject(column);
                            values.add(value instanceof byte[] bytes ? java.util.HexFormat.of().formatHex(bytes) : value);
                        }
                        contents.add(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(values));
                    }
                }
                java.util.Collections.sort(contents);data.add(table + contents);
            }
        }
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
            .digest(data.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
