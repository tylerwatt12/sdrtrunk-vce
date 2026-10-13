/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.alerts;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.HashSet;
import java.util.List;

/** Bounded administrator configuration. Push credentials are kept under a separate private settings key. */
public record TopicAlertConfiguration(long revision, PushSettings push, List<AlertTopic> topics, List<AlertRule> rules)
{
    public TopicAlertConfiguration
    {
        if(revision < 1) throw new IllegalArgumentException("Alert revision must be positive");
        push = push != null ? push : PushSettings.defaults();
        topics = topics != null ? List.copyOf(topics) : List.of(AlertTopic.administratorAlerts());
        rules = rules != null ? List.copyOf(rules) : List.of();
        if(topics.size() > 256 || rules.size() > 512) throw new IllegalArgumentException("Too many topics or rules");
        HashSet<String> ids = new HashSet<>();
        for(AlertTopic topic: topics) if(!ids.add(topic.id())) throw new IllegalArgumentException("Duplicate topic ID");
        if(topics.stream().noneMatch(t -> t.builtIn() && AlertTopic.ADMINISTRATOR_TOPIC_ID.equals(t.id())))
            throw new IllegalArgumentException("The built-in Administrator alerts topic is required");
        HashSet<String> ruleIds = new HashSet<>();
        for(AlertRule rule: rules)
        {
            if(!ruleIds.add(rule.id())) throw new IllegalArgumentException("Duplicate rule ID");
            if(!ids.contains(rule.topicId())) throw new IllegalArgumentException("Rule topic does not exist");
        }
    }

    public static TopicAlertConfiguration defaults()
    {
        return new TopicAlertConfiguration(1, PushSettings.defaults(), List.of(AlertTopic.administratorAlerts()), List.of());
    }

    public record PushSettings(boolean enabled, String mode, @JsonProperty("site_url") String siteUrl,
                               @JsonProperty("sender_label") String senderLabel)
    {
        public PushSettings
        {
            mode = mode != null ? mode : "proxy";
            if(!List.of("direct", "proxy").contains(mode)) throw new IllegalArgumentException("Unknown push mode");
            siteUrl = AlertRule.text(siteUrl, "Site URL", 2048, true);
            senderLabel = AlertRule.text(senderLabel != null ? senderLabel : "VCE installation", "Sender label", 80, false);
        }
        public static PushSettings defaults() { return new PushSettings(false, "proxy", "", "VCE installation"); }
    }
}
