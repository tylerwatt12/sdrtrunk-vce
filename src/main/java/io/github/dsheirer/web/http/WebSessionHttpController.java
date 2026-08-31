/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.web.auth.AccessTier;
import io.github.dsheirer.web.auth.WebAccessAccount;
import io.github.dsheirer.web.auth.WebAccessService;
import io.github.dsheirer.web.auth.WebAccessSession;
import io.github.dsheirer.web.auth.WebAuthenticationService;
import io.github.dsheirer.web.auth.WebCapability;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Exact endpoints for session discovery, login, local desktop handoff, and logout. */
public final class WebSessionHttpController
{
    public static final String SESSION_PATH = "/api/v1/auth/session";
    public static final String LOGIN_PATH = "/api/v1/auth/login";
    public static final String LOGOUT_PATH = "/api/v1/auth/logout";
    public static final String DESKTOP_HANDOFF_PATH = "/api/v1/auth/desktop-handoff";
    private static final String DESKTOP_ALIAS_HANDOFF_PATH = DESKTOP_HANDOFF_PATH + "/aliases";
    private static final String DESKTOP_P25_BANDPLAN_OVERRIDE_HANDOFF_PATH =
        DESKTOP_HANDOFF_PATH + "/p25-bandplan-overrides";
    private static final Logger mLog = LoggerFactory.getLogger(WebSessionHttpController.class);

    private final WebAccessService mAccessService;
    private final WebAuthenticationService mAuthenticationService;
    private final WebRequestSecurity mSecurity;

    public WebSessionHttpController(WebAccessService accessService, WebAuthenticationService authenticationService,
                                    WebRequestSecurity security)
    {
        mAccessService = Objects.requireNonNull(accessService, "Web access service cannot be null");
        mAuthenticationService = Objects.requireNonNull(authenticationService,
            "Web authentication service cannot be null");
        mSecurity = Objects.requireNonNull(security, "Web request security cannot be null");
    }

    public void register(HttpServer server)
    {
        Objects.requireNonNull(server, "HTTP server cannot be null");
        server.createContext(SESSION_PATH, this::handleSession);
        server.createContext(LOGIN_PATH, this::handleLogin);
        server.createContext(LOGOUT_PATH, this::handleLogout);
        server.createContext(DESKTOP_HANDOFF_PATH, this::handleDesktopHandoff);
    }

    private void handleSession(HttpExchange exchange) throws IOException
    {
        WebRequestSecurity.prepareSecurityHeaders(exchange);
        if(!WebHttpSupport.hasExactPath(exchange, SESSION_PATH))
        {
            WebHttpSupport.notFound(exchange);
            return;
        }
        if(!WebHttpSupport.requireNoQuery(exchange))
        {
            return;
        }
        if(!"GET".equals(exchange.getRequestMethod()))
        {
            WebHttpSupport.methodNotAllowed(exchange, "GET");
            return;
        }
        if(WebHttpSupport.hasRequestBody(exchange) || !WebRequestSecurity.hasSafeGetOrigin(exchange))
        {
            WebHttpSupport.sendError(exchange, 400, "request_rejected", "The session request was rejected");
            return;
        }

        WebRequestSecurity.CookieLookup cookie = mSecurity.sessionCookie(exchange);
        Optional<WebAccessSession> session = cookie.valid() ? mSecurity.requestSession(exchange) : Optional.empty();
        if(cookie.present() && session.isEmpty())
        {
            WebRequestSecurity.expireSessionCookie(exchange);
        }
        WebHttpSupport.sendData(exchange, 200, sessionResponse(session.orElse(null)));
    }

