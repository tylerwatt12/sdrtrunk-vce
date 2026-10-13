/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.alerts;

import com.fasterxml.jackson.annotation.JsonProperty;

/** A stable receiver-owned topic. Visibility controls both guest access and notification delivery. */
public record AlertTopic(String id, String name, String description, boolean visible, boolean enabled,
                         @JsonProperty("built_in") boolean builtIn)
{
    public static final String ADMINISTRATOR_TOPIC_ID = "administrator-alerts";

    public AlertTopic
    {
        id = AlertRule.text(id, "Topic ID", 80, false);
        name = AlertRule.text(name, "Topic name", 80, false);
        description = AlertRule.text(description, "Topic description", 240, true);
        if(builtIn && (!ADMINISTRATOR_TOPIC_ID.equals(id) || !"Administrator alerts".equals(name)))
            throw new IllegalArgumentException("The built-in topic identity cannot be changed");
        if(ADMINISTRATOR_TOPIC_ID.equals(id) && !builtIn)
            throw new IllegalArgumentException("The administrator topic is built in");
    }

    public static AlertTopic administratorAlerts()
    {
        return new AlertTopic(ADMINISTRATOR_TOPIC_ID, "Administrator alerts",
            "Tuner, USB, channel, computer, recording, and streaming issues and recovery updates.", false, true, true);
    }
}
