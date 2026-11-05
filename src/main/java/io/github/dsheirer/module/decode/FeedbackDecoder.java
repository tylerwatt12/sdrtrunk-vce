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

package io.github.dsheirer.module.decode;

import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.source.ISourceEventProvider;
import io.github.dsheirer.source.SourceEvent;
import java.util.Arrays;

/**
 * Decoder that provides feedback to signal source(s) via SourceEvent broadcasts and supports registering source
 * event listeners.
 */
public abstract class FeedbackDecoder extends PrimaryDecoder implements ISourceEventProvider
{
    private static final double TWO_PI = 2 * Math.PI;
    private Listener<SourceEvent> mSourceEventListener;
    private volatile SymbolObserver[] mSymbolObservers = new SymbolObserver[0];
    private double mPLLSampleRate;

    /**
     * Sets the rate for phase errors measured between adjacent decimated samples.
     * Symbol-interval measurements use {@link #processSymbolPLLError(float, int)} instead.
     * @param rate in hertz
     */
    protected void setDecimatedSampleRate(double rate)
    {
        if(!Double.isFinite(rate) || rate <= 0)
        {
            throw new IllegalArgumentException("PLL sample rate must be finite and positive");
        }

        mPLLSampleRate = rate;
    }

    /**
     * Protocol description suitable for display in the user interface
     * @return description
     */
    public abstract String getProtocolDescription();

    /**
     * Registers the listener to receive source events from this decoder
     */
    @Override
    public void setSourceEventListener(Listener<SourceEvent> listener)
    {
        mSourceEventListener = listener;
    }

    /**
     * Deregisters the listener from receiving source events from this decoder.
     */
    @Override
    public void removeSourceEventListener()
    {
        mSourceEventListener = null;
    }

    /**
     * Broadcasts the specified source event to the optional registered source event listener
     */
    public void broadcast(SourceEvent sourceEvent)
    {
        if(mSourceEventListener != null)
        {
            mSourceEventListener.receive(sourceEvent);
        }
    }

    /**
     * Sends a correction for phase error measured over one symbol, independent of the IQ sample rate.
     * @param pllError radians per symbol
     * @param symbolRate symbols per second
     */
    public void processSymbolPLLError(float pllError, int symbolRate)
    {
        if(symbolRate <= 0)
        {
            throw new IllegalArgumentException("PLL symbol rate must be positive");
        }

        long correctionRequestHertz = (long)(symbolRate / TWO_PI * pllError);
        broadcast(SourceEvent.frequencyCorrectionRequest(correctionRequestHertz));
    }

    /**
     * Sends a correction for phase error measured between adjacent decimated samples, as in an FM discriminator.
     * @param pllError radians per sample
     */
    public void processSamplePLLError(float pllError)
    {
        if(mPLLSampleRate <= 0)
        {
            throw new IllegalStateException("PLL sample rate has not been configured");
        }

        long correctionRequestHertz = (long)(mPLLSampleRate / TWO_PI * pllError);
        broadcast(SourceEvent.frequencyCorrectionRequest(correctionRequestHertz));
    }

    /**
     * Adds an independent symbol observer. Observers run on the decoder
     * thread and must only perform bounded, non-blocking work.
     */
    public synchronized void addSymbolObserver(SymbolObserver observer)
    {
        if(observer == null)
        {
            throw new IllegalArgumentException("Symbol observer cannot be null");
        }

        SymbolObserver[] observers = mSymbolObservers;

        for(SymbolObserver registered: observers)
        {
            if(registered == observer)
            {
                return;
            }
        }

        SymbolObserver[] updated = Arrays.copyOf(observers, observers.length + 1);
        updated[observers.length] = observer;
        mSymbolObservers = updated;
    }

    /**
     * Removes one independent symbol observer.
     */
    public synchronized void removeSymbolObserver(SymbolObserver observer)
    {
        SymbolObserver[] observers = mSymbolObservers;

        for(int x = 0; x < observers.length; x++)
        {
            if(observers[x] == observer)
            {
                SymbolObserver[] updated = new SymbolObserver[observers.length - 1];
                System.arraycopy(observers, 0, updated, 0, x);
                System.arraycopy(observers, x + 1, updated, x, observers.length - x - 1);
                mSymbolObservers = updated;
                return;
            }
        }
    }

    /**
     * Broadcasts the demodulated symbol to registered observers.
     * @param symbol in range 0 to +/- PI radians
     */
    public void broadcast(float symbol)
    {
        for(SymbolObserver observer: mSymbolObservers)
        {
            observer.receive(symbol);
        }
    }

    @FunctionalInterface
    public interface SymbolObserver
    {
        void receive(float symbol);
    }
}
