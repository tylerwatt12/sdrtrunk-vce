/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.List;

/** Adjacent conversion from the frozen version-15 contract; retired values are intentionally dropped. */
final class Format72WebUserPreferencesCodec
{
    private static final ObjectMapper JSON = new ObjectMapper();
    private Format72WebUserPreferencesCodec() {}

    static String migrateFromFormat71(String source) throws IOException
    {
        Format67WebUserPreferencesCodec.validate(source);
        ObjectNode target = (ObjectNode)JSON.readTree(source);
        target.put("version", 16);
        ((ObjectNode)target.get("presentation")).remove(List.of(
            "show_control_decode_quality", "show_voice_decode_quality"));
        // Removing fields only shrinks the already validated bounded document.
        return JSON.writeValueAsString(target);
    }

    static String defaults() throws IOException
    {
        return migrateFromFormat71(Format67WebUserPreferencesCodec.defaults());
    }
}
