/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.web.tuner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.source.tuner.TunerClass;
import io.github.dsheirer.source.tuner.manager.DiscoveredRecordingTuner;
import io.github.dsheirer.source.tuner.manager.DiscoveredTuner;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import io.github.dsheirer.source.tuner.manager.TunerSettingsService;
import io.github.dsheirer.source.tuner.recording.RecordingTunerConfiguration;
import io.github.dsheirer.web.http.TunerAdminHttpController;
import io.github.dsheirer.web.http.WebRequestSecurity;
import io.github.dsheirer.web.auth.WebAccessService;
import io.github.dsheirer.web.auth.WebAccessSession;
import io.github.dsheirer.web.auth.WebAuthenticationService;
import io.github.dsheirer.web.auth.WebCapability;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class TunerAdminHttpControllerTest
{
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path mTemporaryDirectory;

    @Test
    void browseRouteRequiresExplicitLeaseAndValidMutationBodies() throws Exception
    {
        try(ServerFixture fixture = new ServerFixture(new FakeManager()))
        {
            String path = TunerAdminHttpController.PATH + "/" +
                TunerAdministrationService.opaqueId(fixture.mPhysical) + "/browse";
            assertEquals(405, fixture.send(path, "GET", null).statusCode());
            assertEquals(400, fixture.send(path, "POST", "{\"unknown\":true}").statusCode());
            assertEquals(422, fixture.send(path, "POST", "{\"lease_id\":17}").statusCode());
            assertEquals(422, fixture.send(path, "DELETE", "{}").statusCode());
        }
    }

    @Test
    void usbRescanReturnsWithoutWaitingForDiscovery() throws Exception
    {
        FakeManager manager = new FakeManager();
        try(ServerFixture fixture = new ServerFixture(manager))
        {
            HttpResponse<String> accepted = fixture.send(TunerAdminHttpController.RESCAN_PATH, "POST", null);
            assertEquals(202, accepted.statusCode());
            assertEquals("scanning", MAPPER.readTree(accepted.body()).at("/data/status").textValue());
            assertEquals(1, manager.mRescanRequests);
            assertEquals(false, manager.mRescan.isDone(), "HTTP must not wait for USB discovery");

            manager.mRescan = CompletableFuture.completedFuture(TunerManager.USB_RESCAN_UNAVAILABLE);
            assertEquals(503, fixture.send(TunerAdminHttpController.RESCAN_PATH, "POST", null).statusCode());
            assertEquals(400, fixture.send(TunerAdminHttpController.RESCAN_PATH, "POST", "{}").statusCode());
            assertEquals(405, fixture.send(TunerAdminHttpController.RESCAN_PATH, "GET", null).statusCode());
        }
    }

    @Test
    void recordingRemovalUsesOpaqueIdentityAndMapsSafetyResults() throws Exception
    {
        FakeManager manager = new FakeManager();
        try(ServerFixture fixture = new ServerFixture(manager))
        {
            String recordingPath = TunerAdminHttpController.PATH + "/" +
                TunerAdministrationService.opaqueId(fixture.mRecording);
            assertEquals(200, fixture.send(recordingPath, "DELETE", null).statusCode());
            assertSame(fixture.mRecording, manager.mRemoved);

            manager.mRemovalResult = TunerManager.RecordingTunerRemovalResult.IN_USE;
            assertEquals(409, fixture.send(recordingPath, "DELETE", null).statusCode());
            manager.mRemovalResult = TunerManager.RecordingTunerRemovalResult.NOT_FOUND;
            assertEquals(404, fixture.send(recordingPath, "DELETE", null).statusCode());
            manager.mRemovalResult = TunerManager.RecordingTunerRemovalResult.NOT_RECORDING;
            String physicalPath = TunerAdminHttpController.PATH + "/" +
                TunerAdministrationService.opaqueId(fixture.mPhysical);
            assertEquals(422, fixture.send(physicalPath, "DELETE", null).statusCode());
            assertEquals(400, fixture.send(recordingPath, "DELETE", "{}").statusCode());
            assertEquals(405, fixture.send(recordingPath, "GET", null).statusCode());
        }
    }

    @Test
    void protectedTunerRoutesRejectGuestsAndRequireAdminCsrfForMutations() throws Exception
    {
        Path database = mTemporaryDirectory.resolve("web-access.sqlite");
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            SdrTrunkDatabaseSchema.create(connection);
        }
        WebAccessService access = new WebAccessService(database);
        access.provisionOrResetPrimaryAdmin("admin-password-2026".toCharArray());
        access.createUser("listener", "listener-password-2026".toCharArray());
        WebAuthenticationService authentication = new WebAuthenticationService(access);
        WebAccessSession admin = authentication.login("admin", "admin-password-2026".toCharArray(),
            "admin-test").get(5, TimeUnit.SECONDS).session().orElseThrow();
        WebAccessSession listener = authentication.login("listener", "listener-password-2026".toCharArray(),
            "listener-test").get(5, TimeUnit.SECONDS).session().orElseThrow();
        FakeManager manager = new FakeManager();

        try(WebRequestSecurity security = new WebRequestSecurity(access, authentication);
            ServerFixture fixture = new ServerFixture(manager, security))
        {
            assertEquals(401, fixture.send(TunerAdminHttpController.PATH, "GET", null).statusCode());
            assertEquals(403, fixture.send(TunerAdminHttpController.PATH, "GET", null,
                listener, false).statusCode());
            assertEquals(200, fixture.send(TunerAdminHttpController.PATH, "GET", null,
                admin, false).statusCode());
            assertEquals(403, fixture.send(TunerAdminHttpController.RF_ANALYSIS_PATH, "GET", null,
                listener, false).statusCode());
            assertEquals(200, fixture.send(TunerAdminHttpController.RF_ANALYSIS_PATH, "GET", null,
                admin, false).statusCode());
            assertEquals(403, fixture.send(TunerAdminHttpController.RESCAN_PATH, "POST", null,
                admin, false).statusCode());
            assertEquals(0, manager.mRescanRequests);
            assertEquals(202, fixture.send(TunerAdminHttpController.RESCAN_PATH, "POST", null,
                admin, true).statusCode());
            String recordingPath = TunerAdminHttpController.PATH + "/" +
                TunerAdministrationService.opaqueId(fixture.mRecording);
            assertEquals(403, fixture.send(recordingPath, "DELETE", null,
                listener, true).statusCode());
            assertEquals(200, fixture.send(recordingPath, "DELETE", null,
                admin, true).statusCode());
        }
    }

    @Test
    void rfAnalysisUsesCurrentFrequencySupplierOnlyOnGet() throws Exception
    {
        FakeManager manager = new FakeManager();
        try(ServerFixture fixture = new ServerFixture(manager, null,
            () -> List.of(155_085_000L, 159_030_000L)))
        {
            HttpResponse<String> response = fixture.send(TunerAdminHttpController.RF_ANALYSIS_PATH, "GET", null);
            assertEquals(200, response.statusCode());
            assertEquals(0, MAPPER.readTree(response.body()).at("/data/tuners").size());
            assertEquals(155_085_000L, MAPPER.readTree(response.body())
                .at("/data/frequencies_hz/0").longValue());
            assertEquals(159_030_000L, MAPPER.readTree(response.body())
                .at("/data/frequencies_hz/1").longValue());
            assertEquals(400, fixture.send(TunerAdminHttpController.RF_ANALYSIS_PATH, "GET", "{}").statusCode());
            assertEquals(405, fixture.send(TunerAdminHttpController.RF_ANALYSIS_PATH, "POST", null).statusCode());
        }
    }

    @Test
    void operatorStateAndRestoreRoutesUseTheFiniteLifecycleContract() throws Exception
    {
        FakeManager manager = new FakeManager();
        try(ServerFixture fixture = new ServerFixture(manager))
        {
            String tunerPath = TunerAdminHttpController.PATH + "/" +
                TunerAdministrationService.opaqueId(fixture.mPhysical);
            assertEquals(202, fixture.send(tunerPath + "/state", "PUT", "{\"state\":\"setup\"}")
                .statusCode());
            assertEquals(422, fixture.send(tunerPath + "/state", "PUT", "{\"state\":\"unknown\"}")
                .statusCode());
            assertEquals(405, fixture.send(tunerPath + "/state", "POST", null).statusCode());
            assertEquals(404, fixture.send(tunerPath + "/enabled", "PUT", "{\"enabled\":true}")
                .statusCode());
            assertEquals(409, fixture.send(tunerPath + "/restore", "POST", null).statusCode());
            assertEquals(400, fixture.send(tunerPath + "/restore", "POST", "{}").statusCode());
        }
    }

    private static final class ServerFixture implements AutoCloseable
    {
        private final HttpServer mServer;
        private final HttpClient mClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        private final TunerSettingsService mSettings;
        private final DiscoveredRecordingTuner mRecording;
        private final DiscoveredTuner mPhysical;

        private ServerFixture(FakeManager manager) throws Exception
        {
            this(manager, null);
        }

        private ServerFixture(FakeManager manager, WebRequestSecurity security) throws Exception
        {
            this(manager, security, List::of);
        }

        private ServerFixture(FakeManager manager, WebRequestSecurity security,
                              Supplier<List<Long>> activeFrequencies) throws Exception
        {
            RecordingTunerConfiguration configuration = RecordingTunerConfiguration.createWithUniqueId();
            configuration.setPath("/not-exposed/recording.wav");
            mRecording = new DiscoveredRecordingTuner(configuration);
            mPhysical = new DiscoveredTuner()
            {
                @Override public TunerClass getTunerClass() { return TunerClass.TEST_TUNER; }
                @Override public String getId() { return "physical"; }
                @Override public void start() { }
            };
            manager.getDiscoveredTunerRegistry().add(mPhysical);
            TunerAdministrationService administration = new TunerAdministrationService(
                () -> List.of(mRecording, mPhysical), tuner -> null);
            mSettings = new TunerSettingsService(manager);
            TunerAdminHttpController controller = new TunerAdminHttpController(administration, mSettings, manager,
                activeFrequencies);
            mServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            mServer.createContext(TunerAdminHttpController.PATH, security == null ? controller::handle :
                security.protectApi(WebCapability.ADMIN_TUNERS, controller::handle));
            mServer.start();
        }

        private HttpResponse<String> send(String path, String method, String body) throws Exception
        {
            return send(path, method, body, null, false);
        }

        private HttpResponse<String> send(String path, String method, String body, WebAccessSession session,
                                          boolean csrf) throws Exception
        {
            URI origin = URI.create("http://127.0.0.1:" + mServer.getAddress().getPort());
            HttpRequest.Builder request = HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(5));
            if(body != null)
            {
                request.header("Content-Type", "application/json");
            }
            if(session != null)
            {
                request.header("Cookie", WebRequestSecurity.SESSION_COOKIE_NAME + "=" + session.sessionId());
            }
            if(csrf)
            {
                request.header("Origin", origin.toString());
                request.header(WebRequestSecurity.CSRF_HEADER_NAME, session.csrfToken());
            }
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() :
                HttpRequest.BodyPublishers.ofString(body));
            return mClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }

        @Override
        public void close()
        {
            mServer.stop(0);
            mSettings.close();
        }
    }

    private static final class FakeManager extends TunerManager
    {
        private CompletableFuture<Integer> mRescan = new CompletableFuture<>();
        private int mRescanRequests;
        private DiscoveredTuner mRemoved;
        private RecordingTunerRemovalResult mRemovalResult = RecordingTunerRemovalResult.REMOVED;

        private FakeManager()
        {
            super(null);
        }

        @Override
        public CompletableFuture<Integer> requestUsbTunerRescan()
        {
            mRescanRequests++;
            return mRescan;
        }

        @Override
        public RecordingTunerRemovalResult removeRecordingTuner(DiscoveredTuner tuner)
        {
            mRemoved = tuner;
            return mRemovalResult;
        }
    }
}
