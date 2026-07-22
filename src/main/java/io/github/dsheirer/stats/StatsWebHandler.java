/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.stats;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.web.WebResponses;
import io.github.dsheirer.web.access.AuthorizationSubject;
import io.github.dsheirer.web.access.FeatureAccessGateway;
import io.github.dsheirer.web.access.InMemoryFeatureAccessPolicy;
import io.github.dsheirer.web.access.RemoteAddressAdmissionPolicy;
import io.github.dsheirer.web.access.WebFeature;
import io.github.dsheirer.web.access.WebRequestSubjectResolver;
import io.github.dsheirer.web.access.WebRequestSubjectResolver.WebAuthorization;
import io.github.dsheirer.web.access.WebTransport;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.Graceful;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Jetty route handler for the existing Stats web API and static site.
 *
 * <p>Database requests use Jetty's bounded worker pool. Long-lived SSE responses and bounded static-file transfers
 * run on virtual threads owned by this handler, so an idle browser never reserves a Jetty platform thread. The live
 * event hubs retain their existing subscriber caps and drop-oldest queues; this handler adds a total asynchronous
 * stream cap as a second resource bound.</p>
 */
public final class StatsWebHandler extends Handler.Abstract implements AutoCloseable, Graceful
{
    private static final Logger mLog = LoggerFactory.getLogger(StatsWebHandler.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final ByteBuffer EMPTY_BUFFER = ByteBuffer.allocate(0).asReadOnlyBuffer();
    private static final int STATIC_CHUNK_BYTES = 32 * 1024;
    private static final int MAXIMUM_ASYNC_STREAMS = 128;
    private static final long STREAM_SHUTDOWN_SECONDS = 2;
    private static final long SSE_AUTHORIZATION_RECHECK_MILLISECONDS = 250;
    private static final long SSE_HEARTBEAT_SECONDS = 15;
    private static final FeatureAuthorization UNRESTRICTED_AUTHORIZATION =
        new FeatureAuthorization(null, null, WebAuthorization.permanent(AuthorizationSubject.ANONYMOUS));

    private final Path mAssetRoot;
    private final RemoteAddressAdmissionPolicy mRemoteAddressAdmissionPolicy;
    private final StatsWebDatabase mDatabase;
    private final StatsLiveService mLiveService;
    private final StatsWebCallService mWebCallService;
    private final Supplier<Map<String,Object>> mStatusSupplier;
    private final FeatureAccessGateway mFeatureAccessGateway;
    private final WebRequestSubjectResolver mSubjectResolver;
    private final Set<StatsLiveEventHub.Subscription> mActiveSubscriptions =
        java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Semaphore mAsyncStreamPermits = new Semaphore(MAXIMUM_ASYNC_STREAMS);
    private final AtomicInteger mActiveStreamCount = new AtomicInteger();
    private final AtomicBoolean mAcceptingStreams = new AtomicBoolean();
    private ExecutorService mStreamExecutor;

    StatsWebHandler(Path assetRoot, StatsWebDatabase database, StatsLiveService liveService,
                    StatsWebCallService webCallService, Supplier<Map<String,Object>> statusSupplier)
    {
        this(assetRoot, database, liveService, webCallService, statusSupplier,
            InMemoryFeatureAccessPolicy.currentProfileDefaults(), WebRequestSubjectResolver.anonymous(),
            RemoteAddressAdmissionPolicy.allowAll());
    }

    StatsWebHandler(Path assetRoot, StatsWebDatabase database, StatsLiveService liveService,
                    StatsWebCallService webCallService, Supplier<Map<String,Object>> statusSupplier,
                    FeatureAccessGateway featureAccessGateway, WebRequestSubjectResolver subjectResolver)
    {
        this(assetRoot, database, liveService, webCallService, statusSupplier, featureAccessGateway,
            subjectResolver, RemoteAddressAdmissionPolicy.allowAll());
    }

    StatsWebHandler(Path assetRoot, StatsWebDatabase database, StatsLiveService liveService,
                    StatsWebCallService webCallService, Supplier<Map<String,Object>> statusSupplier,
                    FeatureAccessGateway featureAccessGateway, WebRequestSubjectResolver subjectResolver,
                    RemoteAddressAdmissionPolicy remoteAddressAdmissionPolicy)
    {
        mAssetRoot = Objects.requireNonNull(assetRoot, "Stats web asset root cannot be null")
            .toAbsolutePath().normalize();
        mDatabase = Objects.requireNonNull(database, "Stats web database cannot be null");
        mLiveService = Objects.requireNonNull(liveService, "Stats live service cannot be null");
        mWebCallService = Objects.requireNonNull(webCallService, "Stats web call service cannot be null");
        mStatusSupplier = Objects.requireNonNull(statusSupplier, "Stats status supplier cannot be null");
        mFeatureAccessGateway = Objects.requireNonNull(featureAccessGateway,
            "Feature access gateway cannot be null");
        mSubjectResolver = Objects.requireNonNull(subjectResolver, "Web request subject resolver cannot be null");
        mRemoteAddressAdmissionPolicy = Objects.requireNonNull(remoteAddressAdmissionPolicy,
            "Remote-address admission policy cannot be null");
    }

    @Override
    protected void doStart() throws Exception
    {
        mStreamExecutor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("stats web stream-", 0).factory());
        mAcceptingStreams.set(true);
        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        shutdownStreams();
        super.doStop();
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback)
    {
        try
        {
            prepareSecurityHeaders(response);

            if(!isAllowed(request, response, callback))
            {
                return true;
            }

            String path = Request.getPathInContext(request);

            if(path == null)
            {
                sendText(response, callback, 400, "Invalid request path");
            }
            else
            {
                FeatureAuthorization authorization = authorizeFeature(request, response, callback, path);

                if(authorization == null)
                {
                    return true;
                }

                switch(path)
                {
                    case "/api/status" -> handleJson(request, response, callback, mStatusSupplier::get);
                    case "/api/dashboard" -> handleJson(request, response, callback, mDatabase::dashboard);
                    case "/api/quality" -> handleJson(request, response, callback,
                        () -> mDatabase.qualityHistory(statsRequest(request)));
                    case "/api/systems" -> handleJson(request, response, callback,
                        () -> mDatabase.systems(statsRequest(request)));
                    case "/api/system-directory" -> handleJson(request, response, callback,
                        () -> mDatabase.systemDirectory(statsRequest(request)));
                    case "/api/sites" -> handleJson(request, response, callback,
                        () -> mDatabase.sites(statsRequest(request)));
                    case "/api/system" -> handleJson(request, response, callback,
                        () -> mDatabase.system(statsRequest(request)));
                    case "/api/system/sites" -> handleJson(request, response, callback,
                        () -> mDatabase.systemSites(statsRequest(request)));
                    case "/api/system/talkgroups" -> handleJson(request, response, callback,
                        () -> mDatabase.systemTalkgroups(statsRequest(request)));
                    case "/api/system/radios" -> handleJson(request, response, callback,
                        () -> mDatabase.systemRadios(statsRequest(request)));
                    case "/api/system/talker-aliases" -> handleJson(request, response, callback,
                        () -> mDatabase.systemTalkerAliases(statsRequest(request)));
                    case "/api/talkgroup" -> handleJson(request, response, callback,
                        () -> mDatabase.talkgroup(statsRequest(request)));
                    case "/api/talkgroup/activity" -> handleJson(request, response, callback,
                        () -> mDatabase.talkgroupActivity(statsRequest(request)));
                    case "/api/radio" -> handleJson(request, response, callback,
                        () -> mDatabase.radio(statsRequest(request)));
                    case "/api/affiliations" -> handleJson(request, response, callback,
                        () -> mDatabase.currentAffiliations(statsRequest(request)));
                    case "/api/radio-talkgroups" -> handleJson(request, response, callback,
                        () -> mDatabase.radioTalkgroupRelationships(statsRequest(request)));
                    case "/api/site" -> handleJson(request, response, callback,
                        () -> mDatabase.site(statsRequest(request)));
                    case "/api/site/channels" -> handleJson(request, response, callback,
                        () -> mDatabase.siteChannels(statsRequest(request)));
                    case "/api/site/talkgroups" -> handleJson(request, response, callback,
                        () -> mDatabase.siteTalkgroups(statsRequest(request)));
                    case "/api/site/quality" -> handleJson(request, response, callback,
                        () -> mDatabase.siteQuality(statsRequest(request)));
                    case "/api/site/bands" -> handleJson(request, response, callback,
                        () -> mDatabase.siteBands(statsRequest(request)));
                    case "/api/site/neighbors" -> handleJson(request, response, callback,
                        () -> mDatabase.siteNeighbors(statsRequest(request)));
                    case "/api/site/patches" -> handleJson(request, response, callback,
                        () -> mDatabase.sitePatches(statsRequest(request)));
                    case "/api/activity", "/api/activity/recent" -> handleJson(request, response, callback,
                        () -> mDatabase.activity(statsRequest(request)));
                    case "/api/conventional" -> handleJson(request, response, callback,
                        () -> mDatabase.conventional(statsRequest(request)));
                    case "/api/conventional/detail" -> handleJson(request, response, callback,
                        () -> mDatabase.conventionalDetail(statsRequest(request)));
                    case "/live/systems" -> handleSystemsSse(request, response, callback, authorization);
                    case "/live/web-calls" -> handleWebCallsSse(request, response, callback, authorization);
                    case "/live/activity" -> handleActivitySse(request, response, callback, authorization);
                    default -> {
                        if(path.startsWith("/api/web-player/calls/"))
                        {
                            handleWebCallAudio(request, response, callback, path);
                        }
                        else
                        {
                            handleStatic(request, response, callback, path);
                        }
                    }
                }
            }
        }
        catch(Throwable throwable)
        {
            if(response.isCommitted())
            {
                callback.failed(throwable);
            }
            else
            {
                mLog.warn("Stats web request failed [{}]", request.getHttpURI().getPath(), throwable);
                sendText(response, callback, 500, "Stats request failed");
            }
        }

        return true;
    }

