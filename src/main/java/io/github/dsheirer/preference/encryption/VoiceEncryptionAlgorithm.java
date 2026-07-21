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

package io.github.dsheirer.preference.encryption;

/**
 * Known voice encryption algorithm IDs.  The optional decryption module declares which of these it supports.
 */
public enum VoiceEncryptionAlgorithm
{
    APCO25_ACCORDION_3(VoiceEncryptionProtocol.APCO25, 0x00, "ACCORDIAN 3", null),
    APCO25_BATON_AUTO_EVEN(VoiceEncryptionProtocol.APCO25, 0x01, "BATON AUTO EVEN", null),
    APCO25_FIREFLY(VoiceEncryptionProtocol.APCO25, 0x02, "FIREFLY", null),
    APCO25_MAYFLY(VoiceEncryptionProtocol.APCO25, 0x03, "MAYFLY", null),
    APCO25_SAVILLE(VoiceEncryptionProtocol.APCO25, 0x04, "SAVILLE", null),
    APCO25_MOTOROLA_PADSTONE(VoiceEncryptionProtocol.APCO25, 0x05, "MOTOROLA PADSTONE", null),
    APCO25_BATON_AUTO_ODD(VoiceEncryptionProtocol.APCO25, 0x41, "BATON AUTO ODD", null),
    APCO25_DES_OFB(VoiceEncryptionProtocol.APCO25, 0x81, "DES OFB", 8),
    APCO25_TRIPLE_DES_2_KEY(VoiceEncryptionProtocol.APCO25, 0x82, "2-KEY TRIPLE DES", 16),
    APCO25_TRIPLE_DES_3_KEY(VoiceEncryptionProtocol.APCO25, 0x83, "3-KEY TRIPLE DES", 24),
    APCO25_AES_256(VoiceEncryptionProtocol.APCO25, 0x84, "AES-256", 32),
    APCO25_AES_128(VoiceEncryptionProtocol.APCO25, 0x85, "AES-128", 16),
    APCO25_AES_CBC(VoiceEncryptionProtocol.APCO25, 0x88, "AES-CBC", null),
    APCO25_AES_128_OFB(VoiceEncryptionProtocol.APCO25, 0x89, "AES-128-OFB", 16),
    APCO25_DES_XL(VoiceEncryptionProtocol.APCO25, 0x9F, "MOTOROLA DES-XL", 8),
    APCO25_DVI_XL(VoiceEncryptionProtocol.APCO25, 0xA0, "MOTOROLA DVI-XL", null),
    APCO25_DVP_XL(VoiceEncryptionProtocol.APCO25, 0xA1, "MOTOROLA DVP-XL", null),
    APCO25_DVP_SPFL(VoiceEncryptionProtocol.APCO25, 0xA2, "MOTOROLA DVP-SPFL", null),
    APCO25_HAYSTACK(VoiceEncryptionProtocol.APCO25, 0xA3, "MOTOROLA HAYSTACK", null),
    APCO25_MOTOROLA_A4(VoiceEncryptionProtocol.APCO25, 0xA4, "MOTOROLA UNKNOWN A4", null),
    APCO25_MOTOROLA_A5(VoiceEncryptionProtocol.APCO25, 0xA5, "MOTOROLA UNKNOWN A5", null),
    APCO25_MOTOROLA_A6(VoiceEncryptionProtocol.APCO25, 0xA6, "MOTOROLA UNKNOWN A6", null),
    APCO25_MOTOROLA_A7(VoiceEncryptionProtocol.APCO25, 0xA7, "MOTOROLA UNKNOWN A7", null),
    APCO25_MOTOROLA_A8(VoiceEncryptionProtocol.APCO25, 0xA8, "MOTOROLA UNKNOWN A8", null),
    APCO25_MOTOROLA_A9(VoiceEncryptionProtocol.APCO25, 0xA9, "MOTOROLA UNKNOWN A9", null),
    APCO25_ADP(VoiceEncryptionProtocol.APCO25, 0xAA, "Motorola ADP 40-bit RC4", 5),
    APCO25_MOTOROLA_CFX_256(VoiceEncryptionProtocol.APCO25, 0xAB, "MOTOROLA CFX-256", 32),
    APCO25_MOTOROLA_AC(VoiceEncryptionProtocol.APCO25, 0xAC, "MOTOROLA UNKNOWN AC", null),
    APCO25_MOTOROLA_AD(VoiceEncryptionProtocol.APCO25, 0xAD, "MOTOROLA UNKNOWN AD", null),
    APCO25_MOTOROLA_AE(VoiceEncryptionProtocol.APCO25, 0xAE, "MOTOROLA UNKNOWN AE", null),
    APCO25_AES_256_GCM(VoiceEncryptionProtocol.APCO25, 0xAF, "MOTOROLA AES-256-GCM", 32),
    APCO25_DVP_B0(VoiceEncryptionProtocol.APCO25, 0xB0, "MOTOROLA DVP B0", null),
    DMR_HYTERA_BASIC_PRIVACY(VoiceEncryptionProtocol.DMR, 0x01, "Hytera Basic Privacy", null),
    DMR_HYTERA_ENHANCED_PRIVACY(VoiceEncryptionProtocol.DMR, 0x02, "Hytera Enhanced Privacy", null),
    DMR_DMRA_RC4(VoiceEncryptionProtocol.DMR, 0x21, "DMRA RC4/EP", 5),
    DMR_DMRA_AES_128(VoiceEncryptionProtocol.DMR, 0x24, "DMRA AES-128", 16),
    DMR_DMRA_AES_256(VoiceEncryptionProtocol.DMR, 0x25, "DMRA AES-256", 32),
    DMR_HYTERA_ENHANCED_PRIVACY_2(VoiceEncryptionProtocol.DMR, 0x26, "Hytera Enhanced Privacy 2", null),
    NXDN_SCRAMBLER(VoiceEncryptionProtocol.NXDN, 0x01, "Scrambler", 2),
    NXDN_DES_OFB(VoiceEncryptionProtocol.NXDN, 0x02, "DES-OFB", 8),
    NXDN_AES_256_OFB(VoiceEncryptionProtocol.NXDN, 0x03, "AES-256-OFB", 32);

