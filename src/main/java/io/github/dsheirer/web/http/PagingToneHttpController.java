/* Copyright (C) 2026. Licensed under GPL-3.0-or-later. */
package io.github.dsheirer.web.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import io.github.dsheirer.audio.tone.PagingToneSettingsService;
import java.io.IOException;
import java.util.List;
import java.util.Set;

/** Administrator-only receiver paging-analysis preference. */
public final class PagingToneHttpController
{
    public static final String PATH = "/api/v1/admin/paging-tone-detection";
    private final PagingToneSettingsService mService;
    public PagingToneHttpController(PagingToneSettingsService service) { mService = service; }

    public void handle(HttpExchange exchange) throws IOException
    {
        WebRequestSecurity.prepareSecurityHeaders(exchange);
        if(!WebHttpSupport.requireNoQuery(exchange)) return;
        String path = exchange.getRequestURI().getRawPath();
        try
        {
            if(PATH.equals(path))
            {
                if(!"GET".equals(exchange.getRequestMethod()))
                { WebHttpSupport.methodNotAllowed(exchange, "GET"); return; }
                if(WebHttpSupport.hasRequestBody(exchange))
                    throw new WebHttpSupport.RequestException(400, "invalid_request", "GET requests cannot include a body");
                send(exchange, 200, mService.snapshot());
                return;
            }
            if(!(PATH + "/enabled").equals(path)) { WebHttpSupport.notFound(exchange); return; }
            if(!"PUT".equals(exchange.getRequestMethod()))
            { WebHttpSupport.methodNotAllowed(exchange, "PUT"); return; }
            List<String> revisions = exchange.getRequestHeaders().get("If-Match");
            if(revisions == null || revisions.isEmpty())
                throw new WebHttpSupport.RequestException(428, "revision_required", "Reload audio settings before saving");
            if(revisions.size() != 1 || !revisions.getFirst().matches("\"[0-9a-f]{64}\""))
                throw new WebHttpSupport.RequestException(400, "invalid_revision", "Reload audio settings before saving");
            JsonNode body = WebHttpSupport.readJsonObject(exchange, Set.of("value"));
            if(body.size() != 1 || !body.path("value").isBoolean())
                throw new IllegalArgumentException("Paging-tone detection must be on or off");
            var result = mService.setEnabled(revisions.getFirst().substring(1, 65), body.path("value").booleanValue());
            send(exchange, result.updated() ? 200 : 409, result.snapshot());
        }
        catch(WebHttpSupport.RequestException exception)
        { ApiHttpResponse.sendError(exchange, exception.status(), exception.code(), exception.getMessage()); }
        catch(IllegalArgumentException exception)
        { ApiHttpResponse.sendError(exchange, 422, "invalid_paging_tone_setting", "Choose on or off for paging-tone detection"); }
        catch(IOException exception)
        { ApiHttpResponse.sendError(exchange, 500, "paging_tone_save_failed", "The paging-tone setting could not be saved. Reload and try again"); }
    }

    private static void send(HttpExchange exchange, int status, PagingToneSettingsService.Snapshot snapshot)
        throws IOException
    {
        exchange.getResponseHeaders().set("ETag", '"' + snapshot.revision() + '"');
        ApiHttpResponse.sendDocument(exchange, status, snapshot);
    }
}
