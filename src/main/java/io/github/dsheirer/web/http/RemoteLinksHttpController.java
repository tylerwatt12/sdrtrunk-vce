/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */
package io.github.dsheirer.web.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import io.github.dsheirer.remote.RemoteLinkAdministrationService;
import io.github.dsheirer.remote.RemoteLinkAdministrationService.AdoptFeedRequest;
import io.github.dsheirer.remote.RemoteLinkAdministrationService.CreateSenderRequest;
import io.github.dsheirer.remote.RemoteLinkAdministrationService.CreateSenderResult;
import io.github.dsheirer.remote.RemoteLinkAdministrationService.FeedSnapshot;
import io.github.dsheirer.remote.RemoteLinkAdministrationService.ListenerUpdate;
import io.github.dsheirer.remote.RemoteLinkAdministrationService.RemoteLinkSnapshot;
import io.github.dsheirer.remote.RemoteLinkAdministrationService.SenderConnectionUpdate;
import io.github.dsheirer.remote.RemoteLinkAdministrationService.UpdateFeedRequest;
import io.github.dsheirer.remote.RemoteLinkAdministrationService.UpdateSenderRequest;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Administrator-only API for this installation's inbound and outbound remote links. */
public final class RemoteLinksHttpController
{
    public static final String PATH = "/api/v1/admin/remote-links";
    private static final Pattern SENDER_PATH = Pattern.compile("^" + PATH + "/senders/([^/]+)$");
    private static final Pattern FEED_PATH = Pattern.compile("^" + PATH + "/senders/([^/]+)/feeds/([^/]+)$");
    private static final Pattern ADOPT_PATH = Pattern.compile(
        "^" + PATH + "/senders/([^/]+)/feeds/([^/]+)/adopt$");
    private static final int MAXIMUM_ID_CHARACTERS = 36;
    private static final int MAXIMUM_NAME_CHARACTERS = 120;
    private static final int MAXIMUM_HOST_CHARACTERS = 255;
    private static final int MAXIMUM_SECRET_CHARACTERS = 256;
    private static final int MAXIMUM_EXPORT_CHANNELS = 256;
    private static final long MAXIMUM_JAVASCRIPT_INTEGER = 9_007_199_254_740_991L;
    private final RemoteLinkAdministrationService mService;

    public RemoteLinksHttpController(RemoteLinkAdministrationService service)
    {
        mService = Objects.requireNonNull(service, "Remote-link administration service cannot be null");
    }

    public void handle(HttpExchange exchange) throws IOException
    {
        WebRequestSecurity.prepareSecurityHeaders(exchange);

        try
        {
            route(exchange);
        }
        catch(WebHttpSupport.RequestException exception)
        {
            WebHttpSupport.sendError(exchange, exception.status(), exception.code(), exception.getMessage());
        }
        catch(ResponseSentException ignored)
        {
            //The route already sent the complete response.
        }
        catch(RemoteLinkAdministrationService.NotFoundException exception)
        {
            WebHttpSupport.sendError(exchange, 404, "not_found", "The remote link no longer exists");
        }
        catch(RemoteLinkAdministrationService.StaleRevisionException exception)
        {
            WebHttpSupport.sendError(exchange, 409, "stale_revision",
                "Remote Links changed. Reload this editor before saving");
        }
        catch(RemoteLinkAdministrationService.ConflictException exception)
        {
            WebHttpSupport.sendError(exchange, 409, "remote_link_conflict",
                WebHttpSupport.safeMessage(exception, "The remote-link change conflicts with current settings"));
        }
        catch(RemoteLinkAdministrationService.BusyException exception)
        {
            exchange.getResponseHeaders().set("Retry-After", "1");
            WebHttpSupport.sendError(exchange, 429, "remote_links_busy", "Remote Links is busy; try again");
        }
        catch(RemoteLinkAdministrationService.UnavailableException exception)
        {
            WebHttpSupport.sendError(exchange, 503, "remote_links_unavailable",
                "Remote Links is not ready. Try again shortly");
        }
        catch(IllegalArgumentException exception)
        {
            WebHttpSupport.sendError(exchange, 422, "invalid_remote_link",
                WebHttpSupport.safeMessage(exception, "The remote-link request is invalid"));
        }
        catch(RuntimeException exception)
        {
            // Never log or echo request bodies: the sender-connection request may contain a shared secret.
            WebHttpSupport.sendError(exchange, 503, "remote_link_failed",
                "The remote-link request could not be completed");
        }
    }

