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

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.util.JsonRecyclerPools;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.configuration.channel.ChannelConfigurationOperations;
import io.github.dsheirer.configuration.channel.ChannelConfigurationService.AutoStartRequest;
import io.github.dsheirer.configuration.channel.ChannelConfigurationService.BulkRuntimeRequest;
import io.github.dsheirer.configuration.channel.ChannelConfigurationService.ChannelConfigurationException;
import io.github.dsheirer.configuration.channel.ChannelConfigurationService.ChannelDeleteRequest;
import io.github.dsheirer.configuration.channel.ChannelConfigurationService.ChannelListRequest;
import io.github.dsheirer.configuration.channel.ChannelConfigurationService.ChannelWriteRequest;
import io.github.dsheirer.configuration.channel.ChannelConfigurationService.RevisionRequest;
import io.github.dsheirer.configuration.channel.ChannelConfigurationService.RuntimeRequest;
import io.github.dsheirer.configuration.channel.ChannelConfigurationService.TimeoutRequest;
import io.github.dsheirer.source.tuner.manager.TunerSettingsService;
import io.github.dsheirer.source.tuner.manager.TunerSettingsOperations;
import io.github.dsheirer.source.tuner.manager.TunerSettingsService.EnabledRequest;
import io.github.dsheirer.source.tuner.manager.TunerSettingsService.TunerSettingsException;
import io.github.dsheirer.source.tuner.manager.TunerSettingsService.UpdateRequest;
import io.github.dsheirer.web.WebResponses;
import io.github.dsheirer.web.access.AuthorizationSubject;
import io.github.dsheirer.web.access.FeatureAccessGateway;
import io.github.dsheirer.web.access.InMemoryFeatureAccessPolicy;
import io.github.dsheirer.web.access.RemoteAddressAdmissionPolicy;
import io.github.dsheirer.web.access.WebFeature;
import io.github.dsheirer.web.access.WebRequestSubjectResolver;
import io.github.dsheirer.web.access.WebRequestSubjectResolver.WebAuthorization;
import io.github.dsheirer.web.access.WebTransport;
import io.github.dsheirer.web.live.LiveActivityService;
import io.github.dsheirer.web.live.LiveActivityService.FeedType;
import io.github.dsheirer.web.auth.WebAdminAuthenticationHandler.MutationAuthorization;
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
import java.util.function.Function;
import java.util.function.Supplier;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
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
    private static final int MAXIMUM_TUNER_SETTINGS_BODY_BYTES = 4_096;
    private static final int MAXIMUM_CHANNEL_SETTINGS_BODY_BYTES = 128 * 1_024;
    private static final ObjectMapper TUNER_SETTINGS_OBJECT_MAPPER = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .recyclerPool(JsonRecyclerPools.nonRecyclingPool())
        .streamReadConstraints(StreamReadConstraints.builder()
            .maxDocumentLength(MAXIMUM_TUNER_SETTINGS_BODY_BYTES)
            .maxNestingDepth(3)
            .maxNameLength(48)
            .maxStringLength(96)
            .maxTokenCount(64)
            .build())
        .build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private static final ObjectMapper CHANNEL_SETTINGS_OBJECT_MAPPER = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .recyclerPool(JsonRecyclerPools.nonRecyclingPool())
        .streamReadConstraints(StreamReadConstraints.builder()
            .maxDocumentLength(MAXIMUM_CHANNEL_SETTINGS_BODY_BYTES)
            .maxNestingDepth(6)
            .maxNameLength(64)
            .maxStringLength(512)
            .maxTokenCount(4_096)
            .build())
        .build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private static final ByteBuffer EMPTY_BUFFER = ByteBuffer.allocate(0).asReadOnlyBuffer();
    private static final int STATIC_CHUNK_BYTES = 32 * 1024;
    private static final int MAXIMUM_ASYNC_STREAMS = 128;
    private static final long STREAM_SHUTDOWN_SECONDS = 2;
    private static final long SSE_AUTHORIZATION_RECHECK_MILLISECONDS = 250;
    private static final long SSE_HEARTBEAT_SECONDS = 15;
    static final String TUNER_INVENTORY_PATH = "/api/v1/tuners";
    static final String CHANNEL_SETTINGS_PATH = "/api/v1/configuration/channels";
    private static final FeatureAuthorization UNRESTRICTED_AUTHORIZATION =
        new FeatureAuthorization(null, null, WebAuthorization.permanent(AuthorizationSubject.ANONYMOUS));

    private final Path mAssetRoot;
    private final RemoteAddressAdmissionPolicy mRemoteAddressAdmissionPolicy;
    private final StatsWebDatabase mDatabase;
    private final StatsLiveService mLiveService;
    private final StatsWebCallService mWebCallService;
    private final LiveActivityService mLiveActivityService;
    private final Supplier<Map<String,Object>> mStatusSupplier;
    private final Supplier<?> mTunerInventorySupplier;
    private final TunerSettingsOperations mTunerSettingsService;
    private final ChannelConfigurationOperations mChannelConfigurationService;
    private final Function<Request,MutationAuthorization> mMutationAuthorizer;
    private final FeatureAccessGateway mFeatureAccessGateway;
    private final WebRequestSubjectResolver mSubjectResolver;
    private final Set<StatsLiveEventHub.Subscription> mActiveSubscriptions =
        java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Set<LiveActivityService.OpenStream> mActiveContextStreams =
        java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Semaphore mAsyncStreamPermits = new Semaphore(MAXIMUM_ASYNC_STREAMS);
    private final AtomicInteger mActiveStreamCount = new AtomicInteger();
    private final AtomicBoolean mAcceptingStreams = new AtomicBoolean();
    private ExecutorService mStreamExecutor;

    StatsWebHandler(Path assetRoot, StatsWebDatabase database, StatsLiveService liveService,
                    StatsWebCallService webCallService, Supplier<Map<String,Object>> statusSupplier)
    {
        this(assetRoot, database, liveService, webCallService, statusSupplier,
            null, InMemoryFeatureAccessPolicy.currentProfileDefaults(), WebRequestSubjectResolver.anonymous(),
            RemoteAddressAdmissionPolicy.allowAll(), StatsWebHandler::emptyTunerInventory);
    }

    StatsWebHandler(Path assetRoot, StatsWebDatabase database, StatsLiveService liveService,
                    StatsWebCallService webCallService, Supplier<Map<String,Object>> statusSupplier,
                    FeatureAccessGateway featureAccessGateway, WebRequestSubjectResolver subjectResolver)
    {
        this(assetRoot, database, liveService, webCallService, statusSupplier, null, featureAccessGateway,
            subjectResolver, RemoteAddressAdmissionPolicy.allowAll(), StatsWebHandler::emptyTunerInventory);
    }

    StatsWebHandler(Path assetRoot, StatsWebDatabase database, StatsLiveService liveService,
                    StatsWebCallService webCallService, Supplier<Map<String,Object>> statusSupplier,
                    FeatureAccessGateway featureAccessGateway, WebRequestSubjectResolver subjectResolver,
                    RemoteAddressAdmissionPolicy remoteAddressAdmissionPolicy)
    {
        this(assetRoot, database, liveService, webCallService, statusSupplier, null, featureAccessGateway,
            subjectResolver, remoteAddressAdmissionPolicy, StatsWebHandler::emptyTunerInventory);
    }

    StatsWebHandler(Path assetRoot, StatsWebDatabase database, StatsLiveService liveService,
                    StatsWebCallService webCallService, Supplier<Map<String,Object>> statusSupplier,
                    LiveActivityService liveActivityService, FeatureAccessGateway featureAccessGateway,
                    WebRequestSubjectResolver subjectResolver,
                    RemoteAddressAdmissionPolicy remoteAddressAdmissionPolicy)
    {
        this(assetRoot, database, liveService, webCallService, statusSupplier, liveActivityService,
            featureAccessGateway, subjectResolver, remoteAddressAdmissionPolicy, StatsWebHandler::emptyTunerInventory);
    }

    StatsWebHandler(Path assetRoot, StatsWebDatabase database, StatsLiveService liveService,
                    StatsWebCallService webCallService, Supplier<Map<String,Object>> statusSupplier,
                    LiveActivityService liveActivityService, FeatureAccessGateway featureAccessGateway,
                    WebRequestSubjectResolver subjectResolver,
                    RemoteAddressAdmissionPolicy remoteAddressAdmissionPolicy,
                    Supplier<?> tunerInventorySupplier)
    {
        this(assetRoot, database, liveService, webCallService, statusSupplier, liveActivityService,
            featureAccessGateway, subjectResolver, remoteAddressAdmissionPolicy, tunerInventorySupplier, null,
            request -> new MutationAuthorization(false, () -> false));
    }

    StatsWebHandler(Path assetRoot, StatsWebDatabase database, StatsLiveService liveService,
                    StatsWebCallService webCallService, Supplier<Map<String,Object>> statusSupplier,
                    LiveActivityService liveActivityService, FeatureAccessGateway featureAccessGateway,
                    WebRequestSubjectResolver subjectResolver,
                    RemoteAddressAdmissionPolicy remoteAddressAdmissionPolicy,
                    Supplier<?> tunerInventorySupplier, TunerSettingsOperations tunerSettingsService,
                    Function<Request,MutationAuthorization> mutationAuthorizer)
    {
        this(assetRoot, database, liveService, webCallService, statusSupplier, liveActivityService,
            featureAccessGateway, subjectResolver, remoteAddressAdmissionPolicy, tunerInventorySupplier,
            tunerSettingsService, null, mutationAuthorizer);
    }

    StatsWebHandler(Path assetRoot, StatsWebDatabase database, StatsLiveService liveService,
                    StatsWebCallService webCallService, Supplier<Map<String,Object>> statusSupplier,
                    LiveActivityService liveActivityService, FeatureAccessGateway featureAccessGateway,
                    WebRequestSubjectResolver subjectResolver,
                    RemoteAddressAdmissionPolicy remoteAddressAdmissionPolicy,
                    Supplier<?> tunerInventorySupplier, TunerSettingsOperations tunerSettingsService,
                    ChannelConfigurationOperations channelConfigurationService,
                    Function<Request,MutationAuthorization> mutationAuthorizer)
    {
        mAssetRoot = Objects.requireNonNull(assetRoot, "Stats web asset root cannot be null")
            .toAbsolutePath().normalize();
        mDatabase = Objects.requireNonNull(database, "Stats web database cannot be null");
        mLiveService = Objects.requireNonNull(liveService, "Stats live service cannot be null");
        mWebCallService = Objects.requireNonNull(webCallService, "Stats web call service cannot be null");
        mLiveActivityService = liveActivityService;
        mStatusSupplier = Objects.requireNonNull(statusSupplier, "Stats status supplier cannot be null");
        mTunerInventorySupplier = Objects.requireNonNull(tunerInventorySupplier,
            "Tuner inventory supplier cannot be null");
        mTunerSettingsService = tunerSettingsService;
        mChannelConfigurationService = channelConfigurationService;
        mMutationAuthorizer = Objects.requireNonNull(mutationAuthorizer,
            "Administrator mutation authorizer cannot be null");
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
                ContextRoute contextRoute = ContextRoute.parse(path);
                FeatureAuthorization authorization = authorizeFeature(request, response, callback, path,
                    contextRoute);

                if(authorization == null)
                {
                    return true;
                }

                if(contextRoute != null)
                {
                    handleContextActivity(request, response, callback, contextRoute, authorization);
                    return true;
                }

                ChannelSettingsRoute channelSettingsRoute = ChannelSettingsRoute.parse(path);

                if(channelSettingsRoute != null)
                {
                    handleChannelSettings(request, response, callback, channelSettingsRoute);
                    return true;
                }

                if(path.startsWith(CHANNEL_SETTINGS_PATH + "/"))
                {
                    sendJson(response, callback, 404,
                        Map.of("error", "Channel settings route was not found.", "code", "route_not_found",
                            "status", 404));
                    return true;
                }

                TunerSettingsRoute tunerSettingsRoute = TunerSettingsRoute.parse(path);

                if(tunerSettingsRoute != null)
                {
                    handleTunerSettings(request, response, callback, tunerSettingsRoute);
                    return true;
                }

                if(path.startsWith(TUNER_INVENTORY_PATH + "/"))
                {
                    sendJson(response, callback, 404,
                        Map.of("error", "Receiver settings route was not found.", "code", "route_not_found",
                            "status", 404));
                    return true;
                }

                switch(path)
                {
                    case "/api/status" -> handleJson(request, response, callback, mStatusSupplier::get);
                    case TUNER_INVENTORY_PATH -> handleJson(request, response, callback,
                        mTunerInventorySupplier::get);
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

    private FeatureAuthorization authorizeFeature(Request request, Response response, Callback callback, String path,
                                                  ContextRoute contextRoute)
    {
        if(TUNER_INVENTORY_PATH.equals(path) || path.startsWith(TUNER_INVENTORY_PATH + "/") ||
            CHANNEL_SETTINGS_PATH.equals(path) || path.startsWith(CHANNEL_SETTINGS_PATH + "/"))
        {
            WebAuthorization authorization = resolveAuthorization(request);

            if(authorization.isSessionValid() && authorization.subject().isAuthenticatedAdmin())
            {
                return new FeatureAuthorization(null, null, authorization);
            }

            response.getHeaders().put(HttpHeader.WWW_AUTHENTICATE, "Bearer realm=\"sdrtrunk-admin\"");
            sendText(response, callback, 401, "Administrator sign-in required");
            return null;
        }

        WebFeature feature;
        WebTransport transport;

        if(contextRoute != null)
        {
            feature = contextRoute.feedType() == FeedType.EVENTS ? WebFeature.EVENTS : WebFeature.MESSAGES;
            transport = contextRoute.stream() ? WebTransport.SSE : WebTransport.HTTP;
        }
        else if("/live/web-calls".equals(path) || path.startsWith("/api/web-player/calls/"))
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

        WebAuthorization authorization = resolveAuthorization(request);

        FeatureAuthorization featureAuthorization = new FeatureAuthorization(feature, transport, authorization);

        if(isAuthorized(featureAuthorization))
        {
            return featureAuthorization;
        }

        response.getHeaders().put(HttpHeader.WWW_AUTHENTICATE, "Bearer realm=\"sdrtrunk-admin\"");
        sendText(response, callback, 401, "Administrator sign-in required");
        return null;
    }

    private WebAuthorization resolveAuthorization(Request request)
    {
        try
        {
            return Objects.requireNonNull(mSubjectResolver.resolveAuthorization(request),
                "Subject resolver returned null authorization");
        }
        catch(RuntimeException exception)
        {
            return WebAuthorization.permanent(AuthorizationSubject.ANONYMOUS);
        }
    }

    private static Map<String,Object> emptyTunerInventory()
    {
        return Map.of("revision", 0, "tuners", java.util.List.of(),
            "spectrum", Map.of("exclusive", true, "busy", false));
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

    private static void sendJson(Response response, Callback callback, int status, Object value)
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

    private void handleTunerSettings(Request request, Response response, Callback callback,
                                     TunerSettingsRoute route)
    {
        TunerSettingsOperations service = mTunerSettingsService;

        if(service == null)
        {
            sendJson(response, callback, 503,
                Map.of("error", "Receiver settings are unavailable.", "code", "settings_unavailable",
                    "status", 503));
            return;
        }

        if(route.kind() == TunerSettingsRoute.Kind.SETTINGS && "GET".equals(request.getMethod()))
        {
            completeTunerCommand(response, callback, service.settings(route.tunerId()));
            return;
        }

        if(!"PUT".equals(request.getMethod()))
        {
            response.getHeaders().put(HttpHeader.ALLOW,
                route.kind() == TunerSettingsRoute.Kind.SETTINGS ? "GET, PUT" : "PUT");
            sendJson(response, callback, 405,
                Map.of("error", "Method not allowed.", "code", "method_not_allowed", "status", 405));
            return;
        }

        MutationAuthorization authorization = mMutationAuthorizer.apply(request);

        if(authorization == null || !authorization.authorized())
        {
            sendJson(response, callback, 403,
                Map.of("error", "The settings request was rejected.", "code", "request_rejected", "status", 403));
            return;
        }

        if(!isJsonContentType(request.getHeaders().get(HttpHeader.CONTENT_TYPE)))
        {
            sendJson(response, callback, 415,
                Map.of("error", "A JSON request body is required.", "code", "invalid_request", "status", 415));
            return;
        }

        long contentLength = request.getLength();

        if(contentLength > MAXIMUM_TUNER_SETTINGS_BODY_BYTES)
        {
            response.getHeaders().put(HttpHeader.CONNECTION, "close");
            sendJson(response, callback, 413,
                Map.of("error", "The settings request is too large.", "code", "invalid_request", "status", 413));
            return;
        }

        Promise<RetainableByteBuffer> bodyCompletion = Promise.from(body ->
            completeTunerBody(response, callback, route, authorization, body.takeByteArray(), null),
            failure -> completeTunerBody(response, callback, route, authorization, null, failure));
        Content.Source.asRetainableByteBuffer(request, null, false, MAXIMUM_TUNER_SETTINGS_BODY_BYTES,
            bodyCompletion);
    }

    private void completeTunerBody(Response response, Callback callback, TunerSettingsRoute route,
                                   MutationAuthorization authorization, byte[] body, Throwable failure)
    {
        if(failure != null)
        {
            response.getHeaders().put(HttpHeader.CONNECTION, "close");
            int status = failure instanceof IllegalStateException ? 413 : 400;
            sendJson(response, callback, status,
                Map.of("error", status == 413 ? "The settings request is too large." :
                        "The settings request could not be read.",
                    "code", "invalid_request", "status", status));
            return;
        }

        if(body == null || body.length == 0)
        {
            sendJson(response, callback, 400,
                Map.of("error", "A settings request body is required.", "code", "invalid_request", "status", 400));
            return;
        }

        try
        {
            if(route.kind() == TunerSettingsRoute.Kind.SETTINGS)
            {
                UpdateRequest update = TUNER_SETTINGS_OBJECT_MAPPER.readValue(body, UpdateRequest.class);
                completeTunerCommand(response, callback, mTunerSettingsService.update(route.tunerId(), update,
                    authorization::isSessionValid));
            }
            else
            {
                EnabledRequest update = TUNER_SETTINGS_OBJECT_MAPPER.readValue(body, EnabledRequest.class);
                completeTunerCommand(response, callback, mTunerSettingsService.setEnabled(route.tunerId(), update,
                    authorization::isSessionValid));
            }
        }
        catch(IOException | RuntimeException exception)
        {
            sendJson(response, callback, 400,
                Map.of("error", "The settings request is invalid.", "code", "invalid_request", "status", 400));
        }
    }

    private static void completeTunerCommand(Response response, Callback callback,
                                             CompletableFuture<Map<String,Object>> completion)
    {
        completion.whenComplete((value, failure) ->
        {
            Throwable cause = failure;

            while(cause != null && (cause instanceof java.util.concurrent.CompletionException ||
                cause instanceof ExecutionException) && cause.getCause() != null)
            {
                cause = cause.getCause();
            }

            if(cause == null)
            {
                sendJson(response, callback, 200, value);
            }
            else if(cause instanceof TunerSettingsException exception)
            {
                if(exception.status() == 401)
                {
                    response.getHeaders().put(HttpHeader.WWW_AUTHENTICATE, "Bearer realm=\"sdrtrunk-admin\"");
                }

                if(exception.status() == 503)
                {
                    response.getHeaders().put(HttpHeader.RETRY_AFTER, "1");
                }

                sendJson(response, callback, exception.status(),
                    Map.of("error", exception.getMessage(), "code", exception.code(), "status", exception.status()));
            }
            else
            {
                sendJson(response, callback, 503,
                    Map.of("error", "Receiver settings are unavailable.", "code", "settings_unavailable",
                        "status", 503));
            }
        });
    }

    private void handleChannelSettings(Request request, Response response, Callback callback,
                                       ChannelSettingsRoute route)
    {
        ChannelConfigurationOperations service = mChannelConfigurationService;

        if(service == null)
        {
            sendJson(response, callback, 503,
                Map.of("error", "Channel settings are unavailable.", "code", "settings_unavailable",
                    "status", 503));
            return;
        }

        String method = request.getMethod();

        if(route.kind() == ChannelSettingsRoute.Kind.BASE && "GET".equals(method))
        {
            try
            {
                StatsRequest parameters = statsRequest(request);
                Integer offset = parameters.optionalInt("offset");
                Integer limit = parameters.optionalInt("limit");
                ChannelListRequest listRequest = new ChannelListRequest(parameters.text("q"),
                    valueOr(parameters.text("sort"), "startOrder"),
                    valueOr(parameters.text("direction"), "ascending"),
                    offset != null ? offset : 0, limit != null ? limit : 50);
                completeChannelCommand(response, callback, 200, service.list(listRequest));
            }
            catch(RuntimeException exception)
            {
                sendJson(response, callback, 400,
                    Map.of("error", "Channel list options are invalid.", "code", "invalid_request",
                        "status", 400));
            }

            return;
        }

        if(route.kind() == ChannelSettingsRoute.Kind.TEMPLATE && "GET".equals(method))
        {
            completeChannelCommand(response, callback, 200, service.template(route.protocol()));
            return;
        }

        if(route.kind() == ChannelSettingsRoute.Kind.DETAIL && "GET".equals(method))
        {
            completeChannelCommand(response, callback, 200, service.detail(route.channelId()));
            return;
        }

        if(route.kind() == ChannelSettingsRoute.Kind.EXPORT && "GET".equals(method))
        {
            completeChannelCommand(response, callback, 200, service.export(route.channelId()));
            return;
        }

        if(!route.isMutationMethod(method))
        {
            response.getHeaders().put(HttpHeader.ALLOW, route.allowedMethods());
            sendJson(response, callback, 405,
                Map.of("error", "Method not allowed.", "code", "method_not_allowed", "status", 405));
            return;
        }

        MutationAuthorization authorization = mMutationAuthorizer.apply(request);

        if(authorization == null || !authorization.authorized())
        {
            sendJson(response, callback, 403,
                Map.of("error", "The settings request was rejected.", "code", "request_rejected", "status", 403));
            return;
        }

        if(!isJsonContentType(request.getHeaders().get(HttpHeader.CONTENT_TYPE)))
        {
            sendJson(response, callback, 415,
                Map.of("error", "A JSON request body is required.", "code", "invalid_request", "status", 415));
            return;
        }

        if(request.getLength() > MAXIMUM_CHANNEL_SETTINGS_BODY_BYTES)
        {
            response.getHeaders().put(HttpHeader.CONNECTION, "close");
            sendJson(response, callback, 413,
                Map.of("error", "The settings request is too large.", "code", "invalid_request", "status", 413));
            return;
        }

        Promise<RetainableByteBuffer> bodyCompletion = Promise.from(body ->
            completeChannelBody(response, callback, route, method, authorization, body.takeByteArray(), null),
            failure -> completeChannelBody(response, callback, route, method, authorization, null, failure));
        Content.Source.asRetainableByteBuffer(request, null, false, MAXIMUM_CHANNEL_SETTINGS_BODY_BYTES,
            bodyCompletion);
    }

    private void completeChannelBody(Response response, Callback callback, ChannelSettingsRoute route,
                                     String method, MutationAuthorization authorization, byte[] body,
                                     Throwable failure)
    {
        if(failure != null)
        {
            response.getHeaders().put(HttpHeader.CONNECTION, "close");
            int status = failure instanceof IllegalStateException ? 413 : 400;
            sendJson(response, callback, status,
                Map.of("error", status == 413 ? "The settings request is too large." :
                        "The settings request could not be read.",
                    "code", "invalid_request", "status", status));
            return;
        }

        if(body == null || body.length == 0)
        {
            sendJson(response, callback, 400,
                Map.of("error", "A settings request body is required.", "code", "invalid_request", "status", 400));
            return;
        }

        try
        {
            CompletableFuture<Map<String,Object>> completion;
            int successStatus = 200;

            switch(route.kind())
            {
                case BASE ->
                {
                    ChannelWriteRequest update = CHANNEL_SETTINGS_OBJECT_MAPPER.readValue(body,
                        ChannelWriteRequest.class);
                    completion = mChannelConfigurationService.create(update, authorization::isSessionValid);
                    successStatus = 201;
                }
                case DETAIL ->
                {
                    if("DELETE".equals(method))
                    {
                        ChannelDeleteRequest update = CHANNEL_SETTINGS_OBJECT_MAPPER.readValue(body,
                            ChannelDeleteRequest.class);
                        completion = mChannelConfigurationService.delete(route.channelId(), update,
                            authorization::isSessionValid);
                    }
                    else
                    {
                        ChannelWriteRequest update = CHANNEL_SETTINGS_OBJECT_MAPPER.readValue(body,
                            ChannelWriteRequest.class);
                        completion = mChannelConfigurationService.update(route.channelId(), update,
                            authorization::isSessionValid);
                    }
                }
                case CLONE ->
                {
                    RevisionRequest update = CHANNEL_SETTINGS_OBJECT_MAPPER.readValue(body, RevisionRequest.class);
                    completion = mChannelConfigurationService.cloneChannel(route.channelId(), update,
                        authorization::isSessionValid);
                    successStatus = 201;
                }
                case AUTO_START ->
                {
                    AutoStartRequest update = CHANNEL_SETTINGS_OBJECT_MAPPER.readValue(body, AutoStartRequest.class);
                    completion = mChannelConfigurationService.autoStart(route.channelId(), update,
                        authorization::isSessionValid);
                }
                case RUNTIME ->
                {
                    RuntimeRequest update = CHANNEL_SETTINGS_OBJECT_MAPPER.readValue(body, RuntimeRequest.class);
                    completion = mChannelConfigurationService.runtime(route.channelId(), update,
                        authorization::isSessionValid);
                }
                case BULK_RUNTIME ->
                {
                    BulkRuntimeRequest update = CHANNEL_SETTINGS_OBJECT_MAPPER.readValue(body,
                        BulkRuntimeRequest.class);
                    completion = mChannelConfigurationService.bulkRuntime(update, authorization::isSessionValid);
                }
                case AUTO_START_TIMEOUT ->
                {
                    TimeoutRequest update = CHANNEL_SETTINGS_OBJECT_MAPPER.readValue(body, TimeoutRequest.class);
                    completion = mChannelConfigurationService.setAutoStartTimeout(update,
                        authorization::isSessionValid);
                }
                default -> throw new IllegalArgumentException("Channel route does not accept a request body");
            }

            completeChannelCommand(response, callback, successStatus, completion);
        }
        catch(IOException | RuntimeException exception)
        {
            sendJson(response, callback, 400,
                Map.of("error", "The settings request is invalid.", "code", "invalid_request", "status", 400));
        }
    }

    private static void completeChannelCommand(Response response, Callback callback, int successStatus,
                                               CompletableFuture<Map<String,Object>> completion)
    {
        completion.whenComplete((value, failure) ->
        {
            Throwable cause = unwrapCompletionFailure(failure);

            if(cause == null)
            {
                sendJson(response, callback, successStatus, value);
            }
            else if(cause instanceof ChannelConfigurationException exception)
            {
                if(exception.status() == 401)
                {
                    response.getHeaders().put(HttpHeader.WWW_AUTHENTICATE, "Bearer realm=\"sdrtrunk-admin\"");
                }

                if(exception.status() == 503)
                {
                    response.getHeaders().put(HttpHeader.RETRY_AFTER, "1");
                }

                sendJson(response, callback, exception.status(),
                    Map.of("error", exception.getMessage(), "code", exception.code(), "status", exception.status()));
            }
            else
            {
                sendJson(response, callback, 503,
                    Map.of("error", "Channel settings are unavailable.", "code", "settings_unavailable",
                        "status", 503));
            }
        });
    }

    private static Throwable unwrapCompletionFailure(Throwable failure)
    {
        Throwable cause = failure;

        while(cause != null && (cause instanceof java.util.concurrent.CompletionException ||
            cause instanceof ExecutionException) && cause.getCause() != null)
        {
            cause = cause.getCause();
        }

        return cause;
    }

    private static String valueOr(String value, String fallback)
    {
        return value != null ? value : fallback;
    }

    private static boolean isJsonContentType(String contentType)
    {
        if(contentType == null)
        {
            return false;
        }

        String[] parts = contentType.split(";", -1);

        if(parts.length < 1 || parts.length > 2 || !"application/json".equalsIgnoreCase(parts[0].strip()))
        {
            return false;
        }

        if(parts.length == 1)
        {
            return true;
        }

        String parameter = parts[1].strip();
        int separator = parameter.indexOf('=');

        if(separator < 1 || !"charset".equalsIgnoreCase(parameter.substring(0, separator).strip()))
        {
            return false;
        }

        String charset = parameter.substring(separator + 1).strip();

        if(charset.length() >= 2 && charset.charAt(0) == '"' && charset.charAt(charset.length() - 1) == '"')
        {
            charset = charset.substring(1, charset.length() - 1);
        }

        return "utf-8".equalsIgnoreCase(charset);
    }

    private void handleContextActivity(Request request, Response response, Callback callback,
                                       ContextRoute route, FeatureAuthorization authorization)
    {
        if(!requireMethod(request, response, callback, "GET"))
        {
            return;
        }

        if(mLiveActivityService == null)
        {
            sendJson(response, callback, 503,
                Map.of("error", "Live channel activity is unavailable", "status", 503));
            return;
        }

        if(!route.stream())
        {
            mLiveActivityService.snapshot(route.selectionId(), route.feedType()).ifPresentOrElse(
                snapshot -> sendJson(response, callback, 200, snapshot),
                () -> sendJson(response, callback, 404,
                    Map.of("error", "Live selection is no longer available", "status", 404)));
            return;
        }

        StreamCursor requestedCursor;

        try
        {
            requestedCursor = lastEventCursor(request);
        }
        catch(IllegalArgumentException exception)
        {
            sendJson(response, callback, 400, Map.of("error", exception.getMessage(), "status", 400));
            return;
        }

        LiveActivityService.FeedSnapshot current = mLiveActivityService
            .snapshot(route.selectionId(), route.feedType()).orElse(null);

        if(current == null)
        {
            sendJson(response, callback, 404,
                Map.of("error", "Live selection is no longer available", "status", 404));
            return;
        }

        boolean sameStream = requestedCursor != null && current.streamId().equals(requestedCursor.streamId());
        Long replayAfter = sameStream ? requestedCursor.sequence() : null;

        LiveActivityService.OpenStream stream = mLiveActivityService
            .openStream(route.selectionId(), route.feedType(), replayAfter).orElse(null);

        if(stream == null)
        {
            sendJson(response, callback, 429,
                Map.of("error", "Too many viewers for this live selection", "status", 429));
            return;
        }

        streamContextSse(request, response, callback, route, stream, !sameStream, authorization);
    }

    private static StreamCursor lastEventCursor(Request request)
    {
        String value = request.getHeaders().get("Last-Event-ID");

        if(value == null || value.isBlank())
        {
            return null;
        }

        if(value.length() > 96)
        {
            throw new IllegalArgumentException("Invalid Last-Event-ID header");
        }

        int separator = value.lastIndexOf(':');

        if(separator < 1 || separator == value.length() - 1)
        {
            throw new IllegalArgumentException("Invalid Last-Event-ID header");
        }

        String streamId = value.substring(0, separator);

        if(!streamId.matches("[A-Za-z0-9-]{1,64}"))
        {
            throw new IllegalArgumentException("Invalid Last-Event-ID header");
        }

        try
        {
            long parsed = Long.parseLong(value.substring(separator + 1));

            if(parsed < 0)
            {
                throw new IllegalArgumentException("Invalid Last-Event-ID header");
            }

            return new StreamCursor(streamId, parsed);
        }
        catch(NumberFormatException exception)
        {
            throw new IllegalArgumentException("Invalid Last-Event-ID header");
        }
    }

    private void streamContextSse(Request request, Response response, Callback callback, ContextRoute route,
                                  LiveActivityService.OpenStream stream, boolean sendInitialSnapshot,
                                  FeatureAuthorization authorization)
    {
        if(!isAuthorized(authorization))
        {
            stream.close();
            response.getHeaders().put(HttpHeader.WWW_AUTHENTICATE, "Bearer realm=\"sdrtrunk-admin\"");
            sendText(response, callback, 401, "Administrator sign-in required");
            return;
        }

        if(!mAcceptingStreams.get() || !mAsyncStreamPermits.tryAcquire())
        {
            stream.close();
            sendText(response, callback, 429, "Too many live Stats Server clients");
            return;
        }

        response.setStatus(200);
        response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/event-stream; charset=utf-8");
        response.getHeaders().put(HttpHeader.CACHE_CONTROL, "no-store");
        response.getHeaders().put(HttpHeader.CONNECTION, "keep-alive");
        response.getHeaders().put("X-Accel-Buffering", "no");
        mActiveContextStreams.add(stream);
        request.addFailureListener(failure -> stream.close());

        if(!executeStream(() -> runContextSse(response, callback, route, stream, sendInitialSnapshot,
            authorization)))
        {
            mActiveContextStreams.remove(stream);
            stream.close();
            mAsyncStreamPermits.release();
            sendText(response, callback, 503, "Stats live stream is stopping");
        }
    }

    private void runContextSse(Response response, Callback callback, ContextRoute route,
                               LiveActivityService.OpenStream stream, boolean sendInitialSnapshot,
                               FeatureAuthorization authorization)
    {
        StatsLiveEventHub.Subscription subscription = stream.subscription();
        Throwable failure = null;

        try(stream)
        {
            if(!isAuthorized(authorization))
            {
                writeChunk(response, new byte[0], true);
                return;
            }

            long lastWriteNanos = System.nanoTime();

            if(sendInitialSnapshot)
            {
                writeChunk(response, sseEvent(streamEventId(stream.snapshot().streamId(),
                    subscription.registrationHighWaterEventId()), "snapshot", stream.snapshot()), false);
                lastWriteNanos = System.nanoTime();
            }

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
                    if(!isAuthorized(authorization))
                    {
                        break;
                    }

                    if(event.requiresResnapshot())
                    {
                        LiveActivityService.FeedSnapshot snapshot = mLiveActivityService
                            .snapshot(route.selectionId(), route.feedType())
                            .orElse(stream.snapshot());
                        subscription.acknowledgeSnapshot(snapshot.sequence());
                        writeChunk(response, sseEvent(streamEventId(snapshot.streamId(), snapshot.sequence()),
                            StatsLiveEventHub.RESNAPSHOT_EVENT_NAME, snapshot), false);
                    }
                    else
                    {
                        writeChunk(response, sseEvent(streamEventId(stream.snapshot().streamId(), event.id()),
                            event.name(), event.data()), false);
                    }

                    lastWriteNanos = System.nanoTime();
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
            failure = exception;
        }
        finally
        {
            mActiveContextStreams.remove(stream);
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

    private static byte[] sseEvent(String id, String event, Object data) throws JsonProcessingException
    {
        return ("id: " + id + "\nevent: " + event + "\ndata: " + OBJECT_MAPPER.writeValueAsString(data) +
            "\n\n").getBytes(StandardCharsets.UTF_8);
    }

    private static String streamEventId(String streamId, long sequence)
    {
        return streamId + ":" + sequence;
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

        for(LiveActivityService.OpenStream stream: mActiveContextStreams)
        {
            stream.close();
        }

        mActiveContextStreams.clear();
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
        Object get();
    }

    private record ContextRoute(String selectionId, FeedType feedType, boolean stream)
    {
        private static final String PREFIX = "/api/v1/contexts/";

        private static ContextRoute parse(String path)
        {
            if(path == null || !path.startsWith(PREFIX))
            {
                return null;
            }

            String[] segments = path.substring(PREFIX.length()).split("/", -1);

            if((segments.length != 2 && segments.length != 3) || segments[0].isBlank() ||
                segments[0].length() > 96 || segments[1].isBlank() ||
                segments.length == 3 && !"stream".equals(segments[2]))
            {
                return null;
            }

            try
            {
                return new ContextRoute(segments[0], FeedType.fromPath(segments[1]), segments.length == 3);
            }
            catch(IllegalArgumentException exception)
            {
                return null;
            }
        }
    }

    private record ChannelSettingsRoute(String channelId, Kind kind, String protocol)
    {
        private static final String PREFIX = CHANNEL_SETTINGS_PATH + "/";

        private static ChannelSettingsRoute parse(String path)
        {
            if(CHANNEL_SETTINGS_PATH.equals(path))
            {
                return new ChannelSettingsRoute(null, Kind.BASE, null);
            }

            if(path == null || !path.startsWith(PREFIX))
            {
                return null;
            }

            String[] segments = path.substring(PREFIX.length()).split("/", -1);

            if(segments.length == 1)
            {
                if("runtime".equals(segments[0]))
                {
                    return new ChannelSettingsRoute(null, Kind.BULK_RUNTIME, null);
                }

                if("auto-start-timeout".equals(segments[0]))
                {
                    return new ChannelSettingsRoute(null, Kind.AUTO_START_TIMEOUT, null);
                }

                return channelId(segments[0]) ?
                    new ChannelSettingsRoute(segments[0].toUpperCase(java.util.Locale.ROOT), Kind.DETAIL, null) :
                    null;
            }

            if(segments.length == 2 && "templates".equals(segments[0]) &&
                segments[1].matches("[A-Za-z0-9_]{1,32}"))
            {
                return new ChannelSettingsRoute(null, Kind.TEMPLATE,
                    segments[1].toUpperCase(java.util.Locale.ROOT));
            }

            if(segments.length != 2 || !channelId(segments[0]))
            {
                return null;
            }

            String id = segments[0].toUpperCase(java.util.Locale.ROOT);
            return switch(segments[1])
            {
                case "clone" -> new ChannelSettingsRoute(id, Kind.CLONE, null);
                case "auto-start" -> new ChannelSettingsRoute(id, Kind.AUTO_START, null);
                case "runtime" -> new ChannelSettingsRoute(id, Kind.RUNTIME, null);
                case "export" -> new ChannelSettingsRoute(id, Kind.EXPORT, null);
                default -> null;
            };
        }

        private static boolean channelId(String value)
        {
            return value != null && value.matches("CHN_[0-9A-Fa-f]{28}");
        }

        private boolean isMutationMethod(String method)
        {
            return switch(kind)
            {
                case BASE, CLONE -> "POST".equals(method);
                case DETAIL -> "PUT".equals(method) || "DELETE".equals(method);
                case AUTO_START, RUNTIME, BULK_RUNTIME, AUTO_START_TIMEOUT -> "PUT".equals(method);
                case TEMPLATE, EXPORT -> false;
            };
        }

        private String allowedMethods()
        {
            return switch(kind)
            {
                case BASE -> "GET, POST";
                case DETAIL -> "GET, PUT, DELETE";
                case TEMPLATE, EXPORT -> "GET";
                case CLONE -> "POST";
                case AUTO_START, RUNTIME, BULK_RUNTIME, AUTO_START_TIMEOUT -> "PUT";
            };
        }

        private enum Kind
        {
            BASE,
            TEMPLATE,
            DETAIL,
            EXPORT,
            CLONE,
            AUTO_START,
            RUNTIME,
            BULK_RUNTIME,
            AUTO_START_TIMEOUT
        }
    }

    private record TunerSettingsRoute(String tunerId, Kind kind)
    {
        private static final String PREFIX = TUNER_INVENTORY_PATH + "/";

        private static TunerSettingsRoute parse(String path)
        {
            if(path == null || !path.startsWith(PREFIX))
            {
                return null;
            }

            String[] segments = path.substring(PREFIX.length()).split("/", -1);

            if(segments.length != 2 || !segments[0].matches("TNR_[0-9A-Fa-f]{28}"))
            {
                return null;
            }

            return switch(segments[1])
            {
                case "settings" -> new TunerSettingsRoute(segments[0].toUpperCase(java.util.Locale.ROOT),
                    Kind.SETTINGS);
                case "enabled" -> new TunerSettingsRoute(segments[0].toUpperCase(java.util.Locale.ROOT),
                    Kind.ENABLED);
                default -> null;
            };
        }

        private enum Kind
        {
            SETTINGS,
            ENABLED
        }
    }

    private record StreamCursor(String streamId, long sequence)
    {
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
