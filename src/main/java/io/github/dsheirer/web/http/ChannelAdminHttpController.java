/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.web.http;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.sun.net.httpserver.HttpExchange;
import io.github.dsheirer.channel.ChannelAdministrationService;
import io.github.dsheirer.channel.ChannelDefinition;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Strict administrator-only JSON adapter for web-first channel management. */
public final class ChannelAdminHttpController
{
    public static final String PATH = "/api/v1/admin/channels";
    public static final String READ_PATH = "/api/v1/channel-catalog";
    public static final String PROTOCOLS_PATH = PATH + "/protocols";
    public static final String OPTIONS_PATH = PATH + "/options";
    public static final String ACTIONS_PATH = PATH + "/actions";
    private static final int MAXIMUM_JSON_BODY_BYTES = 512 * 1024;
    private static final Logger mLog = LoggerFactory.getLogger(ChannelAdminHttpController.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private final ChannelAdministrationService mService;

    public ChannelAdminHttpController(ChannelAdministrationService service)
    {
        mService = Objects.requireNonNull(service, "Channel administration service cannot be null");
    }

    public void handle(HttpExchange exchange) throws IOException
    {
        WebRequestSecurity.prepareSecurityHeaders(exchange);
        try
        {
            String path = exchange.getRequestURI().getRawPath();
            if(PROTOCOLS_PATH.equals(path)) handleProtocols(exchange);
            else if(path.startsWith(PROTOCOLS_PATH + "/") && path.endsWith("/template"))
                handleTemplate(exchange, path);
            else if(OPTIONS_PATH.equals(path)) handleOptions(exchange);
            else if(ACTIONS_PATH.equals(path)) handleActions(exchange);
            else if(PATH.equals(path)) handleCollection(exchange);
            else if(path.startsWith(PATH + "/")) handleItem(exchange, path);
            else throw error(404, "not_found", "Not found");
        }
        catch(RequestException exception)
        {
            sendError(exchange, exception.status(), exception.code(), exception.getMessage());
        }
        catch(ChannelAdministrationService.NotFoundException exception)
        {
            sendError(exchange, 404, "not_found", safeMessage(exception, "Channel configuration was not found"));
        }
        catch(ChannelAdministrationService.StaleRevisionException exception)
        {
            sendError(exchange, 409, "stale_revision", "Channel configuration changed; reload and try again");
        }
        catch(ChannelAdministrationService.ChannelRunningException exception)
        {
            sendError(exchange, 409, "channel_running", "Stop the channel before changing its configuration");
        }
        catch(ChannelAdministrationService.NotInitializedException exception)
        {
            sendError(exchange, 503, "configuration_loading", "Channel configuration is still loading");
        }
        catch(ChannelAdministrationService.ConfigurationBusyException exception)
        {
            exchange.getResponseHeaders().set("Retry-After", "1");
            sendError(exchange, 429, "configuration_busy", "Channel configuration is busy; try again");
        }
        catch(ChannelAdministrationService.PersistenceException exception)
        {
            mLog.warn("Unable to persist channel administration change", exception);
            sendError(exchange, 503, "storage_unavailable", "The channel change could not be saved");
        }
        catch(ChannelAdministrationService.LifecycleException exception)
        {
            mLog.warn("Unable to complete channel lifecycle command", exception);
            sendError(exchange, 409, "lifecycle_failed", safeMessage(exception,
                "The channel lifecycle command failed"));
        }
        catch(ChannelAdministrationService.StatisticsException exception)
        {
            mLog.warn("Unable to clear channel statistics", exception);
            sendError(exchange, 503, "statistics_unavailable", "Channel statistics could not be cleared");
        }
        catch(IllegalArgumentException exception)
        {
            sendError(exchange, 400, "invalid_request", safeMessage(exception, "The channel request is invalid"));
        }
        catch(IllegalStateException exception)
        {
            sendError(exchange, 409, "conflict", safeMessage(exception,
                "The channel request conflicts with current configuration"));
        }
        catch(Exception exception)
        {
            if(exchange.getResponseCode() >= 0 && exception instanceof IOException ioException) throw ioException;
            mLog.warn("Unable to complete channel administration request", exception);
            sendError(exchange, 503, "request_failed", "The channel request could not be completed");
        }
    }

    /** Read-only channel catalog for every account allowed to view the receiver. */
    public void handleCatalog(HttpExchange exchange) throws IOException
    {
        WebRequestSecurity.prepareSecurityHeaders(exchange);
        try
        {
            if(!READ_PATH.equals(exchange.getRequestURI().getRawPath()))
                throw error(404, "not_found", "Not found");
            requireGet(exchange);
            sendData(exchange, 200, mService.catalog());
        }
        catch(RequestException exception)
        {
            sendError(exchange, exception.status(), exception.code(), exception.getMessage());
        }
        catch(ChannelAdministrationService.NotInitializedException exception)
        {
            sendError(exchange, 503, "configuration_loading", "Channel configuration is still loading");
        }
        catch(Exception exception)
        {
            mLog.warn("Unable to read the channel catalog", exception);
            sendError(exchange, 503, "request_failed", "The channel catalog could not be loaded");
        }
    }

    private void handleProtocols(HttpExchange exchange) throws IOException, RequestException
    {
        requireGet(exchange);
        sendData(exchange, 200, mService.protocolRegistry().catalog());
    }

    private void handleTemplate(HttpExchange exchange, String path) throws IOException, RequestException
    {
        requireGet(exchange);
        String suffix = "/template";
        String protocolId = path.substring((PROTOCOLS_PATH + "/").length(), path.length() - suffix.length());
        if(protocolId.isBlank() || protocolId.contains("/")) throw error(404, "not_found", "Not found");
        sendData(exchange, 200, mService.template(protocolId));
    }

    private void handleOptions(HttpExchange exchange) throws IOException, RequestException
    {
        requireGet(exchange);
        sendData(exchange, 200, mService.options());
    }

    private void handleCollection(HttpExchange exchange) throws Exception
    {
        requireNoQuery(exchange);
        switch(exchange.getRequestMethod())
        {
            case "GET" -> {
                requireNoBody(exchange);
                sendData(exchange, 200, mService.catalog());
            }
            case "POST" -> {
                ChannelWriteRequest request = readJson(exchange, ChannelWriteRequest.class);
                ChannelAdministrationService.MutationResult result = mService.create(request.definition(null),
                    requiredRevision(request.revision()));
                if(result.configurationIds().size() == 1)
                    exchange.getResponseHeaders().set("Location", PATH + "/" + result.configurationIds().get(0));
                sendData(exchange, 201, result);
            }
            default -> methodNotAllowed(exchange, "GET, POST");
        }
    }

    private void handleItem(HttpExchange exchange, String path) throws Exception
    {
        String statisticsSuffix = "/statistics/clear";
        if(path.endsWith(statisticsSuffix))
        {
            String id = itemId(path.substring(0, path.length() - statisticsSuffix.length()));
            requireMethod(exchange, "POST");
            requireNoQuery(exchange);
            requireNoBody(exchange);
            sendData(exchange, 200, mService.clearStatistics(id));
            return;
        }

        String moveSuffix = "/auto-start/move";
        if(path.endsWith(moveSuffix))
        {
            String id = itemId(path.substring(0, path.length() - moveSuffix.length()));
            requireMethod(exchange, "POST");
            MoveRequest request = readJson(exchange, MoveRequest.class);
            sendData(exchange, 200, mService.moveAutoStart(id, required(request.direction(), "direction"),
                requiredRevision(request.revision())));
            return;
        }

        String id = itemId(path);
        requireNoQuery(exchange);
        switch(exchange.getRequestMethod())
        {
            case "GET" -> {
                requireNoBody(exchange);
                sendData(exchange, 200, mService.get(id));
            }
            case "PUT" -> {
                ChannelWriteRequest request = readJson(exchange, ChannelWriteRequest.class);
                sendData(exchange, 200, mService.update(id, request.definition(id),
                    requiredRevision(request.revision())));
            }
            case "DELETE" -> {
                RevisionRequest request = readJson(exchange, RevisionRequest.class);
                sendData(exchange, 200, mService.deleteChannels(List.of(id),
                    requiredRevision(request.revision())));
            }
            default -> methodNotAllowed(exchange, "GET, PUT, DELETE");
        }
    }

    private void handleActions(HttpExchange exchange) throws Exception
    {
        requireNoQuery(exchange);
        requireMethod(exchange, "POST");
        ActionRequest request = readJson(exchange, ActionRequest.class);
        List<String> ids = required(request.configurationIds(), "configuration_ids");
        switch(required(request.action(), "action"))
        {
            case START -> sendData(exchange, 200, mService.setProcessing(ids, true));
            case STOP -> sendData(exchange, 200, mService.setProcessing(ids, false));
            case ENABLE_AUTO_START -> sendData(exchange, 200, mService.setAutoStart(ids, true,
                requiredRevision(request.revision())));
            case DISABLE_AUTO_START -> sendData(exchange, 200, mService.setAutoStart(ids, false,
                requiredRevision(request.revision())));
            case CLONE -> sendData(exchange, 200, mService.cloneChannels(ids,
                requiredRevision(request.revision())));
            case DELETE -> sendData(exchange, 200, mService.deleteChannels(ids,
                requiredRevision(request.revision())));
        }
    }

    private static String itemId(String path) throws RequestException
    {
        String prefix = PATH + "/";
        if(!path.startsWith(prefix)) throw error(404, "not_found", "Not found");
        String id = path.substring(prefix.length());
        if(id.isBlank() || id.contains("/")) throw error(404, "not_found", "Not found");
        return id;
    }

    private static <T> T readJson(HttpExchange exchange, Class<T> type) throws IOException, RequestException
    {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if(contentType == null || !"application/json".equals(contentType.toLowerCase(Locale.ROOT)
            .split(";", 2)[0].strip()))
            throw error(415, "invalid_content_type", "Content-Type must be application/json");
        byte[] bytes = ApiRequestDecoder.readBody(exchange, MAXIMUM_JSON_BODY_BYTES);
        try
        {
            if(bytes.length == 0) throw error(400, "invalid_request", "A JSON request body is required");
            return OBJECT_MAPPER.readValue(bytes, type);
        }
        catch(RequestException exception)
        {
            throw exception;
        }
        catch(Exception exception)
        {
            throw error(400, "invalid_request", "The JSON body is invalid");
        }
        finally
        {
            Arrays.fill(bytes, (byte)0);
        }
    }

    private static void requireGet(HttpExchange exchange) throws IOException, RequestException
    {
        requireNoQuery(exchange);
        requireMethod(exchange, "GET");
        requireNoBody(exchange);
    }

    private static void requireNoQuery(HttpExchange exchange) throws RequestException
    {
        if(exchange.getRequestURI().getRawQuery() != null)
            throw error(400, "invalid_request", "Query parameters are not supported");
    }

    private static void requireNoBody(HttpExchange exchange) throws RequestException
    {
        String length = exchange.getRequestHeaders().getFirst("Content-Length");
        if(length != null && !"0".equals(length) || exchange.getRequestHeaders().containsKey("Transfer-Encoding"))
            throw error(400, "invalid_request", "This request cannot include a body");
    }

    private static void requireMethod(HttpExchange exchange, String method) throws IOException, RequestException
    {
        if(!method.equals(exchange.getRequestMethod()))
        {
            exchange.getResponseHeaders().set("Allow", method);
            throw error(405, "method_not_allowed", "Method not allowed");
        }
    }

    private static void methodNotAllowed(HttpExchange exchange, String allow) throws IOException
    {
        exchange.getResponseHeaders().set("Allow", allow);
        sendError(exchange, 405, "method_not_allowed", "Method not allowed");
    }

    private static long requiredRevision(Long revision) throws RequestException
    {
        if(revision == null || revision < 0) throw error(400, "invalid_request", "revision is invalid");
        return revision;
    }

    private static <T> T required(T value, String field) throws RequestException
    {
        if(value == null) throw error(400, "invalid_request", field + " is invalid");
        return value;
    }

    private static void sendData(HttpExchange exchange, int status, Object value) throws IOException
    {
        exchange.getResponseHeaders().set("Vary", "Cookie");
        ApiHttpResponse.sendData(exchange, status, value);
    }

    private static void sendError(HttpExchange exchange, int status, String code, String message) throws IOException
    {
        exchange.getResponseHeaders().set("Vary", "Cookie");
        ApiHttpResponse.sendError(exchange, status, code, message);
    }

    private static String safeMessage(RuntimeException exception, String fallback)
    {
        String message = exception.getMessage();
        return message == null || message.isBlank() || message.length() > 240 ? fallback : message;
    }

    private static RequestException error(int status, String code, String message)
    {
        return new RequestException(status, code, message);
    }

    private record SourceRequest(List<Long> frequenciesHz, Long minimumFrequencyHz, Long maximumFrequencyHz,
                                 Long preferredFrequencyHz, String preferredTuner, Integer rotationDelayMs)
    {
        private ChannelDefinition.Source definition()
        {
            return new ChannelDefinition.Source(frequenciesHz, minimumFrequencyHz, maximumFrequencyHz,
                preferredFrequencyHz, preferredTuner, rotationDelayMs);
        }
    }

    private record FrequencyMapRequest(Integer number, Long downlinkHz, Long uplinkHz)
    {
        private ChannelDefinition.FrequencyMapEntry definition()
        {
            if(number == null || downlinkHz == null)
                throw new IllegalArgumentException("Frequency map number and downlink are required");
            return new ChannelDefinition.FrequencyMapEntry(number, downlinkHz, uplinkHz != null ? uplinkHz : 0L);
        }
    }

    private record ChannelWriteRequest(Long revision, String protocolId, String system, String site, String name,
                                       String radioResolveId, Long aliasListId, SourceRequest source,
                                       Map<String,Object> settings, List<FrequencyMapRequest> frequencyMap,
                                       List<String> eventLogs, List<String> recorders,
                                       List<String> auxiliaryDecoders)
    {
        private ChannelDefinition definition(String configurationId)
        {
            if(aliasListId == null || source == null)
                throw new IllegalArgumentException("Alias List and source are required");
            return new ChannelDefinition(configurationId, protocolId, system, site, name, radioResolveId, aliasListId,
                source.definition(), settings, frequencyMap != null ? frequencyMap.stream()
                    .map(FrequencyMapRequest::definition).toList() : List.of(), eventLogs, recorders,
                auxiliaryDecoders, ChannelDefinition.Observed.EMPTY);
        }
    }

    private record RevisionRequest(Long revision) {}
    private record MoveRequest(Long revision, ChannelAdministrationService.Direction direction) {}
    private record ActionRequest(Long revision, Action action, List<String> configurationIds) {}
    private enum Action { START, STOP, ENABLE_AUTO_START, DISABLE_AUTO_START, CLONE, DELETE }
    private static final class RequestException extends Exception
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
}
