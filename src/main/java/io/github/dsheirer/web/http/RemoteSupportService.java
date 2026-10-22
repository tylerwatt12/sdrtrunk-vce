/* Copyright (C) 2026. Licensed under GPL-3.0-or-later. */
package io.github.dsheirer.web.http;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import io.github.dsheirer.web.auth.WebAccessSession;
import io.github.dsheirer.web.auth.WebAuthenticationService;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Receiver-owned outbound support connector. All I/O runs outside the receiver sample and decode paths. */
public final class RemoteSupportService implements AutoCloseable
{
    private static final URI CONNECT = URI.create("wss://radioresolve.com/vce-support/connect");
    private static final URI REVOKE = URI.create("https://radioresolve.com/vce-support/revoke");
    private static final Set<Long> DURATIONS = Set.of(0L, 1800L, 3600L, 21600L, 86400L);
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
    private static final int MAXIMUM_MESSAGE_CHARACTERS = 128 * 1024;
    private final RemoteSupportGrantStore mStore;
    private final Clock mClock;
    private final WebAuthenticationService mAuthentication;
    private final HttpClient mClient;
    private final URI mConnectUri;
    private final URI mRevokeUri;
    private final boolean mRunMaintenance;
    private final ScheduledExecutorService mMaintenance = Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().daemon().name("remote support maintenance").factory());
    private final ThreadPoolExecutor mRequests = new ThreadPoolExecutor(16, 16, 0, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(16), Thread.ofPlatform().daemon().name("remote support request-", 0).factory(),
        new ThreadPoolExecutor.AbortPolicy());
    private RemoteSupportGrantStore.State mState;
    private volatile Map<String,HttpContext> mContexts = Map.of();
    private Connection mConnection;
    private long mNextConnect;
    private long mRetryMillis = 1000;
    private boolean mRevoking;
    private boolean mClosed;
    private boolean mStateNeedsSave;
    private boolean mStopNotSaved;
    private long mNextStateSave;
    private String mMessage = "Remote support is off.";

    public RemoteSupportService(Path applicationRoot, WebAuthenticationService authentication) throws IOException
    {
        this(applicationRoot, authentication, Clock.systemUTC());
    }

    RemoteSupportService(Path applicationRoot, WebAuthenticationService authentication, Clock clock) throws IOException
    {
        this(applicationRoot, authentication, clock, true);
    }

    RemoteSupportService(Path applicationRoot, WebAuthenticationService authentication, Clock clock,
                         boolean runMaintenance) throws IOException
    {
        this(new RemoteSupportGrantStore(applicationRoot), authentication, clock, runMaintenance, CONNECT, REVOKE);
    }

    /** Package-private protocol-test hook; production endpoints are fixed and are not configurable. */
    RemoteSupportService(Path applicationRoot, WebAuthenticationService authentication, Clock clock,
                         URI connect, URI revoke) throws IOException
    {
        this(new RemoteSupportGrantStore(applicationRoot), authentication, clock, true, connect, revoke);
    }

    RemoteSupportService(RemoteSupportGrantStore store, WebAuthenticationService authentication, Clock clock,
                         boolean runMaintenance, URI connect, URI revoke) throws IOException
    {
        mStore = store;
        mAuthentication = authentication;
        mClock = clock;
        mConnectUri = connect;
        mRevokeUri = revoke;
        mRunMaintenance = runMaintenance;
        mState = mStore.read();
        mClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
        if(runMaintenance) mMaintenance.scheduleWithFixedDelay(this::maintain, 0, 1, TimeUnit.SECONDS);
    }

    public void setContexts(Map<String,HttpContext> contexts) { mContexts = Map.copyOf(contexts); }

    /** Used only to initialize background support after restart without opening a local listener. */
    public static boolean hasSavedWork(Path applicationRoot) throws IOException
    {
        RemoteSupportGrantStore.State state = new RemoteSupportGrantStore(applicationRoot).read();
        return state.grant() != null || !state.pending_revocations().isEmpty();
    }

    public synchronized boolean hasActivePermission()
    { return mState.grant() != null && !mState.grant().expired(mClock.millis()); }

    public static boolean isSupportRequest(HttpExchange exchange) { return exchange instanceof RemoteSupportExchange; }
    public static boolean isSupportRequestActive(HttpExchange exchange)
    { return exchange instanceof RemoteSupportExchange support && support.isActive(); }

    public synchronized Map<String,Object> status(boolean canStart)
    {
        RemoteSupportGrantStore.Grant grant = mState.grant();
        boolean enabled = grant != null && !grant.expired(mClock.millis());
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("enabled", enabled);
        result.put("connected", enabled && mConnection != null && mConnection.mReady);
        result.put("code", enabled ? grant.code() : "");
        result.put("expires_at_ms", enabled ? grant.expires_at_ms() : 0L);
        result.put("unlimited", enabled && grant.unlimited());
        result.put("message", mMessage);
        result.put("can_start", canStart);
        result.put("stop_not_saved", mStopNotSaved);
        return result;
    }

    public synchronized void start(long durationSeconds) throws IOException
    {
        if(mClosed) throw new IllegalStateException("Remote support is unavailable");
        if(!DURATIONS.contains(durationSeconds)) throw new IllegalArgumentException("Choose a supported duration");
        List<RemoteSupportGrantStore.Grant> pending = new ArrayList<>(mState.pending_revocations());
        if(mState.grant() != null) pending.add(mState.grant());
        if(pending.size() > 15) throw new IllegalStateException("Connect to the Internet before starting support again");
        SecureRandom random = new SecureRandom();
        byte[] id = new byte[16], secret = new byte[32];
        random.nextBytes(id); random.nextBytes(secret);
        RemoteSupportGrantStore.Grant grant = new RemoteSupportGrantStore.Grant(HexFormat.of().formatHex(id),
            HexFormat.of().formatHex(secret), durationSeconds == 0 ? 0 :
            Math.addExact(mClock.millis(), Math.multiplyExact(durationSeconds, 1000)), durationSeconds == 0, "");
        RemoteSupportGrantStore.State replacement = new RemoteSupportGrantStore.State(1, grant, pending);
        mStore.write(replacement); // Never connect before the permission and credential are durable.
        mState = replacement;
        mStateNeedsSave = false;
        mStopNotSaved = false;
        disconnect(true);
        mNextConnect = 0;
        mRetryMillis = 1000;
        mMessage = "Connecting to remote support…";
        if(mRunMaintenance) mMaintenance.execute(this::maintain);
    }

    public synchronized void stop() throws IOException
    {
        stop(null);
    }

    /** Keeps only the already-authorized Stop reply alive after saving the disabled permission. */
    synchronized void stop(RemoteSupportExchange reply) throws IOException
    {
        if(mState.grant() == null)
        {
            if(mStateNeedsSave)
            {
                mStore.write(mState);
                mStateNeedsSave = false;
                mStopNotSaved = false;
                mMessage = "Remote support is off.";
            }
            return;
        }
        List<RemoteSupportGrantStore.Grant> pending = new ArrayList<>(mState.pending_revocations());
        pending.add(mState.grant());
        RemoteSupportGrantStore.State stopped = new RemoteSupportGrantStore.State(1, null, pending);
        try
        {
            mStore.write(stopped);
            mState = stopped;
            mStateNeedsSave = false;
            mStopNotSaved = false;
            mMessage = "Remote support is off.";
        }
        catch(IOException exception)
        {
            // End access immediately even when the permission cannot be saved. No in-process retry may reconnect.
            mState = stopped;
            mStateNeedsSave = true;
            mNextStateSave = mClock.millis() + 30000;
            try
            {
                mStore.removeActiveState();
                mStopNotSaved = false;
                mMessage = "Remote support is off. Server cleanup will retry while VCE is running.";
            }
            catch(IOException cleanupFailure)
            {
                mStopNotSaved = true;
                mMessage = "Remote access stopped for this run, but Stop could not be saved. Repair the application data folder before restarting VCE.";
                throw new StopPersistenceException(mMessage);
            }
        }
        finally
        {
            if(reply != null && mConnection != null && mConnection.mExchanges.containsValue(reply))
            {
                mConnection.mStopResponseId = reply.requestId();
                mConnection.mStopReplyDeadline = mClock.millis() + 10000;
                mAuthentication.logout(mConnection.mSession.sessionId());
                mConnection.mExchanges.forEach((id, exchange) -> { if(exchange != reply) exchange.cancel(); });
            }
            else disconnect(true);
        }
        if(mRunMaintenance) mMaintenance.execute(this::maintain);
    }

    private synchronized void maintain()
    {
        if(mClosed) return;
        try
        {
            if(mStateNeedsSave && mClock.millis() >= mNextStateSave)
            {
                mNextStateSave = mClock.millis() + 30000;
                try
                {
                    mStore.write(mState);
                    mStateNeedsSave = false;
                    mStopNotSaved = false;
                    if(mState.grant() == null) mMessage = "Remote support is off.";
                }
                catch(IOException ignored) { /* Keep access disabled and permit an explicit Stop retry. */ }
            }
            if(mConnection != null && mConnection.mStopResponseId != null)
            {
                if(mClock.millis() >= mConnection.mStopReplyDeadline) disconnect(true);
                else return;
            }
            if(mState.grant() != null && mState.grant().expired(mClock.millis())) stop();
            revokePending();
            if(mConnection != null && mAuthentication.resolveSession(mConnection.mSession.sessionId()).isEmpty())
                lost(mConnection); // Password resets/capacity eviction recycle only this transport, retaining permission.
            if(mState.grant() == null || mConnection != null || mContexts.isEmpty() ||
                mClock.millis() < mNextConnect) return;
            WebAccessSession session = mAuthentication.openRemoteSupportSession().orElse(null);
            if(session == null)
            {
                mMessage = "Set up the primary administrator before using remote support.";
                return;
            }
            Connection connection = new Connection(mState.grant(), session);
            mConnection = connection;
            mClient.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + connection.mGrant.secret())
                .buildAsync(mConnectUri, connection).whenComplete((socket, failure) -> {
                    if(failure != null) lost(connection);
                });
            mMessage = "Connecting to remote support…";
        }
        catch(IOException | RuntimeException exception)
        { mMessage = "Remote support could not connect. Check the Internet connection and application data folder."; }
    }

    static final class StopPersistenceException extends IOException
    { private StopPersistenceException(String message) { super(message); } }

    private synchronized void revokePending()
    {
        if(mRevoking || mState.pending_revocations().isEmpty()) return;
        RemoteSupportGrantStore.Grant grant = mState.pending_revocations().getFirst();
        mRevoking = true;
        HttpRequest request = HttpRequest.newBuilder(mRevokeUri).timeout(Duration.ofSeconds(10))
            .header("Authorization", "Bearer " + grant.secret()).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"grant_id\":\"" + grant.grant_id() + "\"}")).build();
        mClient.sendAsync(request, HttpResponse.BodyHandlers.discarding()).whenComplete((response, failure) -> {
            synchronized(RemoteSupportService.this)
            {
                mRevoking = false;
                if(mClosed || failure != null || response.statusCode() < 200 || response.statusCode() >= 300) return;
                List<RemoteSupportGrantStore.Grant> remaining = mState.pending_revocations().stream()
                    .filter(value -> !value.grant_id().equals(grant.grant_id())).toList();
                RemoteSupportGrantStore.State replacement = new RemoteSupportGrantStore.State(1, mState.grant(), remaining);
                try
                {
                    mStore.write(replacement);
                    mState = replacement;
                    mStateNeedsSave = false;
                    if(mStopNotSaved && mState.grant() == null) mMessage = "Remote support is off.";
                    mStopNotSaved = false;
                }
                catch(IOException ignored) { /* Retain the durable revocation for a later retry. */ }
            }
        });
    }

    private synchronized boolean allowed(Connection connection)
    {
        return !mClosed && mConnection == connection && mState.grant() != null &&
            mState.grant().grant_id().equals(connection.mGrant.grant_id()) &&
            !mState.grant().expired(mClock.millis());
    }

    private synchronized boolean responseAllowed(Connection connection, String id)
    {
        return allowed(connection) || !mClosed && mConnection == connection &&
            id.equals(connection.mStopResponseId) && mClock.millis() < connection.mStopReplyDeadline;
    }

    private synchronized void lost(Connection connection)
    {
        connection.cancelRequests();
        mAuthentication.logout(connection.mSession.sessionId());
        if(connection.mSocket != null) connection.mSocket.abort();
        if(mConnection != connection) return;
        mConnection = null;
        mNextConnect = mClock.millis() + mRetryMillis;
        mRetryMillis = Math.min(30000, mRetryMillis * 2);
        if(mState.grant() != null) mMessage = "Connection lost. Remote support will reconnect automatically.";
    }

    private synchronized void disconnect(boolean revoke)
    {
        Connection previous = mConnection;
        mConnection = null;
        if(previous == null) return;
        previous.cancelRequests();
        mAuthentication.logout(previous.mSession.sessionId());
        if(previous.mSocket != null)
        {
            if(revoke) previous.mSocket.sendText("{\"type\":\"revoke\"}", true)
                .whenComplete((ignored, failure) -> previous.mSocket.abort());
            else previous.mSocket.abort();
        }
    }

    @Override public synchronized void close()
    {
        mClosed = true;
        disconnect(false); // Receiver shutdown preserves the saved grant and its original deadline.
        mMaintenance.shutdownNow();
        mRequests.shutdownNow();
        mClient.shutdown();
    }

    private final class Connection implements WebSocket.Listener
    {
        private final RemoteSupportGrantStore.Grant mGrant;
        private final WebAccessSession mSession;
        private final Map<String,RemoteSupportExchange> mExchanges = new ConcurrentHashMap<>();
        private final StringBuilder mText = new StringBuilder();
        private final Object mSendLock = new Object();
        private volatile WebSocket mSocket;
        private volatile boolean mReady;
        private volatile String mStopResponseId;
        private volatile long mStopReplyDeadline;

        private Connection(RemoteSupportGrantStore.Grant grant, WebAccessSession session)
        { mGrant = grant; mSession = session; }

        @Override public void onOpen(WebSocket socket)
        {
            mSocket = socket;
            if(!allowed(this)) { socket.abort(); return; }
            socket.sendText("{\"type\":\"hello\",\"grant_id\":\"" + mGrant.grant_id() +
                "\",\"name\":\"VCE installation\",\"expires_at_ms\":" + mGrant.expires_at_ms() +
                ",\"unlimited\":" + mGrant.unlimited() + "}", true).whenComplete((ignored, failure) -> {
                    if(failure != null) lost(this); else socket.request(1);
                });
        }

        @Override public CompletionStage<?> onText(WebSocket socket, CharSequence text, boolean last)
        {
            try
            {
                if((!allowed(this) && mStopResponseId == null) || mText.length() + text.length() > MAXIMUM_MESSAGE_CHARACTERS)
                    throw new IOException("Remote support message rejected");
                mText.append(text);
                if(last)
                {
                    JsonNode message = JSON.readTree(mText.toString());
                    mText.setLength(0);
                    receive(message);
                }
                socket.request(1);
            }
            catch(IOException | RuntimeException exception) { lost(this); }
            return null;
        }

        private void receive(JsonNode message) throws IOException
        {
            if(message == null || !message.isObject()) throw new IOException("Invalid support message");
            String type = message.path("type").asText();
            if(mStopResponseId != null)
            {
                if(type.equals("ack") && mStopResponseId.equals(message.path("id").asText()))
                {
                    RemoteSupportExchange reply = mExchanges.get(mStopResponseId);
                    if(reply != null) reply.acknowledge();
                }
                return;
            }
            if(!mReady)
            {
                if(!type.equals("hello") || !message.path("code").asText().matches("[0-9]{9}") ||
                    !mGrant.host().equals(message.path("host").asText())) throw new IOException("Invalid support greeting");
                synchronized(RemoteSupportService.this)
                {
                    if(!allowed(this)) throw new IOException("Support ended");
                    RemoteSupportGrantStore.Grant grant = mState.grant().withCode(message.path("code").asText());
                    RemoteSupportGrantStore.State replacement =
                        new RemoteSupportGrantStore.State(1, grant, mState.pending_revocations());
                    mStore.write(replacement);
                    mState = replacement;
                    mReady = true;
                    mRetryMillis = 1000;
                    mMessage = "Remote support is ready.";
                }
                return;
            }
            String id = message.path("id").asText();
            if(!id.matches("[0-9]{1,20}")) throw new IOException("Invalid support request identifier");
            if(type.equals("request")) { request(id, message); return; }
            RemoteSupportExchange exchange = mExchanges.get(id);
            if(exchange == null) return; // A completed/cancelled request can have an in-flight final frame.
            switch(type)
            {
                case "data" -> {
                    String encoded = message.path("data").asText();
                    if(encoded.length() > 43692) throw new IOException("Support request chunk exceeds limit");
                    byte[] bytes = Base64.getDecoder().decode(encoded);
                    if(bytes.length == 0 || bytes.length > RemoteSupportExchange.CHUNK_BYTES || !exchange.offer(bytes))
                    { exchange.cancel(); send(Map.of("type", "cancel", "id", id)); }
                }
                case "end" -> exchange.endRequest();
                case "ack" -> exchange.acknowledge();
                case "cancel" -> exchange.cancel();
                default -> throw new IOException("Invalid support message type");
            }
        }

        private void request(String id, JsonNode message) throws IOException
        {
            if(mExchanges.size() >= 16 || mExchanges.containsKey(id)) throw new IOException("Support request limit reached");
            String method = message.path("method").asText();
            String path = message.path("path").asText();
            if(!Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS").contains(method) ||
                path.length() > 16384 || !path.startsWith("/") || path.startsWith("//") ||
                path.indexOf('\\') >= 0 || path.chars().anyMatch(value -> value < 32 || value == 127))
                throw new IOException("Invalid support request target");
            URI uri = URI.create(path);
            if(uri.isAbsolute() || uri.getRawAuthority() != null || uri.getRawFragment() != null)
                throw new IOException("Invalid support request URI");
            Map<String,List<String>> headers = headers(message.path("headers"));
            HttpContext context = mContexts.entrySet().stream()
                .filter(entry -> uri.getPath().startsWith(entry.getKey()))
                .max(Comparator.comparingInt(entry -> entry.getKey().length())).map(Map.Entry::getValue)
                .orElseThrow(() -> new IOException("Support route unavailable"));
            RemoteSupportExchange exchange = new RemoteSupportExchange(id, method, uri, headers, mGrant.host(),
                mSession.sessionId(), context, this::send, () -> responseAllowed(this, id));
            mExchanges.put(id, exchange);
            try
            {
                mRequests.execute(() -> {
                    try
                    {
                        if(!allowed(this)) throw new IOException("Support ended");
                        if(Set.of(WebSessionHttpController.LOGIN_PATH, WebSessionHttpController.DESKTOP_HANDOFF_PATH,
                            WebSessionHttpController.LOGOUT_PATH).contains(uri.getPath()))
                            WebHttpSupport.sendError(exchange, 403, "support_session", "Use the remote support page to sign out.");
                        else new Filter.Chain(context.getFilters(), context.getHandler()).doFilter(exchange);
                    }
                    catch(IOException | RuntimeException failure)
                    {
                        if(exchange.getResponseCode() < 0)
                            try { WebHttpSupport.sendError(exchange, 502, "support_request_failed",
                                "The remote request was interrupted. Check its result before trying again."); }
                            catch(IOException ignored) { exchange.cancel(); }
                    }
                    finally
                    {
                        exchange.close();
                        mExchanges.remove(id, exchange);
                        synchronized(RemoteSupportService.this)
                        {
                            if(id.equals(mStopResponseId) && mConnection == this)
                            { disconnect(true); if(mRunMaintenance) mMaintenance.execute(RemoteSupportService.this::maintain); }
                        }
                    }
                });
            }
            catch(RuntimeException exception) { mExchanges.remove(id); exchange.cancel(); throw exception; }
        }

        private Map<String,List<String>> headers(JsonNode values) throws IOException
        {
            if(!values.isObject() || values.size() > 64) throw new IOException("Support request headers exceed limit");
            Map<String,List<String>> result = new LinkedHashMap<>();
            int total = 0;
            var fields = values.fields();
            while(fields.hasNext())
            {
                var field = fields.next();
                if(!field.getKey().matches("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}") ||
                    !field.getValue().isArray() || field.getValue().size() > 16)
                    throw new IOException("Invalid support request headers");
                List<String> entries = new ArrayList<>();
                for(JsonNode value: field.getValue())
                {
                    if(!value.isTextual() || value.asText().indexOf('\r') >= 0 || value.asText().indexOf('\n') >= 0)
                        throw new IOException("Invalid support request header");
                    total += field.getKey().length() + value.asText().length();
                    if(total > 32768) throw new IOException("Support request headers exceed limit");
                    entries.add(value.asText());
                }
                result.put(field.getKey(), entries);
            }
            return result;
        }

        private void send(Map<String,?> message) throws IOException
        {
            if(!responseAllowed(this, String.valueOf(message.get("id"))) || mSocket == null)
                throw new IOException("Support connection ended");
            synchronized(mSendLock)
            {
                try { mSocket.sendText(JSON.writeValueAsString(message), true).get(10, TimeUnit.SECONDS); }
                catch(Exception exception)
                { if(exception instanceof InterruptedException) Thread.currentThread().interrupt();
                    throw new IOException("Support response interrupted"); }
            }
        }
        private void cancelRequests() { mReady = false; mExchanges.values().forEach(RemoteSupportExchange::cancel); }
        @Override public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason)
        { lost(this); return null; }
        @Override public void onError(WebSocket socket, Throwable error) { lost(this); }
    }
}
