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
import io.github.dsheirer.rrapi.response.GetSitesResponse;
import io.github.dsheirer.rrapi.response.GetStateInfoResponse;
import io.github.dsheirer.rrapi.response.GetSystemInformationResponse;
import io.github.dsheirer.rrapi.response.GetTalkgroupCategoriesResponse;
import io.github.dsheirer.rrapi.response.GetTalkgroupsResponse;
import io.github.dsheirer.rrapi.response.GetUserDataResponse;
import io.github.dsheirer.rrapi.response.ResponseBody;
import io.github.dsheirer.rrapi.response.ResponseEnvelope;
import io.github.dsheirer.rrapi.response.SearchFrequencyResponse;
import io.github.dsheirer.rrapi.type.Agency;
import io.github.dsheirer.rrapi.type.Country;
import io.github.dsheirer.rrapi.type.CountryInfo;
import io.github.dsheirer.rrapi.type.County;
import io.github.dsheirer.rrapi.type.CountyInfo;
import io.github.dsheirer.rrapi.type.State;
import io.github.dsheirer.rrapi.type.StateInfo;
import io.github.dsheirer.rrapi.type.Site;
import io.github.dsheirer.rrapi.type.SearchFrequencyResult;
import io.github.dsheirer.rrapi.type.SystemInformation;
import io.github.dsheirer.rrapi.type.Talkgroup;
import io.github.dsheirer.rrapi.type.TalkgroupCategory;
import io.github.dsheirer.rrapi.type.UserInfo;
import java.io.ByteArrayInputStream;
import java.io.IOException;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
            response(countryResponse), response(stateResponse), response(countyResponse),
            rpcFrequencyResponse());
        AtomicInteger requestIndex = new AtomicInteger();
        List<String> requests = new ArrayList<>();
        List<String> soapActions = new ArrayList<>();

        try(TestHttpsServer server = new TestHttpsServer(exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(request);
            soapActions.add(exchange.getRequestHeaders().getFirst("SOAPAction"));
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
            RadioReferenceGateway.FrequencyResult match = gateway.searchStateFrequencies(10, 853.1625).getFirst();
            assertEquals("Test P25", match.description());
            assertEquals("Test Simulcast", match.alpha());
            assertEquals(10, match.systemId());
            assertEquals(20, match.countyId());
            assertEquals(List.of("Law Dispatch"), match.tags());
        }

        assertEquals(6, requestIndex.get());
        assertTrue(requests.stream().allMatch(request -> request.contains("dummy-password")));
        assertTrue(requests.stream().allMatch(request -> request.contains("test-user")));
        assertTrue(requests.getLast().contains("853.1625"));
        assertTrue(requests.getLast().contains("xmlns:SOAP-ENV=\"http://schemas.xmlsoap.org/soap/envelope/\""));
        assertTrue(requests.getLast().contains("xmlns:ns1=\"http://api.radioreference.com/soap2\""));
        assertTrue(requests.getLast().contains("xsi:type=\"xsd:decimal\""));
        assertTrue(requests.getLast().contains(">rpc</style>"));
        assertEquals("http://api.radioreference.com/soap2#searchStateFreq", soapActions.getLast());
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
            exchange.sendResponseHeaders(500, faultResponse.length);
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
            assertEquals(RadioReferenceGatewayException.Kind.HTTP_ERROR,
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
            assertEquals(RadioReferenceGatewayException.Kind.TIMEOUT,
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
                assertEquals(RadioReferenceGatewayException.Kind.TIMEOUT,
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

    @Test
    void largeSlowSystemEndpointsUseIndependentBoundedBudgets() throws Exception
    {
        UserInfo user = new UserInfo();
        user.setUserName("test-user");
        user.setExpirationDate("Never - Test");
        GetUserDataResponse userBody = new GetUserDataResponse();
        userBody.setUserInfo(user);

        SystemInformation systemInformation = new SystemInformation();
        systemInformation.setName("Large Statewide System");
        systemInformation.setCounties(List.of());
        systemInformation.setStates(List.of());
        systemInformation.setRectangles(List.of());
        systemInformation.setRadioNetworks(List.of());
        systemInformation.setBandplans(List.of());
        GetSystemInformationResponse systemBody = new GetSystemInformationResponse();
        systemBody.setSystemInformation(systemInformation);

        List<Site> sites = new ArrayList<>();

        for(int index = 0; index < 222; index++)
        {
            Site site = new Site();
            site.setSiteId(index + 1);
            site.setSystemId(6643);
            site.setSiteNumber(index + 1);
            site.setDescription("Statewide Site " + index);
            site.setCountyId(1_000 + index);
            site.setRectangles(List.of());
            site.setSiteLicenses(List.of());
            site.setSiteFrequencies(List.of());
            site.setBandplans(List.of());
            sites.add(site);
        }

        GetSitesResponse sitesBody = new GetSitesResponse();
        sitesBody.setSites(sites);
        List<Talkgroup> talkgroups = new ArrayList<>();

        for(int index = 0; index < 6_617; index++)
        {
            Talkgroup talkgroup = new Talkgroup();
            talkgroup.setTalkgroupId(index + 1);
            talkgroup.setDecimalValue(10_000 + index);
            talkgroup.setAlphaTag("MARCS TG " + index);
            talkgroup.setDescription("Large statewide talkgroup " + index);
            talkgroup.setMode("D");
            talkgroup.setTalkgroupCategoryId(index % 200);
            talkgroup.setTags(new io.github.dsheirer.rrapi.type.Tag[0]);
            talkgroups.add(talkgroup);
        }

        GetTalkgroupsResponse talkgroupsBody = new GetTalkgroupsResponse();
        talkgroupsBody.setTalkgroups(talkgroups);
        List<TalkgroupCategory> categories = new ArrayList<>();

        for(int index = 0; index < 200; index++)
        {
            TalkgroupCategory category = new TalkgroupCategory();
            category.setTalkgroupCategoryId(index);
            category.setSystemId(6643);
            category.setName("Category " + index);
            category.setRectangles(List.of());
            categories.add(category);
        }

        GetTalkgroupCategoriesResponse categoriesBody = new GetTalkgroupCategoriesResponse();
        categoriesBody.setTalkgroupCategories(categories);
        List<byte[]> responses = List.of(response(userBody).getBytes(StandardCharsets.UTF_8),
            response(systemBody).getBytes(StandardCharsets.UTF_8),
            response(sitesBody).getBytes(StandardCharsets.UTF_8),
            response(talkgroupsBody).getBytes(StandardCharsets.UTF_8),
            response(categoriesBody).getBytes(StandardCharsets.UTF_8));
        AtomicInteger requestIndex = new AtomicInteger();

        try(TestHttpsServer server = new TestHttpsServer(exchange -> {
            int index = requestIndex.getAndIncrement();

            try
            {
                Thread.sleep(150);
                byte[] response = responses.get(index);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
            }
            catch(IOException ignored)
            {
                //The deliberately timed-out ordinary request can close its exchange before the fake server writes.
            }
            finally
            {
                exchange.close();
            }
        });
            SecureRadioReferenceSoapClient client = client(server.endpoint(), server.sslContext(),
                Duration.ofMillis(50), 8 * 1024 * 1024);
            SecureRadioReferenceService service = new SecureRadioReferenceService(client))
        {
            RadioReferenceException timeout = assertThrows(RadioReferenceException.class, service::getUserInfo);
            assertTrue(timeout.getCause() instanceof RadioReferenceGatewayException);
            assertEquals(RadioReferenceGatewayException.Kind.TIMEOUT,
                ((RadioReferenceGatewayException)timeout.getCause()).kind());
            assertFalse(timeout.toString().contains("dummy-password"));

            assertEquals("Large Statewide System", service.getSystemInformation(6643).getName());
            assertEquals(222, service.getSites(6643).size());
            assertEquals(6_617, service.getTalkgroups(6643).size());
            assertEquals(200, service.getTalkgroupCategories(6643).size());
        }

        assertEquals(5, requestIndex.get());
    }

    @Test
    void cacheFailureDoesNotImmediatelyRepeatTheRemoteRequest() throws Exception
    {
        AtomicInteger requests = new AtomicInteger();

        try(TestHttpsServer server = new TestHttpsServer(exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
            SecureRadioReferenceSoapClient client = client(server.endpoint(), server.sslContext(),
                Duration.ofSeconds(1), 1024);
            CachingRadioReferenceService service = new CachingRadioReferenceService(client))
        {
            RadioReferenceException exception =
                assertThrows(RadioReferenceException.class, () -> service.getSites(6643));
            assertTrue(exception.getCause() instanceof RadioReferenceGatewayException);
            assertEquals(RadioReferenceGatewayException.Kind.HTTP_ERROR,
                ((RadioReferenceGatewayException)exception.getCause()).kind());
            assertEquals(1, requests.get());
        }
    }

    @Test
    void closingAServiceDoesNotAbortAnInFlightRequest() throws Exception
    {
        UserInfo user = new UserInfo();
        user.setUserName("in-flight-user");
        GetUserDataResponse body = new GetUserDataResponse();
        body.setUserInfo(user);
        byte[] response = response(body).getBytes(StandardCharsets.UTF_8);
        CountDownLatch requestEntered = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);

        try(TestHttpsServer server = new TestHttpsServer(exchange -> {
            requestEntered.countDown();

            try
            {
                releaseResponse.await(2, TimeUnit.SECONDS);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
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
                Duration.ofSeconds(2), 1024 * 1024))
        {
            SecureRadioReferenceService service = new SecureRadioReferenceService(client);
            ExecutorService executor = Executors.newSingleThreadExecutor();

            try
            {
                Future<UserInfo> inFlight = executor.submit(service::getUserInfo);
                assertTrue(requestEntered.await(1, TimeUnit.SECONDS));
                service.close();
                releaseResponse.countDown();
                assertEquals("in-flight-user", inFlight.get(1, TimeUnit.SECONDS).getUserName());
                assertThrows(RadioReferenceException.class, service::getUserInfo);
            }
            finally
            {
                releaseResponse.countDown();
                service.close();
                executor.shutdownNow();
            }
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

    /** Production-shaped RPC/encoded response, including SOAP metadata attributes ignored by the data model. */
    private static String rpcFrequencyResponse()
    {
        return """
            <?xml version="1.0" encoding="utf-8"?>
            <SOAP-ENV:Envelope SOAP-ENV:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"
                xmlns:SOAP-ENV="http://schemas.xmlsoap.org/soap/envelope/"
                xmlns:xsd="http://www.w3.org/2001/XMLSchema"
                xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                xmlns:SOAP-ENC="http://schemas.xmlsoap.org/soap/encoding/"
                xmlns:tns="http://api.radioreference.com/soap2">
              <SOAP-ENV:Body>
                <ns1:searchStateFreqResponse xmlns:ns1="http://api.radioreference.com/soap2">
                  <return xsi:type="SOAP-ENC:Array" SOAP-ENC:arrayType="tns:searchFreqResult[1]">
                    <item xsi:type="tns:searchFreqResult">
                      <out xsi:type="xsd:decimal">853.1625</out>
                      <in xsi:type="xsd:decimal">808.1625</in>
                      <callsign xsi:type="xsd:string">WTEST1</callsign>
                      <descr xsi:type="xsd:string">Test P25</descr>
                      <alpha xsi:type="xsd:string">Test Simulcast</alpha>
                      <tone xsi:type="xsd:string">34C</tone>
                      <colorCode xsi:type="xsd:string"></colorCode>
                      <tg xsi:type="xsd:string"></tg>
                      <slot xsi:type="xsd:string"></slot>
                      <mode xsi:type="xsd:string">P25</mode>
                      <class xsi:type="xsd:string">T</class>
                      <tags xsi:type="SOAP-ENC:Array" SOAP-ENC:arrayType="tns:tag[1]">
                        <item xsi:type="tns:tag">
                          <tagId xsi:type="xsd:int">1</tagId>
                          <tagDescr xsi:type="xsd:string">Law Dispatch</tagDescr>
                        </item>
                      </tags>
                      <scid xsi:type="xsd:int">100</scid>
                      <sid xsi:type="xsd:int">10</sid>
                      <aid xsi:type="xsd:int">0</aid>
                      <ctid xsi:type="xsd:int">20</ctid>
                    </item>
                  </return>
                </ns1:searchStateFreqResponse>
              </SOAP-ENV:Body>
            </SOAP-ENV:Envelope>
            """;
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
