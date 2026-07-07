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

/**
 * Runtime state for the known-key decryption vault.
 */
public enum EncryptionKeyVaultState
{
    MISSING("Missing"),
    LOCKED("Locked"),
    UNLOCKED("Unlocked"),
    DISABLED("Disabled"),
    ERROR("Error");

    private final String mLabel;

    EncryptionKeyVaultState(String label)
    {
        mLabel = label;
    }

    public boolean isUnlocked()
    {
        return this == UNLOCKED;
    }

    @Override
    public String toString()
    {
        return mLabel;
    }
}
