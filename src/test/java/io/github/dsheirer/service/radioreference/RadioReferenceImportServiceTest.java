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
            RadioReferenceImportService.TalkgroupPage catalog = fixture.importer.talkgroupCatalog(10, p25List);
            assertEquals(2, catalog.totalItems());
            assertEquals(2, catalog.items().size());
            assertEquals(List.of(RadioReferenceImportService.TalkgroupStatus.NOT_PRESENT,
                    RadioReferenceImportService.TalkgroupStatus.NOT_PRESENT),
                page.items().stream().map(RadioReferenceImportService.TalkgroupRow::status).toList());
            RadioReferenceImportService.TalkgroupImportPreview talkgroups = fixture.importer.previewTalkgroups(
                new RadioReferenceImportService.TalkgroupImportRequest(10, p25List, true, List.of()));
            assertTrue(talkgroups.all());
            assertEquals(2L, talkgroups.counts().get("added"));
            fixture.importer.applyTalkgroups(talkgroups.previewId());
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
            RadioReferenceImportService.TalkgroupImportPreview update = fixture.importer.previewTalkgroups(
                new RadioReferenceImportService.TalkgroupImportRequest(10, p25List, false, List.of(1)));
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
                new RadioReferenceImportService.ConventionalImportRequest(70, 7, "County", "Public Safety", null));
            assertEquals(RadioReferenceImportService.ChannelAction.CREATE, preview.action());
            assertEquals("nbfm", preview.channel().protocolId());
            assertEquals("Default Analog", fixture.channels.options().aliasLists().stream()
                .filter(list -> list.id() == preview.channel().aliasListId()).findFirst().orElseThrow().name());
            assertEquals(List.of(154_100_000L), preview.channel().source().frequenciesHz());
            fixture.importer.applyChannel(preview.previewId());
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

        @Override public TrunkedSystemDetails trunkedSystemDetails(int systemId) { return system; }
        @Override public BoundedPage<TrunkedSiteDetails> trunkedSites(int systemId, int offset, int limit)
            { return new BoundedPage<>(List.of(site), 0, null, 1); }
        @Override public BoundedPage<RemoteTalkgroup> talkgroups(int systemId, Integer categoryId, String search,
            int offset, int limit) { return new BoundedPage<>(talkgroups, 0, null, talkgroups.size()); }
        @Override public List<RemoteTalkgroup> talkgroupsById(int systemId, Collection<Integer> ids)
            { return ids.stream().map(id -> talkgroups.stream().filter(row -> row.id() == id).findFirst().orElseThrow()).toList(); }
        @Override public List<RemoteTalkgroup> allTalkgroups(int systemId) { return List.copyOf(talkgroups); }
        @Override public BoundedPage<RemoteTalkgroupCategory> talkgroupCategories(int systemId, int offset, int limit)
            { return new BoundedPage<>(categories, 0, null, categories.size()); }
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
