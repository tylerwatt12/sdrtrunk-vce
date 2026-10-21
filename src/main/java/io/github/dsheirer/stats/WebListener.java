/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsServer;
import com.sun.net.httpserver.HttpsConfigurator;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.application.ApplicationPreference;
import io.github.dsheirer.preference.application.WebCertificateMode;
import io.github.dsheirer.web.http.EmbeddedHttpServerPolicy;
import io.github.dsheirer.web.http.EmbeddedHttpServerShutdown;
import io.github.dsheirer.web.http.ApiHttpResponse;
import io.github.dsheirer.web.network.WebCertificateIdentity;
import io.github.dsheirer.web.tls.TlsMaterial;
import io.github.dsheirer.web.tls.TlsMaterialException;
import io.github.dsheirer.web.tls.WebTlsMaterialService;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import javax.net.ssl.SSLContext;

/** Shared socket/TLS mechanics. Setup validation never creates receiver feature services. */
public final class WebListener implements AutoCloseable
{
    private final HttpServer mServer;
    private final ExecutorService mExecutor;
    private final PreparedConfiguration mConfiguration;

    private WebListener(HttpServer server, ExecutorService executor, PreparedConfiguration configuration)
    {
        mServer = server;
        mExecutor = executor;
        mConfiguration = configuration;
    }

    HttpServer server() { return mServer; }
    ExecutorService executor() { return mExecutor; }
    PreparedConfiguration configuration() { return mConfiguration; }
    static final Duration RENEWAL_WINDOW = Duration.ofDays(30);

    static WebListener open(PreparedConfiguration configuration, ExecutorService executor,
                            Consumer<HttpServer> routes) throws IOException
    {
        EmbeddedHttpServerPolicy.configureBeforeServerInitialization();
        RequestedConfiguration requested = configuration.requested();
        InetSocketAddress address = createBindAddress(requested.port(), requested.anyIpEnabled());
        HttpServer server = null;
        try
        {
            if(requested.httpsEnabled())
            {
                HttpsServer https = HttpsServer.create(address, 0);
                https.setHttpsConfigurator(new HttpsConfigurator(configuration.sslContext()));
                server = https;
            }
            else server = HttpServer.create(address, 0);
            server.setExecutor(executor);
            routes.accept(server);
            server.start();
            return new WebListener(server, executor, configuration);
        }
        catch(IOException | RuntimeException | Error exception)
        {
            if(server != null) server.stop(0);
            executor.shutdownNow();
            throw exception;
        }
    }

    /** Binds the configured listener and validates its certificate, then releases it before receiver startup. */
    public static WebServerRuntimeState validate(UserPreferences preferences)
        throws IOException, GeneralSecurityException, TlsMaterialException
    {
        try(WebListener listener = validation(preferences))
        {
            return listener.runtimeState("Web listener verified.");
        }
    }

    /** Temporary setup listener exposes no receiver routes, sessions, or feature workers. */
    public static WebListener validation(UserPreferences preferences)
        throws IOException, GeneralSecurityException, TlsMaterialException
    {
        ApplicationPreference application = preferences.getApplicationPreference();
        if(!application.isStatsWebServerEnabled())
            throw new IllegalArgumentException("Enable the web interface before launching.");
        RequestedConfiguration requested = new RequestedConfiguration(StatsWebPath.getAssetsPath(),
            application.getStatsWebServerPort(), application.isStatsWebServerAnyIpEnabled(),
            application.isStatsWebServerAnyIpEnabled() || application.isStatsWebServerHttpsEnabled());
        WebTlsMaterialService tls = new WebTlsMaterialService(
            preferences.getDirectoryPreference().getDirectoryApplicationRoot());
        PreparedConfiguration prepared = prepare(preferences, tls, requested);
        return open(prepared, Executors.newSingleThreadExecutor(), server ->
            server.createContext("/", WebListener::handleValidation));
    }

    public WebServerRuntimeState runtimeState(String message)
    {
        RequestedConfiguration requested = mConfiguration.requested();
        return new WebServerRuntimeState(true, mServer.getAddress().getPort(), requested.anyIpEnabled(),
            requested.httpsEnabled(), mConfiguration.certificateFingerprint(), message);
    }

