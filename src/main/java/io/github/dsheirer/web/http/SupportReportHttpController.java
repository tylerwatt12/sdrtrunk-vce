/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */
package io.github.dsheirer.web.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import io.github.dsheirer.support.SupportBundleService;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.EnumSet;
import java.util.Set;

/** Administrator-only support report generation, download, cancellation, and submission endpoint. */
public final class SupportReportHttpController
{
    public static final String PATH = "/api/v1/admin/support-reports";
    private static final Set<String> REQUEST_FIELDS = Set.of("title", "email", "category", "issue",
        "description", "steps", "sections");
    private final SupportBundleService mService;

    public SupportReportHttpController(SupportBundleService service)
    {
        mService = service;
    }

    public void handle(HttpExchange exchange) throws IOException
    {
        String rawPath = exchange.getRequestURI().getRawPath();
        if(rawPath.equals(PATH))
        {
            if(!WebHttpSupport.requireNoQuery(exchange)) return;
            if(!"POST".equals(exchange.getRequestMethod()))
            {
                WebHttpSupport.methodNotAllowed(exchange, "POST");
                return;
            }
            create(exchange);
            return;
        }

        if(!rawPath.startsWith(PATH + "/"))
        {
            WebHttpSupport.notFound(exchange);
            return;
        }
        if(!WebHttpSupport.requireNoQuery(exchange)) return;
        String suffix = rawPath.substring(PATH.length() + 1);
        String[] parts = suffix.split("/", -1);
        if(parts.length == 1 && "GET".equals(exchange.getRequestMethod()))
        {
            status(exchange, parts[0]);
        }
        else if(parts.length == 2 && "cancel".equals(parts[1]) && "POST".equals(exchange.getRequestMethod()))
        {
            if(!requireEmptyBody(exchange)) return;
            cancel(exchange, parts[0]);
        }
        else if(parts.length == 2 && "submit".equals(parts[1]) && "POST".equals(exchange.getRequestMethod()))
        {
            if(!requireEmptyBody(exchange)) return;
            submit(exchange, parts[0]);
        }
        else if(parts.length == 2 && "download".equals(parts[1]) && "GET".equals(exchange.getRequestMethod()))
        {
            download(exchange, parts[0]);
        }
        else
        {
            WebHttpSupport.notFound(exchange);
        }
    }

    private void create(HttpExchange exchange) throws IOException
    {
        try
        {
            JsonNode request = WebHttpSupport.readJsonObject(exchange, REQUEST_FIELDS);
            JsonNode sectionValues = request.get("sections");
            if(sectionValues == null || !sectionValues.isArray() || sectionValues.isEmpty())
            {
                throw new IllegalArgumentException("Choose at least one item to include");
            }
            EnumSet<SupportBundleService.Section> sections = EnumSet.noneOf(SupportBundleService.Section.class);
            for(JsonNode section: sectionValues)
            {
                if(!section.isTextual()) throw new IllegalArgumentException("A bundle choice is invalid");
                sections.add(SupportBundleService.Section.fromId(section.textValue()));
            }
            SupportBundleService.Request bundleRequest = new SupportBundleService.Request(
                text(request, "title"), text(request, "email"), text(request, "category"),
                text(request, "issue"), text(request, "description"), text(request, "steps"), sections);
            String id = mService.generate(bundleRequest);
            ApiHttpResponse.sendData(exchange, 202, mService.status(id));
        }
        catch(WebHttpSupport.RequestException exception)
        {
            WebHttpSupport.sendError(exchange, exception.status(), exception.code(), exception.getMessage());
        }
        catch(IllegalArgumentException | IllegalStateException exception)
        {
            WebHttpSupport.sendError(exchange, 400, "invalid_support_report", exception.getMessage());
        }
    }

    private void status(HttpExchange exchange, String id) throws IOException
    {
        if(WebHttpSupport.hasRequestBody(exchange))
        {
            WebHttpSupport.sendError(exchange, 400, "invalid_request", "This request cannot include a body");
            return;
        }
        try
        {
            ApiHttpResponse.sendData(exchange, 200, mService.status(id));
        }
        catch(IllegalArgumentException exception)
        {
            WebHttpSupport.sendError(exchange, 404, "support_report_not_found", "Support report not found");
        }
    }

    private void download(HttpExchange exchange, String id) throws IOException
    {
        try
        {
            SupportBundleService.PreparedDownload download = mService.download(id);
            exchange.getResponseHeaders().set("Content-Type", "application/zip");
            exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" +
                download.fileName() + "\"");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, download.bytes());
            try(OutputStream output = exchange.getResponseBody())
            {
                Files.copy(download.path(), output);
            }
        }
        catch(IllegalArgumentException exception)
        {
            WebHttpSupport.sendError(exchange, 404, "support_report_not_found", "Support report not found");
        }
        catch(IllegalStateException exception)
        {
            WebHttpSupport.sendError(exchange, 409, "support_report_not_ready", exception.getMessage());
        }
    }

    private void cancel(HttpExchange exchange, String id) throws IOException
    {
        try
        {
            mService.cancel(id);
            ApiHttpResponse.sendData(exchange, 200, mService.status(id));
        }
        catch(IllegalArgumentException exception)
        {
            WebHttpSupport.sendError(exchange, 404, "support_report_not_found", "Support report not found");
        }
    }

    private void submit(HttpExchange exchange, String id) throws IOException
    {
        try
        {
            mService.submit(id);
            ApiHttpResponse.sendData(exchange, 202, mService.status(id));
        }
        catch(IllegalArgumentException exception)
        {
            WebHttpSupport.sendError(exchange, 404, "support_report_not_found", "Support report not found");
        }
        catch(IllegalStateException exception)
        {
            WebHttpSupport.sendError(exchange, 409, "support_report_not_ready", exception.getMessage());
        }
    }

    private static String text(JsonNode request, String field)
    {
        JsonNode value = request.get(field);
        return value != null && value.isTextual() ? value.textValue() : "";
    }

    private static boolean requireEmptyBody(HttpExchange exchange) throws IOException
    {
        if(WebHttpSupport.hasRequestBody(exchange))
        {
            WebHttpSupport.sendError(exchange, 400, "invalid_request", "This request cannot include a body");
            return false;
        }
        return true;
    }
}
