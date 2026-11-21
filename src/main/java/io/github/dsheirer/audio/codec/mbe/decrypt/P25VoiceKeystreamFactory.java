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

import io.github.dsheirer.audio.codec.mbe.decrypt.crypto.AesOfb;
import io.github.dsheirer.audio.codec.mbe.decrypt.crypto.DesOfb;
import io.github.dsheirer.audio.codec.mbe.decrypt.crypto.HexByteUtil;
import io.github.dsheirer.audio.codec.mbe.decrypt.crypto.Rc4;
import io.github.dsheirer.preference.encryption.VoiceEncryptionKey;
import io.github.dsheirer.preference.encryption.VoiceEncryptionProtocol;

/**
 * Shared APCO25 known-key voice keystream setup for Phase 1 IMBE and Phase 2 AMBE decryptors.
 */
class P25VoiceKeystreamFactory
{
    static final int DES_OFB_ALGORITHM_ID = 0x81;
    static final int AES_256_ALGORITHM_ID = 0x84;
    static final int ADP_ALGORITHM_ID = 0xAA;

    private static final int ADP_KEY_BYTES = 5;
    private static final int ADP_MI_BYTES = 8;
    private static final int ADP_RC4_KEY_BYTES = ADP_KEY_BYTES + ADP_MI_BYTES;
    private static final int ADP_KEYSTREAM_BYTES = 469;

    private static final int DES_KEY_BYTES = 8;
    private static final int AES_256_KEY_BYTES = 32;
    private static final int DES_BLOCKS_PER_SUPERFRAME = 28;
    private static final int AES_PHASE1_BLOCKS_PER_SUPERFRAME = 15;
    private static final int AES_PHASE2_BLOCKS_PER_SUPERFRAME = 9;

    private static final int ADP_PHASE1_VOICE_OFFSET = 267;
    private static final int DES_PHASE1_VOICE_OFFSET = 19;
    private static final int AES_PHASE1_VOICE_OFFSET = 27;
    private static final int ADP_PHASE2_VOICE_OFFSET = 256;
    private static final int DES_PHASE2_VOICE_OFFSET = 8;
    private static final int AES_PHASE2_VOICE_OFFSET = 16;

    private P25VoiceKeystreamFactory()
    {
    }

    enum Phase
    {
        PHASE1,
        PHASE2
    }

    record P25Keystream(byte[] keystream, int voicePayloadStartOffset)
    {
    }

    static boolean supportsAlgorithm(int algorithmId)
    {
        return algorithmId == ADP_ALGORITHM_ID || algorithmId == DES_OFB_ALGORITHM_ID ||
            algorithmId == AES_256_ALGORITHM_ID;
    }

    static P25Keystream create(VoiceEncryptionContext context, VoiceEncryptionKey key, Phase phase)
        throws VoiceFrameDecryptionException
    {
        validateContext(context, phase);
        byte[] mi = P25MessageIndicator.parse64(context.getMessageIndicator());

        return switch(context.getAlgorithmId())
        {
            case ADP_ALGORITHM_ID -> new P25Keystream(createAdpKeystream(parseAdpKey(key), mi),
                phase == Phase.PHASE1 ? ADP_PHASE1_VOICE_OFFSET : ADP_PHASE2_VOICE_OFFSET);
            case DES_OFB_ALGORITHM_ID -> new P25Keystream(
                DesOfb.keystream(parseExactKey(key, DES_KEY_BYTES, "DES-OFB key"), mi,
                    DES_BLOCKS_PER_SUPERFRAME),
                phase == Phase.PHASE1 ? DES_PHASE1_VOICE_OFFSET : DES_PHASE2_VOICE_OFFSET);
            case AES_256_ALGORITHM_ID -> new P25Keystream(
                AesOfb.keystream(parseExactKey(key, AES_256_KEY_BYTES, "AES-256 key"),
                    P25MessageIndicator.expandTo128(mi),
                    phase == Phase.PHASE1 ? AES_PHASE1_BLOCKS_PER_SUPERFRAME :
                        AES_PHASE2_BLOCKS_PER_SUPERFRAME),
                phase == Phase.PHASE1 ? AES_PHASE1_VOICE_OFFSET : AES_PHASE2_VOICE_OFFSET);
            default -> throw new VoiceFrameDecryptionException("Unsupported APCO25 ALGID 0x" +
                Integer.toHexString(context.getAlgorithmId()).toUpperCase());
        };
    }

    private static void validateContext(VoiceEncryptionContext context, Phase phase)
        throws VoiceFrameDecryptionException
    {
        if(context == null || context.getProtocol() != VoiceEncryptionProtocol.APCO25 ||
            !supportsAlgorithm(context.getAlgorithmId()))
        {
            throw new VoiceFrameDecryptionException("APCO25 decryptor requires a supported APCO25 ALGID");
        }

        if(phase == Phase.PHASE1 && context.getTimeslot() != 0)
        {
            throw new VoiceFrameDecryptionException("APCO25 Phase 1 IMBE decryptor requires timeslot 0");
        }

        if(phase == Phase.PHASE2 && context.getTimeslot() <= 0)
        {
            throw new VoiceFrameDecryptionException("APCO25 Phase 2 AMBE decryptor requires a TDMA timeslot");
        }
    }

    private static byte[] parseAdpKey(VoiceEncryptionKey key) throws VoiceFrameDecryptionException
    {
        if(key == null)
        {
            throw new VoiceFrameDecryptionException("Missing ADP key");
        }

        return HexByteUtil.parseLeftPadded(key.getKeyHex(), ADP_KEY_BYTES, "ADP key");
    }

    private static byte[] parseExactKey(VoiceEncryptionKey key, int bytes, String label)
        throws VoiceFrameDecryptionException
    {
        if(key == null)
        {
            throw new VoiceFrameDecryptionException("Missing " + label);
        }

        return HexByteUtil.parseExact(key.getKeyHex(), bytes, label);
    }

    private static byte[] createAdpKeystream(byte[] adpKey, byte[] mi)
    {
        byte[] rc4Key = new byte[ADP_RC4_KEY_BYTES];
        System.arraycopy(adpKey, 0, rc4Key, 0, ADP_KEY_BYTES);
        System.arraycopy(mi, 0, rc4Key, ADP_KEY_BYTES, ADP_MI_BYTES);
        return Rc4.keystream(rc4Key, ADP_KEYSTREAM_BYTES);
    }
}
