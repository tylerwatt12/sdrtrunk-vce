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

package io.github.dsheirer.audio.codec.mbe.decrypt.dmr;

import io.github.dsheirer.audio.codec.mbe.decrypt.VoiceEncryptionContext;
import io.github.dsheirer.audio.codec.mbe.decrypt.VoiceFrameDecryptionException;
import io.github.dsheirer.audio.codec.mbe.decrypt.VoiceFrameDecryptor;
import io.github.dsheirer.audio.codec.mbe.decrypt.crypto.HexByteUtil;
import io.github.dsheirer.bits.BinaryMessage;
import io.github.dsheirer.preference.encryption.VoiceEncryptionKey;
import java.util.Locale;

/**
 * DMRA known-key voice privacy decryptor for ARC4/EP, AES-128, and AES-256.
 */
public class DmrPrivacyFrameDecryptor implements VoiceFrameDecryptor
{
    private final DmrPrivacyAlgorithm mAlgorithm;
    private final VoiceEncryptionContext mContext;
    private final VoiceEncryptionKey mKey;
    private final byte[] mKeyBytes;
    private int mMessageIndicator;
    private byte[] mKeystream;
    private int mFrameIndex;

    public DmrPrivacyFrameDecryptor(VoiceEncryptionContext context, VoiceEncryptionKey key,
                                    DmrPrivacyAlgorithm algorithm) throws VoiceFrameDecryptionException
    {
        mContext = context;
        mKey = key;
        mAlgorithm = algorithm;
        mKeyBytes = parseKey(key, algorithm);
        setMessageIndicator(context != null ? context.getMessageIndicator() : null);
    }

    public DmrPrivacyAlgorithm getAlgorithm()
    {
        return mAlgorithm;
    }

    public VoiceEncryptionContext getContext()
    {
        return mContext;
    }

    public VoiceEncryptionKey getKey()
    {
        return mKey;
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

    /**
     * Loads a new current DMR MI/IV and resets the 18-frame superframe position.
     */
    public void setMessageIndicator(String messageIndicator) throws VoiceFrameDecryptionException
    {
        mMessageIndicator = parseMessageIndicator(messageIndicator);
        mFrameIndex = 0;
        rebuildKeystream();
    }

    @Override
    public byte[] decrypt(byte[] encryptedFrame) throws VoiceFrameDecryptionException
    {
        boolean[] voice = DmrAmbeAdapter.decode9ToVoice49(encryptedFrame);
        applyKeystream(voice);
        byte[] decrypted = DmrAmbeAdapter.encodeVoice49To9(voice);
        advanceFrame();
        return decrypted;
    }

    @Override
    public BinaryMessage decrypt(BinaryMessage encryptedFrame) throws VoiceFrameDecryptionException
    {
        if(encryptedFrame == null)
        {
            throw new VoiceFrameDecryptionException("Missing DMR AMBE frame");
        }

        return BinaryMessage.from(decrypt(encryptedFrame.getBytes()));
    }

    private void applyKeystream(boolean[] voice) throws VoiceFrameDecryptionException
    {
        if(mKeystream == null)
        {
            throw new VoiceFrameDecryptionException("DMR privacy keystream is not initialized");
        }

        int bitOffset = mFrameIndex * DmrPrivacyKeystream.KEYSTREAM_BITS_PER_FRAME;

        for(int x = 0; x < DmrPrivacyKeystream.VOICE_BITS_PER_FRAME; x++)
        {
            if(getKeystreamBit(bitOffset + x))
            {
                voice[x] = !voice[x];
            }
        }
    }

    private boolean getKeystreamBit(int bitOffset)
    {
        return ((mKeystream[bitOffset / 8] >> (7 - (bitOffset % 8))) & 0x01) == 1;
    }

    private void advanceFrame() throws VoiceFrameDecryptionException
    {
        mFrameIndex++;

        if(mFrameIndex >= DmrPrivacyKeystream.SUPERFRAME_AMBE_FRAMES)
        {
            mFrameIndex = 0;
            mMessageIndicator = getPredictedNextMessageIndicator();
            rebuildKeystream();
        }
    }

    private int getPredictedNextMessageIndicator()
    {
        return switch(mAlgorithm)
        {
            case DMRA_RC4 -> DmrPrivacyKeystream.nextArc4Mi(mMessageIndicator);
            case DMRA_AES_128, DMRA_AES_256 -> DmrPrivacyKeystream.expandAesIv(mMessageIndicator).nextMi();
        };
    }

    private void rebuildKeystream() throws VoiceFrameDecryptionException
    {
        mKeystream = switch(mAlgorithm)
        {
            case DMRA_RC4 -> DmrPrivacyKeystream.createArc4SuperframeKeystream(mKeyBytes, mMessageIndicator);
            case DMRA_AES_128, DMRA_AES_256 -> DmrPrivacyKeystream.createAesSuperframeKeystream(mKeyBytes,
                mMessageIndicator);
        };
    }

    private static byte[] parseKey(VoiceEncryptionKey key, DmrPrivacyAlgorithm algorithm)
        throws VoiceFrameDecryptionException
    {
        if(key == null)
        {
            throw new VoiceFrameDecryptionException(algorithm.getLabel() + " requires a configured key");
        }

        byte[] keyBytes = HexByteUtil.parse(key.getKeyHex(), algorithm.getLabel() + " key");

        if(keyBytes.length != algorithm.getKeyBytes())
        {
            throw new VoiceFrameDecryptionException(algorithm.getLabel() + " requires a " +
                algorithm.getKeyBytes() + "-byte key");
        }

        return keyBytes;
    }

    private static int parseMessageIndicator(String messageIndicator) throws VoiceFrameDecryptionException
    {
        if(messageIndicator == null || messageIndicator.isBlank())
        {
            throw new VoiceFrameDecryptionException("DMR privacy requires a 32-bit MI/IV");
        }

        String normalized = messageIndicator.replaceAll("[\\s:_-]", "").toUpperCase(Locale.ROOT);
        int suffix = normalized.indexOf('(');

        if(suffix >= 0)
        {
            normalized = normalized.substring(0, suffix);
        }

        if(normalized.length() < 8)
        {
            throw new VoiceFrameDecryptionException("DMR privacy MI/IV must contain 4 bytes");
        }

        normalized = normalized.substring(0, 8);

        if(!normalized.matches("[0-9A-F]{8}"))
        {
            throw new VoiceFrameDecryptionException("Invalid DMR privacy MI/IV");
        }

        byte[] mi = HexByteUtil.parse(normalized, "DMR privacy MI/IV");
        return ((mi[0] & 0xFF) << 24) | ((mi[1] & 0xFF) << 16) | ((mi[2] & 0xFF) << 8) | (mi[3] & 0xFF);
    }
}