    private FeatureAuthorization authorizeFeature(Request request, Response response, Callback callback, String path)
    {
        WebFeature feature;
        WebTransport transport;

        if("/live/web-calls".equals(path) || path.startsWith("/api/web-player/calls/"))
        {
            feature = WebFeature.CALL_AUDIO;
            transport = path.startsWith("/live/") ? WebTransport.SSE : WebTransport.MEDIA;
        }
        else if(path.startsWith("/api/") || path.startsWith("/live/"))
        {
            feature = WebFeature.STATUS_STATISTICS;
            transport = path.startsWith("/live/") ? WebTransport.SSE : WebTransport.HTTP;
        }
        else
        {
            // The public shell and immutable assets must load so a locked feature can present its sign-in state.
            return UNRESTRICTED_AUTHORIZATION;
        }

        WebAuthorization authorization;

        try
        {
            authorization = Objects.requireNonNull(mSubjectResolver.resolveAuthorization(request),
                "Subject resolver returned null authorization");
        }
        catch(RuntimeException exception)
        {
            authorization = WebAuthorization.permanent(AuthorizationSubject.ANONYMOUS);
        }

        FeatureAuthorization featureAuthorization = new FeatureAuthorization(feature, transport, authorization);

        if(isAuthorized(featureAuthorization))
        {
            return featureAuthorization;
        }

        response.getHeaders().put(HttpHeader.WWW_AUTHENTICATE, "Bearer realm=\"sdrtrunk-admin\"");
        sendText(response, callback, 401, "Administrator sign-in required");
        return null;
    }

