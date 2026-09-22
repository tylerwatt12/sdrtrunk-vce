/*
 * Copyright (C) 2026 Dennis Sheirer
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.dsheirer.stats;

import io.github.dsheirer.module.decode.analog.DecodeConfigAnalog.Bandwidth;
import io.github.dsheirer.module.decode.nbfm.DecodeConfigNBFM;
import io.github.dsheirer.module.decode.nbfm.NBFMDecoder;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.source.SourceEvent;
import io.github.dsheirer.source.tuner.Tuner;
import io.github.dsheirer.source.tuner.channel.TunerChannel;
import io.github.dsheirer.source.tuner.channel.TunerChannelSource;
import io.github.dsheirer.source.tuner.manager.PolyphaseChannelSourceManager;
import io.github.dsheirer.util.concurrent.BoundedSpscReferenceQueue;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * One demand-owned, current-center-only NBFM listening session.  Tuner callbacks only offer to bounded queues;
 * demodulation and PCM encoding are confined to the diagnostic worker.
 */
final class FrequencyListenService implements AutoCloseable
{
    private final TunerDiagnosticService mTuners;
    private Session mActive;

    FrequencyListenService(TunerDiagnosticService tuners)
    {
        mTuners = Objects.requireNonNull(tuners);
    }

    synchronized Session open(String targetId, long frequencyHz, int bandwidthHz)
    {
        if(mActive != null)
        {
            throw new IllegalStateException("A frequency listener is already in use");
        }

        Bandwidth bandwidth = bandwidthFor(bandwidthHz);

        Tuner tuner = mTuners.tunerForTarget(targetId);

        if(tuner == null || !(tuner.getChannelSourceManager() instanceof PolyphaseChannelSourceManager manager))
        {
            throw new IllegalStateException("Selected tuner is unavailable for live listening");
        }

        long center = tuner.getTunerController().getFrequency();
        long halfUsable = tuner.getTunerController().getUsableBandwidth() / 2L;
        long centerExclusion = tuner.getTunerController().getMiddleUnusableHalfBandwidth();

        if(!withinCurrentWindow(frequencyHz, center, bandwidthHz, halfUsable, centerExclusion))
        {
            throw new IllegalArgumentException("Frequency is outside this tuner's usable receiver window");
        }

        DecodeConfigNBFM config = new DecodeConfigNBFM();
        config.setBandwidth(bandwidth);
        TunerChannel channel = new TunerChannel(frequencyHz, bandwidthHz);
        TunerChannelSource source = manager.getSourceAtCurrentCenter(channel, config.getChannelSpecification(),
            "web frequency listen");

        if(source == null)
        {
            throw new IllegalStateException("The tuner cannot source this frequency without retuning");
        }

        try
        {
            Session session = new Session(source, new NBFMDecoder(config));
            mActive = session;
            session.start();
            return session;
        }
        catch(RuntimeException exception)
        {
            source.stop();
            mActive = null;
            throw exception;
        }
    }

    static Bandwidth bandwidthFor(int bandwidthHz)
    {
        return switch(bandwidthHz)
        {
            case 6250 -> Bandwidth.BW_6_25;
            case 12500 -> Bandwidth.BW_12_5;
            case 20000 -> Bandwidth.BW_20_0;
            case 25000 -> Bandwidth.BW_25_0;
            default -> throw new IllegalArgumentException("Unsupported NBFM bandwidth");
        };
    }

    static boolean withinCurrentWindow(long frequencyHz, long center, int bandwidthHz, long halfUsable,
                                       long centerExclusion)
    {
        long halfChannel = bandwidthHz / 2L;
        long offset = Math.abs(frequencyHz - center);
        return frequencyHz > 0 && center > 0 && bandwidthHz > 0 && halfUsable >= halfChannel &&
            offset + halfChannel <= halfUsable &&
            (centerExclusion <= 0 || offset - halfChannel >= centerExclusion);
    }

    @Override
    public synchronized void close()
    {
        if(mActive != null)
        {
            mActive.close();
        }
    }

    final class Session implements AutoCloseable
    {
        private final TunerChannelSource mSource;
        private final NBFMDecoder mDecoder;
        private final BoundedSpscReferenceQueue<ComplexSamples> mSamples = new BoundedSpscReferenceQueue<>(8);
        private final AtomicReference<SourceEvent> mLatestEvent = new AtomicReference<>();
        private final DiagnosticFrameQueue mFrames = new DiagnosticFrameQueue();
        private final AtomicBoolean mClosed = new AtomicBoolean();
        private final AtomicLong mSequence = new AtomicLong();
        private final Thread mWorker;

        Session(TunerChannelSource source, NBFMDecoder decoder)
        {
            mSource = source;
            mDecoder = decoder;
            mWorker = new Thread(this::drain, "web frequency listen decoder");
            mWorker.setDaemon(true);
            mSource.setListener(samples -> mSamples.offer(samples));
            mSource.setSourceEventListener(event -> {
                if(event.getEvent() == SourceEvent.Event.NOTIFICATION_SAMPLE_RATE_CHANGE)
                {
                    mLatestEvent.set(event);
                }
            });
            mDecoder.setBufferListener(audio -> {
                if(!mClosed.get() && audio != null && audio.length > 0)
                {
                    mFrames.offer(DiagnosticStreamFrame.pcm16(1, mSequence.incrementAndGet(),
                        System.currentTimeMillis(), 8000, audio));
                }
            });
        }

        void start()
        {
            mWorker.start();
            mSource.start();
        }

        private void drain()
        {
            try
            {
                mDecoder.getSourceEventListener().receive(SourceEvent.sampleRateChange(mSource.getSampleRate()));

                while(!mClosed.get())
                {
                    SourceEvent event = mLatestEvent.getAndSet(null);

                    if(event != null)
                    {
                        mDecoder.getSourceEventListener().receive(event);
                    }

                    ComplexSamples samples = mSamples.poll();

                    if(samples != null)
                    {
                        mDecoder.receive(samples);
                    }
                    else
                    {
                        LockSupport.parkNanos(5_000_000L);
                    }
                }
            }
            catch(RuntimeException exception)
            {
                close();
            }
        }

        DiagnosticStreamFrame poll(Duration timeout) throws InterruptedException
        {
            return mFrames.poll(timeout);
        }

        @Override
        public void close()
        {
            if(mClosed.compareAndSet(false, true))
            {
                mSource.setListener(null);
                mSource.removeSourceEventListener();
                mSource.stop();
                mWorker.interrupt();
                mLatestEvent.set(null);
                mFrames.close();
                synchronized(FrequencyListenService.this)
                {
                    if(mActive == this)
                    {
                        mActive = null;
                    }
                }
            }
        }
    }
}
