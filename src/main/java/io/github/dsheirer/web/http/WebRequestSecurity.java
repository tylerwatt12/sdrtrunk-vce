/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.http;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpsExchange;
import io.github.dsheirer.web.auth.WebAccessAccount;
import io.github.dsheirer.web.auth.WebAccessService;
import io.github.dsheirer.web.auth.WebAccessSession;
import io.github.dsheirer.web.auth.WebAuthenticationService;
import io.github.dsheirer.web.auth.WebCapability;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Shared request boundary for web authentication, authorization, cookies, same-origin checks, and CSRF protection.
 */
public final class WebRequestSecurity implements AutoCloseable
{
    public static final String SESSION_COOKIE_NAME = "sdrtrunk_web_session";
    public static final String CSRF_HEADER_NAME = "X-CSRF-Token";
    private static final String AUTHORIZATION_ATTRIBUTE = WebRequestSecurity.class.getName() + ".authorization";
    private static final int MAXIMUM_COOKIE_HEADER_CHARACTERS = 8 * 1024;
    private static final String SECURITY_POLICY =
        "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; " +
            "img-src 'self' data:; media-src 'self' blob:; connect-src 'self'; object-src 'none'; " +
            "base-uri 'none'; frame-ancestors 'none'; form-action 'self'";

    private final WebAccessService mAccessService;
    private final WebAuthenticationService mAuthenticationService;

    public WebRequestSecurity(WebAccessService accessService, WebAuthenticationService authenticationService)
    {
        mAccessService = Objects.requireNonNull(accessService, "Web access service cannot be null");
        mAuthenticationService = Objects.requireNonNull(authenticationService,
            "Web authentication service cannot be null");
    }

    /** Wraps one page or API resource with its code-owned capability policy. */
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

    /** Admits a shared transport when the requester can use at least one of its logical resources. */
    public HttpHandler protectAny(Set<WebCapability> capabilities, HttpHandler next)
    {
        Objects.requireNonNull(capabilities, "Web capabilities cannot be null");
        Objects.requireNonNull(next, "Protected HTTP handler cannot be null");
        Set<WebCapability> required = Set.copyOf(capabilities);
        if(required.isEmpty())
        {
            throw new IllegalArgumentException("At least one web capability is required");
        }
        return exchange -> {
            prepareSecurityHeaders(exchange);
            if(authorizeAny(exchange, required))
            {
                next.handle(exchange);
            }
        };
    }

    /** Protects a same-origin, ephemeral viewer control carried by a shared transport. */
    public HttpHandler protectAnyViewerControl(Set<WebCapability> capabilities, HttpHandler next)
    {
        Objects.requireNonNull(next, "Protected HTTP handler cannot be null");
        return protectAny(capabilities, exchange -> {
            CookieLookup cookie = sessionCookie(exchange);
            if(!hasSameOrigin(exchange) || cookie.present() && !authorizeMutation(exchange, cookie))
            {
                WebHttpSupport.sendError(exchange, 403, "request_rejected",
                    "The viewer control request was rejected");
                return;
            }
            next.handle(exchange);
        });
    }

