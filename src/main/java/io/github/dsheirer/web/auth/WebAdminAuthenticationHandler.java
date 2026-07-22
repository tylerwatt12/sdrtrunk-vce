/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.auth;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.util.JsonRecyclerPools;
import io.github.dsheirer.web.access.AuthorizationSubject;
import io.github.dsheirer.web.access.RemoteAddressAdmissionPolicy;
import io.github.dsheirer.web.access.WebRequestSubjectResolver;
import io.github.dsheirer.web.signal.SignalSubjectResolver;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.websocket.server.ServerUpgradeRequest;

/**
 * HTTP transport and shared request-subject resolver for the one web administrator account.
 *
 * <p>The three authentication routes are intentionally small and exact.  Password verification is handed to
 * {@link SingleAdminAuthenticationService}'s bounded worker without blocking a Jetty thread.  Browser sessions are
 * opaque, transient server-side records; the cookie contains only the random session identifier.</p>
 */
public final class WebAdminAuthenticationHandler extends Handler.Wrapper
    implements WebRequestSubjectResolver, SignalSubjectResolver
{
    public static final String SESSION_PATH = "/api/v1/auth/session";
    public static final String LOGIN_PATH = "/api/v1/auth/login";
    public static final String LOGOUT_PATH = "/api/v1/auth/logout";
    public static final String SESSION_COOKIE_NAME = "sdrtrunk_admin_session";
    public static final String CSRF_HEADER_NAME = "X-CSRF-Token";
    private static final String JSON_CONTENT_TYPE = "application/json; charset=utf-8";
    private static final byte[] INVALID_REQUEST = json("{\"error\":\"invalid_request\"}");
    private static final byte[] REQUEST_REJECTED = json("{\"error\":\"request_rejected\"}");
    private static final byte[] INVALID_CREDENTIALS = json("{\"error\":\"invalid_credentials\"}");
    private static final byte[] LOGIN_UNAVAILABLE = json("{\"error\":\"login_unavailable\"}");
    private static final byte[] AUTHENTICATION_REQUIRED = json("{\"error\":\"authentication_required\"}");
    private static final byte[] SECURE_TRANSPORT_REQUIRED = json("{\"error\":\"secure_transport_required\"}");
    private static final byte[] LOGGED_OUT = json("{\"authenticated\":false}");
    private static final JsonFactory JSON_FACTORY = JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .recyclerPool(JsonRecyclerPools.nonRecyclingPool())
        .streamReadConstraints(StreamReadConstraints.builder()
            .maxDocumentLength(Configuration.MAXIMUM_ALLOWED_LOGIN_BODY_BYTES)
            .maxNestingDepth(3)
            .maxNameLength(32)
            .maxStringLength(Pbkdf2PasswordHasher.MAXIMUM_PASSWORD_CHARACTERS)
            .maxTokenCount(8)
            .build())
        .build();

    private final WebAdminAuthenticationOperations mAuthenticationService;
    private final Configuration mConfiguration;
    private final RemoteAddressAdmissionPolicy mRemoteAddressAdmissionPolicy;

    public WebAdminAuthenticationHandler(SingleAdminAuthenticationService authenticationService, Handler next)
    {
        this(authenticationService, next, Configuration.defaults(), RemoteAddressAdmissionPolicy.allowAll());
    }

    public WebAdminAuthenticationHandler(SingleAdminAuthenticationService authenticationService, Handler next,
                                         Configuration configuration)
    {
        this(authenticationService, next, configuration, RemoteAddressAdmissionPolicy.allowAll());
    }

    public WebAdminAuthenticationHandler(SingleAdminAuthenticationService authenticationService, Handler next,
                                         RemoteAddressAdmissionPolicy remoteAddressAdmissionPolicy)
    {
        this(authenticationService, next, Configuration.defaults(), remoteAddressAdmissionPolicy);
    }

    public WebAdminAuthenticationHandler(SingleAdminAuthenticationService authenticationService, Handler next,
                                         Configuration configuration,
                                         RemoteAddressAdmissionPolicy remoteAddressAdmissionPolicy)
    {
        this((WebAdminAuthenticationOperations)authenticationService, next, configuration,
            remoteAddressAdmissionPolicy);
    }

    WebAdminAuthenticationHandler(WebAdminAuthenticationOperations authenticationService, Handler next,
                                  Configuration configuration,
                                  RemoteAddressAdmissionPolicy remoteAddressAdmissionPolicy)
    {
        super(Objects.requireNonNull(next, "Next web handler cannot be null"));
        mAuthenticationService = Objects.requireNonNull(authenticationService,
            "Web administrator authentication service cannot be null");
        mConfiguration = Objects.requireNonNull(configuration,
            "Web administrator HTTP configuration cannot be null");
        mRemoteAddressAdmissionPolicy = Objects.requireNonNull(remoteAddressAdmissionPolicy,
            "Remote address admission policy cannot be null");
    }

    /**
     * Convenience view for constructor injection where the overloaded resolver methods would otherwise be ambiguous.
     */
    public WebRequestSubjectResolver webRequestSubjectResolver()
    {
        return this;
    }

    /**
     * Convenience view for the signal WebSocket transport.
     */
    public SignalSubjectResolver signalSubjectResolver()
    {
        return this;
    }

    /**
     * Authorizes an administrator-only request that changes application state.
     *
     * <p>The request must come from an admitted peer and the same browser origin, contain exactly one live
     * administrator session cookie, and contain exactly one valid CSRF header.  The returned authorization does not
     * expose the session identifier or CSRF token.  A caller that performs asynchronous work should recheck
     * {@link MutationAuthorization#isSessionValid()} immediately before applying the change.</p>
     */
    public MutationAuthorization authorizeMutation(Request request)
    {
        Objects.requireNonNull(request, "Mutation request cannot be null");

        try
        {
            if(!mRemoteAddressAdmissionPolicy.isAllowed(request) || !hasSameOrigin(request))
            {
                return MutationAuthorization.rejected();
            }

            CookieLookup cookie = sessionCookie(request);
            String sessionId = cookie.sessionId();

            if(sessionId == null || mAuthenticationService.resolveSession(sessionId).isEmpty())
            {
                return MutationAuthorization.rejected();
            }

            List<String> csrfHeaders = request.getHeaders().getValuesList(CSRF_HEADER_NAME);

            if(csrfHeaders.size() != 1 || !mAuthenticationService.validateCsrf(sessionId, csrfHeaders.getFirst()))
            {
                return MutationAuthorization.rejected();
            }

            return MutationAuthorization.authorized(
                () -> mAuthenticationService.resolveSession(sessionId).isPresent());
        }
        catch(RuntimeException exception)
        {
            return MutationAuthorization.rejected();
        }
    }

    @Override
    public AuthorizationSubject resolve(Request request)
    {
        return resolveSession(request).isPresent() ? AuthorizationSubject.AUTHENTICATED_ADMIN :
            AuthorizationSubject.ANONYMOUS;
    }

    @Override
    public WebAuthorization resolveAuthorization(Request request)
    {
        CookieLookup cookie = sessionCookie(request);
        String sessionId = cookie.sessionId();

        if(sessionId == null || mAuthenticationService.resolveSession(sessionId).isEmpty())
        {
            return WebAuthorization.permanent(AuthorizationSubject.ANONYMOUS);
        }

        return new WebAuthorization(AuthorizationSubject.AUTHENTICATED_ADMIN,
            () -> mAuthenticationService.resolveSession(sessionId).isPresent());
    }

    @Override
    public AuthorizationSubject resolve(ServerUpgradeRequest request)
    {
        return resolve((Request)request);
    }

    @Override
    public SignalAuthorization resolveAuthorization(ServerUpgradeRequest request)
    {
        CookieLookup cookie = sessionCookie((Request)request);
        String sessionId = cookie.sessionId();

        if(sessionId == null || mAuthenticationService.resolveSession(sessionId).isEmpty())
        {
            return SignalAuthorization.permanent(AuthorizationSubject.ANONYMOUS);
        }

        return new SignalAuthorization(AuthorizationSubject.AUTHENTICATED_ADMIN,
            () -> mAuthenticationService.resolveSession(sessionId).isPresent());
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception
    {
        String path = Request.getPathInContext(request);

        if(!SESSION_PATH.equals(path) && !LOGIN_PATH.equals(path) && !LOGOUT_PATH.equals(path))
        {
            return super.handle(request, response, callback);
        }

        if(!mRemoteAddressAdmissionPolicy.isAllowed(request))
        {
            prepareSecurityHeaders(request, response);
            response.getHeaders().put(HttpHeader.CONNECTION, "close");
            respondJson(response, callback, 403, REQUEST_REJECTED);
            return true;
        }

        prepareSecurityHeaders(request, response);

        if(SESSION_PATH.equals(path))
        {
            return handleSession(request, response, callback);
        }

        return LOGIN_PATH.equals(path) ? handleLogin(request, response, callback) :
            handleLogout(request, response, callback);
    }

    private boolean handleSession(Request request, Response response, Callback callback)
    {
        if(!"GET".equals(request.getMethod()))
        {
            methodNotAllowed(response, callback, "GET");
            return true;
        }

        boolean bodyFramed = request.getLength() > 0 ||
            request.getHeaders().get(HttpHeader.TRANSFER_ENCODING) != null;

        if(!isSafeGetOrigin(request) || bodyFramed)
        {
            if(bodyFramed)
            {
                response.getHeaders().put(HttpHeader.CONNECTION, "close");
            }

            respondJson(response, callback, 400, REQUEST_REJECTED);
            return true;
        }

        CookieLookup cookie = sessionCookie(request);
        Optional<WebAdminSession> session = resolveSession(cookie);

        if(session.isPresent())
        {
            respondJson(response, callback, 200, sessionJson(session.get()));
        }
        else
        {
            if(cookie.present())
            {
                expireSessionCookie(request, response);
            }

            respondJson(response, callback, 200, unauthenticatedSessionJson());
        }

        return true;
    }

    private boolean handleLogin(Request request, Response response, Callback callback)
    {
        if(!"POST".equals(request.getMethod()))
        {
            methodNotAllowed(response, callback, "POST");
            return true;
        }

        if(!hasSameOrigin(request))
        {
            respondJson(response, callback, 403, REQUEST_REJECTED);
            return true;
        }

        if(!request.isSecure() && !isLoopback(request.getConnectionMetaData().getRemoteSocketAddress()))
        {
            respondJson(response, callback, 403, SECURE_TRANSPORT_REQUIRED);
            return true;
        }

        if(!isJson(request.getHeaders().get(HttpHeader.CONTENT_TYPE)))
        {
            respondJson(response, callback, 415, INVALID_REQUEST);
            return true;
        }

        long contentLength = request.getLength();

        if(contentLength > mConfiguration.maximumLoginBodyBytes())
        {
            response.getHeaders().put(HttpHeader.CONNECTION, "close");
            respondJson(response, callback, 413, INVALID_REQUEST);
            return true;
        }

        Promise<RetainableByteBuffer> bodyCompletion = Promise.from(
            body -> completeLoginBody(request, response, callback, body.takeByteArray(), null),
            failure -> completeLoginBody(request, response, callback, null, failure));
        Content.Source.asRetainableByteBuffer(request, null, false, mConfiguration.maximumLoginBodyBytes(),
            bodyCompletion);
        return true;
    }

    private void completeLoginBody(Request request, Response response, Callback callback, byte[] body,
                                   Throwable failure)
    {
        if(failure != null)
        {
            response.getHeaders().put(HttpHeader.CONNECTION, "close");
            int status = failure instanceof IllegalStateException ? 413 : 400;
            respondJson(response, callback, status, INVALID_REQUEST);
            return;
        }

        LoginPayload payload;

        try
        {
            payload = parseLogin(body);
        }
        catch(IOException | IllegalArgumentException exception)
        {
            Arrays.fill(body, (byte)0);
            respondJson(response, callback, 400, INVALID_REQUEST);
            return;
        }

        CompletableFuture<SingleAdminAuthenticationService.LoginResult> completion;

        try(payload)
        {
            completion = mAuthenticationService.login(payload.username(), payload.password(), sourceKey(request));
        }
        catch(RuntimeException exception)
        {
            Arrays.fill(body, (byte)0);
            respondJson(response, callback, 503, LOGIN_UNAVAILABLE);
            return;
        }

        Arrays.fill(body, (byte)0);
        CookieLookup priorCookie = sessionCookie(request);
        completion.whenComplete((result, authenticationFailure) ->
            completeLogin(request, response, callback, priorCookie, result, authenticationFailure));
    }

    private void completeLogin(Request request, Response response, Callback callback, CookieLookup priorCookie,
                               SingleAdminAuthenticationService.LoginResult result, Throwable failure)
    {
        if(failure != null || result == null)
        {
            respondJson(response, callback, 503, LOGIN_UNAVAILABLE);
            return;
        }

        switch(result.status())
        {
            case SUCCESS ->
            {
                WebAdminSession session = result.session().orElseThrow();

                if(priorCookie.sessionId() != null && !priorCookie.sessionId().equals(session.sessionId()))
                {
                    mAuthenticationService.logout(priorCookie.sessionId());
                }

                Response.putCookie(response, sessionCookie(session.sessionId(), request.isSecure()));
                respondJson(response, callback, 200, sessionJson(session));
            }
            case DENIED -> respondJson(response, callback, 401, INVALID_CREDENTIALS);
            case THROTTLED ->
            {
                response.getHeaders().put(HttpHeader.RETRY_AFTER, retryAfterSeconds(result.retryAfterMillis()));
                respondJson(response, callback, 429, LOGIN_UNAVAILABLE);
            }
            case BUSY, SESSION_CAPACITY ->
            {
                response.getHeaders().put(HttpHeader.RETRY_AFTER, "1");
                respondJson(response, callback, 503, LOGIN_UNAVAILABLE);
            }
        }
    }

    private boolean handleLogout(Request request, Response response, Callback callback)
    {
        if(!"POST".equals(request.getMethod()))
        {
            methodNotAllowed(response, callback, "POST");
            return true;
        }

        if(!hasSameOrigin(request))
        {
            respondJson(response, callback, 403, REQUEST_REJECTED);
            return true;
        }

        long contentLength = request.getLength();

        if(contentLength != 0)
        {
            response.getHeaders().put(HttpHeader.CONNECTION, "close");
            respondJson(response, callback, contentLength > 0 ? 413 : 400, INVALID_REQUEST);
            return true;
        }

        CookieLookup cookie = sessionCookie(request);

        if(resolveSession(cookie).isEmpty())
        {
            expireSessionCookie(request, response);
            respondJson(response, callback, 401, AUTHENTICATION_REQUIRED);
            return true;
        }

        List<String> csrfHeaders = request.getHeaders().getValuesList(CSRF_HEADER_NAME);

        if(csrfHeaders.size() != 1 ||
            !mAuthenticationService.validateCsrf(cookie.sessionId(), csrfHeaders.getFirst()))
        {
            respondJson(response, callback, 403, REQUEST_REJECTED);
            return true;
        }

        mAuthenticationService.logout(cookie.sessionId());
        expireSessionCookie(request, response);
        respondJson(response, callback, 200, LOGGED_OUT);
        return true;
    }

    private Optional<WebAdminSession> resolveSession(Request request)
    {
        return resolveSession(sessionCookie(request));
    }

    private Optional<WebAdminSession> resolveSession(CookieLookup cookie)
    {
        return cookie.sessionId() == null ? Optional.empty() :
            mAuthenticationService.resolveSession(cookie.sessionId());
    }

    private static CookieLookup sessionCookie(Request request)
    {
        try
        {
            List<HttpCookie> cookies = Request.getCookies(request);
            String value = null;
            boolean present = false;

            for(HttpCookie cookie: cookies)
            {
                if(SESSION_COOKIE_NAME.equals(cookie.getName()))
                {
                    if(present)
                    {
                        return new CookieLookup(true, null);
                    }

                    present = true;
                    value = cookie.getValue();
                }
            }

            return new CookieLookup(present, value == null || value.isBlank() ? null : value);
        }
        catch(RuntimeException exception)
        {
            return new CookieLookup(true, null);
        }
    }

    static HttpCookie sessionCookie(String sessionId, boolean secure)
    {
        return HttpCookie.build(SESSION_COOKIE_NAME, Objects.requireNonNull(sessionId,
                "Session identifier cannot be null"))
            .path("/")
            .httpOnly(true)
            .secure(secure)
            .sameSite(HttpCookie.SameSite.STRICT)
            .build();
    }

    private static void expireSessionCookie(Request request, Response response)
    {
        HttpCookie expired = HttpCookie.build(SESSION_COOKIE_NAME, "")
            .path("/")
            .httpOnly(true)
            .secure(request.isSecure())
            .sameSite(HttpCookie.SameSite.STRICT)
            .maxAge(0)
            .expires(Instant.EPOCH)
            .build();
        Response.putCookie(response, expired);
    }

    private byte[] unauthenticatedSessionJson()
    {
        return mAuthenticationService.isConfigured() ?
            json("{\"configured\":true,\"authenticated\":false}") :
            json("{\"configured\":false,\"authenticated\":false}");
    }

    private byte[] sessionJson(WebAdminSession session)
    {
        String username = mAuthenticationService.getCredentialMetadata()
            .map(SingleAdminAuthenticationService.CredentialMetadata::username)
            .orElse("admin");
        return json("{\"configured\":true,\"authenticated\":true,\"username\":\"" + username +
            "\",\"csrfToken\":\"" + session.csrfToken() + "\",\"expiresAtEpochMillis\":" +
            session.expiresAtEpochMillis() + "}");
    }

    private static LoginPayload parseLogin(byte[] body) throws IOException
    {
        if(body == null || body.length == 0)
        {
            throw new IllegalArgumentException("Login document is empty");
        }

        String username = null;
        char[] password = null;

        try(JsonParser parser = JSON_FACTORY.createParser(body))
        {
            if(parser.nextToken() != JsonToken.START_OBJECT)
            {
                throw new IllegalArgumentException("Login document is not an object");
            }

            while(parser.nextToken() != JsonToken.END_OBJECT)
            {
                if(parser.currentToken() != JsonToken.FIELD_NAME)
                {
                    throw new IllegalArgumentException("Invalid login field");
                }

                String fieldName = parser.currentName();

                if(parser.nextToken() != JsonToken.VALUE_STRING)
                {
                    throw new IllegalArgumentException("Login fields must be strings");
                }

                int length = parser.getTextLength();

                if("username".equals(fieldName))
                {
                    if(username != null || length < 1 || length > WebAdminCredential.MAXIMUM_USERNAME_CHARACTERS * 4)
                    {
                        throw new IllegalArgumentException("Invalid login username");
                    }

                    username = parser.getText();
                }
                else if("password".equals(fieldName))
                {
                    if(password != null || length < 1 || length > Pbkdf2PasswordHasher.MAXIMUM_PASSWORD_CHARACTERS)
                    {
                        throw new IllegalArgumentException("Invalid login password");
                    }

                    password = copyText(parser);
                }
                else
                {
                    throw new IllegalArgumentException("Unknown login field");
                }
            }

            if(parser.nextToken() != null || username == null || password == null)
            {
                throw new IllegalArgumentException("Incomplete login document");
            }

            return new LoginPayload(username, password);
        }
        catch(IOException | RuntimeException exception)
        {
            if(password != null)
            {
                Arrays.fill(password, '\u0000');
            }

            throw exception;
        }
    }

    private static char[] copyText(JsonParser parser) throws IOException
    {
        char[] source = parser.getTextCharacters();
        return Arrays.copyOfRange(source, parser.getTextOffset(), parser.getTextOffset() + parser.getTextLength());
    }

    private static boolean isJson(String contentType)
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

    private static boolean hasSameOrigin(Request request)
    {
        List<String> values = request.getHeaders().getValuesList(HttpHeader.ORIGIN);
        return values.size() == 1 && matchesRequestOrigin(values.getFirst(), request);
    }

    private static boolean isSafeGetOrigin(Request request)
    {
        List<String> values = request.getHeaders().getValuesList(HttpHeader.ORIGIN);
        return values.isEmpty() || values.size() == 1 && matchesRequestOrigin(values.getFirst(), request);
    }

    private static boolean matchesRequestOrigin(String candidate, Request request)
    {
        try
        {
            Origin browser = normalizeOrigin(URI.create(candidate));
            Origin requested = normalizeOrigin(request.isSecure() ? "https" : "http", Request.getServerName(request),
                Request.getServerPort(request));
            return browser != null && browser.equals(requested);
        }
        catch(IllegalArgumentException exception)
        {
            return false;
        }
    }

    private static Origin normalizeOrigin(URI uri)
    {
        if(uri == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
        {
            return null;
        }

        String path = uri.getPath();

        if(path != null && !path.isEmpty() && !"/".equals(path))
        {
            return null;
        }

        return normalizeOrigin(uri.getScheme(), uri.getHost(), uri.getPort());
    }

    private static Origin normalizeOrigin(String scheme, String host, int port)
    {
        if(scheme == null || host == null || host.isBlank())
        {
            return null;
        }

        String normalizedScheme = scheme.toLowerCase(Locale.ROOT);

        if(!"http".equals(normalizedScheme) && !"https".equals(normalizedScheme))
        {
            return null;
        }

        int effectivePort = port < 0 ? ("https".equals(normalizedScheme) ? 443 : 80) : port;
        return effectivePort < 1 || effectivePort > 65_535 ? null :
            new Origin(normalizedScheme, host.toLowerCase(Locale.ROOT), effectivePort);
    }

    static boolean isLoopback(SocketAddress socketAddress)
    {
        if(!(socketAddress instanceof InetSocketAddress inetSocketAddress))
        {
            return false;
        }

        InetAddress address = inetSocketAddress.getAddress();
        return address != null && address.isLoopbackAddress();
    }

    private static String sourceKey(Request request)
    {
        SocketAddress socketAddress = request.getConnectionMetaData().getRemoteSocketAddress();

        if(socketAddress instanceof InetSocketAddress inetSocketAddress && inetSocketAddress.getAddress() != null)
        {
            return inetSocketAddress.getAddress().getHostAddress();
        }

        return "unknown";
    }

    private static String retryAfterSeconds(long milliseconds)
    {
        long seconds = Math.max(1, Math.addExact(Math.min(milliseconds, Long.MAX_VALUE - 999), 999) / 1_000);
        return Long.toString(Math.min(seconds, 86_400));
    }

    private static void prepareSecurityHeaders(Request request, Response response)
    {
        response.getHeaders().put(HttpHeader.CACHE_CONTROL, "no-store, max-age=0");
        response.getHeaders().put("Pragma", "no-cache");
        response.getHeaders().put("X-Content-Type-Options", "nosniff");
        response.getHeaders().put("X-Frame-Options", "DENY");
        response.getHeaders().put("Content-Security-Policy",
            "default-src 'none'; frame-ancestors 'none'; base-uri 'none'");
        response.getHeaders().put("Referrer-Policy", "no-referrer");
        response.getHeaders().put("Cross-Origin-Resource-Policy", "same-origin");

        if(request.isSecure())
        {
            response.getHeaders().put(HttpHeader.STRICT_TRANSPORT_SECURITY, "max-age=31536000");
        }
    }

    private static void methodNotAllowed(Response response, Callback callback, String allowedMethod)
    {
        response.getHeaders().put(HttpHeader.ALLOW, allowedMethod);
        respondJson(response, callback, 405, INVALID_REQUEST);
    }

    private static void respondJson(Response response, Callback callback, int status, byte[] body)
    {
        response.setStatus(status);
        response.getHeaders().put(HttpHeader.CONTENT_TYPE, JSON_CONTENT_TYPE);
        response.getHeaders().put(HttpHeader.CONTENT_LENGTH, body.length);
        response.write(true, ByteBuffer.wrap(body), callback);
    }

    private static byte[] json(String body)
    {
        return body.getBytes(StandardCharsets.UTF_8);
    }

    public record Configuration(int maximumLoginBodyBytes)
    {
        private static final int MAXIMUM_ALLOWED_LOGIN_BODY_BYTES = 4_096;

        public Configuration
        {
            if(maximumLoginBodyBytes < 256 || maximumLoginBodyBytes > MAXIMUM_ALLOWED_LOGIN_BODY_BYTES)
            {
                throw new IllegalArgumentException("Login body limit must be between 256 and 4096 bytes");
            }
        }

        public static Configuration defaults()
        {
            return new Configuration(1_024);
        }
    }

    /**
     * Credential-free result for an administrator-only state-changing request.
     */
    public record MutationAuthorization(boolean authorized, BooleanSupplier sessionIsValid)
    {
        public MutationAuthorization
        {
            Objects.requireNonNull(sessionIsValid, "Mutation session validity check cannot be null");
        }

        /**
         * Rechecks that the session which authorized this request is still live.  Failures are treated as revoked.
         */
        public boolean isSessionValid()
        {
            if(!authorized)
            {
                return false;
            }

            try
            {
                return sessionIsValid.getAsBoolean();
            }
            catch(RuntimeException exception)
            {
                return false;
            }
        }

        private static MutationAuthorization authorized(BooleanSupplier sessionIsValid)
        {
            return new MutationAuthorization(true, sessionIsValid);
        }

        private static MutationAuthorization rejected()
        {
            return new MutationAuthorization(false, () -> false);
        }
    }

    private record CookieLookup(boolean present, String sessionId)
    {
    }

    private record Origin(String scheme, String host, int port)
    {
    }

    private record LoginPayload(String username, char[] password) implements AutoCloseable
    {
        private LoginPayload
        {
            Objects.requireNonNull(username, "Login username cannot be null");
            Objects.requireNonNull(password, "Login password cannot be null");
        }

        @Override
        public void close()
        {
            Arrays.fill(password, '\u0000');
        }
    }
}
