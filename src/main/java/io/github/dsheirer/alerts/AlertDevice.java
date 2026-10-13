/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.alerts;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

/** Private transport record; guest API responses must explicitly omit destination material and token hashes. */
public record AlertDevice(String id, String name, String method,
                          @JsonProperty("remote_device_id") String remoteDeviceId,
                          String endpoint, String p256dh, String auth,
                          @JsonProperty("manage_token_hash") String manageTokenHash,
                          @JsonProperty("topic_ids") List<String> topicIds, boolean paused,
                          @JsonProperty("quiet_start") String quietStart,
                          @JsonProperty("quiet_end") String quietEnd,
                          @JsonProperty("time_zone") String timeZone,
                          @JsonProperty("subscription_expired") boolean subscriptionExpired)
{
    public AlertDevice
    {
        id = AlertRule.text(id, "Device ID", 80, false); name = AlertRule.text(name, "Device name", 80, true);
        if(!List.of("direct", "proxy").contains(method)) throw new IllegalArgumentException("Unknown device method");
        remoteDeviceId = AlertRule.text(remoteDeviceId, "Remote device ID", 240, true);
        endpoint = AlertRule.text(endpoint, "Push endpoint", 2048, true);
        p256dh = AlertRule.text(p256dh, "Push public key", 160, true); auth = AlertRule.text(auth, "Push auth", 160, true);
        manageTokenHash = AlertRule.text(manageTokenHash, "Management token hash", 128, false);
        if(!manageTokenHash.matches("[a-fA-F0-9]{64}")) throw new IllegalArgumentException("Invalid management token hash");
        if("proxy".equals(method) && remoteDeviceId.isEmpty() || "direct".equals(method) &&
            (endpoint.isEmpty() || p256dh.isEmpty() || auth.isEmpty())) throw new IllegalArgumentException("Incomplete device destination");
        topicIds = topicIds != null ? List.copyOf(topicIds.stream().distinct().toList()) : List.of();
        if(topicIds.size() > 256) throw new IllegalArgumentException("Too many topic subscriptions");
        quietStart = AlertRule.text(quietStart, "Quiet start", 5, true); quietEnd = AlertRule.text(quietEnd, "Quiet end", 5, true);
        if(quietStart.isEmpty() != quietEnd.isEmpty()) throw new IllegalArgumentException("Quiet hours require both times");
        if(!quietStart.isEmpty()) { LocalTime.parse(quietStart); LocalTime.parse(quietEnd); }
        timeZone = timeZone != null ? timeZone : "UTC"; ZoneId.of(timeZone);
    }

    public AlertDevice(String id, String name, String method, String remoteDeviceId, String endpoint, String p256dh,
                       String auth, String manageTokenHash, List<String> topicIds, boolean paused,
                       String quietStart, String quietEnd, String timeZone)
    {
        this(id, name, method, remoteDeviceId, endpoint, p256dh, auth, manageTokenHash, topicIds, paused,
            quietStart, quietEnd, timeZone, false);
    }
}
