/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.module.decode.p25.phase1;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.edac.bch.BCH_63_16_23_P25;

/**
 * Validation helpers for the 64-bit P25 Phase 1 Network Identifier (NID).
 */
final class P25P1NIDValidator
{
    static final int NO_EXPECTED_NAC = -1;
    private static final int PARITY_BIT_INDEX = 63;
    private static final int MAX_CORRECTABLE_BIT_COUNT = 11;

    private P25P1NIDValidator()
    {
    }

    /**
     * Validates a BCH-decoded NID and accounts for the final parity bit. The 64th bit is
     * the XOR of DUID bits S1 and S0 according to the full generator matrix in TIA-102.BAAA-A sections 8.5-8.5.2.
     * A parity error is correctable only when the total NID error count remains within the eleven-bit correction
     * capacity.
     *
     * @param nid BCH-decoded 64-bit NID
     * @return total corrected NID bit count or -1 when the NID cannot be accepted
     */
    static int validate(CorrectedBinaryMessage nid)
    {
        int correctedBitCount = nid.getCorrectedBitCount();

        if(correctedBitCount < 0)
        {
            return -1;
        }

        int duid = nid.getInt(BCH_63_16_23_P25.DUID_FIELD);
        boolean expectedParity = ((duid & 0x2) != 0) ^ ((duid & 0x1) != 0);

        if(nid.get(PARITY_BIT_INDEX) != expectedParity)
        {
            if(correctedBitCount >= MAX_CORRECTABLE_BIT_COUNT)
            {
                return -1;
            }

            correctedBitCount++;
        }

        return correctedBitCount;
    }

    /**
     * Validates the full NID and requires its decoded NAC to match a fixed authority.
     */
    static int validateExpectedNAC(CorrectedBinaryMessage nid, int expectedNAC)
    {
        int correctedBitCount = validate(nid);
        return correctedBitCount >= 0 && nid.getInt(BCH_63_16_23_P25.NAC_FIELD) == expectedNAC ?
            correctedBitCount : -1;
    }
}
