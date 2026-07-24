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
package io.github.dsheirer.service.radioreference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.github.dsheirer.rrapi.RadioReferenceException;
import io.github.dsheirer.rrapi.RadioReferenceService;
import io.github.dsheirer.rrapi.request.RequestEnvelope;
import io.github.dsheirer.rrapi.response.Fault;
import io.github.dsheirer.rrapi.response.GetCountryInfoResponse;
import io.github.dsheirer.rrapi.response.GetCountryListResponse;
import io.github.dsheirer.rrapi.response.GetCountyInfoResponse;
import io.github.dsheirer.rrapi.response.GetStateInfoResponse;
import io.github.dsheirer.rrapi.response.GetUserDataResponse;
import io.github.dsheirer.rrapi.response.ResponseBody;
import io.github.dsheirer.rrapi.response.ResponseEnvelope;
import io.github.dsheirer.rrapi.type.Agency;
import io.github.dsheirer.rrapi.type.Country;
import io.github.dsheirer.rrapi.type.CountryInfo;
import io.github.dsheirer.rrapi.type.County;
import io.github.dsheirer.rrapi.type.CountyInfo;
import io.github.dsheirer.rrapi.type.State;
import io.github.dsheirer.rrapi.type.StateInfo;
import io.github.dsheirer.rrapi.type.UserInfo;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.Test;

class RrapiRadioReferenceGatewayTransportTest
{
    private static final String LEGACY_PLAINTEXT_ENDPOINT = "http://api.radioreference.com/soap2/";
    private static final char[] TEST_STORE_PASSWORD = "changeit".toCharArray();
    /*
     * Test-only localhost certificate and key.  The certificate has localhost/127.0.0.1 SANs and is trusted only by
     * the explicitly injected test SSL context.  Production never loads this material.
     */
    private static final String TEST_SERVER_PKCS12 =
        "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";

    @Test
    void legacyDependencyIsPlaintextButProductionAdapterUsesDedicatedHttpsTransport() throws Exception
    {
        byte[] classBytes;

        try(InputStream input = RadioReferenceService.class.getResourceAsStream("RadioReferenceService.class"))
        {
            assertNotNull(input);
            classBytes = input.readAllBytes();
        }

        String classConstants = new String(classBytes, StandardCharsets.ISO_8859_1);
        assertTrue(classConstants.contains(LEGACY_PLAINTEXT_ENDPOINT));
        assertTrue(SecureRadioReferenceSoapClient.productionEndpoint().toString().startsWith("https://"));
        assertEquals("api.radioreference.com", SecureRadioReferenceSoapClient.productionEndpoint().getHost());
        assertFalse(RadioReferenceService.class.isAssignableFrom(SecureRadioReferenceService.class),
            "the application-owned service must not inherit any legacy plaintext methods");
        assertEquals(SecureRadioReferenceService.class,
            RadioReference.class.getMethod("getService").getReturnType());
        assertEquals(SecureRadioReferenceService.class, CachingRadioReferenceService.class.getSuperclass());

        RadioReferenceGatewayException insecure = assertThrows(RadioReferenceGatewayException.class,
            () -> new SecureRadioReferenceSoapClient(URI.create(LEGACY_PLAINTEXT_ENDPOINT), "user",
                "password".toCharArray(), Duration.ofSeconds(1), Duration.ofSeconds(1), 1_024, null));
        assertEquals(RadioReferenceGatewayException.Kind.INSECURE_TRANSPORT, insecure.kind());
    }

