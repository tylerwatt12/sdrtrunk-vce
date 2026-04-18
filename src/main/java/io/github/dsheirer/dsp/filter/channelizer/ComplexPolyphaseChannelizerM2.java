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
package io.github.dsheirer.dsp.filter.channelizer;

import io.github.dsheirer.dsp.filter.FilterFactory;
import io.github.dsheirer.dsp.filter.design.FilterDesignException;
import io.github.dsheirer.sample.complex.InterleavedComplexSamples;
import io.github.dsheirer.util.Dispatcher;
import java.text.DecimalFormat;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.math3.util.FastMath;
import org.jtransforms.fft.FloatFFT_1D;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Non-Maximally Decimated Polyphase Filter Bank (NMDPFB) channelizer that divides the input baseband complex sample
 * stream into equal bandwidth channels that are each oversampled by 2x for output.
 *
 * This polyphase channelizer is based off of the channelizer described by Fred Harris in Multirate Signal
 * Processing for Communications Systems, p230-233.
 *
 * Samples are loaded into this filter one block at a time (1/2 channel count) and a filtered output is calculated
 * to produce an overall 2x oversampled channel sample rate.  Each sample block load is preceded by a serpentine
 * shift of the existing sample blocks.  We use the java System.arrayCopy() method which is able to leverage
 * native processor intrinsics for efficiency.
 *
 * The prototype filter for the channelizer is rearranged to align with the structure of the sample buffer.
 *
 * Instead of using an array of channel filters as described in the Harris text, this filter and the sample buffer
 * are arranged as a contiguous array to maximize Java's ability to leverage native processor Single Instruction
 * Multiple Data (SIMD) intrinsics (since Java 8).  The filter process is broken into four steps:
 *
 *   -Multiply the inline array of samples and filter coefficients
 *   -Accumulate the results for each sub-channel
 *   -Rearrange the sub-channel results to correctly order the sub-channels
 *   -Perform IFFT
 *
 * Note: design the prototype filter as a Nyquist windowed filter with a -6.02 db attenuation at the channel edge
 * frequency if you need Perfect Reconstruction where you'll later re-join two or more channels to form a wider
 * bandwidth channel or to isolate a signal that located between two channels.
 */
public class ComplexPolyphaseChannelizerM2 extends AbstractComplexPolyphaseChannelizer
{
    private static final Logger mLog = LoggerFactory.getLogger(ComplexPolyphaseChannelizerM2.class);
    private static final DecimalFormat DECIMAL_FORMAT = new DecimalFormat("0.0");
    private static final int DEFAULT_MINIMUM_CHANNEL_BANDWIDTH = 25000;
    /**
     * Determines how many processed channel results to dispatch for threaded IFFT processing per batch
     */
    private static final int PROCESSED_CHANNEL_RESULTS_THRESHOLD = 1024;

    //Sized to process 40 times per second
    private IFFTProcessorDispatcher mIFFTProcessorDispatcher = new IFFTProcessorDispatcher(25);
    private float[] mInlineSamples;
    private float[] mInlineFilter;
    private boolean mTopBlockIndicator = true;
    private int[] mTopBlockMap;
    private int[] mMiddleBlockMap;
    private int mSampleBufferPointer;
    private int mSamplesPerBlock;
    private int mTapsPerChannel;
    private final ConcurrentLinkedQueue<float[]> mChannelResultsPool = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<ChannelResultsBuffer> mChannelResultsBufferPool = new ConcurrentLinkedQueue<>();
    private ChannelResultsBuffer mProcessedChannelResultsBuffer = acquireChannelResultsBuffer();
    private float[] mFilterAccumulator;

    /**
     * Creates a NMDPFB channelizer instance.
     *
     * @param taps of a low-pass filter designed for the inbound sample rate with a cutoff frequency
     * equal to the channel bandwidth (sample rate / filters).  If you need to synthesize (combine two or more
     * channel outputs) a new bandwidth signal from the outputs of this filter, then the filter should be designed
     * as a nyquist filter with -6 dB attenuation at the channel bandwidth cutoff frequency
     * @param sampleRate of the incoming sample stream
     * @param channelCount - number of filters/channels to output.  Since this filter bank performs 2x oversampling for
     * each channel output, this number must be even (divisible by 2).
     */
    public ComplexPolyphaseChannelizerM2(float[] taps, int sampleRate, int channelCount)
    {
        super(sampleRate, channelCount);

        if(channelCount % 2 != 0)
        {
            throw new IllegalArgumentException("Channel count must be an even multiple of the over-sample rate (2x)");
        }

        mTapsPerChannel = (int) FastMath.ceil((double)taps.length / (double)channelCount);

        init(taps);
    }

