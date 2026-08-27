/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.http;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.sun.net.httpserver.HttpExchange;
import io.github.dsheirer.preference.radioreference.RadioReferencePreference;
import io.github.dsheirer.service.radioreference.RadioReferenceDirectoryException;
import io.github.dsheirer.service.radioreference.RadioReferenceDirectoryService;
import io.github.dsheirer.service.radioreference.RadioReferenceDirectoryService.AccountState;
import io.github.dsheirer.service.radioreference.RadioReferenceDirectoryService.AccountStatus;
import io.github.dsheirer.service.radioreference.RadioReferenceDirectoryService.BoundedPage;
import io.github.dsheirer.service.radioreference.RadioReferenceDirectoryService.DirectoryOption;
import io.github.dsheirer.stats.StatsApiV1;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Administrator-only RadioReference account, lookup-region and exact-frequency adapter. */
public final class RadioReferenceHttpController
{
    public static final String PATH = StatsApiV1.RADIO_REFERENCE;
    private static final int MAXIMUM_BODY_BYTES = 4096;
    private static final int MAXIMUM_OPTIONS = 500;
    private static final int DEFAULT_RESULT_LIMIT = 100;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    private final RadioReferenceDirectoryService mService;
    private final Settings mSettings;
    private final Object mStoredLoginLock = new Object();
    private boolean mStoredLoginAttempted;

    public RadioReferenceHttpController(RadioReferenceDirectoryService service,
                                        RadioReferencePreference preference)
    {
        this(service, new PreferenceSettings(preference));
    }

    RadioReferenceHttpController(RadioReferenceDirectoryService service, Settings settings)
    {
        mService = Objects.requireNonNull(service);
        mSettings = Objects.requireNonNull(settings);
    }

    public void handle(HttpExchange exchange) throws IOException
    {
        WebRequestSecurity.prepareSecurityHeaders(exchange);
        String path = exchange.getRequestURI().getRawPath();

        try
        {
            if(PATH.equals(path))
            {
                requireMethod(exchange, "GET");
                requireNoQuery(exchange);
                requireEmptyBody(exchange, "GET");
                ensureStoredSession();
                ApiHttpResponse.sendData(exchange, 200, status());
            }
            else if((PATH + "/session").equals(path))
            {
                session(exchange);
            }
            else if((PATH + "/location").equals(path))
            {
                location(exchange);
            }
            else if((PATH + "/countries").equals(path))
            {
                requireMethod(exchange, "GET");
                requireNoQuery(exchange);
                requireEmptyBody(exchange, "GET");
                ensureStoredSession();
                ApiHttpResponse.sendData(exchange, 200, mService.countries("", MAXIMUM_OPTIONS));
            }
            else if((PATH + "/states").equals(path))
            {
                requireMethod(exchange, "GET");
                requireEmptyBody(exchange, "GET");
                Map<String,String> query = query(exchange, "country_id");
                ensureStoredSession();
                ApiHttpResponse.sendData(exchange, 200,
                    mService.states(positiveInt(query.get("country_id"), "country_id"), "", MAXIMUM_OPTIONS));
            }
            else if((PATH + "/frequencies").equals(path))
            {
                requireMethod(exchange, "GET");
                requireEmptyBody(exchange, "GET");
                Map<String,String> query = query(exchange, "state_id", "frequency_hz", "limit", "offset");
                ensureStoredSession();
                int stateId = positiveInt(query.get("state_id"), "state_id");
                long frequencyHz = positiveLong(query.get("frequency_hz"), "frequency_hz");
                int limit = optionalInt(query.get("limit"), DEFAULT_RESULT_LIMIT);
                int offset = optionalInt(query.get("offset"), 0);
                ApiHttpResponse.sendData(exchange, 200,
                    mService.searchStateFrequencies(stateId, frequencyHz, offset, limit));
            }
            else if((PATH + "/frequencies/details").equals(path))
            {
                requireMethod(exchange, "GET");
                requireEmptyBody(exchange, "GET");
                Map<String,String> query = query(exchange, "frequency_hz", "system_id", "site_number",
                    "sub_category_id", "agency_id", "county_id", "mode");
                ensureStoredSession();
                long frequencyHz = positiveLong(query.get("frequency_hz"), "frequency_hz");
                int systemId = optionalNonNegativeInt(query.get("system_id"), "system_id");
                int siteNumber = optionalNonNegativeInt(query.get("site_number"), "site_number");
                int subCategoryId = optionalNonNegativeInt(query.get("sub_category_id"), "sub_category_id");
                int agencyId = optionalNonNegativeInt(query.get("agency_id"), "agency_id");
                int countyId = optionalNonNegativeInt(query.get("county_id"), "county_id");
                String mode = query.getOrDefault("mode", "");
                ApiHttpResponse.sendData(exchange, 200, mService.frequencyDetails(frequencyHz,
                    systemId > 0 ? systemId : null, siteNumber, subCategoryId, agencyId, countyId, mode));
            }
            else
            {
                ApiHttpResponse.sendError(exchange, 404, "not_found", "Not found");
            }
        }
        catch(ResponseSentException exception)
        {
            // The helper already wrote the complete response.
        }
        catch(RequestException exception)
        {
            ApiHttpResponse.sendError(exchange, exception.status(), exception.code(), exception.getMessage());
        }
        catch(RadioReferenceDirectoryException exception)
        {
            sendDirectoryError(exchange, exception);
        }
    }

