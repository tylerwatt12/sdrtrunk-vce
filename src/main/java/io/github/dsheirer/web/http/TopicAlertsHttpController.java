package io.github.dsheirer.web.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import io.github.dsheirer.alerts.AlertDevice;
import io.github.dsheirer.alerts.AlertAction;
import io.github.dsheirer.alerts.AlertRule;
import io.github.dsheirer.alerts.AlertTopic;
import io.github.dsheirer.alerts.TopicAlertConfiguration;
import io.github.dsheirer.alerts.TopicAlertService;
import io.github.dsheirer.alerts.delivery.PublicAccessVerifier;
import io.github.dsheirer.alerts.delivery.PushDeliveryService;
import io.github.dsheirer.web.auth.AccessTier;
import io.github.dsheirer.web.auth.WebAccessService;
import io.github.dsheirer.web.auth.WebCapability;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Administration uses existing capability/CSRF security; visible Topics independently admit anonymous listeners. */
public final class TopicAlertsHttpController
{
    public static final String ADMIN_PATH = "/api/v1/admin/alerts";
    public static final String PUBLIC_PATH = "/api/v1/alerts";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger LOG = LoggerFactory.getLogger(TopicAlertsHttpController.class);
    private final TopicAlertService mAlerts;
    private final PushDeliveryService mPush;
    private final Supplier<Map<String,Object>> mOptions;
    private final Runnable mConfigurationChanged;
    private final WebAccessService mAccess;
    private final Map<String,Admission> mAdmissions = new ConcurrentHashMap<>();

    public TopicAlertsHttpController(TopicAlertService alerts, PushDeliveryService push,
                                    Supplier<Map<String,Object>> options)
    {
        this(alerts, push, options, () -> { });
    }

    public TopicAlertsHttpController(TopicAlertService alerts, PushDeliveryService push,
                                    Supplier<Map<String,Object>> options, Runnable configurationChanged)
    {
        this(alerts, push, options, configurationChanged, null);
    }

    public TopicAlertsHttpController(TopicAlertService alerts, PushDeliveryService push,
                                    Supplier<Map<String,Object>> options, Runnable configurationChanged, WebAccessService access)
    {
        mAlerts = alerts; mPush = push; mOptions = options; mConfigurationChanged = configurationChanged; mAccess = access;
    }

    public void handleAdmin(HttpExchange exchange) throws IOException
    {
        handle(exchange, true);
    }

    public void handlePublic(HttpExchange exchange) throws IOException
    {
        WebRequestSecurity.prepareSecurityHeaders(exchange);
        if(!Set.of("GET", "HEAD").contains(exchange.getRequestMethod()) && !WebRequestSecurity.hasSameOrigin(exchange))
        {
            ApiHttpResponse.sendError(exchange, 403, "request_rejected", "The subscription request was rejected. Open Topics on this VCE site and try again."); return;
        }
        handle(exchange, false);
    }

