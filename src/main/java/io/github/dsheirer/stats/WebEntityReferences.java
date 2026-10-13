/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats;

import java.util.Map;
import java.util.Set;

/** Public bridge to the same closed navigation references used by the statistics pages. */
public final class WebEntityReferences
{
    private WebEntityReferences() { }

    public static Map<String,Object> channel(String id) { return WebEntityRef.channel(id).toMap(); }
    public static Map<String,Object> radioSystem(String key) { return WebEntityRef.radioSystem(key).toMap(); }
    public static Map<String,Object> identity(String kind, String system, String identity)
    {
        return switch(kind)
        {
            case "radio" -> WebEntityRef.radio(system, identity).toMap();
            case "talkgroup" -> WebEntityRef.talkgroup(system, identity).toMap();
            case "patch_group" -> WebEntityRef.patchGroup(system, identity).toMap();
            default -> throw new IllegalArgumentException("Unsupported identity destination");
        };
    }

    public static Map<String,Object> validate(Map<String,Object> value)
    {
        if(value == null || !(value.get("kind") instanceof String kind))
            throw new IllegalArgumentException("Invalid action destination");
        if(Set.of("channel", "radio_system").contains(kind) && value.keySet().equals(Set.of("kind", "key")) &&
            value.get("key") instanceof String key)
            return kind.equals("channel") ? channel(key) : radioSystem(key);
        if(value.keySet().equals(Set.of("kind", "radio_system_key", "identity_key")) &&
            value.get("radio_system_key") instanceof String system && value.get("identity_key") instanceof String identity)
            return identity(kind, system, identity);
        throw new IllegalArgumentException("Invalid action destination");
    }
}
