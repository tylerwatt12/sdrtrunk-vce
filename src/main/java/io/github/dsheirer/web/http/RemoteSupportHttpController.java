/* Copyright (C) 2026. Licensed under GPL-3.0-or-later. */
package io.github.dsheirer.web.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import io.github.dsheirer.web.auth.AccessTier;
import java.io.IOException;
import java.util.Set;

/** Administrator controls for an explicit, persisted remote-support permission. */
public final class RemoteSupportHttpController
{
    public static final String PATH = "/api/v1/remote-support";
    private final RemoteSupportService mService;
    private final WebRequestSecurity mSecurity;

    public RemoteSupportHttpController(RemoteSupportService service, WebRequestSecurity security)
    { mService = service; mSecurity = security; }

    public void handle(HttpExchange exchange) throws IOException
    {
        if(!WebHttpSupport.hasExactPath(exchange, PATH)) { WebHttpSupport.notFound(exchange); return; }
        if(!WebHttpSupport.requireNoQuery(exchange)) return;
        boolean remote = exchange instanceof RemoteSupportExchange;
        boolean ordinaryAdmin = !remote && mSecurity.authenticatedAccount(exchange)
            .map(account -> account.tier() == AccessTier.ADMIN).orElse(false);
        if(!remote && !ordinaryAdmin)
        { WebHttpSupport.sendError(exchange, 403, "access_denied", "Administrator access is required"); return; }
        try
        {
            switch(exchange.getRequestMethod())
            {
                case "GET" -> {
                    if(WebHttpSupport.hasRequestBody(exchange))
                    { WebHttpSupport.sendError(exchange, 400, "invalid_request", "This request cannot include a body"); return; }
                }
                case "POST" -> {
                    JsonNode request = WebHttpSupport.readJsonObject(exchange, Set.of("action", "duration_seconds"));
                    String action = WebHttpSupport.requiredText(request, "action", 16);
                    if(action.equals("start"))
                    {
                        if(!ordinaryAdmin)
                        { WebHttpSupport.sendError(exchange, 403, "local_administrator_required",
                            "Start remote support from the installation's administration page."); return; }
                        JsonNode duration = request.get("duration_seconds");
                        if(request.size() != 2 || duration == null || !duration.isIntegralNumber() || !duration.canConvertToLong())
                            throw new IllegalArgumentException("Choose a supported duration");
                        mService.start(duration.longValue());
                    }
                    else if(action.equals("stop") && request.size() == 1)
                        mService.stop(remote ? (RemoteSupportExchange)exchange : null);
                    else throw new IllegalArgumentException("Choose a remote support action");
                }
                default -> { WebHttpSupport.methodNotAllowed(exchange, "GET, POST"); return; }
            }
            WebHttpSupport.sendData(exchange, 200, mService.status(ordinaryAdmin));
        }
        catch(WebHttpSupport.RequestException exception)
        { WebHttpSupport.sendError(exchange, exception.status(), exception.code(), exception.getMessage()); }
        catch(IllegalArgumentException exception)
        { WebHttpSupport.sendError(exchange, 400, "invalid_request", exception.getMessage()); }
        catch(RemoteSupportService.StopPersistenceException exception)
        { WebHttpSupport.sendError(exchange, 503, "remote_support_stop_not_saved", exception.getMessage()); }
        catch(IOException | IllegalStateException exception)
        { WebHttpSupport.sendError(exchange, 503, "remote_support_unavailable",
            "Remote support could not save its setting. Check the application data folder and try again."); }
    }
}
