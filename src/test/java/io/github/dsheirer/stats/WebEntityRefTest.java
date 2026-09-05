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
    @Test
    void exposesOnlyTheCanonicalFieldsForEachClosedShape()
    {
        assertEquals(Map.of("kind", "radio_system", "key", "p25:bee00:49f"),
            WebEntityRef.radioSystem("p25:bee00:49f").toMap());
        assertEquals(Map.of("kind", "channel", "key", "728d2d66-de4e-476b-a696-919f32dd4d12"),
            WebEntityRef.channel("728d2d66-de4e-476b-a696-919f32dd4d12").toMap());
        assertEquals(Map.of("kind", "talkgroup", "radio_system_key", "dmr:channel:test",
                "identity_key", "v1-g-x-x-91"),
            WebEntityRef.talkgroup("dmr:channel:test", "v1-g-x-x-91").toMap());
        assertEquals(Map.of("kind", "patch_group", "radio_system_key", "p25:scope",
                "identity_key", "v1-p-bee00-49f-700"),
            WebEntityRef.patchGroup("p25:scope", "v1-p-bee00-49f-700").toMap());
        assertEquals(Map.of("kind", "radio", "radio_system_key", "nxdn:channel:test",
                "identity_key", "v1-r-x-x-1201"),
            WebEntityRef.radio("nxdn:channel:test", "v1-r-x-x-1201").toMap());
    }

    @Test
    void rejectsPartialOrInvalidReferencesAndOmitsAnUnresolvedReference()
    {
        assertThrows(IllegalArgumentException.class, () -> WebEntityRef.radioSystem(" "));
        assertThrows(IllegalArgumentException.class, () -> WebEntityRef.channel("site-guid"));
        assertThrows(IllegalArgumentException.class, () -> WebEntityRef.channel("1-1-1-1-1"));
        assertThrows(IllegalArgumentException.class,
            () -> WebEntityRef.channel("728D2D66-DE4E-476B-A696-919F32DD4D12"));
        assertThrows(IllegalArgumentException.class, () -> WebEntityRef.channel("not-a-uuid"));
        assertThrows(IllegalArgumentException.class, () -> WebEntityRef.radio("scope", "v1-r-x-x-0"));
        assertThrows(IllegalArgumentException.class,
            () -> WebEntityRef.radio("scope", "v1-g-x-x-1"));

        Map<String,Object> row = new LinkedHashMap<>();
        WebEntityRef.put(row, null);
        assertFalse(row.containsKey(WebEntityRef.FIELD));
    }
}
