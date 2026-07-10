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

import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.directory.DirectoryPreference;
import io.github.dsheirer.portable.PortableApplicationPaths;
import java.nio.file.Path;

/**
 * Default location for the voice encryption key vault.
 */
public final class EncryptionKeyVaultPath
{
    public static final String VAULT_DIRECTORY = "vault";
    public static final String VAULT_FILENAME = "encryption-key-vault.sqlite";

    private EncryptionKeyVaultPath()
    {
    }

    public static Path getVaultPath(UserPreferences userPreferences)
    {
        return getVaultPath(userPreferences.getDirectoryPreference());
    }

    public static Path getVaultPath(DirectoryPreference directoryPreference)
    {
        return directoryPreference.getDirectoryApplicationRoot().resolve(VAULT_DIRECTORY).resolve(VAULT_FILENAME);
    }

    public static Path getVaultPath()
    {
        return getVaultPath(PortableApplicationPaths.getDataRoot());
    }

    public static Path getVaultPath(Path applicationRoot)
    {
        return applicationRoot.resolve(VAULT_DIRECTORY).resolve(VAULT_FILENAME);
    }
}
