/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */
package io.github.dsheirer.remote;

import io.github.dsheirer.remote.RemoteWireProtocol.Frame;
import io.github.dsheirer.remote.RemoteWireProtocol.Type;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * One mutually authenticated TCP session per sender. A bounded writer queue isolates radio callbacks from sockets.
 * Intended for a trusted VPN: HMAC authenticates identities, while payload confidentiality and transport integrity
 * come from the VPN rather than this protocol.
 */
public final class RemoteLinkTransport
{
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int CHALLENGE_BYTES = 32;
    private static final int AUTH_TIMEOUT_MS = 5_000;
    private static final int IDLE_TIMEOUT_MS = 20_000;
    private static final int WRITE_QUEUE_CAPACITY = 1_024;
    private static final long MAXIMUM_WRITE_QUEUE_AGE_NANOS = TimeUnit.SECONDS.toNanos(2);
    private static final int MAXIMUM_SENDERS = 32;

    private RemoteLinkTransport()
    {
    }

    public interface Listener
    {
        void connected(String senderId, Connection connection);

        void frame(String senderId, Connection connection, Frame frame);

        void disconnected(String senderId, Connection connection);
    }

    public interface SecretLookup
    {
        String secretFor(String senderId);
    }

    public static Server listen(String bindAddress, int port, SecretLookup secrets, Listener listener)
        throws IOException
    {
        return new Server(bindAddress, port, secrets, listener);
    }

    public static Connection connect(String host, int port, String senderId, String secret, Listener listener)
        throws IOException
    {
        Socket socket = new Socket();
        try
        {
            socket.connect(new InetSocketAddress(host, port), AUTH_TIMEOUT_MS);
            configure(socket);
            DataInputStream input = new DataInputStream(socket.getInputStream());
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            sendNow(output, Type.HELLO, RemoteWireMessages.json(
                new RemoteWireMessages.Hello(RemoteWireProtocol.VERSION, senderId)));
            Frame challenge = require(input, Type.CHALLENGE);
            if(challenge.payload().length != CHALLENGE_BYTES)
            {
                throw new IOException("Invalid remote authentication challenge");
            }
            sendNow(output, Type.AUTH, hmac(secret, senderId, challenge.payload()));
            RemoteWireMessages.Accepted accepted = RemoteWireMessages.parse(require(input, Type.ACCEPT).payload(),
                RemoteWireMessages.Accepted.class);
            byte[] proof = Base64.getUrlDecoder().decode(accepted.hostProof());
            if(!MessageDigest.isEqual(proof, hostProof(secret, senderId, challenge.payload(),
                accepted.sessionId())))
            {
                throw new IOException("Remote host authentication failed");
            }
            socket.setSoTimeout(IDLE_TIMEOUT_MS);
            Connection connection = new Connection(socket, senderId, accepted.sessionId(), input, output, listener);
            connection.start();
            return connection;
        }
        catch(IOException | RuntimeException exception)
        {
            try { socket.close(); } catch(IOException ignored) { }
            if(exception instanceof IOException io) throw io;
            throw new IOException("Unable to authenticate remote connection", exception);
        }
    }

    public static final class Server implements AutoCloseable
    {
        private final ServerSocket mSocket = new ServerSocket();
        private final SecretLookup mSecrets;
        private final Listener mListener;
        private final Map<String,Connection> mConnections = new ConcurrentHashMap<>();
        private final Semaphore mAuthenticationSlots = new Semaphore(MAXIMUM_SENDERS);
        private final AtomicBoolean mClosed = new AtomicBoolean();
        private final Thread mAcceptor;

        private Server(String bindAddress, int port, SecretLookup secrets, Listener listener) throws IOException
        {
            mSecrets = Objects.requireNonNull(secrets);
            mListener = Objects.requireNonNull(listener);
            mSocket.bind(new InetSocketAddress(bindAddress, port), MAXIMUM_SENDERS);
            mAcceptor = Thread.ofPlatform().daemon().name("remote-p25-accept").start(this::acceptLoop);
        }

