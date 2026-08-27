/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.dsheirer.web.auth.AccessTier;
import io.github.dsheirer.web.auth.WebAccessAccount;
import io.github.dsheirer.web.auth.WebAccessService;
import io.github.dsheirer.web.auth.WebAccessSession;
import io.github.dsheirer.web.auth.WebAuthenticationService;
import io.github.dsheirer.web.auth.WebCapability;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
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

/** Exact endpoints for session discovery, login, and logout. */
public final class WebSessionHttpController
{
    public static final String SESSION_PATH = "/api/v1/auth/session";
    public static final String LOGIN_PATH = "/api/v1/auth/login";
    public static final String LOGOUT_PATH = "/api/v1/auth/logout";
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

    /** Retires the prior session only after the replacement session reaches the browser. */
    void deliverLoginResponse(HttpExchange exchange, WebAccessSession created, String existingSessionId)
        throws IOException
    {
        boolean delivered = false;
        try
        {
            WebRequestSecurity.setSessionCookie(exchange, created.sessionId());
            WebHttpSupport.sendData(exchange, 200, sessionResponse(created));
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
