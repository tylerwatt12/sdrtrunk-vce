/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.configuration;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.channel.ChannelAdministrationService;
import io.github.dsheirer.channel.ChannelAdministrationServiceTestSupport;
import io.github.dsheirer.channel.ChannelDefinition;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkTestDatabase;
import io.github.dsheirer.database.configuration.ConfigurationDatabaseStore;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.directory.DirectoryPreference;
import io.github.dsheirer.source.config.SourceConfigTunerMultipleFrequency;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChannelConflictScopeTest
{
    @TempDir Path temporary;

    @Test
    void unrelatedEditsRuntimeStateAndAutoStartDoNotInvalidateAChannelEdit() throws Exception
    {
        try(Fixture f = fixture("unrelated"))
        {
            String first = f.create("First", 851_012_500L);
            String second = f.create("Second", 852_012_500L);
            var original = f.channels.get(first);
            var other = f.channels.get(second);
            f.channels.update(second, renamed(other.channel(), "Other edit"), other.editRevision(), other.channel(), false);
            long beforeRuntime = f.channels.currentRevision();
            f.live(first).processingProperty().set(true);
            f.live(first).processingProperty().set(false);
            assertEquals(beforeRuntime, f.channels.currentRevision(), "Processing alone must not dirty saved configuration");
            f.channels.setAutoStart(List.of(first), true, f.channels.currentRevision());
            f.create("Third", 853_012_500L);

            assertEquals(original.editRevision(), f.channels.get(first).editRevision());
            f.channels.update(first, renamed(original.channel(), "My edit"), original.editRevision(), original.channel(), false);
            assertEquals("My edit", f.channels.get(first).channel().name());
            assertEquals("Other edit", f.channels.get(second).channel().name());
            assertNotNull(f.channels.get(first).autoStartOrder());
            assertEquals(3, new ConfigurationDatabaseStore(f.manager.getDatabasePath()).load().channels().size());
        }
    }

    @Test
    void aNameEditPreservesConcurrentSiteLearningAndPreferredControl() throws Exception
    {
        try(Fixture f = fixture("learning"))
        {
            String id = f.create("Control", 851_012_500L);
            var original = f.channels.get(id);
            Channel live = f.live(id);
            var source = (SourceConfigTunerMultipleFrequency)live.getSourceConfiguration();
            source.addFrequency(852_012_500L);
            source.setPreferredFrequency(852_012_500L);
            ((DecodeConfigP25)live.getDecodeConfiguration()).addLearnedControlFrequency(852_012_500L);
            live.setSourceConfiguration(source);
            var identity = new P25SiteIdentity(0xBEE00, 0x123, 1, 2);
            live.bindP25SiteIdentity(identity);
            assertNotEquals(original.editRevision(), f.channels.get(id).editRevision());

            f.channels.update(id, renamed(original.channel(), "Renamed control"), original.editRevision(), original.channel(), false);
            var saved = f.channels.get(id).channel();
            assertEquals("Renamed control", saved.name());
            assertEquals(List.of(851_012_500L, 852_012_500L), saved.source().frequenciesHz());
            assertEquals(852_012_500L, saved.source().preferredFrequencyHz());
            assertEquals(List.of(852_012_500L), saved.observed().learnedControlFrequenciesHz());
            assertEquals(identity, f.live(id).getP25SiteIdentity());
            var stored = new ConfigurationDatabaseStore(f.manager.getDatabasePath()).load().channels().getFirst();
            assertEquals("Renamed control", stored.getName());
            assertEquals(List.of(851_012_500L, 852_012_500L),
                ((SourceConfigTunerMultipleFrequency)stored.getSourceConfiguration()).getFrequencies());
        }
    }

    @Test
    void overlappingEditFailsBeforePublicationAndDoesNotOverwriteTheOtherWriter() throws Exception
    {
        try(Fixture f = fixture("conflict"))
        {
            String id = f.create("Original", 851_012_500L);
            var original = f.channels.get(id);
            f.channels.update(id, renamed(original.channel(), "Their edit"), original.editRevision(), original.channel(), false);
            Channel live = f.live(id);
            long revision = f.channels.currentRevision();
            assertThrows(ChannelAdministrationService.StaleChannelException.class, () ->
                f.channels.update(id, renamed(original.channel(), "My edit"), original.editRevision(), original.channel(), false));
            assertSame(live, f.live(id));
            assertEquals(revision, f.channels.currentRevision());
            assertEquals("Their edit", f.channels.get(id).channel().name());
            assertEquals("Their edit", new ConfigurationDatabaseStore(f.manager.getDatabasePath()).load().channels().getFirst().getName());
        }
    }

    @Test
    void creationHasNoCatalogPreconditionAndStillValidatesCurrentAliasReferences() throws Exception
    {
        try(Fixture f = fixture("create"))
        {
            f.create("First", 851_012_500L);
            f.create("Second", 852_012_500L);
            ChannelDefinition template = f.channels.template("p25-phase1");
            ChannelDefinition invalid = new ChannelDefinition(null, template.protocolId(), "System", "Site", "Invalid",
                null, Long.MAX_VALUE, new ChannelDefinition.Source(List.of(853_012_500L), null, null, null, null, null),
                template.settings(), List.of(), List.of(), List.of(), List.of(), ChannelDefinition.Observed.EMPTY);
            assertThrows(ChannelAdministrationService.NotFoundException.class, () -> f.channels.create(invalid));
            assertEquals(2, f.channels.catalog().channels().size());
        }
    }

    private Fixture fixture(String name) throws Exception
    {
        Path root = temporary.resolve(name);
        SdrTrunkTestDatabase.create(SdrTrunkDatabasePath.getDatabasePath(root));
        var manager = new ConfigurationManager(new Preferences(root), null, new AliasModel(), null, null);
        manager.init();
        return new Fixture(manager, ChannelAdministrationServiceTestSupport.create(manager));
    }

    private record Fixture(ConfigurationManager manager, ChannelAdministrationService channels) implements AutoCloseable
    {
        String create(String name, long frequency)
        {
            var template = channels.template("p25-phase1");
            var definition = new ChannelDefinition(null, template.protocolId(), "System", "Site", name, null,
                template.aliasListId(), new ChannelDefinition.Source(List.of(frequency), null, null, null, null, null),
                template.settings(), List.of(), List.of(), List.of(), List.of(), ChannelDefinition.Observed.EMPTY);
            return channels.create(definition).configurationIds().getFirst();
        }
        Channel live(String id)
        {
            return manager.getChannelModel().getChannels().stream()
                .filter(channel -> id.equals(channel.getConfigurationId())).findFirst().orElseThrow();
        }
        public void close()
        {
            try { manager.flushConfiguration(); }
            finally { MyEventBus.getGlobalEventBus().unregister(manager.getChannelProcessingManager()); }
        }
    }

    private static ChannelDefinition renamed(ChannelDefinition c, String name)
    {
        return new ChannelDefinition(c.configurationId(), c.protocolId(), c.system(), c.site(), name,
            c.radioResolveId(), c.aliasListId(), c.source(), c.settings(), c.frequencyMap(), c.eventLogs(),
            c.recorders(), c.auxiliaryDecoders(), c.observed());
    }

    private static final class Preferences extends UserPreferences
    {
        private final DirectoryPreference directory;
        Preferences(Path root)
        {
            directory = new DirectoryPreference(type -> {})
            {
                @Override public Path getDirectoryApplicationRoot() { return root; }
            };
        }
        @Override public DirectoryPreference getDirectoryPreference() { return directory; }
    }
}