    private void handleLogin(HttpExchange exchange) throws IOException
    {
        WebRequestSecurity.prepareSecurityHeaders(exchange);
        if(!WebHttpSupport.hasExactPath(exchange, LOGIN_PATH))
        {
            WebHttpSupport.notFound(exchange);
            return;
        }
        if(!WebHttpSupport.requireNoQuery(exchange))
        {
            return;
        }
        if(!"POST".equals(exchange.getRequestMethod()))
        {
            WebHttpSupport.methodNotAllowed(exchange, "POST");
            return;
        }
        if(!WebRequestSecurity.isSecureTransport(exchange) && !WebRequestSecurity.isLoopbackPeer(exchange))
        {
            WebHttpSupport.sendError(exchange, 403, "secure_transport_required",
                "Login requires HTTPS for non-local connections");
            return;
        }
        if(!WebRequestSecurity.hasSameOrigin(exchange))
        {
            WebHttpSupport.sendError(exchange, 403, "request_rejected", "The login request origin was rejected");
            return;
        }

        JsonNode request;
        try
        {
            request = WebHttpSupport.readJsonObject(exchange, Set.of("username", "password"));
        }
        catch(WebHttpSupport.RequestException exception)
        {
            WebHttpSupport.sendError(exchange, exception.status(), exception.code(), exception.getMessage());
            return;
        }

        WebRequestSecurity.CookieLookup existing = mSecurity.sessionCookie(exchange);
        String existingSessionId = existing.valid() ? existing.sessionId() : null;
        char[] password = null;
        CompletableFuture<WebAuthenticationService.LoginResult> completion = null;
        try
        {
            String username = WebHttpSupport.requiredText(request, "username", 256);
            password = WebHttpSupport.requiredPassword(request);
            completion = mAuthenticationService.login(username, password, WebRequestSecurity.sourceKey(exchange),
                existingSessionId);
            WebAuthenticationService.LoginResult result = completion.get(30, TimeUnit.SECONDS);
            if(result.status() == WebAuthenticationService.LoginStatus.THROTTLED)
            {
                exchange.getResponseHeaders().set("Retry-After", Long.toString(Math.max(1,
                    (result.retryAfterMillis() + 999) / 1000)));
                WebHttpSupport.sendError(exchange, 429, "login_throttled",
                    "Too many login attempts; try again later");
                return;
            }
            if(result.status() == WebAuthenticationService.LoginStatus.DENIED)
            {
                WebHttpSupport.sendError(exchange, 401, "invalid_credentials",
                    "The username or password is invalid");
                return;
            }
            if(result.status() != WebAuthenticationService.LoginStatus.SUCCESS || result.session().isEmpty())
            {
                WebHttpSupport.sendError(exchange, 503, "login_unavailable", "Login is temporarily unavailable");
                return;
            }
            deliverLoginResponse(exchange, result.session().get(), existingSessionId);
        }
        catch(WebHttpSupport.RequestException exception)
        {
            WebHttpSupport.sendError(exchange, exception.status(), exception.code(), exception.getMessage());
        }
        catch(IllegalArgumentException exception)
        {
            WebHttpSupport.sendError(exchange, 401, "invalid_credentials", "The username or password is invalid");
        }
        catch(TimeoutException | ExecutionException exception)
        {
            abandonLogin(completion, existingSessionId);
            mLog.warn("Web login worker did not complete normally", exception);
            WebHttpSupport.sendError(exchange, 503, "login_unavailable", "Login is temporarily unavailable");
        }
        catch(InterruptedException exception)
        {
            abandonLogin(completion, existingSessionId);
            Thread.currentThread().interrupt();
            WebHttpSupport.sendError(exchange, 503, "login_unavailable", "Login was interrupted");
        }
        finally
        {
            if(password != null)
            {
                Arrays.fill(password, '\u0000');
            }
        }
    }

