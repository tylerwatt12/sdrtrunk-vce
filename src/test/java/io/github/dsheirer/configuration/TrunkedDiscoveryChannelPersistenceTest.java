/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.configuration;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.channel.ChannelAdministrationService;
import io.github.dsheirer.channel.ChannelAdministrationServiceTestSupport;
import io.github.dsheirer.channel.ChannelDefinition;
import io.github.dsheirer.channel.ChannelDefinitionCodec;
import io.github.dsheirer.channel.ChannelProtocolRegistry;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkTestDatabase;
import io.github.dsheirer.database.configuration.ConfigurationRepository;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.directory.DirectoryPreference;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.traffic.RadioSystemKey;
import io.github.dsheirer.stats.TrunkedDiscoveryEvidence;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrunkedDiscoveryChannelPersistenceTest
{
    @TempDir Path root;

    @Test
    void savesWeakVerifiedP25WithoutReintroducingAScoreGateOrAcceptingAModulationOverride() throws Exception
    {
        try(Fixture fixture = new Fixture(root))
        {
            var nativeSite = new P25SiteIdentity(0xABCDE, 0x123, 2, 7);
            String key = RadioSystemKey.p25(nativeSite);
            var identity = new TrunkedDiscoveryEvidence.Identity(nativeSite, key, key + ":2:7",
                null, nativeSite.system(), nativeSite.site(), null, null, null, null, null);
            var proof = new TrunkedDiscoveryEvidence("p25-phase1", "P25_PHASE_1", identity,
                Map.of("modulation", "C4FM", "learn_announced_control_channels", true), List.of(),
                3, 38, 38, 169, 7000, "Repeated identity on a weak signal");
            var review = fixture.channels.discoveryTrunkedReview("p25-phase1", 774_706_250, "Recording", proof);
            var browserSettings = new LinkedHashMap<>(review.template().settings());
            browserSettings.put("modulation", "CQPSK");
            var saved = fixture.channels.createTrunkedDiscovered(edited(review.template(), browserSettings, List.of()),
                proof, "Weak P25", review.revision(), false);
            var entry = fixture.channels.get(saved.configurationId());
            assertEquals("C4FM", entry.channel().settings().get("modulation"));
            assertNull(entry.autoStartOrder());
            assertEquals(nativeSite.wacn(), entry.channel().observed().p25SiteIdentity().get("wacn"));
            assertEquals(nativeSite.system(), entry.channel().observed().p25SiteIdentity().get("system"));
            assertEquals(nativeSite.rfss(), entry.channel().observed().p25SiteIdentity().get("rfss"));
            assertEquals(nativeSite.site(), entry.channel().observed().p25SiteIdentity().get("site"));
            var disk = new ConfigurationRepository(fixture.database).load();
            assertEquals(1, disk.channels().size());
            assertFalse(disk.channels().getFirst().isAutoStart());
            assertEquals(saved.configurationId(), fixture.channels.discoveryTrunkedSiteMatch(proof).configurationId());
        }
    }

    @Test
    void savesVerifiedDmrAndNxdnSettingsAndMapsWithoutAcceptingBrowserMapOrModeOverrides() throws Exception
    {
        try(Fixture fixture = new Fixture(root))
        {
            var dmr = evidence("dmr", "TIER_III", "dmr:tier3:small:17", 3, 450_000_000,
                Map.of("channel_mode", "TRUNKED", "use_compressed_talkgroups", true));
            var review = fixture.channels.discoveryTrunkedReview("dmr", 450_000_000, "Recording", dmr);
            assertEquals(dmr.frequencyMap(), review.template().frequencyMap());
            var settings = new LinkedHashMap<>(review.template().settings());
            settings.put("channel_mode", "CONVENTIONAL");
            settings.put("use_compressed_talkgroups", false);
            var browser = edited(review.template(), settings,
                List.of(new ChannelDefinition.FrequencyMapEntry(99, 999_000_000, 0)));
            var created = fixture.channels.createTrunkedDiscovered(browser, dmr, "DMR Test", review.revision(), false);
            var saved = fixture.channels.get(created.configurationId()).channel();
            assertEquals("TRUNKED", saved.settings().get("channel_mode"));
            assertEquals(true, saved.settings().get("use_compressed_talkgroups"));
            assertEquals(dmr.frequencyMap(), saved.frequencyMap());
            assertNull(fixture.channels.get(created.configurationId()).autoStartOrder());
            assertEquals(created.configurationId(), fixture.channels.discoveryTrunkedSiteMatch(dmr).configurationId());
            assertFalse(fixture.channels.discoveryFrequencyMatches(450_012_500).isEmpty(),
                "Resolved traffic map frequencies remain reserved after a save");

            var nxdn = evidence("nxdn", "TYPE_C_9600", "nxdn-c:regional:41", 5, 452_000_000,
                Map.of("channel_mode", "TRUNKED", "transmission_mode", "M9600"));
            var nxReview = fixture.channels.discoveryTrunkedReview("nxdn", 452_000_000, "Recording", nxdn);
            var nxSettings = new LinkedHashMap<>(nxReview.template().settings());
            nxSettings.put("transmission_mode", "TYPE_D");
            var nxCreated = fixture.channels.createTrunkedDiscovered(edited(nxReview.template(), nxSettings, List.of()),
                nxdn, "NXDN Test", nxReview.revision(), true);
            var disk = new ConfigurationRepository(fixture.database).load();
            assertEquals(2, disk.channels().size());
            var codec = new ChannelDefinitionCodec(new ChannelProtocolRegistry());
            var roundTrip = disk.channels().stream().filter(channel ->
                channel.getConfigurationId().equals(nxCreated.configurationId())).map(codec::fromChannel)
                .findFirst().orElseThrow();
            assertEquals("M9600", roundTrip.settings().get("transmission_mode"));
            assertEquals(nxdn.frequencyMap(), roundTrip.frequencyMap());
            assertTrue(disk.channels().stream().filter(channel ->
                channel.getConfigurationId().equals(nxCreated.configurationId())).findFirst().orElseThrow().isAutoStart());
        }
    }

    @Test
    void reusesAliasesOnlyForExactNativeSystemsAndRejectsADuplicateSiteOnAnotherControlFrequency() throws Exception
    {
        try(Fixture fixture = new Fixture(root))
        {
            var first = evidence("dmr", "TIER_III", "dmr:tier3:small:17", 3, 450_000_000,
                Map.of("channel_mode", "TRUNKED"));
            var review = fixture.channels.discoveryTrunkedReview("dmr", 450_000_000, null, first);
            var created = fixture.channels.createTrunkedDiscovered(review.template(), first, "Network 17", review.revision(), false);
            var nextSite = evidence("dmr", "TIER_III", "dmr:tier3:small:17", 4, 452_000_000,
                Map.of("channel_mode", "TRUNKED"));
            var next = fixture.channels.discoveryTrunkedReview("dmr", 452_000_000, null, nextSite);
            assertEquals(created.aliasListId(), next.suggestedAliasListId());
            assertEquals(1, next.aliasLists().size());
            assertEquals("Network 17", next.aliasLists().getFirst().name());
            fixture.channels.createTrunkedDiscovered(next.template(), nextSite, null, next.revision(), false);

            var unrelated = evidence("dmr", "TIER_III", "dmr:tier3:small:18", 3, 454_000_000,
                Map.of("channel_mode", "TRUNKED"));
            assertTrue(fixture.channels.discoveryTrunkedReview("dmr", 454_000_000, null, unrelated).aliasLists().isEmpty());
            var duplicate = fixture.channels.discoveryTrunkedReview("dmr", 456_000_000, null, first);
            assertThrows(IllegalStateException.class, () -> fixture.channels.createTrunkedDiscovered(
                duplicate.template(), first, null, duplicate.revision(), false));
            assertEquals(2, new ConfigurationRepository(fixture.database).load().channels().size());
        }
    }

    @Test
    void retainedNativeHistoryRestoresSiteDedupAndAliasesAfterAdministrationRestart() throws Exception
    {
        try(Fixture fixture = new Fixture(root))
        {
            for(String protocol: List.of("dmr","nxdn"))
            {
                boolean dmr = "dmr".equals(protocol);
                long frequency = dmr ? 450_000_000 : 452_000_000;
                var proof = evidence(protocol, dmr ? "TIER_III" : "TYPE_C", dmr ? "dmr:tier3:small:17" : "nxdn-c:regional:41",
                    3, frequency, dmr ? Map.of("channel_mode","TRUNKED") : Map.of("channel_mode","TRUNKED","transmission_mode","M4800"));
                var review = fixture.channels.discoveryTrunkedReview(protocol,frequency,null,proof);
                var saved = fixture.channels.createTrunkedDiscovered(review.template(),proof,dmr ? "Retained DMR" : "Retained NXDN",review.revision(),false);
                var restarted = ChannelAdministrationServiceTestSupport.create(fixture.manager);
                assertNull(restarted.discoveryTrunkedSiteMatch(proof),"Never-received channels have no retained identity");
                var retained = new ChannelAdministrationService.RetainedDiscoveryIdentity(saved.configurationId(),protocol,
                    proof.variant(),proof.identity(),frequency,frequency,1000,4000,4);
                restarted.setRetainedDiscoveryIdentityProvider(ignored -> List.of(retained));
                assertEquals(saved.configurationId(),restarted.discoveryTrunkedSiteMatch(proof).configurationId());
                var nextSite = evidence(protocol,proof.variant(),proof.identity().radioSystemKey(),4,frequency+1_000_000,proof.settings());
                assertEquals(saved.aliasListId(),restarted.discoveryTrunkedReview(protocol,frequency+1_000_000,null,nextSite).suggestedAliasListId());
                var duplicate = restarted.discoveryTrunkedReview(protocol,frequency+2_000_000,null,proof);
                assertThrows(IllegalStateException.class,() -> restarted.createTrunkedDiscovered(duplicate.template(),proof,null,duplicate.revision(),false));
                restarted.setRetainedDiscoveryIdentityProvider(ignored -> List.of(new ChannelAdministrationService.RetainedDiscoveryIdentity(
                    saved.configurationId(),protocol,proof.variant(),proof.identity(),frequency+100_000,frequency+100_000,1000,4000,4)));
                assertNull(restarted.discoveryTrunkedSiteMatch(proof),"An edited source/map cannot reuse unrelated history");
                restarted.setRetainedDiscoveryIdentityProvider(ignored -> { throw new IllegalStateException("offline"); });
                assertNull(restarted.discoveryTrunkedSiteMatch(proof));
                var entry = fixture.channels.get(saved.configurationId());
                var previous = entry.channel();
                var edited = new ChannelDefinition(previous.configurationId(),previous.protocolId(),previous.system(),previous.site(),
                    previous.name(),previous.radioResolveId(),previous.aliasListId(),
                    new ChannelDefinition.Source(List.of(frequency+250_000),null,null,frequency+250_000,null,null),
                    previous.settings(),List.of(),previous.eventLogs(),previous.recorders(),previous.auxiliaryDecoders(),previous.observed());
                fixture.channels.update(saved.configurationId(),edited,entry.revision());
                assertNull(fixture.channels.discoveryTrunkedSiteMatch(proof),"Editing receiving configuration invalidates the in-memory proof");
            }
        }
    }

    @Test
    void nonnativeSiteNumbersStayFrequencyScopedAndInvalidOrConventionalProofCannotCreateChannels() throws Exception
    {
        try(Fixture fixture = new Fixture(root))
        {
            var first = evidence("dmr", "CAPACITY_PLUS", null, 3, 450_000_000,
                Map.of("channel_mode", "TRUNKED"));
            var review = fixture.channels.discoveryTrunkedReview("dmr", 450_000_000, null, first);
            fixture.channels.createTrunkedDiscovered(review.template(), first, "Local DMR", review.revision(), false);
            var another = evidence("dmr", "CAPACITY_PLUS", null, 3, 452_000_000,
                Map.of("channel_mode", "TRUNKED"));
            assertNull(fixture.channels.discoveryTrunkedSiteMatch(another));
            var anotherReview = fixture.channels.discoveryTrunkedReview("dmr", 452_000_000, null, another);
            assertTrue(anotherReview.aliasLists().isEmpty());
            assertNotEquals(review.defaultNewAliasListName(),anotherReview.defaultNewAliasListName(),
                "Frequency-scoped systems need distinct default Alias List names");
            var conventional = evidence("dmr", "CAPACITY_PLUS", null, 3, 454_000_000,
                Map.of("channel_mode", "CONVENTIONAL"));
            assertThrows(IllegalArgumentException.class, () ->
                fixture.channels.discoveryTrunkedReview("dmr", 454_000_000, null, conventional));
            var unverified = new TrunkedDiscoveryEvidence(another.protocolId(), another.variant(), another.identity(),
                another.settings(), another.frequencyMap(), 100, 100, 19, 0, 1, "insufficient evidence");
            assertThrows(IllegalArgumentException.class, () ->
                fixture.channels.discoveryTrunkedReview("dmr", 452_000_000, null, unverified));
            assertThrows(IllegalArgumentException.class, () ->
                fixture.channels.discoveryTrunkedReview("nxdn", 452_000_000, null, another));
            assertThrows(IllegalArgumentException.class, () -> fixture.channels.discoveryTrunkedSiteMatch(null));
        }
    }

    private static TrunkedDiscoveryEvidence evidence(String protocol, String variant, String nativeKey,
                                                    int site, long frequency, Map<String,Object> settings)
    {
        String siteKey = nativeKey != null ? nativeKey + ":site:" + site :
            protocol + ':' + variant + ":frequency:" + frequency + ":site:" + site;
        String[] nativeParts = nativeKey != null ? nativeKey.split(":") : new String[0];
        Integer nativeNumber = nativeKey != null ? Integer.valueOf(nativeParts[nativeParts.length - 1]) : null;
        var identity = new TrunkedDiscoveryEvidence.Identity(null, nativeKey, siteKey,
            "dmr".equals(protocol) ? nativeNumber : null, nativeNumber, site,
            "dmr".equals(protocol) && nativeKey != null ? nativeParts[2] : null,
            "nxdn".equals(protocol) && nativeKey != null ? nativeParts[1] : null, null, 1, 2);
        var evidence = new TrunkedDiscoveryEvidence(protocol, variant, identity, settings,
            List.of(new ChannelDefinition.FrequencyMapEntry(1, frequency + 12_500, 0)), 100, 100, 25, 0, 1, "confirmed");
        assertTrue(evidence.verified(), "Test fixture must contain consistent native identity: " + nativeKey);
        return evidence;
    }

    private static ChannelDefinition edited(ChannelDefinition source, Map<String,Object> settings,
                                            List<ChannelDefinition.FrequencyMapEntry> map)
    {
        return new ChannelDefinition(null, source.protocolId(), source.system(), source.site(), source.name(), null,
            source.aliasListId(), source.source(), settings, map, List.of(), List.of(), List.of(), source.observed());
    }

    private static final class Fixture implements AutoCloseable
    {
        final Path database;
        final ConfigurationManager manager;
        final ChannelAdministrationService channels;

        Fixture(Path root) throws Exception
        {
            database = SdrTrunkDatabasePath.getDatabasePath(root);
            SdrTrunkTestDatabase.create(database);
            var preferences = new UserPreferences()
            {
                final DirectoryPreference directory = new DirectoryPreference(type -> {})
                { @Override public Path getDirectoryApplicationRoot() { return root; } };
                @Override public DirectoryPreference getDirectoryPreference() { return directory; }
            };
            manager = new ConfigurationManager(preferences, null, new AliasModel(), null, null);
            manager.init();
            channels = ChannelAdministrationServiceTestSupport.create(manager);
        }

        @Override public void close()
        {
            try { manager.getChannelProcessingManager().close(); }
            finally
            {
                try { manager.flushConfiguration(); }
                finally { MyEventBus.getGlobalEventBus().unregister(manager.getChannelProcessingManager()); }
            }
        }
    }
}
