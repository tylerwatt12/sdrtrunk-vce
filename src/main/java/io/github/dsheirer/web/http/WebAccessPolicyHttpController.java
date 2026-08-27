/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import io.github.dsheirer.web.auth.AccessTier;
import io.github.dsheirer.web.auth.WebAccessService;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Administrator-only endpoint for configurable site-access policies. */
public final class WebAccessPolicyHttpController
{
    public static final String PATH = "/api/v1/admin/access";
    private static final Logger mLog = LoggerFactory.getLogger(WebAccessPolicyHttpController.class);
    private final WebAccessService mAccessService;

    public WebAccessPolicyHttpController(WebAccessService accessService)
    {
        mAccessService = Objects.requireNonNull(accessService, "Web access service cannot be null");
    }

    public void handle(HttpExchange exchange) throws IOException
    {
        WebRequestSecurity.prepareSecurityHeaders(exchange);
        if(!WebHttpSupport.hasExactPath(exchange, PATH))
        {
            WebHttpSupport.notFound(exchange);
            return;
        }
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
                    throw new WebHttpSupport.RequestException(400, "invalid_request",
                        "GET requests cannot include a body");
                }
                WebHttpSupport.sendData(exchange, 200, Map.of("capabilities", policyResponses()));
            }
            else if("PUT".equals(exchange.getRequestMethod()))
            {
                JsonNode request = WebHttpSupport.readJsonObject(exchange, Set.of("capability", "tier"));
                String id = WebHttpSupport.requiredText(request, "capability", 64);
                AccessTier tier = WebHttpSupport.requiredTier(request, "tier");
                WebHttpSupport.sendData(exchange, 200,
                    WebHttpSupport.policyResponse(mAccessService.setCapabilityTier(id, tier)));
            }
            else
            {
                WebHttpSupport.methodNotAllowed(exchange, "GET, PUT");
            }
        }
        catch(WebHttpSupport.RequestException exception)
        {
            WebHttpSupport.sendError(exchange, exception.status(), exception.code(), exception.getMessage());
        }
        catch(IllegalArgumentException exception)
        {
            WebHttpSupport.sendError(exchange, 400, "invalid_request",
                WebHttpSupport.safeMessage(exception, "The access request is invalid"));
        }
        catch(IllegalStateException exception)
        {
            WebHttpSupport.sendError(exchange, 409, "conflict",
                WebHttpSupport.safeMessage(exception, "The access request conflicts with current state"));
        }
        catch(SQLException exception)
        {
            mLog.warn("Unable to persist web access policy change", exception);
            WebHttpSupport.sendError(exchange, 503, "storage_unavailable", "The access change could not be saved");
        }
        catch(IOException exception)
        {
            if(exchange.getResponseCode() >= 0)
            {
                throw exception;
            }
            mLog.warn("Unable to read or persist web access policy data", exception);
            WebHttpSupport.sendError(exchange, 503, "storage_unavailable", "The access change could not be saved");
        }
    }

    private List<Map<String,Object>> policyResponses()
    {
        List<Map<String,Object>> responses = new ArrayList<>();
        for(WebAccessService.CapabilityPolicy policy: mAccessService.policies())
        {
            responses.add(WebHttpSupport.policyResponse(policy));
        }
        return List.copyOf(responses);
    }
}
