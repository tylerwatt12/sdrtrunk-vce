/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */

package io.github.dsheirer.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Test;

class WebApplicationServiceTest
{
    @Test
    void startsOnEphemeralPortServesAndStopsDeterministically() throws Exception
    {
        Handler handler = new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                if("/health".equals(Request.getPathInContext(request)))
                {
                    WebResponses.text(response, callback, 200, "text/plain; charset=utf-8", "ready");
                    return true;
                }

                WebResponses.text(response, callback, 404, "text/plain; charset=utf-8", "not found");
                return true;
            }
        };
        WebApplicationService service = new WebApplicationService(
            WebApplicationService.Configuration.ephemeralLoopback(), handler, container -> {});

        service.start();
        service.start();
        assertTrue(service.isRunning());
        assertTrue(service.getLocalPort() > 0);

        URI health = service.getBaseUri().resolve("health");
        HttpResponse<String> response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(health).timeout(Duration.ofSeconds(5)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("ready", response.body());
        assertTrue(service.getThreadPoolSnapshot().threads() <= 16);

        service.close();
        service.close();
        assertFalse(service.isRunning());
        assertEquals(-1, service.getLocalPort());
    }

    @Test
    void supportsRepeatedLifecycleWithoutRetainingJettyThreads()
    {
        int before = countWebThreads();

        for(int x = 0; x < 5; x++)
        {
            try(WebApplicationService service = new WebApplicationService(
                WebApplicationService.Configuration.ephemeralLoopback(), new NotFoundHandler(), container -> {}))
            {
                service.start();
                assertTrue(service.isRunning());
            }
        }

        assertEquals(before, countWebThreads());
        assertTrue(Runtime.version().feature() >= 25);
    }

    private static int countWebThreads()
    {
        return (int)Thread.getAllStackTraces().keySet().stream()
            .filter(thread -> thread.isAlive() && thread.getName().startsWith("sdrtrunk web"))
            .count();
    }

    private static class NotFoundHandler extends Handler.Abstract
    {
        @Override
        public boolean handle(Request request, Response response, Callback callback)
        {
            WebResponses.text(response, callback, 404, "text/plain; charset=utf-8", "not found");
            return true;
        }
    }
}