        private void acceptLoop()
        {
            while(!mClosed.get())
            {
                try
                {
                    Socket socket = mSocket.accept();
                    if(mAuthenticationSlots.tryAcquire())
                    {
                        Thread.ofPlatform().daemon().name("remote-p25-auth").start(() -> authenticate(socket));
                    }
                    else
                    {
                        socket.close();
                    }
                }
                catch(IOException exception)
                {
                    if(!mClosed.get())
                    {
                        close();
                    }
                }
            }
        }

        private void authenticate(Socket socket)
        {
            Connection connection = null;
            boolean registered = false;
            try
            {
                configure(socket);
                DataInputStream input = new DataInputStream(socket.getInputStream());
                DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                RemoteWireMessages.Hello hello = RemoteWireMessages.parse(require(input, Type.HELLO).payload(),
                    RemoteWireMessages.Hello.class);
                String senderId = hello.senderId();
                if(hello.version() != RemoteWireProtocol.VERSION)
                {
                    throw new IOException("Remote connection is unavailable");
                }
                String secret = mSecrets.secretFor(senderId);
                if(secret == null)
                {
                    throw new IOException("Remote sender is not authorized");
                }
                byte[] challenge = new byte[CHALLENGE_BYTES];
                RANDOM.nextBytes(challenge);
                sendNow(output, Type.CHALLENGE, challenge);
                byte[] response = require(input, Type.AUTH).payload();
                if(response.length != 32 || !MessageDigest.isEqual(response, hmac(secret, senderId, challenge)))
                {
                    throw new IOException("Remote sender authentication failed");
                }
                String sessionId = UUID.randomUUID().toString();
                socket.setSoTimeout(IDLE_TIMEOUT_MS);
                Connection candidate = new Connection(socket, senderId, sessionId, input, output, mListener);
                connection = candidate;
                synchronized(mConnections)
                {
                    if(mClosed.get() || mConnections.size() >= MAXIMUM_SENDERS ||
                        mConnections.putIfAbsent(senderId, candidate) != null)
                    {
                        throw new IOException("Remote connection is unavailable");
                    }
                    candidate.setOnClose(() -> mConnections.remove(senderId, candidate));
                    registered = true;
                }
                sendNow(output, Type.ACCEPT, RemoteWireMessages.json(new RemoteWireMessages.Accepted(sessionId,
                    Base64.getUrlEncoder().withoutPadding().encodeToString(
                        hostProof(secret, senderId, challenge, sessionId)))));
                connection.start();
            }
            catch(IOException | RuntimeException ignored)
            {
                if(registered) connection.close();
                else try { socket.close(); } catch(IOException ignoredClose) { }
            }
            finally
            {
                mAuthenticationSlots.release();
            }
        }

        public int port()
        {
            return mSocket.getLocalPort();
        }

        @Override
        public void close()
        {
            if(mClosed.compareAndSet(false, true))
            {
                try { mSocket.close(); } catch(IOException ignored) { }
                List<Connection> active;
                synchronized(mConnections)
                {
                    active = List.copyOf(mConnections.values());
                    mConnections.clear();
                }
                for(Connection connection: active) connection.close();
            }
        }
    }

    public static final class Connection implements AutoCloseable
    {
        private final Socket mSocket;
        private final String mSenderId;
        private final String mSessionId;
        private final DataInputStream mInput;
        private final DataOutputStream mOutput;
        private final Listener mListener;
        private final ArrayBlockingQueue<QueuedFrame> mWriteQueue =
            new ArrayBlockingQueue<>(WRITE_QUEUE_CAPACITY);
        private final AtomicBoolean mClosed = new AtomicBoolean();
        private volatile Runnable mOnClose = () -> { };

        private Connection(Socket socket, String senderId, String sessionId, DataInputStream input,
                           DataOutputStream output, Listener listener)
        {
            mSocket = socket;
            mSenderId = senderId;
            mSessionId = sessionId;
            mInput = input;
            mOutput = output;
            mListener = listener;
        }

        private void setOnClose(Runnable onClose)
        {
            mOnClose = onClose;
        }

        private void start()
        {
            try
            {
                mListener.connected(mSenderId, this);
            }
            catch(RuntimeException exception)
            {
                close();
                throw exception;
            }
            Thread.ofPlatform().daemon().name("remote-p25-read").start(this::readLoop);
            Thread.ofPlatform().daemon().name("remote-p25-write").start(this::writeLoop);
        }

