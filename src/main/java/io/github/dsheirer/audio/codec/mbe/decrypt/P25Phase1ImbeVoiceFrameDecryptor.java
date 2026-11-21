/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */

package io.github.dsheirer.audio.codec.mbe.decrypt;

import io.github.dsheirer.bits.BinaryMessage;

/**
 * Shared APCO25 Phase 1 IMBE frame decryptor.
 */
abstract class P25Phase1ImbeVoiceFrameDecryptor implements VoiceFrameDecryptor
{
    private static final int LDU2_VOICE_OFFSET = 101;
    private static final int IMBE_BYTES = 11;
    private final byte[] mKeystream;
    private final int mVoicePayloadStartOffset;
    private int mFrameIndex;

    protected P25Phase1ImbeVoiceFrameDecryptor(byte[] keystream, int voicePayloadStartOffset)
        throws VoiceFrameDecryptionException
    {
        if(keystream == null || keystream.length == 0)
        {
            throw new VoiceFrameDecryptionException("Missing APCO25 voice decryptor keystream");
        }

        mKeystream = keystream;
        mVoicePayloadStartOffset = voicePayloadStartOffset;
    }

    @Override
    public boolean isImplemented()
    {
        return true;
    }

    @Override
    public void reset()
    {
        mFrameIndex = 0;
    }

    @Override
    public byte[] decrypt(byte[] encryptedFrame) throws VoiceFrameDecryptionException
    {
        byte[] packed = ImbeAdapter.decode18ToPacked11(encryptedFrame);
        int offset = getPhase1Offset(mFrameIndex++);

        if(offset < 0 || offset + IMBE_BYTES > mKeystream.length)
        {
            throw new VoiceFrameDecryptionException("APCO25 Phase 1 voice keystream is too short");
        }

        for(int x = 0; x < packed.length; x++)
        {
            packed[x] = (byte)((packed[x] & 0xFF) ^ (mKeystream[offset + x] & 0xFF));
        }

        return ImbeAdapter.encodePacked11To18(packed);
    }

    @Override
    public BinaryMessage decrypt(BinaryMessage encryptedFrame) throws VoiceFrameDecryptionException
    {
        throw new VoiceFrameDecryptionException("APCO25 Phase 2 AMBE decryption is not implemented");
    }

    private int getPhase1Offset(int frameIndex)
    {
        int relative = frameIndex % 18;
        int voicePosition = relative % 9;
        boolean ldu2 = relative >= 9;
        return (ldu2 ? LDU2_VOICE_OFFSET : 0) + mVoicePayloadStartOffset + (voicePosition * IMBE_BYTES) +
            (voicePosition < 8 ? 0 : 2);
    }
}
