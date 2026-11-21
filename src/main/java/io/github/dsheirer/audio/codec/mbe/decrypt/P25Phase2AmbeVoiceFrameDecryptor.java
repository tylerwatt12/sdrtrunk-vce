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
import io.github.dsheirer.preference.encryption.VoiceEncryptionKey;

/**
 * APCO25 Phase 2 AMBE voice frame decryptor.
 *
 * P25 Phase 2 encrypts the 49 protected AMBE voice bits, packed as seven bytes, in an 18-frame TDMA superframe.
 * The key stream setup is shared with P25 Phase 1; only the frame packing and byte offset differ.
 */
public class P25Phase2AmbeVoiceFrameDecryptor implements VoiceFrameDecryptor
{
    private static final int AMBE_BYTES = AmbeProtectedVoiceAdapter.PACKED_VOICE_BYTES;
    private static final int FRAMES_PER_SUPERFRAME = 18;
    private final byte[] mKeystream;
    private final int mVoicePayloadStartOffset;
    private int mFrameIndex;

    public P25Phase2AmbeVoiceFrameDecryptor(VoiceEncryptionContext context, VoiceEncryptionKey key)
        throws VoiceFrameDecryptionException
    {
        this(P25VoiceKeystreamFactory.create(context, key, P25VoiceKeystreamFactory.Phase.PHASE2));
    }

    private P25Phase2AmbeVoiceFrameDecryptor(P25VoiceKeystreamFactory.P25Keystream keystream)
        throws VoiceFrameDecryptionException
    {
        if(keystream.keystream() == null || keystream.keystream().length == 0)
        {
            throw new VoiceFrameDecryptionException("Missing APCO25 Phase 2 voice decryptor keystream");
        }

        mKeystream = keystream.keystream();
        mVoicePayloadStartOffset = keystream.voicePayloadStartOffset();
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
        byte[] packed = P25Phase2AmbeAdapter.decode9ToPacked7(encryptedFrame);
        int offset = getPhase2Offset(mFrameIndex++);

        if(offset < 0 || offset + AMBE_BYTES > mKeystream.length)
        {
            throw new VoiceFrameDecryptionException("APCO25 Phase 2 voice keystream is too short");
        }

        for(int x = 0; x < packed.length; x++)
        {
            packed[x] = (byte)((packed[x] & 0xFF) ^ (mKeystream[offset + x] & 0xFF));
        }

        P25Phase2AmbeAdapter.maskUnusedVoiceBits(packed);
        return P25Phase2AmbeAdapter.encodePacked7To9(packed);
    }

    @Override
    public BinaryMessage decrypt(BinaryMessage encryptedFrame) throws VoiceFrameDecryptionException
    {
        if(encryptedFrame == null)
        {
            throw new VoiceFrameDecryptionException("Missing APCO25 Phase 2 AMBE frame");
        }

        return BinaryMessage.from(decrypt(encryptedFrame.getBytes()));
    }

    private int getPhase2Offset(int frameIndex)
    {
        if(frameIndex >= FRAMES_PER_SUPERFRAME)
        {
            return -1;
        }

        return mVoicePayloadStartOffset + (frameIndex * AMBE_BYTES);
    }
}