    private static void handleValidation(HttpExchange exchange) throws IOException
    {
        exchange.getResponseHeaders().set("Retry-After", "1");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        if("HEAD".equals(exchange.getRequestMethod()))
        {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        }
        else if(exchange.getRequestURI().getPath().startsWith("/api"))
            ApiHttpResponse.sendError(exchange, 503, "receiver_starting", "The receiver is starting. Try again shortly.");
        else
        {
            byte[] body = "<!doctype html><meta http-equiv=refresh content=2><title>VCE is starting</title><p>VCE is starting</p>"
                .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(503, body.length);
            try(var output = exchange.getResponseBody()) { output.write(body); }
        }
    }

    static InetSocketAddress createBindAddress(int port, boolean anyIpEnabled)
    {
        return anyIpEnabled ? new InetSocketAddress("0.0.0.0", port) :
            new InetSocketAddress("127.0.0.1", port);
    }

    static boolean requiresRenewal(TlsMaterial material, Instant now)
    {
        return !material.notAfter().isAfter(now.plus(RENEWAL_WINDOW));
    }

    @Override public void close()
    {
        EmbeddedHttpServerShutdown.stop(mServer, mExecutor);
    }

    static PreparedConfiguration prepare(UserPreferences preferences, WebTlsMaterialService tlsMaterial,
                                         RequestedConfiguration requested)
        throws IOException, GeneralSecurityException, TlsMaterialException
    {
        Files.createDirectories(requested.assetRoot());

        if(!requested.httpsEnabled())
        {
            return new PreparedConfiguration(requested, null, null);
        }

        TlsMaterial material;
        ApplicationPreference preference = preferences.getApplicationPreference();

        if(!preference.isStatsWebServerCertificateModeConfigured())
        {
            boolean existingMaterial = Files.exists(tlsMaterial.certificatePath()) ||
                Files.exists(tlsMaterial.privateKeyPath());
            preference.initializeStatsWebServerCertificateMode(existingMaterial ?
                WebCertificateMode.CUSTOM : WebCertificateMode.AUTOMATIC);
        }

        if(preference.getStatsWebServerCertificateMode() == WebCertificateMode.AUTOMATIC)
        {
            WebCertificateIdentity identity = WebCertificateIdentity.discover();

            try
            {
                material = tlsMaterial.validateInstalledMaterial();
                boolean coversCurrentIdentity = true;

                for(String name: identity.requiredSubjectAlternativeNames())
                {
                    if(!material.coversHost(name))
                    {
                        coversCurrentIdentity = false;
                        break;
                    }
                }

                if(!coversCurrentIdentity || requiresRenewal(material, Instant.now()))
                {
                    material = tlsMaterial.generateSelfSigned(identity.commonName(),
                        identity.subjectAlternativeNames());
                }
            }
            catch(TlsMaterialException exception)
            {
                material = tlsMaterial.generateSelfSigned(identity.commonName(),
                    identity.subjectAlternativeNames());
            }
        }
        else
        {
            material = tlsMaterial.validateInstalledMaterial();
        }

        return new PreparedConfiguration(requested, material.createServerSslContext(),
            material.leafSha256Fingerprint());
    }

    record RequestedConfiguration(Path assetRoot, int port, boolean anyIpEnabled, boolean httpsEnabled)
    {
        RequestedConfiguration
        {
            if(assetRoot == null)
            {
                throw new IllegalArgumentException("Stats web asset root cannot be null");
            }

            assetRoot = assetRoot.toAbsolutePath().normalize();

            if(port < 0 || port > 65_535)
            {
                throw new IllegalArgumentException("Stats web server port is invalid");
            }
        }
    }

    record PreparedConfiguration(RequestedConfiguration requested, SSLContext sslContext,
                                         String certificateFingerprint)
    {
        PreparedConfiguration
        {
            if(requested == null || requested.httpsEnabled() != (sslContext != null) ||
                requested.httpsEnabled() != (certificateFingerprint != null))
            {
                throw new IllegalArgumentException("Prepared web listener TLS state is invalid");
            }
        }
    }

}
