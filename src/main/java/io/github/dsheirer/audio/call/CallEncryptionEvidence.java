/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */
package io.github.dsheirer.audio.call;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.github.dsheirer.audio.codec.mbe.IEncryptionSyncParameters;
import io.github.dsheirer.identifier.encryption.EncryptionKey;
import io.github.dsheirer.identifier.encryption.EncryptionKeyIdentifier;

/**
 * Immutable, bounded evidence captured from the first usable encrypted-call synchronization parameters.
 *
 * <p>The 72-bit P25 message indicator is reduced to a process-only 64-bit comparison fingerprint.  The raw message
 * indicator is never retained, rendered, serialized, logged, or persisted.  This evidence is only used by the
 * in-memory logical-call resolver.</p>
 */
public record CallEncryptionEvidence(int algorithmId, int keyId, long messageIndicatorFingerprint)
{
    private static final int P25_MESSAGE_INDICATOR_HEX_LENGTH = 18;
    private static final long FNV_OFFSET_BASIS = 0xCBF29CE484222325L;
    private static final long FNV_PRIME = 0x100000001B3L;

    public CallEncryptionEvidence
    {
        if(algorithmId < 0 || algorithmId > 0xFF)
        {
            throw new IllegalArgumentException("Encryption algorithm id is outside its 8-bit field");
        }

        if(keyId < 0 || keyId > 0xFFFF)
        {
            throw new IllegalArgumentException("Encryption key id is outside its 16-bit field");
        }
    }

    /**
     * Captures encrypted synchronization parameters without retaining their raw message indicator.
     */
    public static CallEncryptionEvidence capture(IEncryptionSyncParameters parameters)
    {
        return parameters != null ? capture(parameters.getEncryptionKey(), parameters.getMessageIndicator()) : null;
    }

    /**
     * Captures an encrypted key and optional P25 message indicator.
     */
    public static CallEncryptionEvidence capture(EncryptionKeyIdentifier identifier, String messageIndicator)
    {
        EncryptionKey key = identifier != null ? identifier.getValue() : null;

        if(key == null || !identifier.isEncrypted())
        {
            return null;
        }

        return new CallEncryptionEvidence(key.getAlgorithm(), key.getKey(), fingerprint(messageIndicator));
    }

    public boolean hasMessageIndicator()
    {
        return messageIndicatorFingerprint != 0L;
    }

    /**
     * Available only to the in-memory resolver.  Explicitly excluded from generic JSON projection.
     */
    @JsonIgnore
    public long messageIndicatorFingerprint()
    {
        return messageIndicatorFingerprint;
    }

    /**
     * A matching message indicator is strong positive evidence.  Absence or disagreement is intentionally not a
     * negative result because different sites can re-encrypt the same logical call.
     */
    public boolean hasMatchingMessageIndicator(CallEncryptionEvidence other)
    {
        return other != null && hasMessageIndicator() && other.hasMessageIndicator() &&
            messageIndicatorFingerprint == other.messageIndicatorFingerprint;
    }

    private static long fingerprint(String messageIndicator)
    {
        if(messageIndicator == null || messageIndicator.length() != P25_MESSAGE_INDICATOR_HEX_LENGTH)
        {
            return 0L;
        }

        long fingerprint = FNV_OFFSET_BASIS;

        for(int index = 0; index < P25_MESSAGE_INDICATOR_HEX_LENGTH; index++)
        {
            int nibble = Character.digit(messageIndicator.charAt(index), 16);

            if(nibble < 0)
            {
                return 0L;
            }

            fingerprint ^= nibble;
            fingerprint *= FNV_PRIME;
        }

        return fingerprint != 0L ? fingerprint : 1L;
    }

    /**
     * Deliberately omits the message-indicator fingerprint so diagnostic logging cannot disclose or correlate it.
     */
    @Override
    public String toString()
    {
        return "CallEncryptionEvidence[algorithmId=" + algorithmId + ", keyId=" + keyId +
            ", messageIndicator=" + (hasMessageIndicator() ? "present" : "unavailable") + "]";
    }
}
