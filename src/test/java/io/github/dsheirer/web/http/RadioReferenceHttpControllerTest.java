/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.dsheirer.service.radioreference.RadioReferenceDirectoryService;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.Account;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.Agency;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.Country;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.CountryDirectory;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.County;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.CountyDirectory;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.FrequencyResult;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.State;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.StateDirectory;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.TrunkedSystem;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RadioReferenceHttpControllerTest
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void managesPremiumSessionLocationAndExactFrequencyResultsWithoutExposingPassword() throws Exception
    {
        FakeSettings settings = new FakeSettings();
        FakeGateway gateway = new FakeGateway();

        try(RadioReferenceDirectoryService service = new RadioReferenceDirectoryService((user, password) -> gateway))
        {
            RadioReferenceHttpController controller = new RadioReferenceHttpController(service, settings);
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext(RadioReferenceHttpController.PATH, controller::handle);
            server.start();

            try
            {
                URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
                HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

                JsonNode initial = data(send(client, request(origin, "").GET()));
                assertEquals("SIGNED_OUT", initial.at("/account/state").textValue());
                assertFalse(initial.at("/credentials_stored").booleanValue());

                HttpResponse<String> unauthenticated = send(client,
                    request(origin, "/frequencies?state_id=10&frequency_hz=853162500").GET());
                assertEquals(401, unauthenticated.statusCode());

                String loginBody = OBJECT_MAPPER.writeValueAsString(Map.of(
                    "user_name", "test-user", "password", "secret-value", "remember", true));
                HttpResponse<String> login = send(client, jsonRequest(origin, "/session")
                    .PUT(HttpRequest.BodyPublishers.ofString(loginBody)));
                assertEquals("VALID_PREMIUM", data(login).at("/account/state").textValue(), login.body());
                assertFalse(login.body().contains("secret-value"));
                assertTrue(settings.credentialsStored);

                assertEquals("United States", data(send(client, request(origin, "/countries").GET()))
                    .at("/items/0/name").textValue());
                assertEquals("Test State", data(send(client,
                    request(origin, "/states?country_id=1").GET())).at("/items/0/name").textValue());

                HttpResponse<String> location = send(client, jsonRequest(origin, "/location")
                    .PUT(HttpRequest.BodyPublishers.ofString("{\"country_id\":1,\"state_id\":10}")));
                assertEquals(1, data(location).at("/country_id").intValue());
                assertEquals(10, settings.stateId);

                JsonNode matches = data(send(client,
                    request(origin, "/frequencies?state_id=10&frequency_hz=853162500").GET()));
                assertEquals(1, matches.at("/total_items").intValue());
                assertEquals("State P25", matches.at("/items/0/description").textValue());
                assertEquals(2001, matches.at("/items/0/system_id").intValue());
                assertEquals("Franklin", matches.at("/items/0/county_name").textValue());
                assertEquals("https://www.radioreference.com/db/sid/2001",
                    matches.at("/items/0/radio_reference_url").textValue());

                HttpResponse<String> logout = send(client, request(origin, "/session").DELETE());
                assertEquals("SIGNED_OUT", data(logout).at("/account/state").textValue());
                assertFalse(settings.credentialsStored);
                assertFalse(logout.body().contains("secret-value"));
            }
            finally
            {
                server.stop(0);
            }
        }
    }

    private static HttpRequest.Builder request(URI origin, String suffix)
    {
        return HttpRequest.newBuilder(origin.resolve(RadioReferenceHttpController.PATH + suffix))
            .timeout(Duration.ofSeconds(10));
    }

    private static HttpRequest.Builder jsonRequest(URI origin, String suffix)
    {
        return request(origin, suffix).header("Content-Type", "application/json");
    }

    private static HttpResponse<String> send(HttpClient client, HttpRequest.Builder request) throws Exception
    {
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode data(HttpResponse<String> response) throws Exception
    {
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300, response.body());
        return OBJECT_MAPPER.readTree(response.body()).get("data");
    }

    private static final class FakeSettings implements RadioReferenceHttpController.Settings
    {
        private boolean credentialsStored;
        private String userName;
        private String password;
        private int countryId = -1;
        private int stateId = -1;

        @Override
        public boolean hasStoredCredentials()
        {
            return credentialsStored;
        }

        @Override
        public String userName()
        {
            return userName;
        }

        @Override
        public String password()
        {
            return password;
        }

        @Override
        public int countryId()
        {
            return countryId;
        }

        @Override
        public int stateId()
        {
            return stateId;
        }

        @Override
        public void storeCredentials(String userName, String password)
        {
            credentialsStored = true;
            this.userName = userName;
            this.password = password;
        }

        @Override
        public void clearCredentials()
        {
            credentialsStored = false;
            userName = null;
            password = null;
        }

        @Override
        public void storeLocation(int countryId, int stateId)
        {
            this.countryId = countryId;
            this.stateId = stateId;
        }
    }

    private static final class FakeGateway implements RadioReferenceGateway
    {
        @Override
        public Account account()
        {
            return new Account("test-user", "Never - Test Account");
        }

        @Override
        public List<Country> countries()
        {
            return List.of(new Country(1, "United States", "US"));
        }

        @Override
        public CountryDirectory country(int countryId)
        {
            return new CountryDirectory(new Country(1, "United States", "US"),
                List.of(new State(10, "Test State", "TS")), List.of());
        }

        @Override
        public StateDirectory state(int stateId)
        {
            return new StateDirectory(new State(10, "Test State", "TS"),
                List.of(new County(100, "Franklin", "Franklin County")),
                List.of(new TrunkedSystem(2001, "State P25", "Capital", 1, 2, 3)),
                List.of(new Agency(1001, "State Police", 2)));
        }

        @Override
        public CountyDirectory county(int countyId)
        {
            return new CountyDirectory(new County(100, "Franklin", "Franklin County"), List.of(), List.of());
        }

        @Override
        public List<FrequencyResult> searchStateFrequencies(int stateId, double frequencyMHz)
        {
            return List.of(new FrequencyResult(853.1625, 808.1625, "", "State P25", "Franklin Simulcast",
                "34C", "", "", "", "P25", "", List.of("Law Dispatch"), 0, 10, 0, 100));
        }

        @Override
        public void close()
        {
        }
    }
}
