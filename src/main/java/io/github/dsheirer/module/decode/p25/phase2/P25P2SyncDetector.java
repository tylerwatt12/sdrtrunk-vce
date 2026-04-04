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
package io.github.dsheirer.module.decode.p25.phase2;

import io.github.dsheirer.bits.MultiSyncPatternMatcher;
import io.github.dsheirer.bits.SoftSyncDetector;
import io.github.dsheirer.dsp.symbol.Dibit;
import io.github.dsheirer.dsp.symbol.FrameSync;
import io.github.dsheirer.dsp.symbol.ISyncDetectListener;
import io.github.dsheirer.sample.Listener;

public class P25P2SyncDetector implements Listener<Dibit>
{

    /* Determines the threshold for sync pattern soft matching */
    private static final int SYNC_MATCH_THRESHOLD = 4;

    public static final double DEFAULT_SYMBOL_RATE = 6000;

    private MultiSyncPatternMatcher mMatcher;
    private SoftSyncDetector mPrimarySyncDetector;
    public P25P2SyncDetector(ISyncDetectListener syncDetectListener)
    {
        //TODO: since we're only going to feed dibits to find next frame, it makes sense to
        //TODO: update the sync lost parameter to 48 bits ....
        mMatcher = new MultiSyncPatternMatcher(syncDetectListener, 1440, 40);
        mPrimarySyncDetector = new SoftSyncDetector(FrameSync.P25_PHASE2_NORMAL.getSync(), SYNC_MATCH_THRESHOLD, syncDetectListener);
        mMatcher.add(mPrimarySyncDetector);
    }

    /**
     * Calculates the number of bits that match in the current primary detector
     * @return
     */
    public int getPrimarySyncMatchErrorCount()
    {
        return Long.bitCount(mMatcher.getCurrentValue() ^ FrameSync.P25_PHASE2_NORMAL.getSync());
    }

    @Override
    public void receive(Dibit dibit)
    {
        mMatcher.receive(dibit.getBit1(), dibit.getBit2());
    }

    /**
     * Updates the incoming sample stream sample rate to allow the PLL phase inversion detectors to
     * recalculate their internal phase correction values.
     *
     * @param sampleRate of the incoming sample stream
     */
    public void setSampleRate(double sampleRate)
    {
        //No-op: the active sync detectors in this class do not depend on sample rate.
    }
}
