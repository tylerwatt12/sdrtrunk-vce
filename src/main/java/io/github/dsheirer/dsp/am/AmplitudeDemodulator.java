/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.dsp.am;

import io.github.dsheirer.dsp.fm.IDemodulator;

/**
 * AM envelope detector. DC removal, squelch, filtering, gain and resampling are owned by the shared analog decoder.
 */
public class AmplitudeDemodulator implements IDemodulator
{
    @Override
    public float[] demodulate(float[] i, float[] q)
    {
        if(i.length != q.length)
        {
            throw new IllegalArgumentException("I and Q sample buffers must have the same length");
        }

        float[] demodulated = new float[i.length];

        for(int x = 0; x < i.length; x++)
        {
            demodulated[x] = (float)Math.sqrt((i[x] * i[x]) + (q[x] * q[x]));
        }

        return demodulated;
    }
}
