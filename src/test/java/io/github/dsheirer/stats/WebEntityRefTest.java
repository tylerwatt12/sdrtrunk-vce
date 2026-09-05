/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WebEntityRefTest
{
    private static final String CHANNEL_ID = "728d2d66-de4e-476b-a696-919f32dd4d12";

    @Test
    void exposesOnlyTheCanonicalFieldsForEachClosedShape()
    {
        assertEquals(Map.of("kind", "radio_system", "key", "p25:bee00:49f"),
            WebEntityRef.radioSystem("p25:bee00:49f").toMap());
        assertEquals(Map.of("kind", "radio_system", "key", "dmr:tier3:tiny:511"),
            WebEntityRef.radioSystem("dmr:tier3:tiny:511").toMap());
        assertEquals(Map.of("kind", "radio_system", "key", "nxdn-c:regional:16383"),
            WebEntityRef.radioSystem("nxdn-c:regional:16383").toMap());
        assertEquals(Map.of("kind", "channel", "key", CHANNEL_ID),
            WebEntityRef.channel(CHANNEL_ID).toMap());
        assertEquals(Map.of("kind", "talkgroup", "radio_system_key", "dmr:channel:" + CHANNEL_ID,
                "identity_key", "v1-g-x-x-91"),
            WebEntityRef.talkgroup("dmr:channel:" + CHANNEL_ID, "v1-g-x-x-91").toMap());
        assertEquals(Map.of("kind", "patch_group", "radio_system_key", "p25:bee00:49f",
                "identity_key", "v1-p-bee00-49f-700"),
            WebEntityRef.patchGroup("p25:bee00:49f", "v1-p-bee00-49f-700").toMap());
        assertEquals(Map.of("kind", "radio", "radio_system_key", "nxdn-d:channel:" + CHANNEL_ID,
                "identity_key", "v1-r-x-x-1201"),
            WebEntityRef.radio("nxdn-d:channel:" + CHANNEL_ID, "v1-r-x-x-1201").toMap());
    }

    @Test
    void rejectsPartialOrInvalidReferencesAndOmitsAnUnresolvedReference()
    {
        assertThrows(IllegalArgumentException.class, () -> WebEntityRef.radioSystem(" "));
        assertThrows(IllegalArgumentException.class, () -> WebEntityRef.radioSystem("p25:bee00:49f "));
        assertThrows(IllegalArgumentException.class, () -> WebEntityRef.radioSystem("p25:bee00:49F"));
        assertThrows(IllegalArgumentException.class, () -> WebEntityRef.radioSystem("dmr:tier3:tiny:512"));
        assertThrows(IllegalArgumentException.class, () -> WebEntityRef.radioSystem("nxdn-c:global:1024"));
        assertThrows(IllegalArgumentException.class,
            () -> WebEntityRef.radioSystem("dmr:channel:728D2D66-DE4E-476B-A696-919F32DD4D12"));
        assertThrows(IllegalArgumentException.class, () -> WebEntityRef.channel("not-a-channel-uuid"));
        assertThrows(IllegalArgumentException.class, () -> WebEntityRef.channel("1-1-1-1-1"));
        assertThrows(IllegalArgumentException.class,
            () -> WebEntityRef.channel("728D2D66-DE4E-476B-A696-919F32DD4D12"));
        assertThrows(IllegalArgumentException.class, () -> WebEntityRef.channel("not-a-uuid"));
        assertThrows(IllegalArgumentException.class,
            () -> WebEntityRef.radio("scope", "v1-r-x-x-1201"));
        assertThrows(IllegalArgumentException.class,
            () -> WebEntityRef.radio("nxdn-d:channel:" + CHANNEL_ID, "v1-r-x-x-0"));
        assertThrows(IllegalArgumentException.class,
            () -> WebEntityRef.radio("nxdn-d:channel:" + CHANNEL_ID, "v1-g-x-x-1"));

        Map<String,Object> row = new LinkedHashMap<>();
        WebEntityRef.put(row, null);
        assertFalse(row.containsKey(WebEntityRef.FIELD));
    }
}
