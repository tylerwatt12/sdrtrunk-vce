/*
 * *****************************************************************************
 * Copyright (C) 2014-2024 Dennis Sheirer
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

package io.github.dsheirer.module.decode.p25.phase1.message.lc.motorola;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.message.TimeslotMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.lc.LinkControlWord;
import io.github.dsheirer.protocol.Protocol;
import java.util.HashMap;
import java.util.Map;

/**
 * Reassembles a P25 Motorola talker alias from a Header and Data Blocks.
 *
 * This is used for traffic channels and monitors:
 * - Phase 1: link control messaging stream.
 * - Phase 2: mac messaging
 */
public class LCMotorolaTalkerAliasAssembler
{
    private static final int DATA_BLOCK_FRAGMENT_LENGTH = 44;
    private static final int MAXIMUM_BLOCK_COUNT =
        (MotorolaTalkerAliasPayload.MAXIMUM_PAYLOAD_BITS + DATA_BLOCK_FRAGMENT_LENGTH - 1) / DATA_BLOCK_FRAGMENT_LENGTH;
    private static final long MAXIMUM_ASSEMBLY_MILLISECONDS = 10_000;
    private LCMotorolaTalkerAliasHeader mHeader;
    private Map<Integer, LCMotorolaTalkerAliasDataBlock> mDataBlocks = new HashMap<>();
    private int mSequence = -1;
    private long mMostRecentTimestamp;
    private long mHeaderTimestamp;

    /**
     * Clears any partial alias assembled from a previous RF source.
     */
    public void reset()
    {
        mHeader = null;
        mDataBlocks.clear();
        mSequence = -1;
        mMostRecentTimestamp = 0;
        mHeaderTimestamp = 0;
    }

    /**
     * Link control word to process.
     * @param lcw to add
     * @return true if we can (now) assemble a complete talker alias from the header and data blocks.
     */
    public boolean add(LinkControlWord lcw, long timestamp)
    {
        switch(lcw)
        {
            case LCMotorolaTalkerAliasHeader header:
            {
                //The sequence nibble wraps. A header always starts a new fragment lifetime.
                reset();
                if(header.getMessage().size() < 72 || header.getBlockCount() < 1 ||
                    header.getBlockCount() > MAXIMUM_BLOCK_COUNT || header.getFormatValue() != 1) return false;
                mHeader = header;
                mSequence = header.getSequence();
                mHeaderTimestamp = timestamp;
                mMostRecentTimestamp = timestamp;
                break;
            }
            case LCMotorolaTalkerAliasDataBlock block:
            {
                if(mHeader != null && timestamp - mHeaderTimestamp > MAXIMUM_ASSEMBLY_MILLISECONDS)
                {
                    reset();
                    return false;
                }
                if(mHeader == null || block.getSequence() != mSequence || timestamp < mHeaderTimestamp ||
                    block.getMessage().size() < 72 || block.getBlockNumber() < 1 ||
                    block.getBlockNumber() > mHeader.getBlockCount())
                {
                    return false;
                }
                LCMotorolaTalkerAliasDataBlock previous = mDataBlocks.get(block.getBlockNumber());
                if(previous != null && !previous.getFragment().equals(block.getFragment()))
                {
                    reset();
                    return false;
                }
                mMostRecentTimestamp = Math.max(mMostRecentTimestamp, timestamp);
                mDataBlocks.put(block.getBlockNumber(), block);
                break;
            }
            default:
                return false; //For all lcw's that are not headers or data blocks
        }

        return isComplete();
    }

    /**
     * Indicates if the assembler has a header and the correct number of data blocks to reassemble an alias.
     */
    private boolean isComplete()
    {
        if(mHeader != null)
        {
            int dataBlockCount = mHeader.getBlockCount();

            for(int x = 1; x <= dataBlockCount; x++)
            {
                if(!mDataBlocks.containsKey(x))
                {
                    return false;
                }
            }

            return true;
        }

        return false;
    }

    /**
     * Assembles the talker alias once the header and data blocks have been collected.
     *
     * Note: do not invoke unless the add() methods indicate that the assembler is complete.
     * @return assembled talker alias
     * @throws IllegalStateException if the assembler can't assemble the alias.
     */
    public MotorolaTalkerAliasComplete assemble() throws IllegalStateException
    {
        if(!isComplete())
        {
            throw new IllegalStateException("Can't assemble talker alias - missing header or data block(s)");
        }

        int dataBlockCount = mHeader.getBlockCount();
        CorrectedBinaryMessage reassembled = new CorrectedBinaryMessage(dataBlockCount * DATA_BLOCK_FRAGMENT_LENGTH);

        int offset = 0;

        for(int x = 1; x <= dataBlockCount; x++)
        {
            LCMotorolaTalkerAliasDataBlock block = mDataBlocks.get(x);
            reassembled.load(offset, block.getFragment());
            offset += DATA_BLOCK_FRAGMENT_LENGTH;
        }

        MotorolaTalkerAliasComplete complete = new MotorolaTalkerAliasComplete(reassembled, mHeader.getTalkgroup(),
                mHeader.getSequence(), TimeslotMessage.TIMESLOT_0, mMostRecentTimestamp, Protocol.APCO25);

        //   Data:  wwwww sss iiiiii aaaa...aaaa cccc
        //
        //   - w = WACN
        //   - s = system
        //   - i = id
        //   - a = encoded alias
        //   - c = CRC-16/GSM of the previous bytes
        reset();
        return complete;
    }

}
