/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.audio.codec.mbe.decrypt.nxdn;

import io.github.dsheirer.audio.codec.mbe.decrypt.VoiceEncryptionContext;
import io.github.dsheirer.audio.codec.mbe.decrypt.VoiceFrameDecryptionException;
import io.github.dsheirer.audio.codec.mbe.decrypt.VoiceFrameDecryptor;
import io.github.dsheirer.audio.codec.mbe.decrypt.crypto.AesOfb;
import io.github.dsheirer.audio.codec.mbe.decrypt.crypto.DesOfb;
import io.github.dsheirer.audio.codec.mbe.decrypt.crypto.HexByteUtil;
import io.github.dsheirer.bits.BinaryMessage;
import io.github.dsheirer.preference.encryption.VoiceEncryptionKey;
import java.util.Arrays;
import java.util.Locale;

/**
 * NXDN EHR known-key decryptor for the standardized scrambler, DES-OFB, and AES-256-OFB cipher types.
 */
public class NxdnPrivacyFrameDecryptor implements VoiceFrameDecryptor
{
    public static final int SCRAMBLER = 1;
    public static final int DES_OFB = 2;
    public static final int AES_256_OFB = 3;
    private static final int VOICE_BITS = 49;
    private static final int SCRAMBLER_SESSION_FRAMES = 16;
    private static final int OFB_SESSION_FRAMES = 32;
    private final VoiceEncryptionContext mContext;
    private final byte[] mKey;
    private int mFrameIndex;
    private int mScramblerSeed;
    private int mTypeDSeed;
    private boolean mTypeD;
    private byte[] mKeystream;

    public NxdnPrivacyFrameDecryptor(VoiceEncryptionContext context, VoiceEncryptionKey key)
        throws VoiceFrameDecryptionException
    {
        if(context == null)
        {
            throw new VoiceFrameDecryptionException("NXDN decryption requires signaling context");
        }

        mContext = context;
        mKey = HexByteUtil.parse(key != null ? key.getKeyHex() : null, "NXDN key");
        initialize();
    }

    @Override
    public boolean isImplemented()
    {
        return true;
    }

    @Override
    public void reset()
    {
        try
        {
            initialize();
        }
        catch(VoiceFrameDecryptionException e)
        {
            throw new IllegalStateException("Unable to reset NXDN decryptor", e);
        }
    }

    @Override
    public byte[] decrypt(byte[] encryptedFrame) throws VoiceFrameDecryptionException
    {
        if(mContext.getAlgorithmId() != SCRAMBLER && !mTypeD && mFrameIndex >= OFB_SESSION_FRAMES)
        {
            throw new VoiceFrameDecryptionException("NXDN OFB session requires a new initialization vector");
        }

        boolean[] voice = NxdnAmbeAdapter.decode(encryptedFrame);

        if(mContext.getAlgorithmId() == SCRAMBLER)
        {
            applyScrambler(voice);
        }
        else
        {
            applyOfb(voice);
        }

        byte[] decrypted = NxdnAmbeAdapter.encode(voice);
        advanceSession();
        return decrypted;
    }

    @Override
    public void skipVoiceFrames(int voiceFrameCount) throws VoiceFrameDecryptionException
    {
        if(voiceFrameCount < 0)
        {
            throw new VoiceFrameDecryptionException("NXDN voice-frame skip count cannot be negative");
        }

        for(int frame = 0; frame < voiceFrameCount; frame++)
        {
            if(mContext.getAlgorithmId() == SCRAMBLER)
            {
                advanceScramblerBits(VOICE_BITS);
            }
            else if(!mTypeD && mFrameIndex >= OFB_SESSION_FRAMES)
            {
                throw new VoiceFrameDecryptionException("NXDN OFB session requires a new initialization vector");
            }

            advanceSession();
        }
    }

    @Override
    public BinaryMessage decrypt(BinaryMessage encryptedFrame) throws VoiceFrameDecryptionException
    {
        if(encryptedFrame == null)
        {
            throw new VoiceFrameDecryptionException("Missing NXDN AMBE frame");
        }

        return BinaryMessage.from(decrypt(encryptedFrame.getBytes()));
    }

    private void initialize() throws VoiceFrameDecryptionException
    {
        mFrameIndex = 0;

        switch(mContext.getAlgorithmId())
        {
            case SCRAMBLER -> initializeScrambler();
            case DES_OFB, AES_256_OFB -> initializeOfb();
            default -> throw new VoiceFrameDecryptionException("Unsupported NXDN cipher type " +
                mContext.getAlgorithmId());
        }
    }

    private void initializeScrambler() throws VoiceFrameDecryptionException
    {
        if(mKey.length != 2)
        {
            throw new VoiceFrameDecryptionException("NXDN scrambler requires a 15-bit key encoded in 2 bytes");
        }

        int key = ((mKey[0] & 0xFF) << 8) | (mKey[1] & 0xFF);

        if(key == 0 || key > 0x7FFF)
        {
            throw new VoiceFrameDecryptionException("NXDN scrambler key must be in the range 0001-7FFF");
        }

        mScramblerSeed = key;
        mKeystream = null;
    }

