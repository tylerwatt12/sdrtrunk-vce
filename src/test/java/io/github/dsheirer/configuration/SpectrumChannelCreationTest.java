/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.configuration;

import static org.junit.jupiter.api.Assertions.*;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.alias.AliasAdministrationServiceTestSupport;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.channel.ChannelAdministrationService;
import io.github.dsheirer.channel.ChannelAdministrationServiceTestSupport;
import io.github.dsheirer.channel.ChannelDefinition;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkTestDatabase;
import io.github.dsheirer.database.configuration.ConfigurationRepository;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.directory.DirectoryPreference;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpectrumChannelCreationTest
{
    @TempDir Path temporary;

    @Test void discoveryReusesFriendlyNamesAndRecognizesAnAlreadySavedServingSite() throws Exception
    {
        try(Fixture fixture = fixture())
        {
            P25SiteIdentity identity = new P25SiteIdentity(0xBEE00, 0x123, 1, 2);
            var review = fixture.channels.discoveryReview("p25-phase1", 851_012_500, "Tuner", identity, "C4FM");
            var template = review.template();
            var named = new ChannelDefinition(null, template.protocolId(), "County Public Safety", "North",
                "North control", null, template.aliasListId(), template.source(), template.settings(),
                List.of(), List.of(), List.of(), List.of(), ChannelDefinition.Observed.EMPTY);
            var saved = fixture.channels.createDiscovered(named, identity, "County P25", review.revision(), false);
            var known = fixture.channels.discoverySiteMatch(identity);
            assertEquals(saved.configurationId(), known.configurationId());
            assertEquals("North control", known.name());
            assertEquals("County Public Safety", known.system());
            assertEquals("North", known.site());
            var sameSite = fixture.channels.discoveryReview("p25-phase1", 852_012_500, "Tuner", identity, "C4FM");
            assertEquals("County Public Safety", sameSite.template().system());
            assertEquals("North", sameSite.template().site());
            var otherSite = fixture.channels.discoveryReview("p25-phase1", 852_012_500, "Tuner",
                new P25SiteIdentity(0xBEE00, 0x123, 1, 3), "CQPSK");
            assertEquals("County Public Safety", otherSite.template().system());
            assertNull(fixture.channels.discoverySiteMatch(new P25SiteIdentity(0xBEE00, 0x123, 1, 3)));
            var unrelated = fixture.channels.discoveryReview("p25-phase1", 853_012_500, "Tuner",
                new P25SiteIdentity(0xBEE01, 0x123, 1, 2), "CQPSK");
            assertEquals("P25 BEE01-123", unrelated.template().system());
        }
    }

    @Test void savedForLaterKeepsAutomaticStartupOffWithoutLeavingAnOrderGap() throws Exception
    {
        try(Fixture fixture = fixture())
        {
            P25SiteIdentity first = new P25SiteIdentity(0xBEE00, 0x123, 1, 2);
            var review = fixture.channels.discoveryReview("p25-phase1", 851_012_500, "Tuner", first, "C4FM");
            var saved = fixture.channels.createDiscovered(review.template(), first, "County P25",
                review.revision(), false);
            var disk = new ConfigurationRepository(fixture.database).load();
            assertFalse(disk.channels().getFirst().isAutoStart());
            assertNull(disk.channels().getFirst().getAutoStartOrder());
            assertEquals("C4FM", fixture.channels.get(saved.configurationId()).channel().settings().get("modulation"));
            P25SiteIdentity second = new P25SiteIdentity(0xBEE00, 0x123, 1, 3);
            var next = fixture.channels.discoveryReview("p25-phase1", 852_012_500, "Tuner", second, "CQPSK");
            var listening = fixture.channels.createDiscovered(next.template(), second, null, next.revision(), true);
            disk = new ConfigurationRepository(fixture.database).load();
            var enabled = disk.channels().stream().filter(channel ->
                channel.getConfigurationId().equals(listening.configurationId())).findFirst().orElseThrow();
            assertTrue(enabled.isAutoStart());
            assertEquals(1, enabled.getAutoStartOrder());
            assertEquals(saved.aliasListId(), listening.aliasListId());
        }
    }

    @Test void longSystemNamesKeepAValidSuggestedAliasListName() throws Exception
    {
        try(Fixture fixture = fixture())
        {
            P25SiteIdentity identity = new P25SiteIdentity(0xBEE00, 0x123, 1, 2);
            var review = fixture.channels.discoveryReview("p25-phase1", 851_012_500, "Tuner", identity, "C4FM");
            var template = review.template();
            String system = "Countywide Regional Public Safety Radio System";
            var named = new ChannelDefinition(null, template.protocolId(), system, template.site(), template.name(),
                null, template.aliasListId(), template.source(), template.settings(), List.of(), List.of(),
                List.of(), List.of(), ChannelDefinition.Observed.EMPTY);
            fixture.channels.createDiscovered(named, identity, "County P25", review.revision(), false);
            var next = fixture.channels.discoveryReview("p25-phase1", 852_012_500, "Tuner",
                new P25SiteIdentity(0xBEE00, 0x123, 1, 3), "CQPSK");
            assertEquals(system, next.template().system());
            assertEquals("P25 BEE00-123", next.defaultNewAliasListName());
        }
    }

    @Test void newP25ListAndChannelAreSavedTogetherWithListeningDefaultsAndIdentity() throws Exception
    {
        try(Fixture fixture = fixture())
        {
            P25SiteIdentity identity = new P25SiteIdentity(0xBEE00, 0x123, 1, 2);
            var review = fixture.channels.discoveryReview("p25-phase1", 851_012_500, "Tuner", identity, "CQPSK");
            assertTrue(review.aliasLists().isEmpty());
            var saved = fixture.channels.createDiscovered(review.template(), identity, "County P25", review.revision());
            var disk = new ConfigurationRepository(fixture.database).load();
            var channel = disk.channels().getFirst();
            assertEquals(identity, channel.getP25SiteIdentity());
            assertTrue(channel.isAutoStart());
            assertEquals(1, channel.getAutoStartOrder());
            assertEquals("CQPSK", fixture.channels.get(saved.configurationId()).channel().settings().get("modulation"));
            assertEquals(true, fixture.channels.get(saved.configurationId()).channel().settings()
                .get("learn_announced_control_channels"));
            long scanId = disk.scanListConfiguration().defaultScanList().getId();
            assertEquals(java.util.Set.of(scanId), disk.scanListConfiguration()
                .scanListIdsForUnmatchedTalkgroups(saved.aliasListId()));
            assertEquals(java.util.Set.of(scanId), disk.scanListConfiguration()
                .scanListIdsForNewAliases(saved.aliasListId()));
            assertFalse(fixture.channels.discoveryFrequencyMatches(851_012_500).isEmpty(),
                "Stopped saved channels reserve their spectrum");
            var next = fixture.channels.discoveryReview("p25-phase1", 852_012_500, "Tuner",
                new P25SiteIdentity(0xBEE00, 0x123, 1, 3), "C4FM");
            assertEquals(saved.aliasListId(), next.suggestedAliasListId());
            var reused = fixture.channels.createDiscovered(
                withAlias(next.template(), 0), new P25SiteIdentity(0xBEE00, 0x123, 1, 3),
                "Duplicate System List", next.revision());
            assertEquals(saved.aliasListId(), reused.aliasListId());
            assertEquals(disk.aliasListDefinitions().size(),
                new ConfigurationRepository(fixture.database).load().aliasListDefinitions().size());
            assertTrue(fixture.channels.discoveryReview("p25-phase1", 853_012_500, "Tuner",
                new P25SiteIdentity(0xBEE01, 0x123, 1, 3), "C4FM").aliasLists().isEmpty(),
                "System ID alone must never match another WACN");
        }
    }

    @Test void analogCreationNeedsNoP25IdentityAndKeepsExistingDefaults() throws Exception
    {
        try(Fixture fixture = fixture())
        {
            var review = fixture.channels.discoveryReview("am", 118_100_000, "Tuner", null, null);
            ChannelDefinition template = withAlias(review.template(), 0);
            var saved = fixture.channels.createDiscovered(template, null, "Aviation", review.revision());
            var disk = new ConfigurationRepository(fixture.database).load();
            var scans = disk.scanListConfiguration();
            var next = fixture.channels.discoveryReview("nbfm", 155_100_000, "Tuner", null, null);
            var reused = fixture.channels.createDiscovered(withAlias(next.template(), 0), null,
                "aviation", next.revision());
            var later = new ConfigurationRepository(fixture.database).load();
            assertEquals(2, later.channels().size());
            assertEquals(saved.aliasListId(), reused.aliasListId());
            assertEquals(scans.newAliasListMemberships(), later.scanListConfiguration().newAliasListMemberships());
            assertNull(later.channels().getLast().getP25SiteIdentity());
            assertEquals(2, later.channels().getLast().getAutoStartOrder());
        }
    }

    @Test void existingAliasListNameReusesOneListAcrossP25SitesAndKeepsListeningDefaults() throws Exception
    {
        try(Fixture fixture = fixture())
        {
            P25SiteIdentity first = new P25SiteIdentity(0xBEE00, 0x123, 1, 2);
            var review = fixture.channels.discoveryReview("p25-phase1", 851_012_500, "Tuner", first, "C4FM");
            var saved = fixture.channels.createDiscovered(review.template(), first, "County P25",
                review.revision(), false);
            var before = new ConfigurationRepository(fixture.database).load();
            P25SiteIdentity second = new P25SiteIdentity(0xBEE00, 0x123, 1, 3);
            var next = fixture.channels.discoveryReview("p25-phase1", 852_012_500, "Tuner", second, "C4FM");
            var reused = fixture.channels.createDiscovered(withAlias(next.template(), 0), second,
                " county p25 ", next.revision(), false);
            var after = new ConfigurationRepository(fixture.database).load();
            assertEquals(saved.aliasListId(), reused.aliasListId());
            assertEquals(before.aliasListDefinitions().size(), after.aliasListDefinitions().size());
            assertEquals(before.scanListConfiguration().newAliasListMemberships(),
                after.scanListConfiguration().newAliasListMemberships());
            assertEquals(before.scanListConfiguration().unmatchedAliasListMemberships(),
                after.scanListConfiguration().unmatchedAliasListMemberships());
            assertEquals(2, after.channels().size());

            P25SiteIdentity other = new P25SiteIdentity(0xBEE01, 0x123, 1, 4);
            var incompatible = fixture.channels.discoveryReview("p25-phase1", 853_012_500, "Tuner", other, "C4FM");
            assertThrows(IllegalArgumentException.class, () -> fixture.channels.createDiscovered(
                withAlias(incompatible.template(), 0), other, "County P25", incompatible.revision(), false));
            assertEquals(2, new ConfigurationRepository(fixture.database).load().channels().size());
        }
    }

    @Test void existingConventionalAliasListCanReceiveAFirstP25SiteButWrongProtocolCannot() throws Exception
    {
        try(Fixture fixture = fixture())
        {
            var aliases = AliasAdministrationServiceTestSupport.create(fixture.manager);
            long existingId = aliases.createAliasList("County P25", AliasListFamily.P25).aliasListId();
            aliases.createAliasList("Analog", AliasListFamily.NBFM);
            var conventional = fixture.channels.template("p25-conventional");
            fixture.channels.create(new ChannelDefinition(null, conventional.protocolId(), null, null,
                "Dispatch", null, existingId,
                new ChannelDefinition.Source(List.of(155_100_000L), null, null, null, null, null),
                conventional.settings(), List.of(), List.of(), List.of(), List.of(), ChannelDefinition.Observed.EMPTY),
                fixture.channels.currentRevision());
            P25SiteIdentity identity = new P25SiteIdentity(0xBEE00, 0x123, 1, 2);
            var review = fixture.channels.discoveryReview("p25-phase1", 851_012_500, "Tuner", identity, "C4FM");
            assertTrue(review.aliasLists().isEmpty());
            assertThrows(IllegalArgumentException.class, () -> fixture.channels.createDiscovered(
                review.template(), identity, "Analog", review.revision(), false));
            var before = new ConfigurationRepository(fixture.database).load();
            var saved = fixture.channels.createDiscovered(review.template(), identity, "County P25",
                review.revision(), false);
            var disk = new ConfigurationRepository(fixture.database).load();
            assertEquals(existingId, saved.aliasListId());
            assertEquals(before.aliasListDefinitions().size(), disk.aliasListDefinitions().size());
            assertEquals(1, disk.aliasListDefinitions().stream()
                .filter(list -> "County P25".equalsIgnoreCase(list.getName())).count());
            assertEquals(2, disk.channels().size());
        }
    }

    @Test void failedDatabaseInsertLeavesNoAliasListOrChannelInDatabaseOrRuntime() throws Exception
    {
        try(Fixture fixture = fixture())
        {
            var before = new ConfigurationRepository(fixture.database).load();
            try(var connection = DriverManager.getConnection("jdbc:sqlite:" + fixture.database);
                var statement = connection.createStatement())
            {
                statement.execute("CREATE TRIGGER fail_discovery BEFORE INSERT ON configuration_channel " +
                    "BEGIN SELECT RAISE(ABORT, 'test failure'); END");
            }
            var review = fixture.channels.discoveryReview("am", 118_100_000, "Tuner", null, null);
            assertThrows(ChannelAdministrationService.PersistenceException.class, () ->
                fixture.channels.createDiscovered(withAlias(review.template(), 0), null, "Failed List", review.revision()));
            var after = new ConfigurationRepository(fixture.database).load();
            assertEquals(before.aliasListDefinitions().size(), after.aliasListDefinitions().size());
            assertTrue(after.channels().isEmpty());
            assertNull(fixture.manager.getAliasModel().getAliasListDefinition("Failed List"));
            assertTrue(fixture.manager.getChannelModel().getChannels().isEmpty());
        }
    }

    @Test void duplicateFrequencyAndStaleRevisionDoNotCreateAdditionalLists() throws Exception
    {
        try(Fixture fixture = fixture())
        {
            var review = fixture.channels.discoveryReview("nbfm", 155_100_000, "Tuner", null, null);
            fixture.channels.createDiscovered(withAlias(review.template(), 0), null, "First", review.revision());
            assertThrows(ChannelAdministrationService.StaleRevisionException.class, () ->
                fixture.channels.createDiscovered(withAlias(review.template(), 0), null, "Stale", review.revision()));
            var current = fixture.channels.discoveryReview("nbfm", 155_100_000, "Tuner", null, null);
            assertThrows(IllegalStateException.class, () -> fixture.channels.createDiscovered(
                withAlias(current.template(), 0), null, "Duplicate", current.revision()));
            assertNull(fixture.manager.getAliasModel().getAliasListDefinition("Stale"));
            assertNull(fixture.manager.getAliasModel().getAliasListDefinition("Duplicate"));
        }
    }

    private static ChannelDefinition withAlias(ChannelDefinition source, long id)
    {
        return new ChannelDefinition(null, source.protocolId(), source.system(), source.site(), source.name(), null,
            id, source.source(), source.settings(), List.of(), List.of(), List.of(), List.of(),
            ChannelDefinition.Observed.EMPTY);
    }

    private Fixture fixture() throws Exception
    {
        Path root = temporary.resolve(java.util.UUID.randomUUID().toString());
        Path database = SdrTrunkDatabasePath.getDatabasePath(root);
        SdrTrunkTestDatabase.create(database);
        UserPreferences preferences = new UserPreferences()
        {
            final DirectoryPreference directory = new DirectoryPreference(type -> {})
            { @Override public Path getDirectoryApplicationRoot() { return root; } };
            @Override public DirectoryPreference getDirectoryPreference() { return directory; }
        };
        ConfigurationManager manager = new ConfigurationManager(preferences, null, new AliasModel(), null, null);
        manager.init();
        return new Fixture(manager, ChannelAdministrationServiceTestSupport.create(manager), database);
    }

    private record Fixture(ConfigurationManager manager, ChannelAdministrationService channels, Path database)
        implements AutoCloseable
    {
        @Override public void close()
        {
            manager.flushConfiguration();
            MyEventBus.getGlobalEventBus().unregister(manager.getChannelProcessingManager());
        }
    }
}
