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

package io.github.dsheirer.preference.encryption.vault;

import io.github.dsheirer.preference.PreferenceType;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.preference.directory.DirectoryPreference;
import io.github.dsheirer.preference.encryption.EncryptionKeyPreference;
import io.github.dsheirer.preference.encryption.VoiceEncryptionAlgorithm;
import io.github.dsheirer.preference.encryption.VoiceEncryptionKey;
import io.github.dsheirer.preference.encryption.VoiceEncryptionProtocol;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EncryptionKeyVaultServiceTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void createsLocksUnlocksAndRejectsWrongPassword() throws Exception
    {
        EncryptionKeyVaultService service = service("vault.sqlite");
        assertEquals(EncryptionKeyVaultState.MISSING, service.getState());

        service.createVault(password("secret"));
        assertEquals(EncryptionKeyVaultState.UNLOCKED, service.getState());
        service.replaceKeys(List.of(key()));
        assertEquals(1, service.getKeys().size());

        service.lock();
        assertEquals(EncryptionKeyVaultState.LOCKED, service.getState());
        assertTrue(service.getKeys().isEmpty());
        assertThrows(EncryptionKeyVaultException.class, () -> service.unlock(password("wrong"), false));

        service.unlock(password("secret"), false);
        assertEquals(1, service.getKeys().size());
    }

    @Test
    void changesPasswordAndExportsVault() throws Exception
    {
        EncryptionKeyVaultService service = service("change.sqlite");
        service.createVault(password("old"));
        service.replaceKeys(List.of(key()));
        service.changePassword(password("old"), password("new"));
        service.lock();
        assertThrows(EncryptionKeyVaultException.class, () -> service.unlock(password("old"), false));
        service.unlock(password("new"), false);
        assertEquals(1, service.getKeys().size());

        Path export = mTemporaryFolder.resolve("export.sqlite");
        service.exportVault(export);
        assertTrue(Files.exists(export));
    }

    @Test
    void savedPasswordCanAutoUnlock() throws Exception
    {
        EncryptionKeyVaultService service = service("saved.sqlite");
        service.forgetSavedPassword();
        service.createVault(password("saved"));
        service.replaceKeys(List.of(key()));
        service.lock();
        service.unlock(password("saved"), true);
        assertTrue(service.hasSavedPassword());

        EncryptionKeyVaultService reloaded = new EncryptionKeyVaultService(service.getVaultPath());

        try
        {
            assertTrue(reloaded.tryAutoUnlockSavedPassword());
            assertEquals(1, reloaded.getKeys().size());
        }
        finally
        {
            reloaded.forgetSavedPassword();
        }
    }

    @Test
    void preferenceReturnsNoKeysWhileVaultIsLockedOrDisabled() throws Exception
    {
        DirectoryPreference directoryPreference = new DirectoryPreference(null)
        {
            @Override
            public Path getDirectoryApplicationRoot()
            {
                return mTemporaryFolder;
            }
        };

        EncryptionKeyPreference preference = new EncryptionKeyPreference(this::ignore, directoryPreference);
        SdrTrunkDatabaseStartup.createVaultDatabase(preference.getVaultService().getVaultPath());
        preference.getVaultService().createVault(password("secret"));
        preference.addKey(key());
        assertEquals(1, preference.getKeys().size());

        preference.getVaultService().lock();
        assertTrue(preference.getKeys().isEmpty());

        preference.getVaultService().disableForRun();
        assertTrue(preference.getKeys().isEmpty());
    }

    @Test
    void plainStatusIsPublishedOnTheControlWorkerWithoutInitializingAUiToolkit() throws Exception
    {
        EncryptionKeyVaultService service = service("plain-status.sqlite");
        AtomicReference<Thread> notificationThread = new AtomicReference<>();
        List<EncryptionKeyVaultService.Status> observed = new ArrayList<>();
        BiConsumer<EncryptionKeyVaultService.Status,EncryptionKeyVaultService.Status> listener = (previous,current) -> {
            assertEquals(current, service.statusSnapshot());
            assertEquals(current.state(), service.getState());
            notificationThread.set(Thread.currentThread());
            observed.add(current);
        };
        service.addStatusListener(listener);
        try(var worker = Executors.newSingleThreadExecutor())
        {
            Thread commandThread = worker.submit(() -> {
                service.createVault(password("secret"));
                return Thread.currentThread();
            }).get(10, TimeUnit.SECONDS);
            assertEquals(commandThread, notificationThread.get());
            assertEquals(EncryptionKeyVaultState.UNLOCKED, service.statusSnapshot().state());
            assertEquals("Encryption vault created and unlocked", service.getStatus());
            service.replaceKeys(List.of(key()));
            assertEquals(1, service.getKeys().size());

            service.removeStatusListener(listener);
            int notifications = observed.size();
            service.lock();
            assertEquals(notifications, observed.size());
            assertEquals(EncryptionKeyVaultState.LOCKED, service.statusSnapshot().state());
        }
    }

    @Test
    void localPromptPreferenceRetainsItsPersistedValueAndPresentationFailuresDoNotChangeVaultState() throws Exception
    {
        EncryptionKeyVaultService service = service("plain-preference.sqlite");
        boolean original = service.isPromptOnLaunch();
        service.addStatusListener((previous,current) -> { throw new IllegalStateException("observer unavailable"); });
        try
        {
            service.setPromptOnLaunch(!original);
            assertEquals(!original, service.statusSnapshot().promptOnLaunch());
            assertEquals(!original, new EncryptionKeyVaultService(service.getVaultPath()).isPromptOnLaunch());
            service.createVault(password("secret"));
            assertTrue(service.isUnlocked());
            assertEquals(EncryptionKeyVaultState.UNLOCKED, service.statusSnapshot().state());
        }
        finally { service.setPromptOnLaunch(original); }
    }

    private EncryptionKeyVaultService service(String filename) throws Exception
    {
        Path vaultPath = mTemporaryFolder.resolve(filename);
        SdrTrunkDatabaseStartup.createVaultDatabase(vaultPath);
        return new EncryptionKeyVaultService(vaultPath);
    }

    private VoiceEncryptionKey key()
    {
        VoiceEncryptionKey key = new VoiceEncryptionKey();
        key.setLabel("Test Key");
        key.setProtocol(VoiceEncryptionProtocol.APCO25);
        key.setAlgorithmId(VoiceEncryptionAlgorithm.APCO25_ADP.getValue());
        key.setKeyId(0x1234);
        key.setKeyHex("0102030405");
        return key;
    }

    private char[] password(String password)
    {
        return password.toCharArray();
    }

    private void ignore(PreferenceType preferenceType)
    {
    }
}
