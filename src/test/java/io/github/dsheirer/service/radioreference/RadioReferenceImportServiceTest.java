/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.service.radioreference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasAdministrationService;
import io.github.dsheirer.alias.AliasAdministrationServiceTestSupport;
import io.github.dsheirer.alias.AliasFactory;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.channel.ChannelAdministrationService;
import io.github.dsheirer.channel.ChannelAdministrationServiceTestSupport;
import io.github.dsheirer.channel.ChannelDefinition;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkTestDatabase;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.directory.DirectoryPreference;
import io.github.dsheirer.service.radioreference.RadioReferenceDirectoryService.BoundedPage;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.ConventionalFrequency;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.RemoteTalkgroup;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.RemoteTalkgroupCategory;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.TrunkedSiteChannel;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.TrunkedSiteDetails;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.TrunkedSystemDetails;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
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
            fixture.aliases.replaceAlias(dispatch.getId(), locallyStyled, fixture.aliases.currentRevision());
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
    void conventionalPreviewImportsOneRowWithTheFactoryAliasList() throws Exception
    {
        try(Fixture fixture = new Fixture(mTemporaryFolder))
        {
            fixture.directory.conventional = List.of(new ConventionalFrequency(7, 154_100_000L, null, "",
                "County dispatch", "Dispatch", "", "", "", "", "FMN", 0, "", List.of(), 70));
            RadioReferenceImportService.ChannelPreview preview = fixture.importer.previewConventional(
                new RadioReferenceImportService.ConventionalImportRequest(70, 7, null, "County", "Public Safety",
                    null));
            assertEquals(RadioReferenceImportService.ChannelAction.CREATE, preview.action());
            assertEquals("nbfm", preview.channel().protocolId());
            assertEquals("Default Analog", fixture.channels.options().aliasLists().stream()
                .filter(list -> list.id() == preview.channel().aliasListId()).findFirst().orElseThrow().name());
            assertEquals(List.of(154_100_000L), preview.channel().source().frequenciesHz());
            fixture.importer.applyChannel(preview.previewId());
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
                new RadioReferenceImportService.ConventionalImportRequest(70, 7, selectedListId, "County",
                    "Public Safety", null));

            assertEquals(selectedListId, preview.channel().aliasListId());

            long incompatibleListId = aliasList(fixture, AliasListFamily.P25);
            IllegalArgumentException incompatible = assertThrows(IllegalArgumentException.class,
                () -> fixture.importer.previewConventional(
                    new RadioReferenceImportService.ConventionalImportRequest(70, 7, incompatibleListId,
                        "County", "Public Safety", null)));
            assertTrue(incompatible.getMessage().contains("not compatible"));
        }
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
            manager = new ConfigurationManager(new TestUserPreferences(dataRoot), null, new AliasModel(), null, null);
            manager.init();
            aliases = AliasAdministrationServiceTestSupport.create(manager);
            channels = ChannelAdministrationServiceTestSupport.create(manager);
            importer = new RadioReferenceImportService(directory, channels, aliases,
                Clock.fixed(Instant.parse("2026-09-22T12:00:00Z"), ZoneOffset.UTC), Duration.ofMinutes(5));
        }

        @Override
        public void close()
        {
            try
            {
                manager.flushConfiguration();
            }
            finally
            {
                MyEventBus.getGlobalEventBus().unregister(manager.getChannelProcessingManager());
            }
        }
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
        private final List<RemoteTalkgroupCategory> categories =
            List.of(new RemoteTalkgroupCategory(50, 10, "Public Safety"));
        private List<ConventionalFrequency> conventional = List.of();
        private int catalogReads;
        private int categoryReads;

        @Override public TrunkedSystemDetails trunkedSystemDetails(int systemId) { return system; }
        @Override public BoundedPage<TrunkedSiteDetails> trunkedSites(int systemId, int offset, int limit)
            { return new BoundedPage<>(List.of(site), 0, null, 1); }
        @Override public BoundedPage<RemoteTalkgroup> talkgroups(int systemId, Integer categoryId, String search,
            int offset, int limit) { return new BoundedPage<>(talkgroups, 0, null, talkgroups.size()); }
        @Override public List<RemoteTalkgroup> allTalkgroups(int systemId)
            { catalogReads++; return List.copyOf(talkgroups); }
        @Override public BoundedPage<RemoteTalkgroupCategory> talkgroupCategories(int systemId, int offset, int limit)
            { return new BoundedPage<>(categories, 0, null, categories.size()); }
        @Override public List<RemoteTalkgroupCategory> allTalkgroupCategories(int systemId)
            { categoryReads++; return categories; }
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
