/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.alerts;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.database.SdrTrunkDatabase;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Bounded, explicit configuration, guest membership, history, and durable delivery storage. Never used on decode threads. */
public final class TopicAlertStore
{
    public static final int MAXIMUM_DEVICES = 1000;
    public static final int MAXIMUM_ENROLLMENTS = 512;
    public static final int MAXIMUM_MESSAGES = 2000;
    public static final int MAXIMUM_PENDING_DELIVERIES = 2000;
    public static final int MAXIMUM_DELIVERIES = 20000;
    private final Path mPath;
    private final ObjectMapper mMapper = new ObjectMapper();
    private long mQueuedRelayCursor;

    public TopicAlertStore(Path path) { mPath = path.toAbsolutePath().normalize(); }

    public synchronized TopicAlertConfiguration configuration() throws IOException, SQLException
    {
        try(Connection connection = open()) { return configuration(connection); }
    }

    private TopicAlertConfiguration configuration(Connection connection) throws IOException, SQLException
    {
        Settings settings;
        try(PreparedStatement query = connection.prepareStatement("SELECT settings_json FROM application_settings WHERE key='alert.configuration'"); ResultSet rows = query.executeQuery())
        {
            if(!rows.next()) throw new SQLException("Alert configuration is missing; run the Application Migrator");
            settings = mMapper.readValue(rows.getString(1), Settings.class);
        }
        List<AlertTopic> topics = new ArrayList<>();
        try(Statement query = connection.createStatement(); ResultSet rows = query.executeQuery("SELECT id,name,description,visible,enabled,built_in FROM topic_alert_topic ORDER BY built_in DESC,name,id LIMIT 257"))
        {
            while(rows.next()) topics.add(new AlertTopic(rows.getString(1), rows.getString(2), rows.getString(3), rows.getBoolean(4), rows.getBoolean(5), rows.getBoolean(6)));
        }
        List<AlertRule> rules = new ArrayList<>();
        try(Statement query = connection.createStatement(); ResultSet rows = query.executeQuery("SELECT id,topic_id,rule_json FROM topic_alert_rule ORDER BY id LIMIT 513"))
        {
            while(rows.next())
            {
                AlertRule rule = mMapper.readValue(rows.getString(3), AlertRule.class);
                if(!rule.id().equals(rows.getString(1)) || !rule.topicId().equals(rows.getString(2)))
                    throw new SQLException("Alert rule ownership does not match its stored document");
                rules.add(rule);
            }
        }
        return new TopicAlertConfiguration(settings.revision(), settings.push(), topics, rules, settings.builtinActionButtonEnabled());
    }

    public synchronized TopicAlertConfiguration updateConfiguration(TopicAlertConfiguration next, long expectedRevision) throws IOException, SQLException
    {
        if(expectedRevision < 1 || expectedRevision == Long.MAX_VALUE) throw new IllegalArgumentException("Invalid alert revision");
        try(Connection connection = open())
        {
            connection.setAutoCommit(false);
            try
            {
                TopicAlertConfiguration saved = new TopicAlertConfiguration(expectedRevision + 1, next.push(), next.topics(), next.rules(), next.builtinActionButtonEnabled());
                try(PreparedStatement update = connection.prepareStatement("UPDATE application_settings SET settings_json=?,updated_at_ms=? WHERE key='alert.configuration' AND json_extract(settings_json,'$.revision')=?"))
                {
                    update.setString(1, mMapper.writeValueAsString(new Settings(saved.revision(), saved.push(), saved.builtinActionButtonEnabled())));
                    update.setLong(2, System.currentTimeMillis()); update.setLong(3, expectedRevision);
                    if(update.executeUpdate() != 1) throw new RevisionConflictException();
                }
                try(PreparedStatement update = connection.prepareStatement("INSERT INTO topic_alert_topic(id,name,description,visible,enabled,built_in) VALUES(?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET name=excluded.name,description=excluded.description,visible=excluded.visible,enabled=excluded.enabled,built_in=excluded.built_in"))
                {
                    for(AlertTopic topic: saved.topics())
                    {
                        update.setString(1, topic.id()); update.setString(2, topic.name()); update.setString(3, topic.description());
                        update.setBoolean(4, topic.visible()); update.setBoolean(5, topic.enabled()); update.setBoolean(6, topic.builtIn()); update.addBatch();
                    }
                    update.executeBatch();
                }
                removeAbsent(connection, "topic_alert_rule", saved.rules().stream().map(AlertRule::id).toList());
                try(PreparedStatement update = connection.prepareStatement("INSERT INTO topic_alert_rule(id,topic_id,rule_json) VALUES(?,?,?) ON CONFLICT(id) DO UPDATE SET topic_id=excluded.topic_id,rule_json=excluded.rule_json"))
                {
                    for(AlertRule rule: saved.rules())
                    {
                        update.setString(1, rule.id()); update.setString(2, rule.topicId()); update.setString(3, mMapper.writeValueAsString(rule)); update.addBatch();
                    }
                    update.executeBatch();
                }
                removeAbsent(connection, "topic_alert_topic", saved.topics().stream().map(AlertTopic::id).toList());
                connection.commit(); return saved;
            }
            catch(IOException | SQLException | RuntimeException exception) { connection.rollback(); throw exception; }
        }
    }