    private static StatsRequest statsRequest(Request request)
    {
        return StatsRequest.from(request.getHttpURI().toURI());
    }

    private void handleJson(Request request, Response response, Callback callback, JsonSupplier supplier)
    {
        if(!requireMethod(request, response, callback, "GET"))
        {
            return;
        }

        try
        {
            sendJson(response, callback, 200, supplier.get());
        }
        catch(StatsApiException exception)
        {
            sendJson(response, callback, exception.status(),
                Map.of("error", exception.getMessage(), "status", exception.status()));
        }
        catch(RuntimeException exception)
        {
            mLog.warn("Stats web request failed [{}]", request.getHttpURI().getPath(), exception);
            sendJson(response, callback, 500, Map.of("error", "Stats request failed", "status", 500));
        }
    }

    private static void sendJson(Response response, Callback callback, int status, Map<String,Object> value)
    {
        try
        {
            WebResponses.json(response, callback, status, OBJECT_MAPPER.writeValueAsBytes(value));
        }
        catch(JsonProcessingException exception)
        {
            byte[] fallback = "{\"error\":\"Unable to encode response\",\"status\":500}"
                .getBytes(StandardCharsets.UTF_8);
            WebResponses.json(response, callback, 500, fallback);
        }
    }

    private void handleSystemsSse(Request request, Response response, Callback callback,
                                  FeatureAuthorization authorization)
    {
        if(!requireMethod(request, response, callback, "GET"))
        {
            return;
        }

        StatsLiveEventHub.Subscription subscription = mLiveService.subscribeSystems();

        if(subscription == null)
        {
            sendText(response, callback, 429, "Too many live Stats Server clients");
            return;
        }

        streamSse(request, response, callback, subscription, "snapshot", mLiveService.snapshot(), event -> true,
            authorization);
    }