    private void route(HttpExchange exchange) throws IOException, WebHttpSupport.RequestException
    {
        if(!WebHttpSupport.requireNoQuery(exchange))
        {
            return;
        }

        String path = exchange.getRequestURI().getRawPath();
        String method = exchange.getRequestMethod();

        if(PATH.equals(path))
        {
            if(!"GET".equals(method))
            {
                WebHttpSupport.methodNotAllowed(exchange, "GET");
                return;
            }
            requireNoBody(exchange);
            WebHttpSupport.sendData(exchange, 200, snapshotDocument(mService.snapshot()));
            return;
        }

        if((PATH + "/listener").equals(path))
        {
            requireMethod(exchange, method, "PUT");
            JsonNode body = WebHttpSupport.readJsonObject(exchange,
                Set.of("revision", "enabled", "bind_address", "port"));
            RemoteLinkSnapshot result = mService.updateListener(revision(body), new ListenerUpdate(
                requiredBoolean(body, "enabled"), requiredText(body, "bind_address", MAXIMUM_HOST_CHARACTERS),
                port(body, "port")));
            WebHttpSupport.sendData(exchange, 200, snapshotDocument(result));
            return;
        }

        if((PATH + "/sender-connection").equals(path))
        {
            requireMethod(exchange, method, "PUT");
            JsonNode body = WebHttpSupport.readJsonObject(exchange, Set.of("revision", "enabled",
                "destination_host", "destination_port", "sender_id", "secret",
                "exported_channel_configuration_ids"));
            boolean enabled = requiredBoolean(body, "enabled");
            String secret = optionalText(body, "secret", MAXIMUM_SECRET_CHARACTERS);
            RemoteLinkSnapshot result = mService.updateSenderConnection(revision(body),
                new SenderConnectionUpdate(enabled,
                    enabled ? requiredText(body, "destination_host", MAXIMUM_HOST_CHARACTERS) :
                        optionalText(body, "destination_host", MAXIMUM_HOST_CHARACTERS),
                    port(body, "destination_port"), enabled ? requiredText(body, "sender_id", MAXIMUM_ID_CHARACTERS) :
                        optionalText(body, "sender_id", MAXIMUM_ID_CHARACTERS),
                    secret, textList(body, "exported_channel_configuration_ids", MAXIMUM_EXPORT_CHANNELS,
                    MAXIMUM_ID_CHARACTERS)));
            WebHttpSupport.sendData(exchange, 200, snapshotDocument(result));
            return;
        }

        if((PATH + "/senders").equals(path))
        {
            requireMethod(exchange, method, "POST");
            JsonNode body = WebHttpSupport.readJsonObject(exchange, Set.of("revision", "display_name"));
            CreateSenderResult result = mService.createSender(revision(body),
                new CreateSenderRequest(requiredText(body, "display_name", MAXIMUM_NAME_CHARACTERS)));
            WebHttpSupport.sendData(exchange, 201, Map.of("revision", result.revision(),
                "sender_id", result.senderId(), "display_name", result.displayName(), "secret", result.secret()));
            return;
        }

        Matcher adopt = ADOPT_PATH.matcher(path);
        if(adopt.matches())
        {
            requireMethod(exchange, method, "POST");
            JsonNode body = WebHttpSupport.readJsonObject(exchange,
                Set.of("revision", "display_name", "alias_list_id"));
            RemoteLinkSnapshot result = mService.adoptFeed(revision(body), decodedId(adopt.group(1)),
                decodedId(adopt.group(2)), new AdoptFeedRequest(
                requiredText(body, "display_name", MAXIMUM_NAME_CHARACTERS), positiveId(body, "alias_list_id")));
            WebHttpSupport.sendData(exchange, 200, snapshotDocument(result));
            return;
        }

        Matcher feed = FEED_PATH.matcher(path);
        if(feed.matches())
        {
            String senderId = decodedId(feed.group(1));
            String feedId = decodedId(feed.group(2));
            JsonNode body;
            RemoteLinkSnapshot result;
            if("PUT".equals(method))
            {
                body = WebHttpSupport.readJsonObject(exchange,
                    Set.of("revision", "display_name", "alias_list_id", "enabled"));
                result = mService.updateFeed(revision(body), senderId, feedId,
                    new UpdateFeedRequest(requiredText(body, "display_name", MAXIMUM_NAME_CHARACTERS),
                        positiveId(body, "alias_list_id"), requiredBoolean(body, "enabled")));
            }
            else if("DELETE".equals(method))
            {
                body = WebHttpSupport.readJsonObject(exchange, Set.of("revision"));
                result = mService.forgetFeed(revision(body), senderId, feedId);
            }
            else
            {
                WebHttpSupport.methodNotAllowed(exchange, "PUT, DELETE");
                return;
            }
            WebHttpSupport.sendData(exchange, 200, snapshotDocument(result));
            return;
        }

        Matcher sender = SENDER_PATH.matcher(path);
        if(sender.matches())
        {
            String senderId = decodedId(sender.group(1));
            JsonNode body;
            RemoteLinkSnapshot result;
            if("PUT".equals(method))
            {
                body = WebHttpSupport.readJsonObject(exchange,
                    Set.of("revision", "display_name", "auto_adopt", "default_alias_list_id"));
                result = mService.updateSender(revision(body), senderId,
                    new UpdateSenderRequest(requiredText(body, "display_name", MAXIMUM_NAME_CHARACTERS),
                        requiredBoolean(body, "auto_adopt"), optionalPositiveId(body, "default_alias_list_id")));
            }
            else if("DELETE".equals(method))
            {
                body = WebHttpSupport.readJsonObject(exchange, Set.of("revision"));
                result = mService.revokeSender(revision(body), senderId);
            }
            else
            {
                WebHttpSupport.methodNotAllowed(exchange, "PUT, DELETE");
                return;
            }
            WebHttpSupport.sendData(exchange, 200, snapshotDocument(result));
            return;
        }

        WebHttpSupport.notFound(exchange);
    }