    private static void removeAbsent(Connection connection, String table, List<String> ids) throws SQLException
    {
        if("topic_alert_topic".equals(table))
        {
            String filter = ids.isEmpty() ? "" : " AND m.topic_id NOT IN (" + placeholders(ids.size()) + ")";
            try(PreparedStatement query = connection.prepareStatement("SELECT 1 FROM topic_alert_message m JOIN topic_alert_delivery d ON d.alert_id=m.id WHERE d.state IN ('pending','retry','queued')" + filter + " LIMIT 1"))
            {
                for(int i = 0; i < ids.size(); i++) query.setString(i + 1, ids.get(i));
                try(ResultSet rows = query.executeQuery())
                {
                    if(rows.next()) throw new IllegalArgumentException("Disable or hide this topic and wait for queued notifications to finish before deleting it.");
                }
            }
        }
        String sql = "DELETE FROM " + table + (ids.isEmpty() ? "" : " WHERE id NOT IN (" + placeholders(ids.size()) + ")");
        try(PreparedStatement delete = connection.prepareStatement(sql))
        {
            for(int i = 0; i < ids.size(); i++) delete.setString(i + 1, ids.get(i));
            delete.executeUpdate();
        }
    }

    public synchronized List<AlertDevice> devices() throws SQLException
    {
        try(Connection connection = open(); Statement query = connection.createStatement(); ResultSet rows = query.executeQuery("SELECT * FROM topic_alert_device ORDER BY id LIMIT " + (MAXIMUM_DEVICES + 1)))
        {
            List<AlertDevice> result = new ArrayList<>(); while(rows.next()) result.add(device(connection, rows));
            if(result.size() > MAXIMUM_DEVICES) throw new IllegalArgumentException("Maximum of " + MAXIMUM_DEVICES + " notification devices exceeded");
            return List.copyOf(result);
        }
    }

    /** Indexed membership lookup; only the topic being delivered is needed by the alert worker. */
    synchronized List<AlertDevice> devicesForTopic(String topicId, String method) throws SQLException
    {
        try(Connection connection = open(); PreparedStatement query = connection.prepareStatement("SELECT v.* FROM topic_alert_membership membership JOIN topic_alert_device v ON v.id=membership.device_id WHERE membership.topic_id=? AND v.method=? AND v.subscription_expired=0 ORDER BY membership.device_id LIMIT " + (MAXIMUM_DEVICES + 1)))
        {
            query.setString(1, topicId); query.setString(2, method);
            List<AlertDevice> result = new ArrayList<>();
            try(ResultSet rows = query.executeQuery()) { while(rows.next()) result.add(device(rows, List.of(topicId))); }
            if(result.size() > MAXIMUM_DEVICES) throw new IllegalArgumentException("Maximum of " + MAXIMUM_DEVICES + " notification recipients exceeded");
            return List.copyOf(result);
        }
    }

    /** Latest receiver assignments; no retained-event scan and no lookup on decoder callbacks. */
    public synchronized Map<String,SystemFact> currentSystemFacts() throws SQLException
    {
        try(Connection connection = open(); Statement query = connection.createStatement(); ResultSet rows = query.executeQuery("""
            SELECT receiver.configuration_id,system.system_key,receiver.last_seen_ms
            FROM receiver_channel receiver JOIN radio_system system ON system.id=receiver.radio_system_id
            JOIN configuration_channel channel ON channel.configuration_id=receiver.configuration_id
            WHERE channel.channel_kind='TRUNKED'
            ORDER BY receiver.configuration_id LIMIT 4096
            """))
        {
            Map<String,SystemFact> result = new HashMap<>();
            while(rows.next()) result.put(rows.getString(1), new SystemFact(rows.getString(2), rows.getLong(3)));
            return Map.copyOf(result);
        }
    }
    public record SystemFact(String systemKey, long lastSeenMs) {}

    public synchronized Optional<AlertDevice> device(String id) throws SQLException { return findDevice("id", id); }
    public synchronized Optional<AlertDevice> findDeviceByTokenHash(String hash) throws SQLException { return findDevice("manage_token_hash", hash); }
    public synchronized Optional<AlertDevice> byRemoteDeviceId(String id) throws SQLException { return findDevice("remote_device_id", id); }

    private Optional<AlertDevice> findDevice(String column, String value) throws SQLException
    {
        try(Connection connection = open(); PreparedStatement query = connection.prepareStatement("SELECT * FROM topic_alert_device WHERE " + column + "=?"))
        {
            query.setString(1, value); try(ResultSet rows = query.executeQuery()) { return rows.next() ? Optional.of(device(connection, rows)) : Optional.empty(); }
        }
    }

    private static AlertDevice device(Connection connection, ResultSet row) throws SQLException
    {
        return device(row, memberships(connection, "topic_alert_membership", "device_id", row.getString("id")));
    }

    private static AlertDevice device(ResultSet row, List<String> topicIds) throws SQLException
    {
        return new AlertDevice(row.getString("id"), row.getString("name"), row.getString("method"), row.getString("remote_device_id"),
            row.getString("endpoint"), row.getString("p256dh"), row.getString("auth"), row.getString("manage_token_hash"),
            topicIds, row.getBoolean("subscription_expired"));
    }

    public synchronized void saveDevice(AlertDevice device) throws SQLException
    {
        try(Connection connection = open())
        {
            connection.setAutoCommit(false);
            try { saveDevice(connection, device); connection.commit(); }
            catch(SQLException | RuntimeException exception) { connection.rollback(); throw exception; }
        }
    }