    /** Adds same-origin and CSRF protection to unsafe methods after capability authorization. */
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
                WebHttpSupport.sendError(exchange, 403, "request_rejected", "The change request was rejected");
                return;
            }
            next.handle(exchange);
        };
    }

    /** Rechecks current session and policy state for a long-lived request. */
    public boolean isRequestStillAuthorized(HttpExchange exchange)
    {
        Object value = exchange != null ? exchange.getAttribute(AUTHORIZATION_ATTRIBUTE) : null;
        return value instanceof RequestAuthorization authorization && authorization.isStillAllowed();
    }

    /** Rechecks one logical resource carried by a request admitted through {@link #protectAny}. */
    public boolean isRequestStillAuthorized(HttpExchange exchange, WebCapability capability)
    {
        Object value = exchange != null ? exchange.getAttribute(AUTHORIZATION_ATTRIBUTE) : null;
        return value instanceof RequestAuthorization authorization && authorization.isStillAllowed(capability);
    }

    /** Returns the signed-in account already authorized for this request, without database work. */
    public Optional<WebAccessAccount> authenticatedAccount(HttpExchange exchange)
    {
        Object value = exchange != null ? exchange.getAttribute(AUTHORIZATION_ATTRIBUTE) : null;
        return value instanceof RequestAuthorization authorization ? authorization.currentAccount() : Optional.empty();
    }

    public static void prepareSecurityHeaders(HttpExchange exchange)
    {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Security-Policy", SECURITY_POLICY);
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Permissions-Policy", "camera=(), microphone=(), geolocation=(self)");
    }

    private boolean authorize(HttpExchange exchange, WebCapability capability) throws IOException
    {
        CookieLookup cookie = sessionCookie(exchange);
        Optional<WebAccessSession> session = resolve(cookie);
        WebAccessAccount account = session.map(WebAccessSession::account).orElse(null);
        if(mAccessService.isAllowed(account, capability))
        {
            exchange.setAttribute(AUTHORIZATION_ATTRIBUTE,
                new RequestAuthorization(Set.of(capability), session.map(WebAccessSession::sessionId).orElse(null)));
            return true;
        }
        rejectAuthorization(exchange, account);
        return false;
    }

    private boolean authorizeAny(HttpExchange exchange, Set<WebCapability> capabilities) throws IOException
    {
        CookieLookup cookie = sessionCookie(exchange);
        Optional<WebAccessSession> session = resolve(cookie);
        WebAccessAccount account = session.map(WebAccessSession::account).orElse(null);
        if(capabilities.stream().anyMatch(capability -> mAccessService.isAllowed(account, capability)))
        {
            exchange.setAttribute(AUTHORIZATION_ATTRIBUTE,
                new RequestAuthorization(capabilities, session.map(WebAccessSession::sessionId).orElse(null)));
            return true;
        }
        rejectAuthorization(exchange, account);
        return false;
    }

    private Optional<WebAccessSession> resolve(CookieLookup cookie)
    {
        return cookie.valid() ? mAuthenticationService.resolveSession(cookie.sessionId()) : Optional.empty();
    }

    private static void rejectAuthorization(HttpExchange exchange, WebAccessAccount account) throws IOException
    {
        WebHttpSupport.sendError(exchange, account == null ? 401 : 403,
            account == null ? "authentication_required" : "access_denied",
            account == null ? "Authentication is required" : "Access is denied");
    }

    boolean authorizeMutation(HttpExchange exchange)
    {
        return authorizeMutation(exchange, sessionCookie(exchange));
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

    Optional<WebAccessSession> requestSession(HttpExchange exchange)
    {
        return resolve(sessionCookie(exchange));
    }

    CookieLookup sessionCookie(HttpExchange exchange)
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

    static void setSessionCookie(HttpExchange exchange, String sessionId)
    {
        String cookie = SESSION_COOKIE_NAME + "=" + sessionId + "; Path=/; HttpOnly; SameSite=Strict" +
            (isSecureTransport(exchange) ? "; Secure" : "");
        exchange.getResponseHeaders().add("Set-Cookie", cookie);
    }

    static void expireSessionCookie(HttpExchange exchange)
    {
        String cookie = SESSION_COOKIE_NAME + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict" +
            (isSecureTransport(exchange) ? "; Secure" : "");
        exchange.getResponseHeaders().add("Set-Cookie", cookie);
    }

    static boolean hasSafeGetOrigin(HttpExchange exchange)
    {
        List<String> origins = exchange.getRequestHeaders().get("Origin");
        return origins == null || origins.isEmpty() || origins.size() == 1 && hasSameOrigin(exchange);
    }

    static boolean hasSameOrigin(HttpExchange exchange)
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
                scheme.equalsIgnoreCase(origin.getScheme()) && origin.getHost().equalsIgnoreCase(target.getHost()) &&
                effectivePort(origin) == effectivePort(target);
        }
        catch(RuntimeException exception)
        {
            return false;
        }
    }

    static boolean hasLoopbackHost(HttpExchange exchange)
    {
        List<String> hosts = exchange.getRequestHeaders().get("Host");
        InetSocketAddress local = exchange.getLocalAddress();
        return hosts != null && hosts.size() == 1 && local != null &&
            isLoopbackHost(hosts.getFirst(), local.getPort());
    }

    static boolean isLoopbackHost(String authority, int port)
    {
        return ("127.0.0.1:" + port).equals(authority);
    }

    private static int effectivePort(URI uri)
    {
        if(uri.getPort() >= 0)
        {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    static boolean isSecureTransport(HttpExchange exchange)
    {
        return exchange instanceof HttpsExchange;
    }

    static boolean isLoopbackPeer(HttpExchange exchange)
    {
        InetAddress address = exchange.getRemoteAddress() != null ? exchange.getRemoteAddress().getAddress() : null;
        return address != null && address.isLoopbackAddress();
    }

    static String sourceKey(HttpExchange exchange)
    {
        InetAddress address = exchange.getRemoteAddress() != null ? exchange.getRemoteAddress().getAddress() : null;
        return address != null ? address.getHostAddress() : "unknown";
    }

    @Override
    public void close()
    {
        mAuthenticationService.close();
    }

    private final class RequestAuthorization
    {
        private final Set<WebCapability> mCapabilities;
        private final String mSessionId;

        private RequestAuthorization(Set<WebCapability> capabilities, String sessionId)
        {
            mCapabilities = Set.copyOf(capabilities);
            mSessionId = sessionId;
        }

        private boolean isStillAllowed()
        {
            return mCapabilities.stream().anyMatch(this::isStillAllowed);
        }

        private boolean isStillAllowed(WebCapability capability)
        {
            WebAccessAccount account = currentAccount().orElse(null);
            return mCapabilities.contains(capability) && mAccessService.isAllowed(account, capability);
        }

        private Optional<WebAccessAccount> currentAccount()
        {
            return mSessionId == null ? Optional.empty() :
                mAuthenticationService.resolveSession(mSessionId).map(WebAccessSession::account);
        }

        @Override
        public String toString()
        {
            return "RequestAuthorization[capabilities=" + mCapabilities.stream().map(WebCapability::id).toList() +
                ", session=<redacted>]";
        }
    }

    record CookieLookup(boolean present, boolean valid, String sessionId)
    {
        private static final CookieLookup MISSING = new CookieLookup(false, true, null);
    }
}
