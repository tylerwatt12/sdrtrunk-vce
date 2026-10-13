package io.github.dsheirer.alerts.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Short-lived server-owned proofs bind Direct saves to the exact externally checked HTTPS origin. */
public final class PublicAccessVerifier
{
    private final RadioResolvePushClient mGateway;
    private final Map<String,Challenge> mChallenges = new ConcurrentHashMap<>();
    private final Map<String,Proof> mProofs = new ConcurrentHashMap<>();

    public PublicAccessVerifier(RadioResolvePushClient gateway) { mGateway = gateway; }

    public Map<String,Object> test(String requestedUrl) throws IOException, InterruptedException
    {
        String url = normalizeUrl(requestedUrl);
        long now = System.currentTimeMillis();
        mChallenges.values().removeIf(value -> value.expiresAtMs() < now);
        mProofs.values().removeIf(value -> value.expiresAtMs() < now);
        if(mChallenges.size() >= 16 || mProofs.size() >= 128) throw new IOException("Wait before testing public access again");
        String challenge = token();
        mChallenges.put(challenge, new Challenge(now + 60_000));
        try
        {
            JsonNode result = mGateway.testPublicAccess(url, challenge);
            boolean passed = result.path("public_access").asBoolean(false) &&
                url.equals(result.path("checked_url").asText());
            String proof = passed ? token() : "";
            if(passed) mProofs.put(proof, new Proof(url, now + 15 * 60_000));
            return Map.of("public_access", Map.of("status", passed ? "passed" : "failed",
                "checked_url", url, "message", passed ? "Public HTTPS access passed." :
                    "RadioResolve could not reach this VCE site. Check the URL, certificate, and public access, then test again."),
                "test_token", proof);
        }
        finally { mChallenges.remove(challenge); }
    }

    public boolean hasChallenge(String token)
    {
        Challenge challenge = mChallenges.get(token);
        return challenge != null && challenge.expiresAtMs() >= System.currentTimeMillis();
    }

    public boolean passed(String requestedUrl, String token)
    {
        Proof proof = token != null ? mProofs.get(token) : null;
        return proof != null && proof.expiresAtMs() >= System.currentTimeMillis() &&
            normalizeUrl(requestedUrl).equals(proof.url());
    }

    public Map<String,Object> status(String requestedUrl)
    {
        long now = System.currentTimeMillis();
        boolean passed = mProofs.values().stream().anyMatch(value -> value.expiresAtMs() >= now && value.url().equals(requestedUrl));
        return Map.of("status", passed ? "passed" : "unchecked", "checked_url", requestedUrl,
            "message", passed ? "Public HTTPS access passed." : "Test public access before saving Direct delivery.");
    }

    public static String normalizeUrl(String value)
    {
        URI uri;
        try { uri = URI.create(value == null ? "" : value.strip()); }
        catch(RuntimeException ignored) { throw new IllegalArgumentException("Enter the public HTTPS address of this VCE site"); }
        String host = uri.getHost();
        if(!"https".equalsIgnoreCase(uri.getScheme()) || host == null || !host.matches("[A-Za-z0-9.-]+") ||
            !host.contains(".") || host.matches("[0-9.]+") || uri.getUserInfo() != null ||
            uri.getRawQuery() != null || uri.getRawFragment() != null ||
            !(uri.getRawPath().isEmpty() || uri.getRawPath().equals("/")) ||
            uri.getPort() == 0 || uri.getPort() > 65535)
            throw new IllegalArgumentException("Enter a public HTTPS hostname without a path or sign-in details");
        return "https://" + host.toLowerCase(Locale.ROOT) +
            (uri.getPort() == -1 || uri.getPort() == 443 ? "" : ":" + uri.getPort());
    }

    public static String token()
    {
        byte[] value = new byte[32]; new SecureRandom().nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private record Challenge(long expiresAtMs) { }
    private record Proof(String url, long expiresAtMs) { }
}