    private void session(HttpExchange exchange) throws IOException, RequestException,
        RadioReferenceDirectoryException
    {
        requireNoQuery(exchange);

        if("PUT".equals(exchange.getRequestMethod()))
        {
            LoginRequest request = read(exchange, LoginRequest.class);
            char[] password = request.password();
            String storedPassword = Boolean.TRUE.equals(request.remember()) && password != null ?
                new String(password) : null;

            try
            {
                if(request.userName() == null || request.userName().isBlank() || password == null ||
                    password.length == 0)
                {
                    throw new RequestException(400, "invalid_request", "Username and password are required");
                }

                AccountStatus status = mService.login(request.userName(), password);

                synchronized(mStoredLoginLock)
                {
                    mStoredLoginAttempted = true;
                }

                if(status.authenticated())
                {
                    if(Boolean.TRUE.equals(request.remember()))
                    {
                        mSettings.storeCredentials(request.userName().strip(), storedPassword);
                    }
                    else
                    {
                        mSettings.clearCredentials();
                    }
                }

                ApiHttpResponse.sendData(exchange, 200, status());
            }
            finally
            {
                if(password != null)
                {
                    Arrays.fill(password, '\0');
                }
            }
        }
        else if("DELETE".equals(exchange.getRequestMethod()))
        {
            requireEmptyBody(exchange, "DELETE");
            mService.logout();
            mSettings.clearCredentials();

            synchronized(mStoredLoginLock)
            {
                mStoredLoginAttempted = true;
            }

            ApiHttpResponse.sendData(exchange, 200, status());
        }
        else
        {
            methodNotAllowed(exchange, "PUT, DELETE");
        }
    }

    private void location(HttpExchange exchange) throws IOException, RequestException,
        RadioReferenceDirectoryException
    {
        requireMethod(exchange, "PUT");
        requireNoQuery(exchange);
        LocationRequest request = read(exchange, LocationRequest.class);
        int countryId = requiredPositive(request.countryId(), "country_id");
        int stateId = requiredPositive(request.stateId(), "state_id");
        ensureStoredSession();
        BoundedPage<DirectoryOption> states = mService.states(countryId, "", MAXIMUM_OPTIONS);

        if(states.items().stream().noneMatch(state -> state.id() == stateId))
        {
            throw new RequestException(400, "invalid_location", "State does not belong to the selected country");
        }

        mSettings.storeLocation(countryId, stateId);
        ApiHttpResponse.sendData(exchange, 200, status());
    }

    private StatusResponse status()
    {
        return new StatusResponse(mService.status(), mSettings.hasStoredCredentials(),
            mSettings.userName(), mSettings.countryId(), mSettings.stateId());
    }

    private void ensureStoredSession() throws RadioReferenceDirectoryException
    {
        if(mService.status().state() != AccountState.SIGNED_OUT)
        {
            return;
        }

        synchronized(mStoredLoginLock)
        {
            if(mStoredLoginAttempted || mService.status().state() != AccountState.SIGNED_OUT)
            {
                return;
            }

            mStoredLoginAttempted = true;
            String userName = mSettings.userName();
            String password = mSettings.password();

            if(userName != null && !userName.isBlank() && password != null && !password.isEmpty())
            {
                mService.login(userName, password.toCharArray());
            }
        }
    }

