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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.audio.codec.mbe.decrypt.VoiceEncryptionContext;
import io.github.dsheirer.preference.encryption.VoiceEncryptionKey;
import io.github.dsheirer.preference.encryption.VoiceEncryptionProtocol;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/**
 * NXDN TS 1-A, TS 1-D, and TS 1-F published conformance vectors.
 */
class NxdnPrivacyFrameDecryptorTest
{
    private static final String CLEAR_EHR_1031_HZ = "CEA8FE83ACC458200A";
    private static final String DES_KEY = "ABCDEF0123456789";
    private static final String AES_KEY = "ABCDEF0123456789CDEF0123456789ABEF0123456789ABCD0123456789ABCDEF";

    @Test
    void decryptsStandardScramblerVector() throws Exception
    {
        assertDecrypts(CLEAR_EHR_1031_HZ, "2AACF8C7CCA0847464", NxdnPrivacyFrameDecryptor.SCRAMBLER,
            "0001", null);
    }

    @Test
    void decryptsStandardDesVector() throws Exception
    {
        assertDecrypts(CLEAR_EHR_1031_HZ, "5A15117BC1A2267FA5", NxdnPrivacyFrameDecryptor.DES_OFB,
            DES_KEY, "ABCDEF1234567890");
    }

    @Test
    void decryptsStandardAesVector() throws Exception
    {
        assertDecrypts(CLEAR_EHR_1031_HZ, "BD4503BDC7F187AF31", NxdnPrivacyFrameDecryptor.AES_256_OFB,
            AES_KEY, "ABCDEF1234567890");
    }

    @Test
    void decryptsFinalFrameOfStandardTwoSuperframeSession() throws Exception
    {
        NxdnPrivacyFrameDecryptor des = decryptor(NxdnPrivacyFrameDecryptor.DES_OFB, DES_KEY,
            "ABCDEF1234567890");
        NxdnPrivacyFrameDecryptor aes = decryptor(NxdnPrivacyFrameDecryptor.AES_256_OFB, AES_KEY,
            "ABCDEF1234567890");

        assertClear(des.decrypt(hex("5A15117BC1A2267FA5")));
        assertClear(aes.decrypt(hex("BD4503BDC7F187AF31")));
        des.skipVoiceFrames(30);
        aes.skipVoiceFrames(30);

        assertClear(des.decrypt(hex("D827718948CC6A04B1")));
        assertClear(aes.decrypt(hex("0FA9A6E8D5160D1F3C")));
    }

    @Test
    void preservesOFBAlignmentAcross9600BpsFacchFrames() throws Exception
    {
        NxdnPrivacyFrameDecryptor des = decryptor(NxdnPrivacyFrameDecryptor.DES_OFB, DES_KEY,
            "ABCDEF1234567890");
        String[] firstFour = {"5A15117BC1A2267FA5", "FDB29C8B16A43BFC9B", "12119A8C7ED5F4712D",
            "5CD92943840389B3E7"};

        for(String frame: firstFour)
        {
            assertClear(des.decrypt(hex(frame)));
        }

        des.skipVoiceFrames(4);
        assertClear(des.decrypt(hex("9046C209B217777E56")));
    }

    @Test
    void resetsScramblerAtSuperframeBoundary() throws Exception
    {
        NxdnPrivacyFrameDecryptor scrambler = decryptor(NxdnPrivacyFrameDecryptor.SCRAMBLER, "0001", null);
        assertClear(scrambler.decrypt(hex("2AACF8C7CCA0847464")));
        scrambler.skipVoiceFrames(15);
        assertClear(scrambler.decrypt(hex("2AACF8C7CCA0847464")));
    }

    @Test
    void decryptsTypeDDesVector() throws Exception
    {
        assertDecrypts(CLEAR_EHR_1031_HZ, "193AAEE15122D63F46", NxdnPrivacyFrameDecryptor.DES_OFB,
            DES_KEY, "123456");
    }

    @Test
    void decryptsTypeDAesVector() throws Exception
    {
        assertDecrypts(CLEAR_EHR_1031_HZ, "35E463589927D5DC4D", NxdnPrivacyFrameDecryptor.AES_256_OFB,
            AES_KEY, "123456");
    }

    @Test
    void generatesPublishedTypeDInitializationVectors()
    {
        NxdnInitializationVector.Generated des = NxdnInitializationVector.generateTypeDDes(0x123456);
        NxdnInitializationVector.Generated aes = NxdnInitializationVector.generateTypeDAes(0x123456);

        assertEquals("2468AD21DFA7787C", HexFormat.of().withUpperCase().formatHex(des.initializationVector()));
        assertEquals("2468AD21DFA7787CABAD95F89FD55BE0",
            HexFormat.of().withUpperCase().formatHex(aes.initializationVector()));
        assertEquals(0x555BE0, aes.nextSeed());
    }

    @Test
    void expandsPublishedStandardAesInitializationVector()
    {
        assertEquals("ABCDEF12345678908B8DDEEB890F4CF1", HexFormat.of().withUpperCase().formatHex(
            NxdnInitializationVector.expandStandardAes(0xABCDEF1234567890L)));
    }

    @Test
    void providerAdvertisesNxdnCipherTypes()
    {
        NxdnVoiceFrameDecryptorProvider provider = new NxdnVoiceFrameDecryptorProvider();
        assertTrue(provider.supports(context(NxdnPrivacyFrameDecryptor.SCRAMBLER, null)));
        assertTrue(provider.supports(context(NxdnPrivacyFrameDecryptor.DES_OFB, "123456")));
        assertTrue(provider.supports(context(NxdnPrivacyFrameDecryptor.AES_256_OFB, "123456")));
    }

    private static void assertDecrypts(String expected, String encrypted, int algorithm, String keyHex,
                                       String initializationVector) throws Exception
    {
        NxdnPrivacyFrameDecryptor decryptor = new NxdnPrivacyFrameDecryptor(context(algorithm,
            initializationVector), key(keyHex));
        assertArrayEquals(HexFormat.of().parseHex(expected), decryptor.decrypt(HexFormat.of().parseHex(encrypted)));
    }

    private static NxdnPrivacyFrameDecryptor decryptor(int algorithm, String keyHex,
                                                       String initializationVector) throws Exception
    {
        return new NxdnPrivacyFrameDecryptor(context(algorithm, initializationVector), key(keyHex));
    }

    private static byte[] hex(String value)
    {
        return HexFormat.of().parseHex(value);
    }

    private static void assertClear(byte[] frame)
    {
        assertArrayEquals(hex(CLEAR_EHR_1031_HZ), frame);
    }

    private static VoiceEncryptionContext context(int algorithm, String initializationVector)
    {
        return new VoiceEncryptionContext(VoiceEncryptionProtocol.NXDN, algorithm, 1, initializationVector, 0, null);
    }

    private static VoiceEncryptionKey key(String keyHex)
    {
        VoiceEncryptionKey key = new VoiceEncryptionKey();
        key.setProtocol(VoiceEncryptionProtocol.NXDN);
        key.setKeyHex(keyHex);
        return key;
    }
}