        public String senderId()
        {
            return mSenderId;
        }

        public String sessionId()
        {
            return mSessionId;
        }

        public boolean isOpen()
        {
            return !mClosed.get();
        }

        /** Bounded, nonblocking offer. A false result requires the caller to mark a packet gap or end the session. */
        public boolean offer(Frame frame)
        {
            QueuedFrame oldest = mWriteQueue.peek();
            if(oldest != null && System.nanoTime() - oldest.enqueuedAtNanos() >
                MAXIMUM_WRITE_QUEUE_AGE_NANOS) return false;
            return !mClosed.get() && mWriteQueue.offer(new QueuedFrame(frame, System.nanoTime()));
        }

        private void readLoop()
        {
            try
            {
                Frame frame;
                while(!mClosed.get() && (frame = RemoteWireProtocol.read(mInput)) != null)
                {
                    if(frame.type() == Type.HEARTBEAT)
                    {
                        if(frame.payload().length != 0) throw new IOException("Invalid remote heartbeat");
                    }
                    else
                    {
                        mListener.frame(mSenderId, this, frame);
                    }
                }
            }
            catch(IOException | RuntimeException ignored)
            {
                // The owning service reports link loss and closes all streams for this session.
            }
            finally
            {
                close();
            }
        }

        private void writeLoop()
        {
            try
            {
                while(!mClosed.get())
                {
                    QueuedFrame queued = mWriteQueue.poll(5, TimeUnit.SECONDS);
                    if(queued != null && System.nanoTime() - queued.enqueuedAtNanos() >
                        MAXIMUM_WRITE_QUEUE_AGE_NANOS)
                    {
                        throw new IOException("Remote writer queue exceeded latency limit");
                    }
                    RemoteWireProtocol.write(mOutput, queued != null ? queued.frame() : new Frame(Type.HEARTBEAT,
                        new byte[0]));
                    mOutput.flush();
                }
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
            }
            catch(IOException ignored)
            {
                // The reader/owner observes the closed session.
            }
            finally
            {
                close();
            }
        }

        private record QueuedFrame(Frame frame, long enqueuedAtNanos)
        {
        }

        @Override
        public void close()
        {
            if(mClosed.compareAndSet(false, true))
            {
                try { mSocket.close(); } catch(IOException ignored) { }
                mWriteQueue.clear();
                try { mOnClose.run(); } catch(RuntimeException ignored) { }
                try { mListener.disconnected(mSenderId, this); } catch(RuntimeException ignored) { }
            }
        }
    }

    private static void configure(Socket socket) throws IOException
    {
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
        socket.setSoTimeout(AUTH_TIMEOUT_MS);
    }

    private static Frame require(DataInputStream input, Type expected) throws IOException
    {
        Frame frame = RemoteWireProtocol.read(input);
        if(frame == null || frame.type() != expected)
        {
            throw new IOException("Unexpected remote handshake frame");
        }
        return frame;
    }

    private static void sendNow(DataOutputStream output, Type type, byte[] payload) throws IOException
    {
        RemoteWireProtocol.write(output, new Frame(type, payload));
        output.flush();
    }

    private static byte[] hmac(String secret, String senderId, byte[] challenge) throws IOException
    {
        try
        {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(Objects.requireNonNull(secret).getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"));
            mac.update(senderId.getBytes(StandardCharsets.US_ASCII));
            mac.update(challenge);
            return mac.doFinal();
        }
        catch(GeneralSecurityException exception)
        {
            throw new IOException("Remote authentication is unavailable", exception);
        }
    }

    private static byte[] hostProof(String secret, String senderId, byte[] challenge, String sessionId)
        throws IOException
    {
        try
        {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(Objects.requireNonNull(secret).getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"));
            mac.update("host-accept-v1".getBytes(StandardCharsets.US_ASCII));
            mac.update(senderId.getBytes(StandardCharsets.US_ASCII));
            mac.update(challenge);
            mac.update(sessionId.getBytes(StandardCharsets.US_ASCII));
            return mac.doFinal();
        }
        catch(GeneralSecurityException exception)
        {
            throw new IOException("Remote host authentication is unavailable", exception);
        }
    }
}