    private void handle(HttpExchange exchange, boolean admin) throws IOException
    {
        WebRequestSecurity.prepareSecurityHeaders(exchange);
        String prefix = admin ? ADMIN_PATH : PUBLIC_PATH;
        String raw = exchange.getRequestURI().getRawPath();
        if(!raw.startsWith(prefix + "/")) { WebHttpSupport.notFound(exchange); return; }
        String path;
        try { path = ApiRequestDecoder.decodeComponent(raw.substring(prefix.length()), false); }
        catch(IllegalArgumentException ignored) { ApiHttpResponse.sendError(exchange, 400, "invalid_path", "This alert address is invalid"); return; }
        try
        {
            if(admin) admin(exchange, path); else guest(exchange, path);
        }
        catch(WebHttpSupport.RequestException exception)
        {
            ApiHttpResponse.sendError(exchange, exception.status(), exception.code(), exception.getMessage());
        }
        catch(ConcurrentModificationException ignored)
        {
            ApiHttpResponse.sendError(exchange, 409, "revision_conflict", "Alert settings changed. Reload before trying again.");
        }
        catch(SecurityException ignored)
        {
            ApiHttpResponse.sendError(exchange, 403, "subscription_unavailable", "This device connection is unavailable. Subscribe again.");
        }
        catch(IllegalArgumentException exception)
        {
            ApiHttpResponse.sendError(exchange, 422, "invalid_alert_setting", exception.getMessage());
        }
        catch(IllegalStateException exception)
        {
            if(admin) ApiHttpResponse.sendError(exchange, 503, "alerts_unavailable", "Notifications are temporarily unavailable. Try again later.");
            else ApiHttpResponse.sendError(exchange, 409, "alerts_not_ready", "Notifications are not ready. Ask the administrator to finish push setup.");
        }
        catch(io.github.dsheirer.alerts.delivery.RadioResolvePushClient.GatewayException exception)
        {
            int status = exception.status();
            if(status == 401 || status == 403)
                ApiHttpResponse.sendError(exchange, 403, "gateway_not_approved", "The cloud connection is not approved. Check the approval status.");
            else if(status == 429)
                ApiHttpResponse.sendError(exchange, 429, "gateway_busy", "Too many requests. Try again later.");
            else if(status == 404 || status >= 500)
                ApiHttpResponse.sendError(exchange, 503, "gateway_unavailable", "The cloud notification service is unavailable. Try again later.");
            else ApiHttpResponse.sendError(exchange, 422, "gateway_request_rejected", "The cloud notification service did not accept this request. Check the notification settings and try again.");
        }
        catch(IOException exception)
        {
            logFailure(failureOperation(admin, exchange.getRequestMethod(), path), exception);
            ApiHttpResponse.sendError(exchange, 503, "gateway_unavailable",
                "Notifications are temporarily unavailable. Try again later.");
        }
        catch(Exception exception)
        {
            if(exception instanceof InterruptedException) Thread.currentThread().interrupt();
            logFailure(failureOperation(admin, exchange.getRequestMethod(), path), exception);
            ApiHttpResponse.sendError(exchange, 503, "alerts_unavailable", "Notifications are temporarily unavailable. Try again later.");
        }
    }

    /** Only fixed operation names and exception types reach logs; subscription material never does. */
    enum FailureOperation
    {
        ADMIN_SETUP, ADMIN_TOPICS, ADMIN_RULES, ADMIN_PUBLIC_TEST, ADMIN_REGISTRATION, ADMIN_REQUEST,
        PUBLIC_TOPICS, PUBLIC_HISTORY, PUBLIC_PAIRING, PUBLIC_PAIRING_STATUS, PUBLIC_SUBSCRIPTION_READ,
        PUBLIC_SUBSCRIPTION_SAVE, PUBLIC_UNSUBSCRIBE, PUBLIC_SUBSCRIPTION_REFRESH, PUBLIC_REQUEST
    }

    static FailureOperation failureOperation(boolean admin, String method, String path)
    {
        if(admin) return switch(path)
        {
            case "/config" -> FailureOperation.ADMIN_SETUP;
            case "/topics" -> FailureOperation.ADMIN_TOPICS;
            case "/rules" -> FailureOperation.ADMIN_RULES;
            case "/public-test" -> FailureOperation.ADMIN_PUBLIC_TEST;
            case "/registration" -> FailureOperation.ADMIN_REGISTRATION;
            default -> path.startsWith("/topics/") ? FailureOperation.ADMIN_TOPICS :
                path.startsWith("/rules/") ? FailureOperation.ADMIN_RULES : FailureOperation.ADMIN_REQUEST;
        };
        return switch(path)
        {
            case "/topics" -> FailureOperation.PUBLIC_TOPICS;
            case "/history" -> FailureOperation.PUBLIC_HISTORY;
            case "/enrollments" -> FailureOperation.PUBLIC_PAIRING;
            case "/subscriptions/me/refresh" -> FailureOperation.PUBLIC_SUBSCRIPTION_REFRESH;
            case "/subscriptions/me" -> switch(method)
            {
                case "GET" -> FailureOperation.PUBLIC_SUBSCRIPTION_READ;
                case "PUT" -> FailureOperation.PUBLIC_SUBSCRIPTION_SAVE;
                case "DELETE" -> FailureOperation.PUBLIC_UNSUBSCRIBE;
                default -> FailureOperation.PUBLIC_REQUEST;
            };
            default -> path.startsWith("/enrollments/") ? FailureOperation.PUBLIC_PAIRING_STATUS :
                path.startsWith("/history/") ? FailureOperation.PUBLIC_HISTORY : FailureOperation.PUBLIC_REQUEST;
        };
    }