    private void initializeOfb() throws VoiceFrameDecryptionException
    {
        String indicator = normalizeMessageIndicator(mContext.getMessageIndicator());
        mTypeD = indicator.length() == 6;

        if(mTypeD)
        {
            mTypeDSeed = Integer.parseInt(indicator, 16) & 0x7FFFFF;
        }

        if(mContext.getAlgorithmId() == DES_OFB)
        {
            if(mKey.length != 8)
            {
                throw new VoiceFrameDecryptionException("NXDN DES-OFB requires an 8-byte key");
            }

            byte[] iv;

            if(mTypeD)
            {
                NxdnInitializationVector.Generated generated = NxdnInitializationVector.generateTypeDDes(mTypeDSeed);
                iv = generated.initializationVector();
                mTypeDSeed = generated.nextSeed();
            }
            else
            {
                iv = HexByteUtil.parse(indicator, "NXDN DES initialization vector");
            }

            byte[] generated = DesOfb.keystream(mKey, iv, 26);
            mKeystream = Arrays.copyOfRange(generated, 8, generated.length);
        }
        else
        {
            if(mKey.length != 32)
            {
                throw new VoiceFrameDecryptionException("NXDN AES-256-OFB requires a 32-byte key");
            }

            byte[] iv;

            if(mTypeD)
            {
                NxdnInitializationVector.Generated generated = NxdnInitializationVector.generateTypeDAes(mTypeDSeed);
                iv = generated.initializationVector();
                mTypeDSeed = generated.nextSeed();
            }
            else
            {
                byte[] iv64 = HexByteUtil.parse(indicator, "NXDN AES initialization vector");
                iv = NxdnInitializationVector.expandStandardAes(toLong(iv64));
            }

            byte[] generated = AesOfb.keystream(mKey, iv, 14);
            mKeystream = Arrays.copyOfRange(generated, 16, generated.length);
        }
    }

    private void applyScrambler(boolean[] voice)
    {
        for(int index = 0; index < VOICE_BITS; index++)
        {
            if((mScramblerSeed & 1) != 0)
            {
                voice[index] = !voice[index];
            }

            int feedback = ((mScramblerSeed >>> 1) ^ mScramblerSeed) & 1;
            mScramblerSeed = (mScramblerSeed >>> 1) | (feedback << 14);
        }
    }

    private void advanceScramblerBits(int count)
    {
        for(int index = 0; index < count; index++)
        {
            int feedback = ((mScramblerSeed >>> 1) ^ mScramblerSeed) & 1;
            mScramblerSeed = (mScramblerSeed >>> 1) | (feedback << 14);
        }
    }

    private void applyOfb(boolean[] voice)
    {
        int bitOffset = mFrameIndex * VOICE_BITS;

        for(int index = 0; index < VOICE_BITS; index++)
        {
            int streamIndex = bitOffset + index;

            if(((mKeystream[streamIndex / 8] >>> (7 - (streamIndex % 8))) & 1) != 0)
            {
                voice[index] = !voice[index];
            }
        }
    }

    private void advanceSession() throws VoiceFrameDecryptionException
    {
        mFrameIndex++;

        if(mContext.getAlgorithmId() == SCRAMBLER && mFrameIndex == SCRAMBLER_SESSION_FRAMES)
        {
            mFrameIndex = 0;
            initializeScrambler();
        }
        else if(mContext.getAlgorithmId() != SCRAMBLER && mFrameIndex == OFB_SESSION_FRAMES)
        {
            if(mTypeD)
            {
                mFrameIndex = 0;
                rebuildTypeDKeystream();
            }
        }
    }

    private void rebuildTypeDKeystream() throws VoiceFrameDecryptionException
    {
        if(mContext.getAlgorithmId() == DES_OFB)
        {
            NxdnInitializationVector.Generated generated = NxdnInitializationVector.generateTypeDDes(mTypeDSeed);
            byte[] stream = DesOfb.keystream(mKey, generated.initializationVector(), 26);
            mKeystream = Arrays.copyOfRange(stream, 8, stream.length);
            mTypeDSeed = generated.nextSeed();
        }
        else
        {
            NxdnInitializationVector.Generated generated = NxdnInitializationVector.generateTypeDAes(mTypeDSeed);
            byte[] stream = AesOfb.keystream(mKey, generated.initializationVector(), 14);
            mKeystream = Arrays.copyOfRange(stream, 16, stream.length);
            mTypeDSeed = generated.nextSeed();
        }
    }

    private static String normalizeMessageIndicator(String indicator) throws VoiceFrameDecryptionException
    {
        if(indicator == null || indicator.isBlank())
        {
            throw new VoiceFrameDecryptionException("NXDN DES/AES requires an initialization vector");
        }

        String normalized = indicator.replaceAll("[\\s:_-]", "").toUpperCase(Locale.ROOT);

        if(!normalized.matches("(?:[0-9A-F]{6}|[0-9A-F]{16})"))
        {
            throw new VoiceFrameDecryptionException("NXDN initialization vector must contain 23 or 64 bits");
        }

        return normalized;
    }

    private static long toLong(byte[] bytes) throws VoiceFrameDecryptionException
    {
        if(bytes.length != 8)
        {
            throw new VoiceFrameDecryptionException("NXDN initialization vector must contain 8 bytes");
        }

        long value = 0;

        for(byte current: bytes)
        {
            value = (value << 8) | (current & 0xFFL);
        }

        return value;
    }
}
