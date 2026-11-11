/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.List;

/** Adjacent conversion from the frozen version-15 contract; retired values are intentionally dropped. */
final class Format72WebUserPreferencesCodec
{
    private static final ObjectMapper JSON = new ObjectMapper(com.fasterxml.jackson.core.JsonFactory.builder()
        .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
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

    /** Frozen version-16 validation for format 72 after newer database formats are introduced. */
    static void validate(String source) throws IOException
    {
        if(source == null || source.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 131072 ||
            !(JSON.readTree(source) instanceof ObjectNode root) || !root.path("version").isIntegralNumber() ||
            !root.path("version").canConvertToInt() || root.path("version").intValue() != 16 ||
            !(root.get("presentation") instanceof ObjectNode presentation) ||
            presentation.has("show_control_decode_quality") || presentation.has("show_voice_decode_quality"))
            throw new IOException("Invalid version-16 preferences");
        root.put("version", 15);
        presentation.put("show_control_decode_quality", false);
        presentation.put("show_voice_decode_quality", false);
        Format67WebUserPreferencesCodec.validateStructure(root);
    }

    static String defaults() throws IOException
    {
        return migrateFromFormat71(Format67WebUserPreferencesCodec.defaults());
    }
}
