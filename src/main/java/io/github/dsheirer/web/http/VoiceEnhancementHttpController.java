/* Copyright (C) 2026. Licensed under GPL-3.0-or-later. */
package io.github.dsheirer.web.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import io.github.dsheirer.audio.enhance.VoiceEnhancementService;
import java.io.IOException;
import java.util.List;
import java.util.Set;

/** Administrator-only enhancement setting and the selected JMBE library's embedded model status. */
public final class VoiceEnhancementHttpController
{
    public static final String PATH = "/api/v1/admin/voice-enhancement";
    private final VoiceEnhancementService mService;
    public VoiceEnhancementHttpController(VoiceEnhancementService service) { mService = service; }

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
            if(!(PATH + "/enabled").equals(path))
            { WebHttpSupport.notFound(exchange); return; }
            if(!"PUT".equals(exchange.getRequestMethod()))
            { WebHttpSupport.methodNotAllowed(exchange, "PUT"); return; }
            String revision = revision(exchange);
            JsonNode value = WebHttpSupport.readJsonObject(exchange, Set.of("value"));
            if(value.size() != 1 || !value.path("value").isBoolean())
                throw new IllegalArgumentException("Voice enhancement must be on or off");
            VoiceEnhancementService.UpdateResult result = mService.setEnabled(revision,
                value.path("value").booleanValue());
            send(exchange, result.updated() ? 200 : 409, result.snapshot());
        }
        catch(WebHttpSupport.RequestException exception)
        { ApiHttpResponse.sendError(exchange, exception.status(), exception.code(), exception.getMessage()); }
        catch(IllegalArgumentException exception)
        { ApiHttpResponse.sendError(exchange, 422, "invalid_voice_enhancement", exception.getMessage()); }
        catch(IOException exception)
        { ApiHttpResponse.sendError(exchange, 500, "voice_enhancement_save_failed", "The setting could not be saved. Check available disk space and try again"); }
    }

    private static String revision(HttpExchange exchange) throws WebHttpSupport.RequestException
    {
        List<String> values = exchange.getRequestHeaders().get("If-Match");
        if(values == null || values.isEmpty())
            throw new WebHttpSupport.RequestException(428, "revision_required", "Reload audio settings before saving");
        if(values.size() != 1 || !values.getFirst().matches("\"[0-9a-f]{64}\""))
            throw new WebHttpSupport.RequestException(400, "invalid_revision", "Reload audio settings before saving");
        return values.getFirst().substring(1, 65);
    }

    private static void send(HttpExchange exchange, int status, VoiceEnhancementService.Snapshot snapshot)
        throws IOException
    {
        exchange.getResponseHeaders().set("ETag", '"' + snapshot.revision() + '"');
        ApiHttpResponse.sendDocument(exchange, status, snapshot);
    }
}