    private static void saveDevice(Connection connection, AlertDevice device) throws SQLException
    {
        saveDevice(connection, device, List.of());
    }
    private static void saveDevice(Connection connection, AlertDevice device, List<String> pairedTopics) throws SQLException
    {
        requireCapacity(connection, "topic_alert_device", device.id(), MAXIMUM_DEVICES);
        Set<String> retained = new java.util.HashSet<>(memberships(connection, "topic_alert_membership", "device_id", device.id()));
        retained.addAll(pairedTopics);
        requireTopics(connection, device.topicIds(), retained);
        try(PreparedStatement update = connection.prepareStatement("""
            INSERT INTO topic_alert_device(id,name,method,remote_device_id,endpoint,p256dh,auth,manage_token_hash,subscription_expired)
            VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET name=excluded.name,method=excluded.method,
            remote_device_id=excluded.remote_device_id,endpoint=excluded.endpoint,p256dh=excluded.p256dh,auth=excluded.auth,
            manage_token_hash=excluded.manage_token_hash,subscription_expired=excluded.subscription_expired
            """))
        {
            update.setString(1, device.id()); update.setString(2, device.name()); update.setString(3, device.method());
            update.setString(4, device.remoteDeviceId()); update.setString(5, device.endpoint()); update.setString(6, device.p256dh());
            update.setString(7, device.auth()); update.setString(8, device.manageTokenHash());
            update.setBoolean(9, device.subscriptionExpired()); update.executeUpdate();
        }
        replaceMemberships(connection, "topic_alert_membership", "device_id", device.id(), device.topicIds());
    }

    /** Expiry is independent of guest ownership/preferences and does not disconnect the device. */
    public synchronized void markDeviceExpired(String id, boolean expired) throws SQLException
    {
        try(Connection connection = open())
        {
            connection.setAutoCommit(false);
            try
            {
                try(PreparedStatement update = connection.prepareStatement("UPDATE topic_alert_device SET subscription_expired=? WHERE id=?"))
                { update.setBoolean(1, expired); update.setString(2, id); update.executeUpdate(); }
                if(expired) expireDeviceDeliveries(connection, id);
                connection.commit();
            }
            catch(SQLException | RuntimeException exception) { connection.rollback(); throw exception; }
        }
    }

    /** A late response for old Direct subscription material cannot disable a refreshed subscription. */
    public synchronized boolean markDeviceSubscriptionExpired(String id, String endpoint, String p256dh, String auth) throws SQLException
    {
        try(Connection connection = open())
        {
            connection.setAutoCommit(false);
            try
            {
                boolean matched;
                try(PreparedStatement update = connection.prepareStatement("UPDATE topic_alert_device SET subscription_expired=1 WHERE id=? AND method='direct' AND endpoint=? AND p256dh=? AND auth=?"))
                {
                    update.setString(1, id); update.setString(2, endpoint); update.setString(3, p256dh); update.setString(4, auth);
                    matched = update.executeUpdate() != 0;
                }
                if(matched) expireDeviceDeliveries(connection, id);
                connection.commit(); return matched;
            }
            catch(SQLException | RuntimeException exception) { connection.rollback(); throw exception; }
        }
    }

    private static void expireDeviceDeliveries(Connection connection, String id) throws SQLException
    {
        try(PreparedStatement update = connection.prepareStatement("UPDATE topic_alert_delivery SET state='expired',updated_at_ms=? WHERE device_id=? AND state IN ('pending','retry','queued')"))
        { update.setLong(1, System.currentTimeMillis()); update.setString(2, id); update.executeUpdate(); }
    }

    public synchronized void unsubscribe(String deviceId) throws SQLException
    {
        try(Connection connection = open())
        {
            connection.setAutoCommit(false);
            try
            {
                try(PreparedStatement update = connection.prepareStatement("UPDATE topic_alert_delivery SET state='cancelled',updated_at_ms=? WHERE device_id=? AND state IN ('pending','retry','queued')"))
                { update.setLong(1, System.currentTimeMillis()); update.setString(2, deviceId); update.executeUpdate(); }
                try(PreparedStatement update = connection.prepareStatement("UPDATE topic_alert_enrollment SET status='cancelled' WHERE device_id=?"))
                { update.setString(1, deviceId); update.executeUpdate(); }
                try(PreparedStatement update = connection.prepareStatement("DELETE FROM topic_alert_device WHERE id=?"))
                { update.setString(1, deviceId); update.executeUpdate(); }
                connection.commit();
            }
            catch(SQLException | RuntimeException exception) { connection.rollback(); throw exception; }
        }
    }

    public synchronized void saveEnrollment(AlertEnrollment enrollment) throws SQLException
    {
        try(Connection connection = open())
        {
            connection.setAutoCommit(false);
            try { saveEnrollment(connection, enrollment); connection.commit(); }
            catch(SQLException | RuntimeException exception) { connection.rollback(); throw exception; }
        }
    }

