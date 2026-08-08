/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.message;

import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.sample.Listener;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Demand-driven view of the existing message history for one active configured channel and exact frequency.
 * Message retention remains owned by the processing chain; each consumer only keeps a small raw-message queue.
 */
public class DecodeMessageViewService
{
    static final int LIVE_QUEUE_SIZE = 256;
    private static final int TEXT_MAXIMUM_LENGTH = 2_048;
    private static final int PROTOCOL_MAXIMUM_LENGTH = 64;
    private static final long REBIND_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(500);
    private final HistoryResolver mHistoryResolver;

    /**
     * Constructs an instance that resolves the processing chain by exact configuration and source frequency.
     */
    public DecodeMessageViewService(ChannelProcessingManager channelProcessingManager)
    {
        Objects.requireNonNull(channelProcessingManager, "channelProcessingManager cannot be null");
        mHistoryResolver = scope -> {
            List<ProcessingChain> chains = channelProcessingManager.getProcessingChainsByConfiguration(
                scope.configurationId(), scope.frequencyHz());

            if(chains != null)
            {
                for(ProcessingChain chain: chains)
                {
                    if(chain != null)
                    {
                        return chain.getMessageHistory();
                    }
                }
            }

            return null;
        };
    }

    DecodeMessageViewService(HistoryResolver historyResolver)
    {
        mHistoryResolver = Objects.requireNonNull(historyResolver, "historyResolver cannot be null");
    }

    /**
     * Opens a lightweight message session. The caller must close the session to detach its history listener.
     */
    public Session openSession(Scope scope)
    {
        return new Session(Objects.requireNonNull(scope, "scope cannot be null"), mHistoryResolver);
    }

    @FunctionalInterface
    interface HistoryResolver
    {
        MessageHistory resolve(Scope scope);
    }

    /**
     * Exact active-channel selection. Configuration identifiers are normalized UUID strings.
     */
    public record Scope(String configurationId, long frequencyHz)
    {
        public Scope
        {
            if(configurationId == null)
            {
                throw new IllegalArgumentException("configurationId is required");
            }

            try
            {
                configurationId = UUID.fromString(configurationId.strip()).toString();
            }
            catch(IllegalArgumentException exception)
            {
                throw new IllegalArgumentException("configurationId must be a UUID", exception);
            }

            if(frequencyHz <= 0)
            {
                throw new IllegalArgumentException("frequencyHz must be positive");
            }
        }
    }

    /**
     * Safe, bounded fields needed by the web message table.
     */
    public record MessageView(String messageId, long timestampMs, String protocol, int timeslot, boolean valid,
                              String text)
    {
    }

    /**
     * Session binding for one selected channel. Decoder callbacks only enqueue the raw message and never serialize it.
     */
    public static class Session implements AutoCloseable
    {
        private final Scope mScope;
        private final HistoryResolver mHistoryResolver;
        private final ArrayBlockingQueue<IMessage> mQueue = new ArrayBlockingQueue<>(LIVE_QUEUE_SIZE);
        private final AtomicBoolean mClosed = new AtomicBoolean();
        private volatile Binding mBinding;
        private volatile long mGeneration;

        private Session(Scope scope, HistoryResolver historyResolver)
        {
            mScope = scope;
            mHistoryResolver = historyResolver;
        }

        public Scope getScope()
        {
            return mScope;
        }

        /**
         * Re-resolves the active processing chain and rebinds when that chain has been replaced.
         *
         * @return true when the bound history changed
         */
        public boolean refresh()
        {
            if(mClosed.get())
            {
                return false;
            }

            MessageHistory nextHistory = mHistoryResolver.resolve(mScope);

            synchronized(this)
            {
                if(mClosed.get())
                {
                    return false;
                }

                Binding current = mBinding;

                if((current == null && nextHistory == null) ||
                    (current != null && current.history() == nextHistory))
                {
                    return false;
                }

                long generation = ++mGeneration;

                if(current != null)
                {
                    current.history().removeListener(current.listener());
                }

                mBinding = null;
                mQueue.clear();

                if(nextHistory != null)
                {
                    Listener<IMessage> listener = message -> enqueue(generation, message);
                    Binding next = new Binding(nextHistory, listener);
                    mBinding = next;
                    nextHistory.addListener(listener);
                }

                return true;
            }
        }

        public boolean isBound()
        {
            return mBinding != null && !mClosed.get();
        }

