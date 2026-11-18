/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */

package io.github.dsheirer.web.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import io.github.dsheirer.web.settings.ResourceLimitsService;
import java.io.IOException;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Administrator endpoint for receiver resource limits: heap for the next start, process priority,
 * and an optional cgroup CPU quota when a privileged helper is installed.
 */
public final class ResourceLimitsHttpController
{
    public static final String PATH = "/api/v1/admin/resources";
    private static final Set<String> FIELDS = Set.of("maxHeapMb", "nice", "cpuQuotaPercent");
    private static final Logger mLog = LoggerFactory.getLogger(ResourceLimitsHttpController.class);
    private final ResourceLimitsService mResources;

    public ResourceLimitsHttpController(ResourceLimitsService resources)
    {
        mResources = Objects.requireNonNull(resources);
    }

    public void handle(HttpExchange exchange) throws IOException
    {
        WebRequestSecurity.prepareSecurityHeaders(exchange);

        if(!WebHttpSupport.requireNoQuery(exchange))
        {
            return;
        }

        try
        {
            if("GET".equals(exchange.getRequestMethod()))
            {
                if(WebHttpSupport.hasRequestBody(exchange))
                {
                    ApiHttpResponse.sendError(exchange, 400, "invalid_request",
                        "GET requests cannot include a body");
                    return;
                }

                ApiHttpResponse.sendData(exchange, 200, mResources.snapshot());
                return;
            }

            if("PUT".equals(exchange.getRequestMethod()) || "POST".equals(exchange.getRequestMethod()))
            {
                JsonNode body = WebHttpSupport.readJsonObject(exchange, FIELDS);
                ApiHttpResponse.sendData(exchange, 200, mResources.update(body));
                return;
            }

            WebHttpSupport.methodNotAllowed(exchange, "GET, PUT");
        }
        catch(WebHttpSupport.RequestException exception)
        {
            ApiHttpResponse.sendError(exchange, exception.status(), exception.code(), exception.getMessage());
        }
        catch(IllegalArgumentException exception)
        {
            ApiHttpResponse.sendError(exchange, 422, "invalid_resource_limit", exception.getMessage());
        }
        catch(IOException exception)
        {
            mLog.error("Unable to persist resource limits", exception);
            ApiHttpResponse.sendError(exchange, 500, "resource_limit_failed",
                "The resource limits could not be saved");
        }
    }
}
