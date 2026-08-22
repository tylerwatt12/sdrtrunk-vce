/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.channel.metadata.activity;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.channel.metadata.ChannelMetadata;
import io.github.dsheirer.channel.metadata.ChannelMetadataField;
import io.github.dsheirer.channel.state.State;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.configuration.DecoderTypeConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.FrequencyConfigurationIdentifier;
import io.github.dsheirer.identifier.decoder.ChannelStateIdentifier;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Preallocated bounded multi-producer/single-consumer command queue for channel activity observations.
 *
 * <p>The receiver-side offer path allocates nothing, never locks or waits, and makes a fixed number of attempts.
 * Payload references are projected only after the dedicated activity worker consumes them.</p>
 */
final class ChannelActivityIngressQueue
{
    private static final int MAXIMUM_OFFER_ATTEMPTS = 4;
    private final Cell[] mCells;
    private final int mMask;
    private final int mRegularLimit;
    private final AtomicLong mProducerSequence = new AtomicLong();
    private final AtomicLong mRegularCount = new AtomicLong();
    private long mConsumerSequence;

    ChannelActivityIngressQueue(int capacity, int lifecycleReserve)
    {
        if(capacity < 2 || Integer.bitCount(capacity) != 1)
        {
            throw new IllegalArgumentException("capacity must be a power of two greater than one");
        }

        if(lifecycleReserve < 1 || lifecycleReserve >= capacity)
        {
            throw new IllegalArgumentException("lifecycle reserve must be between one and capacity");
        }

        mCells = new Cell[capacity];
        mMask = capacity - 1;
        mRegularLimit = capacity - lifecycleReserve;

        for(int x = 0; x < capacity; x++)
        {
            mCells[x] = new Cell(x);
        }
    }

    boolean offer(int operation, boolean lifecycle, Object first, Object second, Object third,
                  Object fourth, Object fifth, Object sixth, long value)
    {
        if(!lifecycle && !reserveRegularSlot())
        {
            return false;
        }

        long sequence = mProducerSequence.get();

        for(int attempt = 0; attempt < MAXIMUM_OFFER_ATTEMPTS; attempt++)
        {
            Cell cell = mCells[(int)sequence & mMask];
            long difference = cell.mSequence.get() - sequence;

            if(difference == 0)
            {
                if(mProducerSequence.compareAndSet(sequence, sequence + 1))
                {
                    cell.mOperation = operation;
                    cell.mLifecycle = lifecycle;
                    cell.mFirst = first;
                    cell.mSecond = second;
                    cell.mThird = third;
                    cell.mFourth = fourth;
                    cell.mFifth = fifth;
                    cell.mSixth = sixth;
                    cell.mValue = value;
                    cell.mSequence.lazySet(sequence + 1);
                    return true;
                }
            }
            else if(difference < 0)
            {
                if(!lifecycle)
                {
                    mRegularCount.decrementAndGet();
                }

                return false;
            }

            sequence = mProducerSequence.get();
        }

        if(!lifecycle)
        {
            mRegularCount.decrementAndGet();
        }

        return false;
    }

    /**
     * Offers an immutable-by-reference metadata observation without allocating on the producer thread.  A regular
     * slot is reserved before any metadata is read, so a saturated queue rejects the observation without doing
     * projection work.  The claimed cell is published only after every field has been copied, giving the consumer a
     * coherent view even when the mutable {@link ChannelMetadata} advances before the worker drains this entry.
     */
    boolean offerMetadata(int operation, ChannelMetadata metadata, ChannelMetadataField field)
    {
        if(metadata == null || !reserveRegularSlot())
        {
            return false;
        }

        long sequence = mProducerSequence.get();

        for(int attempt = 0; attempt < MAXIMUM_OFFER_ATTEMPTS; attempt++)
        {
            Cell cell = mCells[(int)sequence & mMask];
            long difference = cell.mSequence.get() - sequence;

            if(difference == 0)
            {
                if(mProducerSequence.compareAndSet(sequence, sequence + 1))
                {
                    FrequencyConfigurationIdentifier frequency = metadata.getFrequencyConfigurationIdentifier();
                    ChannelStateIdentifier state = metadata.getChannelStateIdentifier();
                    cell.mOperation = operation;
                    cell.mLifecycle = false;
                    cell.mMetadata = metadata;
                    cell.mMetadataField = field;
                    cell.mMetadataFrequency = frequency != null && frequency.getValue() != null ?
                        frequency.getValue() : 0L;
                    cell.mMetadataTimeslot = metadata.hasTimeslot() ? metadata.getTimeslot() : null;
                    cell.mMetadataState = state != null ? state.getValue() : State.IDLE;
                    cell.mMetadataDecoder = metadata.getDecoderTypeConfigurationIdentifier();
                    cell.mMetadataSource = metadata.getFromIdentifier();
                    cell.mMetadataSourceAliases = metadata.getFromIdentifierAliases();
                    cell.mMetadataTalkerAlias = metadata.getTalkerAliasIdentifier();
                    cell.mMetadataTarget = metadata.getToIdentifier();
                    cell.mMetadataTargetAliases = metadata.getToIdentifierAliases();
                    cell.mMetadataEncryption = metadata.getEncryptionIdentifier();
                    cell.mSequence.lazySet(sequence + 1);
                    return true;
                }
            }
            else if(difference < 0)
            {
                mRegularCount.decrementAndGet();
                return false;
            }

            sequence = mProducerSequence.get();
        }

        mRegularCount.decrementAndGet();
        return false;
    }

