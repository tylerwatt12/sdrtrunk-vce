/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.service.radioreference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasAdministrationService;
import io.github.dsheirer.alias.AliasAdministrationServiceTestSupport;
import io.github.dsheirer.alias.AliasFactory;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.nbfm.DecodeConfigNBFM;
import io.github.dsheirer.module.decode.nxdn.DecodeConfigNXDN;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.decode.p25.phase1.Modulation;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.directory.DirectoryPreference;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.Account;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.ConventionalFrequency;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.Country;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.CountryDirectory;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.CountyDirectory;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.FrequencyResult;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.RemoteTalkgroup;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.RemoteTalkgroupCategory;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.StateDirectory;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.TrunkedSiteChannel;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.TrunkedSiteDetails;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.TrunkedSystemDetails;
import io.github.dsheirer.source.config.SourceConfigTuner;
import io.github.dsheirer.source.config.SourceConfigTunerMultipleFrequency;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RadioReferenceImportServiceTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void previewsAndImportsAnFdmaP25SiteUsingTheExplicitModulationChoice() throws Exception
    {
        FakeGateway gateway = gateway();
        gateway.system = new TrunkedSystemDetails(2001, "State P25", "Capital", "Project 25", "Phase II",
            "APCO-25 Common Air Interface", "BEE00", "49F");
        gateway.sites = List.of(new TrunkedSiteDetails(3001, 2001, 12, "Franklin Simulcast", 100, 1, 1,
            "491", 0, "LSM", false, List.of(
                new TrunkedSiteChannel(853_162_500L, 1, "1", "c", "", true, false),
                new TrunkedSiteChannel(852_900_000L, 2, "2", "a", "", false, true),
                new TrunkedSiteChannel(851_125_000L, 3, "3", "v", "", false, false))));

        try(Fixture fixture = fixture("p25-site", gateway))
        {
            RadioReferenceImportService.SystemPreview system = fixture.importer().systemPreview(2001);
            assertTrue(system.supported());
            assertEquals(DecoderType.P25_PHASE2, system.recommendedDecoder());
            assertEquals(Protocol.APCO25, system.protocol());

            RadioReferenceImportService.SitePreview site = fixture.importer().sitePreview(2001, 3001);
            assertEquals(DecoderType.P25_PHASE1, site.recommendedDecoder());
            assertTrue(site.p25ModulationRequired());
            assertEquals(List.of(853_162_500L, 852_900_000L), site.defaultControlFrequencies());

            RadioReferenceImportService.SiteChannelImport missingModulation =
                new RadioReferenceImportService.SiteChannelImport(2001, 3001, null, null, null, null,
                    null, null, null);
            assertThrows(IllegalArgumentException.class,
                () -> fixture.importer().importSiteChannel(missingModulation));
            assertTrue(fixture.manager().getChannelModel().getChannels().isEmpty());

            RadioReferenceImportService.SiteChannelImport nonCanonicalModulation =
                new RadioReferenceImportService.SiteChannelImport(2001, 3001, null, null, "LSM", null,
                    null, null, null);
            assertThrows(IllegalArgumentException.class,
                () -> fixture.importer().importSiteChannel(nonCanonicalModulation));

            RadioReferenceImportService.SiteChannelImport voiceOnlyFrequency =
                new RadioReferenceImportService.SiteChannelImport(2001, 3001, null, null, "C4FM",
                    List.of(851_125_000L), null, null, null);
            assertThrows(IllegalArgumentException.class,
                () -> fixture.importer().importSiteChannel(voiceOnlyFrequency));
            assertTrue(fixture.manager().getChannelModel().getChannels().isEmpty());

            RadioReferenceImportService.ChannelImportResult result = fixture.importer().importSiteChannel(
                new RadioReferenceImportService.SiteChannelImport(2001, 3001, null, null, "C4FM", null,
                    null, null, null));

            assertEquals(1, result.created());
            Channel channel = fixture.manager().getChannelModel().getChannels().getFirst();
            assertEquals("State P25", channel.getSystem());
            assertEquals("Franklin Simulcast", channel.getSite());
            assertEquals("Franklin Simulcast", channel.getName());
            DecodeConfigP25Phase1 decode = assertInstanceOf(DecodeConfigP25Phase1.class,
                channel.getDecodeConfiguration());
            assertEquals(Modulation.C4FM, decode.getModulation(),
                "RadioReference's LSM label must not override the explicit modulation choice");
            SourceConfigTunerMultipleFrequency source = assertInstanceOf(SourceConfigTunerMultipleFrequency.class,
                channel.getSourceConfiguration());
            assertEquals(List.of(853_162_500L, 852_900_000L), source.getFrequencies());
            assertEquals(851_125_000L, source.getMinimumFrequency());
            assertEquals(853_162_500L, source.getMaximumFrequency());

            assertThrows(IllegalStateException.class, () -> fixture.importer().importSiteChannel(
                new RadioReferenceImportService.SiteChannelImport(2001, 3001, null, null, "CQPSK", null,
                    null, null, null)));
            assertEquals(1, fixture.manager().getChannelModel().getChannels().size());
        }
    }

    @Test
    void keepsHybridMotorolaTalkgroupsAvailableButRejectsTheUnsupportedControlChannel() throws Exception
    {
        FakeGateway gateway = gateway();
        gateway.system = new TrunkedSystemDetails(2001, "Hybrid Motorola", "County", "Motorola", "Type II",
            "Analog and APCO-25 Common Air Interface", "", "");
        gateway.sites = List.of(new TrunkedSiteDetails(3001, 2001, 1, "Hybrid Site", 100, 1, 1,
            "123", 0, "", false, List.of(
                new TrunkedSiteChannel(851_125_000L, 1, "1", "c", "", true, false))));

        try(Fixture fixture = fixture("hybrid-motorola", gateway))
        {
            RadioReferenceImportService.SystemPreview system = fixture.importer().systemPreview(2001);
            assertTrue(system.supported(), "P25 talkgroup aliases remain importable");
            assertEquals(Protocol.APCO25, system.protocol());

            RadioReferenceImportService.SitePreview site = fixture.importer().sitePreview(2001, 3001);
            assertEquals(false, site.supported());
            assertTrue(site.unsupportedReason().contains("Motorola Type II control channels"));

            RadioReferenceImportService.SiteChannelImport request =
                new RadioReferenceImportService.SiteChannelImport(2001, 3001, null, null, "C4FM", null,
                    null, null, null);
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> fixture.importer().importSiteChannel(request));
            assertTrue(exception.getMessage().contains("Motorola Type II control channels"));
            assertTrue(fixture.manager().getChannelModel().getChannels().isEmpty());
        }
    }

    @Test
    void conventionalImportIsAllOrNothingAndUsesAuthoritativeRemoteRows() throws Exception
    {
        FakeGateway gateway = gateway();
        gateway.conventional = List.of(
            new ConventionalFrequency(11, 155_250_000L, null, "WQAB123", "County Fire", "Fire Dispatch",
                "123.0 PL", "", "", "", "FM", 0, "RM", List.of("Fire Dispatch"), 444),
            new ConventionalFrequency(12, 460_100_000L, null, "WQAB124", "Unsupported", "Unsupported",
                "", "", "", "", "TETRA", 0, "RM", List.of(), 444));

        try(Fixture fixture = fixture("conventional", gateway))
        {
            RadioReferenceImportService.ConventionalImport mixed =
                new RadioReferenceImportService.ConventionalImport(444, List.of(11, 12), null,
                    "Franklin County", "Public Safety");
            assertThrows(IllegalArgumentException.class, () -> fixture.importer().importConventional(mixed));
            assertTrue(fixture.manager().getChannelModel().getChannels().isEmpty(),
                "an unsupported row must not leave the preceding row partially imported");

            RadioReferenceImportService.ChannelImportResult result = fixture.importer().importConventional(
                new RadioReferenceImportService.ConventionalImport(444, List.of(11), null,
                    "Franklin County", "Public Safety"));
            assertEquals(1, result.created());
            Channel channel = fixture.manager().getChannelModel().getChannels().getFirst();
            assertEquals("Franklin County", channel.getSystem());
            assertEquals("Public Safety", channel.getSite());
            assertEquals("Fire Dispatch", channel.getName());
            assertInstanceOf(DecodeConfigNBFM.class, channel.getDecodeConfiguration());
            assertEquals(155_250_000L,
                assertInstanceOf(SourceConfigTuner.class, channel.getSourceConfiguration()).getFrequency());

            gateway.conventional = List.of(new ConventionalFrequency(11, 156_000_000L, null, "WQAB123",
                "County Fire", "Updated Remote Name", "123.0 PL", "", "", "", "FMN", 0, "RM",
                List.of("Fire Dispatch"), 444));
            RadioReferenceImportService.ChannelImportResult updated = fixture.importer().importConventional(
                new RadioReferenceImportService.ConventionalImport(444, List.of(11), null,
                    "Franklin County", "Public Safety"));
            assertEquals(1, updated.created());
            Channel reloaded = fixture.manager().getChannelModel().getChannels().getLast();
            assertEquals("Updated Remote Name", reloaded.getName());
            assertEquals(156_000_000L,
                assertInstanceOf(SourceConfigTuner.class, reloaded.getSourceConfiguration()).getFrequency());
        }
    }

    @Test
    void conventionalBatchRejectsDuplicateChannelsWithinTheSameRequest() throws Exception
    {
        FakeGateway gateway = gateway();
        gateway.conventional = List.of(
            new ConventionalFrequency(11, 155_250_000L, null, "WQAB123", "County Fire", "Fire Dispatch",
                "", "", "", "", "FMN", 0, "RM", List.of(), 444),
            new ConventionalFrequency(12, 155_250_000L, null, "WQAB123", "County Fire", "Fire Dispatch Alt",
                "", "", "", "", "FMN", 0, "RM", List.of(), 444));

        try(Fixture fixture = fixture("duplicate-conventional", gateway))
        {
            RadioReferenceImportService.ConventionalImport request =
                new RadioReferenceImportService.ConventionalImport(444, List.of(11, 12), null,
                    "Franklin County", "Public Safety");
            assertThrows(IllegalStateException.class, () -> fixture.importer().importConventional(request));
            assertTrue(fixture.manager().getChannelModel().getChannels().isEmpty());
        }
    }

    @Test
    void conventionalImportTreatsNullAndBlankIdentityLabelsAsDuplicates() throws Exception
    {
        FakeGateway gateway = gateway();
        gateway.conventional = List.of(new ConventionalFrequency(11, 155_250_000L, null, "WQAB123",
            "County Fire", "Imported Name", "", "", "", "", "FMN", 0, "RM", List.of(), 444));

        try(Fixture fixture = fixture("blank-identity-duplicate", gateway))
        {
            Channel existing = new Channel();
            SourceConfigTuner source = new SourceConfigTuner();
            source.setFrequency(155_250_000L);
            existing.setSourceConfiguration(source);
            existing.setDecodeConfiguration(new DecodeConfigNBFM());
            fixture.manager().getChannelModel().addChannel(existing);

            RadioReferenceImportService.ConventionalImport request =
                new RadioReferenceImportService.ConventionalImport(444, List.of(11), null, "   ", "");
            assertThrows(IllegalStateException.class, () -> fixture.importer().importConventional(request));
            assertEquals(1, fixture.manager().getChannelModel().getChannels().size(),
                "blank imported labels must match the existing null system/site identity");
        }
    }

    @Test
    void queuedDesktopImportCannotMutateAfterItsTimeout() throws Exception
    {
        FakeGateway gateway = gateway();
        gateway.conventional = List.of(new ConventionalFrequency(11, 155_250_000L, null, "WQAB123",
            "County Fire", "Fire Dispatch", "", "", "", "", "FMN", 0, "RM", List.of(), 444));

        try(Fixture fixture = fixture("desktop-timeout", gateway))
        {
            AtomicReference<Runnable> queuedTask = new AtomicReference<>();
            RadioReferenceImportService.DesktopConfigurationDispatcher dispatcher =
                new RadioReferenceImportService.DesktopConfigurationDispatcher()
                {
                    @Override
                    public boolean isDispatchThread()
                    {
                        return false;
                    }

                    @Override
                    public void dispatch(Runnable task)
                    {
                        queuedTask.set(task);
                    }
                };
            RadioReferenceImportService importer = new RadioReferenceImportService(fixture.directory(),
                fixture.manager(), fixture.aliasAdministrationService(), dispatcher, Duration.ofMillis(20));

            IllegalStateException timeout = assertThrows(IllegalStateException.class,
                () -> importer.importConventional(new RadioReferenceImportService.ConventionalImport(444,
                    List.of(11), null, "County", "Public Safety")));
            assertEquals("Configuration is busy; try again", timeout.getMessage());
            assertTrue(queuedTask.get() != null);
            assertTrue(fixture.manager().getChannelModel().getChannels().isEmpty());

            queuedTask.get().run();
            assertTrue(fixture.manager().getChannelModel().getChannels().isEmpty(),
                "a runnable canceled while queued must not mutate when JavaFX eventually executes it");
        }
    }

    @Test
    void dmrAndNxdnMapsPreferChannelIdAndFallBackToLogicalChannelNumber() throws Exception
    {
        List<TrunkedSiteChannel> channels = List.of(
            new TrunkedSiteChannel(451_012_500L, 10, "101", "c", "", true, false),
            new TrunkedSiteChannel(451_025_000L, 20, "invalid", "v", "", false, false),
            new TrunkedSiteChannel(451_037_500L, 30, null, "v", "", false, false),
            new TrunkedSiteChannel(451_050_000L, 40, "", "v", "", false, false));

        FakeGateway dmrGateway = gateway();
        dmrGateway.system = new TrunkedSystemDetails(2001, "County DMR", "County", "DMR", "Connect Plus",
            "DMR", "", "");
        dmrGateway.sites = List.of(new TrunkedSiteDetails(3001, 2001, 1, "DMR Site", 100, 1, 1,
            "", 0, "", false, channels));
        try(Fixture fixture = fixture("dmr-channel-map", dmrGateway))
        {
            fixture.importer().importSiteChannel(new RadioReferenceImportService.SiteChannelImport(2001, 3001,
                null, null, null, null, null, null, null));
            DecodeConfigDMR configuration = assertInstanceOf(DecodeConfigDMR.class,
                fixture.manager().getChannelModel().getChannels().getFirst().getDecodeConfiguration());
            assertEquals(List.of(101, 20, 30, 40), configuration.getTimeslotMap().stream()
                .map(mapping -> mapping.getNumber()).toList());
        }

        FakeGateway nxdnGateway = gateway();
        nxdnGateway.system = new TrunkedSystemDetails(2002, "County NXDN", "County", "NXDN", "NEXEDGE 4800",
            "NXDN", "", "");
        nxdnGateway.sites = List.of(new TrunkedSiteDetails(3002, 2002, 1, "NXDN Site", 100, 1, 1,
            "", 0, "", false, channels));
        try(Fixture fixture = fixture("nxdn-channel-map", nxdnGateway))
        {
            fixture.importer().importSiteChannel(new RadioReferenceImportService.SiteChannelImport(2002, 3002,
                null, null, null, null, null, null, null));
            DecodeConfigNXDN configuration = assertInstanceOf(DecodeConfigNXDN.class,
                fixture.manager().getChannelModel().getChannels().getFirst().getDecodeConfiguration());
            assertEquals(List.of(101, 20, 30, 40), configuration.getChannelMap().stream()
                .map(mapping -> mapping.getChannel()).toList());
        }
    }

    @Test
    void importsAConventionalSelectionFromBeyondTheFirstResultPage() throws Exception
    {
        FakeGateway gateway = gateway();
        gateway.conventional = IntStream.rangeClosed(1, 501).mapToObj(index -> new ConventionalFrequency(index,
            150_000_000L + index, null, "", "Channel " + index, "Channel " + index, "", "", "", "",
            "FMN", 0, "RM", List.of(), 444)).toList();

        try(Fixture fixture = fixture("paged-conventional", gateway))
        {
            RadioReferenceImportService.ChannelImportResult result = fixture.importer().importConventional(
                new RadioReferenceImportService.ConventionalImport(444, List.of(501), null,
                    "Franklin County", "Public Safety"));
            assertEquals(1, result.created());
            assertEquals(150_000_501L, fixture.manager().getChannelModel().getChannels().getFirst()
                .getFrequencyList().getFirst());
        }
    }

    @Test
    void previewsAddsAndUpdatesTalkgroupsWhilePreservingLocalBehavior() throws Exception
    {
        FakeGateway gateway = gateway();
        gateway.system = new TrunkedSystemDetails(2001, "State P25", "Capital", "Project 25", "Phase I",
            "APCO-25 Common Air Interface", "BEE00", "49F");
        gateway.talkgroupCategories = List.of(new RemoteTalkgroupCategory(9, 2001, "Dispatch"));
        gateway.talkgroups = List.of(new RemoteTalkgroup(101, 56_001, "County Fire", "Fire dispatch", "D",
            0, 9, List.of("Fire Dispatch")));

        try(Fixture fixture = fixture("talkgroups", gateway))
        {
            AliasAdministrationService administration = fixture.aliasAdministrationService();
            AliasAdministrationService.MutationResult list = administration.createAliasList("County P25",
                AliasListFamily.P25, administration.currentRevision());
            RadioReferenceImportService.TalkgroupPreviewPage first = fixture.importer().talkgroupPreview(2001,
                list.aliasListId(), null, "", 0, 20);
            assertEquals(RadioReferenceImportService.ImportStatus.NOT_PRESENT,
                first.items().getFirst().status());

            RadioReferenceImportService.TalkgroupImportResult added = fixture.importer().importTalkgroups(
                new RadioReferenceImportService.TalkgroupImport(2001, list.aliasListId(), first.revision(),
                    List.of(101), false));
            assertEquals(1, added.added());
            assertEquals(0, added.updated());
            assertEquals(RadioReferenceImportService.ImportStatus.IDENTICAL,
                fixture.importer().talkgroupPreview(2001, list.aliasListId(), null, "", 0, 20)
                    .items().getFirst().status());

            Alias original = fixture.manager().getAliasModel().getAliases().getFirst();
            Alias localEdit = AliasFactory.copyOf(original);
            localEdit.setColor(0x123456);
            localEdit.setRecordable(true);
            AliasAdministrationService.MutationResult locallyUpdated = administration.replaceAlias(original.getId(),
                localEdit, added.revision());

            gateway.talkgroups = List.of(new RemoteTalkgroup(101, 56_001, "County Fire Updated",
                "Updated fire dispatch", "D", 0, 9, List.of("Fire Dispatch")));
            RadioReferenceImportService.TalkgroupPreviewPage changed = fixture.importer().talkgroupPreview(2001,
                list.aliasListId(), null, "", 0, 20);
            assertEquals(locallyUpdated.revision(), changed.revision());
            assertEquals(RadioReferenceImportService.ImportStatus.DIFFERENT,
                changed.items().getFirst().status());
            assertEquals(List.of("name", "description"), changed.items().getFirst().changes().stream()
                .map(RadioReferenceImportService.FieldChange::field).toList());

            RadioReferenceImportService.ConfirmationRequiredException confirmation = assertThrows(
                RadioReferenceImportService.ConfirmationRequiredException.class,
                () -> fixture.importer().importTalkgroups(
                    new RadioReferenceImportService.TalkgroupImport(2001, list.aliasListId(), changed.revision(),
                        List.of(101), false)));
            assertTrue(confirmation.getMessage().contains("Confirm"));
            assertEquals("County Fire", fixture.manager().getAliasModel().getAliases().getFirst().getName(),
                "an unconfirmed RadioReference update must not mutate the Alias");
            assertEquals(locallyUpdated.revision(), administration.currentRevision());

            RadioReferenceImportService.TalkgroupImportResult imported = fixture.importer().importTalkgroups(
                new RadioReferenceImportService.TalkgroupImport(2001, list.aliasListId(), changed.revision(),
                    List.of(101), true));
            assertEquals(0, imported.added());
            assertEquals(1, imported.updated());
            Alias updated = fixture.manager().getAliasModel().getAliases().getFirst();
            assertEquals("County Fire Updated", updated.getName());
            assertEquals("Updated fire dispatch", updated.getDescription());
            assertEquals("Dispatch", updated.getGroup());
            assertEquals(0x123456, updated.getColor());
            assertTrue(updated.isRecordable());

            gateway.talkgroupCategories = List.of();
            RadioReferenceImportService.TalkgroupPreviewPage missingCategory =
                fixture.importer().talkgroupPreview(2001, list.aliasListId(), null, "", 0, 20);
            assertEquals(RadioReferenceImportService.ImportStatus.IDENTICAL,
                missingCategory.items().getFirst().status(),
                "missing category enrichment must not make a local group look different");

            gateway.talkgroups = List.of(new RemoteTalkgroup(101, 56_001, "County Fire Final",
                "Final fire dispatch", "D", 0, 9, List.of("Fire Dispatch")));
            RadioReferenceImportService.TalkgroupPreviewPage changedWithoutCategory =
                fixture.importer().talkgroupPreview(2001, list.aliasListId(), null, "", 0, 20);
            assertEquals(List.of("name", "description"), changedWithoutCategory.items().getFirst().changes().stream()
                .map(RadioReferenceImportService.FieldChange::field).toList());

            fixture.importer().importTalkgroups(new RadioReferenceImportService.TalkgroupImport(2001,
                list.aliasListId(), changedWithoutCategory.revision(), List.of(101), true));
            Alias finalAlias = fixture.manager().getAliasModel().getAliases().getFirst();
            assertEquals("County Fire Final", finalAlias.getName());
            assertEquals("Final fire dispatch", finalAlias.getDescription());
            assertEquals("Dispatch", finalAlias.getGroup(),
                "a missing remote category must not clear the administrator's local group");

            gateway.talkgroups = List.of(new RemoteTalkgroup(101, 56_001, "", "Final fire dispatch", "D",
                0, 9, List.of("Fire Dispatch")));
            RadioReferenceImportService.TalkgroupPreviewPage blankAlpha =
                fixture.importer().talkgroupPreview(2001, list.aliasListId(), null, "", 0, 20);
            fixture.importer().importTalkgroups(new RadioReferenceImportService.TalkgroupImport(2001,
                list.aliasListId(), blankAlpha.revision(), List.of(101), true));
            assertEquals("56001", fixture.manager().getAliasModel().getAliases().getFirst().getName());
            assertEquals(RadioReferenceImportService.ImportStatus.IDENTICAL,
                fixture.importer().talkgroupPreview(2001, list.aliasListId(), null, "", 0, 20)
                    .items().getFirst().status(),
                "the numeric fallback name must not create a permanent update loop");
        }
    }

    @Test
    void rejectsA501stTalkgroupCategoryEvenWhenItIsOnTheFinalPage() throws Exception
    {
        FakeGateway gateway = gateway();
        gateway.system = new TrunkedSystemDetails(2001, "State P25", "Capital", "Project 25", "Phase I",
            "APCO-25 Common Air Interface", "BEE00", "49F");
        gateway.talkgroupCategories = IntStream.rangeClosed(1,
                RadioReferenceImportService.MAXIMUM_IMPORT_ITEMS + 1)
            .mapToObj(index -> new RemoteTalkgroupCategory(index, 2001, "Category " + index)).toList();

        try(Fixture fixture = fixture("bounded-talkgroup-categories", gateway))
        {
            AliasAdministrationService administration = fixture.aliasAdministrationService();
            AliasAdministrationService.MutationResult list = administration.createAliasList("County P25",
                AliasListFamily.P25, administration.currentRevision());

            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> fixture.importer().talkgroupPreview(2001, list.aliasListId(), null, "", 0, 20));
            assertEquals("RadioReference category result is too large", exception.getMessage());
        }
    }

    @Test
    void treatsOnlyTransientCategoryFailuresAsMissingEnrichmentAndPreservesLocalGroup() throws Exception
    {
        FakeGateway gateway = gateway();
        gateway.system = new TrunkedSystemDetails(2001, "State P25", "Capital", "Project 25", "Phase I",
            "APCO-25 Common Air Interface", "BEE00", "49F");
        gateway.talkgroupCategories = List.of(new RemoteTalkgroupCategory(9, 2001, "Dispatch"));
        gateway.talkgroups = List.of(new RemoteTalkgroup(101, 56_001, "County Fire", "Fire dispatch", "D",
            0, 9, List.of("Fire Dispatch")));

        try(Fixture fixture = fixture("transient-talkgroup-categories", gateway))
        {
            AliasAdministrationService administration = fixture.aliasAdministrationService();
            AliasAdministrationService.MutationResult list = administration.createAliasList("County P25",
                AliasListFamily.P25, administration.currentRevision());
            RadioReferenceImportService.TalkgroupPreviewPage initial = fixture.importer().talkgroupPreview(2001,
                list.aliasListId(), null, "", 0, 20);
            RadioReferenceImportService.TalkgroupImportResult added = fixture.importer().importTalkgroups(
                new RadioReferenceImportService.TalkgroupImport(2001, list.aliasListId(), initial.revision(),
                    List.of(101), false));
            assertEquals("Dispatch", fixture.manager().getAliasModel().getAliases().getFirst().getGroup());

            gateway.talkgroups = List.of(new RemoteTalkgroup(101, 56_001, "County Fire Updated",
                "Fire dispatch", "D", 0, 9, List.of("Fire Dispatch")));
            for(RadioReferenceGatewayException.Kind kind: List.of(
                RadioReferenceGatewayException.Kind.TIMEOUT,
                RadioReferenceGatewayException.Kind.UNAVAILABLE))
            {
                gateway.talkgroupCategoryFailure = kind;
                RadioReferenceImportService.TalkgroupPreviewPage preview = fixture.importer().talkgroupPreview(
                    2001, list.aliasListId(), null, "", 0, 20);
                assertEquals(List.of("name"), preview.items().getFirst().changes().stream()
                    .map(RadioReferenceImportService.FieldChange::field).toList());
            }

            gateway.talkgroupCategoryFailure = RadioReferenceGatewayException.Kind.TIMEOUT;
            RadioReferenceImportService.TalkgroupImportResult updated = fixture.importer().importTalkgroups(
                new RadioReferenceImportService.TalkgroupImport(2001, list.aliasListId(), added.revision(),
                    List.of(101), true));
            assertEquals(1, updated.updated());
            Alias alias = fixture.manager().getAliasModel().getAliases().getFirst();
            assertEquals("County Fire Updated", alias.getName());
            assertEquals("Dispatch", alias.getGroup(),
                "transiently unavailable category enrichment must not clear the local group");

            gateway.talkgroupCategoryFailure = RadioReferenceGatewayException.Kind.INVALID_CREDENTIALS;
            RadioReferenceDirectoryException authentication = assertThrows(
                RadioReferenceDirectoryException.class, () -> fixture.importer().talkgroupPreview(
                    2001, list.aliasListId(), null, "", 0, 20));
            assertEquals(RadioReferenceDirectoryException.Code.INVALID_CREDENTIALS, authentication.code());
        }
    }

    private Fixture fixture(String name, FakeGateway gateway) throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve(name);
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        Files.createDirectories(database.getParent());
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        ConfigurationManager manager = new ConfigurationManager(new TestUserPreferences(dataRoot), null,
            new AliasModel(), null, null);
        manager.init();
        RadioReferenceDirectoryService directory =
            new RadioReferenceDirectoryService((userName, password) -> gateway);
        directory.login("test-user", "secret".toCharArray());
        AliasAdministrationService aliasAdministrationService =
            AliasAdministrationServiceTestSupport.create(manager);
        return new Fixture(manager, directory, new RadioReferenceImportService(directory, manager,
            aliasAdministrationService, false), aliasAdministrationService);
    }

    private static FakeGateway gateway()
    {
        return new FakeGateway();
    }

    private record Fixture(ConfigurationManager manager, RadioReferenceDirectoryService directory,
                           RadioReferenceImportService importer,
                           AliasAdministrationService aliasAdministrationService) implements AutoCloseable
    {
        @Override
        public void close()
        {
            directory.close();
            MyEventBus.getGlobalEventBus().unregister(manager.getChannelProcessingManager());
        }
    }

    private static final class TestUserPreferences extends UserPreferences
    {
        private final DirectoryPreference mDirectoryPreference;

        private TestUserPreferences(Path dataRoot)
        {
            mDirectoryPreference = new DirectoryPreference(_ -> { })
            {
                @Override
                public Path getDirectoryApplicationRoot()
                {
                    return dataRoot;
                }
            };
        }

        @Override
        public DirectoryPreference getDirectoryPreference()
        {
            return mDirectoryPreference;
        }
    }

    private static final class FakeGateway implements RadioReferenceGateway
    {
        private TrunkedSystemDetails system;
        private List<TrunkedSiteDetails> sites = List.of();
        private List<RemoteTalkgroup> talkgroups = List.of();
        private List<RemoteTalkgroupCategory> talkgroupCategories = List.of();
        private RadioReferenceGatewayException.Kind talkgroupCategoryFailure;
        private List<ConventionalFrequency> conventional = List.of();

        @Override
        public Account account()
        {
            return new Account("test-user", "Never - Test Account");
        }

        @Override
        public List<Country> countries()
        {
            return List.of();
        }

        @Override
        public CountryDirectory country(int countryId)
        {
            return null;
        }

        @Override
        public StateDirectory state(int stateId)
        {
            return null;
        }

        @Override
        public CountyDirectory county(int countyId)
        {
            return null;
        }

        @Override
        public List<FrequencyResult> searchStateFrequencies(int stateId, double frequencyMHz)
        {
            return List.of();
        }

        @Override
        public TrunkedSystemDetails trunkedSystemDetails(int systemId)
        {
            return system;
        }

        @Override
        public List<TrunkedSiteDetails> trunkedSiteDetails(int systemId)
        {
            return sites;
        }

        @Override
        public List<RemoteTalkgroup> talkgroups(int systemId)
        {
            return talkgroups;
        }

        @Override
        public List<RemoteTalkgroupCategory> talkgroupCategories(int systemId)
            throws RadioReferenceGatewayException
        {
            if(talkgroupCategoryFailure != null)
            {
                throw new RadioReferenceGatewayException(talkgroupCategoryFailure);
            }
            return talkgroupCategories;
        }

        @Override
        public List<ConventionalFrequency> subcategoryFrequencies(int subCategoryId)
        {
            return conventional;
        }

        @Override
        public void close()
        {
        }
    }
}