    static void logFailure(FailureOperation operation, Exception failure)
    {
        Throwable cause = failure;
        SQLException databaseFailure = null;
        String causeClass = "none";
        for(int depth = 0; cause != null && depth < 8; depth++, cause = cause.getCause())
        {
            if(cause instanceof SQLException sql && databaseFailure == null) databaseFailure = sql;
            if(cause != failure) causeClass = cause.getClass().getSimpleName();
            if(cause instanceof java.net.http.HttpTimeoutException || cause instanceof java.net.SocketException ||
                cause instanceof java.net.UnknownHostException || cause instanceof javax.net.ssl.SSLException ||
                cause instanceof java.util.concurrent.TimeoutException) break;
        }
        String sqlState = databaseFailure != null ? databaseFailure.getSQLState() : null;
        if(sqlState == null || !sqlState.matches("[A-Z0-9]{5}")) sqlState = "none";
        LOG.warn("Topic notification request failed: operation={}, exception={}, cause={}, sql_state={}, sql_code={}",
            operation.name(), failure.getClass().getSimpleName(), causeClass, sqlState,
            databaseFailure != null ? databaseFailure.getErrorCode() : 0);
    }

    private void admin(HttpExchange exchange, String path) throws Exception
    {
        String method = exchange.getRequestMethod();
        if(!WebHttpSupport.requireNoQuery(exchange)) return;
        switch(path)
        {
            case "/config" -> {
                if("GET".equals(method)) send(exchange, config());
                else if("PUT".equals(method))
                {
                    JsonNode body = WebHttpSupport.readJsonObject(exchange, Set.of("revision", "enabled", "mode", "site_url", "sender_label", "test_token"));
                    long revision = revision(body);
                    TopicAlertConfiguration current = mAlerts.configuration();
                    if(!body.path("enabled").isBoolean()) throw new IllegalArgumentException("Choose whether notifications are enabled");
                    boolean enabled = body.path("enabled").asBoolean();
                    String mode = text(body, "mode", 16);
                    String url = "direct".equals(mode) ? PublicAccessVerifier.normalizeUrl(text(body, "site_url", 2048)) : "";
                    if(enabled && "direct".equals(mode) && !mPush.verifier().passed(url, body.path("test_token").asText()))
                    {
                        ApiHttpResponse.sendError(exchange, 422, "public_test_required", "Test public HTTPS access for this URL before saving Direct delivery."); return;
                    }
                    var push = new TopicAlertConfiguration.PushSettings(enabled, mode, url,
                        body.path("sender_label").asText(current.push().senderLabel()));
                    mAlerts.updateConfiguration(new TopicAlertConfiguration(current.revision(), push, current.topics(), current.rules(), current.builtinActionButtonEnabled()), revision);
                    mConfigurationChanged.run();
                    send(exchange, config());
                }
                else WebHttpSupport.methodNotAllowed(exchange, "GET, PUT");
            }
            case "/topics", "/rules" -> {
                if("GET".equals(method)) send(exchange, catalog());
                else if("POST".equals(method) || "PUT".equals(method))
                {
                    String field = path.equals("/topics") ? "topic" : "rule";
                    JsonNode body = WebHttpSupport.readJsonObject(exchange, field.equals("topic") ?
                        Set.of("revision", field, "builtin_action_button_enabled") : Set.of("revision", field));
                    try
                    {
                        if(field.equals("topic"))
                        {
                            Boolean builtinAction = null;
                            if(body.has("builtin_action_button_enabled"))
                            {
                                if(!body.path("builtin_action_button_enabled").isBoolean())
                                    throw new IllegalArgumentException("Choose whether the alert action button is enabled");
                                builtinAction = body.path("builtin_action_button_enabled").asBoolean();
                            }
                            mAlerts.saveTopic(JSON.treeToValue(body.path(field), AlertTopic.class), revision(body), builtinAction);
                        }
                        else
                        {
                            if(body.path(field).has("action_button_enabled") && !body.path(field).path("action_button_enabled").isBoolean())
                                throw new IllegalArgumentException("Choose whether the alert action button is enabled");
                            mAlerts.saveRule(JSON.treeToValue(body.path(field), AlertRule.class), revision(body));
                        }
                    }
                    catch(com.fasterxml.jackson.core.JsonProcessingException ignored)
                    { throw new IllegalArgumentException("Check the alert fields and try saving again"); }
                    send(exchange, catalog());
                }
                else WebHttpSupport.methodNotAllowed(exchange, "GET, POST, PUT");
            }
            case "/public-test" -> {
                if(!method(exchange, "POST")) return;
                JsonNode body = WebHttpSupport.readJsonObject(exchange, Set.of("site_url"));
                send(exchange, mPush.verifier().test(text(body, "site_url", 2048)));
            }
            case "/registration" -> {
                if("POST".equals(method))
                {
                    JsonNode body = WebHttpSupport.readJsonObject(exchange, Set.of("sender_label"));
                    String senderLabel = body.has("sender_label") ? text(body, "sender_label", 80) : null;
                    send(exchange, mPush.register(senderLabel)); mConfigurationChanged.run();
                }
                else if("GET".equals(method)) send(exchange, mPush.refreshRegistration());
                else if("DELETE".equals(method)) { mPush.deregister(); send(exchange, mPush.registration()); }
                else WebHttpSupport.methodNotAllowed(exchange, "GET, POST, DELETE");
            }
            default -> {
                if(path.startsWith("/rules/") && exchange.getRequestURI().getRawPath().endsWith("/test"))
                {
                    if(!method(exchange, "POST")) return;
                    JsonNode body = WebHttpSupport.readJsonObject(exchange, Set.of("revision"));
                    long revision = revision(body);
                    if(!mPush.ready()) throw new IllegalArgumentException("Finish push setup and enable notifications before sending a test alert.");
                    String id = path.substring("/rules/".length(), path.length() - "/test".length());
                    mAlerts.testRule(id, revision);
                    send(exchange, Map.of("queued", true));
                }
                else if(path.startsWith("/topics/") || path.startsWith("/rules/"))
                {
                    if(!method(exchange, "DELETE")) return;
                    JsonNode body = WebHttpSupport.readJsonObject(exchange, Set.of("revision"));
                    String id = path.substring(path.indexOf('/', 1) + 1);
                    if(path.startsWith("/topics/")) mAlerts.deleteTopic(id, revision(body));
                    else mAlerts.deleteRule(id, revision(body));
                    send(exchange, catalog());
                }
                else WebHttpSupport.notFound(exchange);
            }
        }
    }

