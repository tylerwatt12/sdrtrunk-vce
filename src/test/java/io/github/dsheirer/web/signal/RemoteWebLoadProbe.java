/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */

package io.github.dsheirer.web.signal;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.spectrum.stream.SpectrumEncoding;
import io.github.dsheirer.spectrum.stream.SpectrumFrameCodec;
import io.github.dsheirer.web.auth.Pbkdf2PasswordHasher;
import io.github.dsheirer.web.auth.WebAdminAuthenticationHandler;
import java.io.ByteArrayOutputStream;
import java.io.Console;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Read-only load probe for an already-running receiver node.
 *
 * <p>This manually invoked utility is deliberately located in the test source set and is absent from normal JUnit
 * discovery. It opens one exclusive administrator spectrum workspace plus the public completed-call SSE/audio routes.
 * It does not send tuner commands, change application settings, write a database, or retain
 * received data after the process exits.</p>
 *
 * <p>Run it with the {@code remoteWebLoadProbe} Gradle task. Example:</p>
 *
 * <pre>
 * ssh -N -L 18090:127.0.0.1:8090 receiver
 * ./gradlew remoteWebLoadProbe --args='--base-url http://127.0.0.1:18090 --viewers 1 --feed-clients 10 --duration-seconds 120 --target AIRSPY'
 * </pre>
 *
 * <p>The administrator name and password are read only from a real interactive console.  Clear HTTP is accepted only
 * through a literal loopback URL (normally an SSH tunnel); a remote URL must use HTTPS.  The administrator cookie is
 * attached only to the exclusive spectrum WebSocket.  Public call-feed and audio requests remain anonymous.</p>
 */
public final class RemoteWebLoadProbe implements AutoCloseable
{
    static final int MAXIMUM_VIEWERS = 1;
    static final int MAXIMUM_FEED_CLIENTS = 10;
    static final int MAXIMUM_DURATION_SECONDS = 15 * 60;
    static final int MAXIMUM_FRAME_BYTES = 8 * 1024 * 1024;
    static final int MAXIMUM_AUDIO_BYTES = 16 * 1024 * 1024;
    static final int MAXIMUM_SSE_LINE_BYTES = 64 * 1024;
    static final double MINIMUM_FRAME_RATE_RATIO = 0.90;
    static final int MAXIMUM_AUTH_RESPONSE_BYTES = 16 * 1024;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration FIRST_DATA_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Configuration mConfiguration;
    private final CredentialPrompt mCredentialPrompt;
    private final HttpClient mStatusClient;
    private final HttpClient mSpectrumClient;
    private final List<HttpClient> mAnonymousFeedClients = new ArrayList<>();
    private final ExecutorService mClientExecutor = Executors.newThreadPerTaskExecutor(
        Thread.ofVirtual().name("remote web load probe-", 0).factory());
    private final List<SignalListener> mSignalListeners = new ArrayList<>();
    private final List<WebSocket> mSignalSockets = new ArrayList<>();
    private final List<FeedClient> mFeedClients = new ArrayList<>();
    private final ConcurrentLinkedQueue<String> mFailures = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean mClosing = new AtomicBoolean();
    private AdminSession mAdminSession;

    private RemoteWebLoadProbe(Configuration configuration, CredentialPrompt credentialPrompt)
    {
        mConfiguration = Objects.requireNonNull(configuration);
        mCredentialPrompt = Objects.requireNonNull(credentialPrompt);
        System.setProperty("jdk.httpclient.allowRestrictedHeaders", "origin");
        mStatusClient = newClient();
        mSpectrumClient = newClient();

        for(int index = 0; index < configuration.feedClientCount(); index++)
        {
            // One client per simulated browser avoids introducing a shared client-side connection/executor bottleneck
            // that ten independent end-user browsers would not have.  These clients never receive the admin cookie.
            mAnonymousFeedClients.add(newClient());
        }
    }

