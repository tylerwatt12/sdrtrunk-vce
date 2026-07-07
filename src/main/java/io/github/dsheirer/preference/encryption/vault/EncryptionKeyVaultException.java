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
 * Vault storage or password failure.
 */
public class EncryptionKeyVaultException extends Exception
{
    public EncryptionKeyVaultException(String message)
    {
        super(message);
    }

    public EncryptionKeyVaultException(String message, Throwable cause)
    {
        super(message, cause);
    }
}