    private void guest(HttpExchange exchange, String path) throws Exception
    {
        String method = exchange.getRequestMethod();
        if(path.startsWith("/public-test/"))
        {
            if(!method(exchange, "GET") || !WebHttpSupport.requireNoQuery(exchange)) return;
            String challenge = path.substring("/public-test/".length());
            if(mPush.verifier().hasChallenge(challenge)) ApiHttpResponse.sendDocument(exchange, 200, Map.of("token", challenge));
            else WebHttpSupport.notFound(exchange);
            return;
        }
        if(path.startsWith("/history/"))
        {
            if(!method(exchange, "GET") || !WebHttpSupport.requireNoQuery(exchange)) return;
            var item = mAlerts.historyItem(path.substring("/history/".length()));
            if(item.isPresent()) send(exchange, publicHistoryItem(item.get())); else WebHttpSupport.notFound(exchange);
            return;
        }
        switch(path)
        {
            case "/topics" -> {
                if(!method(exchange, "GET") || !WebHttpSupport.requireNoQuery(exchange)) return;
                var push = mAlerts.configuration().push();
                send(exchange, Map.of("topics", mAlerts.configuration().topics().stream().filter(AlertTopic::visible).toList(),
                    "enabled", push.enabled(), "mode", push.mode(), "push_enabled", mPush.ready(),
                    "vapid_public_key", "direct".equals(push.mode()) ? mPush.publicKey() : ""));
            }
            case "/history" -> {
                if(!method(exchange, "GET")) return;
                Map<String,String> query = query(exchange, Set.of("topic_id", "topic", "limit", "offset"));
                String topic = query.getOrDefault("topic_id", query.getOrDefault("topic", ""));
                List<String> ids = mAlerts.configuration().topics().stream().filter(AlertTopic::visible).map(AlertTopic::id)
                    .filter(id -> topic.isEmpty() || topic.equals(id)).toList();
                Map<String,Object> page = new LinkedHashMap<>(mAlerts.history(ids, integer(query, "limit", 30, 1, 100), integer(query, "offset", 0, 0, 10000)));
                @SuppressWarnings("unchecked") List<Map<String,Object>> items = (List<Map<String,Object>>)page.get("items");
                page.put("items", items.stream().map(this::publicHistoryItem).toList());
                send(exchange, page);
            }
            case "/enrollments" -> {
                if(!method(exchange, "POST") || !WebHttpSupport.requireNoQuery(exchange)) return;
                if(!admit(exchange)) return;
                JsonNode body = WebHttpSupport.readJsonObject(exchange, Set.of("topic_ids", "prefs", "subscription"));
                send(exchange, mPush.enroll(topicIds(body), body.path("subscription")));
            }
            case "/subscriptions/me" -> {
                if(!WebHttpSupport.requireNoQuery(exchange)) return;
                String token = bearer(exchange);
                if("GET".equals(method)) send(exchange, publicDevice(mPush.managedDevice(token)));
                else if("PUT".equals(method))
                {
                    JsonNode body = WebHttpSupport.readJsonObject(exchange, Set.of("topic_ids", "prefs"));
                    mPush.updateDevice(token, topicIds(body));
                    send(exchange, publicDevice(mPush.managedDevice(token)));
                }
                else if("DELETE".equals(method)) { mPush.disconnect(token); send(exchange, Map.of("status", "disconnected")); }
                else WebHttpSupport.methodNotAllowed(exchange, "GET, PUT, DELETE");
            }
            case "/subscriptions/me/refresh" -> {
                if(!method(exchange, "POST") || !WebHttpSupport.requireNoQuery(exchange)) return;
                JsonNode body = WebHttpSupport.readJsonObject(exchange, Set.of("subscription"));
                mPush.refreshSubscription(bearer(exchange), body.path("subscription"));
                send(exchange, publicDevice(mPush.managedDevice(bearer(exchange))));
            }
            default -> {
                if(path.startsWith("/enrollments/"))
                {
                    if(!method(exchange, "GET") || !WebHttpSupport.requireNoQuery(exchange)) return;
                    send(exchange, mPush.enrollment(path.substring("/enrollments/".length()), bearer(exchange)));
                }
                else WebHttpSupport.notFound(exchange);
            }
        }
    }