    private static void sendDirectoryError(HttpExchange exchange, RadioReferenceDirectoryException exception)
        throws IOException
    {
        int status = switch(exception.code())
        {
            case INVALID_REQUEST -> 400;
            case INVALID_CREDENTIALS, NOT_AUTHENTICATED -> 401;
            case PREMIUM_REQUIRED, INSECURE_TRANSPORT -> 403;
            case RESULT_SET_TOO_LARGE -> 422;
            case BUSY -> 429;
            case TIMEOUT -> 504;
            case UNAVAILABLE, INTERRUPTED, CLOSED -> 503;
        };
        ApiHttpResponse.sendError(exchange, status, exception.code().name().toLowerCase(Locale.ROOT),
            exception.getMessage());
    }

    private static <T> T read(HttpExchange exchange, Class<T> type) throws IOException, RequestException
    {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");

        if(contentType == null || !"application/json".equals(contentType.toLowerCase(Locale.ROOT)
            .split(";", 2)[0].strip()))
        {
            throw new RequestException(415, "invalid_content_type", "Content-Type must be application/json");
        }

        String contentLength = exchange.getRequestHeaders().getFirst("Content-Length");

        if(contentLength != null)
        {
            try
            {
                long parsed = Long.parseLong(contentLength);

                if(parsed < 0 || parsed > MAXIMUM_BODY_BYTES)
                {
                    throw new RequestException(parsed < 0 ? 400 : 413,
                        parsed < 0 ? "invalid_request" : "request_too_large",
                        parsed < 0 ? "Content-Length is invalid" : "Request body is too large");
                }
            }
            catch(NumberFormatException exception)
            {
                throw new RequestException(400, "invalid_request", "Content-Length is invalid");
            }
        }

        byte[] body = ApiRequestDecoder.readBody(exchange, MAXIMUM_BODY_BYTES);

        try
        {
            if(body.length == 0)
            {
                throw new RequestException(400, "invalid_request", "Request body is required");
            }

            if(body.length > MAXIMUM_BODY_BYTES)
            {
                throw new RequestException(413, "request_too_large", "Request body is too large");
            }

            try
            {
                return OBJECT_MAPPER.readValue(body, type);
            }
            catch(IOException exception)
            {
                throw new RequestException(400, "invalid_request", "Request body must be valid JSON");
            }
        }
        finally
        {
            Arrays.fill(body, (byte)0);
        }
    }

    private static Map<String,String> query(HttpExchange exchange, String... permitted) throws RequestException
    {
        Map<String,String> values = new LinkedHashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        Map<String,Boolean> allowed = new LinkedHashMap<>();
        Arrays.stream(permitted).forEach(name -> allowed.put(name, Boolean.TRUE));

        if(raw == null || raw.isBlank())
        {
            return values;
        }

        for(String pair: raw.split("&", -1))
        {
            String[] parts = pair.split("=", 2);
            String name = decode(parts[0]);
            String value = parts.length == 2 ? decode(parts[1]) : "";

            if(!allowed.containsKey(name) || values.putIfAbsent(name, value) != null)
            {
                throw new RequestException(400, "invalid_request", "Query parameters are invalid");
            }
        }

        return values;
    }

    private static String decode(String value) throws RequestException
    {
        try
        {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        }
        catch(IllegalArgumentException exception)
        {
            throw new RequestException(400, "invalid_request", "Query parameters are invalid");
        }
    }

    private static int positiveInt(String value, String field) throws RequestException
    {
        try
        {
            return requiredPositive(value != null ? Integer.valueOf(value) : null, field);
        }
        catch(NumberFormatException exception)
        {
            throw new RequestException(400, "invalid_request", field + " must be a positive integer");
        }
    }

    private static long positiveLong(String value, String field) throws RequestException
    {
        try
        {
            long parsed = value != null ? Long.parseLong(value) : 0;

            if(parsed <= 0)
            {
                throw new NumberFormatException();
            }

            return parsed;
        }
        catch(NumberFormatException exception)
        {
            throw new RequestException(400, "invalid_request", field + " must be a positive integer");
        }
    }

