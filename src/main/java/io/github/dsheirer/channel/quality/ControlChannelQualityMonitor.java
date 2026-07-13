/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.channel.quality;

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.message.DroppedSamplesMessage;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.message.IMessageListener;
import io.github.dsheirer.message.SyncLossMessage;
import io.github.dsheirer.module.Module;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.TSBKMessage;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.MacMessage;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.source.ISourceEventListener;
import io.github.dsheirer.source.SourceEvent;
import io.github.dsheirer.source.heartbeat.Heartbeat;
import io.github.dsheirer.source.heartbeat.IHeartbeatListener;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;

/**
 * Collects low-cost P25 control-channel signal and decode measurements on the processing-chain thread.
 */
public class ControlChannelQualityMonitor extends Module implements IMessageListener, ISourceEventListener,
    IHeartbeatListener
{
    static final long PUBLISH_INTERVAL_MILLISECONDS = 1000;
    static final long ROLLING_WINDOW_MILLISECONDS = 30000;
    private static final int P25_PHASE_1_FRAME_BITS = 196;
    private static final int P25_PHASE_2_FRAME_BITS = 320;

    private final Channel mChannel;
    private final String mGuid;
    private final Consumer<ControlChannelQualitySnapshot> mConsumer;
    private final boolean mPhase2;
    private final Deque<Bucket> mBuckets = new ArrayDeque<>();
    private Bucket mCurrent = new Bucket();
    private long mFrequency;
    private long mLastPublish;
    private long mLastValidDecode;
    private long mLastLcchKey = Long.MIN_VALUE;
    private double mSignalDbfs = Double.NaN;
    private boolean mRunning;

    public ControlChannelQualityMonitor(Channel channel, long initialFrequency,
                                        Consumer<ControlChannelQualitySnapshot> consumer)
    {
        mChannel = channel;
        mGuid = channel != null && channel.isStandardChannel() ? channel.getRadresGuid() : null;
        mFrequency = initialFrequency;
        mConsumer = consumer;
        DecoderType decoderType = channel != null && channel.getDecodeConfiguration() != null ?
            channel.getDecodeConfiguration().getDecoderType() : null;
        mPhase2 = decoderType == DecoderType.P25_PHASE2;
    }

    @Override
    public Listener<IMessage> getMessageListener()
    {
        return this::receive;
    }

    @Override
    public Listener<SourceEvent> getSourceEventListener()
    {
        return this::receive;
    }

    @Override
    public Listener<Heartbeat> getHeartbeatListener()
    {
        return heartbeat -> publishIfDue(System.currentTimeMillis());
    }

    private synchronized void receive(SourceEvent event)
    {
        if(event == null)
        {
            return;
        }

        switch(event.getEvent())
        {
            case NOTIFICATION_CHANNEL_POWER ->
            {
                if(event.getValue() != null)
                {
                    mSignalDbfs = event.getValue().doubleValue();
                    mCurrent.addPower(mSignalDbfs);
                }
            }
            case NOTIFICATION_FREQUENCY_CHANGE, NOTIFICATION_FREQUENCY_ROTATION_SUCCESS ->
            {
                if(event.getValue() != null && event.getValue().longValue() > 0 &&
                    event.getValue().longValue() != mFrequency)
                {
                    publishInactive();
                    mFrequency = event.getValue().longValue();
                    clearWindow();
                }
            }
            default -> { }
        }
    }

    private synchronized void receive(IMessage message)
    {
        if(message instanceof TSBKMessage tsbk)
        {
            countFrame(tsbk.isValid(), Math.max(0, tsbk.getMessage().getCorrectedBitCount()), tsbk.getTimestamp());
        }
        else if(message instanceof MacMessage mac && mac.getDataUnitID().isLCCH())
        {
            long key = mac.getTimestamp() * 4 + mac.getTimeslot();

            if(key != mLastLcchKey)
            {
                mLastLcchKey = key;
                countFrame(mac.isValid(), Math.max(0, mac.getBitErrorCount()), mac.getTimestamp());
            }
        }
        else if(message instanceof SyncLossMessage syncLoss)
        {
            mCurrent.syncLossBits += Math.max(0, syncLoss.getBitsProcessed());
        }
        else if(message instanceof DroppedSamplesMessage dropped)
        {
            mCurrent.droppedBits += Math.max(0, dropped.getBitsDropped());
        }
    }

    private void countFrame(boolean valid, int correctedBits, long timestamp)
    {
        if(valid)
        {
            mCurrent.validFrames++;
            mLastValidDecode = Math.max(mLastValidDecode, timestamp);
        }
        else
        {
            mCurrent.invalidFrames++;
        }

        mCurrent.correctedBits += correctedBits;
    }

    synchronized void publishIfDue(long now)
    {
        if(!mRunning || now - mLastPublish < PUBLISH_INTERVAL_MILLISECONDS)
        {
            return;
        }

        mCurrent.endedAt = now;
        mBuckets.addLast(mCurrent);
        mCurrent = new Bucket();
        mLastPublish = now;
        long cutoff = now - ROLLING_WINDOW_MILLISECONDS;

        while(!mBuckets.isEmpty() && mBuckets.peekFirst().endedAt < cutoff)
        {
            mBuckets.removeFirst();
        }

        publish(true, now);
    }

    private void publish(boolean active, long now)
    {
        if(mConsumer == null || mFrequency <= 0)
        {
            return;
        }

        long powerCount = 0;
        double powerSum = 0;
        double minimum = Double.POSITIVE_INFINITY;
        double maximum = Double.NEGATIVE_INFINITY;
        long valid = 0;
        long invalid = 0;
        long corrected = 0;
        long syncLoss = 0;
        long dropped = 0;

        for(Bucket bucket: mBuckets)
        {
            powerCount += bucket.powerCount;
            powerSum += bucket.powerLinearSum;
            minimum = Math.min(minimum, bucket.minimumSignalDbfs);
            maximum = Math.max(maximum, bucket.maximumSignalDbfs);
            valid += bucket.validFrames;
            invalid += bucket.invalidFrames;
            corrected += bucket.correctedBits;
            syncLoss += bucket.syncLossBits;
            dropped += bucket.droppedBits;
        }

        int frameBits = mPhase2 ? P25_PHASE_2_FRAME_BITS : P25_PHASE_1_FRAME_BITS;
        double attempted = valid + invalid + (double)(syncLoss + dropped) / frameBits;
        Double health = attempted > 0 ? Math.max(0.0, Math.min(100.0, 100.0 * valid / attempted)) : null;
        Double average = powerCount > 0 ? 10.0 * Math.log10(powerSum / powerCount) : null;
        Double min = powerCount > 0 ? minimum : null;
        Double max = powerCount > 0 ? maximum : null;
        Double current = Double.isFinite(mSignalDbfs) ? mSignalDbfs : null;
        mConsumer.accept(new ControlChannelQualitySnapshot(mChannel, mGuid, mFrequency, now, active, current,
            average, min, max, health, valid, invalid, corrected, syncLoss, dropped, mLastValidDecode));
    }

    private void publishInactive()
    {
        if(mFrequency > 0)
        {
            publish(false, System.currentTimeMillis());
        }
    }

    private void clearWindow()
    {
        mBuckets.clear();
        mCurrent = new Bucket();
        mLastValidDecode = 0;
        mLastLcchKey = Long.MIN_VALUE;
        mSignalDbfs = Double.NaN;
        mLastPublish = 0;
    }

    @Override
    public synchronized void reset()
    {
        clearWindow();
    }

    @Override
    public synchronized void start()
    {
        mRunning = true;
        mLastPublish = System.currentTimeMillis();
    }

    @Override
    public synchronized void stop()
    {
        if(mRunning)
        {
            mRunning = false;
            publishInactive();
            clearWindow();
        }
    }

    private static class Bucket
    {
        private long endedAt;
        private long powerCount;
        private double powerLinearSum;
        private double minimumSignalDbfs = Double.POSITIVE_INFINITY;
        private double maximumSignalDbfs = Double.NEGATIVE_INFINITY;
        private long validFrames;
        private long invalidFrames;
        private long correctedBits;
        private long syncLossBits;
        private long droppedBits;

        private void addPower(double dbfs)
        {
            if(Double.isFinite(dbfs))
            {
                powerCount++;
                powerLinearSum += Math.pow(10.0, dbfs / 10.0);
                minimumSignalDbfs = Math.min(minimumSignalDbfs, dbfs);
                maximumSignalDbfs = Math.max(maximumSignalDbfs, dbfs);
            }
        }
    }
}