    private void handleWebCallsSse(Request request, Response response, Callback callback,
                                   FeatureAuthorization authorization)
    {
        if(!requireMethod(request, response, callback, "GET"))
        {
            return;
        }

        StatsLiveEventHub.Subscription subscription = mWebCallService.subscribe();

        if(subscription == null)
        {
            sendText(response, callback, 429, "Too many live Stats Server clients");
            return;
        }

        streamSse(request, response, callback, subscription, "ready", Map.of("state", "live"),
            event -> "call".equals(event.name()), authorization);
    }

    private void handleActivitySse(Request request, Response response, Callback callback,
                                   FeatureAuthorization authorization)
    {
        if(!requireMethod(request, response, callback, "GET"))
        {
            return;
        }

        StatsRequest statsRequest = statsRequest(request);

        try
        {
            validateActivityRequest(statsRequest);
        }
        catch(StatsApiException exception)
        {
            sendText(response, callback, exception.status(), exception.getMessage());
            return;
        }

        StatsLiveEventHub.Subscription subscription = mLiveService.subscribeActivity();

        if(subscription == null)
        {
            sendText(response, callback, 429, "Too many live Stats Server clients");
            return;
        }

        streamSse(request, response, callback, subscription, "ready", Map.of("state", "live"),
            event -> event.data() instanceof Map<?,?> row && matchesActivity(row, statsRequest), authorization);
    }

    private void streamSse(Request request, Response response, Callback callback,
                           StatsLiveEventHub.Subscription subscription, String initialEvent, Object initialData,
                           Predicate<StatsLiveEventHub.LiveEvent> filter, FeatureAuthorization authorization)
    {
        if(!isAuthorized(authorization))
        {
            subscription.close();
            response.getHeaders().put(HttpHeader.WWW_AUTHENTICATE, "Bearer realm=\"sdrtrunk-admin\"");
            sendText(response, callback, 401, "Administrator sign-in required");
            return;
        }

        if(!mAcceptingStreams.get() || !mAsyncStreamPermits.tryAcquire())
        {
            subscription.close();
            sendText(response, callback, 429, "Too many live Stats Server clients");
            return;
        }

        response.setStatus(200);
        response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/event-stream; charset=utf-8");
        response.getHeaders().put(HttpHeader.CACHE_CONTROL, "no-store");
        response.getHeaders().put(HttpHeader.CONNECTION, "keep-alive");
        response.getHeaders().put("X-Accel-Buffering", "no");
        mActiveSubscriptions.add(subscription);
        request.addFailureListener(failure -> subscription.close());

        if(!executeStream(() -> runSse(response, callback, subscription, initialEvent, initialData, filter,
            authorization)))
        {
            mActiveSubscriptions.remove(subscription);
            subscription.close();
            mAsyncStreamPermits.release();
            sendText(response, callback, 503, "Stats live stream is stopping");
        }
    }

