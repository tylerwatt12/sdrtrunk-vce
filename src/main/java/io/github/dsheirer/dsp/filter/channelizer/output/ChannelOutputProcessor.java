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
package io.github.dsheirer.dsp.filter.channelizer.output;

import io.github.dsheirer.dsp.filter.channelizer.ComplexPolyphaseChannelizerM2;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.source.heartbeat.HeartbeatManager;
import io.github.dsheirer.util.Dispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class ChannelOutputProcessor implements IPolyphaseChannelOutputProcessor
{
    private static final Logger mLog = LoggerFactory.getLogger(ChannelOutputProcessor.class);

    private Dispatcher<ComplexPolyphaseChannelizerM2.ChannelResultsBuffer> mChannelResultsDispatcher;
    private HeartbeatManager mHeartbeatManager;
    protected Listener<ComplexSamples> mComplexSamplesListener;
    private int mInputChannelCount;

    /**
     * Base class for polyphase channelizer output channel processing.  Provides built-in frequency translation
     * oscillator support to apply frequency correction to the channel sample stream as requested by sample consumer.
     *
     * @param inputChannelCount is the number of input channels for this output processor
     * @param heartbeatManager to receive pings on the dispatcher thread
     * @param threadName for the dispatcher
     */
    protected ChannelOutputProcessor(int inputChannelCount, HeartbeatManager heartbeatManager, String threadName)
    {
        mInputChannelCount = inputChannelCount;
        //Process 1/10th of the sample rate per second at a rate of 20 times a second (200% of anticipated rate)
        mHeartbeatManager = heartbeatManager;
        mChannelResultsDispatcher = new Dispatcher<>(threadName,20, mHeartbeatManager);
        mChannelResultsDispatcher.setListener(channelResultsBuffer -> {
            try
            {
                process(channelResultsBuffer);
            }
            catch(Exception e)
            {
                mLog.error("Error processing channel results", e);
            }
            finally
            {
                channelResultsBuffer.release();
            }
        });
    }

    @Override
    public void start()
    {
        mChannelResultsDispatcher.start();
    }

    @Override
    public void stop()
    {
        mChannelResultsDispatcher.flushAndStop();
    }

    /**
     * Registers the listener to receive the assembled complex sample buffers from this processor.
     */
    @Override
    public void setListener(Listener<ComplexSamples> listener)
    {
        mComplexSamplesListener = listener;
    }

    @Override
    public int getPolyphaseChannelIndexCount()
    {
        return mInputChannelCount;
    }

    public void dispose()
    {
    }

    @Override
    public void receiveChannelResults(ComplexPolyphaseChannelizerM2.ChannelResultsBuffer channelResultsBuffer)
    {
        if(mChannelResultsDispatcher.isRunning())
        {
            mChannelResultsDispatcher.receive(channelResultsBuffer);
        }
        else
        {
            channelResultsBuffer.release();
        }
    }

    /**
     * Sub-class implementation to process one polyphase channelizer result array.
     * @param channelResultsBuffer to process
     */
    public abstract void process(ComplexPolyphaseChannelizerM2.ChannelResultsBuffer channelResultsBuffer);

    @Override
    public int getInputChannelCount()
    {
        return getPolyphaseChannelIndexCount();
    }
}
