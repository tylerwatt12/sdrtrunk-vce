/*
 * ******************************************************************************
 * sdrtrunk
 * Copyright (C) 2014-2019 Dennis Sheirer
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
 * *****************************************************************************
 */

package io.github.dsheirer.module.decode.p25.phase1.message.pdu.block;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.edac.trellis.SoftViterbiDecoder_1_2_P25;
import io.github.dsheirer.module.decode.p25.phase1.message.SoftDibitMessage;

/**
 * P25 Unconfirmed Data block that uses 1/2 rate trellis coding.
 */
public class UnconfirmedDataBlock extends DataBlock
{
    private static final SoftViterbiDecoder_1_2_P25 SOFT_VITERBI_HALF_RATE_DECODER = new SoftViterbiDecoder_1_2_P25();
    private CorrectedBinaryMessage mDecodedMessage;

    /**
     * Constructs an unconfirmed data block from the deinterleaved soft dibit message.
     * @param softDibits containing deinterleaved soft dibits for the 196-bit data block.
     */
    public UnconfirmedDataBlock(SoftDibitMessage softDibits)
    {
        mDecodedMessage = SOFT_VITERBI_HALF_RATE_DECODER.decode(softDibits);
    }

    /**
     * Message payload
     */
    @Override
    public CorrectedBinaryMessage getMessage()
    {
        return mDecodedMessage;
    }

    /**
     * Number of bit errors detected/corrected during viterbi decoding.
     */
    @Override
    public int getBitErrorsCount()
    {
        return mDecodedMessage.getCorrectedBitCount();
    }

    /**
     * Indicates if this data block passes any block-level CRC.
     * @return true always for unconfirmed data blocks.
     */
    @Override
    public boolean isValid()
    {
        return true;
    }
}
