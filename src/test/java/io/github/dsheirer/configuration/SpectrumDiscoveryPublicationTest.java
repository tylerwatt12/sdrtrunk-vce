/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.configuration;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dsheirer.alias.AliasConfigurationSnapshot;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.channel.ChannelAdministrationService;
import io.github.dsheirer.channel.ChannelAdministrationServiceTestSupport;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkTestDatabase;
import io.github.dsheirer.database.configuration.ConfigurationRepository;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.directory.DirectoryPreference;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Publication failure must report a real committed identity, while a suspended retry must report none. */
class SpectrumDiscoveryPublicationTest
{
    @TempDir Path root;

    @Test
    void failedPublicationReportsPersistedChannelAndBlocksASecondInsertBeforeCommit() throws Exception
    {
        Path database = SdrTrunkDatabasePath.getDatabasePath(root);
        SdrTrunkTestDatabase.create(database);
        UserPreferences preferences = new UserPreferences()
        {
            final DirectoryPreference directory = new DirectoryPreference(type -> {})
            { @Override public Path getDirectoryApplicationRoot() { return root; } };
            @Override public DirectoryPreference getDirectoryPreference() { return directory; }
        };
        ConfigurationManager manager = new ConfigurationManager(preferences, null, new AliasModel(), null, null)
        {
            @Override protected void publishCommittedAliasConfiguration(AliasConfigurationSnapshot committed,
                AliasConfigurationPublication publication)
            { throw new IllegalStateException("Injected publication failure"); }
        };
        manager.init();
        try
        {
            ChannelAdministrationService channels = ChannelAdministrationServiceTestSupport.create(manager);
            var identity = new P25SiteIdentity(0xBEE00, 0x123, 1, 2);
            var review = channels.discoveryReview("p25-phase1", 851_012_500, "Tuner", identity, "CQPSK");
            var committed = assertThrows(ConfigurationManager.ConfigurationPublicationException.class, () ->
                channels.createDiscovered(review.template(), identity, "County P25", review.revision(), false));
            var disk = new ConfigurationRepository(database).load();
            assertEquals(1, disk.channels().size());
            assertEquals(disk.channels().getFirst().getConfigurationId(), committed.committedConfigurationId());
            assertEquals(disk.channels().getFirst().getAliasListId(), committed.committedAliasListId());
            assertEquals(identity, disk.channels().getFirst().getP25SiteIdentity());
            assertFalse(disk.channels().getFirst().isAutoStart());
            assertTrue(manager.getChannelModel().getChannels().isEmpty(), "The committed channel could not publish");
            var next = channels.discoveryReview("p25-phase1", 852_012_500, "Tuner",
                new P25SiteIdentity(0xBEE00, 0x123, 1, 3), "C4FM");
            var suspended = assertThrows(ConfigurationManager.ConfigurationPublicationException.class, () ->
                channels.createDiscovered(next.template(), new P25SiteIdentity(0xBEE00, 0x123, 1, 3),
                    "Another list", next.revision(), true));
            assertNull(suspended.committedConfigurationId());
            assertNull(suspended.committedAliasListId());
            var afterRetry = new ConfigurationRepository(database).load();
            assertEquals(1, afterRetry.channels().size());
            assertEquals(disk.aliasListDefinitions().size(), afterRetry.aliasListDefinitions().size());
        }
        finally { MyEventBus.getGlobalEventBus().unregister(manager.getChannelProcessingManager()); }
    }
}
