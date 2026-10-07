/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.channel.metadata.activity;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.channel.IChannelDescriptor;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.module.decode.event.DecodeEventType;
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
        Cell cell = claim(lifecycle);
        if(cell == null)
        {
            return false;
        }
        cell.mOperation = operation;
        cell.mFirst = first;
        cell.mSecond = second;
        cell.mThird = third;
        cell.mFourth = fourth;
        cell.mFifth = fifth;
        cell.mSixth = sixth;
        cell.mValue = value;
        publish(cell);
        return true;
    }

    /** Copies fixed identity references only after claiming a bounded slot; mutable collections never escape. */
    boolean offerTraffic(int operation, Channel parent, Channel traffic, IChannelDescriptor descriptor,
                         Integer timeslot, IdentifierCollection identifiers, DecodeEventType eventType,
                         long controlFrequency, long callStart, long observedAt)
    {
        Cell cell = claim(false);
        if(cell == null)
        {
            return false;
        }
        cell.mOperation = operation;
        cell.mFirst = parent;
        cell.mSecond = traffic;
        cell.mThird = descriptor;
        cell.mFourth = eventType;
        cell.mValue = controlFrequency;
        cell.mCallStart = callStart;
        cell.mObservedAt = observedAt;
        cell.mMetadataTimeslot = timeslot;
        Identifier<?> source = identifiers != null ? identifiers.getFromIdentifier() : null;
        cell.mMetadataSource = source != null && source.getForm() != Form.TALKER_ALIAS && source.isValid() ?
            source : null;
        Identifier<?> target = identifiers != null ? identifiers.getToIdentifier() : null;
        cell.mMetadataTarget = target != null && target.isValid() ? target : null;
        cell.mMetadataTalkerAlias = identifiers != null ?
            identifiers.getIdentifier(IdentifierClass.USER, Form.TALKER_ALIAS, Role.FROM) : null;
        cell.mMetadataEncryption = identifiers != null ? identifiers.getEncryptionIdentifier() : null;
        publish(cell);
        return true;
    }

    /** Captures the same fixed participant fields for conventional metadata, without producer projection. */
    boolean offerMetadata(int operation, ChannelMetadata metadata, ChannelMetadataField field)
    {
        if(metadata == null)
        {
            return false;
        }
        Cell cell = claim(false);
        if(cell == null)
        {
            return false;
        }
        FrequencyConfigurationIdentifier frequency = metadata.getFrequencyConfigurationIdentifier();
        ChannelStateIdentifier state = metadata.getChannelStateIdentifier();
        cell.mOperation = operation;
        cell.mMetadata = metadata;
        cell.mMetadataField = field;
        cell.mMetadataFrequency = frequency != null && frequency.getValue() != null ? frequency.getValue() : 0L;
        cell.mMetadataTimeslot = metadata.hasTimeslot() ? metadata.getTimeslot() : null;
        cell.mMetadataState = state != null ? state.getValue() : State.IDLE;
        cell.mMetadataDecoder = metadata.getDecoderTypeConfigurationIdentifier();
        cell.mMetadataSource = metadata.getFromIdentifier();
        cell.mMetadataSourceAliases = metadata.getFromIdentifierAliases();
        cell.mMetadataTalkerAlias = metadata.getTalkerAliasIdentifier();
        cell.mMetadataTarget = metadata.getToIdentifier();
        cell.mMetadataTargetAliases = metadata.getToIdentifierAliases();
        cell.mMetadataEncryption = metadata.getEncryptionIdentifier();
        publish(cell);
        return true;
    }

    private Cell claim(boolean lifecycle)
    {
        if(!lifecycle && !reserveRegularSlot())
        {
            return null;
        }
        long sequence = mProducerSequence.get();
        for(int attempt = 0; attempt < MAXIMUM_OFFER_ATTEMPTS; attempt++)
        {
            Cell cell = mCells[(int)sequence & mMask];
            long difference = cell.mSequence.get() - sequence;
            if(difference == 0 && mProducerSequence.compareAndSet(sequence, sequence + 1))
            {
                cell.mLifecycle = lifecycle;
                return cell;
            }
            if(difference < 0)
            {
                break;
            }
            sequence = mProducerSequence.get();
        }
        if(!lifecycle)
        {
            mRegularCount.decrementAndGet();
        }
        return null;
    }

    private static void publish(Cell cell)
    {
        cell.mSequence.lazySet(cell.mSequence.get() + 1);
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
            cell.mMetadataTargetAliases, cell.mMetadataEncryption, cell.mCallStart, cell.mObservedAt);
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
        cell.mCallStart = 0;
        cell.mObservedAt = 0;

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
                 List<Alias> metadataTargetAliases, Identifier<?> metadataEncryption, long callStart, long observedAt)
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
        private long mCallStart;
        private long mObservedAt;
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