    private static void saveEnrollment(Connection connection, AlertEnrollment enrollment) throws SQLException
    {
        requireCapacity(connection, "topic_alert_enrollment", enrollment.id(), MAXIMUM_ENROLLMENTS);
        requireTopics(connection, enrollment.topicIds(), Set.copyOf(memberships(connection, "topic_alert_enrollment_topic", "enrollment_id", enrollment.id())));
        try(PreparedStatement update = connection.prepareStatement("""
            INSERT INTO topic_alert_enrollment(id,remote_enrollment_id,manage_token_hash,created_at_ms,expires_at_ms,status,device_id,enrollment_url,code)
            VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET remote_enrollment_id=excluded.remote_enrollment_id,
            manage_token_hash=excluded.manage_token_hash,expires_at_ms=excluded.expires_at_ms,status=excluded.status,device_id=excluded.device_id,
            enrollment_url=excluded.enrollment_url,code=excluded.code
            """))
        {
            update.setString(1, enrollment.id()); update.setString(2, enrollment.remoteEnrollmentId()); update.setString(3, enrollment.manageTokenHash());
            update.setLong(4, enrollment.createdAtMs()); update.setLong(5, enrollment.expiresAtMs());
            update.setString(6, enrollment.status()); update.setString(7, enrollment.deviceId().isEmpty() ? null : enrollment.deviceId());
            update.setString(8, enrollment.enrollmentUrl()); update.setString(9, enrollment.code()); update.executeUpdate();
        }
        replaceMemberships(connection, "topic_alert_enrollment_topic", "enrollment_id", enrollment.id(), enrollment.topicIds());
    }

    public synchronized void completeEnrollment(AlertEnrollment enrollment, AlertDevice device) throws SQLException
    {
        if(!"completed".equals(enrollment.status()) || !device.id().equals(enrollment.deviceId()) ||
            !device.manageTokenHash().equals(enrollment.manageTokenHash())) throw new IllegalArgumentException("Enrollment and device ownership do not match");
        try(Connection connection = open())
        {
            connection.setAutoCommit(false);
            try { saveDevice(connection, device, enrollment.topicIds()); saveEnrollment(connection, enrollment); connection.commit(); }
            catch(SQLException | RuntimeException exception) { connection.rollback(); throw exception; }
        }
    }

    public synchronized Optional<AlertEnrollment> enrollment(String id) throws SQLException { return findEnrollment("id", id); }
    public synchronized Optional<AlertEnrollment> findEnrollmentByTokenHash(String id) throws SQLException { return findEnrollment("manage_token_hash", id); }
    public synchronized Optional<AlertEnrollment> byRemoteEnrollmentId(String id) throws SQLException { return findEnrollment("remote_enrollment_id", id); }
    private Optional<AlertEnrollment> findEnrollment(String column, String value) throws SQLException
    {
        try(Connection connection = open(); PreparedStatement query = connection.prepareStatement("SELECT * FROM topic_alert_enrollment WHERE " + column + "=?"))
        {
            query.setString(1, value); try(ResultSet rows = query.executeQuery()) { return rows.next() ? Optional.of(enrollment(connection, rows)) : Optional.empty(); }
        }
    }

    public synchronized List<AlertEnrollment> pendingEnrollments(int limit) throws SQLException
    {
        long now = System.currentTimeMillis();
        try(Connection connection = open(); PreparedStatement query = connection.prepareStatement("SELECT * FROM topic_alert_enrollment WHERE status='pending' AND expires_at_ms>? ORDER BY expires_at_ms,id LIMIT ?"))
        {
            query.setLong(1, now); query.setInt(2, Math.clamp(limit, 1, 100));
            List<AlertEnrollment> result = new ArrayList<>(); try(ResultSet rows = query.executeQuery()) { while(rows.next()) result.add(enrollment(connection, rows)); }
            return List.copyOf(result);
        }
    }

    private static AlertEnrollment enrollment(Connection connection, ResultSet row) throws SQLException
    {
        return new AlertEnrollment(row.getString("id"), row.getString("remote_enrollment_id"), row.getString("manage_token_hash"),
            memberships(connection, "topic_alert_enrollment_topic", "enrollment_id", row.getString("id")), row.getLong("created_at_ms"),
            row.getLong("expires_at_ms"), row.getString("status"), row.getString("device_id"), row.getString("enrollment_url"), row.getString("code"));
    }

    private static List<String> memberships(Connection connection, String table, String owner, String id) throws SQLException
    {
        try(PreparedStatement query = connection.prepareStatement("SELECT topic_id FROM " + table + " WHERE " + owner + "=? ORDER BY topic_id LIMIT 257"))
        {
            query.setString(1, id); List<String> result = new ArrayList<>(); try(ResultSet rows = query.executeQuery()) { while(rows.next()) result.add(rows.getString(1)); }
            if(result.size() > 256) throw new SQLException("Topic subscription capacity exceeded"); return List.copyOf(result);
        }
    }

    private static void replaceMemberships(Connection connection, String table, String owner, String id, List<String> topics) throws SQLException
    {
        try(PreparedStatement delete = connection.prepareStatement("DELETE FROM " + table + " WHERE " + owner + "=?")) { delete.setString(1, id); delete.executeUpdate(); }
        try(PreparedStatement update = connection.prepareStatement("INSERT INTO " + table + "(" + owner + ",topic_id) VALUES(?,?)"))
        {
            for(String topic: topics) { update.setString(1, id); update.setString(2, topic); update.addBatch(); } update.executeBatch();
        }
    }

    private static void requireTopics(Connection connection, List<String> topics, Set<String> retained) throws SQLException
    {
        try(PreparedStatement query = connection.prepareStatement("SELECT visible,enabled FROM topic_alert_topic WHERE id=?"))
        {
            for(String topic: topics)
            {
                query.setString(1, topic); try(ResultSet rows = query.executeQuery())
                {
                    if(!rows.next() || (!rows.getBoolean(1) || !rows.getBoolean(2)) && !retained.contains(topic))
                        throw new IllegalArgumentException("Topic is not available for subscription");
                }
            }
        }
    }