    private static int optionalInt(String value, int fallback) throws RequestException
    {
        if(value == null || value.isBlank())
        {
            return fallback;
        }

        try
        {
            return Integer.parseInt(value);
        }
        catch(NumberFormatException exception)
        {
            throw new RequestException(400, "invalid_request", "Pagination values must be integers");
        }
    }

    private static int optionalNonNegativeInt(String value, String field) throws RequestException
    {
        if(value == null || value.isBlank())
        {
            return 0;
        }

        try
        {
            int parsed = Integer.parseInt(value);

            if(parsed < 0)
            {
                throw new NumberFormatException();
            }

            return parsed;
        }
        catch(NumberFormatException exception)
        {
            throw new RequestException(400, "invalid_request", field + " must be a non-negative integer");
        }
    }

    private static int requiredPositive(Integer value, String field) throws RequestException
    {
        if(value == null || value <= 0)
        {
            throw new RequestException(400, "invalid_request", field + " must be a positive integer");
        }

        return value;
    }

    private static void requireMethod(HttpExchange exchange, String method) throws IOException, RequestException
    {
        if(!method.equals(exchange.getRequestMethod()))
        {
            methodNotAllowed(exchange, method);
        }
    }

    private static void methodNotAllowed(HttpExchange exchange, String allow) throws IOException, RequestException
    {
        exchange.getResponseHeaders().set("Allow", allow);
        ApiHttpResponse.sendError(exchange, 405, "method_not_allowed", "Method not allowed");
        throw new ResponseSentException();
    }

    private static void requireNoQuery(HttpExchange exchange) throws RequestException
    {
        if(exchange.getRequestURI().getRawQuery() != null)
        {
            throw new RequestException(404, "not_found", "Not found");
        }
    }

    private static void requireEmptyBody(HttpExchange exchange, String method) throws RequestException
    {
        String contentLength = exchange.getRequestHeaders().getFirst("Content-Length");

        if(contentLength != null && !"0".equals(contentLength) ||
            exchange.getRequestHeaders().containsKey("Transfer-Encoding"))
        {
            throw new RequestException(400, "invalid_request", method + " requests cannot include a body");
        }
    }

    interface Settings
    {
        boolean hasStoredCredentials();
        String userName();
        String password();
        int countryId();
        int stateId();
        void storeCredentials(String userName, String password);
        void clearCredentials();
        void storeLocation(int countryId, int stateId);
    }

    private static final class PreferenceSettings implements Settings
    {
        private final RadioReferencePreference mPreference;

        private PreferenceSettings(RadioReferencePreference preference)
        {
            mPreference = Objects.requireNonNull(preference);
        }

        @Override
        public boolean hasStoredCredentials()
        {
            return mPreference.hasStoredCredentials();
        }

        @Override
        public String userName()
        {
            return mPreference.getUserName();
        }

        @Override
        public String password()
        {
            return mPreference.getPassword();
        }

        @Override
        public int countryId()
        {
            return mPreference.getPreferredCountryId();
        }

        @Override
        public int stateId()
        {
            return mPreference.getPreferredStateId();
        }

        @Override
        public void storeCredentials(String userName, String password)
        {
            mPreference.setStoreCredentials(true);
            mPreference.setUserName(userName);
            mPreference.setPassword(password);
        }

        @Override
        public void clearCredentials()
        {
            mPreference.removeStoredCredentials();
        }

        @Override
        public void storeLocation(int countryId, int stateId)
        {
            mPreference.setPreferredCountryId(countryId);
            mPreference.setPreferredStateId(stateId);
            mPreference.setPreferredCountyId(RadioReferencePreference.INVALID_ID);
        }
    }

    private static class RequestException extends Exception
    {
        private final int mStatus;
        private final String mCode;

        private RequestException(int status, String code, String message)
        {
            super(message);
            mStatus = status;
            mCode = code;
        }

        private int status()
        {
            return mStatus;
        }

        private String code()
        {
            return mCode;
        }
    }

    private static final class ResponseSentException extends RequestException
    {
        private ResponseSentException()
        {
            super(0, "response_sent", "Response sent");
        }
    }

    private record LoginRequest(String userName, char[] password, Boolean remember)
    {
    }

    private record LocationRequest(Integer countryId, Integer stateId)
    {
    }

    private record StatusResponse(AccountStatus account, boolean credentialsStored, String storedUserName,
                                  int countryId, int stateId)
    {
    }
}
