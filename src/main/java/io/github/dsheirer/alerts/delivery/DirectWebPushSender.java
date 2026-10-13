package io.github.dsheirer.alerts.delivery;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.Locale;

/** Bounded provider-only HTTPS Web Push transport; invoked exclusively by the delivery worker. */
public final class DirectWebPushSender implements AutoCloseable
{
    private final HttpClient mHttp = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER).build();

    public int send(String endpoint, String p256dh, String auth, WebPushCodec.Keys keys, String subject,
                    byte[] payload, long ttlSeconds) throws IOException, InterruptedException, GeneralSecurityException
    {
        URI destination = validateEndpoint(endpoint);
        byte[] encrypted = WebPushCodec.encrypt(payload, p256dh, auth);
        String audience = "https://" + destination.getRawAuthority();
        HttpRequest request = HttpRequest.newBuilder(destination).timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/octet-stream").header("Content-Encoding", "aes128gcm")
            .header("TTL", Long.toString(Math.max(0, Math.min(86400, ttlSeconds))))
            .header("Urgency", "normal")
            .header("Authorization", WebPushCodec.authorization(keys, audience, subject,
                System.currentTimeMillis() / 1000 + 12 * 3600))
            .POST(HttpRequest.BodyPublishers.ofByteArray(encrypted)).build();
        return mHttp.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    public static URI validateEndpoint(String endpoint)
    {
        URI uri;
        try { uri = URI.create(endpoint); }
        catch(RuntimeException ignored) { throw new IllegalArgumentException("Invalid push subscription"); }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean provider = host.equals("fcm.googleapis.com") || host.equals("updates.push.services.mozilla.com") ||
            host.endsWith(".push.services.mozilla.com") || host.equals("web.push.apple.com") ||
            host.endsWith(".push.apple.com") || host.endsWith(".notify.windows.com");
        if(endpoint.length() > 2048 || !"https".equals(uri.getScheme()) || !provider ||
            uri.getUserInfo() != null || uri.getFragment() != null || uri.getPort() != -1 && uri.getPort() != 443 ||
            uri.getRawPath() == null || uri.getRawPath().isEmpty())
        {
            throw new IllegalArgumentException("Choose a supported browser push subscription");
        }
        return uri;
    }

    @Override public void close() { mHttp.shutdownNow(); }
}
