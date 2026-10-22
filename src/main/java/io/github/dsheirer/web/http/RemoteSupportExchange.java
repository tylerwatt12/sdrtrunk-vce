/* Copyright (C) 2026. Licensed under GPL-3.0-or-later. */
package io.github.dsheirer.web.http;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpPrincipal;
import com.sun.net.httpserver.HttpsExchange;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.net.ssl.SSLSession;

/** An application-created HTTPS request; network callers cannot manufacture this transport identity. */
final class RemoteSupportExchange extends HttpsExchange
{
    static final int CHUNK_BYTES = 32768;
    private static final Set<String> OMIT_HEADERS = Set.of("connection", "keep-alive", "proxy-authenticate",
        "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade");
    private final String mId;
    private final String mMethod;
    private final URI mUri;
    private final HttpContext mContext;
    private final Headers mRequestHeaders = new Headers();
    private final Headers mResponseHeaders = new Headers();
    private final Map<String,Object> mAttributes = new ConcurrentHashMap<>();
    private final Body mBody;
    private final Sender mSender;
    private final BooleanSupplier mAllowed;
    private final AtomicBoolean mClosed = new AtomicBoolean();
    private final AtomicBoolean mWaitingForAck = new AtomicBoolean();
    private final Semaphore mResponseAck = new Semaphore(0);
    private InputStream mInput;
    private OutputStream mOutput;
    private volatile int mStatus = -1;

    RemoteSupportExchange(String id, String method, URI uri, Map<String,List<String>> headers, String host,
                          String session, HttpContext context, Sender sender, BooleanSupplier allowed)
    {
        mId = id;
        mMethod = method;
        mUri = uri;
        mContext = context;
        mSender = sender;
        mAllowed = allowed;
        mBody = new Body(() -> mSender.send(Map.of("type", "ack", "id", mId)));
        mInput = mBody;
        headers.forEach((name, values) -> {
            if(!OMIT_HEADERS.contains(name.toLowerCase(java.util.Locale.ROOT)) &&
                !Set.of("host", "cookie", "authorization").contains(name.toLowerCase(java.util.Locale.ROOT)))
                mRequestHeaders.put(name, List.copyOf(values));
        });
        mRequestHeaders.set("Host", host);
        mRequestHeaders.set("Cookie", WebRequestSecurity.SESSION_COOKIE_NAME + "=" + session);
        mOutput = new BufferedOutputStream(new OutputStream()
        {
            @Override public void write(int value) throws IOException { write(new byte[]{(byte)value}); }
            @Override public void write(byte[] bytes, int offset, int length) throws IOException
            {
                try
                {
                    if(mClosed.get() || !mAllowed.getAsBoolean()) throw new IOException("Remote support ended");
                    for(int remaining = length; remaining > 0;)
                    {
                        int count = Math.min(CHUNK_BYTES, remaining);
                        byte[] chunk = java.util.Arrays.copyOfRange(bytes, offset, offset + count);
                        mWaitingForAck.set(true);
                        mSender.send(Map.of("type", "data", "id", mId,
                            "data", Base64.getEncoder().encodeToString(chunk)));
                        try
                        {
                            if(!mResponseAck.tryAcquire(30, TimeUnit.SECONDS) || mClosed.get() || !mAllowed.getAsBoolean())
                                throw new IOException("Remote support response stopped");
                        }
                        catch(InterruptedException exception)
                        { Thread.currentThread().interrupt(); throw new IOException("Remote support response interrupted"); }
                        remaining -= count;
                        offset += count;
                    }
                }
                catch(IOException failure) { cancel(); throw failure; }
            }
            @Override public void close() throws IOException
            {
                if(mClosed.compareAndSet(false, true)) mSender.send(Map.of("type", "end", "id", mId));
            }
        }, CHUNK_BYTES);
    }