    private static void requireCapacity(Connection connection, String table, String id, int maximum) throws SQLException
    {
        try(PreparedStatement query = connection.prepareStatement("SELECT (SELECT count(*) FROM " + table + "),(SELECT count(*) FROM " + table + " WHERE id=?)"))
        {
            query.setString(1, id); try(ResultSet rows = query.executeQuery())
            {
                if(rows.next() && rows.getInt(1) >= maximum && rows.getInt(2) == 0)
                    throw new IllegalArgumentException("Maximum of " + maximum + ("topic_alert_device".equals(table) ? " notification devices" : " notification enrollment sessions") + " reached");
            }
        }
    }

    /** Returns false on durable-outbox saturation. The observer drops this alert rather than blocking receiving. */
    public synchronized boolean publish(AlertTopic topic, String ruleId, String alertName, AlertEvent event, String title, String body,
                                         String clickAction, List<AlertDevice> destinations, long now) throws SQLException
    {
        return publish(topic, ruleId, alertName, event, title, body, clickAction, AlertAction.NONE, destinations, now);
    }

    public synchronized boolean publish(AlertTopic topic, String ruleId, String alertName, AlertEvent event, String title, String body,
                                         String clickAction, AlertAction action, List<AlertDevice> destinations, long now) throws SQLException
    {
        return publish(topic, ruleId, alertName, event, title, body, clickAction, action, destinations, now, now);
    }

    /** The delivery eligibility deadline is independent of when matching and durable history were recorded. */
    synchronized boolean publish(AlertTopic topic, String ruleId, String alertName, AlertEvent event, String title, String body,
                                 String clickAction, AlertAction action, List<AlertDevice> destinations, long now,
                                 long nextAttemptAtMs) throws SQLException
    {
        if(action == null) action = AlertAction.NONE;
        String actionJson;
        try { actionJson = action.entityRef() != null ? mMapper.writeValueAsString(action.entityRef()) : null; }
        catch(IOException exception) { throw new SQLException("The alert action could not be saved", exception); }
        if(actionJson != null && actionJson.length() > 2048) throw new IllegalArgumentException("Alert action exceeds its limit");
        if(destinations.size() > MAXIMUM_DEVICES) throw new IllegalArgumentException("Maximum of " + MAXIMUM_DEVICES + " notification recipients exceeded");
        if(destinations.stream().map(AlertDevice::id).distinct().count() != destinations.size())
            throw new IllegalArgumentException("Notification recipients must be unique");
        try(Connection connection = open())
        {
            connection.setAutoCommit(false);
            try
            {
                try(PreparedStatement query = connection.prepareStatement("SELECT (SELECT count(*) FROM topic_alert_membership membership JOIN topic_alert_device device ON device.id=membership.device_id WHERE membership.topic_id=? AND device.subscription_expired=0),(SELECT count(*) FROM topic_alert_delivery WHERE state IN ('pending','retry','queued')),(SELECT count(*) FROM topic_alert_delivery WHERE alert_id IN (SELECT alert_id FROM topic_alert_delivery WHERE state IN ('pending','retry','queued')))"))
                {
                    query.setString(1, topic.id());
                    try(ResultSet rows = query.executeQuery())
                    {
                        rows.next();
                        if(rows.getInt(1) > MAXIMUM_DEVICES) throw new IllegalArgumentException("Maximum of " + MAXIMUM_DEVICES + " notification recipients exceeded");
                        if(rows.getInt(2) + destinations.size() > MAXIMUM_PENDING_DELIVERIES || rows.getInt(3) + destinations.size() > MAXIMUM_DELIVERIES) { connection.rollback(); return false; }
                    }
                }
                String alertId = UUID.randomUUID().toString(); long expiresAt = now + 3_600_000;
                try(PreparedStatement update = connection.prepareStatement("INSERT INTO topic_alert_message(id,rule_id,topic_id,topic_name,alert_name,title,body,click_action,event_type,created_at_ms,expires_at_ms,action_kind,action_entity_ref_json,source_call_uuid) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)"))
                {
                    update.setString(1, alertId); update.setString(2, ruleId); update.setString(3, topic.id()); update.setString(4, topic.name());
                    update.setString(5, alertName); update.setString(6, title); update.setString(7, body); update.setString(8, clickAction); update.setString(9, event.trigger());
                    update.setLong(10, now); update.setLong(11, expiresAt); update.setString(12, action.kind());
                    update.setString(13, actionJson); update.setString(14, event.sourceCallUuid()); update.executeUpdate();
                }
                try(PreparedStatement update = connection.prepareStatement("INSERT INTO topic_alert_delivery(alert_id,device_id,remote_device_id,method,state,attempts,next_at_ms,remote_id,error,updated_at_ms) SELECT ?,?,?,?,'pending',0,?,'','',? FROM topic_alert_device WHERE id=? AND subscription_expired=0"))
                {
                    for(AlertDevice destination: destinations)
                    {
                        update.setString(1, alertId); update.setString(2, destination.id());
                        update.setString(3, "proxy".equals(destination.method()) ? destination.remoteDeviceId() : ""); update.setString(4, destination.method());
                        update.setLong(5, Math.max(now, nextAttemptAtMs)); update.setLong(6, now); update.setString(7, destination.id()); update.addBatch();
                    }
                    update.executeBatch();
                }
                prune(connection, now); connection.commit(); return true;
            }
            catch(SQLException | RuntimeException exception) { connection.rollback(); throw exception; }
        }
    }