    private void handleDesktopHandoff(HttpExchange exchange) throws IOException
    {
        WebRequestSecurity.prepareSecurityHeaders(exchange);
        String redirectLocation = desktopHandoffRedirectLocation(exchange.getRequestURI().getRawPath());
        if(redirectLocation == null)
        {
            WebHttpSupport.notFound(exchange);
            return;
        }
        if(!WebHttpSupport.requireNoQuery(exchange))
        {
            return;
        }
        if(!"GET".equals(exchange.getRequestMethod()))
        {
            WebHttpSupport.methodNotAllowed(exchange, "GET");
            return;
        }
        if(WebHttpSupport.hasRequestBody(exchange) || !WebRequestSecurity.isLoopbackPeer(exchange) ||
            !WebRequestSecurity.hasLoopbackHost(exchange) || !WebRequestSecurity.hasSafeGetOrigin(exchange))
        {
            WebHttpSupport.sendError(exchange, 403, "request_rejected", "The desktop sign-in was rejected");
            return;
        }

        WebRequestSecurity.CookieLookup existing = mSecurity.sessionCookie(exchange);
        String existingSessionId = existing.valid() ? existing.sessionId() : null;
        Optional<WebAccessSession> session =
            mAuthenticationService.redeemDesktopAdministratorHandoff(existingSessionId);

        if(session.isEmpty())
        {
            //Keep the validated same-origin destination so an expired or already-consumed handoff can continue
            //through the ordinary administrator sign-in without losing the requested Alias.
            redirect(exchange, redirectLocation);
            return;
        }

        deliverSessionResponse(exchange, session.get(), existingSessionId, redirectLocation);
    }

    /** Retires the prior session only after the replacement session reaches the browser. */
    void deliverLoginResponse(HttpExchange exchange, WebAccessSession created, String existingSessionId)
        throws IOException
    {
        deliverSessionResponse(exchange, created, existingSessionId, null);
    }

