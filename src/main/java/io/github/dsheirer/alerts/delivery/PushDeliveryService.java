package io.github.dsheirer.alerts.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.alerts.AlertDelivery;
import io.github.dsheirer.alerts.AlertDevice;
import io.github.dsheirer.alerts.AlertEnrollment;
import io.github.dsheirer.alerts.AlertTopic;
import io.github.dsheirer.alerts.TopicAlertConfiguration.PushSettings;
import io.github.dsheirer.alerts.TopicAlertService;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Durable outbox consumption and outbound status polling, independent of receiver/decoder threads. */
public final class PushDeliveryService implements AutoCloseable
{
    private static final Logger LOG = LoggerFactory.getLogger(PushDeliveryService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private final TopicAlertService mAlerts;
    private final PushCredentialStore mCredentials;
    private final RadioResolvePushClient mGateway;
    private final DirectWebPushSender mDirect = new DirectWebPushSender();
    private final PublicAccessVerifier mVerifier;
    private final ScheduledExecutorService mWorker = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "topic push delivery"); thread.setDaemon(true); return thread;
    });
    private final ScheduledExecutorService mRelayWorker = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "RadioResolve push status"); thread.setDaemon(true); return thread;
    });
    private volatile boolean mClosed;

    public PushDeliveryService(Path database, TopicAlertService alerts) throws Exception
    {
        this(database, alerts, new RadioResolvePushClient());
    }

    PushDeliveryService(Path database, TopicAlertService alerts, RadioResolvePushClient gateway) throws Exception
    {
        mAlerts = alerts; mCredentials = new PushCredentialStore(database);
        mGateway = gateway; mVerifier = new PublicAccessVerifier(gateway);
    }

    public void start()
    {
        mWorker.scheduleWithFixedDelay(this::tick, 2, 2, TimeUnit.SECONDS);
        mRelayWorker.scheduleWithFixedDelay(this::relayTick, 2, 15, TimeUnit.SECONDS);
    }
    public PublicAccessVerifier verifier() { return mVerifier; }

    public Map<String,Object> registration()
    {
        var credentials = mCredentials.get();
        return Map.of("status", credentials.registrationStatus(), "sender_id", credentials.senderId());
    }

    public Map<String,Object> register() throws Exception { return register(null); }

    public Map<String,Object> register(String senderLabel) throws Exception
    {
        PushCredentialStore.Credentials credentials;
        String requestedLabel;
        synchronized(this)
        {
            if(mClosed) throw new IllegalStateException("Notifications are unavailable");
            var current = mAlerts.configuration();
            var push = current.push();
            var requested = new PushSettings(push.enabled(), push.mode(), push.siteUrl(), senderLabel != null ? senderLabel : push.senderLabel());
            if(!requested.senderLabel().equals(push.senderLabel()))
                mAlerts.updateConfiguration(new io.github.dsheirer.alerts.TopicAlertConfiguration(current.revision(), requested, current.topics(), current.rules()), current.revision());
            requestedLabel = requested.senderLabel();
            credentials = mCredentials.prepareRegistration();
        }
        JsonNode result = mGateway.register(credentials.installationId(), requestedLabel,
            credentials.apiKey());
        synchronized(this)
        {
            if(!mClosed && credentials.apiKey().equals(mCredentials.get().apiKey()) &&
                !"deregistered".equals(mCredentials.get().registrationStatus()))
                mCredentials.registration(result.path("sender_id").asText(), result.path("status").asText("pending"));
        }
        return registration();
    }

    public Map<String,Object> refreshRegistration() throws Exception
    {
        var credentials = mCredentials.get();
        if(credentials.apiKey().isEmpty() || "deregistered".equals(credentials.registrationStatus())) return registration();
        JsonNode result = mGateway.status(credentials.apiKey());
        synchronized(this)
        {
            if(!mClosed && credentials.apiKey().equals(mCredentials.get().apiKey()) &&
                !"deregistered".equals(mCredentials.get().registrationStatus()))
                mCredentials.registration(result.path("sender_id").asText(), result.path("status").asText("pending"));
        }
        return registration();
    }

    public synchronized void deregister() throws Exception
    {
        var credentials = mCredentials.get();
        if(!credentials.apiKey().isEmpty()) mGateway.deregister(credentials.apiKey());
        mCredentials.registration(credentials.senderId(), "deregistered");
        for(AlertDevice device: mAlerts.devices()) if("proxy".equals(device.method())) mAlerts.unsubscribe(device.id());
        List<AlertEnrollment> pending;
        while(!(pending = mAlerts.pendingEnrollments(100)).isEmpty())
            for(AlertEnrollment enrollment: pending) mAlerts.saveEnrollment(copyEnrollment(enrollment, "cancelled", ""));
    }

    public String publicKey() throws Exception { return directKeys().publicKey(); }

    private synchronized WebPushCodec.Keys directKeys() throws Exception
    {
        if(mClosed) throw new IllegalStateException("Notifications are unavailable");
        return mCredentials.directKeys();
    }

    public boolean ready()
    {
        PushSettings push = mAlerts.configuration().push();
        return !mClosed && push.enabled() && ("direct".equals(push.mode()) ||
            "approved".equals(mCredentials.get().registrationStatus()));
    }

    public Map<String,Object> enroll(List<String> topics, JsonNode subscription) throws Exception
    {
        return enroll(topics, JSON.createObjectNode(), subscription);
    }

    public synchronized Map<String,Object> enroll(List<String> topics, JsonNode ignoredLegacyPreferences, JsonNode subscription) throws Exception
    {
        if(!ready()) throw new IllegalStateException("Notifications are not ready. Ask the administrator to finish push setup.");
        requireTopics(topics, List.of());
        String token = PublicAccessVerifier.token(), hash = hashToken(token);
        String id = UUID.randomUUID().toString();
        if("direct".equals(mAlerts.configuration().push().mode()))
        {
            String endpoint = subscription.path("endpoint").asText();
            String p256dh = subscription.path("keys").path("p256dh").asText();
            String auth = subscription.path("keys").path("auth").asText();
            DirectWebPushSender.validateEndpoint(endpoint);
            WebPushCodec.validateSubscription(p256dh, auth);
            AlertDevice existing = mAlerts.devices().stream().filter(device -> "direct".equals(device.method()) && endpoint.equals(device.endpoint())).findFirst().orElse(null);
            if(existing != null && (!p256dh.equals(existing.p256dh()) || !auth.equals(existing.auth())))
                throw new IllegalArgumentException("This browser subscription is already connected. Reconnect it from Notification settings.");
            if(existing != null) id = existing.id();
            boolean expired = existing != null && existing.subscriptionExpired();
            mAlerts.saveDevice(new AlertDevice(id, "This browser", "direct", "", endpoint, p256dh, auth, hash,
                topics, expired));
            return Map.of("device_id", id, "manage_token", token, "status", expired ? "reconnect_required" : "connected", "mode", "direct");
        }
        // Validate the local enrollment before creating a remote pairing session.
        long now = System.currentTimeMillis();
        new AlertEnrollment(id, "pending", hash, topics, now, now + 900_000, "pending", "", "", "");
        JsonNode remote = mGateway.createEnrollment(mCredentials.get().apiKey());
        AlertEnrollment enrollment = new AlertEnrollment(id, remote.path("id").asText(), hash, topics,
            now, remote.path("expires_at_ms").asLong(now + 900_000), "pending", "",
            remote.path("enrollment_url").asText(), remote.path("code").asText());
        java.net.URI enrollmentUrl = java.net.URI.create(enrollment.enrollmentUrl());
        if(!"https".equals(enrollmentUrl.getScheme()) || !"radioresolve.com".equals(enrollmentUrl.getHost()) ||
            enrollmentUrl.getUserInfo() != null || enrollmentUrl.getPort() != -1 ||
            !enrollmentUrl.getPath().startsWith("/vce-push/"))
            throw new java.io.IOException("RadioResolve returned an invalid enrollment address");
        mAlerts.saveEnrollment(enrollment);
        return Map.of("id", id, "manage_token", token, "status", "pending", "mode", "proxy",
                "enrollment_url", remote.path("enrollment_url").asText(), "code", enrollment.remoteEnrollmentId() + "." + enrollment.code(),
            "expires_at_ms", enrollment.expiresAtMs());
    }

    public Map<String,Object> enrollment(String id, String token) throws Exception
    {
        AlertEnrollment enrollment = mAlerts.enrollment(id).orElse(null);
        if(enrollment == null || !constantHashEquals(enrollment.manageTokenHash(), token))
            throw new SecurityException("This device connection is unavailable. Start a new subscription.");
        if("pending".equals(enrollment.status()))
        {
            JsonNode remote = mGateway.enrollment(mCredentials.get().apiKey(), enrollment.remoteEnrollmentId());
            enrollment = completeEnrollment(enrollment, remote);
            return Map.of("id", id, "status", enrollment.status(), "device_id", enrollment.deviceId(),
                "enrollment_url", enrollment.enrollmentUrl(), "code", enrollment.remoteEnrollmentId() + "." + enrollment.code(),
                "expires_at_ms", enrollment.expiresAtMs(), "topic_ids", enrollment.topicIds());
        }
        return Map.of("id", id, "status", enrollment.status(), "device_id", enrollment.deviceId());
    }

    public AlertDevice managedDevice(String token) throws Exception
    {
        if(mClosed) throw new IllegalStateException("Notifications are unavailable");
        AlertDevice device = mAlerts.findDeviceByTokenHash(hashToken(token)).orElse(null);
        if(device == null) throw new SecurityException("This device connection is unavailable. Subscribe again.");
        return device;
    }

    public void updateDevice(String token, List<String> topics) throws Exception
    {
        updateDevice(token, topics, JSON.createObjectNode());
    }

    public synchronized void updateDevice(String token, List<String> topics, JsonNode ignoredLegacyPreferences) throws Exception
    {
        AlertDevice old = managedDevice(token);
        requireTopics(topics, old.topicIds());
        mAlerts.saveDevice(new AlertDevice(old.id(), old.name(), old.method(), old.remoteDeviceId(), old.endpoint(),
            old.p256dh(), old.auth(), old.manageTokenHash(), topics, old.subscriptionExpired()));
    }

    public synchronized void refreshSubscription(String token, JsonNode subscription) throws Exception
    {
        AlertDevice old = managedDevice(token);
        if(!"direct".equals(old.method())) throw new IllegalArgumentException("Refresh this device on RadioResolve");
        String endpoint = subscription.path("endpoint").asText();
        String p256dh = subscription.path("keys").path("p256dh").asText();
        String auth = subscription.path("keys").path("auth").asText();
        DirectWebPushSender.validateEndpoint(endpoint); WebPushCodec.validateSubscription(p256dh, auth);
        if(mAlerts.devices().stream().anyMatch(device -> "direct".equals(device.method()) && !old.id().equals(device.id()) && endpoint.equals(device.endpoint())))
            throw new IllegalArgumentException("This browser subscription is already connected. Disconnect its previous connection first.");
        boolean unchanged = endpoint.equals(old.endpoint()) && p256dh.equals(old.p256dh()) && auth.equals(old.auth());
        mAlerts.saveDevice(new AlertDevice(old.id(), old.name(), old.method(), "", endpoint, p256dh, auth,
            old.manageTokenHash(), old.topicIds(),
            old.subscriptionExpired() && unchanged));
    }

    public synchronized void disconnect(String token) throws Exception
    {
        AlertDevice device = managedDevice(token);
        if("proxy".equals(device.method())) mGateway.disconnect(mCredentials.get().apiKey(), device.remoteDeviceId());
        mAlerts.unsubscribe(device.id());
    }

    private void requireTopics(List<String> ids, List<String> existing)
    {
        if(ids.size() > 256) throw new IllegalArgumentException("Too many topics selected");
        var visible = mAlerts.configuration().topics().stream().filter(topic -> topic.visible() && topic.enabled()).map(AlertTopic::id).toList();
        if(ids.stream().anyMatch(id -> !visible.contains(id) && !existing.contains(id)))
            throw new IllegalArgumentException("A selected topic is unavailable. Refresh the topic list.");
    }

    private synchronized AlertEnrollment completeEnrollment(AlertEnrollment old, JsonNode remote) throws Exception
    {
        if(mClosed) return old;
        AlertEnrollment current = mAlerts.enrollment(old.id()).orElse(null);
        if(current == null) return copyEnrollment(old, "expired", "");
        if(!"pending".equals(current.status())) return current;
        String status = remote.path("status").asText();
        if("completed".equals(status))
        {
            AlertDevice previous = mAlerts.findDeviceByTokenHash(old.manageTokenHash()).orElse(null);
            String remoteDevice = remote.path("device_id").asText();
            if(remoteDevice.isEmpty()) throw new IllegalStateException("The device connection could not be completed. Try again.");
            if(previous == null) previous = mAlerts.byRemoteDeviceId(remoteDevice).orElse(null);
            String deviceId = previous != null ? previous.id() : old.deviceId().isEmpty() ? UUID.randomUUID().toString() : old.deviceId();
            AlertDevice device = new AlertDevice(deviceId, "This device", "proxy", remoteDevice, "", "", "",
                old.manageTokenHash(), old.topicIds(), false);
            AlertEnrollment completed = copyEnrollment(old, "completed", deviceId);
            mAlerts.completeEnrollment(completed, device);
            return completed;
        }
        if("expired".equals(status) || old.expiresAtMs() <= System.currentTimeMillis() || "revoked".equals(status))
        {
            AlertEnrollment expired = copyEnrollment(old, "revoked".equals(status) ? "cancelled" : "expired", "");
            mAlerts.saveEnrollment(expired); return expired;
        }
        return old;
    }

    private static AlertEnrollment copyEnrollment(AlertEnrollment old, String status, String deviceId)
    {
        return new AlertEnrollment(old.id(), old.remoteEnrollmentId(), old.manageTokenHash(), old.topicIds(),
            old.createdAtMs(), old.expiresAtMs(), status, deviceId,
            old.enrollmentUrl(), old.code());
    }

    void tick()
    {
        if(mClosed) return;
        try
        {
            if(!ready()) return;
            var relayEvents = new java.util.HashSet<String>();
            for(AlertDelivery delivery: mAlerts.pendingDeliveries(20))
            {
                if(mClosed) return;
                if("proxy".equals(delivery.method()))
                {
                    if(relayEvents.add(delivery.alertId())) deliverRelayBatch(delivery);
                    continue;
                }
                deliver(delivery);
            }
        }
        catch(InterruptedException ignored) { Thread.currentThread().interrupt(); }
        catch(Exception exception) { LOG.debug("Topic push worker will retry ({})", exception.getClass().getSimpleName()); }
    }

    void relayTick()
    {
        if(mClosed || mCredentials.get().apiKey().isEmpty() || "deregistered".equals(mCredentials.get().registrationStatus())) return;
        try
        {
            refreshRegistration(); pollUpdates();
            if("approved".equals(mCredentials.get().registrationStatus())) reconcileQueuedRelay();
        }
        catch(InterruptedException ignored) { Thread.currentThread().interrupt(); }
        catch(Exception exception) { LOG.debug("RadioResolve status and queued preferences will be checked again ({})", exception.getClass().getSimpleName()); }
    }

    void deliverRelayBatch(AlertDelivery first) throws Exception
    {
        long now = System.currentTimeMillis();
        List<AlertDelivery> deliveries = mAlerts.deliveriesForAlert(first.alertId());
        if(now >= first.expiresAtMs())
        {
            for(AlertDelivery target: deliveries) if(List.of("pending", "retry").contains(target.state()))
                mAlerts.completeDelivery(target.id(), "expired", "", "The alert expired before sending", 0);
            return;
        }
        List<String> recipients = deliveries.stream().map(AlertDelivery::remoteDeviceId).filter(id -> !id.isEmpty()).distinct().sorted().toList();
        if(recipients.isEmpty()) return;
        try
        {
            List<String> suppressed = suppressedRecipients(first, deliveries);
            JsonNode result = mGateway.alert(mCredentials.get().apiKey(), relayBody(first, recipients, suppressed));
            synchronized(this)
            {
                if(mClosed) return;
                for(AlertDelivery target: deliveries) if(suppressed.contains(target.remoteDeviceId()) && List.of("pending", "retry", "queued").contains(target.state()))
                    mAlerts.completeDelivery(target.id(), "cancelled", target.remoteId(), "Notifications were stopped for this device", 0);
                mAlerts.queueRelayAlert(first.alertId(), result.path("alert_id").asText());
            }
        }
        catch(RadioResolvePushClient.GatewayException exception)
        {
            if(exception.status() == 429 || exception.status() >= 500) retryRelay(first, "RadioResolve is temporarily unavailable");
            else for(AlertDelivery target: deliveries) if(List.of("pending", "retry").contains(target.state()))
                mAlerts.completeDelivery(target.id(), "failed", "", "RadioResolve rejected the alert; check sender approval", 0);
        }
        catch(java.io.IOException exception) { retryRelay(first, "The alert could not be sent; retrying"); }
    }

    private Map<String,Object> relayBody(AlertDelivery first, List<String> recipients, List<String> suppressed)
    {
        return Map.of("client_alert_id", first.alertId(), "alert_name", first.alertName(), "topic_id", first.topicId(),
            "topic_name", first.topicName(), "title", first.title(), "body", first.body(), "device_ids", recipients,
            "expires_at_ms", first.expiresAtMs(), "suppressed_device_ids", suppressed);
    }

    private List<String> suppressedRecipients(AlertDelivery first, List<AlertDelivery> deliveries) throws Exception
    {
        return suppressedRecipients(first, deliveries, deviceSnapshot());
    }

    private Map<String,AlertDevice> deviceSnapshot() throws Exception
    {
        return mAlerts.devices().stream().collect(java.util.stream.Collectors.toMap(AlertDevice::id, device -> device));
    }

    private List<String> suppressedRecipients(AlertDelivery first, List<AlertDelivery> deliveries, Map<String,AlertDevice> devices)
    {
        var config = mAlerts.configuration();
        boolean topicAllowed = config.push().enabled() && "proxy".equals(config.push().mode()) &&
            config.topics().stream().anyMatch(topic -> topic.id().equals(first.topicId()) && topic.visible() && topic.enabled());
        var ids = new java.util.TreeSet<String>();
        for(AlertDelivery target: deliveries)
        {
            if(target.remoteDeviceId().isEmpty() || List.of("push_accepted", "opened").contains(target.state())) continue;
            AlertDevice device = devices.get(target.deviceId());
            if(!topicAllowed || "cancelled".equals(target.state()) || device == null || device.subscriptionExpired() ||
                !device.topicIds().contains(first.topicId()) || !target.remoteDeviceId().equals(device.remoteDeviceId())) ids.add(target.remoteDeviceId());
        }
        return List.copyOf(ids);
    }

    void reconcileQueuedRelay() throws Exception
    {
        Map<String,AlertDevice> devices = deviceSnapshot();
        for(AlertDelivery first: mAlerts.queuedRelayAlerts(100))
        {
            List<AlertDelivery> deliveries = mAlerts.deliveriesForAlert(first.alertId());
            if(System.currentTimeMillis() >= first.expiresAtMs())
            {
                for(AlertDelivery target: deliveries) if(List.of("pending", "retry", "queued").contains(target.state()))
                    mAlerts.completeDelivery(target.id(), "expired", target.remoteId(), "The alert expired before sending", 0);
                continue;
            }
            List<String> suppressed = suppressedRecipients(first, deliveries, devices);
            if(suppressed.isEmpty()) continue;
            List<String> recipients = deliveries.stream().map(AlertDelivery::remoteDeviceId).filter(id -> !id.isEmpty()).distinct().sorted().toList();
            mGateway.alert(mCredentials.get().apiKey(), relayBody(first, recipients, suppressed));
            synchronized(this)
            {
                if(mClosed) return;
                for(AlertDelivery target: deliveries) if(suppressed.contains(target.remoteDeviceId()) && List.of("pending", "retry", "queued").contains(target.state()))
                    mAlerts.completeDelivery(target.id(), "cancelled", target.remoteId(), "Notifications were stopped for this device", 0);
            }
        }
    }

    private synchronized void retryRelay(AlertDelivery first, String reason) throws Exception
    {
        if(mClosed) return;
        long delay = Math.min(300_000, 5_000L << Math.min(6, first.attempts()));
        mAlerts.retryRelayAlert(first.alertId(), reason, System.currentTimeMillis() + delay);
    }

    private void deliver(AlertDelivery delivery) throws Exception
    {
        long now = System.currentTimeMillis();
        AlertDevice device = mAlerts.device(delivery.deviceId()).orElse(null);
        boolean visible = mAlerts.configuration().topics().stream().anyMatch(topic -> topic.id().equals(delivery.topicId()) && topic.visible() && topic.enabled());
        if(device == null || device.subscriptionExpired() || !visible || !device.topicIds().contains(delivery.topicId()) ||
            !device.method().equals(mAlerts.configuration().push().mode()))
        {
            mAlerts.completeDelivery(delivery.id(), "cancelled", "", "Subscription or topic unavailable", 0); return;
        }
        if(now >= delivery.expiresAtMs())
        {
            mAlerts.completeDelivery(delivery.id(), "expired", "", "The alert expired before sending", 0); return;
        }
        try
        {
            String url = "listen".equals(delivery.clickAction()) ? "/?view=live" : "/?view=topics&topic=" + java.net.URLEncoder.encode(delivery.topicId(), StandardCharsets.UTF_8) +
                "&alert=" + java.net.URLEncoder.encode(delivery.alertId(), StandardCharsets.UTF_8);
            byte[] payload = JSON.writeValueAsBytes(Map.of("title", delivery.title(), "body", preview(delivery.body()),
                "url", url, "tag", "vce-" + delivery.alertId()));
            int status = mDirect.send(device.endpoint(), device.p256dh(), device.auth(), directKeys(),
                mAlerts.configuration().push().siteUrl(), payload, (delivery.expiresAtMs() - now) / 1000);
            synchronized(this)
            {
                if(mClosed) return;
                if(status == 404 || status == 410)
                {
                    mAlerts.markDeviceSubscriptionExpired(device.id(), device.endpoint(), device.p256dh(), device.auth());
                    mAlerts.completeDelivery(delivery.id(), "expired", "", "The browser subscription expired", 0);
                }
                else if(status >= 200 && status < 300) mAlerts.completeDelivery(delivery.id(), "push_accepted", "", "", 0);
                else if(status == 429 || status >= 500) retry(delivery, "The push provider is temporarily unavailable");
                else mAlerts.completeDelivery(delivery.id(), "failed", "", "The push provider rejected the message", 0);
            }
        }
        catch(java.io.IOException exception) { retry(delivery, "The notification could not be sent; retrying"); }
    }

    private synchronized void retry(AlertDelivery delivery, String reason) throws Exception
    {
        if(mClosed) return;
        long delay = Math.min(300_000, 5_000L << Math.min(6, delivery.attempts()));
        mAlerts.completeDelivery(delivery.id(), "retry", "", reason, System.currentTimeMillis() + delay);
    }

    private void pollUpdates() throws Exception
    {
        var credentials = mCredentials.get();
        JsonNode result = mGateway.updates(credentials.apiKey(), credentials.updateCursor());
        synchronized(this)
        {
            if(mClosed || !credentials.apiKey().equals(mCredentials.get().apiKey()) ||
                "deregistered".equals(mCredentials.get().registrationStatus())) return;
            for(JsonNode update: result.path("updates"))
            {
                String type = update.path("type").asText();
                if("alert_delivery".equals(type) || "alert_opened".equals(type))
                {
                    String clientId = update.path("client_alert_id").asText();
                    if(!clientId.isEmpty())
                    {
                        AlertDelivery delivery = mAlerts.deliveryForRemote(clientId, update.path("device_id").asText()).orElse(null);
                        if(delivery != null && delivery.method().equals("proxy"))
                            mAlerts.completeDelivery(delivery.id(), "alert_opened".equals(type) ? "opened" : update.path("status").asText(),
                                update.path("alert_id").asText(), "", 0);
                    }
                }
                else if("enrollment_completed".equals(type))
                {
                    AlertEnrollment local = mAlerts.byRemoteEnrollmentId(update.path("enrollment_id").asText()).orElse(null);
                    if(local != null && "pending".equals(local.status()))
                    {
                        var completed = JSON.createObjectNode().put("status", "completed").put("device_id", update.path("device_id").asText());
                        completeEnrollment(local, completed);
                    }
                }
                else if(List.of("device_revoked", "device_expired", "device_refreshed").contains(type))
                    applyDeviceUpdate(type, update.path("device_id").asText());
            }
            mCredentials.cursor(result.path("cursor").asText(credentials.updateCursor()));
        }
    }

    private synchronized void applyDeviceUpdate(String type, String remoteDeviceId) throws Exception
    {
        if(mClosed) return;
        AlertDevice device = mAlerts.byRemoteDeviceId(remoteDeviceId).orElse(null);
        if(device == null) return;
        if("device_revoked".equals(type)) mAlerts.unsubscribe(device.id());
        else mAlerts.markDeviceExpired(device.id(), "device_expired".equals(type));
    }

    private static String preview(String body)
    {
        return body.codePointCount(0, body.length()) > 500 ? body.substring(0, body.offsetByCodePoints(0, 500)) + "…" : body;
    }


    public static String hashToken(String token)
    {
        if(token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw new SecurityException("This device connection is unavailable. Subscribe again.");
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII))); }
        catch(java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private static boolean constantHashEquals(String hash, String token)
    {
        return MessageDigest.isEqual(hash.getBytes(StandardCharsets.US_ASCII), hashToken(token).getBytes(StandardCharsets.US_ASCII));
    }

    @Override public synchronized void close() { mClosed = true; mWorker.shutdownNow(); mRelayWorker.shutdownNow(); mGateway.close(); mDirect.close(); }
}
