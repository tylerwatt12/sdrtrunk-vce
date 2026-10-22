/* Copyright (C) 2026. Licensed under GPL-3.0-or-later. */
package io.github.dsheirer.web.http;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.web.auth.WebAccessService;
import io.github.dsheirer.web.auth.WebAccessSession;
import io.github.dsheirer.web.auth.WebAuthenticationService;
import io.github.dsheirer.web.auth.WebCapability;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RemoteSupportSecurityTest
{
    @TempDir Path mRoot;

    @Test void supportRequiresBootstrapAndCannotAuthorizeOrExtendItsOwnGrant() throws Exception
    {
        Path database = mRoot.resolve("sdrtrunk.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        WebAccessService access = new WebAccessService(database);
        try(WebAuthenticationService authentication = new WebAuthenticationService(access);
            RemoteSupportService service = new RemoteSupportService(mRoot, authentication, Clock.systemUTC(), false))
        {
            assertTrue(authentication.openRemoteSupportSession().isEmpty());
            access.provisionOrResetPrimaryAdmin("a suitable administrator password".toCharArray());
            WebAccessSession session = authentication.openRemoteSupportSession().orElseThrow();
            WebRequestSecurity security = new WebRequestSecurity(access, authentication);
            RemoteSupportHttpController controller = new RemoteSupportHttpController(service, security);
            HttpServer server = HttpServer.create();
            var context = server.createContext(RemoteSupportHttpController.PATH,
                security.protectApi(WebCapability.ADMIN_SETTINGS, controller::handle));
            try
            {
                RemoteSupportExchange start = exchange(context, session,
                    "{\"action\":\"start\",\"duration_seconds\":21600}", "https://vce-test.radioresolve.com", true);
                context.getHandler().handle(start);
                assertEquals(403, start.getResponseCode());
                assertEquals(false, service.status(false).get("enabled"));

                service.start(21600);
                RemoteSupportExchange wrongOrigin = exchange(context, session, "{\"action\":\"stop\"}",
                    "https://attacker.invalid", true);
                context.getHandler().handle(wrongOrigin);
                assertEquals(403, wrongOrigin.getResponseCode());
                assertEquals(true, service.status(false).get("enabled"));

                RemoteSupportExchange noCsrf = exchange(context, session, "{\"action\":\"stop\"}",
                    "https://vce-test.radioresolve.com", false);
                context.getHandler().handle(noCsrf);
                assertEquals(403, noCsrf.getResponseCode());

                RemoteSupportExchange stop = exchange(context, session, "{\"action\":\"stop\"}",
                    "https://vce-test.radioresolve.com", true);
                context.getHandler().handle(stop);
                assertEquals(200, stop.getResponseCode());
                assertEquals(false, service.status(false).get("enabled"));
            }
            finally { server.stop(0); }
        }
    }

    private static RemoteSupportExchange exchange(com.sun.net.httpserver.HttpContext context,
        WebAccessSession session, String body, String origin, boolean csrf)
    {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        AtomicReference<RemoteSupportExchange> reference = new AtomicReference<>();
        RemoteSupportExchange exchange = new RemoteSupportExchange("1", "POST", URI.create(RemoteSupportHttpController.PATH),
            Map.of("Origin", List.of(origin), "Content-Type", List.of("application/json"),
                "Content-Length", List.of(Integer.toString(bytes.length)), "X-CSRF-Token",
                List.of(csrf ? session.csrfToken() : "invalid")), "vce-test.radioresolve.com", session.sessionId(), context,
            message -> { if("data".equals(message.get("type"))) reference.get().acknowledge(); }, () -> true);
        reference.set(exchange);
        assertTrue(exchange.offer(bytes));
        exchange.endRequest();
        return exchange;
    }
}
