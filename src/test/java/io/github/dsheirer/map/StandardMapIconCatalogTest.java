/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.InputStream;
import org.junit.jupiter.api.Test;

class StandardMapIconCatalogTest
{
    @Test
    void allAllowedIconsAreBundledPngsAndUnknownAliasNamesFallBack() throws Exception
    {
        for(StandardMapIconCatalog icon: StandardMapIconCatalog.values())
        {
            assertSame(icon, StandardMapIconCatalog.forAliasName(icon.aliasName()));
            assertSame(icon, StandardMapIconCatalog.forSlug(icon.slug()));
            assertNotNull(getClass().getClassLoader().getResource(icon.resourcePath()));
            try(InputStream image = getClass().getClassLoader().getResourceAsStream(icon.resourcePath()))
            {
                assertNotNull(image);
                assertArrayEquals(new byte[]{(byte)0x89, 'P', 'N', 'G', 13, 10, 26, 10}, image.readNBytes(8));
            }
        }
        assertSame(StandardMapIconCatalog.NO_ICON, StandardMapIconCatalog.forAliasName("custom/user/path.png"));
        assertNull(StandardMapIconCatalog.forSlug("../../etc/passwd"));
    }
}