    /**
     * Creates a NMDPFB channelizer instance and designs a Perfect Reconstruction prototype filter appropriate for
     * the baseband sample rate and quantity of filter taps per polyphase sub-channel.
     *
     * @param sampleRate to be channelized.
     * @param tapsPerChannel to use when designing the filter
     */
    public ComplexPolyphaseChannelizerM2(double sampleRate, int tapsPerChannel) throws FilterDesignException
    {
        super(sampleRate, getChannelCount(sampleRate));

        mTapsPerChannel = tapsPerChannel;

        float[] filterTaps = FilterFactory.getSincM2Channelizer(getChannelSampleRate(), getChannelCount(),
            mTapsPerChannel, false);

        init(filterTaps);
    }

    /**
     * Starts sample processing
     */
    public void start()
    {
        mIFFTProcessorDispatcher.start();
    }

    /**
     * Stops sample processing.
     */
    public void stop()
    {
        mIFFTProcessorDispatcher.flushAndStop();
    }

    /**
     * Calculates the multiple of two number of channels that can be channelized from the specified sample rate so that
     * each channel has a minimum bandwidth of the default channel bandwidth (12.5 kHz).
     * @param sampleRate to channelize
     * @return number of multiple of two channels that can be channelized.
     */
    public static int getChannelCount(double sampleRate)
    {
        int channels = (int)(sampleRate / DEFAULT_MINIMUM_CHANNEL_BANDWIDTH);

        if(channels % 2 != 0)
        {
            channels--;
        }

        mLog.info("Sample Rate [" + DECIMAL_FORMAT.format(sampleRate) + "] providing [" + channels +
            "] channels at [" + DECIMAL_FORMAT.format(sampleRate / channels) + "] Hz each");

        return channels;
    }

    /**
     * Updates this channelizer to use the new sample rate.  This method creates a new filter suitable for the
     * sample rate and reinitializes all internal data structures to prepare for processing the new sample rate.
     * @param sampleRate in hertz
     */
    @Override
    public void setRates(double sampleRate, int channelCount)
    {
        try
        {
            super.setRates(sampleRate, channelCount);
            float[] filterTaps = FilterFactory.getSincM2Channelizer(getChannelSampleRate(), getChannelCount(),
                mTapsPerChannel, false);

            init(filterTaps);
        }
        catch(FilterDesignException fde)
        {
            throw new IllegalArgumentException("Cannot create a channelizer filter for the specified sample rate [" +
                sampleRate + "]");
        }
    }

    /**
     * Receives the complex sample buffer and processes the results through the channelizer.
     */
    @Override
    public void receive(InterleavedComplexSamples complexSamples)
    {
        mCurrentSamplesTimestamp = complexSamples.timestamp();

        float[] samples = complexSamples.samples();

        int samplesPointer = 0;
        int samplesToCopy;

        while(samplesPointer < samples.length)
        {
            if(mSampleBufferPointer < mSamplesPerBlock)
            {
                samplesToCopy = mSamplesPerBlock - mSampleBufferPointer;

                int samplesDiff = samples.length - samplesPointer;
                if(samplesDiff < samplesToCopy)
                {
                    samplesToCopy = samplesDiff;
                }

                System.arraycopy(samples, samplesPointer, mInlineSamples, mSampleBufferPointer, samplesToCopy);

                mSampleBufferPointer += samplesToCopy;
                samplesPointer += samplesToCopy;
            }

            if(mSampleBufferPointer >= mSamplesPerBlock)
            {
                if(mProcessedChannelResultsBuffer.isFull())
                {
                    ChannelResultsBuffer processedChannelResults = mProcessedChannelResultsBuffer;
                    processedChannelResults.setTimestamp(mCurrentSamplesTimestamp);
                    mProcessedChannelResultsBuffer = acquireChannelResultsBuffer();
                    mIFFTProcessorDispatcher.receive(processedChannelResults);
                }

                //Filter buffered samples and produce a single sample across each of the polyphase channels
                mProcessedChannelResultsBuffer.add(process());

                //Right-shift the samples in the buffer over to make room for a new block of samples
                System.arraycopy(mInlineSamples, 0, mInlineSamples, mSamplesPerBlock, (mInlineSamples.length - mSamplesPerBlock));
                mSampleBufferPointer = 0;
            }
        }
    }

    /**
     * Creates a top-block processing accumulator map that maps each interim filter and sample index product
     * to the corresponding final output index for the array that will feed the IFFT.
     *
     * @param channelCount - number of channels
     * @return output index to filter accumulator index mapping
     */
    private static int[] getTopBlockMap(int channelCount)
    {
        int[] newMap = new int[channelCount * 2];

        //Reorder the subchannel arrays to the structure needed for top-block processing
        int blockSize = channelCount / 2;

        for(int channel = 0; channel < blockSize; channel++)
        {
            int newIndex = 2 * channel;
            int originalIndex = 2 * (blockSize - channel - 1);
            int offset = 2 * blockSize;

            newMap[originalIndex] = newIndex;
            newMap[originalIndex + 1] = newIndex + 1;
            newMap[offset + originalIndex] = offset + newIndex;
            newMap[offset + originalIndex + 1] = offset + newIndex + 1;
        }

        return newMap;
    }

