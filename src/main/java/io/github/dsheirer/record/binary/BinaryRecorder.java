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
package io.github.dsheirer.record.binary;

import io.github.dsheirer.module.Module;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.remote.IP25RemoteBitstreamListener;
import io.github.dsheirer.remote.P25RemoteBitstreamPacket;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.buffer.IByteBufferListener;
import io.github.dsheirer.source.ISourceEventListener;
import io.github.dsheirer.source.SourceEvent;
import io.github.dsheirer.util.StringUtils;
import io.github.dsheirer.util.TimeStamp;
import io.github.dsheirer.util.concurrent.BoundedMpscReferenceQueue;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared local/remote demodulated-bit recorder. Files retain the existing headerless .bits format. A gap, retune,
 * restart or lost recorder buffer starts another segment. Only the private worker opens, writes and closes files;
 * callbacks copy one bounded buffer and offer it without waiting. Stop drains accepted buffers asynchronously.
 */
public class BinaryRecorder extends Module implements IByteBufferListener, IP25RemoteBitstreamListener,
    ISourceEventListener
{
    private static final Logger LOGGER = LoggerFactory.getLogger(BinaryRecorder.class);
    private static final int MAX_RECORDING_BYTE_SIZE = 524_288;
    private static final int QUEUE_CAPACITY = 64;
    private static final int MAX_BUFFER_BYTES = 65_536;
    private static final AtomicLong FILE_SEQUENCE = new AtomicLong();
    private final Path mBaseRecordingPath;
    private final String mRecordingIdentifier;
    private final Protocol mProtocol;
    private final int mBitRate;
    private final int mMaximumFileBytes;
    private final BinaryFileOpener mFileOpener;
    private final BoundedMpscReferenceQueue<CapturedBuffer> mBuffers;
    private final AtomicReference<Session> mSession = new AtomicReference<>();
    private final AtomicReference<Thread> mWorker = new AtomicReference<>();
    private final AtomicInteger mProducers = new AtomicInteger();
    private final AtomicLong mBoundary = new AtomicLong();
    private volatile long mFrequency;
    private volatile CompletableFuture<Void> mCompletion = CompletableFuture.completedFuture(null);
    private final Listener<ByteBuffer> mLocalListener = buffer -> receive(buffer, false);
    private final Listener<P25RemoteBitstreamPacket> mRemoteListener = packet ->
    {
        if(packet != null) receive(ByteBuffer.wrap(packet.dibits()), packet.discontinuity());
    };
    private final Listener<SourceEvent> mSourceListener = event ->
    {
        if(event != null && event.getEvent() == SourceEvent.Event.NOTIFICATION_FREQUENCY_CHANGE &&
            event.hasValue() && event.getValue().longValue() > 0 && event.getValue().longValue() != mFrequency)
        {
            mFrequency = event.getValue().longValue();
            mBoundary.incrementAndGet();
        }
    };

    public BinaryRecorder(Path base, String identifier, Protocol protocol, long frequency)
    {
        this(base, identifier, protocol, protocol.getBitRate(), frequency);
    }

    public BinaryRecorder(Path base, String identifier, Protocol protocol, int bitRate, long frequency)
    {
        this(base, identifier, protocol, bitRate, frequency, QUEUE_CAPACITY, MAX_RECORDING_BYTE_SIZE,
            path -> Files.newByteChannel(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE));
    }

    /** Small test seam for blocked storage, partial writes and deterministic queue/segment limits. */
    BinaryRecorder(Path base, String identifier, Protocol protocol, int bitRate, long frequency, int queueCapacity,
                   int maximumFileBytes, BinaryFileOpener opener)
    {
        if(maximumFileBytes < 1) throw new IllegalArgumentException("File size must be positive");
        mBaseRecordingPath = java.util.Objects.requireNonNull(base);
        mRecordingIdentifier = java.util.Objects.requireNonNull(identifier);
        mProtocol = java.util.Objects.requireNonNull(protocol);
        mBitRate = bitRate;
        mFrequency = frequency;
        mMaximumFileBytes = maximumFileBytes;
        mFileOpener = java.util.Objects.requireNonNull(opener);
        mBuffers = new BoundedMpscReferenceQueue<>(queueCapacity);
    }

    @Override public void start()
    {
        Session session = new Session();
        if(mSession.compareAndSet(null, session))
        {
            mCompletion = session.completion;
            ensureWorker();
        }
    }

    @Override public void stop()
    {
        Session session = mSession.getAndSet(null);
        if(session != null)
        {
            session.accepting = false;
            completeUnopened(session);
            LockSupport.unpark(mWorker.get());
        }
    }

    @Override public void reset() { mBoundary.incrementAndGet(); }
    @Override public Listener<ByteBuffer> getByteBufferListener() { return mLocalListener; }
    @Override public Listener<P25RemoteBitstreamPacket> getRemoteBitstreamListener() { return mRemoteListener; }
    @Override public Listener<SourceEvent> getSourceEventListener() { return mSourceListener; }
    CompletableFuture<Void> completion() { return mCompletion; }

    private void receive(ByteBuffer input, boolean discontinuity)
    {
        Session session = mSession.get();
        if(session == null || input == null || !input.hasRemaining()) return;
        mProducers.incrementAndGet();
        session.producers.incrementAndGet();
        try
        {
            if(mSession.get() != session || !session.accepting) return;
            long frequency = mFrequency;
            long boundary = discontinuity ? mBoundary.incrementAndGet() : mBoundary.get();
            if(input.remaining() > MAX_BUFFER_BYTES)
            {
                mBoundary.incrementAndGet();
                return;
            }
            ByteBuffer view = input.asReadOnlyBuffer();
            byte[] bytes = new byte[view.remaining()];
            view.get(bytes);
            if(frequency != mFrequency || boundary != mBoundary.get()) return;
            session.pending.incrementAndGet();
            if(!mBuffers.offer(new CapturedBuffer(session, bytes, frequency, boundary)))
            {
                session.pending.decrementAndGet();
                mBoundary.incrementAndGet();
            }
            LockSupport.unpark(mWorker.get());
        }
        finally
        {
            session.producers.decrementAndGet();
            mProducers.decrementAndGet();
            completeUnopened(session);
        }
    }

    private static void completeUnopened(Session session)
    {
        if(!session.accepting && session.pending.get() == 0 && session.producers.get() == 0 && !session.open)
            session.completion.complete(null);
    }

    private void ensureWorker()
    {
        Thread worker = new Thread(this::writeLoop, "sdrtrunk binary recorder");
        worker.setDaemon(true);
        worker.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
        if(mWorker.compareAndSet(null, worker)) worker.start();
        else LockSupport.unpark(mWorker.get());
    }

    private void writeLoop()
    {
        Set<Session> sessions = new HashSet<>();
        try
        {
            while(true)
            {
                CapturedBuffer buffer = mBuffers.poll();
                if(buffer != null)
                {
                    Session session = buffer.session();
                    sessions.add(session);
                    try
                    {
                        if(session.failure == null) write(session, buffer);
                    }
                    catch(IOException | RuntimeException failure)
                    {
                        session.failure = failure;
                        session.accepting = false;
                        mSession.compareAndSet(session, null);
                        LOGGER.error("Bitstream recording stopped after a storage error ({})",
                            failure.getClass().getSimpleName());
                    }
                    finally { session.pending.decrementAndGet(); }
                }
                for(var iterator = sessions.iterator(); iterator.hasNext();)
                {
                    Session session = iterator.next();
                    if(!session.accepting && session.pending.get() == 0 && session.producers.get() == 0)
                    {
                        finish(session);
                        iterator.remove();
                    }
                }
                if(mSession.get() == null && mBuffers.size() == 0 && mProducers.get() == 0) break;
                if(buffer == null) LockSupport.parkNanos(this, TimeUnit.MILLISECONDS.toNanos(100));
            }
        }
        finally
        {
            sessions.forEach(this::finish);
            mWorker.compareAndSet(Thread.currentThread(), null);
            // Start can race the last empty-queue check; ownership is relinquished before another worker starts.
            if(mSession.get() != null || mBuffers.size() > 0) ensureWorker();
        }
    }

    private void write(Session session, CapturedBuffer captured) throws IOException
    {
        if(session.boundary != captured.boundary() || session.frequency != captured.frequency()) closeFile(session);
        session.boundary = captured.boundary();
        session.frequency = captured.frequency();
        ByteBuffer buffer = ByteBuffer.wrap(captured.bytes());
        while(buffer.hasRemaining())
        {
            if(session.channel == null) openFile(session);
            int limit = buffer.limit();
            int length = Math.min(buffer.remaining(), mMaximumFileBytes - session.bytes);
            buffer.limit(buffer.position() + length);
            while(buffer.hasRemaining())
            {
                if(session.channel.write(buffer) <= 0) throw new IOException("Bitstream writer made no progress");
            }
            buffer.limit(limit);
            session.bytes += length;
            if(session.bytes == mMaximumFileBytes) closeFile(session);
        }
    }

    private void openFile(Session session) throws IOException
    {
        for(int attempt = 0; attempt < 64; attempt++)
        {
            String name = TimeStamp.getTimeStamp("_") + "_" + session.frequency + "_" + mBitRate + "BPS_" +
                mProtocol.getFileNameLabel() + "_" + mRecordingIdentifier.trim() + "_" +
                Long.toUnsignedString(FILE_SEQUENCE.incrementAndGet(), 36) + ".bits";
            Path path = mBaseRecordingPath.resolve(StringUtils.replaceIllegalCharacters(name));
            try
            {
                session.open = true;
                session.channel = mFileOpener.open(path);
                session.bytes = 0;
                return;
            }
            catch(FileAlreadyExistsException collision) { /* Preserve existing files and choose another name. */ }
        }
        throw new IOException("Unable to create a unique bitstream recording");
    }

    private void closeFile(Session session) throws IOException
    {
        if(session.channel != null)
        {
            try { session.channel.close(); }
            finally { session.channel = null; }
        }
        session.bytes = 0;
    }

    private void finish(Session session)
    {
        try { closeFile(session); }
        catch(IOException failure) { if(session.failure == null) session.failure = failure; }
        if(session.failure == null) session.completion.complete(null);
        else session.completion.completeExceptionally(session.failure);
        session.open = false;
    }

    @FunctionalInterface interface BinaryFileOpener
    {
        WritableByteChannel open(Path path) throws IOException;
    }

    private record CapturedBuffer(Session session, byte[] bytes, long frequency, long boundary) { }

    /** Session identity keeps accepted tails separate across a quick stop/start without spawning more writers. */
    private static final class Session
    {
        private final AtomicInteger pending = new AtomicInteger();
        private final AtomicInteger producers = new AtomicInteger();
        private final CompletableFuture<Void> completion = new CompletableFuture<>();
        private volatile boolean accepting = true;
        private volatile boolean open;
        private WritableByteChannel channel;
        private Throwable failure;
        private long boundary = -1;
        private long frequency;
        private int bytes;
    }
}
