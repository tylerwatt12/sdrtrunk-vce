/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.service.radioreference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasAdministrationService;
import io.github.dsheirer.alias.AliasAdministrationServiceTestSupport;
import io.github.dsheirer.alias.AliasFactory;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.channel.ChannelAdministrationService;
import io.github.dsheirer.channel.ChannelAdministrationServiceTestSupport;
import io.github.dsheirer.channel.ChannelDefinition;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.configuration.ChannelConfigurationSnapshot;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.Module;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshot;
import io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshotProvider;
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.SampleType;
import io.github.dsheirer.source.Source;
import io.github.dsheirer.source.SourceEvent;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkTestDatabase;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.directory.DirectoryPreference;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.stats.DiscoveryAliasImportService;
import io.github.dsheirer.service.radioreference.RadioReferenceDirectoryService.BoundedPage;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.ConventionalFrequency;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.RemoteTalkgroup;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.RemoteTalkgroupCategory;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.TrunkedSiteChannel;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.TrunkedSiteDetails;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.TrunkedSystemDetails;
import java.nio.file.Path;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

class RadioReferenceImportServiceTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void detectsP25ModulationAndSelectsTheFourSupportedFrequencySets()
    {
        assertEquals("CQPSK", RadioReferenceImportService.detectP25Modulation("Metro SIMUL 2", null, "C4FM"));
        assertEquals("CQPSK", RadioReferenceImportService.detectP25Modulation("Metro", "LSM simulcast", "C4FM"));
        assertEquals("CQPSK", RadioReferenceImportService.detectP25Modulation("Metro", null, "LSM"));
        assertEquals("C4FM", RadioReferenceImportService.detectP25Modulation("Metro", null, "C4FM"));
        assertEquals("C4FM", RadioReferenceImportService.detectP25Modulation("Metro", null, null));

        TrunkedSiteDetails site = site("Central", "C4FM", List.of(
            channel(851_100_000L, "d", true, false),
            channel(851_200_000L, "a", false, true),
            channel(851_300_000L, "", false, false)));
        assertEquals(List.of(851_100_000L), RadioReferenceImportService.selectSiteFrequencies(site,
            RadioReferenceImportService.FrequencySet.CONTROL, List.of()));
        assertEquals(List.of(851_100_000L, 851_200_000L),
            RadioReferenceImportService.selectSiteFrequencies(site,
                RadioReferenceImportService.FrequencySet.CONTROL_AND_ALTERNATES, List.of()));
        assertEquals(List.of(851_300_000L), RadioReferenceImportService.selectSiteFrequencies(site,
            RadioReferenceImportService.FrequencySet.SELECTED, List.of(851_300_000L)));
        assertEquals(List.of(851_100_000L, 851_200_000L, 851_300_000L),
            RadioReferenceImportService.selectSiteFrequencies(site,
                RadioReferenceImportService.FrequencySet.ALL, List.of()));
        assertThrows(IllegalArgumentException.class, () -> RadioReferenceImportService.selectSiteFrequencies(site,
            RadioReferenceImportService.FrequencySet.CONTROL, List.of(851_100_000L)));
        TrunkedSiteDetails alternatesOnly = site("No primary", "", List.of(
            channel(851_200_000L, "a", false, true)));
        assertThrows(IllegalArgumentException.class, () -> RadioReferenceImportService.selectSiteFrequencies(
            alternatesOnly, RadioReferenceImportService.FrequencySet.CONTROL_AND_ALTERNATES, List.of()));
    }

    @Test
    void controlFreeSitesUseAllUniqueFrequenciesWithoutInventingAControlChannel() throws Exception
    {
        TrunkedSiteDetails site = site("Ford Plant Primary", "", List.of(
            new TrunkedSiteChannel(861_112_500L, 1, "01", "", "1", false, false),
            new TrunkedSiteChannel(861_112_500L, 2, "01", "", "1", false, false),
            new TrunkedSiteChannel(861_512_500L, 3, "02", "", "1", false, false),
            new TrunkedSiteChannel(861_887_500L, 5, "03", "", "1", false, false),
            new TrunkedSiteChannel(861_712_500L, 9, "05", "", "1", false, false),
            new TrunkedSiteChannel(861_962_500L, 11, "06", "", "1", false, false)));
        assertThrows(IllegalArgumentException.class, () -> RadioReferenceImportService.selectSiteFrequencies(site,
            RadioReferenceImportService.FrequencySet.CONTROL_AND_ALTERNATES, List.of()));
        assertEquals(5, RadioReferenceImportService.selectSiteFrequencies(site,
            RadioReferenceImportService.FrequencySet.ALL, List.of()).size());

        try(Fixture fixture = new Fixture(mTemporaryFolder))
        {
            fixture.directory.system = new TrunkedSystemDetails(10, "Ford Plant", "", "DMR",
                "Motorola Capacity Plus Single Site (TRBO)", "DMR", "", "");
            fixture.directory.site = site;
            long dmrList = aliasList(fixture, AliasListFamily.DMR);
            assertThrows(IllegalArgumentException.class, () -> fixture.importer.previewSite(
                new RadioReferenceImportService.SiteImportRequest(10, 20, dmrList,
                    RadioReferenceImportService.FrequencySet.CONTROL_AND_ALTERNATES, List.of(),
                    null, null, null)));
            RadioReferenceImportService.ChannelPreview preview = fixture.importer.previewSite(
                new RadioReferenceImportService.SiteImportRequest(10, 20, dmrList,
                    RadioReferenceImportService.FrequencySet.ALL, List.of(), null, null, null));
            assertEquals("dmr", preview.channel().protocolId());
            assertEquals("TRUNKED", preview.channel().settings().get("channel_mode"));
            assertEquals(5, preview.channel().source().frequenciesHz().size());
            assertEquals(List.of(1, 2, 3, 5, 6), preview.channel().frequencyMap().stream()
                .map(ChannelDefinition.FrequencyMapEntry::number).toList());
        }
    }

    @Test
    void supportedTrunkedFlavorsChooseTheirDecoderAndSettings() throws Exception
    {
        try(Fixture fixture = new Fixture(mTemporaryFolder))
        {
            record Case(String type, String flavor, AliasListFamily family, String protocol, String mode) {}
            List<Case> cases = List.of(
                new Case("Project 25", "Phase I", AliasListFamily.P25, "p25-phase1", null),
                new Case("Project 25", "Phase II", AliasListFamily.P25, "p25-phase2", null),
                new Case("DMR", "Motorola Capacity Plus Single Site (TRBO)", AliasListFamily.DMR, "dmr", null),
                new Case("DMR", "Motorola Capacity Plus Multi Site (TRBO)", AliasListFamily.DMR, "dmr", null),
                new Case("DMR", "Motorola Connect Plus (TRBO)", AliasListFamily.DMR, "dmr", null),
                new Case("DMR", "Motorola Capacity Max", AliasListFamily.DMR, "dmr", null),
                new Case("DMR", "Tier 3 Standard", AliasListFamily.DMR, "dmr", null),
                new Case("DMR", "Conventional Networked", AliasListFamily.DMR, "dmr", null),
                new Case("NXDN", "NEXEDGE 4800", AliasListFamily.NXDN, "nxdn", "M4800"),
                new Case("NXDN", "NEXEDGE 9600", AliasListFamily.NXDN, "nxdn", "M9600"),
                new Case("NXDN", "Conventional Networked", AliasListFamily.NXDN, "nxdn", "M9600"),
                new Case("NXDN", "Icom IDAS Type C", AliasListFamily.NXDN, "nxdn", "M4800"),
                new Case("NXDN", "Icom IDAS Type D", AliasListFamily.NXDN, "nxdn", "TYPE_D"),
                new Case("NXDN", "Kenwood Type D", AliasListFamily.NXDN, "nxdn", "TYPE_D"));
            fixture.directory.site = new TrunkedSiteDetails(20, 10, 1, "Test Site", 1, 0, 1, "321", 0,
                "C4FM", true, List.of(channel(851_100_000L, "", false, false)));
            for(Case value: cases)
            {
                fixture.directory.system = new TrunkedSystemDetails(10, "Test System", "", value.type(),
                    value.flavor(), "Digital", "BEE00", "123");
                RadioReferenceImportService.ChannelPreview preview = fixture.importer.previewSite(
                    new RadioReferenceImportService.SiteImportRequest(10, 20, aliasList(fixture, value.family()),
                        RadioReferenceImportService.FrequencySet.ALL, List.of(), null, null, null));
                assertEquals(value.protocol(), preview.channel().protocolId(), value.toString());
                if(value.family() == AliasListFamily.DMR || value.family() == AliasListFamily.NXDN)
                {
                    assertEquals(value.flavor().equals("Conventional Networked") ? "CONVENTIONAL" : "TRUNKED",
                        preview.channel().settings().get("channel_mode"), value.toString());
                    if(value.flavor().equals("Conventional Networked"))
                    {
                        assertTrue(preview.channel().frequencyMap().isEmpty(), value.toString());
                    }
                }
                if(value.mode() != null)
                {
                    assertEquals(value.mode(), preview.channel().settings().get("transmission_mode"),
                        value.toString());
                }
            }
            fixture.directory.system = new TrunkedSystemDetails(10, "Mixed Phase II", "", "Project 25",
                "Phase II", "Digital", "BEE00", "123");
            fixture.directory.site = site("Phase I control", "C4FM",
                List.of(channel(851_100_000L, "d", true, false)));
            RadioReferenceImportService.ChannelPreview phaseOneSite = fixture.importer.previewSite(
                new RadioReferenceImportService.SiteImportRequest(10, 20,
                    aliasList(fixture, AliasListFamily.P25), RadioReferenceImportService.FrequencySet.ALL,
                    List.of(), null, null, null));
            assertEquals("p25-phase1", phaseOneSite.channel().protocolId(),
                "Phase II systems may still have a Phase I control site");
            fixture.directory.system = new TrunkedSystemDetails(10, "Legacy system", "", "EDACS",
                "Standard", "Analog", "", "");
            assertThrows(IllegalArgumentException.class, () -> fixture.importer.previewSite(
                new RadioReferenceImportService.SiteImportRequest(10, 20,
                    aliasList(fixture, AliasListFamily.P25), RadioReferenceImportService.FrequencySet.ALL,
                    List.of(), null, null, null)), "Unsupported system types must not silently choose a decoder");
        }
    }

    private static long aliasList(Fixture fixture, AliasListFamily family)
    {
        return fixture.aliases.catalog().aliasLists().stream().filter(list -> list.getFamily() == family)
            .findFirst().orElseThrow().getId();
    }

    @Test
    void previewFindsASiteBeyondTheFirstPageWithOneCompleteCatalogRead() throws Exception
    {
        try(Fixture fixture = new Fixture(mTemporaryFolder))
        {
            List<TrunkedSiteDetails> sites = new ArrayList<>();
            for(int index = 0; index < 501; index++)
                sites.add(new TrunkedSiteDetails(1000 + index, 10, index % 255 + 1, "Site " + index, 1, 0, index / 255 + 1,
                    "321", 0, "C4FM", false, fixture.directory.site.channels()));
            fixture.directory.sites = List.copyOf(sites);
            var preview = fixture.importer.previewSite(new RadioReferenceImportService.SiteImportRequest(
                10, 1500, aliasList(fixture, AliasListFamily.P25),
                RadioReferenceImportService.FrequencySet.CONTROL, List.of(), null, null, null));
            assertEquals("Site 500", preview.channel().site());
            assertEquals(1, fixture.directory.siteCatalogReads);
            assertThrows(IllegalArgumentException.class, () -> fixture.importer.previewSite(
                new RadioReferenceImportService.SiteImportRequest(10, 99999, aliasList(fixture, AliasListFamily.P25),
                    RadioReferenceImportService.FrequencySet.CONTROL, List.of(), null, null, null)));
            assertEquals(2, fixture.directory.siteCatalogReads, "a new preview obtains a fresh catalog");
        }
    }

    @Test
    void talkgroupComparisonIgnoresOuterWhitespaceAndReportsInvisibleInternalChanges() throws Exception
    {
        try(Fixture fixture = new Fixture(mTemporaryFolder))
        {
            long listId = aliasList(fixture, AliasListFamily.P25);
            Alias current = new Alias(" Dispatch ");
            current.setAliasListId(listId);
            current.setMatchIdentifier(new Talkgroup(Protocol.APCO25, 101));
            current.setDescription(" Primary dispatch "); current.setGroup(" Public Safety ");
            long id = fixture.aliases.createAlias(current).aliasIds().getFirst();
            var initial = fixture.importer.talkgroupCatalog(10, listId, null);
            var row = initial.items().getFirst();
            assertEquals(RadioReferenceImportService.TalkgroupStatus.IDENTICAL, row.status());
            assertEquals(id, row.existingAliasId());
            assertTrue(row.changes().isEmpty());
            assertEquals(" Dispatch ", row.currentAlias().alphaTag());
            Alias changed = AliasFactory.copyOf(fixture.aliases.getAlias(id).alias());
            changed.setDescription("Primary  dispatch");
            fixture.aliases.replaceAlias(id, changed, fixture.aliases.getAlias(id).editRevision());
            var compared = fixture.importer.talkgroupCatalog(10, listId, initial.catalogId()).items().getFirst();
            assertEquals(RadioReferenceImportService.TalkgroupStatus.DIFFERENT, compared.status());
            assertEquals(List.of(new io.github.dsheirer.alias.AliasImportService.Change("description",
                "Primary  dispatch", "Primary dispatch")), compared.changes());
            assertEquals("Primary  dispatch", compared.currentAlias().description());
            var preview = fixture.importer.previewTalkgroups(new RadioReferenceImportService.TalkgroupImportRequest(
                10, listId, false, List.of(1), initial.catalogId()));
            assertEquals(compared.changes(), preview.rows().getFirst().changes());
            fixture.importer.applyTalkgroups(preview.previewId());
            assertEquals(RadioReferenceImportService.TalkgroupStatus.IDENTICAL,
                fixture.importer.talkgroupCatalog(10, listId, initial.catalogId()).items().getFirst().status());
        }
    }

    @Test
    void createsThenRefreshesOnlySiteSourceFieldsAndKeepsTalkgroupDefaults() throws Exception
    {
        try(Fixture fixture = new Fixture(mTemporaryFolder))
        {
            long p25List = fixture.aliases.catalog().aliasLists().stream()
                .filter(list -> list.getFamily() == AliasListFamily.P25).findFirst().orElseThrow().getId();
            RadioReferenceImportService.SiteImportRequest siteRequest =
                new RadioReferenceImportService.SiteImportRequest(10, 20, p25List,
                    RadioReferenceImportService.FrequencySet.CONTROL_AND_ALTERNATES, List.of(), null, null, null);
            RadioReferenceImportService.ChannelPreview created = fixture.importer.previewSite(siteRequest);
            assertEquals(RadioReferenceImportService.ChannelAction.CREATE, created.action());
            assertEquals("CQPSK", created.detectedModulation());
            assertEquals(List.of(851_100_000L, 851_200_000L), created.channel().source().frequenciesHz());
            String channelId = fixture.importer.applyChannel(created.previewId()).configurationId();
            assertThrows(RadioReferenceImportService.PreviewNotFoundException.class,
                () -> fixture.importer.applyChannel(created.previewId()));
            RadioReferenceImportService.ChannelPreview differentlyNamed = fixture.importer.previewSite(
                new RadioReferenceImportService.SiteImportRequest(10, 20, p25List,
                    RadioReferenceImportService.FrequencySet.CONTROL_AND_ALTERNATES, List.of(), null, null,
                    "Secondary Control"));
            assertEquals(RadioReferenceImportService.ChannelAction.CREATE, differentlyNamed.action(),
                "The same site may be imported again under a different channel name");

            ChannelAdministrationService.Entry saved = fixture.channels.get(channelId);
            Map<String,Object> adjustedSettings = new java.util.LinkedHashMap<>(saved.channel().settings());
            adjustedSettings.put("use_bandplan_override", true);
            ChannelDefinition adjusted = copy(saved.channel(), saved.channel().source(), adjustedSettings,
                List.of("BASEBAND"));
            fixture.channels.update(channelId, adjusted, saved.revision());

            fixture.directory.site = site("Central Simulcast", "C4FM", List.of(
                channel(851_100_000L, "d", true, false),
                channel(851_300_000L, "a", false, true),
                channel(851_400_000L, "", false, false)));
            RadioReferenceImportService.ChannelPreview refreshed = fixture.importer.previewSite(siteRequest);
            assertEquals(RadioReferenceImportService.ChannelAction.UPDATE, refreshed.action());
            assertEquals(channelId, refreshed.existingConfigurationId());
            assertEquals(List.of(851_100_000L, 851_200_000L), refreshed.previousFrequenciesHz());
            assertEquals(List.of(851_100_000L, 851_300_000L), refreshed.channel().source().frequenciesHz());
            assertEquals(true, refreshed.channel().settings().get("use_bandplan_override"));
            assertEquals(List.of("BASEBAND"), refreshed.channel().recorders());
            fixture.importer.applyChannel(refreshed.previewId());

            ChannelDefinition current = fixture.channels.get(channelId).channel();
            assertEquals(List.of(851_100_000L, 851_300_000L), current.source().frequenciesHz());
            assertEquals(true, current.settings().get("use_bandplan_override"));
            assertEquals(List.of("BASEBAND"), current.recorders());

            fixture.directory.system = new TrunkedSystemDetails(10, "County P25", "", "Motorola", "Type II",
                "APCO-25 Common Air Interface Exclusive", "", "");
            assertThrows(IllegalArgumentException.class, () -> fixture.importer.previewSite(siteRequest));

            fixture.directory.system = p25System();
            RadioReferenceImportService.TalkgroupPage page = fixture.importer.talkgroups(10, p25List, null, null,
                0, 100);
            RadioReferenceImportService.TalkgroupPage raw = fixture.importer.talkgroupCatalog(10, null, null);
            assertEquals(2, raw.items().size());
            assertEquals(RadioReferenceImportService.TalkgroupStatus.UNCOMPARED, raw.items().getFirst().status());
            RadioReferenceImportService.TalkgroupPage catalog = fixture.importer.talkgroupCatalog(10, p25List,
                raw.catalogId());
            assertEquals(raw.catalogId(), catalog.catalogId());
            int categoryReadsAtCatalog = fixture.directory.categoryReads;
            assertEquals(2, catalog.totalItems());
            assertEquals(2, catalog.items().size());
            assertEquals(catalog.catalogId(), fixture.importer.talkgroupCatalog(10, p25List,
                catalog.catalogId()).catalogId());
            assertEquals(1, fixture.directory.catalogReads,
                "switching Alias Lists must reannotate without another upstream read");
            assertThrows(IllegalArgumentException.class, () -> fixture.importer.previewTalkgroups(
                new RadioReferenceImportService.TalkgroupImportRequest(10, p25List, false, List.of(1),
                    "unknown-catalog")));
            assertEquals(List.of(RadioReferenceImportService.TalkgroupStatus.NOT_PRESENT,
                    RadioReferenceImportService.TalkgroupStatus.NOT_PRESENT),
                page.items().stream().map(RadioReferenceImportService.TalkgroupRow::status).toList());
            RadioReferenceImportService.TalkgroupImportPreview talkgroups = fixture.importer.previewTalkgroups(
                new RadioReferenceImportService.TalkgroupImportRequest(10, p25List, true, List.of(),
                    catalog.catalogId()));
            assertEquals(1, fixture.directory.catalogReads,
                "preview must reuse the loaded upstream talkgroups");
            assertEquals(categoryReadsAtCatalog, fixture.directory.categoryReads,
                "preview must reuse loaded category enrichment");
            assertTrue(talkgroups.all());
            assertEquals(2L, talkgroups.counts().get("added"));
            fixture.importer.applyTalkgroups(talkgroups.previewId());
            assertEquals(1, fixture.directory.catalogReads,
                "apply must not fetch RadioReference again");
            assertEquals(categoryReadsAtCatalog, fixture.directory.categoryReads,
                "apply must not fetch categories again");
            List<AliasAdministrationService.AliasEntry> imported = fixture.aliases.transferSnapshot(p25List).aliases();
            Alias encrypted = imported.stream().filter(entry -> entry.alias().getName().equals("Encrypted"))
                .findFirst().orElseThrow().alias();
            assertFalse(encrypted.isRecordable());
            assertTrue(encrypted.getBroadcastChannels().isEmpty());
            assertTrue(fixture.aliases.getAlias(encrypted.getId()).scanListIds().isEmpty());

            Alias dispatch = imported.stream().filter(entry -> entry.alias().getName().equals("Dispatch"))
                .findFirst().orElseThrow().alias();
            RadioReferenceImportService.TalkgroupPage importedPage = fixture.importer.talkgroups(10, p25List,
                null, null, 0, 100);
            assertEquals(RadioReferenceImportService.TalkgroupStatus.IDENTICAL,
                importedPage.items().getFirst().status());
            assertEquals(dispatch.getId(), importedPage.items().getFirst().existingAliasId());
            Alias locallyStyled = AliasFactory.copyOf(dispatch);
            locallyStyled.setColor(0x123456);
            fixture.aliases.replaceAlias(dispatch.getId(), locallyStyled, fixture.aliases.getAlias(dispatch.getId()).editRevision());
            fixture.directory.talkgroups.set(0,
                new RemoteTalkgroup(1, 101, "Dispatch Updated", "Primary dispatch", "D", 0, 50, List.of()));
            fixture.importer.clearSessionData();
            assertThrows(IllegalArgumentException.class, () -> fixture.importer.previewTalkgroups(
                new RadioReferenceImportService.TalkgroupImportRequest(10, p25List, false, List.of(1),
                    catalog.catalogId())));
            RadioReferenceImportService.TalkgroupPage fresh = fixture.importer.talkgroupCatalog(10, p25List, null);
            RadioReferenceImportService.TalkgroupImportPreview update = fixture.importer.previewTalkgroups(
                new RadioReferenceImportService.TalkgroupImportRequest(10, p25List, false, List.of(1),
                    fresh.catalogId()));
            assertEquals(1L, update.counts().get("updated"));
            fixture.importer.applyTalkgroups(update.previewId());
            Alias updated = fixture.aliases.getAlias(dispatch.getId()).alias();
            assertEquals("Dispatch Updated", updated.getName());
            assertEquals(0x123456, updated.getColor());
        }
    }

    @Test
    void availabilityChecksOnlyTalkgroupsAndTheImportReusesTheirDownload() throws Exception
    {
        try(Fixture fixture = new Fixture(mTemporaryFolder))
        {
            assertTrue(fixture.importer.talkgroupAvailability(10).hasTalkgroups());
            assertTrue(fixture.importer.talkgroupAvailability(10).hasTalkgroups());
            assertEquals(1, fixture.directory.catalogReads);
            assertEquals(0, fixture.directory.systemReads,
                "availability must not fetch system details");
            assertEquals(0, fixture.directory.categoryReads,
                "availability must not fetch category enrichment");

            RadioReferenceImportService.TalkgroupPage catalog = fixture.importer.talkgroupCatalog(10, null, null);
            assertEquals(1, fixture.directory.catalogReads,
                "the later import must reuse the availability download");
            assertEquals(1, fixture.directory.systemReads);
            assertEquals(1, fixture.directory.categoryReads);
            assertEquals("Public Safety", catalog.items().getFirst().category(),
                "the full catalog must still supply its normal category enrichment");
        }
    }

    @Test
    void emptyAvailabilityIsCachedAndExplicitCatalogRefreshReloadsTheTalkgroups() throws Exception
    {
        try(Fixture fixture = new Fixture(mTemporaryFolder))
        {
            fixture.directory.talkgroups.clear();
            assertFalse(fixture.importer.talkgroupAvailability(10).hasTalkgroups());
            assertFalse(fixture.importer.talkgroupAvailability(10).hasTalkgroups());
            assertEquals(1, fixture.directory.catalogReads);
            fixture.directory.talkgroups.add(new RemoteTalkgroup(1, 101, "Dispatch", "Dispatch", "D", 0,
                50, List.of()));
            assertFalse(fixture.importer.talkgroupAvailability(10).hasTalkgroups(),
                "normal checks must retain the account-scoped source snapshot");

            var refreshed = fixture.importer.talkgroupCatalog(10, null, null, true);
            assertEquals(1, refreshed.totalItems());
            assertTrue(fixture.importer.talkgroupAvailability(10).hasTalkgroups());
            assertEquals(2, fixture.directory.catalogReads,
                "an explicit import refresh must replace the retained raw snapshot");
        }
    }

    @Test
    void availabilityKeepsTheSuccessfulImportSourceAfterRawCacheEviction() throws Exception
    {
        try(Fixture fixture = new Fixture(mTemporaryFolder))
        {
            var original = fixture.importer.talkgroupCatalog(10, null, null);
            for(int systemId = 11; systemId <= 26; systemId++)
                assertTrue(fixture.importer.talkgroupAvailability(systemId).hasTalkgroups());
            assertEquals(17, fixture.directory.catalogReads);
            fixture.directory.talkgroups.clear();

            assertTrue(fixture.importer.talkgroupAvailability(10).hasTalkgroups());
            assertEquals(original.catalogId(), fixture.importer.talkgroupCatalog(10, null, null).catalogId());
            assertEquals(17, fixture.directory.catalogReads,
                "availability must use the same successful source as the upcoming import after raw eviction");
            assertEquals(0, fixture.importer.talkgroupCatalog(10, null, null, true).totalItems());
            assertFalse(fixture.importer.talkgroupAvailability(10).hasTalkgroups());
            assertEquals(18, fixture.directory.catalogReads);
        }
    }

    @Test
    void failedFullRefreshDoesNotChangeAvailabilityFromItsLastSuccessfulImportSource() throws Exception
    {
        try(Fixture fixture = new Fixture(mTemporaryFolder))
        {
            var original = fixture.importer.talkgroupCatalog(10, null, null);
            fixture.directory.talkgroups.clear();
            fixture.directory.categoryFailure = RadioReferenceDirectoryException.Code.RESULT_SET_TOO_LARGE;
            assertEquals(RadioReferenceDirectoryException.Code.RESULT_SET_TOO_LARGE,
                assertThrows(RadioReferenceDirectoryException.class,
                    () -> fixture.importer.talkgroupCatalog(10, null, null, true)).code());

            assertTrue(fixture.importer.talkgroupAvailability(10).hasTalkgroups(),
                "a failed full refresh must not expose a different raw snapshot than the retained import catalog");
            assertEquals(original.catalogId(), fixture.importer.talkgroupCatalog(10, null, null).catalogId());
            assertEquals(2, fixture.directory.catalogReads);
            fixture.directory.categoryFailure = null;
            assertEquals(0, fixture.importer.talkgroupCatalog(10, null, null, true).totalItems());
            assertFalse(fixture.importer.talkgroupAvailability(10).hasTalkgroups());
            assertEquals(3, fixture.directory.catalogReads);
        }
    }

    @Test
    void failedAvailabilityCanBeRetriedWithoutRetainingAFailedResult() throws Exception
    {
        try(Fixture fixture = new Fixture(mTemporaryFolder))
        {
            fixture.directory.catalogFailure = RadioReferenceDirectoryException.Code.UNAVAILABLE;
            assertEquals(RadioReferenceDirectoryException.Code.UNAVAILABLE,
                assertThrows(RadioReferenceDirectoryException.class,
                    () -> fixture.importer.talkgroupAvailability(10)).code());
            fixture.directory.catalogFailure = null;
            assertTrue(fixture.importer.talkgroupAvailability(10).hasTalkgroups());
            assertEquals(2, fixture.directory.catalogReads);
            assertEquals(0, fixture.directory.systemReads);
            assertEquals(0, fixture.directory.categoryReads);
        }
    }

    @Test
    void availabilityRejectsAnExpiredSessionAndDiscardsItsRawSnapshot() throws Exception
    {
        try(Fixture fixture = new Fixture(mTemporaryFolder))
        {
            assertTrue(fixture.importer.talkgroupAvailability(10).hasTalkgroups());
            fixture.directory.catalogSessionFailure = RadioReferenceDirectoryException.Code.NOT_AUTHENTICATED;
            assertEquals(RadioReferenceDirectoryException.Code.NOT_AUTHENTICATED,
                assertThrows(RadioReferenceDirectoryException.class,
                    () -> fixture.importer.talkgroupAvailability(10)).code());
            assertEquals(1, fixture.directory.catalogReads,
                "an expired account must fail before another remote read");

            fixture.directory.catalogSessionFailure = null;
            fixture.directory.talkgroups.clear();
            assertFalse(fixture.importer.talkgroupAvailability(10).hasTalkgroups());
            assertEquals(2, fixture.directory.catalogReads,
                "the next account must not reuse the previous account's raw source");
        }
    }

    @Test
    void availabilityAndConcurrentImportShareOneRawTalkgroupLoad() throws Exception
    {
        try(Fixture fixture = new Fixture(mTemporaryFolder))
        {
            CatalogGate gate = new CatalogGate();
            fixture.directory.catalogGate = gate;
            PendingCall<RadioReferenceImportService.TalkgroupPage> owner = catalogCall(() ->
                fixture.importer.talkgroupCatalog(10, null, null));
            PendingCall<RadioReferenceImportService.TalkgroupAvailability> joining = null;
            try
            {
                assertTrue(gate.entered.await(1, TimeUnit.SECONDS));
                joining = catalogCall(() -> fixture.importer.talkgroupAvailability(10));
                awaitSharedCatalogWait(joining.thread());
                assertEquals(1, fixture.directory.concurrentCatalogReads.get());
                gate.release.countDown();
                assertTrue(joining.result().get(2, TimeUnit.SECONDS).hasTalkgroups());
                assertEquals(2, owner.result().get(2, TimeUnit.SECONDS).totalItems());
                assertEquals(1, fixture.directory.concurrentCatalogReads.get(),
                    "availability and import must share an in-flight raw download");
            }
            finally
            {
                gate.release.countDown();
                stopCatalogCall(owner);
                stopCatalogCall(joining);
                stopCatalogWorker(gate);
            }
        }
    }

    @Test
    void reviewedTalkgroupCatalogsStayStableAndSiteSourceFreshnessBelongsToTheDirectory() throws Exception
    {
        try(Fixture fixture = new Fixture(mTemporaryFolder))
        {
            long listId = aliasList(fixture, AliasListFamily.P25);
            RadioReferenceImportService.TalkgroupPage initial = fixture.importer.talkgroupCatalog(10, listId, null);
            assertEquals(initial.catalogId(), fixture.importer.talkgroupCatalog(10, null, null).catalogId(),
                "reopening a system must reuse its account-scoped catalog even without a browser catalog ID");
            assertEquals(1, fixture.directory.catalogReads);
            RadioReferenceImportService.TalkgroupImportPreview preview = fixture.importer.previewTalkgroups(
                new RadioReferenceImportService.TalkgroupImportRequest(10, listId, true, List.of(), initial.catalogId()));
            fixture.importer.applyTalkgroups(preview.previewId());
            RadioReferenceImportService.TalkgroupPage compared = fixture.importer.talkgroupCatalog(10, listId,
                initial.catalogId());
            assertEquals("Dispatch", compared.items().getFirst().currentAlias().alphaTag());
            assertEquals("Primary dispatch", compared.items().getFirst().currentAlias().description());
            assertEquals("Public Safety", compared.items().getFirst().currentAlias().category());
            fixture.directory.talkgroups.set(0,
                new RemoteTalkgroup(1, 101, "New dispatch name", "Primary dispatch", "D", 0, 50, List.of()));
            assertEquals("Dispatch", fixture.importer.talkgroupCatalog(10, listId, null)
                .items().getFirst().talkgroup().alphaTag(), "normal comparison keeps the cached source");
            RadioReferenceImportService.TalkgroupPage fresh = fixture.importer.talkgroupCatalog(10, listId,
                initial.catalogId(), true);
            assertEquals(2, fixture.directory.catalogReads);
            assertFalse(initial.catalogId().equals(fresh.catalogId()));
            assertEquals(RadioReferenceImportService.TalkgroupStatus.DIFFERENT, fresh.items().getFirst().status());
            assertEquals("New dispatch name", fresh.items().getFirst().talkgroup().alphaTag());
            assertEquals("Dispatch", fresh.items().getFirst().currentAlias().alphaTag());
            assertEquals("Primary dispatch", fresh.items().getFirst().currentAlias().description(),
                "unchanged fields must be supplied alongside changed fields");
            fixture.importer.siteCatalog(10, false);
            fixture.directory.site = site("Renamed site", "C4FM", List.of(channel(852_100_000L, "d", true, false)));
            assertEquals("Renamed site", fixture.importer.siteCatalog(10, false).getFirst().name());
            assertEquals(2, fixture.directory.siteCatalogReads,
                "the importer must leave ordinary source freshness to the shared gateway cache");
            assertEquals("Renamed site", fixture.importer.siteCatalog(10, true).getFirst().name());
            assertEquals(3, fixture.directory.siteCatalogReads);
            fixture.importer.clearSessionData();
            assertThrows(IllegalArgumentException.class, () -> fixture.importer.talkgroupCatalog(10, listId,
                fresh.catalogId()));
            fixture.importer.siteCatalog(10, false);
            fixture.importer.talkgroupCatalog(10, listId, null);
            assertEquals(4, fixture.directory.siteCatalogReads);
            assertEquals(3, fixture.directory.catalogReads);
        }
    }

    @Test
    void concurrentRequestsForTheSameTalkgroupCatalogShareOneRemoteLoad() throws Exception
    {
        try(Fixture fixture = new Fixture(mTemporaryFolder))
        {
            CatalogGate gate = new CatalogGate();
            fixture.directory.catalogGate = gate;
            PendingCall<RadioReferenceImportService.TalkgroupPage> owner = catalogCall(() ->
                fixture.importer.talkgroupCatalog(10, null, null));
            PendingCall<RadioReferenceImportService.TalkgroupPage> joining = null;
            try
            {
                assertTrue(gate.entered.await(1, TimeUnit.SECONDS));
                joining = catalogCall(() -> fixture.importer.talkgroupCatalog(10, null, null));
                awaitSharedCatalogWait(joining.thread());
                assertEquals(1, fixture.directory.concurrentCatalogReads.get(),
                    "the joining request must not start a second RadioReference catalog fetch");
                gate.release.countDown();
                RadioReferenceImportService.TalkgroupPage first = owner.result().get(2, TimeUnit.SECONDS);
                RadioReferenceImportService.TalkgroupPage second = joining.result().get(2, TimeUnit.SECONDS);
                assertEquals(first.catalogId(), second.catalogId());
                assertEquals(first.items(), second.items());
                assertEquals(1, fixture.directory.concurrentCatalogReads.get());
                assertEquals(first.catalogId(), fixture.importer.talkgroupCatalog(10, null, null).catalogId());
            }
            finally
            {
                gate.release.countDown();
                stopCatalogCall(owner);
                stopCatalogCall(joining);
                stopCatalogWorker(gate);
            }
        }
    }

    @Test
    void failedExplicitRefreshPreservesTheLastSuccessfulTalkgroupCatalog() throws Exception
    {
        try(Fixture fixture = new Fixture(mTemporaryFolder))
        {
            RadioReferenceImportService.TalkgroupPage original = fixture.importer.talkgroupCatalog(10, null, null);
            fixture.directory.catalogFailure = RadioReferenceDirectoryException.Code.UNAVAILABLE;
            RadioReferenceDirectoryException failure = assertThrows(RadioReferenceDirectoryException.class, () ->
                fixture.importer.talkgroupCatalog(10, null, original.catalogId(), true));
            assertEquals(RadioReferenceDirectoryException.Code.UNAVAILABLE, failure.code());
            assertEquals(original.catalogId(), fixture.importer.talkgroupCatalog(10, null, null).catalogId());
            assertEquals(original.items(), fixture.importer.talkgroupCatalog(10, null, original.catalogId()).items());
            assertEquals(2, fixture.directory.concurrentCatalogReads.get(),
                "reading the retained value after failure must not perform another remote request");

            fixture.directory.catalogFailure = null;
            fixture.directory.talkgroups.set(0,
                new RemoteTalkgroup(1, 101, "Refreshed dispatch", "Primary dispatch", "D", 0, 50, List.of()));
            RadioReferenceImportService.TalkgroupPage refreshed = fixture.importer.talkgroupCatalog(10, null,
                original.catalogId(), true);
            assertFalse(original.catalogId().equals(refreshed.catalogId()));
            assertEquals("Refreshed dispatch", refreshed.items().getFirst().talkgroup().alphaTag());
            assertEquals(3, fixture.directory.concurrentCatalogReads.get(),
                "a failed load must release its pending slot so a new refresh can succeed");
        }
    }

    @Test
    void accountChangeCancelsJoinedLoadsAndPreventsTheOldOwnerReplacingNewAccountData() throws Exception
    {
        try(Fixture fixture = new Fixture(mTemporaryFolder))
        {
            CatalogGate gate = new CatalogGate();
            fixture.directory.catalogGate = gate;
            PendingCall<RadioReferenceImportService.TalkgroupPage> owner = catalogCall(() ->
                fixture.importer.talkgroupCatalog(10, null, null));
            PendingCall<RadioReferenceImportService.TalkgroupPage> joining = null;
            try
            {
                assertTrue(gate.entered.await(1, TimeUnit.SECONDS));
                joining = catalogCall(() -> fixture.importer.talkgroupCatalog(10, null, null));
                awaitSharedCatalogWait(joining.thread());
                fixture.importer.clearSessionData();
                PendingCall<RadioReferenceImportService.TalkgroupPage> joinedCall = joining;
                ExecutionException joinedFailure = assertThrows(ExecutionException.class,
                    () -> joinedCall.result().get(1, TimeUnit.SECONDS));
                assertTrue(joinedFailure.getCause() instanceof IllegalStateException,
                    "account changes must release a joined caller before the remote owner finishes");

                fixture.directory.catalogGate = null;
                fixture.directory.talkgroups.set(0,
                    new RemoteTalkgroup(1, 101, "New account dispatch", "Primary dispatch", "D", 0, 50, List.of()));
                RadioReferenceImportService.TalkgroupPage current = fixture.importer.talkgroupCatalog(10, null, null);
                assertEquals("New account dispatch", current.items().getFirst().talkgroup().alphaTag());
                gate.release.countDown();
                ExecutionException ownerFailure = assertThrows(ExecutionException.class,
                    () -> owner.result().get(2, TimeUnit.SECONDS));
                assertTrue(ownerFailure.getCause() instanceof IllegalStateException);
                stopCatalogWorker(gate);
                RadioReferenceImportService.TalkgroupPage retained = fixture.importer.talkgroupCatalog(10, null, null);
                assertEquals(current.catalogId(), retained.catalogId());
                assertEquals("New account dispatch", retained.items().getFirst().talkgroup().alphaTag());
                assertEquals(2, fixture.directory.concurrentCatalogReads.get());
            }
            finally
            {
                gate.release.countDown();
                stopCatalogCall(owner);
                stopCatalogCall(joining);
                stopCatalogWorker(gate);
            }
        }
    }

    @Test
    void expiredDirectorySessionRejectsCachedCatalogsAndValidSessionLoadsFreshData() throws Exception
    {
        try(Fixture fixture = new Fixture(mTemporaryFolder))
        {
            RadioReferenceImportService.TalkgroupPage previous = fixture.importer.talkgroupCatalog(10, null, null);
            fixture.importer.siteCatalog(10, false);
            fixture.directory.catalogSessionFailure = RadioReferenceDirectoryException.Code.NOT_AUTHENTICATED;
            assertEquals(RadioReferenceDirectoryException.Code.NOT_AUTHENTICATED,
                assertThrows(RadioReferenceDirectoryException.class,
                    () -> fixture.importer.talkgroupCatalog(10, null, null)).code());
            assertEquals(RadioReferenceDirectoryException.Code.NOT_AUTHENTICATED,
                assertThrows(RadioReferenceDirectoryException.class,
                    () -> fixture.importer.talkgroupCatalog(10, null, previous.catalogId())).code());
            assertEquals(RadioReferenceDirectoryException.Code.NOT_AUTHENTICATED,
                assertThrows(RadioReferenceDirectoryException.class,
                    () -> fixture.importer.systemDetails(10, false)).code());
            assertEquals(RadioReferenceDirectoryException.Code.NOT_AUTHENTICATED,
                assertThrows(RadioReferenceDirectoryException.class,
                    () -> fixture.importer.siteCatalog(10, false)).code());
            assertEquals(1, fixture.directory.concurrentCatalogReads.get(),
                "an invalid session must fail before any remote catalog read");
            assertEquals(1, fixture.directory.siteCatalogReads);

            fixture.directory.catalogSessionFailure = null;
            fixture.directory.system = new TrunkedSystemDetails(10, "New account system", "", "Project 25",
                "Phase I", "Digital", "BEE00", "123");
            fixture.directory.site = site("New account site", "C4FM",
                List.of(channel(852_100_000L, "d", true, false)));
            fixture.directory.talkgroups.set(0,
                new RemoteTalkgroup(1, 101, "New account dispatch", "Primary dispatch", "D", 0, 50, List.of()));
            assertThrows(IllegalArgumentException.class,
                () -> fixture.importer.talkgroupCatalog(10, null, previous.catalogId()),
                "tokens from the expired session must not become valid again");
            RadioReferenceImportService.TalkgroupPage current = fixture.importer.talkgroupCatalog(10, null, null);
            assertFalse(previous.catalogId().equals(current.catalogId()));
            assertEquals("New account dispatch", current.items().getFirst().talkgroup().alphaTag());
            assertEquals("New account system", fixture.importer.systemDetails(10, false).name());
            assertEquals("New account site", fixture.importer.siteCatalog(10, false).getFirst().name());
            assertEquals(2, fixture.directory.concurrentCatalogReads.get());
            assertEquals(2, fixture.directory.siteCatalogReads);
        }
    }

    @Test
    void interruptedOwnerCancelsItsRemoteWorkerWithoutPublishingTheOldCatalog() throws Exception
    {
        try(Fixture fixture = new Fixture(mTemporaryFolder))
        {
            CatalogGate gate = new CatalogGate();
            fixture.directory.catalogGate = gate;
            PendingCall<RadioReferenceImportService.TalkgroupPage> owner = catalogCall(() ->
                fixture.importer.talkgroupCatalog(10, null, null));
            try
            {
                assertTrue(gate.entered.await(1, TimeUnit.SECONDS));
                awaitSharedCatalogWait(owner.thread());
                owner.thread().interrupt();
                ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> owner.result().get(2, TimeUnit.SECONDS));
                assertTrue(failure.getCause() instanceof RadioReferenceDirectoryException);
                assertEquals(RadioReferenceDirectoryException.Code.INTERRUPTED,
                    ((RadioReferenceDirectoryException)failure.getCause()).code());
                stopCatalogWorker(gate);
                assertEquals(1, gate.release.getCount(),
                    "request interruption must stop the remote worker while its fixture is still blocked");

                fixture.directory.catalogGate = null;
                fixture.directory.talkgroups.set(0,
                    new RemoteTalkgroup(1, 101, "Retry dispatch", "Primary dispatch", "D", 0, 50, List.of()));
                RadioReferenceImportService.TalkgroupPage retry = fixture.importer.talkgroupCatalog(10, null, null);
                assertEquals("Retry dispatch", retry.items().getFirst().talkgroup().alphaTag(),
                    "a canceled owner must neither retain its old value nor block a fresh load");
                assertEquals(2, fixture.directory.concurrentCatalogReads.get());
                assertEquals(retry.catalogId(), fixture.importer.talkgroupCatalog(10, null, null).catalogId());
            }
            finally
            {
                gate.release.countDown();
                stopCatalogCall(owner);
                stopCatalogWorker(gate);
            }
        }
    }

    @Test
    void conventionalDraftNamesRequireRecordChoiceAndUseDescriptionWithoutImportingDecoderSettings() throws Exception
    {
        for(String protocol: List.of("am", "nbfm", "p25-conventional", "dmr", "nxdn"))
        {
            try(Fixture fixture = new Fixture(mTemporaryFolder))
            {
                String mode = switch(protocol) { case "am" -> "AM"; case "nbfm" -> "FMN";
                    case "p25-conventional" -> "P25"; case "dmr" -> "DMR"; default -> "NXDN48"; };
                fixture.directory.frequencyMatches = List.of(namingMatch(154_100_000L, mode, "Fire Dispatch", "Fire", null));
                fixture.directory.namingDetails = new RadioReferenceDirectoryService.FrequencyDetails(mode,
                    "Public Safety", "Fire", null,
                    new RadioReferenceDirectoryService.ConventionalContext("County", "Fire Agency", "State"));
                ChannelDefinition draft = namingDraft(fixture, protocol, true, 154_100_000L);
                long revision = fixture.channels.currentRevision();
                var choices = fixture.importer.previewChannelNames(null, null, draft, 10, null, null);
                assertEquals("choose_record", choices.state(), "Even a sole unverified record needs explicit selection");
                assertEquals(1, choices.candidates().size());
                assertNull(choices.previewId());
                var selected = fixture.importer.previewChannelNames(null, null, draft, 10, choices.lookupId(),
                    choices.candidates().getFirst().candidateId());
                assertEquals("matched", selected.state());
                assertEquals("County · Fire Agency", selected.names().system());
                assertEquals("Fire", selected.names().site());
                assertEquals("Fire Dispatch", selected.names().name());
                fixture.directory.conventional = List.of(new ConventionalFrequency(7, 154_100_000L, null, "",
                    "Fire Dispatch", "Fire", "", "", "", "", mode, 0, "", List.of(), 70));
                var directImport = fixture.importer.previewConventional(new RadioReferenceImportService.ConventionalImportRequest(
                    RadioReferenceGateway.DetailKind.AGENCY, 1001, 70, 7, null, null, null, null));
                assertEquals(selected.names(), new RadioReferenceChannelNames(directImport.channel().system(),
                    directImport.channel().site(), directImport.channel().name()),
                    "Direct import and Update names must use the same conventional defaults");
                assertEquals(revision, fixture.channels.currentRevision(), "Looking up names must not create or change a channel");
                assertTrue(fixture.channels.catalog().channels().isEmpty());
                assertThrows(IllegalArgumentException.class, () -> fixture.importer.applyChannelEnrichment(
                    selected.previewId(), true), "Draft names apply only to the editor");
                assertThrows(IllegalArgumentException.class, () -> fixture.importer.previewChannelNames(null, null,
                    draft, 10, choices.lookupId(), "browser-forged-record"));
            }
        }
    }

    @Test
    void savedConventionalNamesChooseAmongExactCompatibleRecordsAndPreserveLocalConfiguration() throws Exception
    {
        try(Fixture fixture = new Fixture(mTemporaryFolder))
        {
            ChannelDefinition draft = namingDraft(fixture, "nbfm", true, 154_100_000L);
            String id = fixture.channels.create(draft).configurationIds().getFirst();
            ChannelDefinition before = fixture.channels.get(id).channel();
            fixture.directory.frequencyMatches = List.of(
                namingMatch(154_100_000L, "FMN", "Fire Dispatch", "Fire", null),
                namingMatch(154_100_000L, "FMN", "Medical Dispatch", "EMS", null),
                namingMatch(154_100_000L, "DMR", "Wrong protocol", "DMR", null),
                namingMatch(155_100_000L, "FMN", "Wrong frequency", "Other", null));
            fixture.directory.namingDetails = new RadioReferenceDirectoryService.FrequencyDetails("FMN", "Safety", "Dispatch", null,
                new RadioReferenceDirectoryService.ConventionalContext("County", "Fire Agency", "State"));
            long revision = fixture.channels.currentRevision();
            var choices = fixture.importer.previewChannelNames(id, revision, null, 10, null, null);
            assertEquals(2, choices.candidates().size(), "Wrong frequency and protocol must be excluded");
            var selected = fixture.importer.previewChannelNames(id, revision, null, 10, choices.lookupId(),
                choices.candidates().stream().filter(value -> value.name().equals("Medical Dispatch"))
                    .findFirst().orElseThrow().candidateId());
            fixture.importer.applyChannelEnrichment(selected.previewId(), true);
            ChannelDefinition after = fixture.channels.get(id).channel();
            assertEquals("Medical Dispatch", after.name());
            assertEquals("County · Fire Agency", after.system());
            assertEquals("Dispatch", after.site());
            assertEquals(before.source(), after.source());
            assertEquals(before.settings(), after.settings());
            assertEquals(before.aliasListId(), after.aliasListId());
            assertEquals(before.frequencyMap(), after.frequencyMap());
            assertEquals(before.recorders(), after.recorders());
            assertEquals(before.radioResolveId(), after.radioResolveId());
            assertThrows(ChannelAdministrationService.StaleRevisionException.class, () ->
                fixture.importer.previewChannelNames(id, revision, null, 10, choices.lookupId(),
                    choices.candidates().getFirst().candidateId()));
        }
    }

    @Test
    void conventionalNamesRequireResolvedOwnerMetadataAndAllowCategoryFallbackWithoutChangingTheChannel() throws Exception
    {
        try(Fixture fixture = new Fixture(mTemporaryFolder))
        {
            fixture.directory.frequencyMatches = List.of(namingMatch(154_100_000L, "FMN", "Fire Dispatch", "Fire", null));
            String id = fixture.channels.create(namingDraft(fixture, "nbfm", true, 154_100_000L)).configurationIds().getFirst();
            ChannelDefinition before = fixture.channels.get(id).channel();
            long revision = fixture.channels.currentRevision();
            var owner = new RadioReferenceDirectoryService.ConventionalContext("County", "Fire Agency", "State");
            for(var details: List.of(
                new RadioReferenceDirectoryService.FrequencyDetails("FMN", "Public Safety", "Fire", null),
                new RadioReferenceDirectoryService.FrequencyDetails("FMN", "", " ", null, owner)))
            {
                fixture.directory.namingDetails = details;
                var unavailable = fixture.importer.previewChannelNames(id, revision, null, 10, null, null);
                assertEquals("unavailable", unavailable.state());
                assertTrue(unavailable.reason().contains("Try this lookup again"));
                assertNull(unavailable.previewId());
                assertNull(unavailable.lookupId());
                assertTrue(unavailable.candidates().isEmpty());
                assertEquals(before, fixture.channels.get(id).channel());
                assertEquals(revision, fixture.channels.currentRevision());
            }
            fixture.directory.namingDetails = new RadioReferenceDirectoryService.FrequencyDetails("FMN", "Public Safety", " ", null, owner);
            var retry = fixture.importer.previewChannelNames(id, revision, null, 10, null, null);
            assertEquals("choose_record", retry.state());
            assertEquals("Public Safety", retry.candidates().getFirst().site(), "A blank Subcategory falls back to its Category");
            assertEquals(before, fixture.channels.get(id).channel());
        }
    }

    @Test
    void manualTrunkedNamesUseExplicitDirectoryRecordWithoutCreatingObservedIdentity() throws Exception
    {
        try(Fixture fixture = new Fixture(mTemporaryFolder))
        {
            ChannelDefinition plain = namingDraft(fixture, "p25-phase1", false, 851_100_000L);
            ChannelDefinition draft = new ChannelDefinition(null, plain.protocolId(), plain.system(), plain.site(), plain.name(),
                plain.radioResolveId(), plain.aliasListId(), plain.source(), plain.settings(), plain.frequencyMap(),
                plain.eventLogs(), plain.recorders(), plain.auxiliaryDecoders(), new ChannelDefinition.Observed(List.of(),
                    Map.of("wacn", 0xBEE00, "system", 0x123, "rfss", 1, "site", 1)));
            fixture.directory.frequencyMatches = List.of(namingMatch(851_100_000L, "P25", "County P25", "", 10));
            var choices = fixture.importer.previewChannelNames(null, null, draft, 10, null, null);
            assertEquals("choose_record", choices.state());
            assertEquals(10, choices.candidates().getFirst().rrSystemId());
            var selected = fixture.importer.previewChannelNames(null, null, draft, 10, choices.lookupId(),
                choices.candidates().getFirst().candidateId());
            assertEquals("County P25 · Central Simulcast", selected.names().name());
            assertNull(selected.identity());
            assertEquals(0, fixture.directory.enrichmentReads, "Browser observed fields are not receiver proof");
            var preferred = selected.withPreferredAliasLists(Map.of(10, 77L));
            assertEquals(77L, preferred.preferredAliasListId());
            assertEquals(77L, preferred.candidates().getFirst().preferredAliasListId());
            fixture.importer.clearSessionData();
            assertThrows(RadioReferenceImportService.PreviewNotFoundException.class, () ->
                fixture.importer.previewChannelNames(null, null, draft, 10, choices.lookupId(),
                    choices.candidates().getFirst().candidateId()));
        }
    }

    @Test
    void verifiedLookupComparesAgainstTheDraftEvenWhenSavedNamesAlreadyMatch() throws Exception
    {
        try(Fixture fixture = new Fixture(mTemporaryFolder))
        {
            String id = enrichmentChannel(fixture, "p25-phase1", false);
            var initial = fixture.importer.previewChannelEnrichment(id, fixture.channels.currentRevision(), null);
            fixture.importer.applyChannelEnrichment(initial.previewId(), true);
            ChannelDefinition saved = fixture.channels.get(id).channel();
            ChannelDefinition draft = new ChannelDefinition(id, saved.protocolId(), "Draft system", "Draft site", "Draft name",
                saved.radioResolveId(), saved.aliasListId(), saved.source(), saved.settings(), saved.frequencyMap(),
                saved.eventLogs(), saved.recorders(), saved.auxiliaryDecoders(), saved.observed());
            var preview = fixture.importer.previewChannelNames(id, fixture.channels.currentRevision(), draft, null, null, null);
            assertEquals("matched", preview.state());
            assertEquals("County P25 · Central Simulcast", preview.names().name());
            assertEquals("Draft name", preview.changes().stream().filter(change -> change.field().equals("name"))
                .findFirst().orElseThrow().before());
            assertEquals(saved, fixture.channels.get(id).channel(), "The reviewed names must remain only a draft");
        }
    }

    @Test
    void ambiguousVerifiedP25NamesOfferOnlyMatchingIdentityRecordsAndRequireAChoice() throws Exception
    {
        try(Fixture fixture = new Fixture(mTemporaryFolder))
        {
            String id = enrichmentChannel(fixture, "p25-phase1", false);
            ChannelDefinition saved = fixture.channels.get(id).channel();
            var channels = List.of(channel(851_100_000L, "d", true, false));
            fixture.directory.sites = List.of(site("First name", "C4FM", channels),
                new TrunkedSiteDetails(21, 10, 1, "Second name", 1, 0, 1, "321", 0, "C4FM", false, channels),
                new TrunkedSiteDetails(22, 10, 2, "Wrong identity", 1, 0, 2, "321", 0, "C4FM", false, channels));
            fixture.directory.frequencyMatches = List.of(namingMatch(851_100_000L, "P25", "County P25", "", 10));
            var choices = fixture.importer.previewChannelNames(id, fixture.channels.currentRevision(), saved, 10, null, null);
            assertEquals("choose_record", choices.state());
            assertEquals(List.of("County P25 · First name", "County P25 · Second name"),
                choices.candidates().stream().map(RadioReferenceImportService.EnrichmentCandidate::name).toList());
            assertNull(choices.previewId());
            var selected = fixture.importer.previewChannelNames(id, fixture.channels.currentRevision(), saved, 10,
                choices.lookupId(), choices.candidates().get(1).candidateId());
            assertEquals(new P25SiteIdentity(0xBEE00, 0x123, 1, 1), selected.identity());
            assertEquals("County P25 · Second name", selected.names().name());
            fixture.importer.applyChannelEnrichment(selected.previewId(), true);
            assertEquals("County P25 · Second name", fixture.channels.get(id).channel().name());
            assertEquals(saved.settings(), fixture.channels.get(id).channel().settings());
        }
    }

    @Test
    void nxdnNamingSelectionAndLegacyApplyRemainBoundToTheLookedUpTransmissionMode() throws Exception
    {
        try(Fixture fixture = new Fixture(mTemporaryFolder))
        {
            ChannelDefinition original = namingDraft(fixture, "nxdn", true, 154_100_000L);
            fixture.directory.frequencyMatches = List.of(namingMatch(154_100_000L, "NXDN48", "Dispatch", "Fire", null));
            var choices = fixture.importer.previewChannelNames(null, null, original, 10, null, null);
            Map<String,Object> alteredSettings = new HashMap<>(original.settings());
            alteredSettings.put("transmission_mode", "M9600");
            ChannelDefinition altered = copy(original, original.source(), alteredSettings, original.recorders());
            assertThrows(IllegalStateException.class, () -> fixture.importer.previewChannelNames(null, null, altered,
                10, choices.lookupId(), choices.candidates().getFirst().candidateId()));
            String id = fixture.channels.create(original).configurationIds().getFirst();
            ChannelDefinition saved = fixture.channels.get(id).channel();
            ChannelDefinition savedAltered = copy(saved, saved.source(), alteredSettings, saved.recorders());
            fixture.directory.frequencyMatches = List.of(namingMatch(154_100_000L, "NXDN96", "Other rate", "Other", null));
            var differentRateChoices = fixture.importer.previewChannelNames(id, fixture.channels.currentRevision(), savedAltered,
                10, null, null);
            var differentRate = fixture.importer.previewChannelNames(id, fixture.channels.currentRevision(), savedAltered,
                10, differentRateChoices.lookupId(), differentRateChoices.candidates().getFirst().candidateId());
            assertThrows(IllegalStateException.class, () -> fixture.importer.applyChannelEnrichment(differentRate.previewId(), true));
            assertEquals(saved, fixture.channels.get(id).channel());
        }
    }

    @Test
    void namingLookupSharesOneDeadlineAcrossDetailsAndNeverReturnsPartialChoices() throws Exception
    {
        try(Fixture fixture = new Fixture(mTemporaryFolder))
        {
            var nanoTime = new java.util.concurrent.atomic.AtomicLong();
            var importer = new RadioReferenceImportService(fixture.directory, fixture.channels, fixture.aliases,
                Clock.fixed(Instant.parse("2026-09-22T12:00:00Z"), ZoneOffset.UTC), Duration.ofMinutes(5), nanoTime::get);
            fixture.directory.frequencyMatches = List.of(
                namingMatch(154_100_000L, "FMN", "First record", "Fire", null),
                namingMatch(154_100_000L, "FM", "Second record", "EMS", null));
            fixture.directory.duringNamingDetail = () -> nanoTime.addAndGet(Duration.ofSeconds(15).toNanos());
            var timeout = assertThrows(RadioReferenceDirectoryException.class, () -> importer.previewChannelNames(
                null, null, namingDraft(fixture, "nbfm", true, 154_100_000L), 10, null, null));
            assertEquals(RadioReferenceDirectoryException.Code.TIMEOUT, timeout.code());
            assertEquals(List.of(Duration.ofSeconds(25), Duration.ofSeconds(10)), fixture.directory.namingBudgets,
                "The second request gets only the remaining budget, not another complete deadline");
            assertTrue(fixture.channels.catalog().channels().isEmpty());
            fixture.directory.duringNamingDetail = null;
            var retried = importer.previewChannelNames(null, null, namingDraft(fixture, "nbfm", true, 154_100_000L),
                10, null, null);
            assertEquals("choose_record", retried.state());
            assertEquals(2, retried.candidates().size(), "Only a fully completed lookup can offer record choices");
        }
    }

    @Test
    void remoteRoutesMayShareFrequenciesWithLocalChannelsAndOtherRemoteRoutes() throws Exception
    {
        try(Fixture fixture = new Fixture(mTemporaryFolder))
        {
            fixture.channels.create(namingDraft(fixture, "nbfm", true, 154_100_000L), true);
            String sender = java.util.UUID.randomUUID().toString();
            String firstFeed = java.util.UUID.randomUUID().toString();
            fixture.channels.create(remoteNamingDraft(fixture, 154_100_000L, sender, firstFeed), true);
            fixture.channels.create(remoteNamingDraft(fixture, 154_100_000L, sender,
                java.util.UUID.randomUUID().toString()), true);
            fixture.channels.create(remoteNamingDraft(fixture, 154_200_000L,
                java.util.UUID.randomUUID().toString(), java.util.UUID.randomUUID().toString()), true);
            fixture.channels.create(namingDraft(fixture, "nbfm", true, 154_200_000L), true);
            assertEquals(5, fixture.channels.catalog().channels().size());
            var duplicate = assertThrows(IllegalArgumentException.class, () -> fixture.channels.create(
                remoteNamingDraft(fixture, 154_300_000L, sender, firstFeed), true));
            assertTrue(duplicate.getMessage().contains("already configured for this sender and feed"));
            assertEquals(5, fixture.channels.catalog().channels().size());
        }
    }

    private static ChannelDefinition remoteNamingDraft(Fixture fixture, long frequency, String sender, String feed)
    {
        ChannelDefinition draft = namingDraft(fixture, "p25-phase1", false, frequency);
        return copy(draft, new ChannelDefinition.Source(List.of(frequency), null, null, null, null, null,
            "REMOTE", sender, feed), draft.settings(), draft.recorders());
    }

    @Test
    void scopedCreateRejectsFrequencyAlreadyUsedByAnotherSavedChannelWithoutChangingEitherChannel() throws Exception
    {
        try(Fixture fixture = new Fixture(mTemporaryFolder))
        {
            ChannelDefinition first = namingDraft(fixture, "nbfm", true, 154_100_000L);
            String id = fixture.channels.create(first).configurationIds().getFirst();
            long revision = fixture.channels.currentRevision();
            var conflict = assertThrows(IllegalStateException.class, () -> fixture.channels.create(
                namingDraft(fixture, "am", true, 154_100_000L), true));
            assertEquals("A channel already uses 154.100000 MHz.", conflict.getMessage());
            assertEquals(revision, fixture.channels.currentRevision());
            assertEquals(1, fixture.channels.catalog().channels().size());
            assertEquals("My channel", fixture.channels.get(id).channel().name());
            fixture.channels.create(namingDraft(fixture, "nbfm", true, 154_200_000L));
            assertEquals(2, fixture.channels.catalog().channels().size());
        }
    }

    @Test
    void normalChannelCreationStillAllowsSeparateLocalDefinitionsOnTheSameFrequency() throws Exception
    {
        try(Fixture fixture = new Fixture(mTemporaryFolder))
        {
            fixture.channels.create(namingDraft(fixture, "nbfm", true, 154_100_000L));
            fixture.channels.create(namingDraft(fixture, "am", true, 154_100_000L));
            assertEquals(2, fixture.channels.catalog().channels().size());
        }
    }

    private static ChannelDefinition namingDraft(Fixture fixture, String protocol, boolean conventional, long frequency)
    {
        ChannelDefinition template = fixture.channels.template(protocol);
        Map<String,Object> settings = new HashMap<>(template.settings());
        if(protocol.equals("dmr") || protocol.equals("nxdn"))
            settings.put("channel_mode", conventional ? "CONVENTIONAL" : "TRUNKED");
        return new ChannelDefinition(null, protocol, "My system", "My site", "My channel", null,
            template.aliasListId(), new ChannelDefinition.Source(List.of(frequency), null, null, null, null, null),
            settings, template.frequencyMap(), template.eventLogs(), template.recorders(), template.auxiliaryDecoders(),
            ChannelDefinition.Observed.EMPTY);
    }

    private static RadioReferenceDirectoryService.FrequencyMatch namingMatch(long frequency, String mode,
        String description, String alpha, Integer systemId)
    {
        return new RadioReferenceDirectoryService.FrequencyMatch(frequency / 1_000_000.0, null, "123.0 PL", "WTEST",
            description, alpha, "", "", mode, mode, "", "", "", "", List.of(), systemId == null ?
            RadioReferenceDirectoryService.MatchType.CONVENTIONAL : RadioReferenceDirectoryService.MatchType.TRUNKED,
            "", 10, "State", 100, "County", 1000, "Fire Agency", 70, systemId, "", null, null, "",
            systemId == null ? "https://www.radioreference.com/db/subcat/70" : "https://www.radioreference.com/db/sid/" + systemId);
    }

    @Test
    void conventionalPreviewImportsOneRowWithTheFactoryAliasList() throws Exception
    {
        try(Fixture fixture = new Fixture(mTemporaryFolder))
        {
            fixture.directory.conventional = List.of(new ConventionalFrequency(7, 154_100_000L, null, "",
                "County dispatch", "Dispatch", "", "", "", "", "FMN", 0, "", List.of(), 70));
            RadioReferenceImportService.ChannelPreview preview = fixture.importer.previewConventional(
                new RadioReferenceImportService.ConventionalImportRequest(RadioReferenceGateway.DetailKind.AGENCY, 1001, 70, 7, null, "County", "Public Safety",
                    null));
            assertEquals(RadioReferenceImportService.ChannelAction.CREATE, preview.action());
            assertEquals("nbfm", preview.channel().protocolId());
            assertEquals("County dispatch", preview.channel().name());
            assertEquals("County", preview.channel().system(), "Preserve an edited system name");
            assertEquals("Public Safety", preview.channel().site(), "Preserve an edited site name");
            assertEquals("Default Analog", fixture.channels.options().aliasLists().stream()
                .filter(list -> list.id() == preview.channel().aliasListId()).findFirst().orElseThrow().name());
            assertEquals(List.of(154_100_000L), preview.channel().source().frequenciesHz());
            fixture.importer.applyChannel(preview.previewId());
        }
    }

    @Test
    void conventionalImportPreservesEditedNamesWhileUsingRadioReferenceDecoderDefaults() throws Exception
    {
        try(Fixture fixture = new Fixture(mTemporaryFolder))
        {
            fixture.directory.conventional = List.of(new ConventionalFrequency(7, 154_100_000L, null, "",
                "County dispatch", "Dispatch", "123.0 PL", "4", "100", "2", "NXDN96", 0, "", List.of(), 70));
            var preview = fixture.importer.previewConventional(new RadioReferenceImportService.ConventionalImportRequest(
                RadioReferenceGateway.DetailKind.AGENCY, 1001, 70, 7, null, "My system", "My site", "My channel"));
            assertEquals("My system", preview.channel().system());
            assertEquals("My site", preview.channel().site());
            assertEquals("My channel", preview.channel().name());
            assertEquals("nxdn", preview.channel().protocolId());
            assertEquals("CONVENTIONAL", preview.channel().settings().get("channel_mode"));
            assertEquals("M9600", preview.channel().settings().get("transmission_mode"));
        }
    }

    @Test
    void discoveryImportsFullTalkgroupCatalogAccuratelyAndFreshRetryPreservesLocalPolicy() throws Exception
    {
        try(Fixture fixture = new Fixture(mTemporaryFolder))
        {
            long listId = fixture.aliases.createAliasList("County public safety names", AliasListFamily.P25).aliasListId();
            fixture.directory.talkgroups.clear();
            fixture.directory.talkgroups.addAll(List.of(
                new RemoteTalkgroup(10, 101, "Fire Dispatch", "Primary fire dispatch", "D", 0, 50, List.of()),
                new RemoteTalkgroup(11, 65535, "Operations – East", "Eastern operations", "D", 0, 50, List.of()),
                new RemoteTalkgroup(12, 102, "Secure Ops", "Secure operations", "D", 2, 50, List.of()),
                new RemoteTalkgroup(13, 103, "", "Unnamed talkgroup", "D", 0, 999, List.of())));
            var matched = new RadioReferenceDiscoveryResolver.Result("matched", "",
                new RadioReferenceDiscoveryResolver.Match(10, 20, "County P25", "Central", List.of(), "", ""),
                "radioreference");
            var helper = new DiscoveryAliasImportService(fixture.importer);
            var batch = new DiscoveryAliasImportService.Batch();
            batch.register(listId, "County public safety names", "County P25", matched);
            helper.retain("discovery", batch);
            var imported = helper.importAliases("discovery", listId);
            assertTrue(imported.complete());
            assertEquals(4, imported.targets().getFirst().added());
            assertEquals(1, fixture.directory.catalogReads);
            var rows = fixture.aliases.transferSnapshot(listId).aliases();
            assertEquals(4, rows.size());
            Map<Integer,AliasAdministrationService.AliasEntry> byTalkgroup = rows.stream().collect(
                java.util.stream.Collectors.toMap(entry ->
                    ((io.github.dsheirer.alias.id.talkgroup.Talkgroup)entry.alias().getMatchIdentifier()).getValue(),
                    entry -> entry));
            assertEquals("Fire Dispatch", byTalkgroup.get(101).alias().getName());
            assertEquals("Primary fire dispatch", byTalkgroup.get(101).alias().getDescription());
            assertEquals("Public Safety", byTalkgroup.get(101).alias().getGroup());
            assertEquals("Operations – East", byTalkgroup.get(65535).alias().getName());
            assertEquals("103", byTalkgroup.get(103).alias().getName());
            assertEquals(null, byTalkgroup.get(103).alias().getGroup());
            assertFalse(byTalkgroup.get(102).alias().isRecordable());
            assertTrue(byTalkgroup.get(102).scanListIds().isEmpty());
            assertTrue(byTalkgroup.get(102).alias().getBroadcastChannels().isEmpty());
            Alias original = byTalkgroup.get(101).alias();
            Alias styled = AliasFactory.copyOf(original);
            styled.setColor(0x345678);
            styled.setRecordable(false);
            fixture.aliases.replaceAlias(original.getId(), styled, java.util.Set.of(),
                fixture.aliases.getAlias(original.getId()).editRevision());
            fixture.directory.talkgroups.set(0, new RemoteTalkgroup(10, 101, "Fire Dispatch Updated",
                "Primary fire dispatch updated", "D", 0, 50, List.of()));
            // A completed response retry must not fetch again or overwrite later user changes.
            assertEquals(imported, helper.importAliases("discovery", listId));
            assertEquals(1, fixture.directory.catalogReads);
            assertEquals("Fire Dispatch", fixture.aliases.getAlias(original.getId()).alias().getName());

            // Explicit source refresh makes new upstream values available to a later discovery retry.
            fixture.importer.talkgroupCatalog(10, listId, null, true);
            assertEquals(2, fixture.directory.catalogReads);
            // A new revision-bound retry after an uncertain prior apply matches IDs instead of duplicating aliases.
            var freshHelper = new DiscoveryAliasImportService(fixture.importer);
            var freshBatch = new DiscoveryAliasImportService.Batch();
            freshBatch.register(listId, "County public safety names", "County P25", matched);
            freshHelper.retain("recovered", freshBatch);
            var refreshed = freshHelper.importAliases("recovered", listId);
            assertTrue(refreshed.complete());
            assertEquals(0, refreshed.targets().getFirst().added());
            assertEquals(1, refreshed.targets().getFirst().updated());
            assertEquals(3, refreshed.targets().getFirst().unchanged());
            assertEquals(4, fixture.aliases.transferSnapshot(listId).aliases().size());
            var current = fixture.aliases.getAlias(original.getId());
            assertEquals("Fire Dispatch Updated", current.alias().getName());
            assertEquals("Primary fire dispatch updated", current.alias().getDescription());
            assertEquals(0x345678, current.alias().getColor());
            assertFalse(current.alias().isRecordable());
            assertTrue(current.scanListIds().isEmpty());

            long incompatible = fixture.aliases.createAliasList("DMR destination", AliasListFamily.DMR).aliasListId();
            var wrongBatch = new DiscoveryAliasImportService.Batch();
            wrongBatch.register(incompatible, "DMR destination", "County P25", matched);
            freshHelper.retain("wrong-family", wrongBatch);
            assertEquals("failed", freshHelper.importAliases("wrong-family", incompatible).targets().getFirst().state());
            assertTrue(fixture.aliases.transferSnapshot(incompatible).aliases().isEmpty());
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "VCE_DISCOVERY_REAL_DATA", matches = ".+")
    void capturedPublicRadioReferenceTalkgroupsImportAccuratelyInAnIsolatedDatabase() throws Exception
    {
        ObjectMapper mapper = new ObjectMapper();
        var source = mapper.readTree(Path.of(System.getenv("VCE_DISCOVERY_REAL_DATA")).toFile());
        var talkgroups = source.required("talkgroups");
        assertEquals(28, talkgroups.size(), "The local evidence fixture is the captured 28-row catalog");
        int systemId = source.required("system_id").intValue();
        String systemName = source.required("system_name").textValue();
        try(Fixture fixture = new Fixture(mTemporaryFolder))
        {
            fixture.directory.system = new TrunkedSystemDetails(systemId, systemName, "", "Project 25",
                "Phase II", "Digital", String.format("%05X", source.required("wacn").intValue()),
                String.format("%03X", source.required("system").intValue()));
            fixture.directory.talkgroups.clear();
            fixture.directory.categories.clear();
            Map<String,Integer> categoryIds = new java.util.LinkedHashMap<>();
            int index = 0;
            for(var row: talkgroups)
            {
                String category = row.required("category_name").textValue();
                int categoryId = categoryIds.computeIfAbsent(category, ignored -> categoryIds.size() + 1);
                String mode = row.required("mode").textValue();
                fixture.directory.talkgroups.add(new RemoteTalkgroup(++index, row.required("value").intValue(),
                    row.required("alpha_tag").textValue(), row.required("description").textValue(), mode,
                    mode.endsWith("E") ? 2 : 0, categoryId, List.of()));
            }
            categoryIds.forEach((name, id) -> fixture.directory.categories.add(
                new RemoteTalkgroupCategory(id, systemId, name)));
            long listId = fixture.aliases.createAliasList(systemName, AliasListFamily.P25).aliasListId();
            var match = new RadioReferenceDiscoveryResolver.Result("matched", "",
                new RadioReferenceDiscoveryResolver.Match(systemId, 0, systemName, "", List.of(), "", ""),
                "radioreference");
            var helper = new DiscoveryAliasImportService(fixture.importer);
            var batch = new DiscoveryAliasImportService.Batch();
            batch.register(listId, systemName, systemName, match);
            helper.retain("real-data-wizard", batch);
            var result = helper.importAliases("real-data-wizard", listId);
            assertTrue(result.complete());
            assertEquals(28, result.targets().getFirst().added());
            var imported = fixture.aliases.transferSnapshot(listId).aliases();
            Map<Integer,AliasAdministrationService.AliasEntry> byTalkgroup = imported.stream().collect(
                java.util.stream.Collectors.toMap(entry ->
                    ((io.github.dsheirer.alias.id.talkgroup.Talkgroup)entry.alias().getMatchIdentifier()).getValue(),
                    entry -> entry));
            assertEquals(28, byTalkgroup.size());
            int encrypted = 0;
            for(var expected: talkgroups)
            {
                var actual = byTalkgroup.get(expected.required("value").intValue());
                assertEquals(expected.required("alpha_tag").textValue(), actual.alias().getName());
                assertEquals(expected.required("description").textValue(), actual.alias().getDescription());
                assertEquals(expected.required("category_name").textValue(), actual.alias().getGroup());
                assertEquals(listId, actual.alias().getAliasListId());
                if(expected.required("mode").textValue().endsWith("E"))
                {
                    encrypted++;
                    assertFalse(actual.alias().isRecordable());
                    assertTrue(actual.scanListIds().isEmpty());
                    assertTrue(actual.alias().getBroadcastChannels().isEmpty());
                }
            }
            assertEquals(2, encrypted);
            assertEquals(result, helper.importAliases("real-data-wizard", listId));
            // Simulate reconstructing an import after an uncertain earlier response, without the success cache.
            var recovered = new DiscoveryAliasImportService(fixture.importer);
            var retry = new DiscoveryAliasImportService.Batch();
            retry.register(listId, systemName, systemName, match);
            recovered.retain("real-data-retry", retry);
            var repeated = recovered.importAliases("real-data-retry", listId);
            assertTrue(repeated.complete());
            assertEquals(0, repeated.targets().getFirst().added());
            assertEquals(0, repeated.targets().getFirst().updated());
            assertEquals(28, repeated.targets().getFirst().unchanged());
            var after = fixture.aliases.transferSnapshot(listId).aliases();
            assertEquals(imported.stream().map(entry -> entry.alias().getId()).toList(),
                after.stream().map(entry -> entry.alias().getId()).toList());
            String report = System.getenv("VCE_DISCOVERY_REAL_DATA_REPORT");
            if(report != null && !report.isBlank())
            {
                mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of(report).toFile(), Map.of(
                    "system_id", systemId, "talkgroups_verified", 28, "names_verified", 28,
                    "descriptions_verified", 28, "categories_verified", 28,
                    "fully_encrypted_with_audio_record_stream_disabled", encrypted,
                    "retry_added", repeated.targets().getFirst().added(), "retry_unchanged", 28,
                    "isolated_database", true));
            }
        }
    }

    @Test
    void conventionalPreviewUsesAnExplicitCompatibleAliasListAndRejectsOtherFamilies() throws Exception
    {
        try(Fixture fixture = new Fixture(mTemporaryFolder))
        {
            fixture.directory.conventional = List.of(new ConventionalFrequency(7, 154_100_000L, null, "",
                "County dispatch", "Dispatch", "", "", "", "", "FMN", 0, "", List.of(), 70));
            long selectedListId = fixture.aliases.createAliasList("County Analog", AliasListFamily.NBFM)
                .aliasListId();
            RadioReferenceImportService.ChannelPreview preview = fixture.importer.previewConventional(
                new RadioReferenceImportService.ConventionalImportRequest(RadioReferenceGateway.DetailKind.AGENCY, 1001, 70, 7, selectedListId, "County",
                    "Public Safety", null));

            assertEquals(selectedListId, preview.channel().aliasListId());

            long incompatibleListId = aliasList(fixture, AliasListFamily.P25);
            IllegalArgumentException incompatible = assertThrows(IllegalArgumentException.class,
                () -> fixture.importer.previewConventional(
                    new RadioReferenceImportService.ConventionalImportRequest(RadioReferenceGateway.DetailKind.AGENCY, 1001, 70, 7, incompatibleListId,
                        "County", "Public Safety", null)));
            assertTrue(incompatible.getMessage().contains("not compatible"));
        }
    }

    @Test
    void preferredTalkgroupListMustBeAnExistingTrunkedFamily() throws Exception
    {
        try(Fixture fixture = new Fixture(mTemporaryFolder))
        {
            assertTrue(fixture.importer.preferredTalkgroupAliasListExists(
                aliasList(fixture, AliasListFamily.P25)));
            assertTrue(fixture.importer.preferredTalkgroupAliasListExists(
                aliasList(fixture, AliasListFamily.DMR)));
            assertTrue(fixture.importer.preferredTalkgroupAliasListExists(
                aliasList(fixture, AliasListFamily.NXDN)));
            assertFalse(fixture.importer.preferredTalkgroupAliasListExists(
                aliasList(fixture, AliasListFamily.NBFM)));
            assertFalse(fixture.importer.preferredTalkgroupAliasListExists(Long.MAX_VALUE));
        }
    }

    @Test
    void enrichesP25NamesFromSavedIdentityAndPreservesEveryOtherSetting() throws Exception
    {
        try(Fixture fixture = new Fixture(mTemporaryFolder))
        {
            for(String protocol: List.of("p25-phase1", "p25-phase2"))
            {
                String id = enrichmentChannel(fixture, protocol, false);
                ChannelAdministrationService.Entry before = fixture.channels.get(id);
                RadioReferenceImportService.EnrichmentPreview preview = fixture.importer.previewChannelEnrichment(
                    id, before.revision(), null);
                assertEquals("matched", preview.state());
                assertEquals(List.of("system", "site", "name"), preview.changes().stream()
                    .map(RadioReferenceImportService.EnrichmentChange::field).toList());
                assertEquals(851_100_000L, fixture.directory.enrichmentIdentity.frequencyHz());
                assertEquals(0xBEE00, fixture.directory.enrichmentIdentity.wacn());
                int lookups = fixture.directory.enrichmentReads;
                fixture.importer.applyChannelEnrichment(preview.previewId(), protocol.equals("p25-phase2"));
                ChannelDefinition after = fixture.channels.get(id).channel();
                assertEquals("County P25", after.system());
                assertEquals("Central Simulcast", after.site());
                assertEquals(protocol.equals("p25-phase2") ? "County P25 · Central Simulcast" : "My channel", after.name());
                assertEquals(before.channel().source(), after.source());
                assertEquals(before.channel().settings(), after.settings());
                assertEquals(before.channel().radioResolveId(), after.radioResolveId());
                assertEquals(before.channel().aliasListId(), after.aliasListId());
                assertEquals(before.channel().frequencyMap(), after.frequencyMap());
                assertEquals(before.channel().eventLogs(), after.eventLogs());
                assertEquals(before.channel().recorders(), after.recorders());
                assertEquals(before.channel().auxiliaryDecoders(), after.auxiliaryDecoders());
                assertEquals(before.channel().observed(), after.observed());
                assertEquals(lookups, fixture.directory.enrichmentReads);
                assertThrows(RadioReferenceImportService.PreviewNotFoundException.class,
                    () -> fixture.importer.applyChannelEnrichment(preview.previewId(), true));
            }
        }
    }

    @Test
    void enrichmentRejectsOtherProtocolsMissingIdentityWrongFrequencyAndAmbiguousSites() throws Exception
    {
        try(Fixture fixture = new Fixture(mTemporaryFolder))
        {
            for(String protocol: List.of("p25-conventional", "dmr", "nxdn"))
            {
                String id = enrichmentChannel(fixture, protocol, false);
                assertEquals("unsupported_protocol", fixture.importer.previewChannelEnrichment(id,
                    fixture.channels.currentRevision(), null).state());
            }
            String id = enrichmentChannel(fixture, "p25-phase1", false);
            Channel channel = savedChannel(fixture, id);
            channel.setP25SiteIdentity(null);
            assertEquals("identity_required", fixture.importer.previewChannelEnrichment(id,
                fixture.channels.currentRevision(), null).state());
            assertEquals(0, fixture.directory.enrichmentReads);
            channel.setP25SiteIdentity(new P25SiteIdentity(0xBEE00, 0x123, 1, 1));
            fixture.directory.site = site("Wrong frequency", "", List.of(channel(852_000_000L, "d", true, false)));
            assertEquals("no_match", fixture.importer.previewChannelEnrichment(id,
                fixture.channels.currentRevision(), null).state());
            TrunkedSiteDetails one = site("One", "", List.of(channel(851_100_000L, "d", true, false)));
            fixture.directory.sites = List.of(one, new TrunkedSiteDetails(21, 10, 1, "Two", 1, 0, 1, "321", 0,
                "C4FM", false, one.channels()));
            RadioReferenceImportService.EnrichmentPreview ambiguous = fixture.importer.previewChannelEnrichment(id,
                fixture.channels.currentRevision(), null);
            assertEquals("ambiguous", ambiguous.state());
            assertNull(ambiguous.previewId());
            assertEquals("My system", fixture.channels.get(id).channel().system());
        }
    }

    @Test
    void enrichmentCannotEraseNamesAndPreservesRemoteSource() throws Exception
    {
        try(Fixture fixture = new Fixture(mTemporaryFolder))
        {
            String id = enrichmentChannel(fixture, "p25-phase1", true);
            fixture.directory.system = new TrunkedSystemDetails(10, "", "", "Project 25", "Phase I", "Digital",
                "BEE00", "123");
            fixture.directory.site = site("", "", List.of(channel(851_100_000L, "d", true, false)));
            assertEquals("no_changes", fixture.importer.previewChannelEnrichment(id,
                fixture.channels.currentRevision(), null).state());
            fixture.directory.system = p25System();
            fixture.directory.site = site("Central", "", List.of(channel(851_100_000L, "d", true, false)));
            ChannelDefinition before = fixture.channels.get(id).channel();
            var preview = fixture.importer.previewChannelEnrichment(id, fixture.channels.currentRevision(), null);
            fixture.importer.applyChannelEnrichment(preview.previewId(), true);
            assertEquals(before.source(), fixture.channels.get(id).channel().source());
            assertEquals(before.radioResolveId(), fixture.channels.get(id).channel().radioResolveId());
        }
    }

    @Test
    void enrichmentRejectsRevisionIdentityAndSessionChanges() throws Exception
    {
        try(Fixture fixture = new Fixture(mTemporaryFolder))
        {
            String id = enrichmentChannel(fixture, "p25-phase1", false);
            assertThrows(ChannelAdministrationService.StaleRevisionException.class,
                () -> fixture.importer.previewChannelEnrichment(id, fixture.channels.currentRevision() - 1, null));
            var preview = fixture.importer.previewChannelEnrichment(id, fixture.channels.currentRevision(), null);
            enrichmentChannel(fixture, "p25-phase1", false);
            assertThrows(ChannelAdministrationService.StaleRevisionException.class,
                () -> fixture.importer.applyChannelEnrichment(preview.previewId(), true));
            var identityPreview = fixture.importer.previewChannelEnrichment(id, fixture.channels.currentRevision(), null);
            commitSiteIdentity(fixture, id, new P25SiteIdentity(0xBEE00, 0x124, 1, 1));
            assertThrows(ChannelAdministrationService.StaleRevisionException.class,
                () -> fixture.importer.applyChannelEnrichment(identityPreview.previewId(), true));
            commitSiteIdentity(fixture, id, new P25SiteIdentity(0xBEE00, 0x123, 1, 1));
            var sessionPreview = fixture.importer.previewChannelEnrichment(id, fixture.channels.currentRevision(), null);
            fixture.importer.clearSessionData();
            assertThrows(RadioReferenceImportService.PreviewNotFoundException.class,
                () -> fixture.importer.applyChannelEnrichment(sessionPreview.previewId(), true));
            fixture.directory.duringEnrichment = fixture.importer::clearSessionData;
            assertThrows(RadioReferenceImportService.PreviewNotFoundException.class,
                () -> fixture.importer.previewChannelEnrichment(id, fixture.channels.currentRevision(), null));
            fixture.directory.duringEnrichment = null;
            var accountPreview = fixture.importer.previewChannelEnrichment(id, fixture.channels.currentRevision(), null);
            fixture.directory.sessionGeneration++;
            assertThrows(RadioReferenceImportService.PreviewNotFoundException.class,
                () -> fixture.importer.applyChannelEnrichment(accountPreview.previewId(), true));
            fixture.directory.duringEnrichment = () -> fixture.directory.sessionGeneration++;
            assertThrows(RadioReferenceImportService.PreviewNotFoundException.class,
                () -> fixture.importer.previewChannelEnrichment(id, fixture.channels.currentRevision(), null));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void enrichmentUsesLiveRemoteParentFrequencyAndRejectsConflictingDecodedIdentity() throws Exception
    {
        try(Fixture fixture = new Fixture(mTemporaryFolder))
        {
            String id = enrichmentChannel(fixture, "p25-phase1", true);
            var pending = fixture.importer.previewChannelEnrichment(id, fixture.channels.currentRevision(), null);
            Channel channel = savedChannel(fixture, id);
            TestP25Provider provider = new TestP25Provider();
            ProcessingChain parent = new ProcessingChain(channel, fixture.manager.getAliasModel())
            {
                private final Source source = new FixedFrequencySource(851_200_000L);
                @Override public boolean isProcessing() { return true; }
                @Override public Source getSource() { return source; }
                @Override public List<Module> getModules() { return List.of(provider); }
            };
            Field field = ChannelProcessingManager.class.getDeclaredField("mProcessingChainsMap");
            field.setAccessible(true);
            Map<Channel,ProcessingChain> chains = (Map<Channel,ProcessingChain>)field.get(
                fixture.manager.getChannelProcessingManager());
            chains.put(channel, parent);
            try
            {
                assertEquals("matched", fixture.importer.previewChannelEnrichment(id,
                    fixture.channels.currentRevision(), null).state());
                assertEquals(851_200_000L, fixture.directory.enrichmentIdentity.frequencyHz());
                provider.system = 0x124;
                assertThrows(IllegalStateException.class, () -> fixture.importer.previewChannelEnrichment(id,
                    fixture.channels.currentRevision(), null));
                assertThrows(IllegalStateException.class,
                    () -> fixture.importer.applyChannelEnrichment(pending.previewId(), true));
                assertEquals(0x123, channel.getP25SiteIdentity().system());
                assertEquals("My system", channel.getSystem());
            }
            finally
            {
                chains.remove(channel);
                parent.dispose();
            }
        }
    }

    private static final class TestP25Provider extends Module implements P25NetworkConfigurationSnapshotProvider
    {
        private int system = 0x123;
        @Override public P25NetworkConfigurationSnapshot getP25NetworkConfigurationSnapshot()
        {
            return new P25NetworkConfigurationSnapshot("P25_PHASE1",
                new P25NetworkConfigurationSnapshot.Network(0xBEE00, system, 0x321, null),
                new P25NetworkConfigurationSnapshot.CurrentSite(system, 0x321, 1, 1, null, null),
                List.of(), List.of(), List.of(), List.of(), List.of());
        }
        @Override public void reset() {}
        @Override public void start() {}
        @Override public void stop() {}
    }

    private static final class FixedFrequencySource extends Source
    {
        private final long mFrequency;
        private FixedFrequencySource(long frequency) { mFrequency = frequency; }
        @Override public SampleType getSampleType() { return SampleType.COMPLEX; }
        @Override public double getSampleRate() { return 2_400_000; }
        @Override public long getFrequency() { return mFrequency; }
        @Override public void reset() {}
        @Override public void start() {}
        @Override public void stop() {}
        @Override public Listener<SourceEvent> getSourceEventListener() { return ignored -> {}; }
        @Override public void setSourceEventListener(Listener<SourceEvent> listener) {}
        @Override public void removeSourceEventListener() {}
    }

    private static String enrichmentChannel(Fixture fixture, String protocol, boolean remote)
    {
        long list = aliasList(fixture, protocol.equals("dmr") ? AliasListFamily.DMR :
            protocol.equals("nxdn") ? AliasListFamily.NXDN : AliasListFamily.P25);
        ChannelDefinition template = fixture.channels.template(protocol);
        ChannelDefinition.Source source = remote ? new ChannelDefinition.Source(List.of(851_100_000L), null,
            null, null, null, null, "REMOTE", java.util.UUID.randomUUID().toString(), java.util.UUID.randomUUID().toString()) :
            new ChannelDefinition.Source(List.of(851_100_000L), null, null, null, null, null);
        ChannelDefinition definition = new ChannelDefinition(null, protocol, "My system", "My site", "My channel",
            java.util.UUID.randomUUID().toString(), list, source, template.settings(), template.frequencyMap(),
            template.eventLogs(), template.recorders(), template.auxiliaryDecoders(), template.observed());
        String id = fixture.channels.create(definition).configurationIds().getFirst();
        if(protocol.startsWith("p25-phase"))
            savedChannel(fixture, id).setP25SiteIdentity(new P25SiteIdentity(0xBEE00, 0x123, 1, 1));
        return id;
    }

    private static Channel savedChannel(Fixture fixture, String id)
    {
        return fixture.manager.getChannelModel().getChannels().stream()
            .filter(channel -> channel.getConfigurationId().equals(id)).findFirst().orElseThrow();
    }

    private static void commitSiteIdentity(Fixture fixture, String id, P25SiteIdentity identity)
    {
        fixture.manager.executeConfigurationTask(() ->
        {
            List<Channel> channels = fixture.manager.getChannelModel().getChannels().stream().map(channel ->
            {
                Channel candidate = channel.copyOfPreservingIdentity();
                if(id.equals(candidate.getConfigurationId())) candidate.setP25SiteIdentity(identity);
                return candidate;
            }).toList();
            fixture.manager.commitAndPublishChannelConfiguration(new ChannelConfigurationSnapshot(channels),
                Set.of(id), false);
            return null;
        });
    }

    private static ChannelDefinition copy(ChannelDefinition source, ChannelDefinition.Source sourceConfiguration,
                                          Map<String,Object> settings, List<String> recorders)
    {
        return new ChannelDefinition(source.configurationId(), source.protocolId(), source.system(), source.site(),
            source.name(), source.radioResolveId(), source.aliasListId(), sourceConfiguration, settings,
            source.frequencyMap(), source.eventLogs(), recorders, source.auxiliaryDecoders(), source.observed());
    }

    private static TrunkedSystemDetails p25System()
    {
        return new TrunkedSystemDetails(10, "County P25", "", "Project 25", "Phase I", "Digital", "BEE00",
            "123");
    }

    private static TrunkedSiteDetails site(String name, String modulation, List<TrunkedSiteChannel> channels)
    {
        return new TrunkedSiteDetails(20, 10, 1, name, 1, 0, 1, "321", 0, modulation, false, channels);
    }

    private static TrunkedSiteChannel channel(long frequency, String use, boolean primary, boolean alternate)
    {
        return new TrunkedSiteChannel(frequency, (int)((frequency / 100_000L) % 2_000L) + 1, "", use, "",
            primary, alternate);
    }

    private static final class Fixture implements AutoCloseable
    {
        private final ConfigurationManager manager;
        private final AliasAdministrationService aliases;
        private final ChannelAdministrationService channels;
        private final FakeDirectory directory = new FakeDirectory();
        private final RadioReferenceImportService importer;

        private Fixture(Path root) throws Exception
        {
            Path dataRoot = root.resolve("rr-import-" + UUIDHolder.next());
            SdrTrunkTestDatabase.create(SdrTrunkDatabasePath.getDatabasePath(dataRoot));
            manager = new ConfigurationManager(new TestUserPreferences(dataRoot), null, new AliasModel(), null);
            manager.init();
            aliases = AliasAdministrationServiceTestSupport.create(manager);
            channels = ChannelAdministrationServiceTestSupport.create(manager);
            importer = new RadioReferenceImportService(directory, channels, aliases,
                Clock.fixed(Instant.parse("2026-09-22T12:00:00Z"), ZoneOffset.UTC), Duration.ofMinutes(5));
        }

        @Override
        public void close()
        {
            manager.quiesceAdministration();
            manager.getChannelProcessingManager().close();
            manager.getBroadcastModel().close();
            manager.close();
            manager.getRadioReferenceDirectoryService().close();
        }
    }

    private static <T> PendingCall<T> catalogCall(Callable<T> call)
    {
        FutureTask<T> result = new FutureTask<>(call);
        Thread thread = new Thread(result, "RadioReference catalog regression fixture");
        thread.setDaemon(true);
        thread.start();
        return new PendingCall<>(result, thread);
    }

    private static void awaitSharedCatalogWait(Thread thread)
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while(thread.isAlive() && System.nanoTime() < deadline)
        {
            if(thread.getState() == Thread.State.TIMED_WAITING &&
                java.util.Arrays.stream(thread.getStackTrace()).anyMatch(frame ->
                    frame.getClassName().equals("java.util.concurrent.CompletableFuture") &&
                        frame.getMethodName().equals("timedGet")))
            {
                return;
            }
            Thread.yield();
        }
        throw new AssertionError("The concurrent request did not join the shared catalog load");
    }

    private static void stopCatalogCall(PendingCall<?> call) throws InterruptedException
    {
        if(call != null)
        {
            call.result().cancel(true);
            call.thread().join(2_000);
            assertFalse(call.thread().isAlive(), "The catalog regression fixture thread must terminate");
        }
    }

    private static void stopCatalogWorker(CatalogGate gate) throws InterruptedException
    {
        if(gate.worker != null)
        {
            gate.worker.join(2_000);
            assertFalse(gate.worker.isAlive(), "The old catalog loader must finish before its cache is checked");
        }
    }

    private record PendingCall<T>(FutureTask<T> result, Thread thread) {}

    private static final class CatalogGate
    {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private volatile Thread worker;
    }

    private static final class FakeDirectory implements RadioReferenceImportService.DirectoryAccess
    {
        private TrunkedSystemDetails system = p25System();
        private TrunkedSiteDetails site = site("Central Simulcast", "C4FM", List.of(
            channel(851_100_000L, "d", true, false), channel(851_200_000L, "a", false, true),
            channel(851_300_000L, "", false, false)));
        private final List<RemoteTalkgroup> talkgroups = new ArrayList<>(List.of(
            new RemoteTalkgroup(1, 101, "Dispatch", "Primary dispatch", "D", 0, 50, List.of()),
            new RemoteTalkgroup(2, 102, "Encrypted", "Encrypted operations", "D", 2, 50, List.of())));
        private final List<RemoteTalkgroupCategory> categories = new ArrayList<>(
            List.of(new RemoteTalkgroupCategory(50, 10, "Public Safety")));
        private List<ConventionalFrequency> conventional = List.of();
        private List<RadioReferenceDirectoryService.FrequencyMatch> frequencyMatches = List.of();
        private RadioReferenceDirectoryService.FrequencyDetails namingDetails;
        private Runnable duringNamingDetail;
        private final List<Duration> namingBudgets = new ArrayList<>();
        @Override public RadioReferenceDirectoryService.FrequencyDetails frequencyDetails(long frequencyHz,
            Integer systemId, int siteNumber, int subCategoryId, int agencyId, int countyId, String rawMode,
            Duration remainingBudget)
        {
            namingBudgets.add(remainingBudget);
            if(duringNamingDetail != null) duringNamingDetail.run();
            return frequencyDetails(frequencyHz, systemId, siteNumber, subCategoryId, agencyId, countyId, rawMode);
        }
        @Override public BoundedPage<RadioReferenceDirectoryService.FrequencyMatch> searchStateFrequencies(int stateId,
            long frequencyHz, int offset, int limit)
        { return new BoundedPage<>(frequencyMatches, 0, null, frequencyMatches.size()); }
        @Override public RadioReferenceDirectoryService.FrequencyDetails frequencyDetails(long frequencyHz,
            Integer systemId, int siteNumber, int subCategoryId, int agencyId, int countyId, String rawMode)
        { return namingDetails != null ? new RadioReferenceDirectoryService.FrequencyDetails(rawMode,
            namingDetails.category(), namingDetails.subCategory(), null, namingDetails.conventionalContext()) :
            new RadioReferenceDirectoryService.FrequencyDetails(rawMode, "Public Safety", "Fire", null,
                new RadioReferenceDirectoryService.ConventionalContext("County", "Fire Agency", "State")); }
        private List<TrunkedSiteDetails> sites;
        private int siteCatalogReads;
        private int systemReads;
        private int catalogReads;
        private int categoryReads;
        private final AtomicInteger concurrentCatalogReads = new AtomicInteger();
        private volatile CatalogGate catalogGate;
        private volatile RadioReferenceDirectoryException.Code catalogFailure;
        private volatile RadioReferenceDirectoryException.Code categoryFailure;
        private volatile RadioReferenceDirectoryException.Code catalogSessionFailure;
        private int enrichmentReads;
        private RadioReferenceDiscoveryResolver.Identity enrichmentIdentity;
        private Runnable duringEnrichment;
        private long sessionGeneration;

        @Override public long sessionGeneration() { return sessionGeneration; }

        @Override public RadioReferenceDiscoveryResolver.Result resolveP25Identity(Integer stateId,
            RadioReferenceDiscoveryResolver.Identity identity)
        {
            enrichmentReads++;
            enrichmentIdentity = identity;
            if(duringEnrichment != null) duringEnrichment.run();
            return RadioReferenceDiscoveryResolver.match(identity, List.of(new RadioReferenceDirectoryService.DiscoverySystem(
                system, sites == null ? List.of(site) : sites)));
        }

        @Override public void requireCatalogSession() throws RadioReferenceDirectoryException
        {
            if(catalogSessionFailure != null) throw new RadioReferenceDirectoryException(catalogSessionFailure);
        }
        @Override public TrunkedSystemDetails trunkedSystemDetails(int systemId) { systemReads++; return system; }
        @Override public List<TrunkedSiteDetails> allTrunkedSites(int systemId)
            { siteCatalogReads++; return sites == null ? List.of(site) : sites; }
        @Override public BoundedPage<RemoteTalkgroup> talkgroups(int systemId, Integer categoryId, String search,
            int offset, int limit) { return new BoundedPage<>(talkgroups, 0, null, talkgroups.size()); }
        @Override public List<RemoteTalkgroup> allTalkgroups(int systemId) throws RadioReferenceDirectoryException
        {
            catalogReads++;
            concurrentCatalogReads.incrementAndGet();
            if(catalogFailure != null) throw new RadioReferenceDirectoryException(catalogFailure);
            List<RemoteTalkgroup> result = List.copyOf(talkgroups);
            CatalogGate gate = catalogGate;
            if(gate != null)
            {
                gate.worker = Thread.currentThread();
                gate.entered.countDown();
                try
                {
                    if(!gate.release.await(5, TimeUnit.SECONDS))
                        throw new RadioReferenceDirectoryException(RadioReferenceDirectoryException.Code.TIMEOUT);
                }
                catch(InterruptedException exception)
                {
                    Thread.currentThread().interrupt();
                    throw new RadioReferenceDirectoryException(RadioReferenceDirectoryException.Code.INTERRUPTED);
                }
            }
            return result;
        }
        @Override public BoundedPage<RemoteTalkgroupCategory> talkgroupCategories(int systemId, int offset, int limit)
            { return new BoundedPage<>(categories, 0, null, categories.size()); }
        @Override public List<RemoteTalkgroupCategory> allTalkgroupCategories(int systemId)
            throws RadioReferenceDirectoryException
        {
            categoryReads++;
            if(categoryFailure != null) throw new RadioReferenceDirectoryException(categoryFailure);
            return categories;
        }
        @Override public RadioReferenceChannelNames conventionalNames(RadioReferenceGateway.DetailKind ownerKind,
            int ownerId, int subCategoryId, ConventionalFrequency frequency)
        {
            assertEquals(RadioReferenceGateway.DetailKind.AGENCY, ownerKind);
            assertEquals(1001, ownerId);
            assertEquals(frequency.subCategoryId(), subCategoryId);
            return RadioReferenceChannelNames.conventional("County", "Fire Agency", "State",
                namingDetails == null ? "Public Safety" : namingDetails.category(),
                namingDetails == null ? "Fire" : namingDetails.subCategory(),
                frequency.description(), frequency.alphaTag(), frequency.downlinkHz());
        }
        @Override public List<ConventionalFrequency> conventionalFrequenciesById(int subCategoryId,
            Collection<Integer> ids)
            { return ids.stream().map(id -> conventional.stream().filter(row -> row.id() == id).findFirst().orElseThrow()).toList(); }
    }

    private static final class TestUserPreferences extends UserPreferences
    {
        private final DirectoryPreference mDirectoryPreference;

        private TestUserPreferences(Path dataRoot)
        {
            mDirectoryPreference = new DirectoryPreference(preferenceType -> {})
            {
                @Override public Path getDirectoryApplicationRoot() { return dataRoot; }
            };
        }

        @Override public DirectoryPreference getDirectoryPreference() { return mDirectoryPreference; }
    }

    private static final class UUIDHolder
    {
        private static int sSequence;
        private static synchronized int next() { return ++sSequence; }
    }
}
