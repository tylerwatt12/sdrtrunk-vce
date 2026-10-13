/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.alerts;

import com.fasterxml.jackson.annotation.JsonProperty;

/** One durable notification destination. Provider acceptance is distinct from relay queueing or an observed open. */
public record AlertDelivery(long id, @JsonProperty("alert_id") String alertId,
                             @JsonProperty("topic_id") String topicId,
                             @JsonProperty("topic_name") String topicName,
                             @JsonProperty("alert_name") String alertName, String title, String body,
                             @JsonProperty("click_action") String clickAction,
                             @JsonProperty("device_id") String deviceId,
                             @JsonProperty("remote_device_id") String remoteDeviceId, String method, String state,
                             @JsonProperty("created_at_ms") long createdAtMs,
                             @JsonProperty("expires_at_ms") long expiresAtMs, int attempts,
                             @JsonProperty("remote_id") String remoteId, String error)
{
}
