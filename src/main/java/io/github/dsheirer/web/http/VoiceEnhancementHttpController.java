/* Copyright (C) 2026. Licensed under GPL-3.0-or-later. */
package io.github.dsheirer.web.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import io.github.dsheirer.audio.enhance.VoiceEnhancementService;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Administrator-only model selection and bounded data-only bundle upload. */
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
            if(!(PATH + "/enabled").equals(path) && !(PATH + "/model").equals(path))
            { WebHttpSupport.notFound(exchange); return; }
            String method = path.endsWith("/enabled") ? "PUT" : "POST";
            if(!method.equals(exchange.getRequestMethod()))
            { WebHttpSupport.methodNotAllowed(exchange, method); return; }
            String revision = revision(exchange);
            VoiceEnhancementService.UpdateResult result;
            if(path.endsWith("/enabled"))
            {
                JsonNode value = WebHttpSupport.readJsonObject(exchange, Set.of("value"));
                if(value.size() != 1 || !value.path("value").isBoolean())
                    throw new IllegalArgumentException("Voice enhancement must be on or off");
                result = mService.setEnabled(revision, value.path("value").booleanValue());
            }
            else
            {
                String type = exchange.getRequestHeaders().getFirst("Content-Type");
                if(type == null || !type.toLowerCase(Locale.ROOT).split(";", 2)[0].strip().equals("application/zip"))
                    throw new WebHttpSupport.RequestException(415, "invalid_content_type", "Choose a model ZIP file");
                String size = exchange.getRequestHeaders().getFirst("Content-Length");
                if(size != null)
                {
                    long length;
                    try { length = Long.parseLong(size); }
                    catch(NumberFormatException exception) { throw new IllegalArgumentException("Invalid upload size"); }
                    if(length < 1 || length > VoiceEnhancementService.MAXIMUM_UPLOAD_BYTES)
                        throw new WebHttpSupport.RequestException(413, "request_too_large", "Choose a model ZIP smaller than 16 MB");
                }
                byte[] bytes = ApiRequestDecoder.readBody(exchange, VoiceEnhancementService.MAXIMUM_UPLOAD_BYTES);
                if(bytes.length > VoiceEnhancementService.MAXIMUM_UPLOAD_BYTES)
                    throw new WebHttpSupport.RequestException(413, "request_too_large", "Choose a model ZIP smaller than 16 MB");
                result = mService.install(revision, bytes);
            }
            send(exchange, result.updated() ? 200 : 409, result.snapshot());
        }
        catch(WebHttpSupport.RequestException exception)
        { ApiHttpResponse.sendError(exchange, exception.status(), exception.code(), exception.getMessage()); }
        catch(VoiceEnhancementService.BusyException exception)
        { ApiHttpResponse.sendError(exchange, 503, "model_busy", "A model update is in progress. Try again shortly"); }
        catch(IllegalArgumentException exception)
        { ApiHttpResponse.sendError(exchange, 422, "invalid_voice_model", "The model or setting is invalid. Choose a compatible model ZIP"); }
        catch(IOException exception)
        { ApiHttpResponse.sendError(exchange, 500, "voice_model_save_failed", "The model could not be saved. Check available disk space and try again"); }
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