    private void deliverSessionResponse(HttpExchange exchange, WebAccessSession created, String existingSessionId,
                                        String redirectLocation) throws IOException
    {
        boolean delivered = false;
        try
        {
            WebRequestSecurity.setSessionCookie(exchange, created.sessionId());
            if(redirectLocation != null)
            {
                redirect(exchange, redirectLocation);
            }
            else
            {
                WebHttpSupport.sendData(exchange, 200, sessionResponse(created));
            }
            delivered = true;
        }
        finally
        {
            if(delivered)
            {
                if(existingSessionId != null && !existingSessionId.equals(created.sessionId()))
                {
                    mAuthenticationService.logout(existingSessionId);
                }
            }
            else if(existingSessionId == null || !existingSessionId.equals(created.sessionId()))
            {
                mAuthenticationService.logout(created.sessionId());
            }
        }
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException
    {
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("Location", location);
        exchange.getResponseHeaders().set("Vary", "Cookie");
        exchange.sendResponseHeaders(303, -1);
        exchange.close();
    }

    /**
     * Fixed desktop handoff path for one persisted Alias.  Only numeric database identities cross the handoff;
     * the server constructs the same-origin destination and never accepts an arbitrary redirect URL.
     */
    public static String desktopAliasHandoffPath(long aliasListId, long aliasId)
    {
        if(aliasListId <= 0 || aliasId <= 0)
        {
            throw new IllegalArgumentException("Alias List and Alias IDs must be positive");
        }

        return DESKTOP_ALIAS_HANDOFF_PATH + "/" + aliasListId + "/" + aliasId;
    }

    /** Fixed desktop handoff path for the Alias catalog. */
    public static String desktopAliasHandoffPath()
    {
        return DESKTOP_ALIAS_HANDOFF_PATH;
    }

    /** Fixed desktop handoff path for creating one site-scoped P25 bandplan override. */
    public static String desktopP25BandplanOverrideHandoffPath(P25SiteIdentity identity)
    {
        Objects.requireNonNull(identity, "P25 site identity cannot be null");
        return DESKTOP_P25_BANDPLAN_OVERRIDE_HANDOFF_PATH + String.format(Locale.ROOT, "/%05X/%03X/%02X/%02X",
            identity.wacn(), identity.system(), identity.rfss(), identity.site());
    }

    private static String desktopHandoffRedirectLocation(String rawPath)
    {
        if(DESKTOP_HANDOFF_PATH.equals(rawPath))
        {
            return "/";
        }

        if(DESKTOP_ALIAS_HANDOFF_PATH.equals(rawPath))
        {
            return "/?view=aliases";
        }

        String p25Prefix = DESKTOP_P25_BANDPLAN_OVERRIDE_HANDOFF_PATH + "/";
        if(rawPath != null && rawPath.startsWith(p25Prefix))
        {
            String[] segments = rawPath.substring(p25Prefix.length()).split("/", -1);
            if(segments.length != 4 || !isFixedHex(segments[0], 5) || !isFixedHex(segments[1], 3) ||
                !isFixedHex(segments[2], 2) || !isFixedHex(segments[3], 2))
            {
                return null;
            }

            try
            {
                P25SiteIdentity identity = new P25SiteIdentity(Integer.parseInt(segments[0], 16),
                    Integer.parseInt(segments[1], 16), Integer.parseInt(segments[2], 16),
                    Integer.parseInt(segments[3], 16));
                return String.format(Locale.ROOT,
                    "/?view=admin&tab=p25-bandplans&createP25Override=1&wacn=%05X&system=%03X&rfss=%02X&site=%02X",
                    identity.wacn(), identity.system(), identity.rfss(), identity.site());
            }
            catch(IllegalArgumentException exception)
            {
                return null;
            }
        }

        String prefix = DESKTOP_ALIAS_HANDOFF_PATH + "/";
        if(rawPath == null || !rawPath.startsWith(prefix))
        {
            return null;
        }

        String[] segments = rawPath.substring(prefix.length()).split("/", -1);
        if(segments.length != 2)
        {
            return null;
        }

        try
        {
            long aliasListId = Long.parseLong(segments[0]);
            long aliasId = Long.parseLong(segments[1]);
            if(aliasListId <= 0 || aliasId <= 0)
            {
                return null;
            }
            return "/?view=aliases&list=" + aliasListId + "&alias=" + aliasId;
        }
        catch(NumberFormatException exception)
        {
            return null;
        }
    }

    private static boolean isFixedHex(String value, int width)
    {
        if(value == null || value.length() != width)
        {
            return false;
        }

        for(int x = 0; x < value.length(); x++)
        {
            char character = value.charAt(x);
            boolean digit = character >= '0' && character <= '9';
            boolean upperHex = character >= 'A' && character <= 'F';
            boolean lowerHex = character >= 'a' && character <= 'f';
            if(!digit && !upperHex && !lowerHex)
            {
                return false;
            }
        }

        return true;
    }

    private void abandonLogin(CompletableFuture<WebAuthenticationService.LoginResult> completion,
                              String existingSessionId)
    {
        if(completion == null)
        {
            return;
        }
        completion.whenComplete((result, throwable) -> {
            if(result != null && result.session().isPresent() &&
                !result.session().get().sessionId().equals(existingSessionId))
            {
                mAuthenticationService.logout(result.session().get().sessionId());
            }
        });
        completion.cancel(true);
    }

    private void handleLogout(HttpExchange exchange) throws IOException
    {
        WebRequestSecurity.prepareSecurityHeaders(exchange);
        if(!WebHttpSupport.hasExactPath(exchange, LOGOUT_PATH))
        {
            WebHttpSupport.notFound(exchange);
            return;
        }
        if(!WebHttpSupport.requireNoQuery(exchange))
        {
            return;
        }
        if(!"POST".equals(exchange.getRequestMethod()))
        {
            WebHttpSupport.methodNotAllowed(exchange, "POST");
            return;
        }

        WebRequestSecurity.CookieLookup cookie = mSecurity.sessionCookie(exchange);
        if(!mSecurity.authorizeMutation(exchange))
        {
            WebHttpSupport.sendError(exchange, 403, "request_rejected", "The logout request was rejected");
            return;
        }
        mAuthenticationService.logout(cookie.sessionId());
        WebRequestSecurity.expireSessionCookie(exchange);
        WebHttpSupport.sendData(exchange, 200, sessionResponse(null));
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
        response.put("tier", WebHttpSupport.tierName(tier));
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
}