    boolean offer(byte[] data) { return !mClosed.get() && mBody.offer(data); }
    String requestId() { return mId; }
    boolean isActive() { return !mClosed.get() && mAllowed.getAsBoolean(); }
    void endRequest() { mBody.end(); }
    void acknowledge()
    { if(mWaitingForAck.compareAndSet(true, false)) mResponseAck.release(); }
    void cancel()
    {
        mClosed.set(true);
        mResponseAck.release();
        mBody.cancel();
    }
    @Override public Headers getRequestHeaders() { return mRequestHeaders; }
    @Override public Headers getResponseHeaders() { return mResponseHeaders; }
    @Override public URI getRequestURI() { return mUri; }
    @Override public String getRequestMethod() { return mMethod; }
    @Override public HttpContext getHttpContext() { return mContext; }
    @Override public InputStream getRequestBody() { return mInput; }
    @Override public OutputStream getResponseBody() { return mOutput; }
    @Override public int getResponseCode() { return mStatus; }
    @Override public void sendResponseHeaders(int status, long length) throws IOException
    {
        if(mStatus != -1 || mClosed.get() || !mAllowed.getAsBoolean()) throw new IOException("Response is unavailable");
        mStatus = status;
        Map<String,List<String>> headers = new LinkedHashMap<>();
        mResponseHeaders.forEach((name, values) -> {
            if(OMIT_HEADERS.contains(name.toLowerCase(java.util.Locale.ROOT))) return;
            List<String> safe = name.equalsIgnoreCase("Set-Cookie") ? values.stream()
                .filter(value -> value.matches(WebRequestSecurity.ALIAS_EXPORT_READY_COOKIE_PREFIX +
                    "[0-9a-f]{32}=1(?:;.*)?"))
                .toList() : List.copyOf(values);
            if(!safe.isEmpty()) headers.put(name, safe);
        });
        if(length > 0) headers.put("Content-Length", List.of(Long.toString(length)));
        mSender.send(Map.of("type", "response", "id", mId, "status", status, "headers", headers));
    }
    @Override public void close()
    {
        try { mOutput.close(); } catch(IOException ignored) { cancel(); }
        mBody.cancel();
    }
    @Override public InetSocketAddress getRemoteAddress()
    { return InetSocketAddress.createUnresolved("remote-support", 443); }
    @Override public InetSocketAddress getLocalAddress()
    { return InetSocketAddress.createUnresolved(mRequestHeaders.getFirst("Host"), 443); }
    @Override public String getProtocol() { return "HTTP/1.1"; }
    @Override public Object getAttribute(String name) { return mAttributes.get(name); }
    @Override public void setAttribute(String name, Object value)
    { if(value == null) mAttributes.remove(name); else mAttributes.put(name, value); }
    @Override public void setStreams(InputStream input, OutputStream output)
    { if(input != null) mInput = input; if(output != null) mOutput = output; }
    @Override public HttpPrincipal getPrincipal() { return null; }
    @Override public SSLSession getSSLSession() { return null; }

    @FunctionalInterface interface Sender { void send(Map<String,?> message) throws IOException; }

    /** Nonblocking producer and a bounded 128 KiB request-body queue. */
    private static final class Body extends InputStream
    {
        private final ArrayBlockingQueue<byte[]> mQueue = new ArrayBlockingQueue<>(4);
        private volatile boolean mEnded;
        private volatile boolean mCancelled;
        private byte[] mCurrent;
        private int mOffset;
        private final Consumed mConsumed;
        Body(Consumed consumed) { mConsumed = consumed; }
        boolean offer(byte[] bytes) { return !mEnded && !mCancelled && mQueue.offer(bytes); }
        void end() { mEnded = true; }
        void cancel() { mCancelled = true; mQueue.clear(); }
        @Override public int read() throws IOException
        {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException
        {
            java.util.Objects.checkFromIndexSize(offset, length, bytes.length);
            if(length == 0) return 0;
            while(mCurrent == null || mOffset == mCurrent.length)
            {
                if(mCancelled) throw new IOException("Remote support request cancelled");
                if(mEnded && mQueue.isEmpty()) return -1;
                try { mCurrent = mQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS); }
                catch(InterruptedException exception)
                { Thread.currentThread().interrupt(); throw new IOException("Remote support request interrupted"); }
                mOffset = 0;
            }
            int count = Math.min(length, mCurrent.length - mOffset);
            System.arraycopy(mCurrent, mOffset, bytes, offset, count);
            mOffset += count;
            if(mOffset == mCurrent.length) mConsumed.acknowledge();
            return count;
        }
        @Override public void close() { cancel(); }
        @FunctionalInterface interface Consumed { void acknowledge() throws IOException; }
    }
}