    /** Exact committed recording provenance can release only a not-yet-attempted initial delivery. */
    synchronized void releaseRecordingReady(List<String> sourceReferences, long now) throws SQLException
    {
        if(sourceReferences.isEmpty()) return;
        if(sourceReferences.size() > 128) throw new IllegalArgumentException("Recording source reference limit exceeded");
        try(Connection connection = open(); PreparedStatement update = connection.prepareStatement(
            "UPDATE topic_alert_delivery SET next_at_ms=?,updated_at_ms=? WHERE state='pending' AND attempts=0 AND next_at_ms>?" +
            " AND alert_id IN (SELECT id FROM topic_alert_message WHERE event_type IN ('call','tone') AND source_call_uuid IN (" +
            placeholders(sourceReferences.size()) + "))"))
        {
            update.setLong(1, now); update.setLong(2, now); update.setLong(3, now);
            for(int index = 0; index < sourceReferences.size(); index++) update.setString(index + 4, sourceReferences.get(index));
            update.executeUpdate();
        }
    }

    public synchronized List<AlertDelivery> pendingDeliveries(int limit) throws SQLException
    {
        return pendingDeliveries(limit, System.currentTimeMillis());
    }

    synchronized List<AlertDelivery> pendingDeliveries(int limit, long now) throws SQLException
    {
        try(Connection connection = open(); PreparedStatement query = connection.prepareStatement(deliverySelect() + " WHERE d.state IN ('pending','retry') AND d.next_at_ms<=? AND m.expires_at_ms>? AND t.visible=1 AND t.enabled=1 AND v.subscription_expired=0 ORDER BY d.next_at_ms,d.id LIMIT ?"))
        {
            query.setLong(1, now); query.setLong(2, now); query.setInt(3, Math.clamp(limit, 1, 100));
            List<AlertDelivery> result = new ArrayList<>(); try(ResultSet rows = query.executeQuery()) { while(rows.next()) result.add(delivery(rows)); }
            return List.copyOf(result);
        }
    }

    public synchronized Optional<AlertDelivery> delivery(long id) throws SQLException
    {
        try(Connection connection = open(); PreparedStatement query = connection.prepareStatement(deliverySelect() + " WHERE d.id=?"))
        {
            query.setLong(1, id); try(ResultSet rows = query.executeQuery()) { return rows.next() ? Optional.of(delivery(rows)) : Optional.empty(); }
        }
    }

    public synchronized List<AlertDelivery> deliveriesForAlert(String alertId) throws SQLException
    {
        try(Connection connection = open(); PreparedStatement query = connection.prepareStatement(deliverySelect() + " WHERE d.alert_id=? ORDER BY d.id LIMIT " + (MAXIMUM_DEVICES + 1)))
        {
            query.setString(1, alertId); List<AlertDelivery> result = new ArrayList<>();
            try(ResultSet rows = query.executeQuery()) { while(rows.next()) result.add(delivery(rows)); }
            if(result.size() > MAXIMUM_DEVICES) throw new SQLException("Alert recipient capacity exceeded");
            return List.copyOf(result);
        }
    }

    public synchronized Optional<AlertDelivery> deliveryForRemote(String alertId, String remoteDeviceId) throws SQLException
    {
        try(Connection connection = open(); PreparedStatement query = connection.prepareStatement(deliverySelect() + " WHERE d.alert_id=? AND d.remote_device_id=? AND d.remote_device_id<>''"))
        {
            query.setString(1, alertId); query.setString(2, remoteDeviceId);
            try(ResultSet rows = query.executeQuery()) { return rows.next() ? Optional.of(delivery(rows)) : Optional.empty(); }
        }
    }

    public synchronized void queueRelayAlert(String alertId, String remoteAlertId) throws SQLException
    {
        updateRelayAlert(alertId, "queued", AlertRule.text(remoteAlertId, "Remote alert ID", 240, false), "", 0);
    }

    /** Includes queued and attempted work with an unknown relay outcome; untouched pending work stays local. */
    public synchronized List<AlertDelivery> queuedRelayAlerts(int limit) throws SQLException
    {
        List<AlertDelivery> result = queuedRelayAlerts(limit, mQueuedRelayCursor);
        if(result.isEmpty() && mQueuedRelayCursor != 0) result = queuedRelayAlerts(limit, 0);
        mQueuedRelayCursor = result.isEmpty() ? 0 : result.getLast().id();
        return result;
    }

    private List<AlertDelivery> queuedRelayAlerts(int limit, long afterId) throws SQLException
    {
        try(Connection connection = open(); PreparedStatement query = connection.prepareStatement(deliverySelect() + " WHERE d.id IN (SELECT min(id) FROM topic_alert_delivery WHERE method='proxy' AND (state='queued' OR state IN ('pending','retry') AND attempts>0) GROUP BY alert_id HAVING min(id)>? ORDER BY min(id) LIMIT ?) ORDER BY d.id"))
        {
            query.setLong(1, afterId); query.setInt(2, Math.clamp(limit, 1, 100));
            List<AlertDelivery> result = new ArrayList<>();
            try(ResultSet rows = query.executeQuery()) { while(rows.next()) result.add(delivery(rows)); }
            return List.copyOf(result);
        }
    }

