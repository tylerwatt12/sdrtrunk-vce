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
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.module.decode.dcs.DCSCode;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.directory.DirectoryPreference;
import io.github.dsheirer.protocol.Protocol;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.*;
import java.util.*;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
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
            alias.setStreamTalkgroupAlias(new StreamAsTalkgroup(StreamAsTalkgroup.MAXIMUM_VALUE));
            alias.setMatchIdentifier(matcher);
            List<String> scans = List.of("Dispatch; Fire", "Quotes \" and , comma");
            List<String> streams = List.of("Provider 1", "Provider 2");
            var fields = AliasTransferCsv.fields(alias, "Source List", scans, streams);
            String csv = AliasTransferCsv.write(List.of(fields));
            var read = AliasTransferCsv.read(csv, AliasTransferCsv.Format.VCE, new AliasListDefinition("Test", AliasListFamily.P25)).getFirst();
            assertEquals("Source List", read.sourceAliasList());
            assertEquals(fields, AliasTransferCsv.fields(read.alias(), read.sourceAliasList(), read.scanLists(), read.streams()));
            assertEquals(AliasTransferCsv.identity(alias), AliasTransferCsv.identity(read.alias()));
        }
    }

    @Test void protectsEverySpreadsheetFormulaPrefixAndDecodesVersionOne() throws Exception
    {
        for(String value: List.of("=Equals", "+Plus", "-Minus", "@At", "'Apostrophe", "\tTab",
            "\rCarriage", "\nLine"))
        {
            Alias alias = new Alias(value);
            alias.setDescription(value);
            alias.setGroup(value);
            alias.setIconName(value);
            Esn esn = new Esn();
            esn.setEsn(value);
            alias.setMatchIdentifier(esn);
            Map<String,String> fields = AliasTransferCsv.fields(alias, value, List.of(), List.of());
            String csv = AliasTransferCsv.write(List.of(fields));
            try(CSVParser parser = CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true).get()
                .parse(new StringReader(csv.substring(1))))
            {
                var row = parser.getRecords().getFirst();
                for(String column: List.of("alias_list", "name", "description", "group", "icon", "text"))
                    assertEquals("'" + value, row.get(column));
            }
            AliasImportService.Input imported = AliasTransferCsv.read(csv, AliasTransferCsv.Format.VCE,
                new AliasListDefinition("Destination", AliasListFamily.P25)).getFirst();
            assertEquals(fields, AliasTransferCsv.fields(imported.alias(), imported.sourceAliasList(),
                imported.scanLists(), imported.streams()));
        }

        String legacy = String.join(",", AliasTransferCsv.VERSION_1_HEADERS) + "\r\n" +
            "1,'=Legacy,'+Description,'-Group,0,'@Icon,ESN,,,,,'=ESN,,false,[],[],\r\n";
        AliasImportService.Input imported = AliasTransferCsv.read(legacy, AliasTransferCsv.Format.VCE,
            new AliasListDefinition("Destination", AliasListFamily.P25)).getFirst();
        assertEquals("=Legacy", imported.alias().getName());
        assertEquals("+Description", imported.alias().getDescription());
        assertEquals("-Group", imported.alias().getGroup());
        assertEquals("@Icon", imported.alias().getIconName());
        assertEquals("=ESN", ((Esn)imported.alias().getMatchIdentifier()).getEsn());
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
        var fields = AliasTransferCsv.fields(alias, "County", List.of(), List.of());
        fields.put("text", "unexpected payload");
        assertThrows(IllegalArgumentException.class, () -> AliasTransferCsv.read(
            AliasTransferCsv.write(List.of(fields)), AliasTransferCsv.Format.VCE, list));
        fields.put("text", "");
        fields.put("stream_as_talkgroup", Integer.toString(StreamAsTalkgroup.MAXIMUM_VALUE + 1));
        assertThrows(IllegalArgumentException.class, () -> AliasTransferCsv.read(
            AliasTransferCsv.write(List.of(fields)), AliasTransferCsv.Format.VCE, list));
    }

    @Test void parsesMoreThanTenThousandVceRowsWithoutAnAliasCountCeiling() throws Exception
    {
        StringWriter output = new StringWriter();

        try(AliasTransferCsv.StreamingWriter writer = AliasTransferCsv.streamingWriter(output))
        {
            for(int value = 1; value <= 10_001; value++)
            {
                writer.write(AliasTransferCsv.fields(alias(1, value, "Alias " + value), "County",
                    List.of(), List.of()));
            }
        }

        List<AliasImportService.Input> rows = AliasTransferCsv.read(new StringReader(output.toString()),
            AliasTransferCsv.Format.VCE, new AliasListDefinition("Destination", AliasListFamily.P25));
        assertEquals(10_001, rows.size());
        assertEquals(10_001, ((Talkgroup)rows.getLast().alias().getMatchIdentifier()).getValue());
    }

    @Test void transfersDatabaseValidLongTextAndPreservesRepeatedMatchers() throws Exception
    {
        try(Fixture fixture = new Fixture(root))
        {
            long destination = fixture.service.createAliasList("Destination", AliasListFamily.P25).aliasListId();
            Alias longText = alias(fixture.list, 700, "N".repeat(300));
            longText.setDescription("D".repeat(5_000));
            longText.setGroup("G".repeat(300));
            fixture.service.createAlias(longText);
            String csv = new AliasImportService(fixture.service).export(fixture.list);
            List<AliasImportService.Input> inputs = AliasTransferCsv.read(csv, AliasTransferCsv.Format.VCE,
                fixture.service.options(destination).aliasList());
            var importer = new AliasImportService(fixture.service);
            importer.apply(importer.preview(destination, AliasImportService.Mode.UPDATE_ADD, inputs, null));
            Alias transferred = fixture.service.transferSnapshot(destination).aliases().getFirst().alias();
            long transferredId = transferred.getId();
            assertEquals(longText.getName(), transferred.getName());
            assertEquals(longText.getDescription(), transferred.getDescription());
            assertEquals(longText.getGroup(), transferred.getGroup());
            try(var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + fixture.database);
                var statement = connection.prepareStatement("""
                    UPDATE alias_activity_summary
                    SET metrics_state='observed', logical_call_count=9
                    WHERE alias_id=?
                    """))
            {
                statement.setLong(1, transferredId);
                assertEquals(1, statement.executeUpdate());
            }

            Alias duplicate = alias(fixture.list, 700, "Duplicate");
            duplicate.setColor(55);
            duplicate.setRecordable(true);
            fixture.service.createAlias(duplicate, Set.of(), fixture.service.currentRevision());
            List<AliasImportService.Input> repeated = AliasTransferCsv.read(importer.export(fixture.list),
                AliasTransferCsv.Format.VCE, fixture.service.options(destination).aliasList());
            assertEquals(2, repeated.size());
            AliasImportService.Plan repeatedPlan = importer.preview(destination, AliasImportService.Mode.UPDATE_ADD,
                repeated, null);
            assertEquals(1L, repeatedPlan.preview().counts().get("unchanged"));
            assertEquals(1L, repeatedPlan.preview().counts().get("added"));
            assertEquals(0L, repeatedPlan.preview().counts().get("error"));
            importer.apply(repeatedPlan);
            List<AliasAdministrationService.AliasEntry> repeatedEntries =
                fixture.service.transferSnapshot(destination).aliases();
            assertEquals(2, repeatedEntries.size());
            assertEquals(transferredId, repeatedEntries.stream()
                .filter(entry -> entry.alias().getName().equals(longText.getName())).findFirst().orElseThrow()
                .alias().getId(), "The matched alias must retain its database ID and activity ownership");
            Alias addedDuplicate = repeatedEntries.stream()
                .filter(entry -> entry.alias().getName().equals("Duplicate")).findFirst().orElseThrow().alias();
            assertTrue(addedDuplicate.getColor() == 55 && addedDuplicate.isRecordable(),
                repeatedEntries.stream().map(entry -> entry.alias().getName() + ":" + entry.alias().getColor() + ":" +
                    entry.alias().isRecordable()).toList().toString());
            try(var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + fixture.database);
                var statement = connection.prepareStatement("""
                    SELECT metrics_state, logical_call_count
                    FROM alias_activity_summary
                    WHERE alias_id=?
                    """))
            {
                statement.setLong(1, transferredId);
                try(var result = statement.executeQuery())
                {
                    assertTrue(result.next());
                    assertEquals("observed", result.getString("metrics_state"));
                    assertEquals(9L, result.getLong("logical_call_count"),
                        "A matched alias keeps the activity owned by its stable ID");
                }

                statement.setLong(1, addedDuplicate.getId());
                try(var result = statement.executeQuery())
                {
                    assertTrue(result.next());
                    assertEquals("not_collected", result.getString("metrics_state"));
                    assertNull(result.getObject("logical_call_count"),
                        "A newly imported duplicate must not inherit another alias's counters");
                }
            }
        }
    }

    @Test void repeatedMatcherRoundTripKeepsTheHighestIdOccurrenceAsTheRuntimeWinner() throws Exception
    {
        try(Fixture fixture = new Fixture(root))
        {
            Alias first = alias(fixture.list, 902, "Earlier configuration");
            first.setColor(11);
            long firstId = fixture.service.createAlias(first, Set.of(), fixture.service.currentRevision())
                .aliasIds().getFirst();
            Alias winner = alias(fixture.list, 902, "Highest ID winner");
            winner.setColor(22);
            long winnerId = fixture.service.createAlias(winner, Set.of(), fixture.service.currentRevision())
                .aliasIds().getFirst();
            assertTrue(winnerId > firstId);

            long destination = fixture.service.createAliasList("Destination", AliasListFamily.P25).aliasListId();
            AliasImportService importer = new AliasImportService(fixture.service);
            List<AliasImportService.Input> rows = AliasTransferCsv.read(importer.export(fixture.list),
                AliasTransferCsv.Format.VCE, fixture.service.options(destination).aliasList());
            assertEquals(List.of("Earlier configuration", "Highest ID winner"),
                rows.stream().map(row -> row.alias().getName()).toList());

            importer.apply(importer.preview(destination, AliasImportService.Mode.UPDATE_ADD, rows, null));
            List<AliasAdministrationService.AliasEntry> imported =
                fixture.service.transferSnapshot(destination).aliases();
            assertEquals(2, imported.size());
            Alias importedWinner = imported.stream().max(Comparator.comparingLong(entry -> entry.alias().getId()))
                .orElseThrow().alias();
            assertEquals("Highest ID winner", importedWinner.getName());
            assertEquals(22, importedWinner.getColor());

            Alias runtimeWinner = fixture.manager.getAliasModel()
                .getAliasList(fixture.service.options(destination).aliasList())
                .getAliases(APCO25Talkgroup.create(902)).getFirst();
            assertEquals(importedWinner.getId(), runtimeWinner.getId());
        }
    }

    @Test void repeatedMatcherImportPrefersExactConfigurationBeforeStableOccurrenceOrder() throws Exception
    {
        try(Fixture fixture = new Fixture(root))
        {
            Alias sourceFirst = alias(fixture.list, 901, "First");
            sourceFirst.setColor(11);
            Alias sourceSecond = alias(fixture.list, 901, "Second");
            sourceSecond.setColor(22);
            fixture.service.createAlias(sourceFirst);
            fixture.service.createAlias(sourceSecond);

            long destination = fixture.service.createAliasList("Destination", AliasListFamily.P25).aliasListId();
            Alias destinationSecond = alias(destination, 901, "Second");
            destinationSecond.setColor(22);
            long secondId = fixture.service.createAlias(destinationSecond).aliasIds().getFirst();
            Alias destinationFirst = alias(destination, 901, "First");
            destinationFirst.setColor(11);
            long firstId = fixture.service.createAlias(destinationFirst).aliasIds().getFirst();

            AliasImportService importer = new AliasImportService(fixture.service);
            List<AliasImportService.Input> inputs = AliasTransferCsv.read(importer.export(fixture.list),
                AliasTransferCsv.Format.VCE, fixture.service.options(destination).aliasList());
            AliasImportService.Plan plan = importer.preview(destination, AliasImportService.Mode.UPDATE_ADD,
                inputs, null);
            assertEquals(2L, plan.preview().counts().get("unchanged"));
            assertEquals(0L, plan.preview().counts().get("updated"));
            importer.apply(plan);
            assertEquals("Second", fixture.service.getAlias(secondId).alias().getName());
            assertEquals(22, fixture.service.getAlias(secondId).alias().getColor());
            assertEquals("First", fixture.service.getAlias(firstId).alias().getName());
            assertEquals(11, fixture.service.getAlias(firstId).alias().getColor());
        }
    }

    @Test void acceptsVersionOneExportsAndRequiresOneSourceListInVersionTwo()
    {
        AliasListDefinition list = new AliasListDefinition("Destination", AliasListFamily.P25);
        String legacy = String.join(",", AliasTransferCsv.VERSION_1_HEADERS) + "\r\n" +
            "1,Dispatch,,,0,,TALKGROUP,APCO25,123,,,,,false,[],[],\r\n";
        AliasImportService.Input imported = AliasTransferCsv.read(legacy, AliasTransferCsv.Format.VCE, list).getFirst();
        assertNull(imported.sourceAliasList());
        assertEquals("Dispatch", imported.alias().getName());

        Alias first = alias(1, 100, "First");
        Alias second = alias(1, 200, "Second");
        String mixedSources = AliasTransferCsv.write(List.of(
            AliasTransferCsv.fields(first, "County A", List.of(), List.of()),
            AliasTransferCsv.fields(second, "County B", List.of(), List.of())));
        assertThrows(IllegalArgumentException.class,
            () -> AliasTransferCsv.read(mixedSources, AliasTransferCsv.Format.VCE, list));
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
            var inputs = List.of(new AliasImportService.Input(changed, false, true, false, List.of(), List.of(), "County"),
                new AliasImportService.Input(alias(list, 300, "New"), false, true, false, List.of(scanName),
                    List.of(fixture.stream.getName()), "County"));
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
            var inputs = List.of(new AliasImportService.Input(update, true, false, true, null, null, null),
                new AliasImportService.Input(alias(fixture.list, 200, "New"), true, false, false, null, null, null),
                new AliasImportService.Input(alias(fixture.list, 300, "Encrypted"), true, false, true, null, null, null));
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

    @Test void radioReferenceFallsBackToListDefaultsWhileVceRowsRemainAuthoritative() throws Exception
    {
        try(Fixture fixture = new Fixture(root))
        {
            var service = fixture.service;
            long scan = service.catalog().scanLists().getFirst().getId();
            String scanName = service.catalog().scanLists().getFirst().getName();
            String streamName = fixture.stream.getName();
            BroadcastChannel stream = new BroadcastChannel(fixture.stream.getConfigurationId(), streamName);
            service.updateAliasListDefaults(fixture.list,
                new AliasListDefaults(new UnmatchedTalkgroupPolicy(true, List.of(stream)), Set.of(scan)),
                service.currentRevision());
            var importer = new AliasImportService(service);
            Alias rrAlias = alias(fixture.list, 400, "RadioReference defaults");
            importer.apply(importer.preview(fixture.list, AliasImportService.Mode.UPDATE_ADD,
                List.of(new AliasImportService.Input(rrAlias, true, false, false, null, null, null)),
                new AliasImportService.Defaults(null, null, null)));
            AliasAdministrationService.AliasEntry inherited = service.transferSnapshot(fixture.list).aliases().getFirst();
            assertTrue(inherited.alias().isRecordable());
            assertEquals(Set.of(scan), inherited.scanListIds());
            assertEquals(Set.of(stream), inherited.alias().getBroadcastChannels());

            Alias vceAlias = alias(fixture.list, 500, "VCE settings");
            vceAlias.setRecordable(false);
            importer.apply(importer.preview(fixture.list, AliasImportService.Mode.UPDATE_ADD,
                List.of(new AliasImportService.Input(vceAlias, false, true, false, List.of(), List.of(),
                    "Source List")), new AliasImportService.Defaults(true, List.of(scanName), List.of(streamName))));
            AliasAdministrationService.AliasEntry explicit = service.transferSnapshot(fixture.list).aliases().stream()
                .filter(entry -> entry.alias().getName().equals("VCE settings")).findFirst().orElseThrow();
            assertFalse(explicit.alias().isRecordable());
            assertTrue(explicit.scanListIds().isEmpty());
            assertTrue(explicit.alias().getBroadcastChannels().isEmpty());
        }
    }

    @Test void blocksUnknownNamesAndStalePreviewsButAllowsRepeatedMatchers() throws Exception
    {
        try(Fixture fixture = new Fixture(root))
        {
            var service = fixture.service;
            long id = service.createAlias(alias(fixture.list, 100, "Keep")).aliasIds().getFirst();
            var importer = new AliasImportService(service);
            var input = new AliasImportService.Input(alias(fixture.list, 200, "New"), false, true, false,
                List.of("Missing"), List.of(), "County");
            var invalid = importer.preview(fixture.list, AliasImportService.Mode.REPLACE, List.of(input), null);
            assertEquals(1L, invalid.preview().counts().get("error"));
            assertThrows(IllegalArgumentException.class, () -> importer.apply(invalid));
            input = new AliasImportService.Input(alias(fixture.list, 200, "New"), false, true, false,
                List.of(), List.of(), "County");
            var duplicated = importer.preview(fixture.list, AliasImportService.Mode.UPDATE_ADD, List.of(input, input), null);
            assertEquals(0L, duplicated.preview().counts().get("error"));
            assertEquals(2L, duplicated.preview().counts().get("added"));
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
