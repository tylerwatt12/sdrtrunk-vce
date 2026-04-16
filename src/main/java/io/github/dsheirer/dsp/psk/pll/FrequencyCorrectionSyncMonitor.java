/*
 * *****************************************************************************
 * Copyright (C) 2014-2025 Dennis Sheirer
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
package io.github.dsheirer.dsp.psk.pll;

import io.github.dsheirer.dsp.symbol.ISyncDetectListener;
import io.github.dsheirer.module.decode.FeedbackDecoder;
import io.github.dsheirer.source.SourceEvent;

/**
 * Monitors decode sync events to adaptively control frequency correction broadcasts.
 *
 * The original author tested adaptive PLL bandwidth and deliberately removed it (commit b3b2e746, Feb 2020),
 * citing extensive testing, but retained the PLLBandwidth infrastructure (fromSyncCount mapping, MAX_SYNC_COUNT)
 * without wiring the bandwidth updates. A ComplexGainControl AGC was also present in the P25P2 signal path during
 * that earlier testing. Since that AGC introduced per-buffer gain changes ahead of the Costas loop, those results
 * may not generalize cleanly to the current decoder path with the AGC removed. This implementation starts from the
 * same fixed BW_300 baseline he settled on: bandwidth narrows from BW_300 (acquisition) to BW_200 (tracking)
 * after two consecutive sync detections, and resets to BW_300 on each decoder reset.
 */
public class FrequencyCorrectionSyncMonitor implements ISyncDetectListener, IFrequencyErrorProcessor
{
    private static final int MAX_SYNC_COUNT = PLLBandwidth.BW_200.getRangeEnd();
    private FeedbackDecoder mFeedbackDecoder;
    private CostasLoop mCostasLoop;
    private int mSyncCount;
    private PLLBandwidth mCurrentBandwidth = PLLBandwidth.BW_300;

    /**
     * Constructs an adaptive monitor to monitor the sync state of a decoder
     *
     * @param costasLoop to receive adaptive gain updates.
     * @param feedbackDecoder to rebroadcast frequency error source events
     */
    public FrequencyCorrectionSyncMonitor(CostasLoop costasLoop, FeedbackDecoder feedbackDecoder)
    {
        mCostasLoop = costasLoop;
        mCostasLoop.setFrequencyErrorProcessor(this);
        mFeedbackDecoder = feedbackDecoder;
    }

    /**
     * Sync detection event.  Updates the running sync count and updates the PLL gain level.
     */
    @Override
    public void syncDetected(int bitErrors)
    {
        mSyncCount++;
        update();
    }

    /**
     * Sync loss event.  Updates the running sync count and updates the PLL gain level.
     */
    @Override
    public void syncLost(int bitsProcessed)
    {
        mSyncCount -= 2;
        update();
    }

    /**
     * Updates the sync count and adjusts PLL bandwidth adaptively.
     */
    private void update()
    {
        if(mSyncCount < 0)
        {
            mSyncCount = 0;
        }
        else if(mSyncCount > MAX_SYNC_COUNT)
        {
            mSyncCount = MAX_SYNC_COUNT;
        }

        PLLBandwidth bandwidth = PLLBandwidth.fromSyncCount(mSyncCount);

        if(bandwidth != mCurrentBandwidth)
        {
            mCurrentBandwidth = bandwidth;
            mCostasLoop.setPLLBandwidth(bandwidth);
        }
    }

    /**
     * Resets the monitor and restores acquisition bandwidth.
     */
    public void reset()
    {
        mSyncCount = 0;
        mCurrentBandwidth = PLLBandwidth.BW_300;
        mCostasLoop.setPLLBandwidth(PLLBandwidth.BW_300);
    }

    public PLLBandwidth getCurrentBandwidth()
    {
        return mCurrentBandwidth;
    }

    @Override
    public void processFrequencyError(long frequencyError)
    {
        mFeedbackDecoder.broadcast(SourceEvent.carrierOffsetMeasurement(-frequencyError));

        //Only rebroadcast as a frequency error measurement if the sync count is more than 2
        if(mSyncCount > 2)
        {
            mFeedbackDecoder.broadcast(SourceEvent.frequencyErrorMeasurement(frequencyError));
        }
    }
}