    private static void requireMethod(HttpExchange exchange, String actual, String required) throws IOException
    {
        if(!required.equals(actual))
        {
            WebHttpSupport.methodNotAllowed(exchange, required);
            throw new ResponseSentException();
        }
    }

    private static void requireNoBody(HttpExchange exchange) throws WebHttpSupport.RequestException
    {
        if(WebHttpSupport.hasRequestBody(exchange))
        {
            throw new WebHttpSupport.RequestException(400, "invalid_request", "GET requests cannot include a body");
        }
    }

    private static long revision(JsonNode body) throws WebHttpSupport.RequestException
    {
        return positiveLong(body, "revision", "Revision is invalid");
    }

    private static int port(JsonNode body, String field) throws WebHttpSupport.RequestException
    {
        JsonNode value = body.get(field);
        if(value == null || !value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 1 ||
            value.intValue() > 65_535)
        {
            throw invalid(field);
        }
        return value.intValue();
    }

    private static boolean requiredBoolean(JsonNode body, String field) throws WebHttpSupport.RequestException
    {
        JsonNode value = body.get(field);
        if(value == null || !value.isBoolean())
        {
            throw invalid(field);
        }
        return value.booleanValue();
    }

    private static String requiredText(JsonNode body, String field, int maximumCharacters)
        throws WebHttpSupport.RequestException
    {
        return WebHttpSupport.requiredText(body, field, maximumCharacters).strip();
    }

