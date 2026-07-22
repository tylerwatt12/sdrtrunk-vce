/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.Inet6Address;
import java.util.List;
import org.junit.jupiter.api.Test;

class WebListenAddressTest
{
    @Test
    void providesCanonicalLoopbackDefault()
    {
        assertEquals("127.0.0.1:8090", WebListenAddress.DEFAULT.toString());
        assertEquals(WebListenAddress.DEFAULT, WebListenAddress.defaults());
        assertEquals("127.0.0.1", WebListenAddress.DEFAULT.host());
        assertEquals(8090, WebListenAddress.DEFAULT.port());
        assertFalse(WebListenAddress.DEFAULT.isIpv6Literal());
    }

    @Test
    void parsesAndCanonicalizesHostnamesAndIpv4WithoutResolvingThem()
    {
        assertEquals("receiver-01.example.com:443",
            WebListenAddress.parse("Receiver-01.Example.COM.:443").toString());
        assertEquals("localhost:1", WebListenAddress.parse("LOCALHOST:1").toString());
        assertEquals("192.168.64.84:65535", WebListenAddress.parse("192.168.64.84:65535").toString());

        WebListenAddress unresolved = WebListenAddress.parse("does-not-exist.invalid:8090");
        assertEquals("does-not-exist.invalid", unresolved.host());
    }

    @Test
    void parsesAndCanonicalizesBracketedIpv6()
    {
        WebListenAddress loopback = WebListenAddress.parse("[0:0:0:0:0:0:0:1]:8090");
        assertEquals("[::1]:8090", loopback.toString());
        assertTrue(loopback.isIpv6Literal());
        assertEquals("[2001:db8::ff00:42:8329]:443",
            WebListenAddress.parse("[2001:0DB8:0000:0000:0000:FF00:0042:8329]:443").toString());
        assertEquals("[::]:1", WebListenAddress.parse("[::]:1").toString());
    }

    @Test
    void resolvesOnlyThroughExplicitBindResolution() throws Exception
    {
        assertTrue(WebListenAddress.parse("127.0.0.1:8090").resolveBindHost().isLoopbackAddress());
        assertTrue(WebListenAddress.parse("[::1]:8090").resolveBindHost() instanceof Inet6Address);
    }

    @Test
    void rejectsSchemesPathsMissingPiecesInvalidPortsAndUnbracketedIpv6()
    {
        List<String> invalid = List.of(
            "",
            " localhost:8090",
            "localhost:8090 ",
            "http://localhost:8090",
            "localhost:8090/path",
            "localhost:8090?query",
            "user@localhost:8090",
            "localhost",
            ":8090",
            "localhost:",
            "localhost:0",
            "localhost:65536",
            "localhost:+8090",
            "localhost:８０９０",
            "localhost:port",
            "::1:8090",
            "[::1]8090",
            "[::1]:",
            "[::1]:8090/path",
            "[127.0.0.1]:8090",
            "[fe80::1%en0]:8090",
            "[invalid]:8090",
            "999.1.1.1:8090",
            "127.0.0:8090",
            "127.00.0.1:8090",
            "-receiver:8090",
            "receiver_:8090",
            "receiver..local:8090"
        );

        assertThrows(IllegalArgumentException.class, () -> WebListenAddress.parse(null));

        for(String value: invalid)
        {
            assertThrows(IllegalArgumentException.class, () -> WebListenAddress.parse(value), value);
        }
    }

    @Test
    void implementsValueEqualityOnCanonicalForm()
    {
        assertEquals(WebListenAddress.parse("LOCALHOST:8090"), WebListenAddress.parse("localhost.:8090"));
        assertEquals(WebListenAddress.parse("[::1]:8090"),
            WebListenAddress.parse("[0:0:0:0:0:0:0:1]:8090"));
    }
}
