/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.web.http;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsExchange;
import io.github.dsheirer.web.auth.AccessTier;
import io.github.dsheirer.web.auth.Pbkdf2PasswordHasher;
import io.github.dsheirer.web.auth.WebAccessAccount;
import io.github.dsheirer.web.auth.WebAccessService;
import io.github.dsheirer.web.auth.WebAccessSession;
import io.github.dsheirer.web.auth.WebAuthenticationService;
import io.github.dsheirer.web.auth.WebCapability;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Small HTTP adapter for web accounts, browser sessions, and capability policies.
 *
 * <p>The credential and policy service remains transport-neutral.  This adapter owns the ephemeral session cookie,
 * same-origin and CSRF checks, bounded login admission, and the four administration routes.  Static assets may be
 * served publicly; every data, stream, export, and audio handler must be wrapped with {@link #protect}.</p>
 */
public final class WebAccessHttpController implements AutoCloseable
{
    public static final String SESSION_PATH = "/api/v1/auth/session";
    public static final String LOGIN_PATH = "/api/v1/auth/login";
    public static final String LOGOUT_PATH = "/api/v1/auth/logout";
    public static final String USERS_PATH = "/api/v1/admin/users";
    public static final String ACCESS_PATH = "/api/v1/admin/access";
    public static final String SESSION_COOKIE_NAME = "sdrtrunk_web_session";
    public static final String CSRF_HEADER_NAME = "X-CSRF-Token";
    private static final Logger mLog = LoggerFactory.getLogger(WebAccessHttpController.class);
    private static final String AUTHORIZATION_ATTRIBUTE =
        WebAccessHttpController.class.getName() + ".authorization";
    private static final int MAXIMUM_JSON_BODY_BYTES = 16 * 1024;
    private static final int MAXIMUM_COOKIE_HEADER_CHARACTERS = 8 * 1024;
    private static final String JSON_CONTENT_TYPE = "application/json; charset=utf-8";
    private static final String SECURITY_POLICY =
        "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; " +
            "img-src 'self' data:; media-src 'self' blob:; connect-src 'self'; object-src 'none'; " +
            "base-uri 'none'; frame-ancestors 'none'; form-action 'self'";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .build());

    private final WebAccessService mAccessService;
    private final WebAuthenticationService mAuthenticationService;

    public WebAccessHttpController(WebAccessService accessService)
    {
        this(accessService, new WebAuthenticationService(accessService));
    }

    public WebAccessHttpController(WebAccessService accessService, WebAuthenticationService authenticationService)
    {
        mAccessService = Objects.requireNonNull(accessService, "Web access service cannot be null");
        mAuthenticationService = Objects.requireNonNull(authenticationService,
            "Web authentication service cannot be null");
    }

    /**
     * Registers exact authentication and administration endpoints.
     */
    public void register(HttpServer server)
    {
        Objects.requireNonNull(server, "HTTP server cannot be null");
        server.createContext(SESSION_PATH, this::handleSession);
        server.createContext(LOGIN_PATH, this::handleLogin);
        server.createContext(LOGOUT_PATH, this::handleLogout);
        server.createContext(USERS_PATH, this::handleUsers);
        server.createContext(ACCESS_PATH, this::handleAccess);
    }

    /**
     * Wraps one page/API resource with its code-owned capability policy.
     */
    public HttpHandler protect(WebCapability capability, HttpHandler next)
    {
        Objects.requireNonNull(capability, "Web capability cannot be null");
        Objects.requireNonNull(next, "Protected HTTP handler cannot be null");
        return exchange -> {
            prepareSecurityHeaders(exchange);

            if(authorize(exchange, capability))
            {
                next.handle(exchange);
            }
        };
    }

    /**
     * Wraps an API resource with capability protection and adds same-origin and CSRF protection to unsafe methods.
     */
    public HttpHandler protectApi(WebCapability capability, HttpHandler next)
    {
        Objects.requireNonNull(capability, "Web capability cannot be null");
        Objects.requireNonNull(next, "Protected HTTP handler cannot be null");
        return exchange -> {
            prepareSecurityHeaders(exchange);

            if(!authorize(exchange, capability))
            {
                return;
            }

            boolean safeMethod = switch(exchange.getRequestMethod())
            {
                case "GET", "HEAD", "OPTIONS" -> true;
                default -> false;
            };

            if(!safeMethod && !authorizeMutation(exchange, sessionCookie(exchange)))
            {
                sendError(exchange, 403, "request_rejected", "The change request was rejected");
                return;
            }

            next.handle(exchange);
        };
    }

    /**
     * Rechecks the session and current policy for a long-lived SSE request.
     */
    public boolean isRequestStillAuthorized(HttpExchange exchange)
    {
        Object value = exchange != null ? exchange.getAttribute(AUTHORIZATION_ATTRIBUTE) : null;
        return value instanceof RequestAuthorization authorization && authorization.isStillAllowed();
    }

    /**
     * Applies response headers shared by static assets and protected endpoints.
     */
    public static void prepareSecurityHeaders(HttpExchange exchange)
    {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Security-Policy", SECURITY_POLICY);
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
    }

    private boolean authorize(HttpExchange exchange, WebCapability capability) throws IOException
    {
        CookieLookup cookie = sessionCookie(exchange);
        Optional<WebAccessSession> session = cookie.valid() ?
            mAuthenticationService.resolveSession(cookie.sessionId()) : Optional.empty();
        WebAccessAccount account = session.map(WebAccessSession::account).orElse(null);

        if(mAccessService.isAllowed(account, capability))
        {
            exchange.setAttribute(AUTHORIZATION_ATTRIBUTE,
                new RequestAuthorization(capability, session.map(WebAccessSession::sessionId).orElse(null)));
            return true;
        }

        int status = account == null ? 401 : 403;
        sendJson(exchange, status, Map.of(
            "error", account == null ? "authentication_required" : "access_denied",
            "status", status,
            "capability", capability.id()));
        return false;
    }

    private void handleSession(HttpExchange exchange) throws IOException
    {
        prepareSecurityHeaders(exchange);

        if(!hasExactPath(exchange, SESSION_PATH))
        {
            notFound(exchange);
            return;
        }

        if(!requireMethod(exchange, "GET"))
        {
            return;
        }

        if(hasRequestBody(exchange) || !hasSafeGetOrigin(exchange))
        {
            sendError(exchange, 400, "request_rejected", "The session request was rejected");
            return;
        }

        CookieLookup cookie = sessionCookie(exchange);
        Optional<WebAccessSession> session = cookie.valid() ?
            mAuthenticationService.resolveSession(cookie.sessionId()) : Optional.empty();

        if(cookie.present() && session.isEmpty())
        {
            expireSessionCookie(exchange);
        }

        sendJson(exchange, 200, sessionResponse(session.orElse(null)));
    }

    private void handleLogin(HttpExchange exchange) throws IOException
    {
        prepareSecurityHeaders(exchange);

        if(!hasExactPath(exchange, LOGIN_PATH))
        {
            notFound(exchange);
            return;
        }

        if(!requireMethod(exchange, "POST"))
        {
            return;
        }

        if(!isSecureTransport(exchange) && !isLoopbackPeer(exchange))
        {
            sendError(exchange, 403, "secure_transport_required",
                "Login requires HTTPS for non-local connections");
            return;
        }

        if(!hasSameOrigin(exchange))
        {
            sendError(exchange, 403, "request_rejected", "The login request origin was rejected");
            return;
        }

        JsonNode request;

        try
        {
            request = readJsonObject(exchange, Set.of("username", "password"));
        }
        catch(RequestException exception)
        {
            sendError(exchange, exception.status(), exception.code(), exception.getMessage());
            return;
        }

        char[] password = null;
        CompletableFuture<WebAuthenticationService.LoginResult> completion = null;

        try
        {
            String username = requiredText(request, "username", 256);
            password = requiredText(request, "password", Pbkdf2PasswordHasher.MAXIMUM_PASSWORD_CHARACTERS)
                .toCharArray();
            completion = mAuthenticationService.login(username, password, sourceKey(exchange));
            WebAuthenticationService.LoginResult result = completion.get(30, TimeUnit.SECONDS);

            if(result.status() == WebAuthenticationService.LoginStatus.THROTTLED)
            {
                exchange.getResponseHeaders().set("Retry-After", Long.toString(Math.max(1,
                    (result.retryAfterMillis() + 999) / 1000)));
                sendError(exchange, 429, "login_throttled", "Too many login attempts; try again later");
                return;
            }

            if(result.status() == WebAuthenticationService.LoginStatus.DENIED)
            {
                sendError(exchange, 401, "invalid_credentials", "The username or password is invalid");
                return;
            }

            if(result.status() != WebAuthenticationService.LoginStatus.SUCCESS || result.session().isEmpty())
            {
                sendError(exchange, 503, "login_unavailable", "Login is temporarily unavailable");
                return;
            }

            CookieLookup existing = sessionCookie(exchange);

            if(existing.valid())
            {
                mAuthenticationService.logout(existing.sessionId());
            }

            WebAccessSession created = result.session().get();
            setSessionCookie(exchange, created.sessionId());
            sendJson(exchange, 200, sessionResponse(created));
        }
        catch(RequestException exception)
        {
            sendError(exchange, exception.status(), exception.code(), exception.getMessage());
        }
        catch(IllegalArgumentException exception)
        {
            sendError(exchange, 401, "invalid_credentials", "The username or password is invalid");
        }
        catch(TimeoutException | ExecutionException exception)
        {
            abandonLogin(completion);

            mLog.warn("Web login worker did not complete normally", exception);
            sendError(exchange, 503, "login_unavailable", "Login is temporarily unavailable");
        }
        catch(InterruptedException exception)
        {
            abandonLogin(completion);
            Thread.currentThread().interrupt();
            sendError(exchange, 503, "login_unavailable", "Login was interrupted");
        }
        finally
        {
            if(password != null)
            {
                Arrays.fill(password, '\u0000');
            }

        }
    }

    /**
     * Stops an HTTP login that no longer has a caller and removes a session if the worker won the completion race.
     */
    private void abandonLogin(CompletableFuture<WebAuthenticationService.LoginResult> completion)
    {
        if(completion == null)
        {
            return;
        }

        completion.whenComplete((result, throwable) ->
        {
            if(result != null && result.session().isPresent())
            {
                mAuthenticationService.logout(result.session().get().sessionId());
            }
        });
        completion.cancel(true);
    }

    private void handleLogout(HttpExchange exchange) throws IOException
    {
        prepareSecurityHeaders(exchange);

        if(!hasExactPath(exchange, LOGOUT_PATH))
        {
            notFound(exchange);
            return;
        }

        if(!requireMethod(exchange, "POST"))
        {
            return;
        }

        CookieLookup cookie = sessionCookie(exchange);

        if(!authorizeMutation(exchange, cookie))
        {
            sendError(exchange, 403, "request_rejected", "The logout request was rejected");
            return;
        }

        mAuthenticationService.logout(cookie.sessionId());
        expireSessionCookie(exchange);
        sendJson(exchange, 200, Map.of("authenticated", false, "tier", AccessTier.PUBLIC.name()));
    }

    private void handleUsers(HttpExchange exchange) throws IOException
    {
        prepareSecurityHeaders(exchange);

        if(!authorize(exchange, WebCapability.ADMIN_USERS))
        {
            return;
        }

        String path = exchange.getRequestURI().getPath();
        boolean collection = USERS_PATH.equals(path);
        String username = collection ? null : usernameFromPath(path);

        if(!collection && username == null)
        {
            notFound(exchange);
            return;
        }

        try
        {
            switch(exchange.getRequestMethod())
            {
                case "GET" -> {
                    if(!collection || hasRequestBody(exchange))
                    {
                        notFound(exchange);
                        return;
                    }

                    List<Map<String,Object>> users = mAccessService.accounts().stream()
                        .map(WebAccessHttpController::accountResponse)
                        .toList();
                    sendJson(exchange, 200, Map.of("users", users, "maximumUsers", WebAccessService.MAXIMUM_USERS));
                }
                case "POST" -> {
                    if(!collection)
                    {
                        notFound(exchange);
                        return;
                    }

                    if(!authorizeMutation(exchange, sessionCookie(exchange)))
                    {
                        sendError(exchange, 403, "request_rejected", "The user change was rejected");
                        return;
                    }

                    JsonNode request = readJsonObject(exchange, Set.of("username", "password", "tier"));
                    char[] password = requiredText(request, "password",
                        Pbkdf2PasswordHasher.MAXIMUM_PASSWORD_CHARACTERS).toCharArray();

                    try
                    {
                        WebAccessAccount created = mAccessService.createUser(
                            requiredText(request, "username", 256), password, requiredAccountTier(request));
                        sendJson(exchange, 201, accountResponse(created));
                    }
                    finally
                    {
                        Arrays.fill(password, '\u0000');
                    }
                }
                case "PUT" -> {
                    if(collection || !authorizeMutation(exchange, sessionCookie(exchange)))
                    {
                        sendError(exchange, 403, "request_rejected", "The user change was rejected");
                        return;
                    }

                    JsonNode request = readJsonObject(exchange, Set.of("password", "tier"));
                    boolean passwordPresent = request.has("password");
                    boolean tierPresent = request.has("tier");

                    if(passwordPresent == tierPresent)
                    {
                        throw new RequestException(400, "invalid_request",
                            "Specify exactly one of password or tier");
                    }

                    WebAccessAccount updated;

                    if(passwordPresent)
                    {
                        char[] password = requiredText(request, "password",
                            Pbkdf2PasswordHasher.MAXIMUM_PASSWORD_CHARACTERS).toCharArray();

                        try
                        {
                            updated = mAccessService.resetUserPassword(username, password);
                        }
                        finally
                        {
                            Arrays.fill(password, '\u0000');
                        }
                    }
                    else
                    {
                        updated = mAccessService.changeUserTier(username, requiredAccountTier(request));
                    }

                    mAuthenticationService.invalidateAccountSessions(username);
                    sendJson(exchange, 200, accountResponse(updated));
                }
                case "DELETE" -> {
                    if(collection || hasRequestBody(exchange) ||
                        !authorizeMutation(exchange, sessionCookie(exchange)))
                    {
                        sendError(exchange, 403, "request_rejected", "The user deletion was rejected");
                        return;
                    }

                    WebAccessAccount deleted = mAccessService.deleteUser(username);
                    mAuthenticationService.invalidateAccountSessions(username);
                    sendJson(exchange, 200, Map.of("deleted", deleted.username()));
                }
                default -> methodNotAllowed(exchange, collection ? "GET, POST" : "PUT, DELETE");
            }
        }
        catch(RequestException exception)
        {
            sendError(exchange, exception.status(), exception.code(), exception.getMessage());
        }
        catch(IllegalArgumentException exception)
        {
            sendError(exchange, 400, "invalid_request", safeMessage(exception, "The user request is invalid"));
        }
        catch(IllegalStateException exception)
        {
            sendError(exchange, 409, "conflict", safeMessage(exception, "The user request conflicts with current state"));
        }
        catch(SQLException exception)
        {
            mLog.warn("Unable to persist web user administration change", exception);
            sendError(exchange, 503, "storage_unavailable", "The user change could not be saved");
        }
        catch(IOException exception)
        {
            if(exchange.getResponseCode() >= 0)
            {
                throw exception;
            }

            mLog.warn("Unable to read or persist web user administration data", exception);
            sendError(exchange, 503, "storage_unavailable", "The user change could not be saved");
        }
    }

    private void handleAccess(HttpExchange exchange) throws IOException
    {
        prepareSecurityHeaders(exchange);

        if(!hasExactPath(exchange, ACCESS_PATH))
        {
            notFound(exchange);
            return;
        }

        if(!authorize(exchange, WebCapability.ADMIN_ACCESS))
        {
            return;
        }

        try
        {
            if("GET".equals(exchange.getRequestMethod()))
            {
                if(hasRequestBody(exchange))
                {
                    throw new RequestException(400, "invalid_request", "GET requests cannot include a body");
                }

                sendJson(exchange, 200, Map.of("capabilities", policyResponses()));
            }
            else if("PUT".equals(exchange.getRequestMethod()))
            {
                if(!authorizeMutation(exchange, sessionCookie(exchange)))
                {
                    sendError(exchange, 403, "request_rejected", "The access change was rejected");
                    return;
                }

                JsonNode request = readJsonObject(exchange, Set.of("capability", "tier"));
                String id = requiredText(request, "capability", 64);
                AccessTier tier = requiredTier(request, "tier");
                WebAccessService.CapabilityPolicy changed = mAccessService.setCapabilityTier(id, tier);
                sendJson(exchange, 200, policyResponse(changed));
            }
            else
            {
                methodNotAllowed(exchange, "GET, PUT");
            }
        }
        catch(RequestException exception)
        {
            sendError(exchange, exception.status(), exception.code(), exception.getMessage());
        }
        catch(IllegalArgumentException exception)
        {
            sendError(exchange, 400, "invalid_request", safeMessage(exception, "The access request is invalid"));
        }
        catch(SQLException exception)
        {
            mLog.warn("Unable to persist web access policy change", exception);
            sendError(exchange, 503, "storage_unavailable", "The access change could not be saved");
        }
        catch(IOException exception)
        {
            if(exchange.getResponseCode() >= 0)
            {
                throw exception;
            }

            mLog.warn("Unable to read or persist web access policy data", exception);
            sendError(exchange, 503, "storage_unavailable", "The access change could not be saved");
        }
    }

    private boolean authorizeMutation(HttpExchange exchange, CookieLookup cookie)
    {
        if(!cookie.valid() || !hasSameOrigin(exchange))
        {
            return false;
        }

        List<String> csrfHeaders = exchange.getRequestHeaders().get(CSRF_HEADER_NAME);
        return csrfHeaders != null && csrfHeaders.size() == 1 &&
            mAuthenticationService.validateCsrf(cookie.sessionId(), csrfHeaders.getFirst());
    }

    private Map<String,Object> sessionResponse(WebAccessSession session)
    {
        WebAccessAccount account = session != null ? session.account() : null;
        AccessTier tier = account != null ? account.tier() : AccessTier.PUBLIC;
        Map<String,Boolean> capabilities = new LinkedHashMap<>();

        for(WebCapability capability: WebCapability.values())
        {
            capabilities.put(capability.id(), mAccessService.isAllowed(tier, capability));
        }

        Map<String,Object> response = new LinkedHashMap<>();
        response.put("configured", mAccessService.isPrimaryAdminConfigured());
        response.put("authenticated", account != null);
        response.put("tier", tier.name());
        response.put("capabilities", capabilities);

        if(account != null)
        {
            response.put("username", account.username());
            response.put("primary", account.primaryAdmin());
            response.put("csrfToken", session.csrfToken());
            response.put("expiresAtEpochMillis", session.expiresAtEpochMillis());
        }

        return response;
    }

    private List<Map<String,Object>> policyResponses()
    {
        List<Map<String,Object>> responses = new ArrayList<>();

        for(WebAccessService.CapabilityPolicy policy: mAccessService.policies())
        {
            responses.add(policyResponse(policy));
        }

        return List.copyOf(responses);
    }

    private static Map<String,Object> policyResponse(WebAccessService.CapabilityPolicy policy)
    {
        return Map.of(
            "id", policy.id(),
            "displayName", policy.displayName(),
            "requiredTier", policy.requiredTier().name(),
            "defaultTier", policy.defaultTier().name(),
            "configurable", policy.configurable());
    }

    private static Map<String,Object> accountResponse(WebAccessAccount account)
    {
        return Map.of(
            "username", account.username(),
            "tier", account.tier().name(),
            "passwordChangedAtEpochMillis", account.passwordChangedAtEpochMillis(),
            "credentialVersion", account.credentialVersion(),
            "primary", account.primaryAdmin());
    }

    private static JsonNode readJsonObject(HttpExchange exchange, Set<String> allowedFields)
        throws IOException, RequestException
    {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");

        if(contentType == null || !contentType.toLowerCase(Locale.ROOT).split(";", 2)[0].strip()
            .equals("application/json"))
        {
            throw new RequestException(415, "invalid_content_type", "Content-Type must be application/json");
        }

        String contentLength = exchange.getRequestHeaders().getFirst("Content-Length");

        if(contentLength != null)
        {
            try
            {
                if(Long.parseLong(contentLength) > MAXIMUM_JSON_BODY_BYTES)
                {
                    throw new RequestException(413, "request_too_large", "The JSON request is too large");
                }
            }
            catch(NumberFormatException exception)
            {
                throw new RequestException(400, "invalid_request", "Content-Length is invalid");
            }
        }

        byte[] bytes;

        try(InputStream inputStream = exchange.getRequestBody())
        {
            bytes = inputStream.readNBytes(MAXIMUM_JSON_BODY_BYTES + 1);
        }

        if(bytes.length == 0)
        {
            throw new RequestException(400, "invalid_request", "A JSON request body is required");
        }

        if(bytes.length > MAXIMUM_JSON_BODY_BYTES)
        {
            Arrays.fill(bytes, (byte)0);
            throw new RequestException(413, "request_too_large", "The JSON request is too large");
        }

        try
        {
            JsonNode value = OBJECT_MAPPER.readTree(bytes);

            if(value == null || !value.isObject())
            {
                throw new RequestException(400, "invalid_request", "The JSON body must be an object");
            }

            java.util.Iterator<String> names = value.fieldNames();

            while(names.hasNext())
            {
                if(!allowedFields.contains(names.next()))
                {
                    throw new RequestException(400, "invalid_request", "The JSON body contains an unknown field");
                }
            }

            return value;
        }
        catch(RequestException exception)
        {
            throw exception;
        }
        catch(Exception exception)
        {
            throw new RequestException(400, "invalid_request", "The JSON body is invalid");
        }
        finally
        {
            Arrays.fill(bytes, (byte)0);
        }
    }

    private static String requiredText(JsonNode request, String field, int maximumCharacters)
        throws RequestException
    {
        JsonNode value = request.get(field);

        if(value == null || !value.isTextual() || value.textValue().isBlank() ||
            value.textValue().length() > maximumCharacters)
        {
            throw new RequestException(400, "invalid_request", field + " is invalid");
        }

        return value.textValue();
    }

    private static AccessTier requiredAccountTier(JsonNode request) throws RequestException
    {
        AccessTier tier = requiredTier(request, "tier");

        if(!tier.isAccountTier())
        {
            throw new RequestException(400, "invalid_request", "A user tier must be USER or ADMIN");
        }

        return tier;
    }

    private static AccessTier requiredTier(JsonNode request, String field) throws RequestException
    {
        String value = requiredText(request, field, 16);

        try
        {
            return AccessTier.valueOf(value.toUpperCase(Locale.ROOT));
        }
        catch(IllegalArgumentException exception)
        {
            throw new RequestException(400, "invalid_request", field + " is invalid");
        }
    }

    private static CookieLookup sessionCookie(HttpExchange exchange)
    {
        List<String> cookieHeaders = exchange.getRequestHeaders().get("Cookie");

        if(cookieHeaders == null || cookieHeaders.isEmpty())
        {
            return CookieLookup.MISSING;
        }

        int characters = cookieHeaders.stream().mapToInt(String::length).sum();

        if(characters > MAXIMUM_COOKIE_HEADER_CHARACTERS)
        {
            return new CookieLookup(true, false, null);
        }

        String found = null;

        for(String header: cookieHeaders)
        {
            for(String pair: header.split(";"))
            {
                int separator = pair.indexOf('=');

                if(separator > 0 && SESSION_COOKIE_NAME.equals(pair.substring(0, separator).strip()))
                {
                    if(found != null)
                    {
                        return new CookieLookup(true, false, null);
                    }

                    found = pair.substring(separator + 1).strip();
                }
            }
        }

        return found == null ? CookieLookup.MISSING : new CookieLookup(true, !found.isBlank(), found);
    }

    private static void setSessionCookie(HttpExchange exchange, String sessionId)
    {
        String cookie = SESSION_COOKIE_NAME + "=" + sessionId + "; Path=/; HttpOnly; SameSite=Strict" +
            (isSecureTransport(exchange) ? "; Secure" : "");
        exchange.getResponseHeaders().add("Set-Cookie", cookie);
    }

    private static void expireSessionCookie(HttpExchange exchange)
    {
        String cookie = SESSION_COOKIE_NAME + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict" +
            (isSecureTransport(exchange) ? "; Secure" : "");
        exchange.getResponseHeaders().add("Set-Cookie", cookie);
    }

    private static boolean hasSafeGetOrigin(HttpExchange exchange)
    {
        List<String> origins = exchange.getRequestHeaders().get("Origin");
        return origins == null || origins.isEmpty() || origins.size() == 1 && hasSameOrigin(exchange);
    }

    private static boolean hasSameOrigin(HttpExchange exchange)
    {
        List<String> origins = exchange.getRequestHeaders().get("Origin");
        List<String> hosts = exchange.getRequestHeaders().get("Host");

        if(origins == null || origins.size() != 1 || hosts == null || hosts.size() != 1)
        {
            return false;
        }

        try
        {
            String scheme = isSecureTransport(exchange) ? "https" : "http";
            URI origin = URI.create(origins.getFirst());
            URI target = URI.create(scheme + "://" + hosts.getFirst());
            return origin.getUserInfo() == null && origin.getRawPath().isEmpty() && origin.getRawQuery() == null &&
                origin.getRawFragment() == null && origin.getHost() != null && target.getHost() != null &&
                scheme.equalsIgnoreCase(origin.getScheme()) &&
                origin.getHost().equalsIgnoreCase(target.getHost()) && effectivePort(origin) == effectivePort(target);
        }
        catch(RuntimeException exception)
        {
            return false;
        }
    }

    private static int effectivePort(URI uri)
    {
        if(uri.getPort() >= 0)
        {
            return uri.getPort();
        }

        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static boolean isSecureTransport(HttpExchange exchange)
    {
        return exchange instanceof HttpsExchange;
    }

    private static boolean isLoopbackPeer(HttpExchange exchange)
    {
        InetAddress address = exchange.getRemoteAddress() != null ? exchange.getRemoteAddress().getAddress() : null;
        return address != null && address.isLoopbackAddress();
    }

    private static String sourceKey(HttpExchange exchange)
    {
        InetAddress address = exchange.getRemoteAddress() != null ? exchange.getRemoteAddress().getAddress() : null;
        return address != null ? address.getHostAddress() : "unknown";
    }

    private static String usernameFromPath(String path)
    {
        String prefix = USERS_PATH + "/";

        if(path == null || !path.startsWith(prefix))
        {
            return null;
        }

        String segment = path.substring(prefix.length());

        if(segment.isBlank() || segment.contains("/"))
        {
            return null;
        }

        try
        {
            String decoded = URLDecoder.decode(segment, StandardCharsets.UTF_8);
            return decoded.contains("/") || decoded.isBlank() ? null : decoded;
        }
        catch(IllegalArgumentException exception)
        {
            return null;
        }
    }

    private static boolean hasExactPath(HttpExchange exchange, String expected)
    {
        return expected.equals(exchange.getRequestURI().getPath());
    }

    private static boolean hasRequestBody(HttpExchange exchange)
    {
        String contentLength = exchange.getRequestHeaders().getFirst("Content-Length");
        return contentLength != null && !"0".equals(contentLength) ||
            exchange.getRequestHeaders().getFirst("Transfer-Encoding") != null;
    }

    private static boolean requireMethod(HttpExchange exchange, String method) throws IOException
    {
        if(method.equals(exchange.getRequestMethod()))
        {
            return true;
        }

        methodNotAllowed(exchange, method);
        return false;
    }

    private static void methodNotAllowed(HttpExchange exchange, String allow) throws IOException
    {
        exchange.getResponseHeaders().set("Allow", allow);
        sendError(exchange, 405, "method_not_allowed", "Method not allowed");
    }

    private static void notFound(HttpExchange exchange) throws IOException
    {
        sendError(exchange, 404, "not_found", "Not found");
    }

    private static void sendError(HttpExchange exchange, int status, String code, String message) throws IOException
    {
        sendJson(exchange, status, Map.of("error", code, "message", message, "status", status));
    }

    private static void sendJson(HttpExchange exchange, int status, Object value) throws IOException
    {
        prepareSecurityHeaders(exchange);
        byte[] body = OBJECT_MAPPER.writeValueAsBytes(value);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", JSON_CONTENT_TYPE);
        headers.set("Cache-Control", "no-store");
        headers.set("Vary", "Cookie");
        exchange.sendResponseHeaders(status, body.length);

        try(OutputStream outputStream = exchange.getResponseBody())
        {
            outputStream.write(body);
        }
    }

    private static String safeMessage(RuntimeException exception, String fallback)
    {
        String message = exception.getMessage();
        return message == null || message.isBlank() || message.length() > 240 ? fallback : message;
    }

    @Override
    public void close()
    {
        mAuthenticationService.close();
    }

    private final class RequestAuthorization
    {
        private final WebCapability mCapability;
        private final String mSessionId;

        private RequestAuthorization(WebCapability capability, String sessionId)
        {
            mCapability = capability;
            mSessionId = sessionId;
        }

        private boolean isStillAllowed()
        {
            WebAccessAccount account = mSessionId == null ? null :
                mAuthenticationService.resolveSession(mSessionId).map(WebAccessSession::account).orElse(null);
            return mAccessService.isAllowed(account, mCapability);
        }

        @Override
        public String toString()
        {
            return "RequestAuthorization[capability=" + mCapability.id() + ", session=<redacted>]";
        }
    }

    private record CookieLookup(boolean present, boolean valid, String sessionId)
    {
        private static final CookieLookup MISSING = new CookieLookup(false, true, null);
    }

    private static final class RequestException extends Exception
    {
        private final int mStatus;
        private final String mCode;

        private RequestException(int status, String code, String message)
        {
            super(message);
            mStatus = status;
            mCode = code;
        }

        private int status()
        {
            return mStatus;
        }

        private String code()
        {
            return mCode;
        }
    }

}
