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
 * Protocol-specific voice frame decryptor.
 */
public interface VoiceFrameDecryptor
{
    /**
     * Indicates if this decryptor has a real frame implementation.
     */
    boolean isImplemented();

    /**
     * Resets any call-local keystream state.
     */
    void reset();

    /**
     * Advances protocol keystream state for transmitted voice positions that were replaced by control/data.  The
     * default preserves binary compatibility for providers whose protocols do not need explicit gap handling.
     */
    default void skipVoiceFrames(int voiceFrameCount) throws VoiceFrameDecryptionException
    {
    }

    /**
     * Decrypts an IMBE/AMBE frame represented as bytes.
     */
    byte[] decrypt(byte[] encryptedFrame) throws VoiceFrameDecryptionException;

    /**
     * Decrypts an AMBE frame represented as a binary message.
     */
    BinaryMessage decrypt(BinaryMessage encryptedFrame) throws VoiceFrameDecryptionException;
}
