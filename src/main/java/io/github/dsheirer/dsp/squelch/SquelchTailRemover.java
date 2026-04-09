/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
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

package io.github.dsheirer.dsp.squelch;

import io.github.dsheirer.sample.Listener;
import java.util.ArrayDeque;

/**
 * Removes trailing squelch noise and optionally trims the first samples after squelch open.
 */
public class SquelchTailRemover
{
    public static final int DEFAULT_TAIL_REMOVAL_MS = 100;
    public static final int DEFAULT_HEAD_REMOVAL_MS = 0;
    public static final int MINIMUM_REMOVAL_MS = 0;
    public static final int MAXIMUM_TAIL_REMOVAL_MS = 300;
    public static final int MAXIMUM_HEAD_REMOVAL_MS = 150;
    private static final int AUDIO_SAMPLE_RATE = 8000;

    private final ArrayDeque<float[]> mDelayBuffer = new ArrayDeque<>();
    private Listener<float[]> mOutputListener;
    private int mTailRemovalSamples;
    private int mHeadSamplesRemaining;
    private int mHeadRemovalSamples;
    private int mBufferedSampleCount;

    public SquelchTailRemover(int tailRemovalMs, int headRemovalMs)
    {
        setTailRemovalMs(tailRemovalMs);
        setHeadRemovalMs(headRemovalMs);
    }

    public void setTailRemovalMs(int tailMs)
    {
        tailMs = Math.max(MINIMUM_REMOVAL_MS, Math.min(MAXIMUM_TAIL_REMOVAL_MS, tailMs));
        mTailRemovalSamples = (int)(AUDIO_SAMPLE_RATE * tailMs / 1000.0);
    }

    public void setHeadRemovalMs(int headMs)
    {
        headMs = Math.max(MINIMUM_REMOVAL_MS, Math.min(MAXIMUM_HEAD_REMOVAL_MS, headMs));
        mHeadRemovalSamples = (int)(AUDIO_SAMPLE_RATE * headMs / 1000.0);
    }

    public void setOutputListener(Listener<float[]> listener)
    {
        mOutputListener = listener;
    }

    public void squelchOpen()
    {
        mHeadSamplesRemaining = mHeadRemovalSamples;
        mDelayBuffer.clear();
        mBufferedSampleCount = 0;
    }

    public void squelchClose()
    {
        mDelayBuffer.clear();
        mBufferedSampleCount = 0;
    }

    public void process(float[] audio)
    {
        if(audio == null || audio.length == 0 || mOutputListener == null)
        {
            return;
        }

        if(mHeadSamplesRemaining > 0)
        {
            if(audio.length <= mHeadSamplesRemaining)
            {
                mHeadSamplesRemaining -= audio.length;
                return;
            }

            int keep = audio.length - mHeadSamplesRemaining;
            float[] trimmed = new float[keep];
            System.arraycopy(audio, mHeadSamplesRemaining, trimmed, 0, keep);
            audio = trimmed;
            mHeadSamplesRemaining = 0;
        }

        if(mTailRemovalSamples <= 0)
        {
            mOutputListener.receive(audio);
            return;
        }

        mDelayBuffer.addLast(audio);
        mBufferedSampleCount += audio.length;

        while(mBufferedSampleCount > mTailRemovalSamples && !mDelayBuffer.isEmpty())
        {
            float[] oldest = mDelayBuffer.peekFirst();

            if(oldest == null)
            {
                break;
            }

            if(mBufferedSampleCount - oldest.length >= mTailRemovalSamples)
            {
                mDelayBuffer.pollFirst();
                mBufferedSampleCount -= oldest.length;
                mOutputListener.receive(oldest);
            }
            else
            {
                int samplesToOutput = mBufferedSampleCount - mTailRemovalSamples;

                if(samplesToOutput > 0 && samplesToOutput < oldest.length)
                {
                    float[] output = new float[samplesToOutput];
                    float[] keep = new float[oldest.length - samplesToOutput];
                    System.arraycopy(oldest, 0, output, 0, samplesToOutput);
                    System.arraycopy(oldest, samplesToOutput, keep, 0, keep.length);

                    mDelayBuffer.pollFirst();
                    mDelayBuffer.addFirst(keep);
                    mBufferedSampleCount -= samplesToOutput;
                    mOutputListener.receive(output);
                }

                break;
            }
        }
    }
}