    /**
     * Creates a top-block processing accumulator map that maps each interim filter and sample index product
     * to the corresponding final output index for the array that will feed the IFFT.
     *
     * @param channelCount - number of channels
     * @return output index to filter accumulator index mapping
     */
    private static int[] getMiddleBlockMap(int channelCount)
    {
        int[] newMap = new int[channelCount * 2];

        //Reorder the subchannel arrays to the structure needed for top-block processing
        int blockSize = channelCount / 2;

        for(int channel = 0; channel < blockSize; channel++)
        {
            int newIndex = 2 * channel;
            int originalIndex = 2 * (blockSize - channel - 1);
            int offset = 2 * blockSize;

            newMap[offset + originalIndex] = newIndex;
            newMap[offset + originalIndex + 1] = newIndex + 1;
            newMap[originalIndex] = offset + newIndex;
            newMap[originalIndex + 1] = offset + newIndex + 1;
        }

        return newMap;
    }

    /**
     * Rearranges the filter coefficients to align with a contiguous sample buffer for processing efficiency.
     * @param coefficients of the polyphase filter
     * @param channelCount number of channels where each channel is an I/Q pair
     * @return filter rearranged for inline sample buffer processing
     */
    private static float[] getAlignedFilter(float[] coefficients, int channelCount, int tapsPerChannel)
    {
        float[] filter = new float[channelCount * tapsPerChannel * 2];
        int blockSize = channelCount;

        int coefficientPointer = 0;
        int filterPointer = 0;

        //Create a new filter that duplicates each tap to produce an interleaved I/Q filter
        while(coefficientPointer < coefficients.length)
        {
            filter[filterPointer++] = coefficients[coefficientPointer];
            filter[filterPointer++] = coefficients[coefficientPointer++];
        }

        //Swap each of the coefficients on block size boundaries
        for(int x = 0; x < filter.length; x += blockSize)
        {
            for(int y = 0; y < blockSize / 2; y++)
            {
                int index1 = x + y;
                int index2 = x + (blockSize - y - 1);
                float temp = filter[index2];
                filter[index2] = filter[index1];
                filter[index1] = temp;
            }
        }

        return filter;
    }


    /**
     * Processes the sample buffer for each new block of sample data that is loaded and distributes the results to any
     * registered channel listeners.
     */
    private float[] process()
    {
        Arrays.fill(mFilterAccumulator, 0.0f);

        //Accumulate each sample/filter product directly into the I/Q sub-channels.
        for(int tap = 0; tap < mTapsPerChannel; tap++)
        {
            int tapOffset = tap * getSubChannelCount();

            for(int channel = 0; channel < getSubChannelCount(); channel++)
            {
                mFilterAccumulator[channel] += mInlineSamples[tapOffset + channel] * mInlineFilter[tapOffset + channel];
            }
        }

        float[] processed = acquireChannelResultsArray();

        if(mTopBlockIndicator)
        {
            for(int x = 0; x < getSubChannelCount(); x++)
            {
                processed[x] = mFilterAccumulator[mTopBlockMap[x]];
            }
        }
        else
        {
            for(int x = 0; x < getSubChannelCount(); x++)
            {
                processed[x] = mFilterAccumulator[mMiddleBlockMap[x]];
            }
        }

        mTopBlockIndicator = !mTopBlockIndicator;

        return processed;
    }

    /**
     * Initializes the channelizer filter structures.
     *
     * @param coefficients of the prototype filter for this channelizer
     */
    private void init(float[] coefficients)
    {
        //This channelizer is only reinitialized during construction or when the PolyphaseChannelManager has already
        //removed all registered channels before changing sample rate/channel count.  That contract ensures there are
        //no in-flight ChannelResultsBuffer instances still owned by downstream consumers when the pools are cleared.
        if(mProcessedChannelResultsBuffer != null)
        {
            recycleChannelResultsBuffer(mProcessedChannelResultsBuffer);
        }

        mChannelResultsPool.clear();
        mChannelResultsBufferPool.clear();
        int channelCount = getChannelCount();
        mIFFTProcessorDispatcher.setFFT(new FloatFFT_1D(channelCount));
        int bufferLength = getSubChannelCount() * mTapsPerChannel;
        mSamplesPerBlock = channelCount; //Same as subChannelCount / 2
        mTopBlockMap = getTopBlockMap(channelCount);
        mMiddleBlockMap = getMiddleBlockMap(channelCount);
        mInlineFilter = getAlignedFilter(coefficients, channelCount, mTapsPerChannel);
        mInlineSamples = new float[bufferLength];
        mFilterAccumulator = new float[getSubChannelCount()];
        mProcessedChannelResultsBuffer = acquireChannelResultsBuffer();
    }

