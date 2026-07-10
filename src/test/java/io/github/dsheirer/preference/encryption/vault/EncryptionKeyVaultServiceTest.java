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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        preference.setKeys(List.of(key()));
        assertEquals(1, preference.getKeys().size());

        preference.getVaultService().lock();
        assertTrue(preference.getKeys().isEmpty());

        preference.getVaultService().disableForRun();
        assertTrue(preference.getKeys().isEmpty());
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