    private Map<String,Object> config() throws Exception
    {
        var config = mAlerts.configuration(); var push = config.push();
        return Map.of("revision", config.revision(), "enabled", push.enabled(), "mode", push.mode(), "site_url", push.siteUrl(),
            "sender_label", push.senderLabel(), "public_access", mPush.verifier().status(push.siteUrl()),
            "builtin_action_button_enabled", config.builtinActionButtonEnabled(),
            "registration", mPush.registration(), "direct", Map.of("vapid_public_key", "direct".equals(push.mode()) ? mPush.publicKey() : ""));
    }

    private Map<String,Object> catalog() throws Exception
    {
        var config = mAlerts.configuration(); List<AlertDevice> devices = mAlerts.devices();
        List<Map<String,Object>> topics = config.topics().stream().map(topic -> {
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("id", topic.id()); row.put("name", topic.name()); row.put("description", topic.description());
            row.put("visible", topic.visible()); row.put("enabled", topic.enabled()); row.put("built_in", topic.builtIn());
            row.put("rule_count", config.rules().stream().filter(rule -> rule.topicId().equals(topic.id())).count());
            row.put("subscription_count", devices.stream().filter(device -> device.topicIds().contains(topic.id())).count());
            return row;
        }).toList();
        return Map.of("revision", config.revision(), "enabled", config.push().enabled(), "topics", topics, "rules", config.rules(), "options", mOptions.get(),
            "builtin_action_button_enabled", config.builtinActionButtonEnabled());
    }

