/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.module.decode.p25.phase1;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.module.decode.DecoderFactory;
import io.github.dsheirer.module.decode.p25.phase2.DecodeConfigP25Phase2;
import org.junit.jupiter.api.Test;

class DecodeConfigP25BandplanOverrideTest
{
    private final ObjectMapper mObjectMapper = new ObjectMapper();

    @Test
    void settingRoundTripsAndCopiesForBothTrunkedP25Configurations() throws Exception
    {
        DecodeConfigP25Phase1 phase1 = new DecodeConfigP25Phase1();
        DecodeConfigP25Phase2 phase2 = new DecodeConfigP25Phase2();
        phase1.setUseP25BandplanOverride(true);
        phase2.setUseP25BandplanOverride(true);

        DecodeConfigP25Phase1 restoredPhase1 = mObjectMapper.readValue(mObjectMapper.writeValueAsString(phase1),
            DecodeConfigP25Phase1.class);
        DecodeConfigP25Phase2 restoredPhase2 = mObjectMapper.readValue(mObjectMapper.writeValueAsString(phase2),
            DecodeConfigP25Phase2.class);

        assertTrue(restoredPhase1.getUseP25BandplanOverride());
        assertTrue(restoredPhase2.getUseP25BandplanOverride());
        assertTrue(((DecodeConfigP25Phase1)DecoderFactory.copy(phase1)).getUseP25BandplanOverride());
        assertTrue(((DecodeConfigP25Phase2)DecoderFactory.copy(phase2)).getUseP25BandplanOverride());
        assertFalse(new DecodeConfigP25Phase1().getUseP25BandplanOverride());
    }
}
