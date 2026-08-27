/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
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

package io.github.dsheirer.audio.call;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.AliasList;
import org.junit.jupiter.api.Test;

class MutableAudioCallBuilderEncryptionStateTest
{
    @Test
    void retainsUnknownUntilAudioOrAuthoritativeSignalingArrives()
    {
        MutableAudioCallBuilder builder = builder();

        assertEquals(CallEncryptionState.UNKNOWN, builder.getEncryptionState());
        builder.addAudio(new float[0], 900L);
        assertEquals(CallEncryptionState.UNKNOWN, builder.getEncryptionState());
        builder.addAudio(new float[160], 1_000L);
        assertEquals(CallEncryptionState.CLEAR, builder.getEncryptionState());
    }

    @Test
    void encryptedStateSurvivesDecodedAudioFromADecryptor()
    {
        MutableAudioCallBuilder builder = builder();

        assertTrue(builder.observeEncryptionState(CallEncryptionState.ENCRYPTED));
        builder.addAudio(new float[160], 1_000L);
        assertEquals(CallEncryptionState.ENCRYPTED, builder.getEncryptionState());
    }

    @Test
    void conflictingAuthoritativeObservationsFailOpenPermanently()
    {
        MutableAudioCallBuilder builder = builder();

        assertTrue(builder.observeEncryptionState(CallEncryptionState.CLEAR));
        assertTrue(builder.observeEncryptionState(CallEncryptionState.ENCRYPTED));
        assertEquals(CallEncryptionState.UNKNOWN, builder.getEncryptionState());
        assertFalse(builder.observeEncryptionState(CallEncryptionState.CLEAR));
        assertFalse(builder.observeEncryptionState(CallEncryptionState.ENCRYPTED));
        builder.addAudio(new float[160], 1_000L);
        assertEquals(CallEncryptionState.UNKNOWN, builder.getEncryptionState());
    }

    @Test
    void encryptedEvidenceAlsoEstablishesEncryptedState()
    {
        MutableAudioCallBuilder builder = builder();

        assertTrue(builder.setCallEncryptionEvidence(new CallEncryptionEvidence(0x84, 0x101, 42L)));
        assertEquals(CallEncryptionState.ENCRYPTED, builder.getEncryptionState());
    }

    private static MutableAudioCallBuilder builder()
    {
        return new MutableAudioCallBuilder(AliasList.empty("test"), 0);
    }
}
