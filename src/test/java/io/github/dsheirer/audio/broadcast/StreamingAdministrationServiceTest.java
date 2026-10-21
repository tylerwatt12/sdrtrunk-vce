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
import io.github.dsheirer.configuration.ChannelConfigurationSnapshot;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.decode.nbfm.DecodeConfigNBFM;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.source.config.SourceConfigTuner;
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
        ConfigurationManager manager=new ConfigurationManager(new TestPreferences(root),null,new AliasModel(),null);
        try
        {
            manager.init();
            StreamingAdministrationService streams=new StreamingAdministrationService(manager);
            var created=streams.save(null,"BROADCASTIFY_CALL",Map.of("name","Calls","api_key","secret-value","system_id",42),null);
            String id=created.configurationId();
            var stored=new ConfigurationDatabaseStore(database).load().broadcastConfigurations().getFirst();
            assertEquals(id,stored.getConfigurationId());
            assertEquals("Calls",stored.getName());
            assertThrows(StreamingAdministrationService.StaleRevisionException.class,() -> streams.save(id,"BROADCASTIFY_CALL",Map.of("name","Stale"),"old-collection-revision"));
            AliasAdministrationService aliases=AliasAdministrationServiceTestSupport.create(manager);
            long listId=aliases.createAliasList("County",AliasListFamily.P25).aliasListId();
            Alias alias=new Alias("Dispatch");
            alias.setAliasListDefinition(manager.getAliasModel().aliasListDefinitions().stream().filter(list -> list.getId()==listId).findFirst().orElseThrow());
            alias.setMatchIdentifier(new Talkgroup(Protocol.APCO25,101));
            long aliasId=aliases.createAlias(alias).aliasIds().getFirst();
            streams.assign(id,List.of(aliasId),List.of(),streams.aliases(id,"",false,0,50).revision());
            assertTrue(streams.aliases(id,"Dispatch",false,0,50).items().getFirst().assigned());
            assertThrows(IllegalArgumentException.class,() -> streams.delete(id,streams.get(id).editRevision()));
            var renamed=streams.save(id,"BROADCASTIFY_CALL",Map.of("name","County Calls"),streams.get(id).editRevision());
            assertTrue(manager.getAliasModel().getAliases().getFirst().hasBroadcastConfiguration(id));
            assertFalse(streams.get(id).toString().contains("secret-value"));
            try(Connection connection=SdrTrunkDatabase.open(database);var statement=connection.createStatement())
            {
                statement.execute("CREATE TRIGGER fail_stream_update BEFORE UPDATE ON configuration_broadcast_stream BEGIN SELECT RAISE(ABORT, 'test failure'); END");
            }
            assertThrows(ConfigurationManager.ConfigurationCommitException.class,() -> streams.save(id,"BROADCASTIFY_CALL",Map.of("name","Must not publish"),renamed.editRevision()));
            assertEquals("County Calls",manager.getBroadcastModel().getBroadcastConfiguration(id).getName());
            assertEquals("County Calls",new ConfigurationDatabaseStore(database).load().broadcastConfigurations().getFirst().getName());
            assertEquals(renamed.editRevision(),streams.get(id).editRevision());
            try(Connection connection=SdrTrunkDatabase.open(database);var statement=connection.createStatement()) { statement.execute("DROP TRIGGER fail_stream_update"); }
            streams.assign(id,List.of(),List.of(aliasId),streams.aliases(id,"",false,0,50).revision());
            var defaults=manager.createDetachedAliasConfigurationSnapshot();
            defaults.definitions().stream().filter(list -> list.getId()==listId).findFirst().orElseThrow()
                .setNewAliasBehavior(new NewAliasBehavior(false,List.of(new io.github.dsheirer.alias.id.broadcast.BroadcastChannel(id,"County Calls"))));
            manager.commitAndPublishAliasConfiguration(defaults,
                new ConfigurationManager.AliasConfigurationPublication(Set.of(),true,false,false));
            assertEquals(1,streams.get(id).references().aliasLists().size());
            assertThrows(IllegalArgumentException.class,() -> streams.delete(id,streams.get(id).editRevision()));
            defaults=manager.createDetachedAliasConfigurationSnapshot();
            defaults.definitions().stream().filter(list -> list.getId()==listId).findFirst().orElseThrow()
                .setNewAliasBehavior(NewAliasBehavior.DEFAULT);
            manager.commitAndPublishAliasConfiguration(defaults,
                new ConfigurationManager.AliasConfigurationPublication(Set.of(),true,false,false));
            streams.delete(id,streams.get(id).editRevision());
            assertTrue(streams.catalog().destinations().isEmpty());
            assertTrue(new ConfigurationDatabaseStore(database).load().broadcastConfigurations().isEmpty());
        }
        finally { manager.flushConfiguration();MyEventBus.getGlobalEventBus().unregister(manager.getChannelProcessingManager()); }
    }

    @Test void destinationEditsIgnoreOtherConfigurationAndRuntimeChangesButProtectTheSameDestination() throws Exception
    {
        Path root=temporary.resolve("scoped-edits");
        Path database=SdrTrunkDatabasePath.getDatabasePath(root);
        SdrTrunkTestDatabase.create(database);
        ConfigurationManager manager=new ConfigurationManager(new TestPreferences(root),null,new AliasModel(),null);
        try
        {
            manager.init();
            var streams=new StreamingAdministrationService(manager);
            String id=streams.save(null,"BROADCASTIFY_CALL",Map.of("name","Primary","api_key","first-secret"),null).configurationId();
            String baseline=streams.get(id).editRevision();
            long catalogBaseline=manager.getStreamingConfigurationRevision();
            String other=streams.save(null,"BROADCASTIFY_CALL",Map.of("name","Archive"),null).configurationId();
            streams.save(other,"BROADCASTIFY_CALL",Map.of("host","https://example.invalid/calls"),streams.get(other).editRevision());
            AliasAdministrationService aliases=AliasAdministrationServiceTestSupport.create(manager);
            long listId=aliases.createAliasList("County analog",AliasListFamily.NBFM).aliasListId();
            Channel channel=new Channel("Monitoring");
            channel.setAliasListDefinition(manager.getAliasModel().getAliasListDefinition(listId));
            channel.setDecodeConfiguration(new DecodeConfigNBFM());
            SourceConfigTuner source=new SourceConfigTuner(); source.setFrequency(155_000_000L);
            channel.setSourceConfiguration(source);
            manager.commitAndPublishChannelConfiguration(new ChannelConfigurationSnapshot(List.of(channel)),Set.of(channel.getConfigurationId()),false);
            io.github.dsheirer.controller.channel.ChannelTestAccess.setProcessing(manager.getChannelModel().getChannels().getFirst(), true);
            ConfiguredBroadcast configured = manager.getBroadcastModel().getConfiguredBroadcasts().stream()
                .filter(item->item.getBroadcastConfiguration().getConfigurationId().equals(id)).findFirst().orElseThrow();
            var sender = new AbstractAudioBroadcaster<BroadcastConfiguration>(configured.getBroadcastConfiguration()) {
                @Override public void start() {}
                @Override public void stop() {}
                @Override public void dispose() {}
                @Override public void receive(AudioRecording recording) {}
                @Override public int getAudioQueueSize() { return 0; }
            };
            configured.setAudioBroadcaster(sender);
            sender.setBroadcastState(BroadcastState.CONNECTING);
            assertNotEquals(catalogBaseline,manager.getStreamingConfigurationRevision());
            assertEquals(baseline,streams.get(id).editRevision(), "unrelated edits and runtime events preserve the destination baseline");
            var saved=streams.save(id,"BROADCASTIFY_CALL",Map.of("name","Updated primary"),baseline);
            assertEquals("Updated primary",streams.get(id).destination().settings().get("name"));
            assertEquals(2,new ConfigurationDatabaseStore(database).load().broadcastConfigurations().size());
            assertThrows(StreamingAdministrationService.StaleRevisionException.class,()->
                streams.save(id,"BROADCASTIFY_CALL",Map.of("name","Old editor"),baseline));
            assertThrows(StreamingAdministrationService.StaleRevisionException.class,()->streams.delete(id,baseline));
            assertThrows(StreamingAdministrationService.StaleRevisionException.class,()->
                streams.save(id,"BROADCASTIFY_CALL",Map.of("name","No baseline"),null));
            var credentials=streams.save(id,"BROADCASTIFY_CALL",Map.of("api_key","replacement-secret"),saved.editRevision());
            assertNotEquals(saved.editRevision(),credentials.editRevision(), "secret replacement is a saved-content conflict too");
            assertThrows(StreamingAdministrationService.StaleRevisionException.class,()->
                streams.save(id,"BROADCASTIFY_CALL",Map.of("name","Overwrites newer credential"),saved.editRevision()));
            assertFalse(streams.get(id).toString().contains("replacement-secret"));
            assertThrows(IllegalArgumentException.class,()->streams.save(null,"BROADCASTIFY_CALL",Map.of("name","Archive"),null));
            assertEquals(2,streams.catalog().destinations().size(), "create still checks fresh duplicate names");
            streams.delete(other,streams.get(other).editRevision());
            streams.save(id,"BROADCASTIFY_CALL",Map.of("name","Final primary"),credentials.editRevision());
        }
        finally { manager.flushConfiguration();MyEventBus.getGlobalEventBus().unregister(manager.getChannelProcessingManager()); }
    }

    @Test void destinationSaveValidatesCurrentSiteDependenciesInsteadOfRejectingAnUnrelatedVersion() throws Exception
    {
        Path root=temporary.resolve("fresh-site-selection");
        SdrTrunkTestDatabase.create(SdrTrunkDatabasePath.getDatabasePath(root));
        ConfigurationManager manager=new ConfigurationManager(new TestPreferences(root),null,new AliasModel(),null);
        try
        {
            manager.init();
            AliasAdministrationService aliases=AliasAdministrationServiceTestSupport.create(manager);
            long listId=aliases.createAliasList("County",AliasListFamily.P25).aliasListId();
            long otherList=aliases.createAliasList("Regional",AliasListFamily.P25).aliasListId();
            Channel channel=new Channel("County site");
            channel.setAliasListDefinition(manager.getAliasModel().getAliasListDefinition(listId));
            channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
            SourceConfigTuner source=new SourceConfigTuner(); source.setFrequency(851_000_000L);
            channel.setSourceConfiguration(source);
            manager.commitAndPublishChannelConfiguration(new ChannelConfigurationSnapshot(List.of(channel)),Set.of(channel.getConfigurationId()),false);
            var streams=new StreamingAdministrationService(manager);
            String id=streams.save(null,"BROADCASTIFY_CALL_SITE",Map.of("name","County site calls","api_key","fixture-key",
                "system_id",42,"alias_list_id",listId,"channel_configuration_id",channel.getConfigurationId()),null).configurationId();
            String baseline=streams.get(id).editRevision();
            assertEquals(1,streams.options().sites().size(), "only the assigned Alias List exposes this site");
            Channel reassigned=manager.getChannelModel().getChannels().getFirst().copyOfPreservingIdentity();
            reassigned.setAliasListDefinition(manager.getAliasModel().getAliasListDefinition(otherList));
            manager.commitAndPublishChannelConfiguration(new ChannelConfigurationSnapshot(List.of(reassigned)),Set.of(reassigned.getConfigurationId()),false);
            assertEquals(baseline,streams.get(id).editRevision());
            assertThrows(IllegalArgumentException.class,()->streams.save(id,"BROADCASTIFY_CALL_SITE",Map.of("enabled",true),baseline));
            assertFalse(streams.get(id).status().enabled());
            streams.save(id,"BROADCASTIFY_CALL_SITE",Map.of("name","Review site selection"),baseline);
            assertFalse(streams.get(id).status().enabled());
        }
        finally { manager.flushConfiguration();MyEventBus.getGlobalEventBus().unregister(manager.getChannelProcessingManager()); }
    }

    @Test void assignmentChecksTrackOnlyDestinationRoutingAndValidateFreshAliases() throws Exception
    {
        Path root=temporary.resolve("scoped-routing");
        SdrTrunkTestDatabase.create(SdrTrunkDatabasePath.getDatabasePath(root));
        ConfigurationManager manager=new ConfigurationManager(new TestPreferences(root),null,new AliasModel(),null);
        try
        {
            manager.init();
            var streams=new StreamingAdministrationService(manager);
            String id=streams.save(null,"BROADCASTIFY_CALL",Map.of("name","Calls"),null).configurationId();
            String other=streams.save(null,"BROADCASTIFY_CALL",Map.of("name","Archive"),null).configurationId();
            AliasAdministrationService aliases=AliasAdministrationServiceTestSupport.create(manager);
            long listId=aliases.createAliasList("County",AliasListFamily.P25).aliasListId();
            Alias first=new Alias("Dispatch");
            first.setAliasListDefinition(manager.getAliasModel().aliasListDefinitions().stream().filter(list->list.getId()==listId).findFirst().orElseThrow());
            first.setMatchIdentifier(new Talkgroup(Protocol.APCO25,101));
            long aliasId=aliases.createAlias(first).aliasIds().getFirst();
            String assignmentBaseline=streams.aliases(id,"",false,0,50).revision();
            streams.assign(other,List.of(aliasId),List.of(),streams.aliases(other,"",false,0,50).revision());
            AliasAdministrationService.AliasEntry opened=aliases.getAlias(aliasId);
            Alias renamed=opened.alias(); renamed.setName("Renamed dispatch");
            aliases.replaceAlias(aliasId,renamed,opened.editRevision());
            streams.save(id,"BROADCASTIFY_CALL",Map.of("name","County calls"),streams.get(id).editRevision());
            assertEquals(assignmentBaseline,streams.aliases(id,"",false,0,50).revision());
            streams.assign(id,List.of(aliasId),List.of(),assignmentBaseline);
            assertTrue(streams.aliases(id,"",true,0,50).items().getFirst().assigned());
            assertTrue(streams.aliases(other,"",true,0,50).items().getFirst().assigned(), "assignment changes preserve other destinations");
            assertThrows(StreamingAdministrationService.StaleRevisionException.class,()->
                streams.assign(id,List.of(),List.of(aliasId),assignmentBaseline));
            String current=streams.aliases(id,"",false,0,50).revision();
            assertThrows(IllegalArgumentException.class,()->streams.assign(id,List.of(Long.MAX_VALUE),List.of(),current));
            assertEquals(current,streams.aliases(id,"",false,0,50).revision(), "failed saves preserve routing");
        }
        finally { manager.flushConfiguration();MyEventBus.getGlobalEventBus().unregister(manager.getChannelProcessingManager()); }
    }

    @Test void everyProviderRoundTripsThroughTheExistingDatabase() throws Exception
    {
        Path root=temporary.resolve("all-providers");
        Path database=SdrTrunkDatabasePath.getDatabasePath(root);
        SdrTrunkTestDatabase.create(database);
        ConfigurationManager manager=new ConfigurationManager(new TestPreferences(root),null,new AliasModel(),null);
        try
        {
            manager.init();
            var service=new StreamingAdministrationService(manager);
            for(var provider: StreamingConfigurationCodec.providers())
            {
                assertTrue(service.template(provider.id()).configuredCredentials().isEmpty());
                String secret=provider.fields().stream().filter(field -> field.type().equals("password")).findFirst().orElseThrow().key();
                var saved=service.save(null,provider.id(),Map.of("name",provider.label(),secret,"fixture-only-secret"),null);
                var stored=new ConfigurationDatabaseStore(database).load().broadcastConfigurations().stream()
                    .filter(item -> item.getConfigurationId().equals(saved.configurationId())).findFirst().orElseThrow();
                assertEquals(provider.id(),stored.getBroadcastServerType().name());
                assertEquals(Set.of(secret),StreamingConfigurationCodec.view(stored).configuredCredentials());
                assertFalse(service.get(saved.configurationId()).toString().contains("fixture-only-secret"));
            }
            assertEquals(9,new ConfigurationDatabaseStore(database).load().broadcastConfigurations().size());
            var site=service.catalog().destinations().stream().filter(item -> item.provider().equals("BROADCASTIFY_CALL_SITE")).findFirst().orElseThrow();
            assertThrows(IllegalArgumentException.class,() -> service.save(site.configurationId(),site.provider(),
                Map.of("enabled",true,"system_id",42,"alias_list_id",999L,"channel_configuration_id",UUID.randomUUID().toString()),service.get(site.configurationId()).editRevision()));
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
