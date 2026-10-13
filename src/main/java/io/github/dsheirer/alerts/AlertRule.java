/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.alerts;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

/** Typed rule configuration. IDs and protocol facts select the source; labels are presentation only. */
public record AlertRule(String id, String name, @JsonProperty("topic_id") String topicId, boolean enabled,
                        String trigger, @JsonProperty("system_key") String systemKey,
                        @JsonProperty("site_id") String siteId, @JsonProperty("channel_id") String channelId,
                        @JsonProperty("protocol_family") String protocolFamily, String protocol, Condition conditions, Timing timing, Schedule schedule,
                        @JsonProperty("cooldown_minutes") int cooldownMinutes,
                        @JsonProperty("repeat_key") String repeatKey,
                        @JsonProperty("notify_recovery") boolean notifyRecovery,
                        @JsonProperty("title_template") String titleTemplate,
                        @JsonProperty("body_template") String bodyTemplate,
                        @JsonProperty("click_action") String clickAction)
{
    public static final Set<String> TRIGGERS = Set.of("call", "emergency", "paging", "affiliation", "command", "health", "tone", "dcs", "event");
    public static final Set<String> FIELDS = Set.of("radio", "target_radio", "talkgroup", "alias_id", "channel_id", "system_key", "site_id", "protocol", "event_type", "encrypted", "emergency", "duration_seconds", "tone", "dcs", "issue", "severity", "state");
    public static final Set<String> OPERATORS = Set.of("is", "not", "in", "contains", "gte", "lte", "exists");

    public AlertRule
    {
        id = text(id, "Rule ID", 80, false); name = text(name, "Rule name", 100, false);
        topicId = text(topicId, "Rule topic", 80, false);
        if(!TRIGGERS.contains(trigger)) throw new IllegalArgumentException("Unsupported alert trigger");
        systemKey = text(systemKey, "System key", 240, true); siteId = text(siteId, "Site ID", 80, true);
        channelId = text(channelId, "Channel ID", 80, true); protocol = text(protocol, "Protocol", 40, true);
        protocolFamily = text(protocolFamily, "Protocol family", 16, true);
        if(!protocolFamily.isEmpty() && !AlertProtocolFamily.FAMILIES.contains(protocolFamily))
            throw new IllegalArgumentException("Choose a supported protocol family");
        if("health".equals(trigger) && (!systemKey.isEmpty() || !siteId.isEmpty() || !channelId.isEmpty() || !protocol.isEmpty() || !protocolFamily.isEmpty()))
            throw new IllegalArgumentException("Health rules use issue conditions; receiver identity scope is unavailable");
        conditions = conditions != null ? conditions : Condition.all();
        conditions.validate(0, new int[]{0});
        timing = timing != null ? timing : Timing.immediate();
        if("sustained".equals(timing.mode()) && !"health".equals(trigger))
            throw new IllegalArgumentException("Sustained timing requires a receiver health condition");
        schedule = schedule != null ? schedule : Schedule.always();
        if(cooldownMinutes < 0 || cooldownMinutes > 10080) throw new IllegalArgumentException("Invalid repeat limit");
        repeatKey = repeatKey != null ? repeatKey : "rule";
        if(!Set.of("rule", "radio", "talkgroup", "pair", "channel").contains(repeatKey))
            throw new IllegalArgumentException("Unknown repeat key");
        titleTemplate = text(titleTemplate, "Notification title", 128, false);
        bodyTemplate = text(bodyTemplate, "Notification text", 2048, false);
        clickAction = clickAction != null ? clickAction : "details";
        if(!Set.of("details", "listen").contains(clickAction)) throw new IllegalArgumentException("Unknown click action");
    }

    public AlertRule(String id, String name, String topicId, boolean enabled, String trigger, String systemKey,
                     String siteId, String channelId, String protocol, Condition conditions, Timing timing, Schedule schedule,
                     int cooldownMinutes, String repeatKey, boolean notifyRecovery, String titleTemplate,
                     String bodyTemplate, String clickAction)
    {
        this(id, name, topicId, enabled, trigger, systemKey, siteId, channelId, "", protocol, conditions, timing, schedule,
            cooldownMinutes, repeatKey, notifyRecovery, titleTemplate, bodyTemplate, clickAction);
    }

    static String text(String value, String label, int maximum, boolean optional)
    {
        String result = value != null ? value.strip() : "";
        if(result.length() > maximum || (!optional && result.isEmpty()) || result.indexOf('\u0000') >= 0)
            throw new IllegalArgumentException(label + " is missing or too long");
        return result;
    }

    public record Condition(String op, List<Condition> items, String field, String operator, String value)
    {
        public Condition
        {
            items = items != null ? List.copyOf(items) : List.of();
            op = op != null ? op : ""; field = field != null ? field : "";
            operator = operator != null ? operator : ""; value = value != null ? value : "";
        }
        public static Condition all() { return new Condition("all", List.of(), "", "", ""); }
        void validate(int depth, int[] count)
        {
            if(depth > 8 || ++count[0] > 128) throw new IllegalArgumentException("Conditions exceed their limit");
            if(!op.isEmpty())
            {
                if(!Set.of("all", "any", "none").contains(op) || !field.isEmpty() || !operator.isEmpty())
                    throw new IllegalArgumentException("Invalid condition group");
                if(items.isEmpty() && (depth > 0 || !"all".equals(op)))
                    throw new IllegalArgumentException("Remove empty condition groups");
                for(Condition item: items) item.validate(depth + 1, count);
            }
            else if(!items.isEmpty() || !FIELDS.contains(field) || !OPERATORS.contains(operator) ||
                (!"exists".equals(operator) && value.isBlank()) || value.length() > 512)
                throw new IllegalArgumentException("Invalid condition");
        }
    }

    public record Timing(String mode, int minutes, int count, @JsonProperty("window_minutes") int windowMinutes)
    {
        public Timing
        {
            mode = mode != null ? mode : "immediate";
            if(!Set.of("immediate", "quiet", "count", "sustained").contains(mode))
                throw new IllegalArgumentException("Unsupported alert timing");
            if(minutes < 0 || minutes > 10080 || count < 0 || count > 10000 || windowMinutes < 0 || windowMinutes > 1440 ||
                (!"immediate".equals(mode) && !"count".equals(mode) && minutes == 0) ||
                ("count".equals(mode) && (count == 0 || windowMinutes == 0)))
                throw new IllegalArgumentException("Invalid alert timing value");
        }
        public static Timing immediate() { return new Timing("immediate", 0, 0, 0); }
    }

    public record Schedule(String mode, List<Integer> days, String start, String end,
                           @JsonProperty("time_zone") String timeZone)
    {
        public Schedule
        {
            mode = mode != null ? mode : "always";
            days = days != null ? List.copyOf(days) : List.of(0, 1, 2, 3, 4, 5, 6);
            start = start != null ? start : "00:00"; end = end != null ? end : "00:00";
            timeZone = timeZone != null ? timeZone : "UTC";
            if(!Set.of("always", "custom").contains(mode) || days.stream().anyMatch(d -> d == null || d < 0 || d > 6) ||
                ("custom".equals(mode) && days.isEmpty())) throw new IllegalArgumentException("Invalid active schedule");
            LocalTime.parse(start); LocalTime.parse(end); ZoneId.of(timeZone);
        }
        public static Schedule always() { return new Schedule("always", null, null, null, "UTC"); }
    }
}
