/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.configuration;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.channel.ChannelAdministrationService;
import io.github.dsheirer.channel.ChannelAdministrationServiceTestSupport;
import io.github.dsheirer.channel.ChannelDefinition;
import io.github.dsheirer.channel.ChannelDefinitionCodec;
import io.github.dsheirer.channel.ChannelProtocolRegistry;
import io.github.dsheirer.channel.TrunkedDiscoveryIdentity;
import io.github.dsheirer.alias.AliasConfigurationSnapshot;
import java.util.Set;
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
            var nextTemplate = next.template();
            var namedListChoice = new ChannelDefinition(null, nextTemplate.protocolId(), nextTemplate.system(),
                nextTemplate.site(), nextTemplate.name(), null, 0, nextTemplate.source(), nextTemplate.settings(),
                nextTemplate.frequencyMap(), List.of(), List.of(), List.of(), nextTemplate.observed());
            var reused = fixture.channels.createTrunkedDiscovered(namedListChoice, nextSite, "network 17",
                next.revision(), false);
            assertEquals(created.aliasListId(), reused.aliasListId());

            var unrelated = evidence("dmr", "TIER_III", "dmr:tier3:small:18", 3, 454_000_000,
                Map.of("channel_mode", "TRUNKED"));
            assertTrue(fixture.channels.discoveryTrunkedReview("dmr", 454_000_000, null, unrelated).aliasLists().isEmpty());
            var unrelatedReview = fixture.channels.discoveryTrunkedReview("dmr", 454_000_000, null, unrelated);
            assertThrows(IllegalArgumentException.class, () -> fixture.channels.createTrunkedDiscovered(
                unrelatedReview.template(), unrelated, "Network 17", unrelatedReview.revision(), false));
            var duplicate = fixture.channels.discoveryTrunkedReview("dmr", 456_000_000, null, first);
            assertThrows(IllegalStateException.class, () -> fixture.channels.createTrunkedDiscovered(
                duplicate.template(), first, null, duplicate.revision(), false));
            assertEquals(2, new ConfigurationRepository(fixture.database).load().channels().size());
        }
    }

    @Test
    void newListChoiceReusesTheSingleMatchingListDespiteADifferentProposedName() throws Exception
    {
        try(Fixture fixture = new Fixture(root))
        {
            int existingLists = new ConfigurationRepository(fixture.database).load().aliasListDefinitions().size();
            for(String protocol: List.of("p25-phase1", "dmr", "nxdn"))
            {
                long frequency = "p25-phase1".equals(protocol) ? 770_000_000 :
                    "dmr".equals(protocol) ? 450_000_000 : 452_000_000;
                var first = matchingSiteEvidence(protocol, 3, frequency);
                var review = fixture.channels.discoveryTrunkedReview(protocol, frequency, null, first);
                var created = fixture.channels.createTrunkedDiscovered(review.template(), first,
                    protocol + " Dispatch", review.revision(), false);

                var nextSite = matchingSiteEvidence(protocol, 4, frequency + 1_000_000);
                var next = fixture.channels.discoveryTrunkedReview(protocol, frequency + 1_000_000, null, nextSite);
                assertEquals(1, next.aliasLists().size());
                assertEquals(created.aliasListId(), next.aliasLists().getFirst().id());
                var template = next.template();
                var newListChoice = new ChannelDefinition(null, template.protocolId(), template.system(),
                    template.site(), template.name(), null, 0, template.source(), template.settings(),
                    template.frequencyMap(), List.of(), List.of(), List.of(), template.observed());
                var reused = fixture.channels.createTrunkedDiscovered(newListChoice, nextSite,
                    "New directory name", next.revision(), false);
                assertEquals(created.aliasListId(), reused.aliasListId());
            }
            var disk = new ConfigurationRepository(fixture.database).load();
            assertEquals(6, disk.channels().size());
            assertEquals(existingLists + 3, disk.aliasListDefinitions().size());
        }
    }

    @Test
    void savedNativeIdentitySurvivesRestartWithoutHistoryAndReceivingEditsInvalidateIt() throws Exception
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
                assertEquals(saved.configurationId(), restarted.discoveryTrunkedSiteMatch(proof).configurationId());
                var persisted = new ConfigurationRepository(fixture.database).load().channels().stream()
                    .filter(channel -> saved.configurationId().equals(channel.getConfigurationId())).findFirst().orElseThrow();
                assertEquals(TrunkedDiscoveryIdentity.from(proof), persisted.getTrunkedDiscoveryIdentity());
                assertNull(persisted.copyOf().getTrunkedDiscoveryIdentity(), "Clones need their own receiver proof");
                assertEquals(persisted.getTrunkedDiscoveryIdentity(), persisted.copyOfPreservingIdentity().getTrunkedDiscoveryIdentity());
                persisted.regenerateConfigurationId();
                assertNull(persisted.getTrunkedDiscoveryIdentity(), "A legacy import receives a new channel identity and no proof");
                var nextSite = evidence(protocol,proof.variant(),proof.identity().radioSystemKey(),4,frequency+1_000_000,proof.settings());
                assertEquals(saved.aliasListId(),restarted.discoveryTrunkedReview(protocol,frequency+1_000_000,null,nextSite).suggestedAliasListId());
                var duplicate = restarted.discoveryTrunkedReview(protocol,frequency+2_000_000,null,proof);
                assertThrows(IllegalStateException.class,() -> restarted.createTrunkedDiscovered(duplicate.template(),proof,null,duplicate.revision(),false));
                var entry = fixture.channels.get(saved.configurationId());
                var previous = entry.channel();
                var edited = new ChannelDefinition(previous.configurationId(),previous.protocolId(),previous.system(),previous.site(),
                    previous.name(),previous.radioResolveId(),previous.aliasListId(),
                    new ChannelDefinition.Source(List.of(frequency+250_000),null,null,frequency+250_000,null,null),
                    previous.settings(),List.of(),previous.eventLogs(),previous.recorders(),previous.auxiliaryDecoders(),previous.observed());
                fixture.channels.update(saved.configurationId(),edited,entry.revision());
                assertNull(fixture.channels.discoveryTrunkedSiteMatch(proof),"Editing receiving configuration invalidates the saved proof");
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
            var firstSaved = fixture.channels.createTrunkedDiscovered(
                review.template(), first, review.defaultNewAliasListName(), review.revision(), false);
            var another = evidence("dmr", "CAPACITY_PLUS", null, 3, 452_000_000,
                Map.of("channel_mode", "TRUNKED"));
            assertNull(fixture.channels.discoveryTrunkedSiteMatch(another));
            var anotherReview = fixture.channels.discoveryTrunkedReview("dmr", 452_000_000, null, another);
            assertTrue(anotherReview.aliasLists().isEmpty());
            assertNotEquals(review.defaultNewAliasListName(),anotherReview.defaultNewAliasListName(),
                "Frequency-scoped systems need distinct default Alias List names");
            var anotherSaved = fixture.channels.createTrunkedDiscovered(
                anotherReview.template(), another, anotherReview.defaultNewAliasListName(), anotherReview.revision(), false);
            assertNotEquals(firstSaved.aliasListId(), anotherSaved.aliasListId(),
                "Accepting both suggested names must keep unrelated systems' Aliases separate");
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

    @Test
    void batchCommitsValidRowsOnceAndDeduplicatesWithinTheSelection() throws Exception
    {
        try(Fixture fixture = new Fixture(root))
        {
            int beforeAliases = new ConfigurationRepository(fixture.database).load().aliasListDefinitions().size();
            var first = matchingSiteEvidence("dmr", 3, 450_000_000);
            var second = matchingSiteEvidence("dmr", 4, 452_000_000);
            var third = matchingSiteEvidence("nxdn", 5, 454_000_000);
            var firstReview = fixture.channels.discoveryTrunkedReview("dmr", 450_000_000, null, first);
            var secondReview = fixture.channels.discoveryTrunkedReview("dmr", 452_000_000, null, second);
            var thirdReview = fixture.channels.discoveryTrunkedReview("nxdn", 454_000_000, null, third);
            var requests = List.of(
                new ChannelAdministrationService.DiscoveryRequest(firstReview.template(), null, first, "DMR Batch", true),
                new ChannelAdministrationService.DiscoveryRequest(secondReview.template(), null, second, "Different suggestion", true),
                new ChannelAdministrationService.DiscoveryRequest(firstReview.template(), null, first, "Duplicate", false),
                new ChannelAdministrationService.DiscoveryRequest(thirdReview.template(), null, third, "NXDN Batch", false));
            var batch = fixture.channels.createDiscoveredBatch(requests, firstReview.revision(), () -> false);
            assertNull(batch.publicationFailure());
            assertEquals(1, fixture.commits);
            assertEquals(4, batch.results().size());
            assertNull(batch.results().get(0).failure());
            assertNull(batch.results().get(1).failure());
            assertNotNull(batch.results().get(2).failure());
            assertNull(batch.results().get(3).failure());
            assertEquals(batch.results().get(0).created().aliasListId(), batch.results().get(1).created().aliasListId());
            assertTrue(batch.results().get(0).created().aliasListCreated());
            assertFalse(batch.results().get(1).created().aliasListCreated());
            var disk = new ConfigurationRepository(fixture.database).load();
            assertEquals(3, disk.channels().size());
            assertEquals(beforeAliases + 2, disk.aliasListDefinitions().size());
            assertEquals(List.of(1, 2), disk.channels().stream().filter(channel -> channel.isAutoStart())
                .map(channel -> channel.getAutoStartOrder()).sorted().toList());
            assertThrows(ChannelAdministrationService.StaleRevisionException.class, () ->
                fixture.channels.createDiscoveredBatch(requests, firstReview.revision(), () -> false));
            assertEquals(1, fixture.commits);
            var cancelled = fixture.channels.createDiscoveredBatch(requests, fixture.channels.currentRevision(), () -> true);
            assertTrue(cancelled.results().stream().allMatch(row -> row.created() == null && row.failure() != null));
            assertEquals(1, fixture.commits);
        }
    }

    @Test
    void rejectedRowDoesNotLeaveAnAliasAndBrowserProofCannotCreateOrReplaceIdentity() throws Exception
    {
        try(Fixture fixture = new Fixture(root))
        {
            var proof = matchingSiteEvidence("dmr", 3, 450_000_000);
            var review = fixture.channels.discoveryTrunkedReview("dmr", 450_000_000, null, proof);
            var template = review.template();
            var invalid = new ChannelDefinition(null, template.protocolId(), template.system(), template.site(), "", null,
                0, template.source(), template.settings(), template.frequencyMap(), List.of(), List.of(), List.of(), template.observed());
            var batch = fixture.channels.createDiscoveredBatch(List.of(
                new ChannelAdministrationService.DiscoveryRequest(invalid, null, proof, "Must not remain", false),
                new ChannelAdministrationService.DiscoveryRequest(template, null, proof, "Verified system", false)),
                review.revision(), () -> false);
            assertNotNull(batch.results().getFirst().failure());
            var saved = batch.results().get(1).created();
            assertNotNull(saved);
            assertFalse(new ConfigurationRepository(fixture.database).load().aliasListDefinitions().stream()
                .anyMatch(list -> "Must not remain".equals(list.getName())));
            var entry = fixture.channels.get(saved.configurationId());
            var observed = new ChannelDefinition.Observed(List.of(), Map.of(),
                TrunkedDiscoveryIdentity.from(matchingSiteEvidence("dmr", 9, 455_000_000)));
            var current = entry.channel();
            var forged = new ChannelDefinition(current.configurationId(), current.protocolId(), "New friendly name", current.site(),
                current.name(), null, current.aliasListId(), current.source(), current.settings(), current.frequencyMap(),
                List.of(), List.of(), List.of(), observed);
            fixture.channels.update(saved.configurationId(), forged, entry.revision());
            assertEquals(TrunkedDiscoveryIdentity.from(proof), fixture.channels.get(saved.configurationId()).channel().observed().trunkedDiscoveryIdentity());
            var codec = new ChannelDefinitionCodec(new ChannelProtocolRegistry());
            var disk = new ConfigurationRepository(fixture.database).load();
            var owner = disk.aliasListDefinitions().stream().filter(list -> list.getId() == saved.aliasListId()).findFirst().orElseThrow();
            var imported = new ChannelDefinition(null, forged.protocolId(), forged.system(), forged.site(), forged.name(), null,
                forged.aliasListId(), forged.source(), forged.settings(), forged.frequencyMap(), List.of(), List.of(), List.of(), observed);
            assertNull(codec.toChannel(imported, owner, null).getTrunkedDiscoveryIdentity());
            assertNull(codec.cloneChannel(disk.channels().getFirst(), owner).getTrunkedDiscoveryIdentity());
        }
    }

    @Test
    void savedJsonIdentityDoesNotDependOnPropertyOrderAndRejectsConflictingReceivingSettings() throws Exception
    {
        try(Fixture fixture = new Fixture(root))
        {
            var proof = matchingSiteEvidence("dmr", 3, 450_000_000);
            var review = fixture.channels.discoveryTrunkedReview("dmr", 450_000_000, null, proof);
            var saved = fixture.channels.createTrunkedDiscovered(review.template(), proof, "Order independent", review.revision(), false);
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            try(var connection = io.github.dsheirer.database.SdrTrunkDatabase.open(fixture.database))
            {
                com.fasterxml.jackson.databind.node.ObjectNode original;
                try(var query = connection.prepareStatement("SELECT config_json FROM configuration_channel WHERE configuration_id = ?"))
                {
                    query.setString(1, saved.configurationId());
                    try(var result = query.executeQuery())
                    {
                        assertTrue(result.next());
                        original = (com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(result.getString(1));
                    }
                }
                var reordered = mapper.createObjectNode();
                reordered.set("trunkedDiscoveryIdentity", original.get("trunkedDiscoveryIdentity"));
                var names = new java.util.ArrayList<String>();
                original.fieldNames().forEachRemaining(names::add);
                java.util.Collections.reverse(names);
                for(String name: names)
                    if(!"trunkedDiscoveryIdentity".equals(name)) reordered.set(name, original.get(name));
                try(var update = connection.prepareStatement("UPDATE configuration_channel SET config_json = ? WHERE configuration_id = ?"))
                {
                    update.setString(1, mapper.writeValueAsString(reordered));
                    update.setString(2, saved.configurationId());
                    assertEquals(1, update.executeUpdate());
                }
                var reloaded = new ConfigurationRepository(fixture.database).load().channels().getFirst();
                assertEquals(TrunkedDiscoveryIdentity.from(proof), reloaded.getTrunkedDiscoveryIdentity());
                assertEquals(List.of(450_000_000L), new ChannelDefinitionCodec(new ChannelProtocolRegistry())
                    .fromChannel(reloaded).source().frequenciesHz());
                reordered.set("decodeConfiguration", mapper.valueToTree(new io.github.dsheirer.module.decode.nxdn.DecodeConfigNXDN()));
                assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class, () ->
                    mapper.treeToValue(reordered, io.github.dsheirer.controller.channel.Channel.class));
                var local = evidence("dmr", "CAPACITY_PLUS", null, 3, 450_000_000, Map.of("channel_mode", "TRUNKED"));
                reordered.set("decodeConfiguration", original.get("decodeConfiguration"));
                reordered.set("trunkedDiscoveryIdentity", mapper.valueToTree(TrunkedDiscoveryIdentity.from(local)));
                var wrongSource = new io.github.dsheirer.source.config.SourceConfigTuner();
                wrongSource.setFrequency(451_000_000);
                reordered.set("sourceConfiguration", mapper.valueToTree(wrongSource));
                assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class, () ->
                    mapper.treeToValue(reordered, io.github.dsheirer.controller.channel.Channel.class));
            }
        }
    }

    @Test
    void persistedIdentityRejectsForgedVariantKeysAndConflictingSiteFacts()
    {
        var proof = matchingSiteEvidence("dmr", 3, 450_000_000);
        assertThrows(IllegalArgumentException.class, () -> new TrunkedDiscoveryIdentity("dmr", "UNKNOWN", proof.identity()));
        assertThrows(IllegalArgumentException.class, () -> new TrunkedDiscoveryIdentity("nxdn", "TYPE_C", proof.identity()));
        var value = proof.identity();
        var forged = new TrunkedDiscoveryEvidence.Identity(null, value.radioSystemKey(), value.siteKey(), value.network(),
            value.system(), 99, value.model(), value.category(), null, value.ran(), value.colorCode());
        assertThrows(IllegalArgumentException.class, () -> new TrunkedDiscoveryIdentity("dmr", "TIER_III", forged));
        var differentColor = new TrunkedDiscoveryEvidence.Identity(null, value.radioSystemKey(), value.siteKey(), value.network(),
            value.system(), value.site(), value.model(), value.category(), null, value.ran(), 9);
        var conflict = new TrunkedDiscoveryEvidence(proof.protocolId(), proof.variant(), differentColor, proof.settings(),
            proof.frequencyMap(), 100, 100, 25, 0, 1, "confirmed");
        assertFalse(TrunkedDiscoveryIdentity.from(proof).matches(conflict, true));
        assertTrue(TrunkedDiscoveryIdentity.from(proof).matches(conflict, false), "Another site may use another color");
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

    private static TrunkedDiscoveryEvidence matchingSiteEvidence(String protocol, int site, long frequency)
    {
        if("p25-phase1".equals(protocol))
        {
            var nativeSite = new P25SiteIdentity(0xABCDE, 0x123, 2, site);
            String key = RadioSystemKey.p25(nativeSite);
            var identity = new TrunkedDiscoveryEvidence.Identity(nativeSite, key, key + ":2:" + site,
                null, nativeSite.system(), nativeSite.site(), null, null, null, null, null);
            return new TrunkedDiscoveryEvidence(protocol, "P25_PHASE_1", identity,
                Map.of("modulation", "C4FM", "learn_announced_control_channels", true), List.of(),
                100, 100, 25, 0, 1, "confirmed");
        }
        return "dmr".equals(protocol) ?
            evidence(protocol, "TIER_III", "dmr:tier3:small:17", site, frequency,
                Map.of("channel_mode", "TRUNKED")) :
            evidence(protocol, "TYPE_C", "nxdn-c:regional:41", site, frequency,
                Map.of("channel_mode", "TRUNKED", "transmission_mode", "M4800"));
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
        int commits;

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
            manager = new ConfigurationManager(preferences, null, new AliasModel(), null, null)
            {
                @Override public synchronized void commitAndPublishDiscoveredChannels(AliasConfigurationSnapshot aliases,
                    ChannelConfigurationSnapshot channels, Set<String> ids)
                {
                    commits++;
                    super.commitAndPublishDiscoveredChannels(aliases, channels, ids);
                }
                @Override public synchronized ChannelConfigurationSnapshot commitAndPublishChannelConfiguration(
                    ChannelConfigurationSnapshot channels, Set<String> ids, boolean autoStartOnly)
                {
                    commits++;
                    return super.commitAndPublishChannelConfiguration(channels, ids, autoStartOnly);
                }
            };
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
