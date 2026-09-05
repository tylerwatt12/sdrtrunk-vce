package io.github.dsheirer.alias;

import io.github.dsheirer.alias.id.AliasID;
import io.github.dsheirer.alias.id.broadcast.BroadcastChannel;
import io.github.dsheirer.alias.id.dcs.Dcs;
import io.github.dsheirer.alias.id.esn.Esn;
import io.github.dsheirer.alias.id.radio.Radio;
import io.github.dsheirer.alias.id.radio.RadioRange;
import io.github.dsheirer.alias.id.status.UnitStatusID;
import io.github.dsheirer.alias.id.status.UserStatusID;
import io.github.dsheirer.alias.id.talkgroup.StreamAsTalkgroup;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.alias.id.talkgroup.TalkgroupRange;
import io.github.dsheirer.alias.id.tone.TonesID;
import io.github.dsheirer.audio.broadcast.BroadcastFormat;
import io.github.dsheirer.audio.broadcast.broadcastify.BroadcastifyCallConfiguration;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.alias.AliasDatabaseStore;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.identifier.tone.AmbeTone;
import io.github.dsheirer.identifier.tone.Tone;
import io.github.dsheirer.identifier.tone.ToneSequence;
import io.github.dsheirer.module.decode.dcs.DCSCode;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.directory.DirectoryPreference;
import io.github.dsheirer.protocol.Protocol;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class AliasImportServiceTest
{
    @TempDir Path root;

    @Test void roundTripsEveryMatcherAndConfigurationIncludingEscapedTextAndCollections()
    {
        Dcs dcs = new Dcs(); dcs.setDCSCode(Arrays.stream(DCSCode.values()).filter(code -> code != DCSCode.UNKNOWN).findFirst().orElseThrow());
        Esn esn = new Esn(); esn.setEsn("1234*");
        UserStatusID user = new UserStatusID(); user.setStatus(12);
        UnitStatusID unit = new UnitStatusID(); unit.setStatus(34);
        var tones = new TonesID(new ToneSequence(List.of(new Tone(AmbeTone.ALL_VALID_TONES.iterator().next(), 5))));
        List<AliasID> matchers = List.of(new Talkgroup(Protocol.APCO25, 123), new Talkgroup(Protocol.APCO25_PHASE2, 124),
            new TalkgroupRange(Protocol.DMR, 50, 70), new Radio(Protocol.NXDN, 4321),
            new RadioRange(Protocol.APCO25, 100, 200), new Talkgroup(Protocol.FLEETSYNC, 123),
            new Talkgroup(Protocol.MDC1200, 123), new Talkgroup(Protocol.AM, 1),
            new Talkgroup(Protocol.NBFM, 1), user, unit, dcs, esn, tones);
        for(AliasID matcher: matchers)
        {
            Alias alias = new Alias("'=formula,\"quoted\"\nUnicode é");
            alias.setDescription("=SUM(A1)\nSecond line"); alias.setGroup("'Group");
            alias.setColor(-123456); alias.setIconName("Icon"); alias.setRecordable(true);
            alias.setStreamTalkgroupAlias(new StreamAsTalkgroup(321)); alias.setMatchIdentifier(matcher);
            List<String> scans = List.of("Dispatch; Fire", "Quotes \" and , comma");
            List<String> streams = List.of("Provider 1", "Provider 2");
            var fields = AliasTransferCsv.fields(alias, scans, streams);
            String csv = AliasTransferCsv.write(List.of(fields));
            var read = AliasTransferCsv.read(csv, AliasTransferCsv.Format.VCE, new AliasListDefinition("Test", AliasListFamily.P25)).getFirst();
            assertEquals(fields, AliasTransferCsv.fields(read.alias(), read.scanLists(), read.streams()));
            assertEquals(AliasTransferCsv.identity(alias), AliasTransferCsv.identity(read.alias()));
        }
    }

    @Test void strictHeadersAndMalformedInputsAreRejected()
    {
        AliasListDefinition list = new AliasListDefinition("County", AliasListFamily.P25);
        String csv = String.join(",", AliasTransferCsv.RR_HEADERS) + "\r\n123,7b,Dispatch,D,Fire dispatch,Fire Dispatch,Fire\r\n";
        assertEquals(123, ((Talkgroup)AliasTransferCsv.read(csv, AliasTransferCsv.Format.RADIOREFERENCE, list).getFirst().alias().getMatchIdentifier()).getValue());
        assertThrows(IllegalArgumentException.class, () -> AliasTransferCsv.read(csv.replace("Alpha Tag", "alpha tag"), AliasTransferCsv.Format.RADIOREFERENCE, list));
        assertThrows(IllegalArgumentException.class, () -> AliasTransferCsv.read(String.join(",", AliasTransferCsv.HEADERS), AliasTransferCsv.Format.VCE, list));
        assertThrows(IllegalArgumentException.class, () -> AliasTransferCsv.read(
            String.join(",", AliasTransferCsv.RR_HEADERS) + "\r\n123,7b,\"unterminated", AliasTransferCsv.Format.RADIOREFERENCE, list));
        assertThrows(IllegalArgumentException.class, () -> AliasTransferCsv.names("[\"One\",\"One\"]"));
        assertThrows(IllegalArgumentException.class, () -> AliasTransferCsv.names("[123]"));
        assertThrows(IllegalArgumentException.class, () -> AliasTransferCsv.names("[\"One\"] trailing"));
        Alias alias = new Alias("Dispatch");
        alias.setMatchIdentifier(new Talkgroup(Protocol.APCO25, 123));
        var fields = AliasTransferCsv.fields(alias, List.of(), List.of());
        fields.put("text", "unexpected payload");
        assertThrows(IllegalArgumentException.class, () -> AliasTransferCsv.read(
            AliasTransferCsv.write(List.of(fields)), AliasTransferCsv.Format.VCE, list));
    }

    @Test void previewsAndAppliesMixedReplacementAtomicallyWithStableIdsAndMemberships() throws Exception
    {
        try(Fixture fixture = new Fixture(root))
        {
            var service = fixture.service;
            long list = fixture.list;
            long scan = service.catalog().scanLists().getFirst().getId();
            String scanName = service.catalog().scanLists().getFirst().getName();
            Alias first = alias(list, 100, "Old name"); first.setColor(17); first.setRecordable(true);
            first.setBroadcastChannels(List.of(new BroadcastChannel(fixture.stream.getConfigurationId(), fixture.stream.getName())));
            long firstId = service.createAlias(first, Set.of(scan), service.currentRevision()).aliasIds().getFirst();
            long removeId = service.createAlias(alias(list, 200, "Remove"), Set.of(), service.currentRevision()).aliasIds().getFirst();
            long otherList = service.createAliasList("Other", AliasListFamily.P25).aliasListId();
            long otherId = service.createAlias(alias(otherList, 100, "Other list")).aliasIds().getFirst();
            Alias changed = alias(list, 100, "Updated"); changed.setColor(99);
            var importer = new AliasImportService(service);
            var inputs = List.of(new AliasImportService.Input(changed, false, true, false, List.of(), List.of()),
                new AliasImportService.Input(alias(list, 300, "New"), false, true, false, List.of(scanName), List.of(fixture.stream.getName())));
            var plan = importer.preview(list, AliasImportService.Mode.REPLACE, inputs, null);
            assertEquals(Map.of("added", 1L, "updated", 1L, "unchanged", 0L, "deleted", 1L, "error", 0L), plan.preview().counts());
            assertEquals("Old name", service.getAlias(firstId).alias().getName());
            assertTrue(plan.preview().rows().stream().filter(row -> row.result().equals("updated")).findFirst().orElseThrow()
                .changes().stream().anyMatch(change -> change.field().equals("scan_lists")));
            int commits = fixture.manager.commits;
            importer.apply(plan);
            assertEquals(commits + 1, fixture.manager.commits);
            assertEquals("Updated", service.getAlias(firstId).alias().getName());
            assertEquals(Set.of(), service.getAlias(firstId).scanListIds());
            assertThrows(AliasAdministrationService.NotFoundException.class, () -> service.getAlias(removeId));
            assertEquals("Other list", service.getAlias(otherId).alias().getName());
            var newEntry = service.transferSnapshot(list).aliases().stream().filter(entry -> entry.alias().getName().equals("New")).findFirst().orElseThrow();
            assertEquals(Set.of(scan), newEntry.scanListIds());
            assertEquals(fixture.stream.getConfigurationId(), newEntry.alias().getBroadcastChannels().iterator().next().getConfigurationId());
            var repeated = importer.preview(list, AliasImportService.Mode.UPDATE_ADD, inputs, null);
            assertEquals(2L, repeated.preview().counts().get("unchanged"));
            var store = new AliasDatabaseStore(fixture.database);
            assertEquals(3, store.loadAliases(store.loadAliasListDefinitions()).size());

            String exported = importer.export(list);
            var imported = AliasTransferCsv.read(exported, AliasTransferCsv.Format.VCE, service.options(list).aliasList());
            assertEquals(2L, importer.preview(list, AliasImportService.Mode.UPDATE_ADD, imported, null).preview().counts().get("unchanged"));
        }
    }

    @Test void radioReferencePreservesLocalFieldsAndAppliesDefaultsOnlyToNewUnencryptedAliases() throws Exception
    {
        try(Fixture fixture = new Fixture(root))
        {
            var service = fixture.service;
            long scan = service.catalog().scanLists().getFirst().getId();
            String scanName = service.catalog().scanLists().getFirst().getName();
            Alias old = alias(fixture.list, 100, "Old"); old.setColor(123); old.setGroup("Local");
            old.setRecordable(true); old.setStreamTalkgroupAlias(new StreamAsTalkgroup(12));
            old.setBroadcastChannels(List.of(new BroadcastChannel(fixture.stream.getConfigurationId(), fixture.stream.getName())));
            long id = service.createAlias(old, Set.of(scan), service.currentRevision()).aliasIds().getFirst();
            Alias update = alias(fixture.list, 100, "RadioReference"); update.setDescription("Updated description");
            var inputs = List.of(new AliasImportService.Input(update, true, false, true, null, null),
                new AliasImportService.Input(alias(fixture.list, 200, "New"), true, false, false, null, null),
                new AliasImportService.Input(alias(fixture.list, 300, "Encrypted"), true, false, true, null, null));
            var importer = new AliasImportService(service);
            importer.apply(importer.preview(fixture.list, AliasImportService.Mode.UPDATE_ADD, inputs,
                new AliasImportService.Defaults(true, List.of(scanName), List.of(fixture.stream.getName()))));
            var saved = service.getAlias(id);
            assertEquals(123, saved.alias().getColor()); assertEquals("Local", saved.alias().getGroup());
            assertTrue(saved.alias().isRecordable()); assertEquals(12, saved.alias().getStreamTalkgroupAlias().getValue());
            assertEquals(Set.of(scan), saved.scanListIds());
            var entries = service.transferSnapshot(fixture.list).aliases();
            var created = entries.stream().filter(entry -> entry.alias().getName().equals("New")).findFirst().orElseThrow();
            assertTrue(created.alias().isRecordable()); assertEquals(Set.of(scan), created.scanListIds());
            var encrypted = entries.stream().filter(entry -> entry.alias().getName().equals("Encrypted")).findFirst().orElseThrow();
            assertFalse(encrypted.alias().isRecordable()); assertTrue(encrypted.scanListIds().isEmpty());
            assertTrue(encrypted.alias().getBroadcastChannels().isEmpty());
        }
    }

    @Test void blocksUnknownNamesDuplicatesStalePreviewAndRollsBackFailedSave() throws Exception
    {
        try(Fixture fixture = new Fixture(root))
        {
            var service = fixture.service;
            long id = service.createAlias(alias(fixture.list, 100, "Keep")).aliasIds().getFirst();
            var importer = new AliasImportService(service);
            var input = new AliasImportService.Input(alias(fixture.list, 200, "New"), false, true, false, List.of("Missing"), List.of());
            var invalid = importer.preview(fixture.list, AliasImportService.Mode.REPLACE, List.of(input), null);
            assertEquals(1L, invalid.preview().counts().get("error"));
            assertThrows(IllegalArgumentException.class, () -> importer.apply(invalid));
            input = new AliasImportService.Input(alias(fixture.list, 200, "New"), false, true, false, List.of(), List.of());
            var duplicated = importer.preview(fixture.list, AliasImportService.Mode.UPDATE_ADD, List.of(input, input), null);
            assertEquals(1L, duplicated.preview().counts().get("error"));
            var plan = importer.preview(fixture.list, AliasImportService.Mode.REPLACE, List.of(input), null);
            service.createAlias(alias(fixture.list, 300, "After preview"));
            assertThrows(AliasAdministrationService.StaleRevisionException.class, () -> importer.apply(plan));
            var fresh = importer.preview(fixture.list, AliasImportService.Mode.REPLACE, List.of(input), null);
            fixture.manager.fail = true;
            assertThrows(RuntimeException.class, () -> importer.apply(fresh));
            assertEquals("Keep", service.getAlias(id).alias().getName());
            assertEquals(2, service.transferSnapshot(fixture.list).aliases().size());
            var store = new AliasDatabaseStore(fixture.database);
            assertEquals(2, store.loadAliases(store.loadAliasListDefinitions()).size());
        }
    }

    private static Alias alias(long list, int id, String name)
    {
        Alias alias = new Alias(name); alias.setAliasListId(list); alias.setMatchIdentifier(new Talkgroup(Protocol.APCO25, id)); return alias;
    }

    private static class Fixture implements AutoCloseable
    {
        final Manager manager;
        final AliasAdministrationService service;
        final Path database;
        final long list;
        final BroadcastifyCallConfiguration stream;
        Fixture(Path root) throws Exception
        {
            database = SdrTrunkDatabasePath.getDatabasePath(root);
            Files.createDirectories(database.getParent()); SdrTrunkDatabaseStartup.createGlobalDatabase(database);
            manager = new Manager(new UserPreferences()
            {
                private final DirectoryPreference directory = new DirectoryPreference(ignored -> {})
                { @Override public Path getDirectoryApplicationRoot() { return root; } };
                @Override public DirectoryPreference getDirectoryPreference() { return directory; }
            });
            manager.init(); service = AliasAdministrationServiceTestSupport.create(manager);
            stream = new BroadcastifyCallConfiguration(BroadcastFormat.MP3); stream.setName("Provider; One");
            manager.getBroadcastModel().addBroadcastConfiguration(stream);
            list = service.createAliasList("County", AliasListFamily.P25).aliasListId();
        }
        public void close() { MyEventBus.getGlobalEventBus().unregister(manager.getChannelProcessingManager()); }
    }
    private static class Manager extends ConfigurationManager
    {
        int commits; boolean fail;
        Manager(UserPreferences preferences) { super(preferences, null, new AliasModel(), null, null); }
        @Override protected AliasConfigurationSnapshot commitAliasConfiguration(AliasConfigurationSnapshot snapshot,
            AliasConfigurationPublication publication)
        {
            if(fail) throw new IllegalStateException("Injected save failure");
            commits++; return super.commitAliasConfiguration(snapshot, publication);
        }
    }
}