    private void runSse(Response response, Callback callback, StatsLiveEventHub.Subscription subscription,
                        String initialEvent, Object initialData, Predicate<StatsLiveEventHub.LiveEvent> filter,
                        FeatureAuthorization authorization)
    {
        Throwable failure = null;

        try(subscription)
        {
            if(!isAuthorized(authorization))
            {
                writeChunk(response, new byte[0], true);
                return;
            }

            writeChunk(response, sseEvent(initialEvent, initialData), false);
            long lastWriteNanos = System.nanoTime();

            while(mAcceptingStreams.get() && !subscription.isClosed())
            {
                if(!isAuthorized(authorization))
                {
                    break;
                }

                StatsLiveEventHub.LiveEvent event = subscription.poll(SSE_AUTHORIZATION_RECHECK_MILLISECONDS,
                    TimeUnit.MILLISECONDS);

                if(event != null)
                {
                    // Recheck after the blocking poll so a revocation can never race with delivery of a queued event.
                    if(!isAuthorized(authorization))
                    {
                        break;
                    }

                    if(filter.test(event))
                    {
                        writeChunk(response, sseEvent(event.name(), event.data()), false);
                        lastWriteNanos = System.nanoTime();
                    }
                }
                else if(System.nanoTime() - lastWriteNanos >= TimeUnit.SECONDS.toNanos(SSE_HEARTBEAT_SECONDS))
                {
                    writeChunk(response, (": heartbeat " + System.currentTimeMillis() + "\n\n")
                        .getBytes(StandardCharsets.UTF_8), false);
                    lastWriteNanos = System.nanoTime();
                }
            }

            writeChunk(response, new byte[0], true);
        }
        catch(InterruptedException exception)
        {
            Thread.currentThread().interrupt();
            failure = exception;
        }
        catch(IOException | RuntimeException exception)
        {
            // Most IO failures are ordinary browser disconnects.
            failure = exception;
        }
        finally
        {
            mActiveSubscriptions.remove(subscription);
            mAsyncStreamPermits.release();
            mActiveStreamCount.decrementAndGet();

            if(failure == null)
            {
                callback.succeeded();
            }
            else
            {
                callback.failed(failure);
            }
        }
    }

    private boolean isAuthorized(FeatureAuthorization authorization)
    {
        if(authorization == null || !authorization.authorization().isSessionValid())
        {
            return false;
        }

        if(authorization.feature() == null)
        {
            return true;
        }

        try
        {
            return mFeatureAccessGateway.authorize(authorization.feature(), authorization.authorization().subject(),
                authorization.transport()).isAllowed();
        }
        catch(RuntimeException exception)
        {
            return false;
        }
    }

    private void handleWebCallAudio(Request request, Response response, Callback callback, String path)
    {
        if(!requireMethod(request, response, callback, "GET"))
        {
            return;
        }

        String prefix = "/api/web-player/calls/";

        if(!path.endsWith("/audio"))
        {
            sendText(response, callback, 404, "Call audio not found");
            return;
        }

        String id = path.substring(prefix.length(), path.length() - "/audio".length());
        StatsWebCallService.CachedCall call = mWebCallService.get(id);

        if(call == null)
        {
            sendText(response, callback, 404, "Call audio is no longer available");
            return;
        }

        byte[] wave = call.wave();
        response.setStatus(200);
        response.getHeaders().put(HttpHeader.CONTENT_TYPE, "audio/wav");
        response.getHeaders().put(HttpHeader.CACHE_CONTROL, "no-store, no-transform");
        response.getHeaders().put(HttpHeader.ACCEPT_RANGES, "none");
        response.getHeaders().put(HttpHeader.CONTENT_LENGTH, wave.length);
        response.write(true, ByteBuffer.wrap(wave), callback);
    }

    private void handleStatic(Request request, Response response, Callback callback, String path)
    {
        if(!requireMethod(request, response, callback, "GET", "HEAD"))
        {
            return;
        }

        String requestPath = request.getHttpURI().getDecodedPath();

        if(requestPath == null || requestPath.equals("/") || requestPath.isBlank())
        {
            requestPath = "/index.html";
        }

        if(!requestPath.startsWith("/"))
        {
            sendText(response, callback, 403, "Forbidden");
            return;
        }

        Path file;

        try
        {
            file = mAssetRoot.resolve(requestPath.substring(1)).normalize();
        }
        catch(InvalidPathException exception)
        {
            sendText(response, callback, 400, "Invalid request path");
            return;
        }

        if(!file.startsWith(mAssetRoot))
        {
            sendText(response, callback, 403, "Forbidden");
            return;
        }

        if(!Files.isRegularFile(file))
        {
            if("/index.html".equals(requestPath))
            {
                sendMissingAssetsPage(response, callback);
            }
            else
            {
                sendText(response, callback, 404, "Not found");
            }

            return;
        }

        try
        {
            Path realRoot = mAssetRoot.toRealPath();
            Path realFile = file.toRealPath();

            if(!realFile.startsWith(realRoot))
            {
                sendText(response, callback, 403, "Forbidden");
                return;
            }

            long length = Files.size(realFile);
            response.setStatus(200);
            response.getHeaders().put(HttpHeader.CONTENT_TYPE, contentType(realFile));
            response.getHeaders().put(HttpHeader.CACHE_CONTROL, "no-cache");
            response.getHeaders().put(HttpHeader.CONTENT_LENGTH, length);

            if("HEAD".equals(request.getMethod()))
            {
                response.write(true, EMPTY_BUFFER.duplicate(), callback);
            }
            else if(!mAcceptingStreams.get() || !mAsyncStreamPermits.tryAcquire())
            {
                response.reset();
                sendText(response, callback, 503, "Too many active web transfers");
            }
            else if(!executeStream(() -> streamFile(response, callback, realFile, length)))
            {
                mAsyncStreamPermits.release();
                response.reset();
                sendText(response, callback, 503, "Web transfer service is stopping");
            }
        }
        catch(IOException exception)
        {
            sendText(response, callback, 404, "Not found");
        }
    }