    @Test
    void secureFakeServerExercisesAllDirectoryRequests() throws Exception
    {
        UserInfo userInfo = new UserInfo();
        userInfo.setUserName("test-user");
        userInfo.setExpirationDate("Never - Test");
        GetUserDataResponse userResponse = new GetUserDataResponse();
        userResponse.setUserInfo(userInfo);

        Country country = new Country();
        country.setCountryId(1);
        country.setName("Test Country");
        country.setCountryCode("TC");
        GetCountryListResponse countriesResponse = new GetCountryListResponse();
        countriesResponse.setCountries(List.of(country));

        State state = new State();
        state.setStateId(10);
        state.setName("Test State");
        state.setStateCode("TS");
        Agency nationalAgency = new Agency();
        nationalAgency.setAgencyId(100);
        nationalAgency.setName("National Agency");
        CountryInfo countryInfo = new CountryInfo();
        countryInfo.setCountryId(1);
        countryInfo.setName("Test Country");
        countryInfo.setCountryCode("TC");
        countryInfo.setStates(List.of(state));
        countryInfo.setAgencies(List.of(nationalAgency));
        GetCountryInfoResponse countryResponse = new GetCountryInfoResponse();
        countryResponse.setCountryInfo(countryInfo);

        County county = new County();
        county.setCountyId(20);
        county.setName("Test County");
        StateInfo stateInfo = new StateInfo();
        stateInfo.setStateId(10);
        stateInfo.setName("Test State");
        stateInfo.setCounties(List.of(county));
        stateInfo.setSystems(List.of());
        stateInfo.setAgencies(List.of());
        GetStateInfoResponse stateResponse = new GetStateInfoResponse();
        stateResponse.setStateInfo(stateInfo);

        CountyInfo countyInfo = new CountyInfo();
        countyInfo.setCountyId(20);
        countyInfo.setName("Test County");
        countyInfo.setSystems(List.of());
        countyInfo.setAgencies(List.of());
        GetCountyInfoResponse countyResponse = new GetCountyInfoResponse();
        countyResponse.setCountyInfo(countyInfo);

        List<String> responses = List.of(response(userResponse), response(countriesResponse),
            response(countryResponse), response(stateResponse), response(countyResponse));
        AtomicInteger requestIndex = new AtomicInteger();
        List<String> requests = new ArrayList<>();

        try(TestHttpsServer server = new TestHttpsServer(exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(request);
            byte[] response = responses.get(requestIndex.getAndIncrement()).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/xml;charset=UTF-8");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
            SecureRadioReferenceSoapClient client = client(server.endpoint(), server.sslContext(),
                Duration.ofSeconds(2), 1024 * 1024);
            RrapiRadioReferenceGateway gateway = new RrapiRadioReferenceGateway(client))
        {
            assertEquals("test-user", gateway.account().userName());
            assertEquals("Test Country", gateway.countries().getFirst().name());
            assertEquals("Test State", gateway.country(1).states().getFirst().name());
            assertEquals("Test County", gateway.state(10).counties().getFirst().name());
            assertEquals("Test County", gateway.county(20).county().name());
        }

        assertEquals(5, requestIndex.get());
        assertTrue(requests.stream().allMatch(request -> request.contains("dummy-password")));
        assertTrue(requests.stream().allMatch(request -> request.contains("test-user")));
    }

    @Test
    void defaultTrustAndHostnameValidationRejectUntrustedServer() throws Exception
    {
        try(TestHttpsServer server = new TestHttpsServer(exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
            SecureRadioReferenceSoapClient client = client(server.endpoint(), null, Duration.ofSeconds(1), 1024);
            RrapiRadioReferenceGateway gateway = new RrapiRadioReferenceGateway(client))
        {
            assertEquals(RadioReferenceGatewayException.Kind.UNAVAILABLE,
                assertThrows(RadioReferenceGatewayException.class, gateway::account).kind());
        }
    }

    @Test
    void authenticationFaultRemainsClassifiableByTheLegacyLoginView() throws Exception
    {
        Fault fault = new Fault();
        fault.setFaultCode("AUTH");
        byte[] faultResponse = response(fault).getBytes(StandardCharsets.UTF_8);

        try(TestHttpsServer server = new TestHttpsServer(exchange -> {
            exchange.sendResponseHeaders(200, faultResponse.length);
            exchange.getResponseBody().write(faultResponse);
            exchange.close();
        });
            SecureRadioReferenceSoapClient client = client(server.endpoint(), server.sslContext(),
                Duration.ofSeconds(1), 1024 * 1024);
            SecureRadioReferenceService service = new SecureRadioReferenceService(client))
        {
            RadioReferenceException exception =
                assertThrows(RadioReferenceException.class, service::getUserInfo);
            assertTrue(exception.hasFault());
            assertEquals("AUTH", exception.getFault().getFaultCode());
            assertFalse(exception.getMessage().contains("dummy-password"));
        }
    }

    @Test
    void redirectsAreNotFollowedAndCannotDowngradeToHttp() throws Exception
    {
        AtomicInteger plaintextRequests = new AtomicInteger();
        HttpServer plaintext = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        plaintext.createContext("/", exchange -> {
            plaintextRequests.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        plaintext.start();

        try(TestHttpsServer server = new TestHttpsServer(exchange -> {
            exchange.getResponseHeaders().set("Location",
                "http://localhost:" + plaintext.getAddress().getPort() + "/downgrade");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
            SecureRadioReferenceSoapClient client = client(server.endpoint(), server.sslContext(),
                Duration.ofSeconds(1), 1024);
            RrapiRadioReferenceGateway gateway = new RrapiRadioReferenceGateway(client))
        {
            assertEquals(RadioReferenceGatewayException.Kind.UNAVAILABLE,
                assertThrows(RadioReferenceGatewayException.class, gateway::account).kind());
            assertEquals(0, plaintextRequests.get());
        }
        finally
        {
            plaintext.stop(0);
        }
    }

    @Test
    void requestDeadlineAndResponseLimitAreEnforced() throws Exception
    {
        try(TestHttpsServer server = new TestHttpsServer(exchange -> {
            try
            {
                Thread.sleep(250);
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
            }

            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
            SecureRadioReferenceSoapClient client = client(server.endpoint(), server.sslContext(),
                Duration.ofMillis(50), 1024);
            RrapiRadioReferenceGateway gateway = new RrapiRadioReferenceGateway(client))
        {
            assertEquals(RadioReferenceGatewayException.Kind.UNAVAILABLE,
                assertThrows(RadioReferenceGatewayException.class, gateway::account).kind());
        }

        try(TestHttpsServer server = new TestHttpsServer(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write('<');
            exchange.getResponseBody().flush();

            try
            {
                Thread.sleep(2_000);
                exchange.getResponseBody().write("/>".getBytes(StandardCharsets.UTF_8));
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
            }
            finally
            {
                exchange.close();
            }
        });
            SecureRadioReferenceSoapClient client = client(server.endpoint(), server.sslContext(),
                Duration.ofMillis(100), 1024);
            RrapiRadioReferenceGateway gateway = new RrapiRadioReferenceGateway(client))
        {
            assertTimeoutPreemptively(Duration.ofSeconds(1), () ->
                assertEquals(RadioReferenceGatewayException.Kind.UNAVAILABLE,
                    assertThrows(RadioReferenceGatewayException.class, gateway::account).kind()));
        }

        try(TestHttpsServer server = new TestHttpsServer(exchange -> {
            byte[] response = "x".repeat(2_048).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
            SecureRadioReferenceSoapClient client = client(server.endpoint(), server.sslContext(),
                Duration.ofSeconds(1), 1024);
            RrapiRadioReferenceGateway gateway = new RrapiRadioReferenceGateway(client))
        {
            assertEquals(RadioReferenceGatewayException.Kind.RESULT_SET_TOO_LARGE,
                assertThrows(RadioReferenceGatewayException.class, gateway::account).kind());
        }
    }

    private static SecureRadioReferenceSoapClient client(URI endpoint, SSLContext sslContext,
                                                          Duration requestTimeout, int maximumResponseBytes)
        throws Exception
    {
        return new SecureRadioReferenceSoapClient(endpoint, "test-user", "dummy-password".toCharArray(),
            Duration.ofSeconds(1), requestTimeout, maximumResponseBytes, sslContext);
    }

    private static String response(ResponseBody body) throws Exception
    {
        ResponseEnvelope envelope = new ResponseEnvelope();
        envelope.setResponseBody(body);
        return envelope.toXmlString();
    }

    private static SSLContext testSslContext() throws Exception
    {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(new ByteArrayInputStream(Base64.getDecoder().decode(TEST_SERVER_PKCS12)),
            TEST_STORE_PASSWORD);
        KeyManagerFactory keyManagers =
            KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keyStore, TEST_STORE_PASSWORD);
        TrustManagerFactory trustManagers =
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(keyStore);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers.getKeyManagers(), trustManagers.getTrustManagers(), null);
        return context;
    }

    private static final class TestHttpsServer implements AutoCloseable
    {
        private final HttpsServer mServer;
        private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
        private final SSLContext mSslContext;

        private TestHttpsServer(com.sun.net.httpserver.HttpHandler handler) throws Exception
        {
            mSslContext = testSslContext();
            mServer = HttpsServer.create(new InetSocketAddress("localhost", 0), 0);
            mServer.setHttpsConfigurator(new HttpsConfigurator(mSslContext));
            mServer.createContext("/soap2/", handler);
            mServer.setExecutor(mExecutor);
            mServer.start();
        }

        private URI endpoint()
        {
            return URI.create("https://localhost:" + mServer.getAddress().getPort() + "/soap2/");
        }

        private SSLContext sslContext()
        {
            return mSslContext;
        }

        @Override
        public void close()
        {
            mServer.stop(0);
            mExecutor.shutdownNow();
        }
    }
}
