/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */
package io.github.dsheirer.web.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import io.github.dsheirer.source.tuner.recording.RecordingTunerFileCatalog;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;

/** Admin-only recording tuner file selection. The registered route must use a fixed ADMIN capability. */
public final class RecordingTunerHttpController
{
    public static final String PATH = "/api/v1/admin/tuners/recordings";
    public static final String RESCAN_PATH = PATH + "/rescan";
    private final RecordingTunerFileCatalog mCatalog;
    private final BiConsumer<Path, Long> mRegistrar;
    private volatile boolean mInitialScanComplete;

    public RecordingTunerHttpController(RecordingTunerFileCatalog catalog, BiConsumer<Path, Long> registrar)
    {
        mCatalog = Objects.requireNonNull(catalog, "Recording file catalog cannot be null");
        mRegistrar = Objects.requireNonNull(registrar, "Recording tuner registrar cannot be null");
    }

    public void handle(HttpExchange exchange) throws IOException
    {
        if(!WebHttpSupport.requireNoQuery(exchange))
        {
            return;
        }

        String path = exchange.getRequestURI().getRawPath();
        if(RESCAN_PATH.equals(path))
        {
            if(!"POST".equals(exchange.getRequestMethod()))
            {
                WebHttpSupport.methodNotAllowed(exchange, "POST");
                return;
            }
            if(WebHttpSupport.hasRequestBody(exchange))
            {
                ApiHttpResponse.sendError(exchange, 400, "invalid_request", "Rescan does not accept a body");
                return;
            }
            rescan(exchange);
            return;
        }
        if(!PATH.equals(path))
        {
            WebHttpSupport.notFound(exchange);
            return;
        }

        switch(exchange.getRequestMethod())
        {
            case "GET" -> {
                if(WebHttpSupport.hasRequestBody(exchange))
                {
                    ApiHttpResponse.sendError(exchange, 400, "invalid_request", "GET does not accept a body");
                }
                else
                {
                    try
                    {
                        synchronized(this)
                        {
                            if(!mInitialScanComplete)
                            {
                                mCatalog.rescan();
                                mInitialScanComplete = true;
                            }
                        }
                        WebHttpSupport.sendData(exchange, 200, mCatalog.snapshot());
                    }
                    catch(IOException exception)
                    {
                        ApiHttpResponse.sendError(exchange, 503, "recording_directory_unavailable",
                            "The recording tuner directory could not be scanned");
                    }
                }
            }
            case "POST" -> create(exchange);
            default -> WebHttpSupport.methodNotAllowed(exchange, "GET, POST");
        }
    }

    private void rescan(HttpExchange exchange) throws IOException
    {
        RecordingTunerFileCatalog.ScanResult result;
        try
        {
            result = mCatalog.rescan();
        }
        catch(IOException exception)
        {
            WebHttpSupport.sendError(exchange, 503, "recording_directory_unavailable",
                "The recording tuner directory could not be scanned");
            return;
        }
        mInitialScanComplete = true;
        WebHttpSupport.sendData(exchange, 200, result);
    }

    private void create(HttpExchange exchange) throws IOException
    {
        try
        {
            JsonNode request = WebHttpSupport.readJsonObject(exchange, Set.of("file_id", "center_frequency_hz"));
            String id = WebHttpSupport.requiredText(request, "file_id", 64);
            JsonNode frequencyNode = request.get("center_frequency_hz");
            if(frequencyNode == null || !frequencyNode.isIntegralNumber() || !frequencyNode.canConvertToLong())
            {
                ApiHttpResponse.sendError(exchange, 400, "invalid_frequency", "Center frequency must be an integer");
                return;
            }
            long frequency = frequencyNode.longValue();
            if(frequency < 1_000_000 || frequency > Integer.MAX_VALUE)
            {
                ApiHttpResponse.sendError(exchange, 422, "invalid_frequency",
                    "Center frequency must be between 1 MHz and 2.14 GHz");
                return;
            }

            Path selected;
            try
            {
                selected = mCatalog.resolve(id);
            }
            catch(IllegalArgumentException exception)
            {
                ApiHttpResponse.sendError(exchange, 404, "recording_not_found",
                    "Select a recording from the current catalog");
                return;
            }
            catch(IOException exception)
            {
                ApiHttpResponse.sendError(exchange, 409, "recording_changed",
                    "Recording changed or is unavailable; rescan and select it again");
                return;
            }

            try
            {
                mRegistrar.accept(selected, frequency);
            }
            catch(IllegalArgumentException exception)
            {
                ApiHttpResponse.sendError(exchange, 422, "recording_tuner_invalid",
                    "The recording tuner could not be added with these settings");
                return;
            }
            catch(RuntimeException exception)
            {
                ApiHttpResponse.sendError(exchange, 500, "recording_tuner_failed",
                    "The recording tuner could not be added");
                return;
            }

            WebHttpSupport.sendData(exchange, 201, Map.of("created", true));
        }
        catch(WebHttpSupport.RequestException exception)
        {
            ApiHttpResponse.sendError(exchange, exception.status(), exception.code(), exception.getMessage());
        }
    }
}
