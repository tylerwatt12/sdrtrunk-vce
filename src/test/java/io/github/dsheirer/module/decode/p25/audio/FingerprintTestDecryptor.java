/*
 * Copyright (C) 2026
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.dsheirer.module.decode.p25.audio;

import io.github.dsheirer.audio.codec.mbe.decrypt.VoiceFrameDecryptor;
import io.github.dsheirer.bits.BinaryMessage;

/** Deliberately changes the ciphertext so tests can distinguish received from decrypted evidence. */
final class FingerprintTestDecryptor implements VoiceFrameDecryptor
{
    private final int mBytes;

    FingerprintTestDecryptor(int bytes)
    {
        mBytes = bytes;
    }

    public boolean isImplemented() { return true; }
    public void reset() {}

    public byte[] decrypt(byte[] frame)
    {
        byte[] decrypted = new byte[mBytes];
        decrypted[0] = (byte)0x80;
        return decrypted;
    }

    public BinaryMessage decrypt(BinaryMessage frame)
    {
        BinaryMessage decrypted = new BinaryMessage(mBytes * 8);
        decrypted.set(0);
        return decrypted;
    }
}
