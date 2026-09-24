/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.audio.broadcast;

import static org.junit.jupiter.api.Assertions.*;
import io.github.dsheirer.alias.*;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.database.*;
import io.github.dsheirer.database.configuration.ConfigurationDatabaseStore;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.directory.DirectoryPreference;
import io.github.dsheirer.protocol.Protocol;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StreamingAdministrationServiceTest
{
    @TempDir Path temporary;

    @Test void everyProviderRetainsIdentityAndSecretsWithoutReturningThem() throws Exception
    {
        assertEquals(9, StreamingConfigurationCodec.providers().size());
        for(var provider: StreamingConfigurationCodec.providers())
        {
            var original=StreamingConfigurationCodec.template(provider.id());
            Map<String,Object> changes=new HashMap<>();
            changes.put("name","Test " + provider.id());
            String secret=provider.fields().stream().filter(field -> field.type().equals("password")).findFirst().orElseThrow().key();
            changes.put(secret,"test-secret-not-for-browser");
            boolean configurableAge=provider.fields().stream().anyMatch(field -> field.key().equals("maximum_recording_age"));
            if(configurableAge) changes.put("maximum_recording_age",123456L);
            var saved=StreamingConfigurationCodec.apply(original,changes);
            var renamed=StreamingConfigurationCodec.apply(saved,Map.of("name","Renamed"));
            assertEquals(original.getConfigurationId(),renamed.getConfigurationId());
            assertEquals(configurableAge ? 123456L : original.getMaximumRecordingAge(),renamed.getMaximumRecordingAge());
            assertEquals(Set.of(secret),StreamingConfigurationCodec.view(renamed).configuredCredentials());
            assertFalse(StreamingConfigurationCodec.view(renamed).settings().containsKey(secret));
            assertFalse(StreamingConfigurationCodec.view(renamed).toString().contains("test-secret-not-for-browser"));
            assertThrows(IllegalArgumentException.class,() -> StreamingConfigurationCodec.apply(saved,Map.of("configuration_id","other")));
            assertThrows(IllegalArgumentException.class,() -> StreamingConfigurationCodec.apply(saved,Map.of("maximum_recording_age",1.5)));
        }
    }

    @Test void durableCommandsProtectReferencesAndFailedSavesDoNotChangeRuntime() throws Exception
    {
        Path root=temporary.resolve("receiver");
        Path database=SdrTrunkDatabasePath.getDatabasePath(root);
        SdrTrunkTestDatabase.create(database);
        ConfigurationManager manager=new ConfigurationManager(new TestPreferences(root),null,new AliasModel(),null,null);
        try
        {
            manager.init();
            StreamingAdministrationService streams=new StreamingAdministrationService(manager,false);
            var initial=streams.catalog();
            var created=streams.save(null,"BROADCASTIFY_CALL",Map.of("name","Calls","api_key","secret-value","system_id",42),initial.revision());
            String id=created.configurationId();
            var stored=new ConfigurationDatabaseStore(database).load().broadcastConfigurations().getFirst();
            assertEquals(id,stored.getConfigurationId());
            assertEquals("Calls",stored.getName());
            assertThrows(StreamingAdministrationService.StaleRevisionException.class,() -> streams.save(id,"BROADCASTIFY_CALL",Map.of("name","Stale"),initial.revision()));
            AliasAdministrationService aliases=AliasAdministrationServiceTestSupport.create(manager);
            long listId=aliases.createAliasList("County",AliasListFamily.P25,aliases.currentRevision()).aliasListId();
            Alias alias=new Alias("Dispatch");
            alias.setAliasListDefinition(manager.getAliasModel().aliasListDefinitions().stream().filter(list -> list.getId()==listId).findFirst().orElseThrow());
            alias.setMatchIdentifier(new Talkgroup(Protocol.APCO25,101));
            long aliasId=aliases.createAlias(alias).aliasIds().getFirst();
            var assigned=streams.assign(id,List.of(aliasId),List.of(),streams.catalog().revision());
            assertTrue(streams.aliases(id,"Dispatch",false,0,50).items().getFirst().assigned());
            assertThrows(IllegalArgumentException.class,() -> streams.delete(id,assigned.revision()));
            var renamed=streams.save(id,"BROADCASTIFY_CALL",Map.of("name","County Calls"),streams.catalog().revision());
            assertTrue(manager.getAliasModel().getAliases().getFirst().hasBroadcastConfiguration(id));
            assertFalse(streams.get(id).toString().contains("secret-value"));
            try(Connection connection=SdrTrunkDatabase.open(database);var statement=connection.createStatement())
            {
                statement.execute("CREATE TRIGGER fail_stream_update BEFORE UPDATE ON configuration_broadcast_stream BEGIN SELECT RAISE(ABORT, 'test failure'); END");
            }
            assertThrows(ConfigurationManager.ConfigurationCommitException.class,() -> streams.save(id,"BROADCASTIFY_CALL",Map.of("name","Must not publish"),renamed.revision()));
            assertEquals("County Calls",manager.getBroadcastModel().getBroadcastConfiguration(id).getName());
            assertEquals("County Calls",new ConfigurationDatabaseStore(database).load().broadcastConfigurations().getFirst().getName());
            assertEquals(renamed.revision(),streams.catalog().revision());
            try(Connection connection=SdrTrunkDatabase.open(database);var statement=connection.createStatement()) { statement.execute("DROP TRIGGER fail_stream_update"); }
            var unassigned=streams.assign(id,List.of(),List.of(aliasId),streams.catalog().revision());
            var defaults=manager.createDetachedAliasConfigurationSnapshot();
            defaults.definitions().stream().filter(list -> list.getId()==listId).findFirst().orElseThrow()
                .setNewAliasBehavior(new NewAliasBehavior(false,List.of(new io.github.dsheirer.alias.id.broadcast.BroadcastChannel(id,"County Calls"))));
            manager.commitAndPublishAliasConfiguration(defaults,
                new ConfigurationManager.AliasConfigurationPublication(Set.of(),true,false,false),null);
            assertEquals(1,streams.get(id).references().aliasLists().size());
            assertThrows(IllegalArgumentException.class,() -> streams.delete(id,streams.catalog().revision()));
            defaults=manager.createDetachedAliasConfigurationSnapshot();
            defaults.definitions().stream().filter(list -> list.getId()==listId).findFirst().orElseThrow()
                .setNewAliasBehavior(NewAliasBehavior.DEFAULT);
            manager.commitAndPublishAliasConfiguration(defaults,
                new ConfigurationManager.AliasConfigurationPublication(Set.of(),true,false,false),null);
            streams.delete(id,streams.catalog().revision());
            assertTrue(streams.catalog().destinations().isEmpty());
            assertTrue(new ConfigurationDatabaseStore(database).load().broadcastConfigurations().isEmpty());
        }
        finally { manager.flushConfiguration();MyEventBus.getGlobalEventBus().unregister(manager.getChannelProcessingManager()); }
    }

    @Test void everyProviderRoundTripsThroughTheExistingDatabase() throws Exception
    {
        Path root=temporary.resolve("all-providers");
        Path database=SdrTrunkDatabasePath.getDatabasePath(root);
        SdrTrunkTestDatabase.create(database);
        ConfigurationManager manager=new ConfigurationManager(new TestPreferences(root),null,new AliasModel(),null,null);
        try
        {
            manager.init();
            var service=new StreamingAdministrationService(manager,false);
            for(var provider: StreamingConfigurationCodec.providers())
            {
                assertTrue(service.template(provider.id()).configuredCredentials().isEmpty());
                String secret=provider.fields().stream().filter(field -> field.type().equals("password")).findFirst().orElseThrow().key();
                var saved=service.save(null,provider.id(),Map.of("name",provider.label(),secret,"fixture-only-secret"),service.catalog().revision());
                var stored=new ConfigurationDatabaseStore(database).load().broadcastConfigurations().stream()
                    .filter(item -> item.getConfigurationId().equals(saved.configurationId())).findFirst().orElseThrow();
                assertEquals(provider.id(),stored.getBroadcastServerType().name());
                assertEquals(Set.of(secret),StreamingConfigurationCodec.view(stored).configuredCredentials());
                assertFalse(service.get(saved.configurationId()).toString().contains("fixture-only-secret"));
            }
            assertEquals(9,new ConfigurationDatabaseStore(database).load().broadcastConfigurations().size());
            var site=service.catalog().destinations().stream().filter(item -> item.provider().equals("BROADCASTIFY_CALL_SITE")).findFirst().orElseThrow();
            assertThrows(IllegalArgumentException.class,() -> service.save(site.configurationId(),site.provider(),
                Map.of("enabled",true,"system_id",42,"alias_list_id",999L,"channel_configuration_id",UUID.randomUUID().toString()),service.catalog().revision()));
            assertFalse(service.get(site.configurationId()).status().enabled());
        }
        finally { manager.flushConfiguration();MyEventBus.getGlobalEventBus().unregister(manager.getChannelProcessingManager()); }
    }

    static final class TestPreferences extends UserPreferences
    {
        private final DirectoryPreference directories;
        TestPreferences(Path root) { directories=new DirectoryPreference(type -> {}) {
            @Override public Path getDirectoryApplicationRoot() { return root; }
        }; }
        @Override public DirectoryPreference getDirectoryPreference() { return directories; }
    }
}
