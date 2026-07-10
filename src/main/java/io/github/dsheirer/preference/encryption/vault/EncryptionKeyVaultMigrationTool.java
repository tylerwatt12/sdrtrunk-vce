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

import io.github.dsheirer.preference.encryption.EncryptionKeyPreference;
import io.github.dsheirer.preference.encryption.VoiceEncryptionKey;
import io.github.dsheirer.preference.encryption.VoiceEncryptionProtocol;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import java.io.Console;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * One-off migration utility for legacy Java Preferences encryption keys.
 */
public class EncryptionKeyVaultMigrationTool
{
    private static final String NODE_KEYS = "keys";
    private static final String KEY_LABEL = "label";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_PROTOCOL = "protocol";
    private static final String KEY_ALGORITHM_ID = "algorithm_id";
    private static final String KEY_KEY_ID = "key_id";
    private static final String KEY_KEY_HEX = "key_hex";
    private static final String KEY_SCOPE = "scope";

    public static void main(String[] args) throws Exception
    {
        System.exit(run(args));
    }

    private static int run(String[] args) throws Exception
    {
        boolean clearLegacyPreferences = Arrays.asList(args).contains("--clear-preferences");
        boolean savePassword = Arrays.asList(args).contains("--save-password");
        Path passwordFile = getPathArgument(args, "--password-file");
        List<VoiceEncryptionKey> keys = loadLegacyKeys();

        if(keys.isEmpty())
        {
            System.out.println("No legacy encryption keys found.");
            return 0;
        }

        char[] password = passwordFile == null ? readPasswordFromConsole("New encryption vault password: ") :
            Files.readString(passwordFile).trim().toCharArray();
        char[] confirm = passwordFile == null ? readPasswordFromConsole("Confirm encryption vault password: ") :
            Arrays.copyOf(password, password.length);

        if(!Arrays.equals(password, confirm))
        {
            clear(password);
            clear(confirm);
            throw new IllegalArgumentException("Vault passwords do not match.");
        }

        try
        {
            Path vaultPath = EncryptionKeyVaultPath.getVaultPath();
            SdrTrunkDatabaseStartup.createVaultDatabase(vaultPath);
            EncryptionKeyVaultService vaultService = new EncryptionKeyVaultService(vaultPath);
            vaultService.createVault(password);
            vaultService.replaceKeys(keys);

            if(savePassword)
            {
                vaultService.unlock(password, true);
            }

            System.out.println("Migrated " + keys.size() + " encryption key(s) to " + vaultPath);

            if(clearLegacyPreferences)
            {
                clearLegacyPreferences();
                System.out.println("Legacy Java Preferences encryption keys removed.");
            }
        }
        finally
        {
            clear(password);
            clear(confirm);
        }

        return 0;
    }

    private static char[] readPasswordFromConsole(String prompt)
    {
        Console console = System.console();

        if(console == null)
        {
            throw new IllegalStateException("Console is required so the vault password is not echoed. Use " +
                "--password-file for non-interactive migration.");
        }

        return console.readPassword(prompt);
    }

    private static Path getPathArgument(String[] args, String option)
    {
        for(int x = 0; x < args.length - 1; x++)
        {
            if(option.equals(args[x]))
            {
                return Path.of(args[x + 1]);
            }
        }

        return null;
    }

    private static List<VoiceEncryptionKey> loadLegacyKeys() throws BackingStoreException
    {
        List<VoiceEncryptionKey> keys = new ArrayList<>();
        Preferences keysNode = Preferences.userNodeForPackage(EncryptionKeyPreference.class).node(NODE_KEYS);

        for(String child: keysNode.childrenNames())
        {
            VoiceEncryptionKey key = readKey(child, keysNode.node(child));

            if(key != null)
            {
                keys.add(key);
            }
        }

        return keys;
    }

    private static VoiceEncryptionKey readKey(String id, Preferences node)
    {
        try
        {
            VoiceEncryptionKey key = new VoiceEncryptionKey(id);
            key.setLabel(node.get(KEY_LABEL, null));
            key.setEnabled(node.getBoolean(KEY_ENABLED, true));
            key.setProtocol(VoiceEncryptionProtocol.valueOf(node.get(KEY_PROTOCOL, VoiceEncryptionProtocol.APCO25.name())));
            key.setAlgorithmId(node.getInt(KEY_ALGORITHM_ID, 0));
            key.setKeyId(node.getInt(KEY_KEY_ID, 0));
            key.setKeyHex(node.get(KEY_KEY_HEX, null));
            key.setScope(node.get(KEY_SCOPE, null));
            return key;
        }
        catch(Exception e)
        {
            System.err.println("Skipping invalid legacy encryption key [" + id + "]: " + e.getMessage());
            return null;
        }
    }

    private static void clearLegacyPreferences() throws BackingStoreException
    {
        Preferences keysNode = Preferences.userNodeForPackage(EncryptionKeyPreference.class).node(NODE_KEYS);
        keysNode.removeNode();
        Preferences.userNodeForPackage(EncryptionKeyPreference.class).flush();
    }

    private static void clear(char[] password)
    {
        if(password != null)
        {
            Arrays.fill(password, '\0');
        }
    }
}
