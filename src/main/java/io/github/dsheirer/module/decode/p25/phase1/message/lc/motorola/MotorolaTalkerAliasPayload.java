/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.module.decode.p25.phase1.message.lc.motorola;

import io.github.dsheirer.bits.BinaryMessage;

/** Shared Motorola alias payload framing, after the carrier's bit fragments have been concatenated. */
public final class MotorolaTalkerAliasPayload
{
    static final int SUID_BITS = 56;
    static final int CRC_BITS = 16;
    //Receiver resource policy, not an asserted Motorola wire-format maximum.
    private static final int MAXIMUM_CODE_UNITS = 64;
    public static final int MAXIMUM_PAYLOAD_BITS = SUID_BITS + MAXIMUM_CODE_UNITS * 16 + CRC_BITS;

    private MotorolaTalkerAliasPayload() {}

    /**
     * Finds the unique SUID/cipher/CRC extent, excluding zero transport filler. Cipher length is a whole number
     * of UTF-16 code units. CRC-16/GSM covers the SUID and cipher: polynomial 0x1021, init 0, xorout 0xFFFF,
     * MSB first. A zero checksum byte belongs to the payload, so trimming trailing zero bytes is unsafe.
     * Returns -1 for invalid, truncated, over-limit or ambiguous framing. Work is bounded by the receiver cap.
     */
    static int findPayloadEnd(BinaryMessage message)
    {
        if(message.size() < SUID_BITS + 16 + CRC_BITS || message.size() > MAXIMUM_PAYLOAD_BITS + 100)
        {
            return -1;
        }

        int crc = 0;
        for(int bit = 0; bit < SUID_BITS; bit += 8)
        {
            crc = updateCrc(crc, message.getByte(bit) & 0xFF);
        }

        int matchedEnd = -1;
        for(int units = 1; units <= MAXIMUM_CODE_UNITS; units++)
        {
            int cipherEnd = SUID_BITS + units * 16;
            int payloadEnd = cipherEnd + CRC_BITS;
            if(payloadEnd > message.size()) break;

            crc = updateCrc(crc, message.getByte(cipherEnd - 16) & 0xFF);
            crc = updateCrc(crc, message.getByte(cipherEnd - 8) & 0xFF);
            if((crc ^ 0xFFFF) != message.getInt(cipherEnd, payloadEnd - 1)) continue;

            int nextSet = message.nextSetBit(payloadEnd);
            if(nextSet >= 0 && nextSet < message.size()) continue;
            if(matchedEnd >= 0) return -1;
            matchedEnd = payloadEnd;
        }
        return matchedEnd;
    }

    private static int updateCrc(int crc, int value)
    {
        crc ^= value << 8;
        for(int bit = 0; bit < 8; bit++)
        {
            crc = ((crc << 1) ^ ((crc & 0x8000) != 0 ? 0x1021 : 0)) & 0xFFFF;
        }
        return crc;
    }
}