        public long generation()
        {
            return mGeneration;
        }

        /**
         * Returns a newest-first snapshot from the selected processing chain's existing bounded history.
         */
        public List<MessageView> snapshot()
        {
            refresh();
            Binding binding = mBinding;

            if(binding == null || mClosed.get())
            {
                return List.of();
            }

            List<IMessage> messages = binding.history().getItems();
            List<MessageView> views = new ArrayList<>(messages.size());

            for(int x = messages.size() - 1; x >= 0; x--)
            {
                MessageView view = view(messages.get(x));

                if(view != null)
                {
                    views.add(view);
                }
            }

            return binding == mBinding && !mClosed.get() ? List.copyOf(views) : List.of();
        }

        /**
         * Waits for the next displayable message. Long waits are divided into short intervals so a replaced processing
         * chain is detected and rebound without a separate maintenance thread.
         */
        public MessageView poll(long timeout, TimeUnit unit) throws InterruptedException
        {
            Objects.requireNonNull(unit, "unit cannot be null");

            if(timeout < 0)
            {
                throw new IllegalArgumentException("timeout cannot be negative");
            }

            long remainingNanos = unit.toNanos(timeout);
            long started = System.nanoTime();

            while(!mClosed.get())
            {
                refresh();
                IMessage message;

                if(remainingNanos <= 0)
                {
                    message = mQueue.poll();
                }
                else
                {
                    message = mQueue.poll(Math.min(remainingNanos, REBIND_INTERVAL_NANOS), TimeUnit.NANOSECONDS);
                }

                MessageView view = view(message);

                if(view != null)
                {
                    return view;
                }

                if(remainingNanos <= 0)
                {
                    return null;
                }

                remainingNanos = unit.toNanos(timeout) - (System.nanoTime() - started);
            }

            return null;
        }

        private void enqueue(long generation, IMessage message)
        {
            if(message == null || message instanceof StuffBitsMessage || mClosed.get() || generation != mGeneration)
            {
                return;
            }

            if(!mQueue.offer(message))
            {
                mQueue.poll();
                mQueue.offer(message);
            }
        }

        @Override
        public synchronized void close()
        {
            if(!mClosed.compareAndSet(false, true))
            {
                return;
            }

            mGeneration++;
            Binding binding = mBinding;
            mBinding = null;

            if(binding != null)
            {
                binding.history().removeListener(binding.listener());
            }

            mQueue.clear();
        }

        private static MessageView view(IMessage message)
        {
            if(message == null || message instanceof StuffBitsMessage)
            {
                return null;
            }

            long timestamp = timestamp(message);
            return new MessageView(messageId(message, timestamp), timestamp, protocol(message), timeslot(message),
                valid(message), text(message));
        }

        private static String messageId(IMessage message, long timestamp)
        {
            return Long.toUnsignedString(timestamp, 36) + "-" +
                Integer.toUnsignedString(System.identityHashCode(message), 36);
        }

        private static long timestamp(IMessage message)
        {
            try
            {
                return message.getTimestamp();
            }
            catch(RuntimeException _)
            {
                return 0;
            }
        }

        private static String protocol(IMessage message)
        {
            try
            {
                Protocol protocol = message.getProtocol();
                return protocol != null ? bounded(protocol.toString(), PROTOCOL_MAXIMUM_LENGTH) : "Unknown";
            }
            catch(RuntimeException _)
            {
                return "Unknown";
            }
        }

        private static int timeslot(IMessage message)
        {
            try
            {
                return message.getTimeslot();
            }
            catch(RuntimeException _)
            {
                return 0;
            }
        }

        private static boolean valid(IMessage message)
        {
            try
            {
                return message.isValid();
            }
            catch(RuntimeException _)
            {
                return false;
            }
        }

        private static String text(IMessage message)
        {
            try
            {
                return bounded(message.toString(), TEXT_MAXIMUM_LENGTH);
            }
            catch(RuntimeException _)
            {
                return "MESSAGE ITEM ENCOUNTERED PARSING ERROR";
            }
        }

        private static String bounded(String value, int maximumLength)
        {
            if(value == null)
            {
                return "";
            }

            String stripped = value.strip();
            return stripped.length() <= maximumLength ? stripped :
                stripped.substring(0, maximumLength - 1) + "…";
        }

        private record Binding(MessageHistory history, Listener<IMessage> listener)
        {
        }
    }
}
