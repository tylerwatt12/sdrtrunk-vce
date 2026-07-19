/*
 * *****************************************************************************
 * Copyright (C) 2014-2023 Dennis Sheirer
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
package io.github.dsheirer.spectrum;

import io.github.dsheirer.buffer.INativeBuffer;
import io.github.dsheirer.controller.NamingThreadFactory;
import io.github.dsheirer.dsp.window.WindowFactory;
import io.github.dsheirer.dsp.window.WindowType;
import io.github.dsheirer.preference.spectrum.SpectrumPreference;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.spectrum.converter.DFTResultsConverter;
import java.io.IOException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jtransforms.fft.FloatFFT_1D;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Processes both complex samples or float samples and dispatches a float array of DFT results, using configurable fft
 * size and output dispatch timelines.
 */
public class ComplexDftProcessor implements Listener<INativeBuffer>, IDFTWidthChangeProcessor, AutoCloseable
{
    private static final Logger mLog = LoggerFactory.getLogger(ComplexDftProcessor.class);
    //The Cosine and Hann windows seem to offer the best spectral display with minimal bin leakage/smearing
    private WindowType mWindowType = WindowType.BLACKMAN_HARRIS_7;
    private float[] mWindow;
    private DFTSize mDFTSize = DFTSize.FFT04096;
    private DFTSize mNewDFTSize = DFTSize.FFT04096;
    private FloatFFT_1D mFFT = new FloatFFT_1D(mDFTSize.getSize());
    private int mFrameRate;
    private final SpectrumPreference mSpectrumPreference;
    private AtomicBoolean mRunning = new AtomicBoolean();
    private final AtomicBoolean mDisposed = new AtomicBoolean();
    private ScheduledFuture<?> mProcessorTaskHandle;
    private ScheduledExecutorService mExecutorService = Executors.newSingleThreadScheduledExecutor(new NamingThreadFactory("sdrtrunk dft processor"));
    private CopyOnWriteArrayList<DFTResultsConverter> mListeners = new CopyOnWriteArrayList<>();
    private NativeBufferManager<INativeBuffer> mDftBufferManager = new NativeBufferManager<>(mDFTSize.getSize());
    private float[] mCurrentSamples = new float[mDFTSize.getSize() * 2];
    private float[] mPreviousSamples = new float[mDFTSize.getSize() * 2];

    public ComplexDftProcessor()
    {
        this(null);
    }

    public ComplexDftProcessor(SpectrumPreference spectrumPreference)
    {
        mSpectrumPreference = spectrumPreference;
        mFrameRate = spectrumPreference != null ? spectrumPreference.getFrameRate() : 20;
        setWindowType(mWindowType);
        start();
    }

    public void dispose()
    {
        if(!mDisposed.compareAndSet(false, true))
        {
            return;
        }

        stop();
        mExecutorService.shutdownNow();

        try
        {
            if(!mExecutorService.awaitTermination(5, TimeUnit.SECONDS))
            {
                mLog.warn("DFT processor executor did not terminate within five seconds");
            }
        }
        catch(InterruptedException exception)
        {
            Thread.currentThread().interrupt();
        }

        mListeners.clear();
        // Do not clear the buffer manager here. A producer callback that entered receive() just before disposal can
        // still be completing its non-blocking enqueue; the now-unreachable manager and its bounded buffers are
        // reclaimed with this processor without racing that latency-sensitive callback.
        mWindow = null;
    }

    @Override
    public void close()
    {
        dispose();
    }

    public WindowType getWindowType()
    {
        return mWindowType;
    }

    public void setWindowType(WindowType windowType)
    {
        mWindowType = windowType;
        updateWindow();
    }

    private void updateWindow()
    {
        mWindow = WindowFactory.getWindow(mWindowType, mDFTSize.getSize() * 2);
    }

    /**
     * Queues an FFT size change request.  The scheduled executor will apply
     * the change when it runs.
     */
    public void setDFTSize(DFTSize size)
    {
        mNewDFTSize = size;
    }

    public DFTSize getDFTSize()
    {
        return mDFTSize;
    }

    public int getFrameRate()
    {
        return mFrameRate;
    }