    private Map<String,Object> publicHistoryItem(Map<String,Object> item)
    {
        Map<String,Object> result = new LinkedHashMap<>(item);
        Object saved = result.remove("action");
        AlertAction action = saved instanceof AlertAction value ? value : AlertAction.NONE;
        WebCapability capability = action.kind().equals("live") ? WebCapability.LIVE_VIEW : WebCapability.RADIO_VIEW;
        boolean allowed = !action.kind().equals("none") && mAccess != null && mAccess.isAllowed(AccessTier.PUBLIC, capability);
        result.put("action", allowed ? action.toMap() : null);
        return result;
    }

    private static Map<String,Object> publicDevice(AlertDevice device)
    {
        return Map.of("device_id", device.id(), "topic_ids", device.topicIds(), "status", device.subscriptionExpired() ? "reconnect_required" : "connected", "mode", device.method());
    }

    private synchronized boolean admit(HttpExchange exchange) throws IOException
    {
        long now = System.currentTimeMillis();
        mAdmissions.values().removeIf(value -> value.startedAt() + 60_000 < now);
        String key = WebRequestSecurity.sourceKey(exchange);
        Admission old = mAdmissions.getOrDefault(key, new Admission(now, 0));
        if(old.count() >= 10 || mAdmissions.size() >= 1024 && !mAdmissions.containsKey(key))
        {
            exchange.getResponseHeaders().set("Retry-After", "60");
            ApiHttpResponse.sendError(exchange, 429, "enrollment_limit", "Wait a minute before starting another device connection."); return false;
        }
        mAdmissions.put(key, new Admission(old.startedAt(), old.count() + 1)); return true;
    }

    private static List<String> topicIds(JsonNode body)
    {
        JsonNode values = body.path("topic_ids");
        if(!values.isArray() || values.size() > 256) throw new IllegalArgumentException("Choose topics before enabling notifications");
        List<String> ids = new ArrayList<>();
        for(JsonNode value: values)
        {
            if(!value.isTextual() || value.asText().length() > 80) throw new IllegalArgumentException("Choose valid topics");
            if(!ids.contains(value.asText())) ids.add(value.asText());
        }
        return List.copyOf(ids);
    }

    private static String bearer(HttpExchange exchange)
    {
        List<String> headers = exchange.getRequestHeaders().get("Authorization");
        if(headers == null || headers.size() != 1 || !headers.getFirst().matches("Bearer [A-Za-z0-9_-]{43}")) throw new SecurityException();
        return headers.getFirst().substring(7);
    }

    private static String text(JsonNode body, String name, int maximum)
    {
        JsonNode value = body.path(name);
        if(!value.isTextual() || value.asText().length() > maximum) throw new IllegalArgumentException("Check the " + name.replace('_', ' ') + " field");
        return value.asText();
    }

    private static long revision(JsonNode body)
    {
        JsonNode value = body.path("revision");
        if(!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 1) throw new IllegalArgumentException("Reload the alert settings before saving");
        return value.longValue();
    }

    private static Map<String,String> query(HttpExchange exchange, Set<String> allowed)
    {
        Map<String,String> result = new LinkedHashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        if(raw != null) for(String pair: raw.split("&"))
        {
            String[] parts = pair.split("=", 2);
            String key = ApiRequestDecoder.decodeComponent(parts[0], true);
            if(!allowed.contains(key) || result.containsKey(key)) throw new IllegalArgumentException("Unknown or repeated alert filter");
            result.put(key, ApiRequestDecoder.decodeComponent(parts.length == 2 ? parts[1] : "", true));
        }
        return result;
    }

    private static int integer(Map<String,String> query, String key, int fallback, int minimum, int maximum)
    {
        try { int value = Integer.parseInt(query.getOrDefault(key, Integer.toString(fallback))); if(value >= minimum && value <= maximum) return value; }
        catch(NumberFormatException ignored) { }
        throw new IllegalArgumentException("Invalid alert history page");
    }

    private static boolean method(HttpExchange exchange, String expected) throws IOException
    {
        if(expected.equals(exchange.getRequestMethod())) return true;
        WebHttpSupport.methodNotAllowed(exchange, expected); return false;
    }

    private static void send(HttpExchange exchange, Object body) throws IOException { ApiHttpResponse.sendData(exchange, 200, body); }
    private record Admission(long startedAt, int count) { }
}
