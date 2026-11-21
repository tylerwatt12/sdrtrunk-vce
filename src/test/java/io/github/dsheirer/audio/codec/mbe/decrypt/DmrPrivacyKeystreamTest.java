/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.audio.codec.mbe.decrypt;

import io.github.dsheirer.audio.codec.mbe.decrypt.dmr.DmrPrivacyKeystream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class DmrPrivacyKeystreamTest
{
    @Test
    void advancesArc4MiWithDmraLfsr()
    {
        assertEquals(0x278BA396, DmrPrivacyKeystream.nextArc4Mi(0x0C11F14A));
        assertEquals(0x598726CD, DmrPrivacyKeystream.nextArc4Mi(0xEA7E9D12));
    }

    @Test
    void expandsAesMiTo128BitIv()
    {
        DmrPrivacyKeystream.AesIv iv = DmrPrivacyKeystream.expandAesIv(0xC840C1AA);

        assertEquals("C840C1AA62B33B5B887EAA95F51D2701", hex(iv.iv()));
        assertEquals(0x62B33B5B, iv.nextMi());
    }

    @Test
    void expandsAes256LogVector()
    {
        DmrPrivacyKeystream.AesIv iv = DmrPrivacyKeystream.expandAesIv(0x45442076);

        assertEquals("45442076AA6A4E7F6F19426F63764935", hex(iv.iv()));
        assertEquals(0xAA6A4E7F, iv.nextMi());
    }

    private static String hex(byte[] bytes)
    {
        StringBuilder sb = new StringBuilder();

        for(byte value: bytes)
        {
            sb.append(String.format("%02X", value & 0xFF));
        }

        return sb.toString();
    }
}