    private final VoiceEncryptionProtocol mProtocol;
    private final int mValue;
    private final String mLabel;
    private final Integer mExpectedKeyBytes;

    VoiceEncryptionAlgorithm(VoiceEncryptionProtocol protocol, int value, String label, Integer expectedKeyBytes)
    {
        mProtocol = protocol;
        mValue = value;
        mLabel = label;
        mExpectedKeyBytes = expectedKeyBytes;
    }

    public VoiceEncryptionProtocol getProtocol()
    {
        return mProtocol;
    }

    public int getValue()
    {
        return mValue;
    }

    public Integer getExpectedKeyBytes()
    {
        return mExpectedKeyBytes;
    }

    public boolean hasExpectedKeyLength()
    {
        return mExpectedKeyBytes != null;
    }

    @Override
    public String toString()
    {
        return mLabel + " (0x" + Integer.toHexString(mValue).toUpperCase() + ")";
    }

    public static VoiceEncryptionAlgorithm fromValue(VoiceEncryptionProtocol protocol, int value)
    {
        for(VoiceEncryptionAlgorithm algorithm: values())
        {
            if(algorithm.getProtocol() == protocol && algorithm.getValue() == value)
            {
                return algorithm;
            }
        }

        return null;
    }

    public static String getLabel(VoiceEncryptionProtocol protocol, int value)
    {
        VoiceEncryptionAlgorithm algorithm = fromValue(protocol, value);

        if(algorithm != null)
        {
            return algorithm.toString();
        }

        return "0x" + Integer.toHexString(value).toUpperCase();
    }
}
