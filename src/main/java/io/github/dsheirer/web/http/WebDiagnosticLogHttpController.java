/* Copyright (C) 2026. Licensed under GPL-3.0-or-later. */
package io.github.dsheirer.web.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import io.github.dsheirer.support.ApplicationLogService;
import io.github.dsheirer.support.WebDiagnosticLogService;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Registered behind ADMIN_SETTINGS authorization and mutation CSRF checks by the web server. */
public final class WebDiagnosticLogHttpController
{
    public static final String PATH = "/api/v1/admin/web-diagnostic-log";
    private final WebDiagnosticLogService mService;

    public WebDiagnosticLogHttpController(WebDiagnosticLogService service)
    {
        mService = Objects.requireNonNull(service);
    }

    public void handle(HttpExchange exchange) throws IOException
    {
        WebRequestSecurity.prepareSecurityHeaders(exchange);
        String path = exchange.getRequestURI().getRawPath();
        if(!PATH.equals(path) && !(PATH + "/entries").equals(path) && !(PATH + "/download").equals(path))
        { WebHttpSupport.notFound(exchange); return; }
        String method = exchange.getRequestMethod();
        if(!"GET".equals(method) && !(PATH.equals(path) && "PUT".equals(method)))
        { WebHttpSupport.methodNotAllowed(exchange, PATH.equals(path) ? "GET, PUT" : "GET"); return; }
        if("GET".equals(method) && WebHttpSupport.hasRequestBody(exchange))
        { WebHttpSupport.sendError(exchange, 400, "invalid_request", "GET requests cannot include a body"); return; }
        if(!(PATH + "/entries").equals(path) && !WebHttpSupport.requireNoQuery(exchange)) return;
        try
        {
            if(PATH.equals(path))
            {
                if("PUT".equals(method))
                {
                    JsonNode request = WebHttpSupport.readJsonObject(exchange, Set.of("enabled"));
                    if(request.size() != 1 || !request.path("enabled").isBoolean())
                        throw new WebHttpSupport.RequestException(400, "invalid_request", "Choose whether diagnostic logging is on or off");
                    WebHttpSupport.sendData(exchange, 200, mService.setEnabled(request.path("enabled").booleanValue()));
                }
                else WebHttpSupport.sendData(exchange, 200, mService.status());
            }
            else if(path.endsWith("/entries")) entries(exchange);
            else download(exchange);
        }
        catch(WebHttpSupport.RequestException exception)
        { WebHttpSupport.sendError(exchange, exception.status(), exception.code(), exception.getMessage()); }
        catch(IllegalArgumentException exception)
        { WebHttpSupport.sendError(exchange, 400, "invalid_request", "Diagnostic log parameters are invalid"); }
        catch(ApplicationLogService.BusyException exception)
        {
            exchange.getResponseHeaders().set("Retry-After", "2");
            WebHttpSupport.sendError(exchange, 503, "web_diagnostic_log_busy",
                "Diagnostic logs are temporarily unavailable. Try again shortly.");
        }
        catch(IllegalStateException | IOException exception)
        {
            if(exchange.getResponseCode() < 0 || exchange.getResponseCode() == 0)
                WebHttpSupport.sendError(exchange, 503, "web_diagnostic_log_unavailable",
                    "Diagnostic logs could not be read or saved. Check the application log folder and try again.");
            else throw new IOException("Diagnostic response could not be sent", exception);
        }
    }

    private void entries(HttpExchange exchange) throws IOException
    {
        Map<String,String> query = query(exchange.getRequestURI().getRawQuery());
        ApplicationLogService.Snapshot value = mService.snapshot(query.getOrDefault("log", "current"), query.get("after"),
            query.containsKey("compact") ? query.get("revision") : null);
        if(!query.containsKey("compact"))
        {
            WebHttpSupport.sendData(exchange, 200, value);
            return;
        }
        WebHttpSupport.sendData(exchange, 200, new Document(value.log(), value.fileName(), value.available(),
            value.entries().stream().map(entry -> new WireEntry(entry.id(), entry.time(), entry.level(), entry.source(),
                entry.message(), entry.details(), headerPrefix(entry), entry.text().indexOf('\n') >= 0,
                entry.truncated())).toList(), value.updatedAt(), value.maxEntries(), value.truncated(), value.latestId(),
            value.gap(), value.changeReason(), value.revision(), value.incremental(), value.firstId()));
    }

    private void download(HttpExchange exchange) throws IOException
    {
        // ZIP preparation releases its read permit before a browser receives any bytes.
        try(WebDiagnosticLogService.Download download = mService.download())
        {
            exchange.getResponseHeaders().set("Content-Type", "application/zip");
            exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"sdrtrunk_web_diagnostics.zip\"");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("Vary", "Cookie");
            exchange.sendResponseHeaders(200, Files.size(download.path()));
            try(OutputStream output = exchange.getResponseBody()) { Files.copy(download.path(), output); }
        }
    }

    private static Map<String,String> query(String raw)
    {
        Map<String,String> values = new LinkedHashMap<>();
        if(raw == null) return values;
        if(raw.isEmpty() || raw.length() > 256) throw new IllegalArgumentException("Invalid query");
        for(String part: raw.split("&", -1))
        {
            String[] pair = part.split("=", 2);
            String name = ApiRequestDecoder.decodeComponent(pair[0], true);
            String value = pair.length == 2 ? ApiRequestDecoder.decodeComponent(pair[1], true) : "";
            if(!Set.of("log", "after", "revision", "compact").contains(name) || value.isEmpty() ||
                values.putIfAbsent(name, value) != null) throw new IllegalArgumentException("Invalid query");
        }
        if(values.containsKey("compact") && !"true".equals(values.get("compact")) ||
            values.containsKey("revision") && !values.containsKey("compact"))
            throw new IllegalArgumentException("Invalid compact protocol");
        return values;
    }

    private static String headerPrefix(ApplicationLogService.Entry entry)
    {
        String first = entry.text().split("\n", 2)[0];
        return first.substring(0, first.length() - entry.message().length());
    }

    private record WireEntry(String id, String time, String level, String source, String message, String details,
                             String headerPrefix, boolean multiline, boolean truncated) { }
    private record Document(String log, String fileName, boolean available, List<WireEntry> entries, long updatedAt,
                            int maxEntries, boolean truncated, String latestId, boolean gap, String changeReason,
                            String revision, boolean incremental, String firstId) { }
}
