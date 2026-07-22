/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */

package io.github.dsheirer.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.web.tls.TlsMaterial;
import io.github.dsheirer.web.tls.WebTlsMaterialService;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WebApplicationServiceTest
{
    @TempDir
    Path mTemporaryDirectory;

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

    @Test
    void servesTheSameHandlerThroughTheSingleHttpsConnector() throws Exception
    {
        Handler handler = new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                WebResponses.text(response, callback, 200, "text/plain; charset=utf-8",
                    Boolean.toString(request.isSecure()));
                return true;
            }
        };
        TlsMaterial tlsMaterial = new WebTlsMaterialService(mTemporaryDirectory)
            .generateSelfSigned("127.0.0.1", List.of("127.0.0.1"));
        WebApplicationService.Configuration configuration = WebApplicationService.Configuration.application(
            InetAddress.getByName("127.0.0.1"), 0, tlsMaterial);

        try(WebApplicationService service = new WebApplicationService(configuration, handler, container -> {}))
        {
            service.start();
            assertEquals("https", service.getBaseUri().getScheme());
            HttpResponse<String> response = trustSelfSignedClient().send(HttpRequest.newBuilder(service.getBaseUri())
                .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals("true", response.body());
        }
    }

    @Test
    void preservesConfiguredBrowserHostAndUsesFamilyMatchedLoopbackForWildcardBindings() throws Exception
    {
        WebApplicationService.Configuration named = WebApplicationService.Configuration.application(
            InetAddress.getByName("127.0.0.1"), 0, "receiver.example", null);
        assertEquals("receiver.example", named.browserHost());

        try(WebApplicationService service = new WebApplicationService(named, new NotFoundHandler(), container -> {}))
        {
            service.start();
            assertEquals("receiver.example", service.getBaseUri().getHost());
        }

        assertEquals("127.0.0.1", WebApplicationService.Configuration.application(
            InetAddress.getByName("0.0.0.0"), 0).browserHost());
        assertEquals("::1", WebApplicationService.Configuration.application(
            InetAddress.getByName("::"), 0).browserHost());
    }

    private static HttpClient trustSelfSignedClient() throws Exception
    {
        X509TrustManager trustManager = new X509TrustManager()
        {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authenticationType)
            {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authenticationType)
            {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers()
            {
                return new X509Certificate[0];
            }
        };
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{trustManager}, new SecureRandom());
        return HttpClient.newBuilder().sslContext(context).connectTimeout(Duration.ofSeconds(5)).build();
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