    public synchronized void retryRelayAlert(String alertId, String error, long retryAtMs) throws SQLException
    {
        if(retryAtMs <= 0) throw new IllegalArgumentException("Relay retry time is required");
        updateRelayAlert(alertId, "retry", "", bounded(error, 512), retryAtMs);
    }

    private void updateRelayAlert(String alertId, String state, String remoteAlertId, String error, long nextAtMs) throws SQLException
    {
        try(Connection connection = open(); PreparedStatement update = connection.prepareStatement("UPDATE topic_alert_delivery SET state=?,remote_id=?,error=?,next_at_ms=?,attempts=min(attempts+1,100),updated_at_ms=? WHERE alert_id=? AND method='proxy' AND state IN ('pending','retry')"))
        {
            update.setString(1, state); update.setString(2, remoteAlertId); update.setString(3, error); update.setLong(4, nextAtMs);
            update.setLong(5, System.currentTimeMillis()); update.setString(6, alertId); update.executeUpdate();
        }
    }

    private static String deliverySelect()
    {
        return "SELECT d.id,m.id,m.topic_id,m.topic_name,m.alert_name,m.title,m.body,m.click_action,d.device_id,d.remote_device_id,d.method,d.state,m.created_at_ms,m.expires_at_ms,d.attempts,d.remote_id,d.error FROM topic_alert_delivery d JOIN topic_alert_message m ON m.id=d.alert_id JOIN topic_alert_topic t ON t.id=m.topic_id LEFT JOIN topic_alert_device v ON v.id=d.device_id";
    }
    private static AlertDelivery delivery(ResultSet rows) throws SQLException
    {
        return new AlertDelivery(rows.getLong(1), rows.getString(2), rows.getString(3), rows.getString(4), rows.getString(5), rows.getString(6),
            rows.getString(7), rows.getString(8), rows.getString(9), rows.getString(10), rows.getString(11), rows.getString(12), rows.getLong(13), rows.getLong(14), rows.getInt(15), rows.getString(16), rows.getString(17));
    }

    public synchronized void completeDelivery(long id, String state, String remoteId, String error, long retryAtMs) throws SQLException
    {
        if(!Set.of("pending", "retry", "queued", "push_accepted", "failed", "expired", "cancelled", "opened").contains(state))
            throw new IllegalArgumentException("Unknown delivery status");
        if(retryAtMs > 0) state = "retry";
        try(Connection connection = open(); PreparedStatement update = connection.prepareStatement("UPDATE topic_alert_delivery SET state=?,remote_id=?,error=?,attempts=min(attempts+1,100),next_at_ms=?,updated_at_ms=? WHERE id=? AND state<>'opened' AND (state<>'push_accepted' OR ?='opened') AND (state NOT IN ('failed','expired','cancelled') OR ? IN ('push_accepted','opened'))"))
        {
            update.setString(1, state); update.setString(2, AlertRule.text(remoteId, "Remote alert ID", 240, true));
            update.setString(3, bounded(error, 512)); update.setLong(4, Math.max(0, retryAtMs)); update.setLong(5, System.currentTimeMillis()); update.setLong(6, id);
            update.setString(7, state); update.setString(8, state); update.executeUpdate();
        }
    }

    public synchronized Map<String,Object> history(List<String> topicIds, int limit, int offset) throws SQLException
    {
        limit = Math.clamp(limit, 1, 100); offset = Math.clamp(offset, 0, MAXIMUM_MESSAGES);
        if(topicIds != null && topicIds.isEmpty()) return Map.of("items", List.of(), "total", 0, "limit", limit, "offset", offset);
        String filter = topicIds == null ? "" : " WHERE m.topic_id IN (" + placeholders(topicIds.size()) + ") AND t.visible=1";
        try(Connection connection = open())
        {
            List<Map<String,Object>> items = new ArrayList<>(); int total;
            try(PreparedStatement query = connection.prepareStatement("SELECT count(*) FROM topic_alert_message m JOIN topic_alert_topic t ON t.id=m.topic_id" + filter))
            {
                setTopics(query, topicIds); try(ResultSet rows = query.executeQuery()) { rows.next(); total = rows.getInt(1); }
            }
            try(PreparedStatement query = connection.prepareStatement(historySelect() + filter + " ORDER BY m.created_at_ms DESC,m.id LIMIT ? OFFSET ?"))
            {
                int index = setTopics(query, topicIds); query.setInt(index++, limit); query.setInt(index, offset);
                try(ResultSet rows = query.executeQuery())
                {
                    while(rows.next()) items.add(historyItem(rows));
                }
            }
            return Map.of("items", List.copyOf(items), "total", total, "limit", limit, "offset", offset);
        }
    }

    /** Public detail lookup uses the opaque message primary key and current topic visibility. */
    public synchronized Optional<Map<String,Object>> historyItem(String id) throws SQLException
    {
        try(Connection connection = open(); PreparedStatement query = connection.prepareStatement(historySelect() + " WHERE m.id=? AND t.visible=1"))
        {
            query.setString(1, id);
            try(ResultSet rows = query.executeQuery()) { return rows.next() ? Optional.of(historyItem(rows)) : Optional.empty(); }
        }
    }

    /** Private exact-call reference; deliberately excluded from public history and every delivery payload. */
    public synchronized Optional<String> sourceCallUuid(String alertId) throws SQLException
    {
        try(Connection connection = open(); PreparedStatement query = connection.prepareStatement("SELECT m.source_call_uuid FROM topic_alert_message m JOIN topic_alert_topic t ON t.id=m.topic_id WHERE m.id=? AND t.visible=1"))
        {
            query.setString(1, alertId);
            try(ResultSet rows = query.executeQuery())
            {
                return rows.next() ? Optional.ofNullable(rows.getString(1)) : Optional.empty();
            }
        }
    }

