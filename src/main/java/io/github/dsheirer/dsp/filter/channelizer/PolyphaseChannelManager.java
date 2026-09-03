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
package io.github.dsheirer.dsp.filter.channelizer;

import io.github.dsheirer.buffer.INativeBuffer;
import io.github.dsheirer.buffer.INativeBufferProvider;
import io.github.dsheirer.controller.channel.event.ChannelStopProcessingRequest;
import io.github.dsheirer.dsp.filter.design.FilterDesignException;
import io.github.dsheirer.dsp.filter.channelizer.output.ChannelOutputProcessor;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.log.LoggingSuppressor;
import io.github.dsheirer.sample.Broadcaster;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.complex.InterleavedComplexSamples;
import io.github.dsheirer.source.ISourceEventProcessor;
import io.github.dsheirer.source.SourceEvent;
import io.github.dsheirer.source.SourceException;
import io.github.dsheirer.source.tuner.TunerController;
import io.github.dsheirer.source.tuner.channel.TunerChannel;
import io.github.dsheirer.source.tuner.channel.TunerChannelSource;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.commons.math3.util.FastMath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Polyphase Channel Manager is a DDC channel manager and complex buffer queue/processor for a tuner.  This class
 * provides DDC polyphase channel sources and wraps a polyphase channelizer processing sample buffers produced by
 * the tuner and distributing channelized sample buffers to each allocated DDC polyphase channel source.  This
 * class is responsible for monitoring the tuner for changes in center frequency and/or sample rate and updating
 * active DDC polyphase channel sources accordingly.  This class also monitors source event requests and
 * notifications received from active DDC polyphase channel sources to adjust sample streams as required.
 *
 * Channel bandwidth and channel count are determined by the sample rate of the baseband buffer stream provider.  This
 * class is currently designed to provide channels each with a minimum usable bandwidth of 12.5 kHz and oversampled by
 * 2.0 to a minimum of 25.0 kHz channel sample rate.  If the baseband stream provider sample rate is not evenly
 * divisible by 12.5 kHz channels for an even number of channels, the channel bandwidth will be increased.
 *
 * Note: add this channel manager as a source event listener to the complex buffer provider to ensure this manager
 * adapts to changes in source frequency and sample rate.
 */
public class PolyphaseChannelManager implements ISourceEventProcessor
{
    private static final DecimalFormat FREQUENCY_FORMAT = new DecimalFormat("0.00000");
    private static final LoggingSuppressor LOGGING_SUPPRESSOR = new LoggingSuppressor(LoggerFactory.getLogger(PolyphaseChannelManager.class));
    private static final Logger mLog = LoggerFactory.getLogger(PolyphaseChannelManager.class);
    private static final double MINIMUM_CHANNEL_BANDWIDTH = 25000.0;
    private static final double CHANNEL_OVERSAMPLING = 2.0;
    private static final int POLYPHASE_CHANNELIZER_TAPS_PER_CHANNEL = 9;

    private Broadcaster<SourceEvent> mSourceEventBroadcaster = new Broadcaster<>();
    private INativeBufferProvider mNativeBufferProvider;
    private List<PolyphaseChannelSource> mChannelSources = new CopyOnWriteArrayList<>();
    private ChannelCalculator mChannelCalculator;
    private SynthesisFilterManager mFilterManager = new SynthesisFilterManager();
    private volatile ComplexPolyphaseChannelizerM2 mPolyphaseChannelizer;
    private ChannelSourceEventListener mChannelSourceEventListener = new ChannelSourceEventListener();
    private NativeBufferReceiver mNativeBufferReceiver = new NativeBufferReceiver();
    private NativeBufferProcessor mBufferProcessor;
    private final Object mChannelizerLock = new Object();
    private Map<Integer,float[]> mOutputProcessorFilters = new HashMap<>();
    private TunerController mTunerController;
    private volatile boolean mRunning = true;
    private final AtomicLong mRetiredChannelOutputDrops = new AtomicLong();
    private final AtomicLong mChannelLifecycleVersion = new AtomicLong();
    private final AtomicLong mLastStableChannelOutputDrops = new AtomicLong();
    private final AtomicLong mRetiredIfftDrops = new AtomicLong();
    private final AtomicLong mIfftLifecycleVersion = new AtomicLong();
    private final AtomicLong mLastStableIfftDrops = new AtomicLong();
    private volatile ComplexPolyphaseChannelizerM2.QueueStatus mLastStableIfftQueueStatus =
        emptyIfftQueueStatus();

