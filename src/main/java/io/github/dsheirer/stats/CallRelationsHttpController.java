/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats;

import com.sun.net.httpserver.HttpExchange;
import io.github.dsheirer.web.auth.WebCapability;
import io.github.dsheirer.web.http.ApiHttpResponse;
import io.github.dsheirer.web.http.WebRequestSecurity;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Set;

/** The source and every target dataset use the requester's current receiver access policy. */
final class CallRelationsHttpController
{
    static final String PATH = "/api/v1/call-relations";
    private static final Set<WebCapability> DATASETS = Set.of(WebCapability.RADIO_VIEW, WebCapability.RECORDINGS_VIEW);
    private final CallRelationsService mRelations;
    private final WebRequestSecurity mSecurity;

    CallRelationsHttpController(CallRelationsService relations, WebRequestSecurity security)
    {
        mRelations = relations; mSecurity = security;
    }

    void handle(HttpExchange exchange) throws IOException
    {
        WebRequestSecurity.prepareSecurityHeaders(exchange);
        if(!"GET".equals(exchange.getRequestMethod()))
        {
            exchange.getResponseHeaders().set("Allow", "GET");
            ApiHttpResponse.sendError(exchange, 405, "method_not_allowed", "Open related calls from Activity or Recordings and try again.");
            return;
        }
        if(exchange.getRequestURI().getRawQuery() != null)
        {
            ApiHttpResponse.sendError(exchange, 400, "unknown_parameter", "Open related calls from Activity or Recordings and try again.");
            return;
        }
        String path = exchange.getRequestURI().getRawPath();
        String[] segments = path.startsWith(PATH + "/") ? path.substring(PATH.length() + 1).split("/", -1) : new String[0];
        if(segments.length != 2 || !Set.of("activity", "recording").contains(segments[0]))
        { notFound(exchange); return; }
        long id;
        try
        {
            if(!segments[1].matches("[1-9][0-9]*")) throw new NumberFormatException();
            id = Long.parseLong(segments[1]);
        }
        catch(NumberFormatException ignored)
        {
            ApiHttpResponse.sendError(exchange, 400, "invalid_id", "Choose a valid call and try again.");
            return;
        }
        boolean activitySource = segments[0].equals("activity");
        WebCapability sourceCapability = activitySource ? WebCapability.RADIO_VIEW : WebCapability.RECORDINGS_VIEW;
        // Check the source first, then register both logical resources for independent target checks.
        mSecurity.protect(sourceCapability, sourceExchange -> mSecurity.protectAny(DATASETS, allowed -> {
            try
            {
                boolean activityAllowed = mSecurity.isRequestStillAuthorized(allowed, WebCapability.RADIO_VIEW);
                boolean recordingsAllowed = mSecurity.isRequestStillAuthorized(allowed, WebCapability.RECORDINGS_VIEW);
                if(activitySource && !activityAllowed || !activitySource && !recordingsAllowed)
                {
                    ApiHttpResponse.sendError(allowed, 403, "access_denied", "Access is denied.");
                    return;
                }
                var result = activitySource ? mRelations.forActivity(id, activityAllowed, recordingsAllowed) :
                    mRelations.forRecording(id, activityAllowed, recordingsAllowed);
                if(result == null) notFound(allowed); else ApiHttpResponse.sendData(allowed, 200, result);
            }
            catch(SQLException exception)
            {
                ApiHttpResponse.sendError(allowed, 503, "related_content_unavailable",
                    "Related calls are temporarily unavailable. Try again later.");
            }
        }).handle(sourceExchange)).handle(exchange);
    }

    private static void notFound(HttpExchange exchange) throws IOException
    { ApiHttpResponse.sendError(exchange, 404, "not_found", "This call is no longer available."); }
}
