/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.web.http;

import com.sun.net.httpserver.HttpExchange;
import io.github.dsheirer.support.ApplicationLogService;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Read-only application-file snapshots. The server registers this handler behind ADMIN_SETTINGS authorization. */
public final class ApplicationLogHttpController
{
    public static final String PATH = "/api/v1/application-log";
    private static final int MAXIMUM_QUERY_CHARACTERS = 256;
    private final ApplicationLogService mService;

    public ApplicationLogHttpController(ApplicationLogService service)
    {
        mService = Objects.requireNonNull(service);
    }

    public void handle(HttpExchange exchange) throws IOException
    {
        if(!WebHttpSupport.hasExactPath(exchange, PATH))
        {
            WebHttpSupport.notFound(exchange);
            return;
        }
        if(!"GET".equals(exchange.getRequestMethod()))
        {
            WebHttpSupport.methodNotAllowed(exchange, "GET");
            return;
        }
        if(WebHttpSupport.hasRequestBody(exchange))
        {
            WebHttpSupport.sendError(exchange, 400, "invalid_request", "GET requests cannot include a body");
            return;
        }

        Map<String,String> query;
        try
        {
            query = query(exchange.getRequestURI().getRawQuery());
        }
        catch(IllegalArgumentException exception)
        {
            WebHttpSupport.sendError(exchange, 400, "invalid_request", "Application log parameters are invalid");
            return;
        }

        ApplicationLogService.Snapshot snapshot;
        try
        {
            snapshot = mService.snapshot(query.getOrDefault("log", "current"), query.get("after"),
                query.containsKey("compact") ? query.get("revision") : null);
        }
        catch(IllegalArgumentException exception)
        {
            WebHttpSupport.sendError(exchange, 400, "invalid_request", "Application log parameters are invalid");
            return;
        }
        catch(ApplicationLogService.BusyException exception)
        {
            exchange.getResponseHeaders().set("Retry-After", "2");
            WebHttpSupport.sendError(exchange, 503, "application_log_busy",
                "Application messages are temporarily unavailable. Try again shortly.");
            return;
        }
        catch(IOException exception)
        {
            WebHttpSupport.sendError(exchange, 503, "application_log_unavailable",
                "Application messages could not be read. Try again shortly.");
            return;
        }
        if(!query.containsKey("compact"))
        {
            WebHttpSupport.sendData(exchange, 200, snapshot);
            return;
        }
        // File-reader admission has already been released. A blocked or disconnected browser cannot hold it.
        WebHttpSupport.sendData(exchange, 200, new Document(snapshot.log(), snapshot.fileName(), snapshot.available(),
            snapshot.entries().stream().map(entry -> new WireEntry(entry.id(), entry.time(), entry.level(),
                entry.source(), entry.message(), entry.details(), headerPrefix(entry),
                entry.text().indexOf('\n') >= 0, entry.truncated())).toList(), snapshot.updatedAt(), snapshot.maxEntries(), snapshot.truncated(),
            snapshot.latestId(), snapshot.gap(), snapshot.changeReason(), snapshot.revision(),
            snapshot.incremental(), snapshot.firstId()));
    }

    private static Map<String,String> query(String raw)
    {
        Map<String,String> values = new LinkedHashMap<>();
        if(raw == null) return values;
        if(raw.isEmpty() || raw.length() > MAXIMUM_QUERY_CHARACTERS)
        {
            throw new IllegalArgumentException("Invalid query");
        }
        for(String part: raw.split("&", -1))
        {
            String[] pair = part.split("=", 2);
            String name = ApiRequestDecoder.decodeComponent(pair[0], true);
            String value = pair.length == 2 ? ApiRequestDecoder.decodeComponent(pair[1], true) : "";
            if((!"log".equals(name) && !"after".equals(name) && !"revision".equals(name) && !"compact".equals(name)) || value.isEmpty() ||
                values.putIfAbsent(name, value) != null)
            {
                throw new IllegalArgumentException("Invalid query");
            }
        }
        if(values.containsKey("compact") && !"true".equals(values.get("compact")) ||
            values.containsKey("revision") && !values.containsKey("compact"))
        {
            throw new IllegalArgumentException("Invalid compact protocol");
        }
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
