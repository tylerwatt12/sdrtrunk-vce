/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import io.github.dsheirer.web.auth.WebAccessAccount;
import io.github.dsheirer.web.auth.WebAccessService;
import io.github.dsheirer.web.auth.WebAuthenticationService;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Administrator-only CRUD endpoint for ordinary web users. */
public final class WebUserAdminHttpController
{
    public static final String PATH = "/api/v1/admin/users";
    private static final Logger mLog = LoggerFactory.getLogger(WebUserAdminHttpController.class);

    private final WebAccessService mAccessService;
    private final WebAuthenticationService mAuthenticationService;

    public WebUserAdminHttpController(WebAccessService accessService,
                                      WebAuthenticationService authenticationService)
    {
        mAccessService = Objects.requireNonNull(accessService, "Web access service cannot be null");
        mAuthenticationService = Objects.requireNonNull(authenticationService,
            "Web authentication service cannot be null");
    }

    public void handle(HttpExchange exchange) throws IOException
    {
        WebRequestSecurity.prepareSecurityHeaders(exchange);
        String rawPath = exchange.getRequestURI().getRawPath();
        boolean collection = PATH.equals(rawPath);
        String username = collection ? null : usernameFromPath(rawPath);
        if(!collection && username == null)
        {
            WebHttpSupport.notFound(exchange);
            return;
        }
        if(!WebHttpSupport.requireNoQuery(exchange))
        {
            return;
        }

        try
        {
            switch(exchange.getRequestMethod())
            {
                case "GET" -> get(exchange, collection);
                case "POST" -> create(exchange, collection);
                case "PUT" -> update(exchange, collection, username);
                case "DELETE" -> delete(exchange, collection, username);
                default -> WebHttpSupport.methodNotAllowed(exchange, collection ? "GET, POST" : "PUT, DELETE");
            }
        }
        catch(WebHttpSupport.RequestException exception)
        {
            WebHttpSupport.sendError(exchange, exception.status(), exception.code(), exception.getMessage());
        }
        catch(IllegalArgumentException exception)
        {
            WebHttpSupport.sendError(exchange, 400, "invalid_request",
                WebHttpSupport.safeMessage(exception, "The user request is invalid"));
        }
        catch(IllegalStateException exception)
        {
            WebHttpSupport.sendError(exchange, 409, "conflict",
                WebHttpSupport.safeMessage(exception, "The user request conflicts with current state"));
        }
        catch(SQLException exception)
        {
            mLog.warn("Unable to persist web user administration change", exception);
            WebHttpSupport.sendError(exchange, 503, "storage_unavailable", "The user change could not be saved");
        }
        catch(IOException exception)
        {
            if(exchange.getResponseCode() >= 0)
            {
                throw exception;
            }
            mLog.warn("Unable to read or persist web user administration data", exception);
            WebHttpSupport.sendError(exchange, 503, "storage_unavailable", "The user change could not be saved");
        }
    }

    private void get(HttpExchange exchange, boolean collection) throws IOException, WebHttpSupport.RequestException
    {
        if(!collection)
        {
            WebHttpSupport.notFound(exchange);
            return;
        }
        if(WebHttpSupport.hasRequestBody(exchange))
        {
            throw new WebHttpSupport.RequestException(400, "invalid_request", "GET requests cannot include a body");
        }
        List<Map<String,Object>> users = mAccessService.accounts().stream()
            .map(WebHttpSupport::accountResponse)
            .toList();
        WebHttpSupport.sendData(exchange, 200,
            Map.of("users", users, "maximumUsers", WebAccessService.MAXIMUM_USERS));
    }

    private void create(HttpExchange exchange, boolean collection)
        throws IOException, SQLException, WebHttpSupport.RequestException
    {
        if(!collection)
        {
            WebHttpSupport.notFound(exchange);
            return;
        }
        JsonNode request = WebHttpSupport.readJsonObject(exchange, Set.of("username", "password", "tier"));
        char[] password = WebHttpSupport.requiredPassword(request);
        try
        {
            WebAccessAccount created = mAccessService.createUser(
                WebHttpSupport.requiredText(request, "username", 256), password,
                WebHttpSupport.requiredAccountTier(request));
            WebHttpSupport.sendData(exchange, 201, WebHttpSupport.accountResponse(created));
        }
        finally
        {
            Arrays.fill(password, '\u0000');
        }
    }

    private void update(HttpExchange exchange, boolean collection, String username)
        throws IOException, SQLException, WebHttpSupport.RequestException
    {
        if(collection)
        {
            WebHttpSupport.notFound(exchange);
            return;
        }
        JsonNode request = WebHttpSupport.readJsonObject(exchange, Set.of("password", "tier"));
        boolean passwordPresent = request.has("password");
        boolean tierPresent = request.has("tier");
        if(passwordPresent == tierPresent)
        {
            throw new WebHttpSupport.RequestException(400, "invalid_request",
                "Specify exactly one of password or tier");
        }

        WebAccessAccount updated;
        if(passwordPresent)
        {
            char[] password = WebHttpSupport.requiredPassword(request);
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
            updated = mAccessService.changeUserTier(username, WebHttpSupport.requiredAccountTier(request));
        }
        mAuthenticationService.invalidateAccountSessions(updated.username());
        WebHttpSupport.sendData(exchange, 200, WebHttpSupport.accountResponse(updated));
    }

    private void delete(HttpExchange exchange, boolean collection, String username)
        throws IOException, SQLException, WebHttpSupport.RequestException
    {
        if(collection)
        {
            WebHttpSupport.notFound(exchange);
            return;
        }
        if(WebHttpSupport.hasRequestBody(exchange))
        {
            throw new WebHttpSupport.RequestException(400, "invalid_request",
                "DELETE requests cannot include a body");
        }
        WebAccessAccount deleted = mAccessService.deleteUser(username);
        mAuthenticationService.invalidateAccountSessions(deleted.username());
        WebHttpSupport.sendData(exchange, 200, Map.of("deleted", deleted.username()));
    }

    private static String usernameFromPath(String rawPath)
    {
        String prefix = PATH + "/";
        if(rawPath == null || !rawPath.startsWith(prefix))
        {
            return null;
        }
        String segment = rawPath.substring(prefix.length());
        if(segment.isBlank() || segment.length() > 768 || segment.contains("/"))
        {
            return null;
        }
        try
        {
            String decoded = ApiRequestDecoder.decodeComponent(segment, false);
            return decoded.isBlank() || decoded.contains("/") || decoded.contains("\\") || decoded.contains("%") ||
                decoded.contains("+") ? null : decoded;
        }
        catch(IllegalArgumentException exception)
        {
            return null;
        }
    }
}