    public void setFrameRate(int framesPerSecond)
    {
        if(framesPerSecond < 1 || framesPerSecond > 1000)
        {
            throw new IllegalArgumentException("DFTProcessor cannot run more than 1000 times per second -- requested " +
                "setting:" + framesPerSecond);
        }

        mFrameRate = framesPerSecond;
        if(mSpectrumPreference != null)
        {
            mSpectrumPreference.setFrameRate(mFrameRate);
        }
        restart();
    }

    public void start()
    {
        if(mDisposed.get())
        {
            throw new IllegalStateException("Cannot restart a disposed DFT processor");
        }

        if(mProcessorTaskHandle == null)
        {
            //Schedule the DFT to run calculations at a fixed rate
            int initialDelay = 0;
            int period = (1000 / mFrameRate);

            mProcessorTaskHandle = mExecutorService.scheduleAtFixedRate(new DFTCalculationTask(), initialDelay, period,
                TimeUnit.MILLISECONDS);
        }
    }

    public void stop()
    {
        //Cancel running DFT calculation task
        if(mProcessorTaskHandle != null)
        {
            mProcessorTaskHandle.cancel(false);
            mProcessorTaskHandle = null;
        }
    }

    public boolean isRunning()
    {
        return mProcessorTaskHandle != null;
    }

    public void restart()
    {
        stop();
        start();
    }

    /**
     * Places the sample into a transfer queue for future processing.
     */
    @Override
    public void receive(INativeBuffer buffer)
    {
        if(!mDisposed.get())
        {
            mDftBufferManager.add(buffer);
        }
    }

    public void addConverter(DFTResultsConverter listener)
    {
        mListeners.add(listener);
    }

    private class DFTCalculationTask implements Runnable
    {
        /**
         * Checks for a queued FFT width change request and applies it. This method is only accessed by the scheduled
         * executor that gains access to run a calculate method, thus providing thread safety.
         */
        private void checkFFTSize()
        {
            if(mNewDFTSize.getSize() != mDFTSize.getSize())
            {
                mDFTSize = mNewDFTSize;
                updateWindow();
                mFFT = new FloatFFT_1D(mDFTSize.getSize());
                mCurrentSamples = new float[mDFTSize.getSize() * 2];
                mPreviousSamples = new float[mDFTSize.getSize() * 2];
            }
        }

        /**
         * Takes a calculated DFT results set, reformats the data, and sends it
         * out to all registered listeners.
         */
        private void dispatch(float[] results)
        {
            for(DFTResultsConverter mListener: mListeners)
            {
                mListener.receive(results);
            }
        }

        private void calculate()
        {
            //We always send the previous calculated samples - this should improve the screen rendering since the frame
            //rate will always occur on an even rhythm.  Any delays caused by processing will be absorbed and not impact
            //the screen rendering.
            dispatch(mPreviousSamples);

            try
            {
                //If this throws an IO exception, the buffer queue is (temporarily) empty and we return from the method
                mDftBufferManager.get(mDFTSize.getSize(), mCurrentSamples);
                WindowFactory.apply(mWindow, mCurrentSamples);
                mFFT.complexForward(mCurrentSamples);
                float[] completedSamples = mPreviousSamples;
                mPreviousSamples = mCurrentSamples;
                mCurrentSamples = completedSamples;
            }
            catch(IOException ioe)
            {
                //Not enough samples available, dispatch the previous samples again
            }
            catch(Exception e)
            {
                if(hasInterruptedExceptionCause(e))
                {
                    mLog.info("FFT Library interrupted exception - this is normal during application shutdown");
                }
                else
                {
                    mLog.error("Error while calculating FFT results", e);
                }
            }
        }

        private boolean hasInterruptedExceptionCause(Throwable throwable)
        {
            Throwable current = throwable;

            while(current != null)
            {
                if(current instanceof InterruptedException)
                {
                    return true;
                }

                current = current.getCause();
            }

            return false;
        }

        @Override
        public void run()
        {
            boolean acquired = false;

            try
            {
				/* Only run if we're not currently running */
                if(mRunning.compareAndSet(false, true))
                {
                    acquired = true;
                    checkFFTSize();
                    calculate();
                }
            }
            catch(Exception e)
            {
                mLog.error("error during dft processor calculation task", e);
            }
            finally
            {
                if(acquired)
                {
                    mRunning.set(false);
                }
            }
        }
    }

    public void clearBuffer()
    {
        mDftBufferManager.clear();
    }

    public boolean isExecutorTerminated()
    {
        return mExecutorService.isTerminated();
    }
}