    private boolean reserveRegularSlot()
    {
        long count = mRegularCount.get();

        for(int attempt = 0; attempt < MAXIMUM_OFFER_ATTEMPTS; attempt++)
        {
            if(count >= mRegularLimit)
            {
                return false;
            }

            if(mRegularCount.compareAndSet(count, count + 1))
            {
                return true;
            }

            count = mRegularCount.get();
        }

        return false;
    }

    Entry poll()
    {
        long sequence = mConsumerSequence;
        Cell cell = mCells[(int)sequence & mMask];

        if(cell.mSequence.get() - (sequence + 1) != 0)
        {
            return null;
        }

        Entry entry = new Entry(cell.mOperation, cell.mLifecycle, cell.mFirst, cell.mSecond, cell.mThird,
            cell.mFourth, cell.mFifth, cell.mSixth, cell.mValue, cell.mMetadata, cell.mMetadataField,
            cell.mMetadataFrequency, cell.mMetadataTimeslot, cell.mMetadataState, cell.mMetadataDecoder,
            cell.mMetadataSource, cell.mMetadataSourceAliases, cell.mMetadataTalkerAlias, cell.mMetadataTarget,
            cell.mMetadataTargetAliases, cell.mMetadataEncryption);
        cell.mFirst = null;
        cell.mSecond = null;
        cell.mThird = null;
        cell.mFourth = null;
        cell.mFifth = null;
        cell.mSixth = null;
        cell.mMetadata = null;
        cell.mMetadataField = null;
        cell.mMetadataTimeslot = null;
        cell.mMetadataState = null;
        cell.mMetadataDecoder = null;
        cell.mMetadataSource = null;
        cell.mMetadataSourceAliases = null;
        cell.mMetadataTalkerAlias = null;
        cell.mMetadataTarget = null;
        cell.mMetadataTargetAliases = null;
        cell.mMetadataEncryption = null;

        if(!cell.mLifecycle)
        {
            mRegularCount.decrementAndGet();
        }

        cell.mSequence.lazySet(sequence + mCells.length);
        mConsumerSequence = sequence + 1;
        return entry;
    }

    int regularCapacity()
    {
        return mRegularLimit;
    }

    int size()
    {
        long size = mProducerSequence.get() - mConsumerSequence;
        return (int)Math.max(0, Math.min(mCells.length, size));
    }

    void clear()
    {
        while(poll() != null)
        {
            // Drain from the single consumer thread.
        }
    }

    record Entry(int operation, boolean lifecycle, Object first, Object second, Object third, Object fourth,
                 Object fifth, Object sixth, long value, ChannelMetadata metadata, ChannelMetadataField metadataField,
                 long metadataFrequency, Integer metadataTimeslot, State metadataState,
                 DecoderTypeConfigurationIdentifier metadataDecoder, Identifier<?> metadataSource,
                 List<Alias> metadataSourceAliases, Identifier<?> metadataTalkerAlias, Identifier<?> metadataTarget,
                 List<Alias> metadataTargetAliases, Identifier<?> metadataEncryption)
    {
    }

    private static final class Cell
    {
        private final AtomicLong mSequence;
        private int mOperation;
        private boolean mLifecycle;
        private Object mFirst;
        private Object mSecond;
        private Object mThird;
        private Object mFourth;
        private Object mFifth;
        private Object mSixth;
        private long mValue;
        private ChannelMetadata mMetadata;
        private ChannelMetadataField mMetadataField;
        private long mMetadataFrequency;
        private Integer mMetadataTimeslot;
        private State mMetadataState;
        private DecoderTypeConfigurationIdentifier mMetadataDecoder;
        private Identifier<?> mMetadataSource;
        private List<Alias> mMetadataSourceAliases;
        private Identifier<?> mMetadataTalkerAlias;
        private Identifier<?> mMetadataTarget;
        private List<Alias> mMetadataTargetAliases;
        private Identifier<?> mMetadataEncryption;

        private Cell(long sequence)
        {
            mSequence = new AtomicLong(sequence);
        }
    }
}
