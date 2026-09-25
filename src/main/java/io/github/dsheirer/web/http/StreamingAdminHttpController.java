/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.web.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import io.github.dsheirer.audio.broadcast.StreamingAdministrationService;
import io.github.dsheirer.configuration.ConfigurationManager;
import java.io.IOException;
import java.util.*;
import static io.github.dsheirer.web.http.WebHttpSupport.*;

/** Administrator-only streaming commands; all responses use explicitly credential-free service views. */
public final class StreamingAdminHttpController
{
    public static final String PATH = "/api/v1/admin/streaming";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final StreamingAdministrationService mService;
    public StreamingAdminHttpController(StreamingAdministrationService service) { mService = Objects.requireNonNull(service); }

    public void handle(HttpExchange exchange) throws IOException
    {
        try { route(exchange); }
        catch(RequestException exception) { sendError(exchange, exception.status(), exception.code(), exception.getMessage()); }
        catch(StreamingAdministrationService.NotFoundException exception) { sendError(exchange,404,"not_found","Destination no longer exists"); }
        catch(StreamingAdministrationService.StaleRevisionException exception) { sendError(exchange,409,"stale_revision","Configuration changed. Reload this editor before saving"); }
        catch(StreamingAdministrationService.ConfigurationBusyException exception)
        { exchange.getResponseHeaders().set("Retry-After","1"); sendError(exchange,429,"configuration_busy","Configuration is busy; try again"); }
        catch(StreamingAdministrationService.NotInitializedException exception)
        { sendError(exchange,503,"configuration_loading","Configuration is still loading"); }
        catch(ConfigurationManager.ConfigurationPublicationException exception)
        { sendError(exchange,503,"publication_failed","Settings were saved but could not be applied. Restart VCE"); }
        catch(ConfigurationManager.ConfigurationCommitException exception)
        { sendError(exchange,503,"storage_unavailable","The change could not be saved. The previous settings remain active"); }
        catch(IllegalArgumentException exception)
        { sendError(exchange,400,"invalid_request",safeMessage(exception,"Invalid streaming request")); }
        catch(Exception exception)
        {
            // Never include provider exceptions, request bodies, or credentials in errors/logs.
            if(exchange.getResponseCode() >= 0 && exception instanceof IOException io) throw io;
            sendError(exchange,503,"request_failed","The streaming request could not be completed");
        }
    }