    /**
     * Acquires a reusable processed channel results array sized for the current sub-channel count.
     */
    private float[] acquireChannelResultsArray()
    {
        float[] channelResults = mChannelResultsPool.poll();

        if(channelResults == null || channelResults.length != getSubChannelCount())
        {
            return new float[getSubChannelCount()];
        }

        return channelResults;
    }

    /**
     * Acquires a reusable batch container for processed channel results.
     */
    private ChannelResultsBuffer acquireChannelResultsBuffer()
    {
        ChannelResultsBuffer buffer = mChannelResultsBufferPool.poll();

        if(buffer == null)
        {
            buffer = new ChannelResultsBuffer(PROCESSED_CHANNEL_RESULTS_THRESHOLD, this::recycleChannelResultsBuffer);
        }

        buffer.reset();
        return buffer;
    }

    /**
     * Recycles a fully consumed channel results batch and its backing arrays into local pools.
     */
    private void recycleChannelResultsBuffer(ChannelResultsBuffer buffer)
    {
        for(int index = 0; index < buffer.size(); index++)
        {
            float[] channelResults = buffer.get(index);

            if(channelResults != null && channelResults.length == getSubChannelCount())
            {
                mChannelResultsPool.offer(channelResults);
            }
        }

        buffer.clear();
        mChannelResultsBufferPool.offer(buffer);
    }

    /**
     * Shared batch of processed channel results arrays.  The analysis channelizer produces these batches and multiple
     * downstream output processors consume them asynchronously, so recycling happens only after the last consumer
     * releases the batch.
     */
    public static class ChannelResultsBuffer
    {
        private final float[][] mChannelResults;
        private final RecycleCallback mRecycleCallback;
        private final AtomicInteger mConsumerCount = new AtomicInteger();
        private int mSize;
        private long mTimestamp;

        private ChannelResultsBuffer(int capacity, RecycleCallback recycleCallback)
        {
            mChannelResults = new float[capacity][];
            mRecycleCallback = recycleCallback;
        }

        public void add(float[] channelResults)
        {
            if(mSize >= mChannelResults.length)
            {
                throw new IllegalStateException("Channel results buffer capacity exceeded");
            }

            mChannelResults[mSize++] = channelResults;
        }

        public float[] get(int index)
        {
            return mChannelResults[index];
        }

        public int size()
        {
            return mSize;
        }

        public boolean isFull()
        {
            return mSize >= mChannelResults.length;
        }

        public long timestamp()
        {
            return mTimestamp;
        }

        void setTimestamp(long timestamp)
        {
            mTimestamp = timestamp;
        }

        void prepareForConsumers(int consumerCount)
        {
            mConsumerCount.set(consumerCount);
        }

        public void release()
        {
            if(mConsumerCount.decrementAndGet() == 0)
            {
                mRecycleCallback.recycle(this);
            }
        }

        void recycleNow()
        {
            mRecycleCallback.recycle(this);
        }

        private void reset()
        {
            mSize = 0;
            mTimestamp = 0;
            mConsumerCount.set(0);
        }

        private void clear()
        {
            Arrays.fill(mChannelResults, 0, mSize, null);
            reset();
        }
    }

    @FunctionalInterface
    private interface RecycleCallback
    {
        void recycle(ChannelResultsBuffer buffer);
    }

    /**
     * Separate threaded processor to receive and enqueue filtered channel results buffers, perform IFFT on each array
     * as required to align the phase of each polyphase channel, and then dispatch the results to any registered
     * sample consumer channels.
     */
    public class IFFTProcessorDispatcher extends Dispatcher<ChannelResultsBuffer>
    {
        private FloatFFT_1D mFFT;

        public IFFTProcessorDispatcher(long interval)
        {
            super("sdrtrunk polyphase ifft processor", interval);

            //We create a listener interface to receive the batched channel results arrays from the scheduled thread pool
            //dispatcher thread that is part of this continuous buffer processor.  We perform an IFFT on each
            //channel results array contained in each results buffer and then dispatch the buffer
            //so that it can be distributed to each channel listener.
            setListener(list -> {
                try
                {
                    for(int index = 0; index < list.size(); index++)
                    {
                        float[] channelResults = list.get(index);

                        if(channelResults != null)
                        {
                            //Rotate each of the channels to the correct phase using the IFFT
                            mFFT.complexInverse(channelResults, true);
                        }
                    }

                    dispatch(list);
                }
                catch(Throwable t)
                {
                    mLog.error("Error during IFFT and dispatch of processed channel results", t);
                }
            });
        }

        private void setFFT(FloatFFT_1D fft)
        {
            mFFT = fft;
        }
    }
}