    private static HttpClient newClient()
    {
        return HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public static void main(String[] args) throws Exception
    {
        if(args.length == 1 && "--help".equals(args[0]))
        {
            System.out.println(Configuration.usage());
            return;
        }

        Configuration configuration = Configuration.parse(args);
        Console console = System.console();

        if(console == null)
        {
            throw new IllegalStateException("A real interactive console is required for administrator login. " +
                "Run this probe from a terminal attached directly to the Java process; credentials are never " +
                "accepted from command-line options, environment variables, or files.");
        }

        try(RemoteWebLoadProbe probe = new RemoteWebLoadProbe(configuration, new ConsoleCredentialPrompt(console)))
        {
            Result result = probe.run();
            result.print();

            if(!result.passed())
            {
                throw new IllegalStateException("Remote web load probe failed; see the PROBE result line");
            }
        }
    }

    private Result run() throws Exception
    {
        long statusStart = System.nanoTime();
        HttpResponse<String> status = mStatusClient.send(HttpRequest.newBuilder(mConfiguration.statusUri())
            .timeout(REQUEST_TIMEOUT).header("Accept", "application/json").GET().build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        long statusMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - statusStart);

        if(status.statusCode() != 200)
        {
            throw new IOException("Status endpoint returned HTTP " + status.statusCode());
        }

        ServerSnapshot initialServer = ServerSnapshot.from(OBJECT_MAPPER.readTree(status.body()));
        openSignalViewers();
        awaitFirstSignalFrames();
        openFeedClients();
        awaitFeedReadiness();

        mSignalListeners.forEach(SignalListener::beginMeasurement);
        List<SignalSnapshot> initialSignal = mSignalListeners.stream().map(SignalListener::snapshot).toList();
        List<FeedSnapshot> initialFeed = mFeedClients.stream().map(FeedClient::snapshot).toList();
        long started = System.nanoTime();
        System.out.printf("PROBE phase=READY viewers=%d feedClients=%d maxFps=%d statusMillis=%d " +
                "centerHz=%d sampleRateHz=%d bins=%d%n",
            mSignalListeners.size(), mFeedClients.size(), mConfiguration.maximumFramesPerSecond(), statusMillis,
            mSignalListeners.getFirst().latestHeader().centerFrequencyHz(),
            mSignalListeners.getFirst().latestHeader().sampleRateHz(),
            mSignalListeners.getFirst().latestHeader().binCount());
        System.out.flush();

        long deadline = started + mConfiguration.duration().toNanos();

        while(System.nanoTime() < deadline && mFailures.isEmpty())
        {
            long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            Thread.sleep(Math.max(1, Math.min(500, remainingMillis)));
        }

        long elapsedNanos = Math.max(1, System.nanoTime() - started);
        ServerSnapshot finalServer;

        try
        {
            finalServer = readServerSnapshot();
        }
        catch(Exception exception)
        {
            recordFailure("final-status " + exception.getClass().getSimpleName());
            finalServer = initialServer;
        }

        stopClients();
        List<SignalSnapshot> finalSignal = mSignalListeners.stream().map(SignalListener::snapshot).toList();
        List<FeedSnapshot> finalFeed = mFeedClients.stream().map(FeedClient::snapshot).toList();
        return Result.from(mConfiguration, statusMillis, elapsedNanos, initialSignal, finalSignal,
            initialFeed, finalFeed, initialServer, finalServer, List.copyOf(mFailures));
    }

    private ServerSnapshot readServerSnapshot() throws IOException, InterruptedException
    {
        HttpResponse<String> response = mStatusClient.send(HttpRequest.newBuilder(mConfiguration.statusUri())
            .timeout(REQUEST_TIMEOUT).header("Accept", "application/json").GET().build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if(response.statusCode() != 200)
        {
            throw new IOException("Status endpoint returned HTTP " + response.statusCode());
        }

        return ServerSnapshot.from(OBJECT_MAPPER.readTree(response.body()));
    }

    private void openSignalViewers() throws Exception
    {
        if(mConfiguration.viewerCount() != 1)
        {
            throw new IllegalStateException("The administrator spectrum probe requires exactly one viewer");
        }

        mAdminSession = loginAdministrator(mSpectrumClient, mConfiguration, mCredentialPrompt);

        for(int index = 0; index < mConfiguration.viewerCount(); index++)
        {
            SignalListener listener = new SignalListener(index + 1, this::recordFailure);
            WebSocket socket = mSpectrumClient.newWebSocketBuilder().connectTimeout(CONNECT_TIMEOUT)
                .header("Origin", mConfiguration.origin())
                .header("Cookie", mAdminSession.cookieHeader())
                .buildAsync(mConfiguration.webSocketUri(), listener)
                .get(CONNECT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            mSignalListeners.add(listener);
            mSignalSockets.add(socket);
            String target = mConfiguration.targetId() != null ?
                ",\"targetId\":\"" + mConfiguration.targetId() + "\"" : "";
            socket.sendText("{\"action\":\"subscribe\",\"requestId\":1,\"maxFps\":" +
                mConfiguration.maximumFramesPerSecond() + target + "}", true)
                .get(CONNECT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private void awaitFirstSignalFrames() throws Exception
    {
        CompletableFuture<?>[] firstFrames = mSignalListeners.stream().map(SignalListener::firstFrame)
            .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(firstFrames).get(FIRST_DATA_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void openFeedClients() throws Exception
    {
        for(int index = 0; index < mConfiguration.feedClientCount(); index++)
        {
            HttpRequest request = HttpRequest.newBuilder(mConfiguration.feedUri())
                .header("Accept", "text/event-stream").GET().build();
            HttpClient browserClient = mAnonymousFeedClients.get(index);
            HttpResponse<InputStream> response = browserClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

            if(response.statusCode() != 200)
            {
                response.body().close();
                throw new IOException("Live call feed returned HTTP " + response.statusCode());
            }

            String contentType = response.headers().firstValue("Content-Type").orElse("");

            if(!contentType.toLowerCase(Locale.US).startsWith("text/event-stream"))
            {
                response.body().close();
                throw new IOException("Live call feed did not return text/event-stream");
            }

            FeedClient feedClient = new FeedClient(index + 1, response.body(), browserClient, mClientExecutor,
                mConfiguration.baseUri(), this::recordFailure);
            mFeedClients.add(feedClient);
            feedClient.start();
        }
    }

    private void awaitFeedReadiness() throws Exception
    {
        CompletableFuture<?>[] ready = mFeedClients.stream().map(FeedClient::ready)
            .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(ready).get(FIRST_DATA_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void recordFailure(String failure)
    {
        if(!mClosing.get() && mFailures.size() < 8)
        {
            mFailures.add(failure);
        }
    }

    private void stopClients()
    {
        if(!mClosing.compareAndSet(false, true))
        {
            return;
        }

        for(FeedClient feedClient: mFeedClients)
        {
            feedClient.stop();
        }

        for(int index = 0; index < mSignalSockets.size(); index++)
        {
            WebSocket socket = mSignalSockets.get(index);
            mSignalListeners.get(index).stop();

            try
            {
                socket.sendText("{\"action\":\"unsubscribe\"}", true)
                    .thenCompose(ignored -> socket.sendClose(WebSocket.NORMAL_CLOSURE, "probe complete"))
                    .get(CONNECT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            }
            catch(Exception exception)
            {
                socket.abort();
            }
        }

        mClientExecutor.shutdown();

        try
        {
            if(!mClientExecutor.awaitTermination(REQUEST_TIMEOUT.toSeconds() + 2, TimeUnit.SECONDS))
            {
                mClientExecutor.shutdownNow();
            }
        }
        catch(InterruptedException exception)
        {
            Thread.currentThread().interrupt();
            mClientExecutor.shutdownNow();
        }
    }

    @Override
    public void close()
    {
        stopClients();
        logoutAdministrator();

        for(HttpClient browserClient: mAnonymousFeedClients)
        {
            browserClient.close();
        }

        mSpectrumClient.close();
        mStatusClient.close();
    }

    private void logoutAdministrator()
    {
        AdminSession session = mAdminSession;
        mAdminSession = null;

        if(session == null)
        {
            return;
        }

        try
        {
            HttpRequest request = HttpRequest.newBuilder(mConfiguration.logoutUri())
                .timeout(REQUEST_TIMEOUT)
                .header("Origin", mConfiguration.origin())
                .header("Cookie", session.cookieHeader())
                .header(WebAdminAuthenticationHandler.CSRF_HEADER_NAME, session.csrfToken())
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
            HttpResponse<Void> response = mSpectrumClient.send(request,
                HttpResponse.BodyHandlers.discarding());

            if(response.statusCode() != 200 && response.statusCode() != 401)
            {
                // Logout is best effort during teardown.  Never include a response body, cookie, or token here.
                mFailures.add("admin-logout http-" + response.statusCode());
            }
        }
        catch(Exception exception)
        {
            mFailures.add("admin-logout " + exception.getClass().getSimpleName());
        }
    }

    static AdminSession loginAdministrator(HttpClient client, Configuration configuration,
                                           CredentialPrompt credentialPrompt) throws Exception
    {
        Objects.requireNonNull(client, "HTTP client cannot be null");
        Objects.requireNonNull(configuration, "Probe configuration cannot be null");
        Objects.requireNonNull(credentialPrompt, "Credential prompt cannot be null");

        try(AdminCredentials credentials = credentialPrompt.read())
        {
            byte[] requestBody = loginRequestBody(credentials.username(), credentials.password());

            try
            {
                if(requestBody.length > WebAdminAuthenticationHandler.Configuration.defaults()
                    .maximumLoginBodyBytes())
                {
                    throw new IOException("Administrator credentials exceed the bounded login request size");
                }

                HttpRequest request = HttpRequest.newBuilder(configuration.loginUri())
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Origin", configuration.origin())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody))
                    .build();
                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

                try(InputStream responseBody = response.body())
                {
                    if(response.statusCode() != 200)
                    {
                        throw new IOException("Administrator login returned HTTP " + response.statusCode() +
                            "; verify the credentials and use HTTPS or a loopback SSH tunnel");
                    }

                    byte[] body = responseBody.readNBytes(MAXIMUM_AUTH_RESPONSE_BYTES + 1);

                    try
                    {
                        if(body.length > MAXIMUM_AUTH_RESPONSE_BYTES)
                        {
                            throw new IOException("Administrator login response exceeded the probe limit");
                        }

                        String csrfToken = parseLoginCsrfToken(body);
                        String cookieHeader = parseSessionCookie(response.headers()
                            .allValues("Set-Cookie"), configuration.isSecureTransport());
                        return new AdminSession(cookieHeader, csrfToken);
                    }
                    finally
                    {
                        Arrays.fill(body, (byte)0);
                    }
                }
            }
            finally
            {
                Arrays.fill(requestBody, (byte)0);
            }
        }
    }

    static byte[] loginRequestBody(String username, char[] password)
    {
        Objects.requireNonNull(username, "Administrator username cannot be null");
        Objects.requireNonNull(password, "Administrator password cannot be null");
        WipeableByteArrayOutputStream output = new WipeableByteArrayOutputStream();

        try
        {
            writeAscii(output, "{\"username\":");
            writeJsonString(output, username);
            writeAscii(output, ",\"password\":");
            writeJsonString(output, password);
            output.write('}');
            return output.toByteArray();
        }
        finally
        {
            output.wipe();
        }
    }

    static String parseLoginCsrfToken(byte[] body) throws IOException
    {
        boolean authenticated = false;
        boolean sawAuthenticated = false;
        String csrfToken = null;

        try(JsonParser parser = OBJECT_MAPPER.getFactory().createParser(body))
        {
            if(parser.nextToken() != JsonToken.START_OBJECT)
            {
                throw new IOException("Administrator login response was invalid");
            }

            while(parser.nextToken() != JsonToken.END_OBJECT)
            {
                if(parser.currentToken() != JsonToken.FIELD_NAME)
                {
                    throw new IOException("Administrator login response was invalid");
                }

                String name = parser.currentName();
                JsonToken value = parser.nextToken();

                if("authenticated".equals(name))
                {
                    if(sawAuthenticated || (value != JsonToken.VALUE_TRUE && value != JsonToken.VALUE_FALSE))
                    {
                        throw new IOException("Administrator login response was invalid");
                    }

                    sawAuthenticated = true;
                    authenticated = value == JsonToken.VALUE_TRUE;
                }
                else if("csrfToken".equals(name))
                {
                    if(csrfToken != null || value != JsonToken.VALUE_STRING)
                    {
                        throw new IOException("Administrator login response was invalid");
                    }

                    csrfToken = parser.getText();
                }
                else
                {
                    parser.skipChildren();
                }
            }

            if(parser.nextToken() != null || !sawAuthenticated || !authenticated || !isBoundedToken(csrfToken))
            {
                throw new IOException("Administrator login response was invalid");
            }
        }

        return csrfToken;
    }

    static String parseSessionCookie(List<String> setCookieHeaders, boolean secureTransport) throws IOException
    {
        String cookieHeader = null;

        for(String header: setCookieHeaders)
        {
            String[] attributes = header.split(";", -1);
            int separator = attributes[0].indexOf('=');

            if(separator < 1 || !WebAdminAuthenticationHandler.SESSION_COOKIE_NAME.equals(
                attributes[0].substring(0, separator).strip()))
            {
                continue;
            }

            if(cookieHeader != null)
            {
                throw new IOException("Administrator login returned multiple session cookies");
            }

            String value = attributes[0].substring(separator + 1).strip();
            boolean httpOnly = false;
            boolean sameSiteStrict = false;
            boolean rootPath = false;
            boolean secure = false;

            for(int index = 1; index < attributes.length; index++)
            {
                String attribute = attributes[index].strip();
                httpOnly |= "HttpOnly".equalsIgnoreCase(attribute);
                sameSiteStrict |= "SameSite=Strict".equalsIgnoreCase(attribute);
                rootPath |= "Path=/".equalsIgnoreCase(attribute);
                secure |= "Secure".equalsIgnoreCase(attribute);
            }

            if(!isBoundedToken(value) || !httpOnly || !sameSiteStrict || !rootPath ||
                (secureTransport && !secure))
            {
                throw new IOException("Administrator login returned an unusable session cookie");
            }

            cookieHeader = WebAdminAuthenticationHandler.SESSION_COOKIE_NAME + "=" + value;
        }

        if(cookieHeader == null)
        {
            throw new IOException("Administrator login did not return a session cookie");
        }

        return cookieHeader;
    }

    private static boolean isBoundedToken(String value)
    {
        return value != null && value.length() >= 32 && value.length() <= 256 &&
            value.chars().allMatch(character -> (character >= 'A' && character <= 'Z') ||
                (character >= 'a' && character <= 'z') || (character >= '0' && character <= '9') ||
                character == '_' || character == '-');
    }

    private static void writeJsonString(ByteArrayOutputStream output, CharSequence value)
    {
        output.write('"');

        for(int index = 0; index < value.length(); index++)
        {
            writeJsonCharacter(output, value.charAt(index));
        }

        output.write('"');
    }

    private static void writeJsonString(ByteArrayOutputStream output, char[] value)
    {
        output.write('"');

        for(char character: value)
        {
            writeJsonCharacter(output, character);
        }

        output.write('"');
    }

    private static void writeJsonCharacter(ByteArrayOutputStream output, char character)
    {
        switch(character)
        {
            case '"' -> writeAscii(output, "\\\"");
            case '\\' -> writeAscii(output, "\\\\");
            case '\b' -> writeAscii(output, "\\b");
            case '\f' -> writeAscii(output, "\\f");
            case '\n' -> writeAscii(output, "\\n");
            case '\r' -> writeAscii(output, "\\r");
            case '\t' -> writeAscii(output, "\\t");
            default ->
            {
                if(character < 0x20 || Character.isSurrogate(character))
                {
                    writeAscii(output, "\\u");
                    output.write(Character.forDigit(character >>> 12 & 0xF, 16));
                    output.write(Character.forDigit(character >>> 8 & 0xF, 16));
                    output.write(Character.forDigit(character >>> 4 & 0xF, 16));
                    output.write(Character.forDigit(character & 0xF, 16));
                }
                else if(character <= 0x7F)
                {
                    output.write(character);
                }
                else if(character <= 0x7FF)
                {
                    output.write(0xC0 | character >>> 6);
                    output.write(0x80 | character & 0x3F);
                }
                else
                {
                    output.write(0xE0 | character >>> 12);
                    output.write(0x80 | character >>> 6 & 0x3F);
                    output.write(0x80 | character & 0x3F);
                }
            }
        }
    }

    private static void writeAscii(ByteArrayOutputStream output, String value)
    {
        for(int index = 0; index < value.length(); index++)
        {
            output.write(value.charAt(index));
        }
    }

    interface CredentialPrompt
    {
        AdminCredentials read();
    }

    record AdminCredentials(String username, char[] password) implements AutoCloseable
    {
        AdminCredentials
        {
            username = Objects.requireNonNull(username, "Administrator username cannot be null").strip();
            password = Objects.requireNonNull(password, "Administrator password cannot be null");

            if(username.isBlank() || username.length() > 64)
            {
                throw new IllegalArgumentException("Administrator username is invalid");
            }

            if(password.length < 1 || password.length > Pbkdf2PasswordHasher.MAXIMUM_PASSWORD_CHARACTERS)
            {
                throw new IllegalArgumentException("Administrator password length is invalid");
            }
        }

        @Override
        public void close()
        {
            Arrays.fill(password, '\0');
        }

        @Override
        public String toString()
        {
            return "AdminCredentials[username=<redacted>, password=<redacted>]";
        }
    }

    record AdminSession(String cookieHeader, String csrfToken)
    {
        AdminSession
        {
            Objects.requireNonNull(cookieHeader, "Administrator session cookie cannot be null");
            Objects.requireNonNull(csrfToken, "Administrator CSRF token cannot be null");
        }

        @Override
        public String toString()
        {
            return "AdminSession[cookie=<redacted>, csrf=<redacted>]";
        }
    }

    private static final class ConsoleCredentialPrompt implements CredentialPrompt
    {
        private final Console mConsole;

        private ConsoleCredentialPrompt(Console console)
        {
            mConsole = Objects.requireNonNull(console);
        }

        @Override
        public AdminCredentials read()
        {
            String username = mConsole.readLine("Administrator username: ");
            char[] password = mConsole.readPassword("Administrator password: ");

            if(username == null || password == null)
            {
                if(password != null)
                {
                    Arrays.fill(password, '\0');
                }

                throw new IllegalStateException("Administrator login was cancelled");
            }

            try
            {
                return new AdminCredentials(username, password);
            }
            catch(RuntimeException exception)
            {
                Arrays.fill(password, '\0');
                throw exception;
            }
        }
    }

    private static final class WipeableByteArrayOutputStream extends ByteArrayOutputStream
    {
        private void wipe()
        {
            Arrays.fill(buf, (byte)0);
            reset();
        }
    }

    static FrameHeader inspectFrame(ByteBuffer source)
    {
        ByteBuffer frame = source.slice().order(ByteOrder.LITTLE_ENDIAN);
        int frameBytes = frame.remaining();

        if(frameBytes < SpectrumFrameCodec.HEADER_BYTE_COUNT)
        {
            throw new IllegalArgumentException("SFFT frame is shorter than its header");
        }

        if(frame.get(0) != 'S' || frame.get(1) != 'F' || frame.get(2) != 'F' || frame.get(3) != 'T')
        {
            throw new IllegalArgumentException("SFFT frame magic is invalid");
        }

        int version = Short.toUnsignedInt(frame.getShort(SpectrumFrameCodec.OFFSET_VERSION));
        int headerBytes = Short.toUnsignedInt(frame.getShort(SpectrumFrameCodec.OFFSET_HEADER_BYTE_COUNT));
        long targetGeneration = frame.getLong(SpectrumFrameCodec.OFFSET_TARGET_GENERATION);
        long sequence = frame.getLong(SpectrumFrameCodec.OFFSET_SEQUENCE);
        long centerFrequencyHz = frame.getLong(SpectrumFrameCodec.OFFSET_CENTER_FREQUENCY);
        long sampleRateHz = frame.getLong(SpectrumFrameCodec.OFFSET_SAMPLE_RATE);
        int binCount = frame.getInt(SpectrumFrameCodec.OFFSET_BIN_COUNT);
        int encoding = Byte.toUnsignedInt(frame.get(SpectrumFrameCodec.OFFSET_ENCODING));
        int payloadBytes = frame.getInt(SpectrumFrameCodec.OFFSET_PAYLOAD_BYTE_COUNT);

        if(version != SpectrumFrameCodec.VERSION || headerBytes != SpectrumFrameCodec.HEADER_BYTE_COUNT)
        {
            throw new IllegalArgumentException("SFFT frame version or header length is invalid");
        }

        if(targetGeneration < 0 || sequence < 0 || centerFrequencyHz < 0 || sampleRateHz <= 0)
        {
            throw new IllegalArgumentException("SFFT frame contains invalid metadata");
        }

        if(binCount < 1 || binCount > SpectrumFrameCodec.MAXIMUM_BIN_COUNT ||
            encoding != SpectrumEncoding.FLOAT32.getWireIdentifier())
        {
            throw new IllegalArgumentException("SFFT frame bin count or encoding is invalid");
        }

        if(frame.get(SpectrumFrameCodec.OFFSET_ENCODING + 1) != 0 ||
            frame.get(SpectrumFrameCodec.OFFSET_ENCODING + 2) != 0 ||
            frame.get(SpectrumFrameCodec.OFFSET_ENCODING + 3) != 0 ||
            Float.compare(frame.getFloat(SpectrumFrameCodec.OFFSET_QUANTIZATION_SCALE), 1.0f) != 0 ||
            Float.compare(frame.getFloat(SpectrumFrameCodec.OFFSET_QUANTIZATION_OFFSET), 0.0f) != 0)
        {
            throw new IllegalArgumentException("SFFT frame reserved or quantization fields are invalid");
        }

        int expectedPayload = Math.multiplyExact(binCount, Float.BYTES);

        if(payloadBytes != expectedPayload || frameBytes != Math.addExact(headerBytes, payloadBytes))
        {
            throw new IllegalArgumentException("SFFT frame payload length is invalid");
        }

        return new FrameHeader(targetGeneration, sequence, centerFrequencyHz, sampleRateHz, binCount, frameBytes);
    }

    record Configuration(URI baseUri, String origin, int viewerCount, int feedClientCount, Duration duration,
                         int maximumFramesPerSecond, String targetId)
    {
        Configuration
        {
            baseUri = validateHttpOrigin(baseUri, "base URL");
            URI originUri = validateHttpOrigin(URI.create(Objects.requireNonNull(origin,
                "Origin cannot be null")), "origin");

            if(!sameOrigin(baseUri, originUri))
            {
                throw new IllegalArgumentException("Origin must exactly match the base URL origin");
            }

            origin = httpOrigin(originUri);

            if("http".equalsIgnoreCase(baseUri.getScheme()) && !isLiteralLoopbackHost(baseUri.getHost()))
            {
                throw new IllegalArgumentException("Clear HTTP is allowed only through a literal loopback URL. " +
                    "Use HTTPS for a remote node or connect through an SSH tunnel to 127.0.0.1/localhost.");
            }

            if(viewerCount < 1 || viewerCount > MAXIMUM_VIEWERS)
            {
                throw new IllegalArgumentException("Viewer count must be between 1 and " + MAXIMUM_VIEWERS);
            }

            if(feedClientCount < 0 || feedClientCount > MAXIMUM_FEED_CLIENTS)
            {
                throw new IllegalArgumentException("Feed client count must be between 0 and " + MAXIMUM_FEED_CLIENTS);
            }

            Objects.requireNonNull(duration, "Duration cannot be null");

            if(duration.compareTo(Duration.ofSeconds(5)) < 0 ||
                duration.compareTo(Duration.ofSeconds(MAXIMUM_DURATION_SECONDS)) > 0)
            {
                throw new IllegalArgumentException("Duration must be between 5 and " + MAXIMUM_DURATION_SECONDS +
                    " seconds");
            }

            if(maximumFramesPerSecond < 1 || maximumFramesPerSecond > 30)
            {
                throw new IllegalArgumentException("Maximum frame rate must be between 1 and 30 FPS");
            }

            if(targetId != null)
            {
                targetId = targetId.strip().toUpperCase(Locale.ROOT);

                if(targetId.isBlank() || targetId.length() > 32 || !targetId.matches("[A-Z0-9_\\-]+"))
                {
                    throw new IllegalArgumentException("Target ID is invalid");
                }
            }
        }

        static Configuration parse(String[] args)
        {
            URI baseUri = null;
            String origin = null;
            int viewers = 1;
            int feedClients = 10;
            int durationSeconds = 60;
            int maximumFramesPerSecond = 20;
            String targetId = null;

            for(int index = 0; index < args.length; index += 2)
            {
                if(index + 1 >= args.length)
                {
                    throw new IllegalArgumentException("Missing value for " + args[index] + "\n" + usage());
                }

                String key = args[index];
                String value = args[index + 1];

                switch(key)
                {
                    case "--base-url" -> baseUri = URI.create(value);
                    case "--origin" -> origin = value;
                    case "--viewers" -> viewers = parseInteger(value, "viewer count");
                    case "--feed-clients" -> feedClients = parseInteger(value, "feed client count");
                    case "--duration-seconds" -> durationSeconds = parseInteger(value, "duration");
                    case "--max-fps" -> maximumFramesPerSecond = parseInteger(value, "maximum frame rate");
                    case "--target" -> targetId = value;
                    default -> throw new IllegalArgumentException("Unknown option: " + key + "\n" + usage());
                }
            }

            if(baseUri == null)
            {
                throw new IllegalArgumentException("--base-url is required\n" + usage());
            }

            URI validatedBase = validateHttpOrigin(baseUri, "base URL");
            String effectiveOrigin = origin != null ? origin : httpOrigin(validatedBase);
            return new Configuration(validatedBase, effectiveOrigin, viewers, feedClients,
                Duration.ofSeconds(durationSeconds), maximumFramesPerSecond, targetId);
        }

        URI statusUri()
        {
            return baseUri.resolve("/api/status");
        }

        URI feedUri()
        {
            return baseUri.resolve("/live/web-calls");
        }

        URI loginUri()
        {
            return baseUri.resolve(WebAdminAuthenticationHandler.LOGIN_PATH);
        }

        URI logoutUri()
        {
            return baseUri.resolve(WebAdminAuthenticationHandler.LOGOUT_PATH);
        }

        boolean isSecureTransport()
        {
            return "https".equalsIgnoreCase(baseUri.getScheme());
        }

        URI webSocketUri()
        {
            String scheme = "https".equalsIgnoreCase(baseUri.getScheme()) ? "wss" : "ws";

            try
            {
                return new URI(scheme, null, baseUri.getHost(), baseUri.getPort(), SignalWebSocketTransport.PATH,
                    null, null);
            }
            catch(URISyntaxException exception)
            {
                throw new IllegalArgumentException("Unable to create signal WebSocket URL", exception);
            }
        }

        static String usage()
        {
            return "Usage: --base-url https://host:port [--origin https://same-host:port] " +
                "[--viewers 1] [--feed-clients 0..10] [--duration-seconds 5..900] [--max-fps 1..30] " +
                "[--target AIRSPY|RTL2832]\n" +
                "Administrator credentials are prompted from a real console. For clear HTTP, use a loopback " +
                "base URL through an SSH tunnel; no credential options or files are supported.";
        }

        private static int parseInteger(String value, String label)
        {
            try
            {
                return Integer.parseInt(value);
            }
            catch(NumberFormatException exception)
            {
                throw new IllegalArgumentException("Invalid " + label + ": " + value, exception);
            }
        }

        private static URI validateHttpOrigin(URI uri, String label)
        {
            Objects.requireNonNull(uri, label + " cannot be null");
            String scheme = Optional.ofNullable(uri.getScheme()).orElse("").toLowerCase(Locale.US);
            String path = Optional.ofNullable(uri.getPath()).orElse("");

            if((!"http".equals(scheme) && !"https".equals(scheme)) || uri.getHost() == null ||
                uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null ||
                (!path.isEmpty() && !"/".equals(path)))
            {
                throw new IllegalArgumentException(label +
                    " must be an HTTP(S) origin with no credentials, query, fragment, or non-root path");
            }

            try
            {
                return new URI(scheme, null, uri.getHost(), uri.getPort(), "/", null, null);
            }
            catch(URISyntaxException exception)
            {
                throw new IllegalArgumentException("Invalid " + label, exception);
            }
        }

        private static boolean isLiteralLoopbackHost(String value)
        {
            String host = Objects.requireNonNull(value).toLowerCase(Locale.ROOT);

            if("localhost".equals(host) || "[::1]".equals(host) || "::1".equals(host))
            {
                return true;
            }

            String[] octets = host.split("\\.", -1);

            if(octets.length != 4 || !"127".equals(octets[0]))
            {
                return false;
            }

            for(String octet: octets)
            {
                try
                {
                    if(octet.isEmpty() || octet.length() > 3 || Integer.parseInt(octet) > 255)
                    {
                        return false;
                    }
                }
                catch(NumberFormatException exception)
                {
                    return false;
                }
            }

            return true;
        }

        private static String httpOrigin(URI uri)
        {
            try
            {
                return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), null, null, null).toString();
            }
            catch(URISyntaxException exception)
            {
                throw new IllegalArgumentException("Unable to create HTTP origin", exception);
            }
        }
    }

    record FrameHeader(long targetGeneration, long sequence, long centerFrequencyHz, long sampleRateHz, int binCount,
                       int frameBytes)
    {
    }

    private static final class SignalListener implements WebSocket.Listener
    {
        private final int mIdentifier;
        private final java.util.function.Consumer<String> mFailureConsumer;
        private final CompletableFuture<FrameHeader> mFirstFrame = new CompletableFuture<>();
        private final ByteArrayOutputStream mPartial = new ByteArrayOutputStream();
        private final AtomicBoolean mStopping = new AtomicBoolean();
        private long mFrames;
        private long mBytes;
        private long mSequenceSkips;
        private long mOutOfOrderFrames;
        private long mTargetChanges;
        private long mMaximumInterFrameNanos;
        private long mLastArrivalNanos;
        private long mLastTargetGeneration = -1;
        private long mLastSequence = -1;
        private FrameHeader mLatestHeader;

        private SignalListener(int identifier, java.util.function.Consumer<String> failureConsumer)
        {
            mIdentifier = identifier;
            mFailureConsumer = failureConsumer;
        }

        @Override
        public void onOpen(WebSocket webSocket)
        {
            webSocket.request(Long.MAX_VALUE);
        }

        @Override
        public synchronized CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last)
        {
            try
            {
                if(mPartial.size() == 0 && last)
                {
                    accept(inspectFrame(data));
                }
                else
                {
                    if((long)mPartial.size() + data.remaining() > MAXIMUM_FRAME_BYTES)
                    {
                        throw new IllegalArgumentException("SFFT message exceeds the probe frame limit");
                    }

                    byte[] fragment = new byte[data.remaining()];
                    data.get(fragment);
                    mPartial.writeBytes(fragment);

                    if(last)
                    {
                        accept(inspectFrame(ByteBuffer.wrap(mPartial.toByteArray())));
                        mPartial.reset();
                    }
                }
            }
            catch(RuntimeException exception)
            {
                fail("signal-" + mIdentifier + " invalid-frame " + exception.getClass().getSimpleName());
                webSocket.abort();
            }

            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason)
        {
            if(!mStopping.get())
            {
                fail("signal-" + mIdentifier + " closed-" + statusCode);
            }

            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error)
        {
            if(!mStopping.get())
            {
                fail("signal-" + mIdentifier + " " + error.getClass().getSimpleName());
            }
        }

        private void accept(FrameHeader header)
        {
            long now = System.nanoTime();

            if(mLastArrivalNanos != 0)
            {
                mMaximumInterFrameNanos = Math.max(mMaximumInterFrameNanos, now - mLastArrivalNanos);
            }

            if(mLastTargetGeneration == header.targetGeneration())
            {
                if(mLastSequence >= 0 && header.sequence() > mLastSequence + 1)
                {
                    mSequenceSkips += header.sequence() - mLastSequence - 1;
                }
                else if(mLastSequence >= 0 && header.sequence() <= mLastSequence)
                {
                    mOutOfOrderFrames++;
                }
            }
            else if(mLastTargetGeneration >= 0)
            {
                mTargetChanges++;
            }

            mLastArrivalNanos = now;
            mLastTargetGeneration = header.targetGeneration();
            mLastSequence = header.sequence();
            mLatestHeader = header;
            mFrames++;
            mBytes += header.frameBytes();
            mFirstFrame.complete(header);
        }

        private void fail(String failure)
        {
            mFirstFrame.completeExceptionally(new IOException(failure));
            mFailureConsumer.accept(failure);
        }

        private CompletableFuture<FrameHeader> firstFrame()
        {
            return mFirstFrame;
        }

        private synchronized FrameHeader latestHeader()
        {
            return mLatestHeader;
        }

        private synchronized SignalSnapshot snapshot()
        {
            return new SignalSnapshot(mFrames, mBytes, mSequenceSkips, mOutOfOrderFrames, mTargetChanges,
                mMaximumInterFrameNanos);
        }

        private synchronized void beginMeasurement()
        {
            mFrames = 0;
            mBytes = 0;
            mSequenceSkips = 0;
            mOutOfOrderFrames = 0;
            mTargetChanges = 0;
            mMaximumInterFrameNanos = 0;
            mLastArrivalNanos = System.nanoTime();
        }

        private void stop()
        {
            mStopping.set(true);
        }
    }

    record SignalSnapshot(long frames, long bytes, long sequenceSkips, long outOfOrderFrames, long targetChanges,
                          long maximumInterFrameNanos)
    {
        SignalSnapshot subtract(SignalSnapshot earlier)
        {
            return new SignalSnapshot(frames - earlier.frames, bytes - earlier.bytes,
                sequenceSkips - earlier.sequenceSkips, outOfOrderFrames - earlier.outOfOrderFrames,
                targetChanges - earlier.targetChanges, maximumInterFrameNanos);
        }
    }

    private static final class FeedClient
    {
        private final int mIdentifier;
        private final InputStream mInputStream;
        private final HttpClient mHttpClient;
        private final ExecutorService mExecutor;
        private final URI mBaseUri;
        private final java.util.function.Consumer<String> mFailureConsumer;
        private final CompletableFuture<Void> mReady = new CompletableFuture<>();
        private final AtomicBoolean mStopping = new AtomicBoolean();
        private final AtomicBoolean mAudioInFlight = new AtomicBoolean();
        private final LongAdder mCallEvents = new LongAdder();
        private final LongAdder mAudioRequests = new LongAdder();
        private final LongAdder mAudioSuccesses = new LongAdder();
        private final LongAdder mAudioFailures = new LongAdder();
        private final LongAdder mAudioSkippedBusy = new LongAdder();
        private final LongAdder mAudioBytes = new LongAdder();

        private FeedClient(int identifier, InputStream inputStream, HttpClient httpClient, ExecutorService executor,
                           URI baseUri, java.util.function.Consumer<String> failureConsumer)
        {
            mIdentifier = identifier;
            mInputStream = inputStream;
            mHttpClient = httpClient;
            mExecutor = executor;
            mBaseUri = baseUri;
            mFailureConsumer = failureConsumer;
        }

        private void start()
        {
            mExecutor.execute(this::readEvents);
        }

        private void readEvents()
        {
            try
            {
                SseEventReader reader = new SseEventReader(mInputStream);
                SseEvent event;

                while(!mStopping.get() && (event = reader.next()) != null)
                {
                    if("ready".equals(event.name()))
                    {
                        mReady.complete(null);
                    }
                    else if("call".equals(event.name()))
                    {
                        mCallEvents.increment();
                        fetchAudio(event.data());
                    }
                }

                if(!mStopping.get())
                {
                    fail("feed-" + mIdentifier + " unexpected-eof");
                }
            }
            catch(Exception exception)
            {
                if(!mStopping.get())
                {
                    fail("feed-" + mIdentifier + " " + exception.getClass().getSimpleName());
                }
            }
        }

        private void fetchAudio(String data)
        {
            if(!mAudioInFlight.compareAndSet(false, true))
            {
                mAudioSkippedBusy.increment();
                return;
            }

            mAudioRequests.increment();

            try
            {
                mExecutor.execute(() ->
                {
                    try
                    {
                        JsonNode event = OBJECT_MAPPER.readTree(data);
                        String audioPath = event.path("audio_url").asText("");
                        URI audioUri = mBaseUri.resolve(audioPath);

                        if(audioPath.isBlank() || !sameOrigin(mBaseUri, audioUri))
                        {
                            throw new IOException("Call audio URL is absent or outside the configured origin");
                        }

                        HttpRequest request = HttpRequest.newBuilder(audioUri).timeout(REQUEST_TIMEOUT)
                            .header("Accept", "audio/wav").GET().build();
                        HttpResponse<InputStream> response = mHttpClient.send(request,
                            HttpResponse.BodyHandlers.ofInputStream());

                        try(InputStream audio = response.body())
                        {
                            if(response.statusCode() != 200)
                            {
                                throw new IOException("Call audio returned HTTP " + response.statusCode());
                            }

                            byte[] header = audio.readNBytes(4);

                            if(header.length != 4 || header[0] != 'R' || header[1] != 'I' ||
                                header[2] != 'F' || header[3] != 'F')
                            {
                                throw new IOException("Call audio is not a RIFF WAVE response");
                            }

                            long received = 4;
                            byte[] buffer = new byte[32 * 1024];
                            int count;

                            while((count = audio.read(buffer)) >= 0)
                            {
                                received += count;

                                if(received > MAXIMUM_AUDIO_BYTES)
                                {
                                    throw new IOException("Call audio exceeds the probe response limit");
                                }
                            }

                            mAudioBytes.add(received);
                            mAudioSuccesses.increment();
                        }
                    }
                    catch(Exception exception)
                    {
                        mAudioFailures.increment();
                        fail("feed-" + mIdentifier + " audio-" + exception.getClass().getSimpleName());
                    }
                    finally
                    {
                        mAudioInFlight.set(false);
                    }
                });
            }
            catch(RuntimeException exception)
            {
                mAudioInFlight.set(false);
                mAudioFailures.increment();
                fail("feed-" + mIdentifier + " audio-executor-stopped");
            }
        }

        private void fail(String failure)
        {
            mReady.completeExceptionally(new IOException(failure));
            mFailureConsumer.accept(failure);
        }

        private CompletableFuture<Void> ready()
        {
            return mReady;
        }

        private FeedSnapshot snapshot()
        {
            return new FeedSnapshot(mCallEvents.sum(), mAudioRequests.sum(), mAudioSuccesses.sum(),
                mAudioFailures.sum(), mAudioSkippedBusy.sum(), mAudioBytes.sum());
        }

        private void stop()
        {
            if(mStopping.compareAndSet(false, true))
            {
                try
                {
                    mInputStream.close();
                }
                catch(IOException exception)
                {
                    // The live stream is already closed.
                }
            }
        }
    }

    record FeedSnapshot(long callEvents, long audioRequests, long audioSuccesses, long audioFailures,
                        long audioSkippedBusy, long audioBytes)
    {
        private static FeedSnapshot combine(List<FeedSnapshot> snapshots)
        {
            long callEvents = 0;
            long audioRequests = 0;
            long audioSuccesses = 0;
            long audioFailures = 0;
            long audioSkippedBusy = 0;
            long audioBytes = 0;

            for(FeedSnapshot snapshot: snapshots)
            {
                callEvents += snapshot.callEvents;
                audioRequests += snapshot.audioRequests;
                audioSuccesses += snapshot.audioSuccesses;
                audioFailures += snapshot.audioFailures;
                audioSkippedBusy += snapshot.audioSkippedBusy;
                audioBytes += snapshot.audioBytes;
            }

            return new FeedSnapshot(callEvents, audioRequests, audioSuccesses, audioFailures, audioSkippedBusy,
                audioBytes);
        }

        private FeedSnapshot subtract(FeedSnapshot earlier)
        {
            return new FeedSnapshot(callEvents - earlier.callEvents, audioRequests - earlier.audioRequests,
                audioSuccesses - earlier.audioSuccesses, audioFailures - earlier.audioFailures,
                audioSkippedBusy - earlier.audioSkippedBusy, audioBytes - earlier.audioBytes);
        }
    }

    record ServerSnapshot(long publishedFrames, long deliveredFrames, long failedSends, long sourceStarts,
                          long sourceStops, long tunerFrames, long tunerPublicationErrors, long maximumSendMicros,
                          long maximumDeliveryGapMicros, int threadPoolBusy, int threadPoolQueued)
    {
        private static ServerSnapshot from(JsonNode status)
        {
            JsonNode metrics = status.path("signal").path("metrics");
            JsonNode threadPool = status.path("server").path("threadPool");

            if(metrics.isMissingNode() || threadPool.isMissingNode())
            {
                throw new IllegalArgumentException("Status response is missing bounded signal metrics");
            }

            return new ServerSnapshot(metrics.path("publishedFrames").asLong(),
                metrics.path("deliveredFrames").asLong(), metrics.path("failedSends").asLong(),
                metrics.path("sourceStarts").asLong(), metrics.path("sourceStops").asLong(),
                metrics.path("tunerFrames").asLong(), metrics.path("tunerPublicationErrors").asLong(),
                metrics.path("maximumSendMicros").asLong(), metrics.path("maximumDeliveryGapMicros").asLong(),
                threadPool.path("busy").asInt(), threadPool.path("queued").asInt());
        }

        private ServerSnapshot subtract(ServerSnapshot earlier)
        {
            return new ServerSnapshot(publishedFrames - earlier.publishedFrames,
                deliveredFrames - earlier.deliveredFrames, failedSends - earlier.failedSends,
                sourceStarts - earlier.sourceStarts, sourceStops - earlier.sourceStops,
                tunerFrames - earlier.tunerFrames, tunerPublicationErrors - earlier.tunerPublicationErrors,
                maximumSendMicros, maximumDeliveryGapMicros, threadPoolBusy, threadPoolQueued);
        }
    }

    static final class SseEventReader
    {
        private final InputStream mInputStream;

        SseEventReader(InputStream inputStream)
        {
            mInputStream = Objects.requireNonNull(inputStream);
        }

        SseEvent next() throws IOException
        {
            String name = "message";
            StringBuilder data = new StringBuilder();

            while(true)
            {
                String line = readBoundedLine(mInputStream);

                if(line == null)
                {
                    return data.isEmpty() ? null : new SseEvent(name, data.toString());
                }

                if(line.isEmpty())
                {
                    if(!data.isEmpty() || !"message".equals(name))
                    {
                        return new SseEvent(name, data.toString());
                    }

                    continue;
                }

                if(line.charAt(0) == ':')
                {
                    continue;
                }

                int separator = line.indexOf(':');
                String field = separator >= 0 ? line.substring(0, separator) : line;
                String value = separator >= 0 ? line.substring(separator + 1) : "";

                if(value.startsWith(" "))
                {
                    value = value.substring(1);
                }

                if("event".equals(field))
                {
                    name = value;
                }
                else if("data".equals(field))
                {
                    if(!data.isEmpty())
                    {
                        data.append('\n');
                    }

                    data.append(value);
                }
            }
        }

        private static String readBoundedLine(InputStream inputStream) throws IOException
        {
            ByteArrayOutputStream line = new ByteArrayOutputStream();

            while(true)
            {
                int value = inputStream.read();

                if(value < 0)
                {
                    return line.size() == 0 ? null : line.toString(StandardCharsets.UTF_8);
                }

                if(value == '\n')
                {
                    byte[] bytes = line.toByteArray();
                    int length = bytes.length > 0 && bytes[bytes.length - 1] == '\r' ? bytes.length - 1 : bytes.length;
                    return new String(bytes, 0, length, StandardCharsets.UTF_8);
                }

                if(line.size() >= MAXIMUM_SSE_LINE_BYTES)
                {
                    throw new IOException("SSE line exceeds the probe limit");
                }

                line.write(value);
            }
        }
    }

    record SseEvent(String name, String data)
    {
    }

    record Result(int viewers, int feedClients, int maximumFramesPerSecond, long statusMillis, long elapsedNanos,
                  long totalFrames, long minimumViewerFrames, long maximumViewerFrames, long totalFrameBytes,
                  long sequenceSkips, long outOfOrderFrames, long targetChanges, int stalledViewers,
                  long maximumInterFrameNanos, FeedSnapshot feed, List<FeedSnapshot> feedByClient,
                  ServerSnapshot server, List<String> failures)
    {
        private static Result from(Configuration configuration, long statusMillis, long elapsedNanos,
                                   List<SignalSnapshot> initialSignal, List<SignalSnapshot> finalSignal,
                                   List<FeedSnapshot> initialFeed, List<FeedSnapshot> finalFeed,
                                   ServerSnapshot initialServer,
                                   ServerSnapshot finalServer, List<String> failures)
        {
            long totalFrames = 0;
            long minimumViewerFrames = Long.MAX_VALUE;
            long maximumViewerFrames = 0;
            long totalFrameBytes = 0;
            long sequenceSkips = 0;
            long outOfOrderFrames = 0;
            long targetChanges = 0;
            int stalledViewers = 0;
            long maximumInterFrameNanos = 0;

            for(int index = 0; index < finalSignal.size(); index++)
            {
                SignalSnapshot delta = finalSignal.get(index).subtract(initialSignal.get(index));
                totalFrames += delta.frames;
                minimumViewerFrames = Math.min(minimumViewerFrames, delta.frames);
                maximumViewerFrames = Math.max(maximumViewerFrames, delta.frames);
                totalFrameBytes += delta.bytes;
                sequenceSkips += delta.sequenceSkips;
                outOfOrderFrames += delta.outOfOrderFrames;
                targetChanges += delta.targetChanges;
                maximumInterFrameNanos = Math.max(maximumInterFrameNanos, delta.maximumInterFrameNanos);

                if(delta.maximumInterFrameNanos > TimeUnit.SECONDS.toNanos(5))
                {
                    stalledViewers++;
                }
            }

            List<FeedSnapshot> feedByClient = new ArrayList<>(finalFeed.size());

            for(int index = 0; index < finalFeed.size(); index++)
            {
                feedByClient.add(finalFeed.get(index).subtract(initialFeed.get(index)));
            }

            return new Result(configuration.viewerCount(), configuration.feedClientCount(),
                configuration.maximumFramesPerSecond(), statusMillis, elapsedNanos, totalFrames,
                minimumViewerFrames == Long.MAX_VALUE ? 0 : minimumViewerFrames, maximumViewerFrames,
                totalFrameBytes, sequenceSkips, outOfOrderFrames, targetChanges, stalledViewers,
                maximumInterFrameNanos, FeedSnapshot.combine(feedByClient), List.copyOf(feedByClient),
                finalServer.subtract(initialServer), failures);
        }

        boolean passed()
        {
            long minimumExpectedFrames = minimumExpectedFrames(elapsedNanos, maximumFramesPerSecond);
            boolean audioCovered = feed.callEvents() == 0 || feedClients == 0 ||
                coveredAudioClients() == feedClients;
            return failures.isEmpty() && minimumViewerFrames >= minimumExpectedFrames && outOfOrderFrames == 0 &&
                stalledViewers == 0 && server.failedSends() == 0 && server.tunerPublicationErrors() == 0 &&
                server.maximumDeliveryGapMicros() <= TimeUnit.SECONDS.toMicros(5) &&
                feed.audioFailures() == 0 && audioCovered;
        }

        void print()
        {
            double seconds = elapsedNanos / 1_000_000_000.0;
            double framesPerViewerSecond = totalFrames / Math.max(0.001, seconds * viewers);
            int coveredAudioClients = coveredAudioClients();
            int requiredAudioClients = feed.callEvents() == 0 ? 0 : feedClients;
            String audioCoverage = feed.callEvents() == 0 ? "no-calls-observed" :
                (coveredAudioClients == requiredAudioClients ? "covered" : "partial");
            String firstFailure = failures.isEmpty() ? "none" : failures.getFirst().replace(' ', '_');
            System.out.printf(Locale.US, "PROBE phase=RESULT passed=%s elapsedSeconds=%.3f viewers=%d " +
                    "feedClients=%d maxFps=%d frames=%d minViewerFrames=%d maxViewerFrames=%d " +
                    "framesPerViewerSecond=%.3f frameBytes=%d sequenceSkips=%d outOfOrder=%d targetChanges=%d " +
                    "stalledViewers=%d maxInterFrameMillis=%.3f serverPublishedFrames=%d " +
                    "serverDeliveredFrames=%d serverFailedSends=%d sourceStarts=%d sourceStops=%d tunerFrames=%d " +
                    "tunerPublicationErrors=%d maxServerSendMillis=%.3f maxServerDeliveryGapMillis=%.3f " +
                    "jettyBusy=%d jettyQueued=%d callEvents=%d audioRequests=%d " +
                    "audioSuccesses=%d audioFailures=%d " +
                    "audioSkippedBusy=%d audioBytes=%d audioCoverage=%s audioCoveredClients=%d " +
                    "audioRequiredClients=%d audioPerClient=%s statusMillis=%d failures=%d firstFailure=%s%n",
                passed(), seconds, viewers, feedClients, maximumFramesPerSecond, totalFrames, minimumViewerFrames,
                maximumViewerFrames, framesPerViewerSecond, totalFrameBytes, sequenceSkips, outOfOrderFrames,
                targetChanges, stalledViewers, maximumInterFrameNanos / 1_000_000.0, server.publishedFrames(),
                server.deliveredFrames(), server.failedSends(), server.sourceStarts(), server.sourceStops(),
                server.tunerFrames(), server.tunerPublicationErrors(), server.maximumSendMicros() / 1_000.0,
                server.maximumDeliveryGapMicros() / 1_000.0, server.threadPoolBusy(), server.threadPoolQueued(),
                feed.callEvents(), feed.audioRequests(), feed.audioSuccesses(),
                feed.audioFailures(), feed.audioSkippedBusy(), feed.audioBytes(), audioCoverage, coveredAudioClients,
                requiredAudioClients, audioPerClient(), statusMillis, failures.size(), firstFailure);
        }

        private static long minimumExpectedFrames(long elapsedNanos, int maximumFramesPerSecond)
        {
            double seconds = elapsedNanos / 1_000_000_000.0;
            return Math.max(1,
                (long)Math.floor(seconds * maximumFramesPerSecond * MINIMUM_FRAME_RATE_RATIO));
        }

        private int coveredAudioClients()
        {
            return (int)feedByClient.stream().filter(snapshot -> snapshot.audioSuccesses() > 0).count();
        }

        private String audioPerClient()
        {
            if(feedByClient.isEmpty())
            {
                return "none";
            }

            StringBuilder coverage = new StringBuilder();

            for(int index = 0; index < feedByClient.size(); index++)
            {
                if(index > 0)
                {
                    coverage.append(';');
                }

                FeedSnapshot snapshot = feedByClient.get(index);
                coverage.append(index + 1).append("[events:").append(snapshot.callEvents())
                    .append(",successes:").append(snapshot.audioSuccesses())
                    .append(",failures:").append(snapshot.audioFailures()).append(']');
            }

            return coverage.toString();
        }
    }

    private static boolean sameOrigin(URI first, URI second)
    {
        return first.getScheme().equalsIgnoreCase(second.getScheme()) &&
            first.getHost().equalsIgnoreCase(second.getHost()) && effectivePort(first) == effectivePort(second) &&
            second.getUserInfo() == null;
    }

    private static int effectivePort(URI uri)
    {
        if(uri.getPort() >= 0)
        {
            return uri.getPort();
        }

        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }
}
