/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.application.service.LiveContext;
import io.github.dsheirer.application.service.LiveContextResolver;
import io.github.dsheirer.channel.IChannelDescriptor;
import io.github.dsheirer.channel.metadata.activity.ChannelActivitySelectionDescriptor;
import io.github.dsheirer.channel.metadata.activity.ChannelActivitySelectionScope;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.module.decode.event.DecodeEvent;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.decode.p25.phase1.message.IFrequencyBand;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.web.WebApplicationService;
import io.github.dsheirer.web.access.AuthorizationSubject;
import io.github.dsheirer.web.access.InMemoryFeatureAccessPolicy;
import io.github.dsheirer.web.access.WebRequestSubjectResolver;
import io.github.dsheirer.web.live.LiveActivityService;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.jetty.server.Request;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StatsWebContextActivityIntegrationTest
{
    private static final String SELECTION_ID = "exact-context-integration";
    private static final long FREQUENCY = 851_012_500L;

    @TempDir
    Path mTemporaryFolder;
    private StatsLiveService mLiveService;
    private StatsWebCallService mCallService;
    private LiveActivityService mActivityService;
    private ProcessingChain mProcessingChain;
    private StatsWebHandler mHandler;
    private WebApplicationService mWebApplicationService;
    private HttpClient mHttpClient;
    private AtomicBoolean mSessionValid;

    @BeforeEach
    void setUp() throws Exception
    {
        Path databasePath = mTemporaryFolder.resolve("sdrtrunk.sqlite");
        Path assets = mTemporaryFolder.resolve("assets");
        Files.createDirectories(assets);
        Files.writeString(assets.resolve("index.html"), "<h1>Test</h1>", StandardCharsets.UTF_8);
        SdrTrunkDatabaseStartup.createGlobalDatabase(databasePath);
        StatsWebDatabase database = new StatsWebDatabase(new UserPreferences(), databasePath);
        mLiveService = new StatsLiveService(database, null);
        mCallService = new StatsWebCallService();
        mLiveService.start();
        mCallService.start();

        Channel channel = new Channel("Dispatch");
        channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
        mProcessingChain = new ProcessingChain(channel, new AliasModel());
        ChannelActivitySelectionDescriptor selection = new ChannelActivitySelectionDescriptor(SELECTION_ID,
            "table-1", "row-1", "Metro / Downtown", "Dispatch",
            ChannelActivitySelectionScope.EXACT_FREQUENCY, null, channel.getChannelID(), FREQUENCY, 1, "P25");
        LiveContext context = new LiveContext(selection, null, channel, mProcessingChain, mProcessingChain);
        mActivityService = new LiveActivityService(new FixedContextResolver(context));
        mActivityService.start();

        mSessionValid = new AtomicBoolean(true);
        WebRequestSubjectResolver subjectResolver = new WebRequestSubjectResolver()
        {
            @Override
            public AuthorizationSubject resolve(Request request)
            {
                return AuthorizationSubject.AUTHENTICATED_ADMIN;
            }

            @Override
            public WebAuthorization resolveAuthorization(Request request)
            {
                return new WebAuthorization(AuthorizationSubject.AUTHENTICATED_ADMIN, mSessionValid::get);
            }
        };
        mHandler = new StatsWebHandler(assets, database, mLiveService, mCallService,
            () -> Map.of("server", Map.of("enabled", true)), mActivityService,
            InMemoryFeatureAccessPolicy.currentProfileDefaults(), subjectResolver,
            io.github.dsheirer.web.access.RemoteAddressAdmissionPolicy.allowAll());
        mWebApplicationService = new WebApplicationService(WebApplicationService.Configuration.ephemeralLoopback(),
            mHandler, container -> {});
        mWebApplicationService.start();
        mHttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach
    void tearDown()
    {
        if(mWebApplicationService != null)
        {
            mWebApplicationService.close();
        }

        if(mActivityService != null)
        {
            mActivityService.close();
        }

        if(mLiveService != null)
        {
            mLiveService.close();
        }

        if(mCallService != null)
        {
            mCallService.close();
        }

        if(mProcessingChain != null)
        {
            mProcessingChain.dispose();
        }
    }

    @Test
    void servesSnapshotReplaysReconnectAndRejectsStaleEpoch() throws Exception
    {
        String path = "api/v1/contexts/" + SELECTION_ID + "/events";
        HttpResponse<String> snapshot = mHttpClient.send(HttpRequest.newBuilder(
            mWebApplicationService.getBaseUri().resolve(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, snapshot.statusCode());
        assertTrue(snapshot.body().contains("\"streamId\""));
        assertTrue(snapshot.body().contains("\"tableTitle\":\"Metro / Downtown\""));

        String lastApplied;

        try(InputStream stream = open(path + "/stream", null))
        {
            String initial = readEvent(stream);
            assertTrue(initial.contains("event: snapshot\n"));
            assertTrue(eventId(initial).contains(":"));

            mProcessingChain.getDecodeEventHistory().receive(event(1));
            String delta = readEvent(stream);
            assertTrue(delta.contains("event: delta\n"));
            assertTrue(delta.contains("dispatch-1"));
            lastApplied = eventId(delta);
        }

        mProcessingChain.getDecodeEventHistory().receive(event(2));

        try(InputStream replay = open(path + "/stream", lastApplied))
        {
            String delta = readEvent(replay);
            assertTrue(delta.contains("event: delta\n"));
            assertTrue(delta.contains("dispatch-2"));
        }

        String staleEpoch = "00000000-0000-0000-0000-000000000000:1";

        try(InputStream replacement = open(path + "/stream", staleEpoch))
        {
            assertTrue(readEvent(replacement).contains("event: snapshot\n"));
            mSessionValid.set(false);
            assertStreamCloses(replacement);
        }
    }

    private InputStream open(String path, String lastEventId) throws Exception
    {
        HttpRequest.Builder request = HttpRequest.newBuilder(mWebApplicationService.getBaseUri().resolve(path))
            .timeout(Duration.ofSeconds(5)).GET();

        if(lastEventId != null)
        {
            request.header("Last-Event-ID", lastEventId);
        }

        HttpResponse<InputStream> response = mHttpClient.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, response.statusCode());
        return response.body();
    }

    private static DecodeEvent event(int index)
    {
        return DecodeEvent.builder(DecodeEventType.CALL_GROUP, 10_000L + index)
            .duration(100L)
            .channel(new TestChannelDescriptor(FREQUENCY))
            .details("dispatch-" + index)
            .protocol(Protocol.APCO25)
            .timeslot(1)
            .build();
    }

    private static String readEvent(InputStream stream) throws Exception
    {
        byte[] event = new byte[64 * 1024];
        int length = 0;

        while(length < event.length)
        {
            int value = stream.read();

            if(value < 0)
            {
                break;
            }

            event[length++] = (byte)value;

            if(length >= 2 && event[length - 2] == '\n' && event[length - 1] == '\n')
            {
                return new String(event, 0, length, StandardCharsets.UTF_8);
            }
        }

        throw new AssertionError("SSE event did not terminate within the bounded test buffer");
    }

    private static String eventId(String event)
    {
        return event.lines().filter(line -> line.startsWith("id: ")).findFirst().orElseThrow().substring(4);
    }

    private static void assertStreamCloses(InputStream stream) throws Exception
    {
        CompletableFuture<Integer> next = new CompletableFuture<>();
        Thread reader = Thread.ofVirtual().start(() -> {
            try
            {
                next.complete(stream.read());
            }
            catch(Exception exception)
            {
                next.completeExceptionally(exception);
            }
        });

        try
        {
            assertEquals(-1, next.get(1, TimeUnit.SECONDS));
        }
        finally
        {
            if(!next.isDone())
            {
                stream.close();
                reader.interrupt();
            }
        }
    }

    private static final class FixedContextResolver extends LiveContextResolver
    {
        private final LiveContext mContext;

        private FixedContextResolver(LiveContext context)
        {
            super(new ChannelProcessingManager(null, null, null, null, new UserPreferences()));
            mContext = context;
        }

        @Override
        public void start()
        {
        }

        @Override
        public Optional<LiveContext> resolve(String selectionId)
        {
            return mContext.selectionId().equals(selectionId) ? Optional.of(mContext) : Optional.empty();
        }

        @Override
        public void stop()
        {
        }
    }

    private record TestChannelDescriptor(long getDownlinkFrequency) implements IChannelDescriptor
    {
        @Override
        public long getUplinkFrequency()
        {
            return 0;
        }

        @Override
        public int[] getFrequencyBandIdentifiers()
        {
            return new int[0];
        }

        @Override
        public void setFrequencyBand(IFrequencyBand bandIdentifier)
        {
        }

        @Override
        public boolean isTDMAChannel()
        {
            return true;
        }

        @Override
        public int getTimeslotCount()
        {
            return 2;
        }

        @Override
        public Protocol getProtocol()
        {
            return Protocol.APCO25;
        }
    }
}
