package io.github.dsheirer.alerts.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/** Fixed RadioResolve gateway. Credentials and subscription URLs never appear in errors or logs. */
public final class RadioResolvePushClient implements AutoCloseable
{
    public static final URI API = URI.create("https://radioresolve.com/api/vce-push/v1/");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAXIMUM_RESPONSE_BYTES = 1_048_576;
    private final URI mApi;
    private final HttpClient mHttp;

    public RadioResolvePushClient()
    {
        this(API, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    RadioResolvePushClient(URI api, HttpClient http)
    {
        mApi = api; mHttp = http;
    }

    public JsonNode register(String installationId, String senderName, String credential)
        throws IOException, InterruptedException
    {
        return request("POST", "senders", null, Map.of("installation_id", installationId,
            "sender_name", senderName, "credential", credential));
    }

    public JsonNode status(String credential) throws IOException, InterruptedException
    {
        return request("GET", "senders/me", credential, null);
    }

    public void deregister(String credential) throws IOException, InterruptedException
    {
        request("DELETE", "senders/me", credential, null);
    }

    public JsonNode createEnrollment(String credential) throws IOException, InterruptedException
    {
        return request("POST", "enrollments", credential, Map.of());
    }

    public JsonNode enrollment(String credential, String id) throws IOException, InterruptedException
    {
        return request("GET", "enrollments/" + safeId(id), credential, null);
    }

    public void disconnect(String credential, String deviceId) throws IOException, InterruptedException
    {
        request("DELETE", "devices/" + safeId(deviceId), credential, null);
    }

    public JsonNode alert(String credential, Map<String,Object> alert) throws IOException, InterruptedException
    {
        return request("POST", "alerts", credential, alert);
    }

    public JsonNode updates(String credential, String cursor) throws IOException, InterruptedException
    {
        if(cursor == null || !cursor.matches("[0-9]{1,20}")) cursor = "0";
        return request("GET", "updates?after=" + cursor, credential, null);
    }

    public JsonNode testPublicAccess(String url, String token) throws IOException, InterruptedException
    {
        return request("POST", "public-test", null, Map.of("url", url, "token", token));
    }

    private JsonNode request(String method, String path, String credential, Object body)
        throws IOException, InterruptedException
    {
        HttpRequest.Builder builder = HttpRequest.newBuilder(mApi.resolve(path))
            .timeout(Duration.ofSeconds(12)).header("Accept", "application/json");
        if(credential != null)
        {
            if(!credential.matches("[A-Za-z0-9_-]{43}")) throw new IOException("Invalid gateway credential");
            builder.header("Authorization", "Bearer " + credential);
        }
        if(body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() :
            HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body)));
        var pending = mHttp.sendAsync(builder.build(), info -> new BoundedBody());
        HttpResponse<byte[]> response;
        try { response = pending.get(13, TimeUnit.SECONDS); }
        catch(java.util.concurrent.ExecutionException exception) { throw new IOException("RadioResolve could not be reached"); }
        catch(java.util.concurrent.TimeoutException exception) { pending.cancel(true); throw new IOException("RadioResolve timed out"); }
        catch(InterruptedException exception) { pending.cancel(true); throw exception; }
        byte[] bytes = response.body();
        if(response.statusCode() < 200 || response.statusCode() >= 300)
            throw new GatewayException(response.statusCode());
        if(bytes.length == 0) return JSON.createObjectNode();
        try { return JSON.readTree(bytes); }
        catch(IOException ignored) { throw new IOException("RadioResolve returned an invalid response"); }
    }

    private static String safeId(String id)
    {
        if(id == null || !id.matches("[A-Za-z0-9_-]{1,100}")) throw new IllegalArgumentException("Invalid device identifier");
        return id;
    }

    public static final class GatewayException extends IOException
    {
        private final int mStatus;
        GatewayException(int status) { super("RadioResolve request failed (" + status + ")"); mStatus = status; }
        public int status() { return mStatus; }
    }

    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]>
    {
        private final CompletableFuture<byte[]> mBody = new CompletableFuture<>();
        private final ByteArrayOutputStream mBytes = new ByteArrayOutputStream();
        private Flow.Subscription mSubscription;
        @Override public CompletionStage<byte[]> getBody() { return mBody; }
        @Override public void onSubscribe(Flow.Subscription subscription) { mSubscription = subscription; subscription.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers)
        {
            for(ByteBuffer buffer: buffers)
            {
                if(buffer.remaining() > MAXIMUM_RESPONSE_BYTES - mBytes.size())
                {
                    mSubscription.cancel(); mBody.completeExceptionally(new IOException("Gateway response too large")); return;
                }
                byte[] bytes = new byte[buffer.remaining()]; buffer.get(bytes); mBytes.writeBytes(bytes);
            }
            mSubscription.request(1);
        }
        @Override public void onError(Throwable error) { mBody.completeExceptionally(new IOException("Gateway response failed")); }
        @Override public void onComplete() { mBody.complete(mBytes.toByteArray()); }
    }

    @Override public void close() { mHttp.shutdownNow(); }
}
