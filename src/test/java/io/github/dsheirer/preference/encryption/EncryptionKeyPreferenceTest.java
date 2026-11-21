/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.preference.encryption;

import io.github.dsheirer.audio.codec.mbe.decrypt.VoiceEncryptionContext;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.settings.ApplicationSettingsStore;
import io.github.dsheirer.database.settings.EncryptionKeySchema;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class EncryptionKeyPreferenceTest
{
    @TempDir Path directory;

    @Test
    void ordinaryConfigurationPersistsPlaintextKeysForEveryProtocolAndSupportsCrud() throws Exception
    {
        Path database = directory.resolve("database/sdrtrunk.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        AtomicInteger notifications = new AtomicInteger();
        var preference = new EncryptionKeyPreference(ignored -> notifications.incrementAndGet(),
            new ApplicationSettingsStore(database));
        for(VoiceEncryptionProtocol protocol: VoiceEncryptionProtocol.values()) preference.addKey(key(protocol));
        assertEquals(3, preference.getKeys().size());
        assertEquals(3, notifications.get());
        var reopened = new EncryptionKeyPreference(ignored -> {}, new ApplicationSettingsStore(database));
        for(VoiceEncryptionProtocol protocol: VoiceEncryptionProtocol.values())
        {
            var context = new VoiceEncryptionContext(protocol, 1, 7, null, 0, null);
            assertEquals("0123456789", reopened.resolve(context).orElseThrow().getKeyHex());
        }
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            var query = connection.prepareStatement("SELECT settings_json FROM application_settings WHERE key=?"))
        {
            query.setString(1, EncryptionKeySchema.KEY);
            try(var row = query.executeQuery())
            {
                assertTrue(row.next());
                assertTrue(row.getString(1).contains("\"key_hex\":\"0123456789\""));
                assertEquals(3, EncryptionKeySchema.decode(row.getString(1)).size());
            }
        }
        VoiceEncryptionKey changed = reopened.getKeys().getFirst();
        changed.setEnabled(false);
        reopened.updateKey(changed);
        assertTrue(reopened.getKeys().stream().anyMatch(saved -> !saved.isEnabled()));
        reopened.removeKey(changed);
        assertEquals(2, new EncryptionKeyPreference(ignored -> {}, new ApplicationSettingsStore(database)).getKeys().size());
        assertFalse(java.nio.file.Files.exists(directory.resolve("vault")));
    }

    @Test
    void receiverResolutionDoesNotWaitForAdministrationWritesOrReadStorage() throws Exception
    {
        CountDownLatch saving = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger loads = new AtomicInteger();
        var settings = new ApplicationSettingsStore(directory.resolve("unused.sqlite"))
        {
            @Override public Optional<String> loadJson(String name, int maximum)
            {
                loads.incrementAndGet();
                return Optional.of("[]");
            }
            @Override public void save(String name, Object value) throws IOException
            {
                saving.countDown();
                try
                {
                    if(!release.await(5, TimeUnit.SECONDS)) throw new IOException("Test writer was not released");
                }
                catch(InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException(); }
            }
        };
        var preference = new EncryptionKeyPreference(ignored -> {}, settings);
        var context = new VoiceEncryptionContext(VoiceEncryptionProtocol.APCO25, 1, 7, null, 0, null);
        try(var executor = Executors.newVirtualThreadPerTaskExecutor())
        {
            var write = executor.submit(() -> preference.addKey(key(VoiceEncryptionProtocol.APCO25)));
            try
            {
                assertTrue(saving.await(2, TimeUnit.SECONDS));
                var receiver = executor.submit(() -> {
                    assertFalse(preference.hasKeys());
                    assertTrue(preference.resolve(context).isEmpty());
                });
                receiver.get(1, TimeUnit.SECONDS);
                assertEquals(1, loads.get());
            }
            finally { release.countDown(); }
            write.get(2, TimeUnit.SECONDS);
        }
        assertTrue(preference.hasKeys());
        VoiceEncryptionKey match = preference.resolve(context).orElseThrow();
        match.setKeyHex("FFFF");
        assertEquals("0123456789", preference.resolve(context).orElseThrow().getKeyHex());
        assertEquals(1, loads.get());
    }

    @Test
    void failedWritesKeepTheLastCommittedSnapshotAndDoNotNotify() throws Exception
    {
        AtomicInteger notifications = new AtomicInteger();
        var settings = new ApplicationSettingsStore(directory.resolve("unused.sqlite"))
        {
            @Override public Optional<String> loadJson(String name, int maximum) { return Optional.of("[]"); }
            @Override public void save(String name, Object value) throws SQLException { throw new SQLException("unavailable"); }
        };
        var preference = new EncryptionKeyPreference(ignored -> notifications.incrementAndGet(), settings);
        assertThrows(IllegalStateException.class, () -> preference.addKey(key(VoiceEncryptionProtocol.DMR)));
        assertFalse(preference.hasKeys());
        assertEquals(0, notifications.get());
    }

    @Test
    void startupRefusesInvalidStoredKeysWithoutChangingThemOrDisclosingTheirContents() throws Exception
    {
        Path database = directory.resolve("invalid/database/sdrtrunk.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        var settings = new ApplicationSettingsStore(database);
        var node = EncryptionKeySchema.encode(java.util.List.of(key(VoiceEncryptionProtocol.DMR)));
        ((com.fasterxml.jackson.databind.node.ObjectNode)node.get(0)).put("key_hex", "INVALID-KEY-CONTENTS");
        settings.save(EncryptionKeySchema.KEY, node);
        var failure = assertThrows(SQLException.class,
            () -> SdrTrunkDatabaseStartup.validateGlobalDatabaseForStartup(database));
        assertFalse(failure.toString().contains("INVALID-KEY-CONTENTS"));
        assertNull(failure.getCause());
        assertEquals(node.toString(), settings.loadJson(EncryptionKeySchema.KEY, EncryptionKeySchema.MAXIMUM_BYTES).orElseThrow());
    }

    private static VoiceEncryptionKey key(VoiceEncryptionProtocol protocol)
    {
        var key = new VoiceEncryptionKey();
        key.setProtocol(protocol);
        key.setLabel(protocol.name());
        key.setAlgorithmId(1);
        key.setKeyId(7);
        key.setKeyHex("01 23:45-67_89");
        return key;
    }
}
