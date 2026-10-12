/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.web.http;

import com.sun.net.httpserver.HttpExchange;
import io.github.dsheirer.web.tuner.TunerAllocationMonitorService;
import java.io.IOException;
import java.util.Objects;
import java.util.function.Supplier;

/** Read-only, administrator-only view of current receiving windows and bounded in-memory allocator decisions. */
public final class TunerAllocationHttpController
{
    public static final String PATH = "/api/v1/admin/tuner-allocation";
    private final Supplier<TunerAllocationMonitorService.Snapshot> mSnapshot;

    public TunerAllocationHttpController(TunerAllocationMonitorService service) { this(service::snapshot); }

    public TunerAllocationHttpController(Supplier<TunerAllocationMonitorService.Snapshot> snapshot)
    {
        mSnapshot = Objects.requireNonNull(snapshot);
    }

    public void handle(HttpExchange exchange) throws IOException
    {
        if(!PATH.equals(exchange.getRequestURI().getRawPath()))
        {
            WebHttpSupport.notFound(exchange);
            return;
        }
        if(!"GET".equals(exchange.getRequestMethod()))
        {
            WebHttpSupport.methodNotAllowed(exchange, "GET");
            return;
        }
        if(exchange.getRequestURI().getRawQuery() != null || WebHttpSupport.hasRequestBody(exchange))
        {
            ApiHttpResponse.sendError(exchange, 400, "invalid_request", "Tuner allocation parameters are invalid");
            return;
        }
        TunerAllocationMonitorService.Snapshot snapshot = mSnapshot.get();
        if(snapshot == null)
        {
            ApiHttpResponse.sendError(exchange, 503, "tuner_allocation_unavailable",
                "Tuner allocation is temporarily unavailable. Try again shortly.");
            return;
        }
        ApiHttpResponse.sendData(exchange, 200, snapshot);
    }
}