    /** Bounded exact-source lookup. Hidden and removed topics never become related-alert destinations. */
    public synchronized List<Map<String,Object>> historyBySourceCallReferences(List<String> sources) throws SQLException
    {
        if(sources == null || sources.isEmpty()) return List.of();
        if(sources.size() > 128) throw new IllegalArgumentException("Too many source call references");
        List<String> references = sources.stream().map(io.github.dsheirer.audio.call.SourceCallReference::canonical).distinct().toList();
        if(references.contains(null)) throw new IllegalArgumentException("A source call reference is required");
        try(Connection connection = open(); PreparedStatement query = connection.prepareStatement(historySelect() +
            " WHERE m.source_call_uuid IN (" + placeholders(references.size()) + ") AND t.visible=1 ORDER BY m.created_at_ms DESC,m.id LIMIT 100"))
        {
            for(int index = 0; index < references.size(); index++) query.setString(index + 1, references.get(index));
            List<Map<String,Object>> items = new ArrayList<>();
            try(ResultSet rows = query.executeQuery()) { while(rows.next()) items.add(historyItem(rows)); }
            return List.copyOf(items);
        }
    }

    private static String historySelect()
    {
        return "SELECT m.id,m.rule_id,m.topic_id,m.topic_name,m.alert_name,m.title,m.body,m.click_action,m.event_type,m.created_at_ms,m.expires_at_ms,m.action_kind,m.action_entity_ref_json FROM topic_alert_message m JOIN topic_alert_topic t ON t.id=m.topic_id";
    }
    private Map<String,Object> historyItem(ResultSet row) throws SQLException
    {
        Map<String,Object> item = new LinkedHashMap<>();
        String[] keys = {"id","rule_id","topic_id","topic_name","alert_name","title","body","click_action","event_type"};
        for(int i = 0; i < keys.length; i++) item.put(keys[i], row.getString(i + 1));
        item.put("created_at_ms", row.getLong(10)); item.put("expires_at_ms", row.getLong(11));
        AlertAction action = AlertAction.NONE;
        try
        {
            String json = row.getString(13);
            Map<String,Object> reference = json != null ? mMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() {}) : null;
            action = new AlertAction(row.getString(12), reference);
        }
        catch(IOException | IllegalArgumentException ignored) { }
        item.put("action", action); return Map.copyOf(item);
    }

    private static int setTopics(PreparedStatement query, List<String> topics) throws SQLException
    {
        int index = 1; if(topics != null) for(String topic: topics) query.setString(index++, topic); return index;
    }
    private static String placeholders(int count) { return String.join(",", java.util.Collections.nCopies(count, "?")); }
    private static String bounded(String value, int length) { return value == null ? "" : value.substring(0, Math.min(length, value.length())); }

    public synchronized void prune(long now) throws SQLException
    {
        try(Connection connection = open()) { prune(connection, now); }
    }
    private static void prune(Connection connection, long now) throws SQLException
    {
        try(PreparedStatement expire = connection.prepareStatement("UPDATE topic_alert_delivery SET state='expired',updated_at_ms=? WHERE state IN ('pending','retry','queued') AND alert_id IN (SELECT id FROM topic_alert_message WHERE expires_at_ms<=?)"))
        {
            expire.setLong(1, now); expire.setLong(2, now); expire.executeUpdate();
        }
        try(PreparedStatement expire = connection.prepareStatement("DELETE FROM topic_alert_enrollment WHERE expires_at_ms<=?")) { expire.setLong(1, now); expire.executeUpdate(); }
        try(Statement prune = connection.createStatement())
        {
            prune.executeUpdate("DELETE FROM topic_alert_message WHERE NOT EXISTS (SELECT 1 FROM topic_alert_delivery retained WHERE retained.alert_id=topic_alert_message.id AND retained.state IN ('pending','retry','queued')) AND id IN (SELECT id FROM topic_alert_message ORDER BY created_at_ms DESC,id LIMIT -1 OFFSET " + MAXIMUM_MESSAGES + ")");
            prune.executeUpdate("DELETE FROM topic_alert_delivery WHERE state NOT IN ('pending','retry','queued') AND NOT EXISTS (SELECT 1 FROM topic_alert_delivery retained WHERE retained.alert_id=topic_alert_delivery.alert_id AND retained.state IN ('pending','retry','queued')) AND id IN (SELECT id FROM topic_alert_delivery ORDER BY id DESC LIMIT -1 OFFSET " + MAXIMUM_DELIVERIES + ")");
            prune.executeUpdate("UPDATE topic_alert_delivery SET state='cancelled' WHERE device_id IS NULL AND state IN ('pending','retry')");
        }
    }

    private Connection open() throws SQLException
    {
        try { return SdrTrunkDatabase.open(mPath); }
        catch(IOException exception) { throw new SQLException("Alert database is unavailable", exception); }
    }
    private record Settings(long revision, TopicAlertConfiguration.PushSettings push,
                            @com.fasterxml.jackson.annotation.JsonProperty("builtin_action_button_enabled") boolean builtinActionButtonEnabled) {}
    public static final class RevisionConflictException extends SQLException
    {
        public RevisionConflictException() { super("Alert configuration changed; reload before saving"); }
    }
}