    private static String optionalText(JsonNode body, String field, int maximumCharacters)
        throws WebHttpSupport.RequestException
    {
        JsonNode value = body.get(field);
        if(value == null || value.isNull())
        {
            return null;
        }
        if(!value.isTextual() || value.textValue().length() > maximumCharacters)
        {
            throw invalid(field);
        }
        String text = value.textValue();
        return text.isBlank() ? null : text;
    }

    private static long positiveId(JsonNode body, String field) throws WebHttpSupport.RequestException
    {
        return positiveLong(body, field, field + " is invalid");
    }

    private static Long optionalPositiveId(JsonNode body, String field) throws WebHttpSupport.RequestException
    {
        JsonNode value = body.get(field);
        return value == null || value.isNull() ? null : positiveLong(body, field, field + " is invalid");
    }

    private static long positiveLong(JsonNode body, String field, String message)
        throws WebHttpSupport.RequestException
    {
        JsonNode value = body.get(field);
        if(value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0 ||
            value.longValue() > MAXIMUM_JAVASCRIPT_INTEGER)
        {
            throw new WebHttpSupport.RequestException(400, "invalid_request", message);
        }
        return value.longValue();
    }

    private static List<String> textList(JsonNode body, String field, int maximumItems, int maximumCharacters)
        throws WebHttpSupport.RequestException
    {
        JsonNode value = body.get(field);
        if(value == null || !value.isArray() || value.size() > maximumItems)
        {
            throw invalid(field);
        }
        List<String> values = new ArrayList<>(value.size());
        for(JsonNode item: value)
        {
            if(!item.isTextual() || item.textValue().isBlank() || item.textValue().length() > maximumCharacters)
            {
                throw invalid(field);
            }
            values.add(item.textValue());
        }
        if(values.stream().distinct().count() != values.size())
        {
            throw invalid(field);
        }
        return List.copyOf(values);
    }

    private static String decodedId(String value)
    {
        String decoded = ApiRequestDecoder.decodeComponent(value, false);
        if(decoded.isBlank() || decoded.length() > MAXIMUM_ID_CHARACTERS || decoded.indexOf('/') >= 0)
        {
            throw new IllegalArgumentException("Remote-link identity is invalid");
        }
        return decoded;
    }

    private static WebHttpSupport.RequestException invalid(String field)
    {
        return new WebHttpSupport.RequestException(400, "invalid_request", field + " is invalid");
    }

    private static Map<String,Object> snapshotDocument(RemoteLinkSnapshot snapshot)
    {
        LinkedHashMap<String,Object> value = new LinkedHashMap<>();
        value.put("revision", snapshot.revision());
        value.put("listener", listenerDocument(snapshot.listener()));
        value.put("sender_connection", senderConnectionDocument(snapshot.senderConnection()));
        value.put("export_channel_options", snapshot.exportChannelOptions().stream().map(option -> Map.of(
            "channel_configuration_id", safe(option.channelConfigurationId()),
            "name", safe(option.name()), "system_name", safe(option.systemName()),
            "site_name", safe(option.siteName()), "protocol", safe(option.protocol()))).toList());
        value.put("alias_lists", snapshot.aliasLists().stream().map(option -> Map.of(
            "alias_list_id", option.aliasListId(), "name", safe(option.name()))).toList());
        value.put("senders", snapshot.senders().stream().map(RemoteLinksHttpController::senderDocument).toList());
        return Map.copyOf(value);
    }

