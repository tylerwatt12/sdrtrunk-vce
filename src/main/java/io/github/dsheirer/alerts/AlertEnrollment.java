/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.alerts;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

/** Pending anonymous pairing is distinct from a completed device subscription. */
public record AlertEnrollment(String id, @JsonProperty("remote_enrollment_id") String remoteEnrollmentId,
                               @JsonProperty("manage_token_hash") String manageTokenHash,
                               @JsonProperty("topic_ids") List<String> topicIds, boolean paused,
                               @JsonProperty("quiet_start") String quietStart,
                               @JsonProperty("quiet_end") String quietEnd,
                               @JsonProperty("time_zone") String timeZone,
                               @JsonProperty("created_at_ms") long createdAtMs,
                               @JsonProperty("expires_at_ms") long expiresAtMs, String status,
                               @JsonProperty("device_id") String deviceId,
                               @JsonProperty("enrollment_url") String enrollmentUrl, String code)
{
    public AlertEnrollment
    {
        id = AlertRule.text(id, "Enrollment ID", 80, false);
        remoteEnrollmentId = AlertRule.text(remoteEnrollmentId, "Remote enrollment ID", 240, false);
        manageTokenHash = AlertRule.text(manageTokenHash, "Management token hash", 128, false);
        if(!manageTokenHash.matches("[a-fA-F0-9]{64}")) throw new IllegalArgumentException("Invalid management token hash");
        topicIds = topicIds != null ? List.copyOf(topicIds.stream().distinct().toList()) : List.of();
        if(topicIds.size() > 256 || createdAtMs <= 0 || expiresAtMs <= createdAtMs ||
            !List.of("pending", "completed", "expired", "cancelled").contains(status))
            throw new IllegalArgumentException("Invalid enrollment state");
        quietStart = quietStart != null ? quietStart : ""; quietEnd = quietEnd != null ? quietEnd : "";
        if(quietStart.isEmpty() != quietEnd.isEmpty()) throw new IllegalArgumentException("Quiet hours require both times");
        if(!quietStart.isEmpty()) { LocalTime.parse(quietStart); LocalTime.parse(quietEnd); }
        timeZone = timeZone != null ? timeZone : "UTC"; ZoneId.of(timeZone);
        deviceId = deviceId != null ? deviceId : "";
        enrollmentUrl = AlertRule.text(enrollmentUrl, "Enrollment URL", 2048, true);
        code = AlertRule.text(code, "Enrollment code", 240, true);
    }

    public AlertEnrollment(String id, String remoteEnrollmentId, String manageTokenHash, List<String> topicIds,
                           boolean paused, String quietStart, String quietEnd, String timeZone,
                           long createdAtMs, long expiresAtMs, String status, String deviceId)
    {
        this(id, remoteEnrollmentId, manageTokenHash, topicIds, paused, quietStart, quietEnd, timeZone,
            createdAtMs, expiresAtMs, status, deviceId, "", "");
    }
}