    private void streamFile(Response response, Callback callback, Path file, long length)
    {
        Throwable failure = null;

        try(InputStream inputStream = Files.newInputStream(file))
        {
            long remaining = length;

            if(remaining == 0)
            {
                writeChunk(response, new byte[0], true);
            }

            while(remaining > 0)
            {
                int target = (int)Math.min(STATIC_CHUNK_BYTES, remaining);
                byte[] bytes = inputStream.readNBytes(target);

                if(bytes.length != target)
                {
                    throw new IOException("Static asset changed while it was being served");
                }

                remaining -= bytes.length;
                writeChunk(response, bytes, remaining == 0);
            }
        }
        catch(InterruptedException exception)
        {
            Thread.currentThread().interrupt();
            failure = exception;
        }
        catch(IOException | RuntimeException exception)
        {
            failure = exception;
        }
        finally
        {
            mAsyncStreamPermits.release();
            mActiveStreamCount.decrementAndGet();

            if(failure == null)
            {
                callback.succeeded();
            }
            else
            {
                callback.failed(failure);
            }
        }
    }

    private boolean executeStream(Runnable task)
    {
        ExecutorService executor = mStreamExecutor;

        if(executor == null || executor.isShutdown())
        {
            return false;
        }

        try
        {
            mActiveStreamCount.incrementAndGet();
            executor.execute(task);
            return true;
        }
        catch(RejectedExecutionException exception)
        {
            mActiveStreamCount.decrementAndGet();
            return false;
        }
    }

    private static void writeChunk(Response response, byte[] bytes, boolean last)
        throws IOException, InterruptedException
    {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        response.write(last, bytes.length == 0 ? EMPTY_BUFFER.duplicate() : ByteBuffer.wrap(bytes),
            Callback.from(completion));

        try
        {
            completion.get();
        }
        catch(ExecutionException exception)
        {
            Throwable cause = exception.getCause();

            if(cause instanceof IOException ioException)
            {
                throw ioException;
            }

            throw new IOException("Unable to write web response", cause);
        }
    }

    private static byte[] sseEvent(String event, Object data) throws JsonProcessingException
    {
        return ("event: " + event + "\ndata: " + OBJECT_MAPPER.writeValueAsString(data) + "\n\n")
            .getBytes(StandardCharsets.UTF_8);
    }

    private void sendMissingAssetsPage(Response response, Callback callback)
    {
        sendHtml(response, callback, 200, """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <title>sdrtrunk-vce</title>
              <style>body{font-family:Arial,sans-serif;margin:2rem;line-height:1.4}</style>
            </head>
            <body>
              <h1>sdrtrunk-vce</h1>
              <p>No web assets were found in:</p>
              <pre>%s</pre>
            </body>
            </html>
            """.formatted(mAssetRoot));
    }

    private static boolean matchesActivity(Map<?,?> row, StatsRequest request)
    {
        Integer wacn = request.optionalIdentifier("wacn");
        Integer system = request.optionalIdentifier("system_id");
        Integer talkgroup = request.optionalIdentifier("talkgroup_id");
        Integer radio = request.optionalIdentifier("radio_id");
        String guid = request.text("guid");
        String context = request.text("context");

        if(wacn != null && !numberEquals(row.get("wacn"), wacn) ||
            system != null && !numberEquals(row.get("system_id"), system) ||
            guid != null && !guid.equals(row.get("guid")) ||
            context != null && !context.equals(row.get("context_key")))
        {
            return false;
        }

        if(talkgroup != null && (!numberEquals(row.get("target_id"), talkgroup) ||
            !(numberEquals(row.get("target_kind_code"), 1) || numberEquals(row.get("target_kind_code"), 3))))
        {
            return false;
        }

        return radio == null || numberEquals(row.get("source_radio_id"), radio) ||
            numberEquals(row.get("target_id"), radio) && numberEquals(row.get("target_kind_code"), 2);
    }

