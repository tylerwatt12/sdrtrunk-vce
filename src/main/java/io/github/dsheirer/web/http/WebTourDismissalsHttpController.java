/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.web.http;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import io.github.dsheirer.web.auth.WebAccessAccount;
import io.github.dsheirer.web.auth.WebAccessSession;
import io.github.dsheirer.web.auth.WebTourDismissalService;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Exact own-account tour history endpoint with additive, CSRF-protected updates. */
public final class WebTourDismissalsHttpController
{
    public static final String PATH = "/api/v1/me/tour-dismissals";
    private static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Logger mLog = LoggerFactory.getLogger(WebTourDismissalsHttpController.class);
    private final WebRequestSecurity mSecurity;
    private final WebTourDismissalService mService;

    public WebTourDismissalsHttpController(WebRequestSecurity security, WebTourDismissalService service)
    {
        mSecurity = Objects.requireNonNull(security);
        mService = Objects.requireNonNull(service);
    }

    public void handle(HttpExchange exchange) throws IOException
    {
        WebRequestSecurity.prepareSecurityHeaders(exchange);
        if(!WebHttpSupport.hasExactPath(exchange, PATH))
        {
            WebHttpSupport.notFound(exchange);
            return;
        }
        if(!WebHttpSupport.requireNoQuery(exchange)) return;
        WebAccessAccount account = mSecurity.requestSession(exchange).map(WebAccessSession::account).orElse(null);
        if(account == null)
        {
            ApiHttpResponse.sendError(exchange, 401, "authentication_required", "Authentication is required");
            return;
        }
        try
        {
            switch(exchange.getRequestMethod())
            {
                case "GET" -> {
                    if(WebHttpSupport.hasRequestBody(exchange) || !WebRequestSecurity.hasSafeGetOrigin(exchange))
                        ApiHttpResponse.sendError(exchange, 400, "request_rejected", "The tour history request was rejected");
                    else ApiHttpResponse.sendData(exchange, 200, mService.get(account));
                }
                case "PUT" -> {
                    if(!mSecurity.authorizeMutation(exchange))
                        ApiHttpResponse.sendError(exchange, 403, "request_rejected", "The change request was rejected");
                    else merge(exchange, account);
                }
                default -> {
                    exchange.getResponseHeaders().set("Allow", "GET, PUT");
                    ApiHttpResponse.sendError(exchange, 405, "method_not_allowed", "Method not allowed");
                }
            }
        }
        catch(SQLException | IOException exception)
        {
            mLog.error("Unable to access account tour history", exception);
            ApiHttpResponse.sendError(exchange, 500, "tour_history_unavailable", "Tour history is temporarily unavailable");
        }
        catch(IllegalArgumentException exception)
        {
            ApiHttpResponse.sendError(exchange, 422, "invalid_tour_dismissals", "Tour history is invalid or exceeds its limit");
        }
    }

    private void merge(HttpExchange exchange, WebAccessAccount account) throws IOException, SQLException
    {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if(contentType == null || !"application/json".equals(contentType.toLowerCase(Locale.ROOT).split(";", 2)[0].strip()))
        {
            ApiHttpResponse.sendError(exchange, 415, "invalid_content_type", "Content-Type must be application/json");
            return;
        }
        byte[] body = ApiRequestDecoder.readBody(exchange, WebTourDismissalService.MAXIMUM_JSON_BYTES);
        try
        {
            if(body.length > WebTourDismissalService.MAXIMUM_JSON_BYTES)
            {
                ApiHttpResponse.sendError(exchange, 413, "request_too_large", "Request body is too large");
                return;
            }
            WebTourDismissalService.Snapshot incoming;
            try
            {
                JsonNode document = MAPPER.readTree(body);
                if(document == null || !document.isObject() || document.size() != 2 ||
                    !document.has("version") || !document.get("version").isIntegralNumber() ||
                    !document.get("version").canConvertToInt() || document.get("version").intValue() != 1 ||
                    !document.has("dismissed") || !document.get("dismissed").isObject())
                    throw new IllegalArgumentException("Invalid tour history document");
                Map<String,Integer> dismissed = new LinkedHashMap<>();
                var fields = document.get("dismissed").fields();
                while(fields.hasNext())
                {
                    var field = fields.next();
                    if(!field.getValue().isIntegralNumber() || !field.getValue().canConvertToInt())
                        throw new IllegalArgumentException("Tour version must be an integer");
                    dismissed.put(field.getKey(), field.getValue().intValue());
                }
                incoming = new WebTourDismissalService.Snapshot(1, dismissed);
            }
            catch(IOException | IllegalArgumentException exception)
            {
                ApiHttpResponse.sendError(exchange, 422, "invalid_tour_dismissals", "Tour history is invalid or exceeds its limit");
                return;
            }
            ApiHttpResponse.sendData(exchange, 200, mService.merge(account, incoming));
        }
        finally { Arrays.fill(body, (byte)0); }
    }
}
