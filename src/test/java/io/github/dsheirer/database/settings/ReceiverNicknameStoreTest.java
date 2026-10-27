/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.settings;

import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import java.nio.file.Path;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ReceiverNicknameStoreTest
{
    private static final String FIRST = "tuner-0123456789abcdef0123456789abcdef";
    private static final String SECOND = "tuner-abcdef0123456789abcdef0123456789";
    @TempDir Path directory;

    @Test void savesClearAndRestartPreserveNamesIndependentlyOfTunerSettings() throws Exception
    {
        Path database = directory.resolve("sdrtrunk.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        ReceiverNicknameStore store = new ReceiverNicknameStore(database);
        store.set(FIRST, "  Roof antenna  ");
        store.set(SECOND, "Portable");
        var before = store.snapshot();
        assertEquals("Roof antenna", before.get(FIRST));
        assertThrows(UnsupportedOperationException.class, () -> before.put(FIRST, "changed"));
        new ApplicationSettingsStore(database).save(ApplicationSettingsStore.TUNER_SETTINGS, java.util.Map.of());
        assertEquals("Roof antenna", new ReceiverNicknameStore(database).get(FIRST));
        store.set(FIRST, "   ");
        assertEquals("", new ReceiverNicknameStore(database).get(FIRST));
        assertEquals("Portable", new ReceiverNicknameStore(database).get(SECOND));
        assertEquals("Roof antenna", before.get(FIRST));
    }

    @Test void failedWriteKeepsLastCommittedCacheAndNamesAreBounded() throws Exception
    {
        Path database = directory.resolve("sdrtrunk.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        new ReceiverNicknameStore(database).set(FIRST, "Existing");
        ReceiverNicknameStore failing = new ReceiverNicknameStore(new ApplicationSettingsStore(database)
        {
            @Override public void save(String key, Object value) throws SQLException
            {
                throw new SQLException("injected write failure");
            }
        });
        assertThrows(SQLException.class, () -> failing.set(FIRST, "Unsaved"));
        assertEquals("Existing", failing.get(FIRST));
        assertEquals("Existing", new ReceiverNicknameStore(database).get(FIRST));
        assertThrows(IllegalArgumentException.class, () -> failing.set("native-private-device", "Name"));
        assertThrows(IllegalArgumentException.class, () -> failing.set(FIRST, "x".repeat(81)));
        assertThrows(IllegalArgumentException.class, () -> failing.set(FIRST, "not\na label"));
        assertEquals("📻".repeat(80), ReceiverNicknameStore.normalize("📻".repeat(80)));
    }

    @Test void malformedOrAbsentCurrentSettingsAreRefusedWithoutRuntimeRepair() throws Exception
    {
        Path database = directory.resolve("sdrtrunk.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        for(String invalid : java.util.List.of("null", "[]", "{\"native-device\":\"Name\"}",
            "{\"" + FIRST + "\":\" A \"}", "{\"" + FIRST + "\":\"\"}",
            "{\"" + FIRST + "\":\"A\",\"" + FIRST + "\":\"B\"}"))
        {
            new ApplicationSettingsStore(database).save("receiver.nicknames", new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(invalid));
            // Duplicate fields must be tested against exact raw JSON rather than a normalized tree.
            if(invalid.contains("\"B\""))
            {
                assertThrows(SQLException.class, () -> ReceiverNicknameSchema.decode(invalid));
                continue;
            }
            assertThrows(SQLException.class, () -> new ReceiverNicknameStore(database));
        }
        try(var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database);
            var statement = connection.createStatement())
        {
            statement.executeUpdate("DELETE FROM application_settings WHERE key='receiver.nicknames'");
        }
        assertThrows(SQLException.class, () -> new ReceiverNicknameStore(database));
        assertFalse(new ApplicationSettingsStore(database).contains("receiver.nicknames"));
    }
}
