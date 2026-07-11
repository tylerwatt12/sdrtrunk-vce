package io.github.dsheirer.jmbe.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VersionTest
{
    @Test
    void comparesEqualVersionsAsEqual()
    {
        Version version = Version.fromString("v1.0.12");
        assertEquals(version, Version.fromString("1.0.12"));
        assertEquals(0, version.compareTo(Version.fromString("v1.0.12")));
        assertTrue(Version.fromString("v1.0.12a").compareTo(version) > 0);
    }

    @Test
    void rejectsMalformedVersions()
    {
        assertNull(Version.fromString("v1x0x12"));
        assertNull(Version.fromString("v1.0.12alpha"));
    }
}