    private void route(HttpExchange exchange) throws Exception
    {
        String path = exchange.getRequestURI().getRawPath();
        if(!path.equals(PATH) && !path.startsWith(PATH + "/")) { notFound(exchange); return; }
        String suffix = path.substring(PATH.length());
        String method = exchange.getRequestMethod();
        if(!suffix.endsWith("/aliases") && exchange.getRequestURI().getRawQuery() != null)
            throw new IllegalArgumentException("Query parameters are not supported here");
        if(method.equals("GET"))
        {
            String length = exchange.getRequestHeaders().getFirst("Content-Length");
            if(length != null && !length.equals("0") || exchange.getRequestHeaders().containsKey("Transfer-Encoding"))
                throw new IllegalArgumentException("GET requests cannot contain a body");
            Object result;
            if(suffix.isEmpty()) result = mService.catalog();
            else if(suffix.equals("/options")) result = mService.options();
            else if(suffix.startsWith("/templates/")) result = mService.template(suffix.substring(11));
            else if(suffix.endsWith("/aliases"))
            {
                Map<String,String> query = query(exchange);
                if(!Set.of("true","false").contains(query.getOrDefault("assigned","false")))
                    throw new IllegalArgumentException("Invalid assignment filter");
                result = mService.aliases(suffix.substring(1,suffix.length()-8),query.getOrDefault("q",""),
                    Boolean.parseBoolean(query.getOrDefault("assigned","false")),
                    integer(query.getOrDefault("offset","0")),integer(query.getOrDefault("limit","50")));
            }
            else if(suffix.length()>1 && suffix.indexOf('/',1)<0) result = mService.get(suffix.substring(1));
            else { notFound(exchange); return; }
            sendData(exchange,200,result); return;
        }
        if(method.equals("POST") && suffix.equals("/feeds/refresh"))
        { readJsonObject(exchange,Set.of());sendData(exchange,200,Map.of("items",mService.feeds()));return; }
        if(method.equals("POST") && suffix.equals("/feeds"))
        {
            JsonNode body = readJsonObject(exchange,Set.of("feed_id","revision"));
            if(!body.path("feed_id").isIntegralNumber() || !body.path("feed_id").canConvertToInt())
                throw new IllegalArgumentException("Invalid feed identity");
            sendData(exchange,201,mService.addFeed(body.path("feed_id").intValue(),requiredText(body,"revision",100))); return;
        }
        if(method.equals("POST") && suffix.equals("/test"))
        {
            JsonNode body = readJsonObject(exchange,Set.of("configuration_id","provider","settings"));
            String id = body.hasNonNull("configuration_id") ? requiredText(body,"configuration_id",36) : null;
            sendData(exchange,200,mService.test(id,requiredText(body,"provider",80),settings(body)));return;
        }
        if(method.equals("POST") && suffix.endsWith("/aliases"))
        {
            if(exchange.getRequestURI().getRawQuery()!=null) throw new IllegalArgumentException("Assignment saves cannot have query parameters");
            JsonNode body=readJsonObject(exchange,Set.of("revision","add","remove"));
            sendData(exchange,200,mService.assign(suffix.substring(1,suffix.length()-8),ids(body,"add"),ids(body,"remove"),
                requiredText(body,"revision",100)));return;
        }
        if(method.equals("POST") && suffix.isEmpty() || method.equals("PUT") && suffix.length()>1 && suffix.indexOf('/',1)<0)
        {
            JsonNode body=readJsonObject(exchange,Set.of("revision","provider","settings"));
            sendData(exchange,suffix.isEmpty()?201:200,mService.save(suffix.isEmpty()?null:suffix.substring(1),
                requiredText(body,"provider",80),settings(body),requiredText(body,"revision",100)));return;
        }
        if(method.equals("DELETE") && suffix.length()>1 && suffix.indexOf('/',1)<0)
        {
            JsonNode body=readJsonObject(exchange,Set.of("revision"));
            sendData(exchange,200,mService.delete(suffix.substring(1),requiredText(body,"revision",100)));return;
        }
        methodNotAllowed(exchange,"GET, POST, PUT, DELETE");
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> settings(JsonNode body)
    {
        if(!body.path("settings").isObject()) throw new IllegalArgumentException("Settings must be an object");
        return MAPPER.convertValue(body.get("settings"),Map.class);
    }
    private static List<Long> ids(JsonNode body,String field)
    {
        JsonNode array=body.path(field);
        if(!array.isArray() || array.size()>500) throw new IllegalArgumentException("Invalid alias selection");
        List<Long> ids=new ArrayList<>();
        for(JsonNode value:array)
        {
            if(!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue()<=0 || value.longValue()>9007199254740991L)
                throw new IllegalArgumentException("Invalid alias identity");
            ids.add(value.longValue());
        }
        return ids;
    }
    private static int integer(String value)
    {
        try { return Integer.parseInt(value); }
        catch(NumberFormatException exception) { throw new IllegalArgumentException("Invalid page number"); }
    }
    private static Map<String,String> query(HttpExchange exchange)
    {
        Map<String,String> values=new HashMap<>();
        String raw=exchange.getRequestURI().getRawQuery();
        if(raw==null)return values;
        if(raw.length()>2048)throw new IllegalArgumentException("Query is too long");
        for(String part:raw.split("&",-1))
        {
            String[] pair=part.split("=",2);
            String key=ApiRequestDecoder.decodeComponent(pair[0],true);
            if(pair.length!=2 || !Set.of("q","assigned","offset","limit").contains(key) ||
                values.putIfAbsent(key,ApiRequestDecoder.decodeComponent(pair[1],true))!=null)
                throw new IllegalArgumentException("Invalid query parameters");
        }
        return values;
    }
}