    /**
     * Creates a polyphase channel manager instance.
     *
     * @param nativeBufferProvider (ie tuner) that supports register/deregister for reusable baseband sample buffer
     * streams
     * @param frequency of the baseband complex buffer sample stream (ie center frequency)
     * @param sampleRate of the baseband complex buffer sample stream
     */
    public PolyphaseChannelManager(INativeBufferProvider nativeBufferProvider, long frequency, double sampleRate)
    {
        if(nativeBufferProvider == null)
        {
            throw new IllegalArgumentException("Complex buffer provider argument cannot be null");
        }

        mNativeBufferProvider = nativeBufferProvider;

        int channelCount = (int)(sampleRate / MINIMUM_CHANNEL_BANDWIDTH);

        //Ensure channel count is an even integer since we're using a 2x oversampling polyphase channelizer
        if(channelCount % 2 != 0)
        {
            channelCount--;
        }

        mChannelCalculator = new ChannelCalculator(sampleRate, channelCount, frequency, CHANNEL_OVERSAMPLING);
        mBufferProcessor = new NativeBufferProcessor("sdrtrunk polyphase buffer processor", sampleRate,
            mNativeBufferReceiver);
    }

    /**
     * Creates a polyphase channel manager for the tuner controller
     *
     * @param tunerController for a tuner that provides a baseband complex buffer stream.
     */
    public PolyphaseChannelManager(TunerController tunerController)
    {
        this(tunerController, tunerController.getFrequency(), tunerController.getSampleRate());
        mTunerController = tunerController;
    }

    /** Creates a channelizer replacement.  Package visibility supports deterministic lifecycle regression tests. */
    ComplexPolyphaseChannelizerM2 createChannelizer(double sampleRate) throws FilterDesignException
    {
        return new ComplexPolyphaseChannelizerM2(sampleRate, POLYPHASE_CHANNELIZER_TAPS_PER_CHANNEL);
    }

    /** Creates a tentative channel source before its shutdown-safe admission check. */
    PolyphaseChannelSource createChannelSource(TunerChannel tunerChannel, String threadName)
    {
        return new PolyphaseChannelSource(tunerChannel, mChannelCalculator, mFilterManager,
            mChannelSourceEventListener, threadName,
            mTunerController != null ? mTunerController.getTunerFrequencyErrorManager() : null);
    }

    /**
     * Provides a description of the state of this manager.
     */
    public String getStateDescription()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("Polyphase Channel Manager Providing [").append(mChannelSources.size()).append("] Channels");
        sb.append("\n\t").append(mChannelCalculator);
        for(PolyphaseChannelSource pcs: mChannelSources)
        {
            List<Integer> indexes = pcs.getOutputProcessorIndexes();
            double sampleRate = pcs.getSampleRate();
            long indexCenterFrequency = pcs.getIndexCenterFrequency();
            long appliedFrequencyOffset = pcs.getFrequencyOffset();
            long requestedCenterFrequency = pcs.getFrequency();

            sb.append("\n\tPolyphase | Tuner SR:").append(FREQUENCY_FORMAT.format(pcs.getTunerSampleRate() / 1E6d));
            sb.append(" CF:").append(FREQUENCY_FORMAT.format(pcs.getTunerCenterFrequency() / 1E6d));
            sb.append(" BW: ").append(FREQUENCY_FORMAT.format(sampleRate / 1E6d));
            sb.append(" | Channel CF: ").append(FREQUENCY_FORMAT.format(indexCenterFrequency / 1E6d));
            sb.append(" REQUESTED CF: ").append(FREQUENCY_FORMAT.format(requestedCenterFrequency / 1E6d));
            sb.append(" MIXER:").append(FREQUENCY_FORMAT.format(appliedFrequencyOffset / 1E6d));
            sb.append(" | Polyphase Indices: ").append(indexes);
            sb.append(" HASH:").append(Integer.toHexString(pcs.hashCode()).toUpperCase());
        }

