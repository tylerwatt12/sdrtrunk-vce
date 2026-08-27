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
        assertEquals(Map.of("kind", "system", "key", "p25:BEE00:49F:alias-list:1"),
            WebEntityRef.system("p25:BEE00:49F:alias-list:1").toMap());
        assertEquals(Map.of("kind", "site", "key", "728d2d66-de4e-476b-a696-919f32dd4d12"),
            WebEntityRef.site("728d2d66-de4e-476b-a696-919f32dd4d12").toMap());
        assertEquals(Map.of("kind", "conventional", "key", "728d2d66-de4e-476b-a696-919f32dd4d12"),
            WebEntityRef.conventional("728d2d66-de4e-476b-a696-919f32dd4d12").toMap());
        assertEquals(Map.of("kind", "talkgroup", "scope", "dmr:guid:site", "id", 91),
            WebEntityRef.talkgroup("dmr:guid:site", 91).toMap());
        assertEquals(Map.of("kind", "patch_group", "scope", "p25:scope", "id", 700),
            WebEntityRef.patchGroup("p25:scope", 700).toMap());
        assertEquals(Map.of("kind", "radio", "scope", "nxdn:guid:site", "id", 1201),
            WebEntityRef.radio("nxdn:guid:site", 1201).toMap());
    }

    @Test
    void rejectsPartialOrInvalidReferencesAndOmitsAnUnresolvedReference()
    {
        assertThrows(IllegalArgumentException.class, () -> WebEntityRef.system(" "));
        assertThrows(IllegalArgumentException.class, () -> WebEntityRef.site("site-guid"));
        assertThrows(IllegalArgumentException.class, () -> WebEntityRef.site("1-1-1-1-1"));
        assertThrows(IllegalArgumentException.class,
            () -> WebEntityRef.site("728D2D66-DE4E-476B-A696-919F32DD4D12"));
        assertThrows(IllegalArgumentException.class, () -> WebEntityRef.conventional("not-a-uuid"));
        assertThrows(IllegalArgumentException.class, () -> WebEntityRef.radio("scope", 0));

        Map<String,Object> row = new LinkedHashMap<>();
        WebEntityRef.put(row, null);
        assertFalse(row.containsKey(WebEntityRef.FIELD));
    }
}
