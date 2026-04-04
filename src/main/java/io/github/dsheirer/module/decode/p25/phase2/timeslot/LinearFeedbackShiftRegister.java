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

package io.github.dsheirer.module.decode.p25.phase2.timeslot;

import io.github.dsheirer.bits.BinaryMessage;
import io.github.dsheirer.bits.BitSetFullException;

/**
 * APCO-25 Phase II Linear Feedback Shift Register for generating the scrambling sequence for descrambling
 * a Phase II inbound or outbound channel.
 */
public class LinearFeedbackShiftRegister
{

    private static long MASK = 0xFFFFFFFFFFFl;
    private static long TAP_43 = (1l << 43);
    private static long TAP_33 = (1l << 33);
    private static long TAP_19 = (1l << 19);
    private static long TAP_14 = (1l << 14);
    private static long TAP_8 = (1l << 8);
    private static long TAP_3 = (1l << 3);

    private long mRegisters;
    private int mWacn;
    private int mSystem;
    private int mNac;

    public void updateSeed(int wacn, int system, int nac)
    {
        mWacn = wacn;
        mSystem = system;
        mNac = nac;

        mRegisters = (long)(0xFFFFF & wacn) << 24;
        mRegisters += (0xFFF & system) << 12;
        mRegisters += (0xFFF & nac);

        if(mRegisters == 0)
        {
            mRegisters = 0xFFFFFFFFFFFl;
        }
    }

    /**
     * Loads the seed value directly into the registers.
     * @param seed for the lfsr
     */
    public void updateSeed(long seed)
    {
        mRegisters = seed;
    }

    /**
     * Indicates if the shift register is currently configured for the argument values (and doesn't need updating).
     *
     * @param wacn value
     * @param system value
     * @param nac value
     * @return true if the argument values match the current shift register configuration
     */
    public boolean isCurrent(int wacn, int system, int nac)
    {
        return mWacn == wacn && mSystem == system && mNac == nac;
    }

    /**
     * Generates a 4320 bit (de)scrambling sequence for APCO25 Phase II channel superframe.
     *
     * @param wacn for the network from the Network Status Broadcast message.
     * @param system for the network from the Network Status Broadcast message.
     * @param nac or color code for the network from the Network Status Broadcast message.
     * @return scrambling sequence in a binary message
     */
    public BinaryMessage generateScramblingSequence(int wacn, int system, int nac)
    {
        updateSeed(wacn, system, nac);

        BinaryMessage sequence = new BinaryMessage(4320);

        try
        {
            for(int x = 0; x < 4320; x++)
            {
                sequence.add(next());
            }
        }
        catch(BitSetFullException e)
        {
            //This shouldn't happen
        }

        return sequence;
    }

    /**
     * Generates a (de)scrambling sequence for the specified seed and length
     * @param seed value to use
     * @param length of the generated sequence
     * @return scrambling sequence in a binary message
     */
    public BinaryMessage generateScramblingSequence(long seed, int length)
    {
        updateSeed(seed);
        BinaryMessage sequence = new BinaryMessage(length);

        try
        {
            for(int x = 0; x < length; x++)
            {
                sequence.add(next());
            }
        }
        catch(BitSetFullException e)
        {
            //This shouldn't happen
        }

        return sequence;
    }

    /**
     * Provides the next output bit from the LFSR
     */
    public boolean next()
    {
        boolean retVal = getTap(TAP_43);

        boolean feedback = retVal;

        feedback ^= getTap(TAP_33);
        feedback ^= getTap(TAP_19);
        feedback ^= getTap(TAP_14);
        feedback ^= getTap(TAP_8);
        feedback ^= getTap(TAP_3);
        mRegisters <<= 1;
        mRegisters &= MASK;

        if(feedback)
        {
            mRegisters++;
        }

        return retVal;
    }

    /**
     * Provides the boolean value of the specified tap position
     */
    private boolean getTap(long tap)
    {
        return (mRegisters & tap) == tap;
    }
}