        return sb.toString();
    }

    /** Requests a bounded receiver IQ queue duration without acquiring the receiver queue lock. */
    public void requestNativeBufferQueueDuration(long durationMilliseconds)
    {
        mBufferProcessor.requestMaximumQueueDurationMilliseconds(durationMilliseconds);
    }

    /** Read-only approximate queue counters for administrator diagnostics. */
    public NativeBufferQueueStatus getNativeBufferQueueStatus()
    {
        NativeBufferProcessor.QueueStatus status = mBufferProcessor.status();
        return new NativeBufferQueueStatus(status.appliedDurationMilliseconds(),
            status.requestedDurationMilliseconds(), status.queuedSamples(), status.queuedMilliseconds(),
            status.highWaterSamples(), status.highWaterMilliseconds(), status.droppedBuffers(),
            status.droppedSamples(), status.droppedMilliseconds());
    }

    public record NativeBufferQueueStatus(long appliedDurationMilliseconds, long requestedDurationMilliseconds,
                                          long queuedSamples, long queuedMilliseconds, long highWaterSamples,
                                          long highWaterMilliseconds, long droppedBuffers,
                                          long droppedSamples, long droppedMilliseconds)
    {
    }

    /**
     * Immutable, off-thread receiver pipeline snapshot.  The channel source list is copy-on-write and each queue
     * exposes only atomic or volatile counters, so this never acquires a receiver-path lock.
     */
    public PipelineStatus getPipelineStatus()
    {
        ComplexPolyphaseChannelizerM2.QueueStatus ifft = getIfftQueueStatus();
        List<ChannelQueueStatus> channels = List.of();
        long channelDroppedBatches = mLastStableChannelOutputDrops.get();

        for(int attempt = 0; attempt < 4; attempt++)
        {
            long before = mChannelLifecycleVersion.get();

            if((before & 1L) != 0)
            {
                Thread.onSpinWait();
                continue;
            }

            List<ChannelQueueStatus> candidate = mChannelSources.stream().map(source ->
            {
                ChannelOutputProcessor.QueueStatus queue = source.getOutputQueueStatus();
                return new ChannelQueueStatus(source.getFrequency(), queue.queuedBatches(), queue.highWaterBatches(),
                    queue.capacityBatches(), queue.droppedBatches());
            }).toList();
            long retired = mRetiredChannelOutputDrops.get();
            long after = mChannelLifecycleVersion.get();

            if(before == after && (after & 1L) == 0)
            {
                channels = candidate;
                long stableTotal = retired + candidate.stream().mapToLong(ChannelQueueStatus::droppedBatches).sum();
                channelDroppedBatches = mLastStableChannelOutputDrops.accumulateAndGet(stableTotal, Math::max);
                break;
            }
        }

        return new PipelineStatus(ifft.queuedBatches(), ifft.highWaterBatches(), ifft.capacityBatches(),
            ifft.droppedBatches(), channelDroppedBatches, channels);
    }

    /** Returns a monotonic channelizer-pipeline drop total without locking the receiver lifecycle. */
    private ComplexPolyphaseChannelizerM2.QueueStatus getIfftQueueStatus()
    {
        for(int attempt = 0; attempt < 4; attempt++)
        {
            long before = mIfftLifecycleVersion.get();

            if((before & 1L) != 0)
            {
                Thread.onSpinWait();
                continue;
            }

            ComplexPolyphaseChannelizerM2 channelizer = mPolyphaseChannelizer;
            long retired = mRetiredIfftDrops.get();
            ComplexPolyphaseChannelizerM2.QueueStatus current = channelizer != null ? channelizer.getQueueStatus() :
                emptyIfftQueueStatus();
            long after = mIfftLifecycleVersion.get();

            if(before == after && (after & 1L) == 0)
            {
                long drops = mLastStableIfftDrops.accumulateAndGet(retired + current.droppedBatches(), Math::max);
                ComplexPolyphaseChannelizerM2.QueueStatus stable = new ComplexPolyphaseChannelizerM2.QueueStatus(
                    current.queuedBatches(), current.highWaterBatches(), current.capacityBatches(), drops);
                mLastStableIfftQueueStatus = stable;
                return stable;
            }
        }

        ComplexPolyphaseChannelizerM2.QueueStatus cached = mLastStableIfftQueueStatus;
        long drops = mLastStableIfftDrops.get();

        if(cached.droppedBatches() >= drops)
        {
            return cached;
        }

        return new ComplexPolyphaseChannelizerM2.QueueStatus(cached.queuedBatches(), cached.highWaterBatches(),
            cached.capacityBatches(), Math.max(cached.droppedBatches(), drops));
    }

    private static ComplexPolyphaseChannelizerM2.QueueStatus emptyIfftQueueStatus()
    {
        return new ComplexPolyphaseChannelizerM2.QueueStatus(0, 0, 0, 0);
    }

    public record PipelineStatus(int ifftQueuedBatches, int ifftHighWaterBatches, int ifftCapacityBatches,
                                 long ifftDroppedBatches, long channelDroppedBatches,
                                 List<ChannelQueueStatus> channels)
    {
    }

    public record ChannelQueueStatus(long frequencyHz, int queuedBatches, int highWaterBatches,
                                     int capacityBatches, long droppedBatches)
    {
    }

    public void stopAllChannels()
    {
        List<TunerChannelSource> toStop;

        synchronized(mChannelizerLock)
        {
            //Close channel admission before taking the stop snapshot.  getChannel() performs its final admission
            //check under this same lock, so a source is either included here or rejected and disposed there.
            mRunning = false;
            toStop = new ArrayList<>(mChannelSources);
        }

        for(TunerChannelSource tunerChannelSource: toStop)
        {
            MyEventBus.getGlobalEventBus().post(new ChannelStopProcessingRequest(tunerChannelSource));
        }
    }

    /**
     * Releases this manager's tuner listener and processing thread.
     */
    public void dispose()
    {
        mRunning = false;

        synchronized(mChannelizerLock)
        {
            mNativeBufferProvider.removeBufferListener(mBufferProcessor);
            mBufferProcessor.dispose();
        }

        mSourceEventBroadcaster.clear();
    }

    /**
     * Signals to all provisioned tuner channel sources that the source complex buffer provider has an error and can
     * no longer provide channels, so that the tuner channel source can notify the consumer of the error state.
     */
    public void setErrorMessage(String errorMessage)
    {
        for(TunerChannelSource tunerChannelSource: mChannelSources)
        {
            tunerChannelSource.setError(errorMessage);
        }
    }

    /**
     * Current channel bandwidth/spacing.
     */
    public double getChannelBandwidth()
    {
        return mChannelCalculator.getChannelBandwidth();
    }

    /**
     * Provides a Digital Drop Channel (DDC) for the specified tuner channel or returns null if the channel can't be
     * sourced due to the current center frequency and/or sample rate.
     * @param tunerChannel specifying center frequency and bandwidth.
     * @param threadName for the channel's dispatcher
     * @return source or null.
     */
    public TunerChannelSource getChannel(TunerChannel tunerChannel, String threadName)
    {
        PolyphaseChannelSource channelSource = null;

        if(mRunning)
        {
            try
            {
                channelSource = createChannelSource(tunerChannel, threadName);

                synchronized(mChannelizerLock)
                {
                    if(mRunning)
                    {
                        mChannelSources.add(channelSource);
                    }
                    else
                    {
                        channelSource.dispose();
                        channelSource = null;
                    }
                }
            }
            catch(IllegalArgumentException iae)
            {
                LOGGING_SUPPRESSOR.error(iae.getMessage(), 3, "Couldn't allocate channel. " + iae.getMessage());
                channelSource = null;
            }
        }

        return channelSource;
    }

    /**
     * Stops/removes the channel source from receiving channelized sample buffers and deregisters from the tuner
     * when this is the last channel being sourced.
     *
     * @param channelSource to stop
     */
    private void stopChannelSource(PolyphaseChannelSource channelSource)
    {
        channelSource.stopOutputProcessorForRemoval();
        int channelCount;

        synchronized(mChannelizerLock)
        {
            mChannelLifecycleVersion.incrementAndGet();

            try
            {
                if(mChannelSources.remove(channelSource))
                {
                    mRetiredChannelOutputDrops.addAndGet(channelSource.getDroppedBatchCount());
                }

                if(mPolyphaseChannelizer != null)
                {
                    mPolyphaseChannelizer.removeChannel(channelSource);
                }

                channelCount = getTunerChannelCount();

                //If this is the last/only channel, deregister to stop the sample buffers
                if(mPolyphaseChannelizer != null && mPolyphaseChannelizer.getRegisteredChannelCount() == 0)
                {
                    mNativeBufferProvider.removeBufferListener(mBufferProcessor);
                    mBufferProcessor.stop();
                    mPolyphaseChannelizer.stop();
                }
            }
            finally
            {
                mChannelLifecycleVersion.incrementAndGet();
            }
        }

        //Listener callbacks can acquire the tuner controller lock.  Broadcast only after releasing the channelizer
        //lock so channel allocation (tuner -> channelizer) and removal can never deadlock in opposite lock order.
        mSourceEventBroadcaster.broadcast(SourceEvent.channelCountChange(channelCount));

        try
        {
            //Broadcast a stop sample stream notification in case this was a forced-stop so consumers are aware
            channelSource.process(SourceEvent.stopSampleStreamNotification(channelSource));
        }
        catch(SourceException _)
        {
            //Do nothing
        }
    }

    /**
     * Process source events received from the source (ie tuner controller) for frequency and sample rate change
     * notifications.
     * @param sourceEvent to process
     * @throws SourceException
     */
    @Override
    public void process(SourceEvent sourceEvent) throws SourceException
    {
        switch(sourceEvent.getEvent())
        {
            case NOTIFICATION_FREQUENCY_CHANGE:
                mNativeBufferReceiver.receive(sourceEvent);
                break;
            case NOTIFICATION_SAMPLE_RATE_CHANGE:
                //Update channel calculator immediately so that channels can be allocated
                double sampleRate = sourceEvent.getValue().doubleValue();
                mBufferProcessor.setSampleRate(sampleRate);
                int channelCount = ComplexPolyphaseChannelizerM2.getChannelCount(sampleRate);
                mChannelCalculator.setRates(sampleRate, channelCount);
                break;
              case NOTIFICATION_FREQUENCY_AND_SAMPLE_RATE_LOCKED,
                  NOTIFICATION_FREQUENCY_AND_SAMPLE_RATE_UNLOCKED,
                  NOTIFICATION_MEASURED_FREQUENCY_ERROR,
                  NOTIFICATION_RECORDING_FILE_LOADED:
                //no-op
                break;
            case NOTIFICATION_FREQUENCY_CORRECTION_CHANGE:
                //Re-broadcast this event to each channel so that the decoders can reset error tracking function(s).
                List<TunerChannelSource> channels = new ArrayList<>(mChannelSources);
                for(TunerChannelSource channel: channels)
                {
                    channel.broadcastConsumerSourceEvent(sourceEvent);
                }
                break;
            default:
                mLog.info("Unrecognized source event: {}", sourceEvent);
                break;
        }
    }

    /**
     * Sorted set of currently sourced tuner channels being provided by this channel manager.  The set is ordered by
     * frequency (low to high).
     */
    public SortedSet<TunerChannel> getTunerChannels()
    {
        SortedSet<TunerChannel> tunerChannels = new TreeSet<>();

        for(PolyphaseChannelSource channelSource: mChannelSources)
        {
            tunerChannels.add(channelSource.getTunerChannel());
        }

        return tunerChannels;
    }

    /**
     * Count of currently sourced tuner channels
     */
    public int getTunerChannelCount()
    {
        return mChannelSources.size();
    }

    /**
     * Adds the listener to receive source events
     */
    public void addSourceEventListener(Listener<SourceEvent> listener)
    {
        mSourceEventBroadcaster.addListener(listener);
    }

    /**
     * Removes the listener from receiving source events
     */
    public void removeSourceEventListener(Listener<SourceEvent> listener)
    {
        mSourceEventBroadcaster.removeListener(listener);
    }

    /**
     * Internal class for handling requests for start/stop sample stream from polyphase channel sources
     */
    private class ChannelSourceEventListener implements Listener<SourceEvent>
    {
        /**
         * Creates or updates the channelizer to process the incoming sample rate and updates any channel processors.
         *
         * Note: this method should only be invoked on the mBufferProcessor thread or prior to starting the mBufferProcessor.
         * Sample rate source events will normally arrive via the incoming complex buffer stream from the mBufferProcessor
         * and will be handled as they arrive.
         */
        private boolean checkChannelizerConfiguration()
        {
            //Channel calculator is always in sync with the tuner's current sample rate
            double tunerSampleRate = mChannelCalculator.getSampleRate();

            //If the channelizer is not setup, or setup to the wrong sample rate, recreate it
            if(mPolyphaseChannelizer == null || FastMath.abs(mPolyphaseChannelizer.getSampleRate() - tunerSampleRate) > 0.5)
            {
                if(mPolyphaseChannelizer != null && mPolyphaseChannelizer.getRegisteredChannelCount() > 0)
                {
                    throw new IllegalStateException("Polyphase Channelizer cannot be changed to a new sample rate while " +
                        "channels are currently sourced.  Ensure you remove all tuner channels before changing tuner " +
                        "sample rate.  Current channel count:" +
                        mPolyphaseChannelizer.getRegisteredChannelCount());
                }

                mIfftLifecycleVersion.incrementAndGet();

                try
                {
                    ComplexPolyphaseChannelizerM2 previous = mPolyphaseChannelizer;
                    ComplexPolyphaseChannelizerM2 replacement =
                        PolyphaseChannelManager.this.createChannelizer(tunerSampleRate);

                    if(previous != null)
                    {
                        previous.stop();
                        mRetiredIfftDrops.addAndGet(previous.getDroppedBatchCount());
                    }

                    //Publish the complete replacement once so an in-flight native buffer can never observe null or
                    //switch channelizers midway through its iterator.
                    mPolyphaseChannelizer = replacement;
                    //Clear previous channel synthesis filters only after a replacement was successfully published.
                    mOutputProcessorFilters.clear();
                }
                catch(IllegalArgumentException iae)
                {
                    mLog.error("Could not create polyphase channelizer for sample rate [" + tunerSampleRate + "]", iae);
                    return false;
                }
                catch(FilterDesignException fde)
                {
                    mLog.error("Could not create filter for polyphase channelizer for sample rate [" + tunerSampleRate + "]", fde);
                    return false;
                }
                finally
                {
                    mIfftLifecycleVersion.incrementAndGet();
                }
            }

            return mPolyphaseChannelizer != null &&
                FastMath.abs(mPolyphaseChannelizer.getSampleRate() - tunerSampleRate) <= 0.5;
        }

        /**
         * Starts/adds the channel source to receive channelized sample buffers, registering with the tuner to receive
         * sample buffers when this is the first channel.
         *
         * @param channelSource to start
         */
        private void startChannelSource(PolyphaseChannelSource channelSource)
        {
            int channelCount;

            synchronized(mChannelizerLock)
            {
                //Start and stop requests can cross while a one-shot source is being removed.  Never register a stale
                //source that has already been retired from this manager or has entered terminal shutdown.
                if(!mRunning || !mChannelSources.contains(channelSource) || channelSource.isOutputProcessorStopping())
                {
                    return;
                }

                //Note: the polyphase channel source has already been added to the mChannelSources in getChannel() method
                if(!checkChannelizerConfiguration())
                {
                    //Mark this one-shot source terminal before returning to PolyphaseChannelSource.start().  Its
                    //post-start check then issues the normal stop request, which removes/disposes the source and also
                    //stops its per-channel frequency-error manager without ever starting the output processor.
                    channelSource.stopOutputProcessorForRemoval();
                    return;
                }

                mPolyphaseChannelizer.addChannel(channelSource);
                channelCount = getTunerChannelCount();

                //If this is the first channel, register to start the sample buffers flowing
                if(mPolyphaseChannelizer.getRegisteredChannelCount() == 1)
                {
                    mPolyphaseChannelizer.start();
                    mBufferProcessor.start();
                    mNativeBufferProvider.addBufferListener(mBufferProcessor);
                }
            }

            //Keep external listener work outside mChannelizerLock.  Source allocation callers can already hold the
            //tuner controller lock, so invoking a listener here while locked would recreate the reverse lock order.
            mSourceEventBroadcaster.broadcast(SourceEvent.channelCountChange(channelCount));
        }

        @Override
        public void receive(SourceEvent sourceEvent)
        {
            switch(sourceEvent.getEvent())
            {
                case REQUEST_START_SAMPLE_STREAM:
                    if(sourceEvent.hasSource() && sourceEvent.getSource() instanceof PolyphaseChannelSource polyphasechannelsource)
                    {
                        startChannelSource(polyphasechannelsource);
                    }
                    else
                    {
                        mLog.error("Request to start sample stream for unrecognized source: {}",
                            (sourceEvent.hasSource() ? sourceEvent.getSource().getClass() : "null source"));
                    }
                    break;
                case REQUEST_STOP_SAMPLE_STREAM:
                    if(sourceEvent.hasSource() && sourceEvent.getSource() instanceof PolyphaseChannelSource polyphasechannelsource)
                    {
                        stopChannelSource(polyphasechannelsource);
                        polyphasechannelsource.dispose();
                    }
                    else
                    {
                        mLog.error("Request to stop sample stream for unrecognized source: {}",
                            (sourceEvent.hasSource() ? sourceEvent.getSource().getClass() : "null source"));
                    }
                    break;
                default:
                    mLog.error("Received unrecognized source event from polyphase channel source [{}]",
                        sourceEvent.getEvent());
                    break;
            }
        }
    }

    /**
     * Processes the incoming buffer stream from the provider and transfers the buffers to the polyphase channelizer.
     *
     * This monitor incorporates a source event handler that queues a center frequency update so that it can be
     * handled on the buffer processing thread, avoiding having to lock on the output processor thread.  Since we
     * anticipate that these two threads will contend for access to this update required flag, we use an update lock
     * to protect access to the flag.
     */
    public class NativeBufferReceiver implements Listener<INativeBuffer>
    {
        private boolean mOutputProcessorUpdateRequired = false;

        /**
         * Updates each of the output processors for any changes in the tuner's center frequency or sample rate, which
         * would cause the output processors to change the polyphase channelizer results channel(s) that the processor
         * is consuming.
         */
        private void updateOutputProcessors()
        {
            for(PolyphaseChannelSource channelSource: mChannelSources)
            {
                try
                {
                    channelSource.updateOutputProcessor(mChannelCalculator, mFilterManager);
                }
                catch(IllegalArgumentException _)
                {
                    mLog.error("Error updating polyphase channel source output processor following tuner frequency or sample rate change");
                    stopChannelSource(channelSource);
                }
            }
        }

        /**
         * Processes tuner center frequency change source events to flag when output processors need updating.
         * @param event that affects configuration of the channelizer (frequency or sample rate change events)
         */
        public void receive(SourceEvent event)
        {
            long frequency = event.getValue().longValue();

            if(mChannelCalculator.getCenterFrequency() != frequency)
            {
                //Update the channel calculator frequency so that it's ready when the output processor update occurs
                mChannelCalculator.setCenterFrequency(frequency);
                mOutputProcessorUpdateRequired = true;
            }
        }

        /**
         * Process native buffer streams and update polyphase output channels when the parent tuner center
         * frequency changes.
         * @param nativeBuffer of sample to process.
         */
        @Override
        public void receive(INativeBuffer nativeBuffer)
        {
            if(mOutputProcessorUpdateRequired)
            {
                try
                {
                    updateOutputProcessors();
                }
                catch(Exception _)
                {
                    mLog.error("Error updating polyphase channel output processors");
                }
                mOutputProcessorUpdateRequired = false;
            }

            ComplexPolyphaseChannelizerM2 channelizer = mPolyphaseChannelizer;
            long generation = mIfftLifecycleVersion.get();

            if(channelizer != null && (generation & 1L) == 0)
            {
                Iterator<InterleavedComplexSamples> iterator = nativeBuffer.iteratorInterleaved();

                while(iterator.hasNext())
                {
                    if(mIfftLifecycleVersion.get() != generation || mPolyphaseChannelizer != channelizer)
                    {
                        //A sample-rate replacement occurred during this native buffer.  Do not feed its tail into a
                        //different channelizer generation; the old stopped dispatcher safely recycles any late batch.
                        break;
                    }

                    try
                    {
                        channelizer.receive(iterator.next());
                    }
                    catch(Exception exception)
                    {
                        mLog.error("Error", exception);
                    }
                }
            }
        }
    }
}