    private static boolean numberEquals(Object value, int expected)
    {
        return value instanceof Number number && number.intValue() == expected;
    }

    private static void validateActivityRequest(StatsRequest request)
    {
        request.optionalIdentifier("wacn");
        request.optionalIdentifier("system_id");
        request.optionalIdentifier("talkgroup_id");
        request.optionalIdentifier("radio_id");
    }

    private boolean isAllowed(Request request, Response response, Callback callback)
    {
        if(mRemoteAddressAdmissionPolicy.isAllowed(request))
        {
            return true;
        }

        sendText(response, callback, 403, "Request source is not admitted.");

        return false;
    }

    private static void prepareSecurityHeaders(Response response)
    {
        response.getHeaders().put("X-Content-Type-Options", "nosniff");
        response.getHeaders().put("X-Frame-Options", "DENY");
        response.getHeaders().put("Referrer-Policy", "no-referrer");
        response.getHeaders().put("Permissions-Policy", "camera=(), microphone=(), geolocation=(), usb=()");
        response.getHeaders().put("Content-Security-Policy",
            "default-src 'self'; base-uri 'none'; frame-ancestors 'none'; object-src 'none'; " +
                "form-action 'self'; connect-src 'self'; img-src 'self' data:; script-src 'self'; style-src 'self'");
    }

    private static boolean requireMethod(Request request, Response response, Callback callback, String... methods)
    {
        String actual = request.getMethod();

        for(String method: methods)
        {
            if(method.equals(actual))
            {
                return true;
            }
        }

        sendText(response, callback, 405, "Method not allowed");
        return false;
    }

    private static String contentType(Path file)
    {
        String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);

        if(name.endsWith(".html"))
        {
            return "text/html; charset=utf-8";
        }
        else if(name.endsWith(".css"))
        {
            return "text/css; charset=utf-8";
        }
        else if(name.endsWith(".js"))
        {
            return "application/javascript; charset=utf-8";
        }
        else if(name.endsWith(".json"))
        {
            return "application/json; charset=utf-8";
        }
        else if(name.endsWith(".txt"))
        {
            return "text/plain; charset=utf-8";
        }
        else if(name.endsWith(".svg"))
        {
            return "image/svg+xml";
        }
        else if(name.endsWith(".png"))
        {
            return "image/png";
        }

        return "application/octet-stream";
    }

    private static void sendHtml(Response response, Callback callback, int status, String body)
    {
        WebResponses.text(response, callback, status, "text/html; charset=utf-8", body);
    }

    private static void sendText(Response response, Callback callback, int status, String body)
    {
        WebResponses.text(response, callback, status, "text/plain; charset=utf-8", body);
    }

    int activeStreamCount()
    {
        return mActiveStreamCount.get();
    }

    private synchronized void shutdownStreams()
    {
        mAcceptingStreams.set(false);

        for(StatsLiveEventHub.Subscription subscription: mActiveSubscriptions)
        {
            subscription.close();
        }

        mActiveSubscriptions.clear();
        ExecutorService executor = mStreamExecutor;
        mStreamExecutor = null;

        if(executor != null)
        {
            executor.shutdownNow();

            try
            {
                if(!executor.awaitTermination(STREAM_SHUTDOWN_SECONDS, TimeUnit.SECONDS))
                {
                    mLog.warn("Stats web stream tasks did not stop within [{}] seconds", STREAM_SHUTDOWN_SECONDS);
                }
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Ends long-lived responses before Jetty waits for in-flight requests during graceful server shutdown.
     */
    @Override
    public CompletableFuture<Void> shutdown()
    {
        shutdownStreams();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public boolean isShutdown()
    {
        return !mAcceptingStreams.get();
    }

    @Override
    public void close()
    {
        shutdownStreams();
    }

    @FunctionalInterface
    private interface JsonSupplier
    {
        Map<String,Object> get();
    }

    private record FeatureAuthorization(WebFeature feature, WebTransport transport,
                                        WebAuthorization authorization)
    {
        private FeatureAuthorization
        {
            Objects.requireNonNull(authorization, "Web authorization cannot be null");

            if((feature == null) != (transport == null))
            {
                throw new IllegalArgumentException("Feature and transport must either both be present or both absent");
            }
        }
    }
}