    private static Map<String,Object> listenerDocument(RemoteLinkAdministrationService.ListenerSnapshot listener)
    {
        LinkedHashMap<String,Object> value = new LinkedHashMap<>();
        value.put("enabled", listener.enabled());
        put(value, "bind_address", listener.bindAddress());
        value.put("port", listener.port());
        value.put("state", listener.state().name());
        put(value, "status_message", listener.statusMessage());
        value.put("dependencies", listener.dependencies().stream().map(dependency -> {
            LinkedHashMap<String,Object> item = new LinkedHashMap<>();
            put(item, "id", dependency.id());
            put(item, "label", dependency.label());
            item.put("state", dependency.state().name());
            put(item, "status_message", dependency.statusMessage());
            return Map.copyOf(item);
        }).toList());
        return Map.copyOf(value);
    }

    private static Map<String,Object> senderConnectionDocument(
        RemoteLinkAdministrationService.SenderConnectionSnapshot connection)
    {
        LinkedHashMap<String,Object> value = new LinkedHashMap<>();
        value.put("enabled", connection.enabled());
        put(value, "destination_host", connection.destinationHost());
        value.put("destination_port", connection.destinationPort());
        put(value, "sender_id", connection.senderId());
        value.put("credential_configured", connection.credentialConfigured());
        value.put("state", connection.state().name());
        value.put("last_connected_at_ms", connection.lastConnectedAtMs());
        put(value, "status_message", connection.statusMessage());
        value.put("exported_channel_configuration_ids", connection.exportedChannelConfigurationIds());
        return Map.copyOf(value);
    }

    private static Map<String,Object> senderDocument(RemoteLinkAdministrationService.SenderSnapshot sender)
    {
        LinkedHashMap<String,Object> value = new LinkedHashMap<>();
        put(value, "sender_id", sender.senderId());
        put(value, "display_name", sender.displayName());
        value.put("state", sender.state().name());
        value.put("credential_configured", sender.credentialConfigured());
        value.put("paired_at_ms", sender.pairedAtMs());
        value.put("last_seen_at_ms", sender.lastSeenAtMs());
        value.put("auto_adopt", sender.autoAdopt());
        put(value, "default_alias_list_id", sender.defaultAliasListId());
        put(value, "status_message", sender.statusMessage());
        value.put("feeds", sender.feeds().stream().map(RemoteLinksHttpController::feedDocument).toList());
        return Map.copyOf(value);
    }

    private static Map<String,Object> feedDocument(FeedSnapshot feed)
    {
        LinkedHashMap<String,Object> value = new LinkedHashMap<>();
        put(value, "feed_id", feed.feedId());
        put(value, "advertised_name", feed.advertisedName());
        put(value, "display_name", feed.displayName());
        put(value, "protocol", feed.protocol());
        put(value, "system_name", feed.systemName());
        put(value, "site_name", feed.siteName());
        put(value, "wacn", feed.wacn());
        put(value, "system", feed.system());
        put(value, "rfss", feed.rfss());
        put(value, "site", feed.site());
        value.put("frequency_hz", feed.frequencyHz());
        value.put("state", feed.state().name());
        value.put("adopted", feed.adopted());
        value.put("enabled", feed.enabled());
        put(value, "channel_configuration_id", feed.channelConfigurationId());
        put(value, "alias_list_id", feed.aliasListId());
        value.put("last_seen_at_ms", feed.lastSeenAtMs());
        put(value, "lag_milliseconds", feed.lagMilliseconds());
        value.put("dropped_packet_count", feed.droppedPacketCount());
        value.put("sequence_gap_count", feed.sequenceGapCount());
        put(value, "status_message", feed.statusMessage());
        return Map.copyOf(value);
    }

    private static String safe(String value)
    {
        return value != null ? value : "";
    }

    private static void put(Map<String,Object> values, String key, Object value)
    {
        if(value != null)
        {
            values.put(key, value);
        }
    }

    /** Stops route execution after an Allow response has already been written. */
    private static final class ResponseSentException extends RuntimeException
    {
    }
}
