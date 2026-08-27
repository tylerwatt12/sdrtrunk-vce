/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.http;

import com.sun.net.httpserver.HttpExchange;
import io.github.dsheirer.web.auth.WebAccessAccount;
import io.github.dsheirer.web.auth.WebUserPreferencesService;
import io.github.dsheirer.web.settings.WebUserPreferences;
import io.github.dsheirer.web.settings.WebUserPreferencesCodec;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Exact signed-in-user endpoint for one complete, revisioned preference document. */
public final class WebUserPreferencesHttpController
{
    public static final String PATH = "/api/v1/me/preferences";
    private static final Pattern ETAG = Pattern.compile("\"([1-9][0-9]*)\"");
    private static final Logger mLog = LoggerFactory.getLogger(WebUserPreferencesHttpController.class);
    private final WebRequestSecurity mSecurity;
    private final WebUserPreferencesService mPreferences;

    public WebUserPreferencesHttpController(WebRequestSecurity security, WebUserPreferencesService preferences)
    {
        mSecurity = Objects.requireNonNull(security, "Web request security cannot be null");
        mPreferences = Objects.requireNonNull(preferences, "Web user preference service cannot be null");
    }

    public void handle(HttpExchange exchange) throws IOException
    {
        WebRequestSecurity.prepareSecurityHeaders(exchange);
        if(!PATH.equals(exchange.getRequestURI().getRawPath()))
        {
            ApiHttpResponse.sendError(exchange, 404, "not_found", "Not found");
            return;
        }
        if(exchange.getRequestURI().getRawQuery() != null)
        {
            ApiHttpResponse.sendError(exchange, 400, "unknown_parameter", "Query parameters are not supported");
            return;
        }

        WebAccessAccount account = mSecurity.authenticatedAccount(exchange).orElse(null);
        if(account == null)
        {
            ApiHttpResponse.sendError(exchange, 401, "authentication_required", "Authentication is required");
            return;
        }

        try
        {
            switch(exchange.getRequestMethod())
            {
                case "GET" -> get(exchange, account);
                case "PUT" -> put(exchange, account);
                default -> {
                    exchange.getResponseHeaders().set("Allow", "GET, PUT");
                    ApiHttpResponse.sendError(exchange, 405, "method_not_allowed", "Method not allowed");
                }
            }
        }
        catch(RequestException exception)
        {
            ApiHttpResponse.sendError(exchange, exception.status(), exception.code(), exception.getMessage());
        }
        catch(SQLException exception)
        {
            mLog.error("Unable to access web user preferences", exception);
            ApiHttpResponse.sendError(exchange, 500, "preferences_unavailable",
                "User preferences are temporarily unavailable");
        }
    }

    private void get(HttpExchange exchange, WebAccessAccount account)
        throws IOException, SQLException, RequestException
    {
        requireEmptyBody(exchange);
        WebUserPreferencesService.Snapshot snapshot;
        try
        {
            snapshot = mPreferences.get(account);
        }
        catch(IOException exception)
        {
            mLog.error("Unable to load web user preferences", exception);
            ApiHttpResponse.sendError(exchange, 500, "preferences_unavailable",
                "User preferences are temporarily unavailable");
            return;
        }
        send(exchange, snapshot);
    }

    private void put(HttpExchange exchange, WebAccessAccount account)
        throws IOException, SQLException, RequestException
    {
        long expectedRevision = requireRevision(exchange);
        WebUserPreferences preferences = readPreferences(exchange);

        try
        {
            send(exchange, mPreferences.update(account, expectedRevision, preferences));
        }
        catch(WebUserPreferencesService.RevisionConflictException exception)
        {
            exchange.getResponseHeaders().set("ETag", quoteRevision(exception.currentRevision()));
            ApiHttpResponse.sendError(exchange, 409, "preference_conflict",
                "User preferences changed since they were loaded");
        }
        catch(IOException exception)
        {
            mLog.error("Unable to save web user preferences", exception);
            ApiHttpResponse.sendError(exchange, 500, "preferences_unavailable",
                "User preferences are temporarily unavailable");
        }
    }

    private static void send(HttpExchange exchange, WebUserPreferencesService.Snapshot snapshot) throws IOException
    {
        exchange.getResponseHeaders().set("ETag", quoteRevision(snapshot.revision()));
        ApiHttpResponse.sendDocument(exchange, 200, snapshot);
    }

    private static long requireRevision(HttpExchange exchange) throws RequestException
    {
        List<String> values = exchange.getRequestHeaders().get("If-Match");
        if(values == null || values.isEmpty())
        {
            throw new RequestException(428, "revision_required", "If-Match is required");
        }
        if(values.size() != 1)
        {
            throw new RequestException(400, "invalid_revision", "If-Match must contain one quoted revision");
        }

        Matcher matcher = ETAG.matcher(values.get(0));
        if(!matcher.matches())
        {
            throw new RequestException(400, "invalid_revision", "If-Match must contain one quoted revision");
        }
        try
        {
            return Long.parseLong(matcher.group(1));
        }
        catch(NumberFormatException exception)
        {
            throw new RequestException(400, "invalid_revision", "If-Match revision is too large");
        }
    }

    private static WebUserPreferences readPreferences(HttpExchange exchange) throws IOException, RequestException
    {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if(contentType == null || !"application/json".equals(contentType.toLowerCase(Locale.ROOT)
            .split(";", 2)[0].strip()))
        {
            throw new RequestException(415, "invalid_content_type", "Content-Type must be application/json");
        }

        String contentLength = exchange.getRequestHeaders().getFirst("Content-Length");
        if(contentLength != null)
        {
            try
            {
                long length = Long.parseLong(contentLength);
                if(length < 0)
                {
                    throw new RequestException(400, "invalid_request", "Content-Length is invalid");
                }
                if(length > WebUserPreferences.MAXIMUM_JSON_BYTES)
                {
                    throw new RequestException(413, "request_too_large", "Request body is too large");
                }
            }
            catch(NumberFormatException exception)
            {
                throw new RequestException(400, "invalid_request", "Content-Length is invalid");
            }
        }

        byte[] body = ApiRequestDecoder.readBody(exchange, WebUserPreferences.MAXIMUM_JSON_BYTES);
        try
        {
            if(body.length == 0)
            {
                throw new RequestException(400, "invalid_request", "Request body is required");
            }
            if(body.length > WebUserPreferences.MAXIMUM_JSON_BYTES)
            {
                throw new RequestException(413, "request_too_large", "Request body is too large");
            }
            try
            {
                return WebUserPreferencesCodec.decode(body);
            }
            catch(IOException exception)
            {
                throw new RequestException(422, "invalid_preferences", "User preferences are invalid");
            }
        }
        finally
        {
            Arrays.fill(body, (byte)0);
        }
    }

    private static void requireEmptyBody(HttpExchange exchange) throws RequestException
    {
        String contentLength = exchange.getRequestHeaders().getFirst("Content-Length");
        if(contentLength != null && !"0".equals(contentLength) ||
            exchange.getRequestHeaders().containsKey("Transfer-Encoding"))
        {
            throw new RequestException(400, "invalid_request", "GET requests cannot include a body");
        }
    }

    private static String quoteRevision(long revision)
    {
        return '"' + Long.toString(revision) + '"';
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
