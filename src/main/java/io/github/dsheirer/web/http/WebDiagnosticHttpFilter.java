/*
 * Copyright (C) 2026 Dennis Sheirer
 */
package io.github.dsheirer.web.http;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.github.dsheirer.support.WebDiagnosticLogService;
import io.github.dsheirer.support.WebDiagnosticRequestMetrics;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;

/** Observes the HTTP boundary without retaining exchanges, bodies, headers, addresses, or raw request URLs. */
public final class WebDiagnosticHttpFilter extends Filter
{
    private static final Set<String> METHODS = Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
    private static final Set<String> OPERATIONS = Set.of("settings", "state", "start", "stop", "restart", "reset",
        "recover", "enable", "disable", "enabled", "refresh", "import", "export", "download", "catalog",
        "preview", "apply", "configuration", "gain", "frequency", "sample-rate", "allocation", "spectrum",
        "events", "messages", "status", "audio", "calls", "users", "accounts", "test", "cancel", "save",
        "delete", "connect", "disconnect", "resolve", "clear", "restore", "auto-start");
    private final WebDiagnosticLogService mService;

    public WebDiagnosticHttpFilter(WebDiagnosticLogService service)
    {
        mService = Objects.requireNonNull(service);
    }

    @Override public String description() { return "Bounded web diagnostic observation"; }

    @Override
    public void doFilter(HttpExchange exchange, Chain chain) throws IOException
    {
        long generation = mService.captureGeneration();
        if(generation == 0)
        {
            chain.doFilter(exchange);
            return;
        }
        // Viewer traffic is summarized periodically, so reading a log cannot recursively log every poll.
        String method = METHODS.contains(exchange.getRequestMethod()) ? exchange.getRequestMethod() : "OTHER";
        String context = exchange.getHttpContext().getPath();
        if(("GET".equals(method) || "HEAD".equals(method)) &&
            (WebDiagnosticLogHttpController.PATH.equals(context) || ApplicationLogHttpController.PATH.equals(context)))
        {
            WebDiagnosticRequestMetrics metrics = mService.viewerMetrics();
            WebDiagnosticRequestMetrics.Ticket ticket = metrics.begin(viewerOperation(context,
                exchange.getRequestURI().getRawPath()), generation);
            long startedAt = System.nanoTime();
            boolean failed = true;
            try
            {
                chain.doFilter(exchange);
                failed = false;
            }
            finally
            {
                metrics.finish(ticket, System.nanoTime() - startedAt, exchange.getResponseCode(), failed,
                    declaredResponseBytes(exchange));
            }
            return;
        }
        String route = normalizedRoute(context, exchange.getRequestURI().getRawPath());
        long requestId = mService.requestStarted(method, route);
        long startedAt = System.nanoTime();
        boolean failed = true;
        try
        {
            chain.doFilter(exchange);
            failed = false;
        }
        finally
        {
            mService.requestFinished(requestId, method, route, System.nanoTime() - startedAt,
                exchange.getResponseCode(), failed);
        }
    }

    static String viewerOperation(String context, String rawPath)
    {
        if(ApplicationLogHttpController.PATH.equals(context) && context.equals(rawPath))
            return "application_log_read";
        if(WebDiagnosticLogHttpController.PATH.equals(context))
        {
            if(context.equals(rawPath)) return "web_log_status";
            if((context + "/entries").equals(rawPath)) return "web_log_read";
            if((context + "/download").equals(rawPath)) return "web_log_download";
        }
        return "unknown_log_request";
    }

    private static long declaredResponseBytes(HttpExchange exchange)
    {
        String value = exchange.getResponseHeaders().getFirst("Content-Length");
        if(value == null || value.length() > 20) return -1;
        try
        {
            long bytes = Long.parseLong(value);
            return bytes >= 0 ? bytes : -1;
        }
        catch(NumberFormatException exception) { return -1; }
    }

    static String normalizedRoute(String context, String rawPath)
    {
        // Contexts are code-owned. An allowlist preserves recognizable operation names while replacing values,
        // including filenames and IDs. Queries and decoded user text never enter the logging service.
        if("/".equals(context)) return "/web-assets";
        if(rawPath == null || rawPath.length() > 2048 || !rawPath.startsWith(context)) return context;
        String suffix = rawPath.substring(context.length());
        if(suffix.isEmpty()) return context;
        StringBuilder normalized = new StringBuilder(context);
        int segments = 0;
        for(String part: suffix.split("/", 10))
        {
            if(part.isEmpty()) continue;
            if(++segments > 8) break;
            if(normalized.charAt(normalized.length() - 1) != '/') normalized.append('/');
            normalized.append(OPERATIONS.contains(part) ? part : "{value}");
        }
        return normalized.toString();
    }

    /**
     * Decorates context registration only. Start and retain the original server: long-lived handlers compare the
     * real HttpContext server identity with the active listener, including when the listener is HTTPS.
     */
    public static HttpServer instrumentContexts(HttpServer server, WebDiagnosticLogService service,
                                                 Filter... responseFilters)
    {
        Objects.requireNonNull(server);
        Objects.requireNonNull(service);
        List<Filter> filters = List.of(responseFilters);
        return new HttpServer()
        {
            @Override public void bind(InetSocketAddress address, int backlog) throws IOException
            { server.bind(address, backlog); }
            @Override public void start() { server.start(); }
            @Override public void setExecutor(Executor executor) { server.setExecutor(executor); }
            @Override public Executor getExecutor() { return server.getExecutor(); }
            @Override public void stop(int delay) { server.stop(delay); }
            @Override public HttpContext createContext(String path, HttpHandler handler)
            { return instrument(server.createContext(path, handler)); }
            @Override public HttpContext createContext(String path) { return instrument(server.createContext(path)); }
            @Override public void removeContext(String path) { server.removeContext(path); }
            @Override public void removeContext(HttpContext context) { server.removeContext(context); }
            @Override public InetSocketAddress getAddress() { return server.getAddress(); }

            private HttpContext instrument(HttpContext context)
            {
                context.getFilters().addAll(filters);
                context.getFilters().add(new WebDiagnosticHttpFilter(service));
                return context;
            }
        };
    }
}
