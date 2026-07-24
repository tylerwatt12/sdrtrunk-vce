/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.database.alias;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasFactory;
import io.github.dsheirer.alias.AliasIdentifierPolicy;
import io.github.dsheirer.alias.action.RecurringAction;
import io.github.dsheirer.alias.action.clip.ClipAction;
import io.github.dsheirer.alias.id.AliasID;
import io.github.dsheirer.alias.id.broadcast.BroadcastChannel;
import io.github.dsheirer.alias.id.dcs.Dcs;
import io.github.dsheirer.alias.id.AliasIDType;
import io.github.dsheirer.alias.id.legacy.mpt1327.MPT1327ID;
import io.github.dsheirer.alias.id.legacy.nonrecordable.NonRecordable;
import io.github.dsheirer.alias.id.priority.Priority;
import io.github.dsheirer.alias.id.record.Record;
import io.github.dsheirer.alias.id.talkgroup.P25FullyQualifiedTalkgroup;
import io.github.dsheirer.alias.id.talkgroup.StreamAsTalkgroup;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.alias.id.talkgroup.TalkgroupRange;
import io.github.dsheirer.alias.id.tone.TonesID;
import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.identifier.tone.AmbeTone;
import io.github.dsheirer.identifier.tone.Tone;
import io.github.dsheirer.identifier.tone.ToneSequence;
import io.github.dsheirer.module.decode.dcs.DCSCode;
import io.github.dsheirer.protocol.Protocol;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AliasDatabaseStoreTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void roundTripsAliases() throws Exception
    {
        Path database = mTemporaryFolder.resolve("sdrtrunk.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        AliasDatabaseStore store = new AliasDatabaseStore(database);
        assertFalse(store.hasAliases());

        Alias alias = new Alias("County Fire Dispatch");
        alias.setAliasListName("Lake County");
        alias.setGroup("Fire");
        alias.setColor(0x123456);
        alias.setIconName("Fire Truck");
        alias.setStreamTalkgroupAlias(new StreamAsTalkgroup(42));
        alias.addAliasID(new Talkgroup(Protocol.APCO25, 1001));
        alias.addAliasID(new TalkgroupRange(Protocol.APCO25, 2000, 2999));
        alias.addAliasID(new P25FullyQualifiedTalkgroup(0xbee00, 0x123, 3101));
        alias.addAliasID(new BroadcastChannel("RadioResolve"));
        alias.addAliasID(new Record());
        alias.addAliasID(new NonRecordable());
        alias.addAliasID(new Priority(5));

        Dcs dcs = new Dcs();
        dcs.setDCSCode(DCSCode.N023);
        alias.addAliasID(dcs);

        ToneSequence toneSequence = new ToneSequence();
        toneSequence.addTone(new Tone(AmbeTone.DTMF_1, 3));
        alias.addAliasID(new TonesID(toneSequence));

        ClipAction clipAction = new ClipAction();
        clipAction.setPath("/tmp/alert.wav");
        clipAction.setInterval(RecurringAction.Interval.DELAYED_RESET);
        clipAction.setPeriod(7);
        alias.addAliasAction(clipAction);

        store.replaceAliases(List.of(alias));
        assertTrue(store.hasAliases());

        List<Alias> aliases = store.loadAliases();
        assertEquals(1, aliases.size());

        Alias loaded = aliases.get(0);
        assertEquals("County Fire Dispatch", loaded.getName());
        assertEquals("Lake County", loaded.getAliasListName());
        assertEquals("Fire", loaded.getGroup());
        assertEquals(0x123456, loaded.getColor());
        assertEquals("Fire Truck", loaded.getIconName());
        assertEquals(42, loaded.getStreamTalkgroupAlias().getValue());

        assertTrue(hasTalkgroup(loaded, Protocol.APCO25, 1001));
        assertTrue(hasTalkgroupRange(loaded, Protocol.APCO25, 2000, 2999));
        assertTrue(hasFullyQualifiedTalkgroup(loaded, 0xbee00, 0x123, 3101));
        assertTrue(hasIdentifier(loaded, BroadcastChannel.class));
        assertTrue(hasIdentifier(loaded, Record.class));
        assertTrue(hasIdentifier(loaded, NonRecordable.class));
        assertTrue(hasIdentifier(loaded, Priority.class));
        assertTrue(hasIdentifier(loaded, Dcs.class));
        assertTrue(hasIdentifier(loaded, TonesID.class));

        assertEquals(1, loaded.getAliasActions().size());
        ClipAction loadedClip = (ClipAction)loaded.getAliasActions().get(0);
        assertEquals("/tmp/alert.wav", loadedClip.getPath());
        assertEquals(RecurringAction.Interval.DELAYED_RESET, loadedClip.getInterval());
        assertEquals(7, loadedClip.getPeriod());

        try(Connection connection = SdrTrunkDatabase.open(database);
            Statement statement = connection.createStatement())
        {
            assertFalse(tableExists(connection, "alias_identifier"));
            assertEquals(3, countRows(connection, "alias_talkgroup"));
            assertEquals(1, countRows(connection, "alias_broadcast_channel"));
            assertEquals(1, countRows(connection, "alias_text_identifier"));
            assertEquals(1, countRows(connection, "alias_tone_sequence"));
            assertEquals(1, countRows(connection, "alias_action"));

            try(ResultSet resultSet = statement.executeQuery("""
                SELECT record_enabled, non_recordable, priority
                FROM alias
                """))
            {
                assertTrue(resultSet.next());
                assertEquals(1, resultSet.getInt("record_enabled"));
                assertEquals(1, resultSet.getInt("non_recordable"));
                assertEquals(5, resultSet.getInt("priority"));
            }
        }
    }

    @Test
    void treatsExistingAliasRowsAsInitializedForMigration() throws Exception
    {
        Path database = mTemporaryFolder.resolve("legacy-alias.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        AliasDatabaseStore store = new AliasDatabaseStore(database);

        try(Connection connection = SdrTrunkDatabase.open(database);
            PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO alias (sort_order, name, alias_list_name, group_name, color, icon_name, stream_as_talkgroup)
                VALUES (0, 'Legacy Alias', 'Legacy List', NULL, 0, NULL, NULL)
                """))
        {
            statement.executeUpdate();
        }

        assertTrue(store.isInitialized());
        assertEquals(1, store.loadAliases().size());
    }

    @Test
    void ignoresRetiredScriptActionsInExistingDatabases() throws Exception
    {
        Path database = mTemporaryFolder.resolve("retired-script-action.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        AliasDatabaseStore store = new AliasDatabaseStore(database);

        try(Connection connection = SdrTrunkDatabase.open(database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO alias (sort_order, name, alias_list_name, group_name, color, icon_name,
                    stream_as_talkgroup)
                VALUES (0, 'Legacy Script Alias', 'Legacy List', NULL, 0, NULL, NULL)
                """);
            statement.executeUpdate("""
                INSERT INTO alias_action (alias_id, sort_order, type, interval, period, path, script)
                SELECT id, 0, 'SCRIPT', 'ONCE', 0, NULL, '/tmp/retired-script' FROM alias
                """);
        }

        List<Alias> aliases = store.loadAliases();
        assertEquals(1, aliases.size());
        assertTrue(aliases.get(0).getAliasActions().isEmpty(),
            "retired script rows must never be restored or executed");
    }

    @Test
    void keepsIdentifiersAndActionsAttachedToOwningAliasRows() throws Exception
    {
        Path database = mTemporaryFolder.resolve("multi-alias.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        AliasDatabaseStore store = new AliasDatabaseStore(database);

        Alias systemA = new Alias("System A Dispatch");
        systemA.setAliasListName("System A");
        systemA.addAliasID(new Talkgroup(Protocol.APCO25, 1001));
        ClipAction systemAClip = new ClipAction();
        systemAClip.setPath("/tmp/system-a.wav");
        systemA.addAliasAction(systemAClip);

        Alias systemB = new Alias("System B Dispatch");
        systemB.setAliasListName("System B");
        systemB.addAliasID(new Talkgroup(Protocol.APCO25, 2002));
        ClipAction systemBClip = new ClipAction();
        systemBClip.setPath("/tmp/system-b.wav");
        systemB.addAliasAction(systemBClip);

        store.replaceAliases(List.of(systemA, systemB));

        List<Alias> aliases = store.loadAliases();
        Alias loadedSystemA = getAlias(aliases, "System A Dispatch");
        Alias loadedSystemB = getAlias(aliases, "System B Dispatch");

        assertTrue(hasTalkgroup(loadedSystemA, Protocol.APCO25, 1001));
        assertFalse(hasTalkgroup(loadedSystemA, Protocol.APCO25, 2002));
        assertTrue(hasTalkgroup(loadedSystemB, Protocol.APCO25, 2002));
        assertFalse(hasTalkgroup(loadedSystemB, Protocol.APCO25, 1001));

        assertEquals(1, loadedSystemA.getAliasActions().size());
        assertEquals("/tmp/system-a.wav", ((ClipAction)loadedSystemA.getAliasActions().get(0)).getPath());
        assertEquals(1, loadedSystemB.getAliasActions().size());
        assertEquals("/tmp/system-b.wav", ((ClipAction)loadedSystemB.getAliasActions().get(0)).getPath());
    }

    @Test
    void emptyAliasReplacementMarksInitialized() throws Exception
    {
        Path database = mTemporaryFolder.resolve("empty-alias.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        AliasDatabaseStore store = new AliasDatabaseStore(database);
        assertFalse(store.isInitialized());

        store.replaceAliases(List.of());

        assertTrue(store.isInitialized());
        assertTrue(store.loadAliases().isEmpty());
    }

    @Test
    void preservesButHidesRetiredMptAliasIdentifiers() throws Exception
    {
        Path database = mTemporaryFolder.resolve("retired-mpt-alias.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        AliasDatabaseStore store = new AliasDatabaseStore(database);

        Alias alias = new Alias("Legacy MPT Alias");
        alias.setAliasListName("Legacy");
        MPT1327ID legacyIdentifier = new MPT1327ID();
        legacyIdentifier.setIdent("001-0001");
        alias.addAliasID(legacyIdentifier);
        alias.addAliasID(new Talkgroup(Protocol.MPT1327, 8_193));

        store.replaceAliases(List.of(alias));
        Alias loaded = store.loadAliases().get(0);
        assertEquals(2, loaded.getAliasIdentifiers().size());
        assertTrue(loaded.getAliasIdentifiers().stream().noneMatch(AliasIdentifierPolicy::isUserVisible));
        assertTrue(AliasIDType.MPT1327.isRetiredCompatibility());
        MPT1327ID loadedLegacy = loaded.getAliasIdentifiers().stream()
            .filter(MPT1327ID.class::isInstance)
            .map(MPT1327ID.class::cast)
            .findFirst()
            .orElseThrow();
        assertEquals("001-0001", loadedLegacy.getIdent());
        MPT1327ID copiedLegacy = (MPT1327ID)AliasFactory.copyOf(loadedLegacy);
        assertEquals("001-0001", copiedLegacy.getIdent());

        store.replaceAliases(List.of(loaded));
        Alias savedAgain = store.loadAliases().get(0);
        assertEquals(2, savedAgain.getAliasIdentifiers().size());
        assertTrue(savedAgain.getAliasIdentifiers().stream().noneMatch(AliasIdentifierPolicy::isUserVisible));
    }

    private static boolean hasIdentifier(Alias alias, Class<? extends AliasID> type)
    {
        return alias.getAliasIdentifiers().stream().anyMatch(type::isInstance);
    }

    private static boolean tableExists(Connection connection, String table) throws Exception
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?
            """))
        {
            statement.setString(1, table);

            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next();
            }
        }
    }

    private static int countRows(Connection connection, String table) throws Exception
    {
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + table))
        {
            assertTrue(resultSet.next());
            return resultSet.getInt(1);
        }
    }

    private static Alias getAlias(List<Alias> aliases, String name)
    {
        return aliases.stream()
            .filter(alias -> name.equals(alias.getName()))
            .findFirst()
            .orElseThrow();
    }

    private static boolean hasTalkgroup(Alias alias, Protocol protocol, int value)
    {
        return alias.getAliasIdentifiers().stream()
            .filter(Talkgroup.class::isInstance)
            .map(Talkgroup.class::cast)
            .anyMatch(talkgroup -> talkgroup.getProtocol() == protocol && talkgroup.getValue() == value);
    }

    private static boolean hasTalkgroupRange(Alias alias, Protocol protocol, int min, int max)
    {
        return alias.getAliasIdentifiers().stream()
            .filter(TalkgroupRange.class::isInstance)
            .map(TalkgroupRange.class::cast)
            .anyMatch(range -> range.getProtocol() == protocol &&
                range.getMinTalkgroup() == min &&
                range.getMaxTalkgroup() == max);
    }

    private static boolean hasFullyQualifiedTalkgroup(Alias alias, int wacn, int system, int value)
    {
        return alias.getAliasIdentifiers().stream()
            .filter(P25FullyQualifiedTalkgroup.class::isInstance)
            .map(P25FullyQualifiedTalkgroup.class::cast)
            .anyMatch(talkgroup -> talkgroup.getWacn() == wacn &&
                talkgroup.getSystem() == system &&
                talkgroup.getValue() == value);
    }
}
